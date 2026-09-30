package com.openminis.app.service

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LiveUpdateContentTextTest {
    @Test fun `title is published only after its JSON string closes`() {
        val partial = "{\"tool_title\":\"检查项目文件"
        assertNull(completedToolTitle(partial))
        assertEquals("检查项目文件", completedToolTitle(partial + "\",\"command\":\"still streaming"))
        assertNull(completedToolTitle("{\"tool_title\":\" \"}"))
        assertEquals("检查\"配置\"", completedToolTitle("""{"tool_title":"检查\"配置\"","command":""""))
    }

    @Test fun `ready title survives fast execution until the next visible reply`() {
        val session = "live-title-test"
        SessionActivityTracker.setActive(session)
        try {
            SessionActivityTracker.publishToolTitle(session, "shell_execute", null)
            assertNull(SessionActivityTracker.pendingToolContent.value)
            SessionActivityTracker.publishToolTitle(session, "shell_execute", "检查项目文件")
            assertEquals("检查项目文件", SessionActivityTracker.pendingToolContent.value?.title)
            SessionActivityTracker.toolStarted()
            SessionActivityTracker.updateToolStatus("Running: shell_execute", "shell_execute", true, "检查项目文件")
            SessionActivityTracker.clearToolRunning()
            assertEquals("检查项目文件", SessionActivityTracker.pendingToolContent.value?.title)
            SessionActivityTracker.publishLiveReply(session, "## 找到了配置文件")
            assertNull(SessionActivityTracker.pendingToolContent.value)
            assertEquals("找到了配置文件", SessionActivityTracker.liveReplyPreview.value)
        } finally {
            SessionActivityTracker.setInactive(session)
            SessionActivityTracker.clearOverlayTasks()
        }
    }

    @Test fun `tool title wins over prior reply`() {
        assertEquals("检查项目文件", liveUpdateContent(AgentPhase.TOOL, "检查项目文件", "旧回复", "执行命令"))
        assertEquals("执行命令", liveUpdateContent(AgentPhase.TOOL, " ", "旧回复", "执行命令"))
    }

    @Test fun `reply and completion use visible content`() {
        for (phase in listOf(AgentPhase.GENERATING, AgentPhase.COMPLETED)) {
            assertEquals("发现三个问题", liveUpdateContent(phase, "旧工具", "发现三个问题", "默认状态"))
            assertEquals("默认状态", liveUpdateContent(phase, null, null, "默认状态"))
        }
    }

    @Test fun `thinking never exposes reasoning as a reply`() {
        assertEquals("思考中", liveUpdateContent(AgentPhase.THINKING, null, "旧回复", "思考中"))
    }

    @Test fun `latest markdown heading is used without formatting`() {
        assertEquals("修复编译问题", replyStatusText("# 检查环境\n正文\n## **修复编译问题**\n现在开始修复"))
    }

    @Test fun `plain reply follows latest nonempty line`() {
        assertEquals("正在检查依赖", replyStatusText("先看目录\n\n正在检查依赖\n"))
        assertEquals("查看文档", replyStatusText("[查看文档](https://example.com)"))
        assertNull(replyStatusText(" \n "))
        assertNull(replyStatusText(null))
    }

    @Test fun `large replies have bounded previews`() {
        val preview = replyStatusText("a".repeat(100_000))!!
        assertEquals(120, preview.length)
    }

    @Test fun `short chip text preserves content and truncates long titles`() {
        assertEquals("检查文件", chipContentText("检查文件"))
        assertEquals("正在检查…", chipContentText("正在检查项目中的配置文件"))
        assertEquals("检查 文件", chipContentText(" 检查\n 文件 "))
    }

    @Test fun `chip truncation does not split emoji surrogate pairs`() {
        val chip = chipContentText("😀".repeat(8))
        assertEquals("😀".repeat(4) + "…", chip)
        assertEquals(5, chip.codePointCount(0, chip.length))
    }

    @Test fun `streaming uses persisted session id and notification uses content not timer`() {
        val vm = File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt").readText()
        val service = File("src/main/java/com/openminis/app/service/AgentForegroundService.kt").readText()
        assertTrue(vm.contains("publishLiveReply(activeSessionId, activeSb)"))
        assertTrue(vm.contains("completedToolTitle(chunk.accumulated)"))
        val input = vm.substringAfter("is LLMStreamChunk.ToolInputDelta ->")
            .substringBefore("is LLMStreamChunk.ToolCallComplete ->")
        assertTrue(input.contains("publishToolTitle(activeSessionId, prev.toolName, completeTitle)"))
        assertTrue(service.contains("executingToolName ?: pendingTool?.name"))
        assertTrue(service.contains("val shortCritical = chipContentText(statusText)"))
        assertFalse(service.contains("val shortCritical = chipTimerText(elapsedMs)"))
    }
}
