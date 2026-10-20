package com.remoteesp32midi.glassmidi.midi

import com.remoteesp32midi.glassmidi.model.MidiMessage

/**
 * 标准 BLE-MIDI 编解码。
 *
 * 包结构：header（13-bit 时间戳高 6 位），然后每条消息一个 timestamp 字节
 * （低 7 位），再跟完整 MIDI 消息。这里始终写完整 status，不生成 running status。
 * 解码兼容同包 running status；跨包 running status 不合法，直接丢弃。
 */
class BleMidiCodec {

    fun encode(messages: List<MidiMessage>, timestampMs: Long): ByteArray {
        require(messages.isNotEmpty()) { "empty MIDI message list" }
        val timestamp = (timestampMs and 0x1FFF).toInt()
        val bytes = ArrayList<Byte>(messages.size * 4 + 2)
        bytes += (0x80 or ((timestamp shr 7) and 0x3F)).toByte()
        val lowTimestamp = (0x80 or (timestamp and 0x7F)).toByte()
        for (message in messages) {
            bytes += lowTimestamp
            message.toBytes().forEach { bytes += it }
        }
        return bytes.toByteArray()
    }

    fun decode(packet: ByteArray): List<MidiMessage> {
        if (packet.isEmpty()) return emptyList()
        require(packet[0].toInt() and 0xC0 == 0x80) { "missing BLE-MIDI header" }

        val messages = ArrayList<MidiMessage>()
        var index = 1
        var runningStatus: Int? = null

        while (index < packet.size) {
            val timestampByte = packet[index].toInt() and 0xFF
            require(timestampByte and 0x80 != 0) { "missing message timestamp" }
            index++

            val first = packet.readRawByte(index, "message start")
            if (first and 0x80 != 0) {
                index++
                val status = first
                require(status in 0x80..0xEF) { "unsupported status: 0x${status.toString(16)}" }
                val dataLength = dataLengthFor(status) - 1
                val data = IntArray(dataLength)
                for (i in data.indices) {
                    data[i] = packet.readDataByte(index + i, "status 0x${status.toString(16)} data")
                }
                index += dataLength
                runningStatus = status
                messages += MidiMessage(
                    status = status,
                    data1 = data[0],
                    data2 = data.getOrNull(1),
                )
            } else {
                val status = runningStatus
                    ?: throw IllegalArgumentException("running status without previous status")
                val dataLength = dataLengthFor(status) - 1
                val data = IntArray(dataLength)
                data[0] = first
                index++
                for (i in 1 until dataLength) {
                    data[i] = packet.readDataByte(index, "running status data")
                    index++
                }
                messages += MidiMessage(
                    status = status,
                    data1 = data[0],
                    data2 = data.getOrNull(1),
                )
            }
        }
        return messages
    }

    private fun dataLengthFor(status: Int): Int = when (status and 0xF0) {
        0xC0, 0xD0 -> 2
        else -> 3
    }

    private fun ByteArray.readRawByte(index: Int, what: String): Int {
        require(index in indices) { "truncated packet at $what" }
        return this[index].toInt() and 0xFF
    }

    private fun ByteArray.readDataByte(index: Int, what: String): Int {
        val value = readRawByte(index, what)
        require(value and 0x80 == 0) { "data byte has bit 7 set at $what" }
        return value
    }
}
