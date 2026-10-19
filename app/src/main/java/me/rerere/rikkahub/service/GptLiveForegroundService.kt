package me.rerere.rikkahub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import org.koin.android.ext.android.inject
import org.koin.core.context.GlobalContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val LIVE_SERVICE_TAG = "GptLiveFgs"

/**
 * Owns the Android foreground lifetime, not the voice session. An in-memory session token ties
 * every start, acknowledgement and notification action to one explicit user-initiated call.
 */
class GptLiveForegroundService : Service() {
    companion object {
        private const val ACTION_ACQUIRE = "me.rerere.rikkahub.action.GPT_LIVE_ACQUIRE"
        private const val ACTION_RELEASE = "me.rerere.rikkahub.action.GPT_LIVE_RELEASE"
        private const val ACTION_MUTE = "me.rerere.rikkahub.action.GPT_LIVE_MUTE"
        private const val ACTION_END = "me.rerere.rikkahub.action.GPT_LIVE_END"
        private const val EXTRA_SESSION_TOKEN = "gpt_live_session_token"
        private const val EXTRA_CONVERSATION_ID = "gpt_live_conversation_id"
        private const val CHANNEL_ID = "gpt_live_voice"
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = 5 * 60 * 1000L
        const val NOTIFICATION_ID = 2005

        // Never persist this set. A redelivered intent after process death cannot reopen the mic.
        private val requestedSessions = ConcurrentHashMap.newKeySet<String>()

        /** Submit while the activity is visible. Success is not the foreground-ready ACK. */
        fun acquire(context: Context, sessionToken: String, conversationId: Uuid): Boolean {
            requestedSessions.add(sessionToken)
            val intent = Intent(context, GptLiveForegroundService::class.java).apply {
                action = ACTION_ACQUIRE
                putExtra(EXTRA_SESSION_TOKEN, sessionToken)
                putExtra(EXTRA_CONVERSATION_ID, conversationId.toString())
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.onFailure {
                requestedSessions.remove(sessionToken)
                Log.e(LIVE_SERVICE_TAG, "Could not start the voice foreground service", it)
                reportStartFailure(sessionToken)
            }.getOrDefault(false)
        }

        fun release(context: Context, sessionToken: String) {
            // Revoke immediately, even if ACQUIRE is still queued in Android's main thread.
            requestedSessions.remove(sessionToken)
            val intent = Intent(context, GptLiveForegroundService::class.java).apply {
                action = ACTION_RELEASE
                putExtra(EXTRA_SESSION_TOKEN, sessionToken)
            }
            runCatching { context.startService(intent) }.onFailure {
                // There may be no service left after a failed start or process teardown. Never
                // call stopService as a fallback: it could stop a newer token's foreground service.
                Log.w(LIVE_SERVICE_TAG, "Could not deliver the voice release request", it)
            }
        }

        private fun reportStartFailure(sessionToken: String) {
            val koin = GlobalContext.getOrNull() ?: return
            runCatching {
                koin.get<AppScope>().launch {
                    koin.get<GptLiveSessionManager>().onForegroundFailure(
                        sessionToken,
                        "Could not start background voice. Open the app, allow microphone access, and try again.",
                    )
                }
            }.onFailure { Log.w(LIVE_SERVICE_TAG, "Could not report foreground startup failure", it) }
        }
    }

    private val manager: GptLiveSessionManager by inject()
    private val appScope: AppScope by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessionToken: String? = null
    private var conversationId: String? = null
    private var lastStartId = 0
    private var isForeground = false
    private var callStartedAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockJob: Job? = null
    private var notificationJob: Job? = null
    private var channelFailure: String? = null

    override fun onCreate() {
        super.onCreate()
        runCatching {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "GPT Live voice", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Controls for an ongoing voice conversation"
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }.onFailure {
            channelFailure = "Could not create the voice conversation notification."
            Log.e(LIVE_SERVICE_TAG, "Could not create the voice notification channel", it)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_ACQUIRE -> acquireSession(intent, startId)
            ACTION_RELEASE -> releaseSession(intent.getStringExtra(EXTRA_SESSION_TOKEN), startId)
            ACTION_MUTE -> withCurrentSession(intent, startId) { token -> manager.toggleMuted(token) }
            ACTION_END -> withCurrentSession(intent, startId) { token -> manager.stop(token) }
            else -> if (sessionToken == null) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val token = sessionToken
        sessionToken = null
        conversationId = null
        token?.let(requestedSessions::remove)
        notificationJob?.cancel()
        wakeLockJob?.cancel()
        releaseWakeLock()
        serviceScope.cancel()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        // Use AppScope: cancelling serviceScope must not discard the cleanup notification.
        token?.let { appScope.launch { manager.onForegroundDestroyed(it) } }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val token = sessionToken
        Log.e(LIVE_SERVICE_TAG, "Voice foreground service timed out (type=$fgsType)")
        token?.let {
            requestedSessions.remove(it)
            manager.onForegroundFailure(it, "Android stopped background voice because its service timed out.")
        }
        releaseWakeLock()
        // Android requires the foreground service to stop within seconds of this callback.
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForeground = false
        stopSelf()
    }

    private fun acquireSession(intent: Intent, startId: Int) {
        val incoming = intent.getStringExtra(EXTRA_SESSION_TOKEN)
        val incomingConversation = intent.getStringExtra(EXTRA_CONVERSATION_ID)
        val state = manager.state.value
        val valid = incoming != null && incomingConversation != null &&
            incoming in requestedSessions && state.sessionToken == incoming &&
            state.conversationId?.toString() == incomingConversation && state.isActive

        // Even a cancelled startForegroundService request must enter foreground promptly. Do not
        // stop a newer active token or remove its notification when an old ACQUIRE arrives late.
        val keepExisting = sessionToken != null && state.sessionToken == sessionToken && state.isActive
        val notificationToken = if (valid) {
            incoming
        } else if (keepExisting) {
            sessionToken
        } else {
            null
        }
        val notificationConversation = if (valid) {
            incomingConversation
        } else if (keepExisting) {
            conversationId
        } else {
            null
        }
        val notification = buildNotification(notificationToken, notificationConversation, state)
        try {
            channelFailure?.let { error(it) }
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    0
                },
            )
            isForeground = true
        } catch (e: Exception) {
            Log.e(LIVE_SERVICE_TAG, "Could not enter the voice foreground service", e)
            incoming?.let {
                requestedSessions.remove(it)
                manager.onForegroundFailure(
                    it,
                    "Could not start background voice. Open the app, allow microphone access, and try again.",
                )
            }
            if (!keepExisting) stopSelf(startId)
            return
        }
        if (!valid) {
            incoming?.let(requestedSessions::remove)
            if (!keepExisting) stopSelf(startId)
            return
        }

        val token = checkNotNull(incoming)
        if (sessionToken != token) {
            val previous = sessionToken
            sessionToken = token
            conversationId = incomingConversation
            callStartedAt = System.currentTimeMillis()
            previous?.let {
                requestedSessions.remove(it)
                manager.onForegroundDestroyed(it)
            }
        }
        try {
            ensureWakeLock()
        } catch (e: Exception) {
            Log.e(LIVE_SERVICE_TAG, "Could not keep voice audio awake", e)
            manager.onForegroundFailure(token, "Could not keep the voice conversation active in the background.")
            releaseSession(token, startId)
            return
        }
        observeNotification()
        // This ACK is the only point at which the session may request focus and open the microphone.
        if (token in requestedSessions) manager.onForegroundReady(token)
    }

