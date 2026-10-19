package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class GptLiveConfigTest {
    private val assistant = Assistant(systemPrompt = "Speak gently.")
    private val conversation = Conversation(assistantId = assistant.id, messageNodes = emptyList())
    private val model = Model(modelId = "gpt-live-1")
    private val provider = ProviderSetting.OpenAI(baseUrl = "https://ai-gateway.vercel.sh/v1", apiKey = "key")

    @Test
    fun `only explicit GPT Live model identifiers support live voice`() {
        assertTrue(model.isGptLiveModel(provider))
        assertTrue(model.copy(modelId = "openai/gpt-live-1").isGptLiveModel(provider))
        assertFalse(model.copy(modelId = "gpt-realtime").isGptLiveModel(provider))
        assertFalse(model.copy(modelId = "gpt-live-1-preview").isGptLiveModel(provider))
        assertFalse(model.isGptLiveModel(ProviderSetting.Google()))
        assertFalse(model.isGptLiveModel(null))
        assertFalse(null.isGptLiveModel(provider))
    }

    @Test
    fun `Vercel uses live sessions websocket and qualified model`() {
        val config = createGptLiveConfig(model, provider, assistant, conversation, KeyRoulette.default())
        assertEquals("wss://ai-gateway.vercel.sh/v1/live/sessions", config.url)
        assertEquals("openai/gpt-live-1", config.model)
        assertEquals("key", config.apiKey)
    }

    @Test
    fun `native OpenAI uses unqualified model and custom proxy paths are preserved`() {
        val native = createGptLiveConfig(
            model.copy(modelId = "openai/gpt-live-1"),
            provider.copy(baseUrl = "https://api.openai.com/v1/"),
            assistant,
            conversation,
            KeyRoulette.default(),
        )
        assertEquals("wss://api.openai.com/v1/live/sessions", native.url)
        assertEquals("gpt-live-1", native.model)
        val proxy = createGptLiveConfig(
            model,
            provider.copy(baseUrl = "https://proxy.example/proxy/v1/"),
            assistant,
            conversation,
            KeyRoulette.default(),
        )
        assertEquals("wss://proxy.example/proxy/v1/live/sessions", proxy.url)
        assertEquals("gpt-live-1", proxy.model)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `live configuration rejects an insecure provider endpoint`() {
        createGptLiveConfig(
            model,
            provider.copy(baseUrl = "http://proxy.example/v1"),
            assistant,
            conversation,
            KeyRoulette.default(),
        )
    }

    @Test
    fun `header overrides are case insensitive and model wins over assistant and provider`() {
        val config = createGptLiveConfig(
            model.copy(customHeaders = listOf(CustomHeader("X-Route", "model"))),
            provider.copy(
                customHeaders = listOf(CustomHeader("x-route", "provider"), CustomHeader("X-Region", "west"))
            ),
            assistant.copy(
                customHeaders = listOf(CustomHeader("X-ROUTE", "assistant"), CustomHeader("X-User", "owner"))
            ),
            conversation,
            KeyRoulette.default(),
        )
        assertEquals(3, config.headers.size)
        assertEquals("model", config.headers.entries.single { it.key.equals("x-route", true) }.value)
        assertEquals("west", config.headers["X-Region"])
        assertEquals("owner", config.headers["X-User"])
    }

    @Test
    fun `key roulette receives original key list and provider identity`() {
        val keys = "first, second\nthird"
        val keyRoulette = object : KeyRoulette {
            override fun next(keys: String, providerId: String): String {
                assertEquals("first, second\nthird", keys)
                assertEquals(provider.id.toString(), providerId)
                return "second"
            }
        }
        val config = createGptLiveConfig(model, provider.copy(apiKey = keys), assistant, conversation, keyRoulette)
        assertEquals("second", config.apiKey)
    }

    @Test
    fun `conversation system prompt follows assistant permission and adds honest voice guidance`() {
        val custom = conversation.copy(customSystemPrompt = "Use Mandarin.")
        val inherited = createGptLiveConfig(model, provider, assistant, custom, KeyRoulette.default())
        assertTrue(inherited.instructions.startsWith("Speak gently."))
        val overridden = createGptLiveConfig(
            model,
            provider,
            assistant.copy(allowConversationSystemPrompt = true),
            custom,
            KeyRoulette.default(),
        )
        assertTrue(overridden.instructions.startsWith("Use Mandarin."))
        assertTrue(overridden.instructions.contains("No tools are connected"))
    }

    @Test
    fun `context contains only selected visible text and respects message limit`() {
        val history = conversation.copy(messageNodes = listOf(
            MessageNode(messages = listOf(message("old"))),
            MessageNode(messages = listOf(message("discarded branch"), message("selected branch")), selectIndex = 1),
            MessageNode(messages = listOf(UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("reply"), UIMessagePart.Image("data:image/png;base64,SECRET")),
            ))),
        ))
        assertEquals(listOf("USER: selected branch", "ASSISTANT: reply"), history.gptLiveContext(2))
        assertFalse(history.gptLiveContext(0).joinToString().contains("SECRET"))
    }

    @Test
    fun `context is bounded to recent 400 byte chunks and starts at checkpoints`() {
        val history = conversation.copy(messageNodes = listOf(
            MessageNode(messages = listOf(message("before checkpoint"))),
            MessageNode(messages = listOf(message("summary").copy(isContextCheckpoint = true))),
            MessageNode(messages = listOf(message("z".repeat(20_000) + "recent ending"))),
        ))
        val context = history.gptLiveContext(0)
        assertEquals(20, context.size)
        assertTrue(context.all { it.toByteArray(Charsets.UTF_8).size <= 400 })
        assertTrue(context.joinToString("").endsWith("recent ending"))
        assertFalse(context.joinToString("").contains("before checkpoint"))
        val short = history.copy(messageNodes = history.messageNodes.take(2)).gptLiveContext(0)
        assertEquals(listOf("USER: summary"), short)
    }

    @Test
    fun `Chinese and emoji context survives byte chunking without dropped or split characters`() {
        val text = "你好🙂这是一段中文语音对话🌏".repeat(100)
        val history = conversation.copy(messageNodes = listOf(MessageNode(messages = listOf(message(text)))))
        val context = history.gptLiveContext(0)
        assertTrue(context.size in 2..20)
        assertTrue(context.all { it.toByteArray(Charsets.UTF_8).size <= 400 })
        val recovered = context.joinToString("") { it.removePrefix("USER: ") }
        assertEquals(text, recovered)
        assertEquals(text, String(recovered.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    private fun message(text: String) = UIMessage(
        id = Uuid.random(),
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )
}
