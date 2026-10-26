package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.mergeCustomHeaders
import me.rerere.live.LiveSessionConfig
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import okhttp3.HttpUrl.Companion.toHttpUrl

private const val LIVE_CONTEXT_CHUNK_BYTES = 400
private const val LIVE_CONTEXT_MAX_CHUNKS = 20

internal fun Model?.isGptLiveModel(provider: ProviderSetting?): Boolean =
    provider is ProviderSetting.OpenAI &&
        (this?.modelId == "gpt-live-1" || this?.modelId == "openai/gpt-live-1")

internal fun createGptLiveConfig(
    model: Model,
    provider: ProviderSetting.OpenAI,
    assistant: Assistant,
    conversation: Conversation,
    keyRoulette: KeyRoulette,
    backendEnabled: Boolean = false,
): LiveSessionConfig {
    require(model.isGptLiveModel(provider)) { "This model does not support GPT Live voice conversations." }
    val baseUrl = provider.baseUrl.trim().trimEnd('/').toHttpUrl()
    require(baseUrl.isHttps) { "实时语音需要 HTTPS 提供商地址。" }
    val endpoint = baseUrl.newBuilder()
        .addPathSegments("live/sessions")
        .query(null)
        .fragment(null)
        .build()
        .toString()
        .replaceFirst("https://", "wss://")
    val modelId = when (baseUrl.host) {
        "ai-gateway.vercel.sh" -> "openai/gpt-live-1"
        "api.openai.com" -> "gpt-live-1"
        else -> model.modelId
    }
    val headers = provider.mergeCustomHeaders(assistant.customHeaders + model.customHeaders)
    val systemPrompt = if (assistant.allowConversationSystemPrompt) {
        conversation.customSystemPrompt?.takeIf { it.isNotBlank() } ?: assistant.systemPrompt
    } else {
        assistant.systemPrompt
    }
    val instructions = listOf(
        systemPrompt,
        "Have a natural voice conversation. Keep spoken replies concise and use the user's language.",
        if (backendEnabled) {
            "Delegate substantive questions, searches and tasks to the client assistant. " +
                "Briefly acknowledge while waiting, then convey the returned result without inventing details. " +
                "Tool approval may require the user to approve in the chat."
        } else {
            "No tools are connected to this voice session; do not claim to have performed searches or actions."
        },
    ).filter { it.isNotBlank() }.joinToString("\n\n")
    return LiveSessionConfig(
        url = endpoint,
        apiKey = keyRoulette.next(provider.apiKey, provider.id.toString()),
        model = modelId,
        instructions = instructions,
        headers = headers.names().associateWith { headers[it].orEmpty() },
        context = conversation.gptLiveContext(assistant.contextMessageLimit),
        backendEnabled = backendEnabled,
    )
}

/** Only visible text from the latest selected branch is sent; attachment data stays out of context. */
internal fun Conversation.gptLiveContext(messageLimit: Int): List<String> {
    val maxCharacters = LIVE_CONTEXT_CHUNK_BYTES * LIVE_CONTEXT_MAX_CHUNKS
    val messages = currentMessages.let { messages ->
        val checkpoint = messages.indexOfLast { it.isContextCheckpoint }
        if (checkpoint >= 0) messages.drop(checkpoint) else messages
    }.let { if (messageLimit > 0) it.takeLast(messageLimit) else it }
    val chunks = ArrayDeque<String>()
    for (message in messages.asReversed()) {
        if (message.role != MessageRole.USER && message.role != MessageRole.ASSISTANT) continue
        val text = message.toText().trim().takeLast(maxCharacters)
            .dropWhile { Character.isLowSurrogate(it) }
        if (text.isBlank()) continue
        val role = "${message.role.name}: "
        val chunksForMessage = text.utf8Chunks(LIVE_CONTEXT_CHUNK_BYTES - role.toByteArray(Charsets.UTF_8).size)
        for (chunk in chunksForMessage.asReversed()) {
            if (chunks.size == LIVE_CONTEXT_MAX_CHUNKS) return chunks.toList()
            chunks.addFirst(role + chunk)
        }
    }
    return chunks.toList()
}

/** The Live context limit is in UTF-8 bytes; never split a Unicode code point. */
private fun String.utf8Chunks(maxBytes: Int): List<String> = buildList {
    val chunk = StringBuilder()
    var bytes = 0
    var offset = 0
    while (offset < length) {
        val point = codePointAt(offset)
        val characters = String(Character.toChars(point))
        val size = characters.toByteArray(Charsets.UTF_8).size
        if (bytes + size > maxBytes) {
            add(chunk.toString())
            chunk.clear()
            bytes = 0
        }
        chunk.append(characters)
        bytes += size
        offset += Character.charCount(point)
    }
    if (chunk.isNotEmpty()) add(chunk.toString())
}