    private fun releaseSession(token: String?, startId: Int) {
        if (token == null || sessionToken != token) {
            if (sessionToken == null) stopSelf(startId)
            return
        }
        requestedSessions.remove(token)
        sessionToken = null
        conversationId = null
        notificationJob?.cancel()
        notificationJob = null
        wakeLockJob?.cancel()
        wakeLockJob = null
        releaseWakeLock()
        // The manager requested this release or has already left the session. Unexpected
        // destruction still reports the currently held token from onDestroy().
        // Only stop if Android has no newer start queued. A newer ACQUIRE must still be allowed
        // to satisfy its startForegroundService obligation, even after this call was cancelled.
        stopSelf(startId)
    }

    private fun withCurrentSession(intent: Intent, startId: Int, action: (String) -> Unit) {
        val token = intent.getStringExtra(EXTRA_SESSION_TOKEN)
        if (token != null && token == sessionToken && token in requestedSessions &&
            manager.state.value.sessionToken == token && manager.state.value.isActive
        ) {
            action(token)
        } else if (sessionToken == null) {
            // A stale notification may recreate the Service; it cannot recreate a session.
            stopSelf(startId)
        }
    }

    private fun observeNotification() {
        if (notificationJob?.isActive == true) return
        notificationJob = serviceScope.launch {
            manager.state
                .map { state ->
                    NotificationState(
                        token = state.sessionToken,
                        active = state.isActive,
                        muted = state.muted,
                        changing = state.microphoneChanging,
                        text = notificationText(state),
                    )
                }
                .distinctUntilChanged()
                .collect { state ->
                    val token = sessionToken ?: return@collect
                    if (state.token != token || !state.active || token !in requestedSessions) {
                        releaseSession(token, lastStartId)
                    } else {
                        runCatching {
                            getSystemService(NotificationManager::class.java).notify(
                                NOTIFICATION_ID,
                                buildNotification(token, conversationId, manager.state.value),
                            )
                        }.onFailure {
                            Log.w(LIVE_SERVICE_TAG, "Could not update the voice notification", it)
                        }
                    }
                }
        }
    }

