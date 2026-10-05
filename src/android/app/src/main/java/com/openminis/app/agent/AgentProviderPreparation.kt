package com.openminis.app.agent

import android.content.Context
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import kotlinx.coroutines.*

/** Legacy OAuth refresh is request-owned; a refreshed instance cannot replace a newer user selection. */
internal class AgentProviderPreparation(private val context: Context, private val providers: ProviderRepository) {
    suspend fun prepare(provider: LLMProvider, entryId: String?, session: String,
        bind: (LLMProvider, LLMProvider) -> Unit): LLMProvider {
        currentCoroutineContext().ensureActive()
        if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth != true) return provider
        val entry = entryId?.let { id -> providers.config.value.modelEntries.find { it.id == id } } ?: return provider
        val instance = providers.instance(entry.providerInstanceId) ?: return provider
        return try {
            val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
            val token = manager?.validAccessToken() ?: return provider
            if (token == providers.loadApiKey(instance.id)) provider else {
                providers.saveApiKey(instance.id, token)
                val refreshed = ProviderFactory.create(instance, token, provider.model, context, sessionId = session,
                    overrides = (provider as? com.openminis.app.provider.openai.OpenAIProvider)?.modelOverrides)
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.Main) { bind(provider, refreshed) }
                refreshed
            }
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (failure is CancellationException && failure.cause == null) throw failure
            AppLogger.warning("AgentProviderPreparation", "OAuth refresh failed type=${failure.javaClass.simpleName}")
            provider
        }
    }
    fun prompt(provider: LLMProvider, base: String?): String? {
        if ((provider as? com.openminis.app.provider.anthropic.AnthropicProvider)?.isOAuth != true) return base
        val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
        return if (base?.startsWith(prefix) == true) base else "$prefix\n\n${base.orEmpty()}"
    }
}
