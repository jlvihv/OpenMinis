package com.openminis.app.sandbox.offload

/** Local aggregation only: never retain SMS bodies. Sender identities are exact trimmed addresses. */
internal class SmsStatsCounter {
    var total = 0L
        private set
    var inbox = 0L
        private set
    var sent = 0L
        private set
    var unread = 0L
        private set
    var unknownSender = 0L
        private set
    val senders = mutableMapOf<String, Long>()

    fun add(address: String?, type: Int, read: Int) {
        total++
        if (type == 2) sent++
        if (type != 1) return
        inbox++
        if (read == 0) unread++
        val number = address?.trim().orEmpty()
        if (number.isEmpty()) unknownSender++
        else senders[number] = (senders[number] ?: 0L) + 1
    }
}