    private fun ensureWakeLock() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rikkahub:GptLive")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        if (wakeLockJob?.isActive == true) return
        wakeLockJob = serviceScope.launch {
            while (isActive) {
                delay(WAKE_LOCK_RENEW_MS)
                val token = sessionToken ?: break
                if (token !in requestedSessions) break
                runCatching { lock.acquire(WAKE_LOCK_TIMEOUT_MS) }.onFailure {
                    manager.onForegroundFailure(token, "Android could not keep background voice awake.")
                    releaseSession(token, lastStartId)
                }
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    private fun buildNotification(token: String?, conversation: String?, state: LiveVoiceState): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle("GPT Live voice")
            .setContentText(if (token == null) "Ending voice conversation…" else notificationText(state))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (token != null && conversation != null) {
            builder.setContentIntent(conversationIntent(token, conversation))
            if (callStartedAt > 0) builder.setWhen(callStartedAt).setUsesChronometer(true)
            if (!state.microphoneChanging && state.phase != LiveVoicePhase.Closing) {
                builder.addAction(
                    android.R.drawable.ic_btn_speak_now,
                    if (state.muted) "Unmute" else "Mute",
                    actionIntent(token, ACTION_MUTE),
                )
            }
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "End", actionIntent(token, ACTION_END))
        }
        return builder.build()
    }

    private fun conversationIntent(token: String, conversation: String): PendingIntent {
        val intent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = tokenUri(token, "open")
            putExtra("conversationId", conversation)
        }
        return PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun actionIntent(token: String, actionName: String): PendingIntent {
        val intent = Intent(this, GptLiveForegroundService::class.java).apply {
            action = actionName
            data = tokenUri(token, actionName)
            putExtra(EXTRA_SESSION_TOKEN, token)
        }
        return PendingIntent.getService(
            this,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun tokenUri(token: String, action: String): Uri = Uri.Builder()
        .scheme("rikkahub-gpt-live")
        .authority("voice")
        .appendPath(token)
        .appendPath(action)
        .build()

    private fun notificationText(state: LiveVoiceState): String = when {
        state.phase == LiveVoicePhase.Connecting -> "Connecting…"
        state.phase == LiveVoicePhase.Closing -> "Ending voice conversation…"
        state.microphoneChanging -> "Updating microphone…"
        state.muted && state.playing -> "Speaking · microphone muted"
        state.muted -> "Microphone muted"
        state.playing -> "Speaking · microphone on"
        state.pendingDelegations > 0 -> "Assistant is working · microphone on"
        else -> "Listening · microphone on"
    }

    private data class NotificationState(
        val token: String?,
        val active: Boolean,
        val muted: Boolean,
        val changing: Boolean,
        val text: String,
    )
}
