package com.openminis.app.ui.chat

import com.openminis.app.agent.AgentSubagentRuntime
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.sandbox.ExecutionCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** UI/child-session adapter; no budget, monitoring, registry or completion decisions. */
internal class ChatSubagentChild(private val vm: ChatViewModel, private val id: String,
    private val pool: BrowserTabPool) : AgentSubagentRuntime.Child {
    override val modelName get() = vm.modelName.value
    override val compacting get() = vm.isCompacting
    override val streaming get() = vm.isStreaming
    override suspend fun ready() = withTimeoutOrNull(5_000L) { vm.activeEntryId.first { it != null }; true } ?: false
    override suspend fun submit(prompt: String): String? = withContext(Dispatchers.Main) {
        val outcome = vm.submitPrompt(prompt)
        if (outcome is ChatViewModel.SubmitOutcome.Sent) null else outcome.toString()
    }
    override suspend fun submitResume(prompt: String) = withContext(Dispatchers.Main) {
        when (vm.submitPrompt(prompt)) {
            ChatViewModel.SubmitOutcome.Sent -> AgentSubagentRuntime.Submission.SENT
            ChatViewModel.SubmitOutcome.Queued -> AgentSubagentRuntime.Submission.QUEUED
            ChatViewModel.SubmitOutcome.Compacting -> AgentSubagentRuntime.Submission.COMPACTING
            is ChatViewModel.SubmitOutcome.Rejected -> AgentSubagentRuntime.Submission.REJECTED
        }
    }
    override suspend fun cancel() = withContext(Dispatchers.Main) {
        vm.cancelCompactBeforeSend()
        vm.cancelCompact()
        if (vm.isStreaming.value) vm.cancelStream()
    }
    override suspend fun settled() { vm.awaitAgentRunSettlement() }
    override fun wrapUp() { vm.helperWrapUpRequested = true }
    override fun steer(message: String) = vm.enqueueSteer(message)
    override fun missedSteers() = vm.drainMissedSteers()
    override suspend fun releaseTabs() { pool.releaseTabs(id) }
    override fun stopTool() { ExecutionCoordinator.stopCurrentCommand(id) }
    override fun snapshot() = snapshotOf(vm)

    companion object {
        fun snapshotOf(vm: ChatViewModel): AgentSubagentRuntime.Snapshot {
            val messages = mergeStreamingOverlay(vm.messages.value, vm.streamingById.value)
            val assistant = messages.lastOrNull { it.role == "assistant" }
            val tool = assistant?.toolBlocks?.lastOrNull { it.kind == "tool_use" }
            val text = assistant?.toolBlocks?.filter { it.kind == "text" }?.joinToString("\n") { it.content }
                ?.ifBlank { null } ?: assistant?.content.orEmpty()
            return AgentSubagentRuntime.Snapshot(tool?.toolName.orEmpty(), tool?.toolTitle.orEmpty(), text,
                vm.messages.value.count { it.role == "assistant" }, vm.messages.value.lastOrNull { it.role == "assistant" }?.error)
        }
    }
}
