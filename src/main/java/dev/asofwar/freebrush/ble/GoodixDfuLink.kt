package dev.asofwar.freebrush.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The DFU conversation over a connected [GattSession]: frames out on [GoodixDfu.RX] in MTU-sized pieces, answers in
 * on [GoodixDfu.TX]. One request at a time, as the chip expects.
 */
class GoodixDfuLink private constructor(
    private val s: GattSession,
    private val incoming: Channel<ByteArray>,
) : DfuTransport {
    private val assembler = GoodixDfu.Assembler()

    override suspend fun send(opcode: Int, data: ByteArray) {
        val frame = GoodixDfu.frame(opcode, data)
        val piece = (s.mtu - 3).coerceAtLeast(20)
        var at = 0
        while (at < frame.size) {
            val end = minOf(at + piece, frame.size)
            s.write(GoodixDfu.SERVICE, GoodixDfu.RX, frame.copyOfRange(at, end))
            at = end
        }
    }

    override suspend fun receive(opcode: Int, timeoutMs: Long): GoodixDfu.Reply? {
        val reply = withTimeoutOrNull(timeoutMs) {
            var r: GoodixDfu.Reply? = null
            while (r == null) r = assembler.feed(incoming.receive())
            r
        } ?: return null
        if (reply.opcode != opcode) throw GattException("DFU: ответ 0x%02x на команду 0x%02x".format(reply.opcode, opcode))
        return reply
    }

    companion object {
        /** Subscribes to the DFU answers on [s]; the link lives as long as [scope]. */
        suspend fun open(s: GattSession, scope: CoroutineScope, onStep: (String) -> Unit): GoodixDfuLink {
            if (!s.hasCharacteristic(GoodixDfu.SERVICE, GoodixDfu.TX) || !s.hasCharacteristic(GoodixDfu.SERVICE, GoodixDfu.RX)) {
                throw GattException("У щётки нет сервиса обновления Goodix")
            }
            // A bigger MTU cuts a 1 KB program frame into 5 writes instead of 52; the default still works.
            runCatching { s.requestMtu(247) }.onSuccess { onStep("DFU: MTU ${s.mtu}") }
            val incoming = Channel<ByteArray>(Channel.UNLIMITED)
            scope.async(start = CoroutineStart.UNDISPATCHED) {
                s.notifications.collect { if (it.characteristic == GoodixDfu.TX) incoming.trySend(it.value) }
            }
            if (!s.enableNotifications(GoodixDfu.SERVICE, GoodixDfu.TX)) throw GattException("DFU: не удалось подписаться на ответы")
            return GoodixDfuLink(s, incoming)
        }
    }
}
