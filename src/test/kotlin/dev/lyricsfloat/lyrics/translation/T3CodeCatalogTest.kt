package dev.lyricsfloat.lyrics.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class T3CodeCatalogTest {
    // Trimmed from a real server.getConfig reply.
    private val config = translationJson.parseToJsonElement(
        """
        {"providers": [
          {"instanceId": "claudeAgent", "displayName": "Claude", "enabled": true, "status": "ready", "models": [
            {"slug": "claude-haiku-5-5", "name": "Claude Haiku 5.5", "capabilities": {"optionDescriptors": [
              {"id": "effort", "label": "Reasoning", "type": "select", "options": [
                {"id": "low", "label": "Low"}, {"id": "medium", "label": "Medium", "isDefault": true},
                {"id": "ultrathink", "label": "Ultrathink"}],
               "promptInjectedValues": ["ultrathink"]}]}},
            {"slug": "claude-haiku-4-5", "name": "Claude Haiku 4.5", "capabilities": {"optionDescriptors": [
              {"id": "thinking", "label": "Thinking", "type": "boolean"}]}}]},
          {"instanceId": "codex", "displayName": "Codex", "enabled": true, "status": "ready", "models": [
            {"slug": "gpt-6.1-sol", "name": "GPT-6.1-Sol", "capabilities": {"optionDescriptors": [
              {"id": "reasoningEffort", "label": "Reasoning", "type": "select", "currentValue": "low", "options": [
                {"id": "low", "label": "Low", "isDefault": true}, {"id": "high", "label": "High"}]},
              {"id": "serviceTier", "label": "Service Tier", "type": "select", "options": [
                {"id": "default", "label": "Standard"}]}]}}]},
          {"instanceId": "cursor", "displayName": "Cursor", "enabled": false, "status": "disabled", "models": []},
          {"instanceId": "pi", "displayName": "Pi", "enabled": true, "status": "error", "models": []}
        ]}
        """,
    )

    @Test
    fun keepsEnabledProvidersWithModels() {
        val providers = T3CodeClient.parseCatalog(config)
        assertEquals(listOf("claudeAgent", "codex"), providers.map { it.instanceId })
        assertEquals(true, providers[0].ready)
    }

    @Test
    fun readsReasoningOptions() {
        val claude = T3CodeClient.parseCatalog(config)[0]
        val haiku = claude.models[0]
        assertEquals("effort", haiku.effortOptionId)
        assertEquals(listOf("low", "medium"), haiku.efforts.map { it.first })
        assertEquals("medium", haiku.defaultEffort)

        val noSelect = claude.models[1]
        assertNull(noSelect.effortOptionId)
        assertEquals(emptyList(), noSelect.efforts)
    }

    @Test
    fun prefersCurrentValueAndIgnoresOtherSelects() {
        val sol = T3CodeClient.parseCatalog(config)[1].models.single()
        assertEquals("reasoningEffort", sol.effortOptionId)
        assertEquals("low", sol.defaultEffort)
        assertEquals(listOf("low" to "Low", "high" to "High"), sol.efforts)
    }
}
