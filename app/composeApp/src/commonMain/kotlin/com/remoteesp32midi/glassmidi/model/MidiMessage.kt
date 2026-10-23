package com.remoteesp32midi.glassmidi.model

/**
 * 一条完整 MIDI 消息。status 带通道号；channel 属性是 0..15 的线上值。
 * 2 字节消息（Program Change/Channel Pressure）的 data2 为 null。
 */
data class MidiMessage(
    val status: Int,
    val data1: Int,
    val data2: Int? = null,
) {
    init {
        require(status in 0x80..0xFF) { "status out of range: $status" }
        require(data1 in 0..0x7F) { "data1 out of range: $data1" }
        require(data2 == null || data2 in 0..0x7F) { "data2 out of range: $data2" }
        require(data2 == null || status !in 0xC0..0xDF) { "2-byte status has data2" }
    }

    val command: Int get() = status and 0xF0

    /** 0..15。 */
    val channel: Int get() = status and 0x0F

    val isChannelVoice: Boolean get() = status in 0x80..0xEF

    val isNoteOn: Boolean get() = command == 0x90 && (data2 ?: 0) > 0

    val isNoteOff: Boolean get() = command == 0x80 || (command == 0x90 && (data2 ?: 0) == 0)

    fun toBytes(): ByteArray {
        val second = data2
        return if (second == null) {
            byteArrayOf(status.toByte(), data1.toByte())
        } else {
            byteArrayOf(status.toByte(), data1.toByte(), second.toByte())
        }
    }

    companion object {
        fun noteOn(channel: Int, note: Int, velocity: Int): MidiMessage =
            MidiMessage(0x90 or channelZeroBased(channel), note, velocity)

        fun noteOff(channel: Int, note: Int): MidiMessage =
            MidiMessage(0x80 or channelZeroBased(channel), note, 0)

        fun controlChange(channel: Int, controller: Int, value: Int): MidiMessage =
            MidiMessage(0xB0 or channelZeroBased(channel), controller, value)

        /** MIDI pitch bend 的两个数据字节是 LSB 在前、MSB 在后。 */
        fun pitchBend(channel: Int, value: Int): MidiMessage {
            require(value in 0..16383) { "pitch bend out of range: $value" }
            return MidiMessage(
                0xE0 or channelZeroBased(channel),
                value and 0x7F,
                (value shr 7) and 0x7F,
            )
        }

        /**
         * 发送给 ESP32-S3 的端口握手消息（[0xF4, 0x7D, portIndex]）。
         * 固件拦截此消息并将当前手机连接绑定到 USB 虚拟端口 portIndex (0 = Port 1, 1 = Port 2)，不会转发到电脑。
         */
        fun portSelect(portIndex: Int): MidiMessage =
            MidiMessage(0xF4, 0x7D, portIndex.coerceIn(0, 1))

        private fun channelZeroBased(channel: Int): Int {
            require(channel in 1..16) { "channel out of range: $channel" }
            return channel - 1
        }
    }
}
