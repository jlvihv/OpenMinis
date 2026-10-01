package com.openminis.app.sandbox.offload

import org.junit.Assert.*
import org.junit.Test

class PhoneToolsOffloadHandlerTest {
    @Test fun acceptsSingleNumbers() {
        assertTrue(PhoneToolsOffloadHandler.validSms("+8613800138000", "你好"))
        assertTrue(PhoneToolsOffloadHandler.validSms("12345", "hello"))
    }
    @Test fun rejectsRecipientListsAndUriInjection() {
        for (number in listOf("123,456", "123;456", "123 456", "sms:123", "123?body=x", "", "+", "123\n456")) {
            assertFalse(number, PhoneToolsOffloadHandler.validSms(number, "hello"))
        }
    }
    @Test fun boundsMessageSize() {
        assertFalse(PhoneToolsOffloadHandler.validSms("12345", ""))
        assertFalse(PhoneToolsOffloadHandler.validSms("12345", "   "))
        assertTrue(PhoneToolsOffloadHandler.validSms("12345", "a".repeat(1000)))
        assertFalse(PhoneToolsOffloadHandler.validSms("12345", "a".repeat(1001)))
    }
    @Test fun captureFilenamesAreUniqueAndInsideAttachments() {
        val paths = (1..100).map { PhoneToolsOffloadHandler.capturePath("m4a") }
        assertEquals(100, paths.toSet().size)
        paths.forEach {
            assertTrue(it.startsWith("/var/minis/attachments/"))
            assertTrue(it.endsWith(".m4a"))
            java.util.UUID.fromString(it.substringAfterLast('/').removeSuffix(".m4a"))
        }
        assertTrue(PhoneToolsOffloadHandler.capturePath("jpg").endsWith(".jpg"))
    }
    @Test fun cooldownBlocksLoopsAndClockRollback() {
        assertTrue(PhoneToolsOffloadHandler.smsCooldownElapsed(100_000, 0))
        assertFalse(PhoneToolsOffloadHandler.smsCooldownElapsed(100_000, 100_000))
        assertFalse(PhoneToolsOffloadHandler.smsCooldownElapsed(159_999, 100_000))
        assertTrue(PhoneToolsOffloadHandler.smsCooldownElapsed(160_000, 100_000))
        assertFalse(PhoneToolsOffloadHandler.smsCooldownElapsed(99_999, 100_000))
    }
    @Test fun smsListIsBoundedAndPaginated() {
        val query = PhoneToolsOffloadHandler.smsReadQuery(OffloadArgs(listOf("list", "--limit", "100", "--offset", "50", "--type", "inbox")))
        assertEquals(100, query.limit)
        assertEquals(50, query.offset)
        assertEquals("type = ?", query.selection)
        assertEquals(listOf("1"), query.values)
        val default = PhoneToolsOffloadHandler.smsReadQuery(OffloadArgs(listOf("list")))
        assertEquals(50, default.limit)
        assertNull(default.selection)
        assertRejected { PhoneToolsOffloadHandler.smsReadQuery(OffloadArgs(listOf("list", "--limit", "101"))) }
        assertRejected { PhoneToolsOffloadHandler.smsReadQuery(OffloadArgs(listOf("list", "--offset=-1"))) }
    }
    @Test fun smsSearchBindsUntrustedValuesAndEscapesWildcards() {
        val query = PhoneToolsOffloadHandler.smsReadQuery(OffloadArgs(listOf("search", "--query", "50%_off", "--number", "x' OR 1=1--")))
        assertFalse(query.selection!!.contains("OR 1=1"))
        assertEquals("x' OR 1=1--", query.values[0])
        assertEquals("%50\\%\\_off%", query.values[1])
        assertRejected { PhoneToolsOffloadHandler.smsReadQuery(OffloadArgs(listOf("search"))) }
    }
    @Test fun waitingDefaultsToNewInboxSmsWithoutFilters() {
        val query = PhoneToolsOffloadHandler.smsReadQuery(OffloadArgs(listOf("wait")), now = 123456L)
        assertEquals("type = ? AND date >= ?", query.selection)
        assertEquals(listOf("1", "123456"), query.values)
        assertEquals(1, query.limit)
        assertEquals(0, query.offset)
        val explicit = PhoneToolsOffloadHandler.smsReadQuery(OffloadArgs(listOf("wait", "--after", "1970-01-01T00:00:01Z")), now = 123456L)
        assertEquals(listOf("1", "1000"), explicit.values)
        assertRejected { PhoneToolsOffloadHandler.smsReadQuery(OffloadArgs(listOf("wait", "--number", "123"))) }
        assertRejected { PhoneToolsOffloadHandler.smsReadQuery(OffloadArgs(listOf("wait", "--query", "code"))) }
    }
    @Test fun statsCountsInboxSenderNumbersWithoutBodies() {
        val stats = SmsStatsCounter()
        stats.add(" 123 ", 1, 0)
        stats.add("123", 1, 1)
        stats.add("456", 1, 1)
        stats.add("789", 2, 1)
        stats.add(null, 1, 0)
        stats.add("456", 3, 0)
        assertEquals(6L, stats.total)
        assertEquals(4L, stats.inbox)
        assertEquals(1L, stats.sent)
        assertEquals(2L, stats.unread)
        assertEquals(1L, stats.unknownSender)
        assertEquals(mapOf("123" to 2L, "456" to 1L), stats.senders)
        val query = PhoneToolsOffloadHandler.smsReadQuery(OffloadArgs(listOf("stats")))
        assertNull(query.selection)
    }
    @Test fun smsGetAndDatesAreValidated() {
        val query = PhoneToolsOffloadHandler.smsReadQuery(OffloadArgs(listOf("get", "--id", "42")))
        assertEquals("_id = ?", query.selection)
        assertEquals(listOf("42"), query.values)
        assertEquals(1, query.limit)
        assertRejected { PhoneToolsOffloadHandler.smsReadQuery(OffloadArgs(listOf("get", "--id", "42 OR 1=1"))) }
        assertRejected { PhoneToolsOffloadHandler.smsReadQuery(OffloadArgs(listOf("list", "--after", "2026-10-02T00:00:00Z", "--before", "2026-10-01T00:00:00Z"))) }
    }
    @Test fun deletionScopesShellPermissionAndRestoresIt() {
        val command = PhoneToolsOffloadHandler.smsDeleteCommand(listOf(12, 34))
        assertTrue(command.contains("cmd appops get com.android.shell WRITE_SMS"))
        assertTrue(command.contains("trap 'restore' EXIT"))
        assertTrue(command.contains("cmd appops set com.android.shell WRITE_SMS allow"))
        assertTrue(command.contains("_id IN (12,34)"))
        assertTrue(command.contains("restore || exit 1"))
        assertRejected { PhoneToolsOffloadHandler.smsDeleteCommand(emptyList()) }
        assertRejected { PhoneToolsOffloadHandler.smsDeleteCommand(listOf(-1)) }
    }
    @Test fun deletionScriptRestoresPermissionOnSuccessAndFailure() {
        val dir = java.nio.file.Files.createTempDirectory("sms-delete-test").toFile()
        try {
            val state = java.io.File(dir, "mode").apply { writeText("ignore") }
            java.io.File(dir, "cmd").apply {
                writeText("""#!/bin/sh
                    case "${'$'}2" in
                      get) printf 'WRITE_SMS: %s; time=0\n' "${'$'}(cat '${state.absolutePath}')" ;;
                      set) printf '%s' "${'$'}5" > '${state.absolutePath}' ;;
                      *) exit 1 ;;
                    esac
                """.trimIndent())
                setExecutable(true)
            }
            val content = java.io.File(dir, "content")
            for (exit in listOf(0, 1)) {
                content.writeText("#!/bin/sh\nexit $exit\n"); content.setExecutable(true)
                val process = ProcessBuilder("sh", "-c", PhoneToolsOffloadHandler.smsDeleteCommand(listOf(12L)))
                    .apply { environment()["PATH"] = dir.absolutePath + ":" + environment()["PATH"] }
                    .start()
                assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(exit, process.exitValue())
                assertEquals("ignore", state.readText())
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun deletionAllowsLargeExplicitIdLists() {
        assertEquals(listOf(1L, 2L), PhoneToolsOffloadHandler.smsDeleteIds("1,2,1"))
        assertEquals(10_000, PhoneToolsOffloadHandler.smsDeleteIds((1..10_000).joinToString(",")).size)
        for (ids in listOf("", "all", "0", "-1", "1 OR 1=1", "1); DROP TABLE sms", "1,")) {
            assertRejected { PhoneToolsOffloadHandler.smsDeleteIds(ids) }
        }
    }
    private fun assertRejected(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: Exception) { }
    }
    @Test fun documentsEveryRegisteredCommand() {
        assertEquals(setOf("sms", "record", "camera", "share", "control"), PhoneToolsOffloadHandler.HELP.keys)
    }
}
