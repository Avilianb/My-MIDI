package com.remoteesp32midi.glassmidi.midi

import com.remoteesp32midi.glassmidi.model.MidiMessage

/** Caller serializes access. Latest continuous value per mapping; releases stay FIFO. */
class MidiSendQueue(private val priorityCapacity: Int = 64) {
    private val priority = ArrayDeque<List<MidiMessage>>()
    private val continuous = linkedMapOf<Pair<Int, Int>, List<MidiMessage>>()

    fun offer(messages: List<MidiMessage>): Boolean {
        if (messages.any { it.isPortHandshake }) clear()
        var index = 0
        while (index < messages.size) {
            val first = messages[index++]
            val next = messages.getOrNull(index)
            val pair = first.command == 0xB0 && first.data1 < 32 &&
                next?.status == first.status && next.data1 == first.data1 + 32
            val group = if (pair) listOf(first, messages[index++]) else listOf(first)
            val key = first.status to if (first.command == 0xE0) -1 else first.data1
            // 0/127 CCs include momentary button/sustain releases, which must not be evicted.
            val urgent = first.isNoteOn || first.isNoteOff || first.isPortHandshake ||
                (group.size == 1 && first.command == 0xB0 && first.data2 in listOf(0, 127))
            if (urgent) {
                if (priority.size >= priorityCapacity) return false
                continuous.remove(key)
                priority.addLast(group)
            } else {
                continuous[key] = group
            }
        }
        return true
    }

    fun poll(maxMessages: Int = 1): List<MidiMessage>? = priority.removeFirstOrNull() ?: run {
        val result = mutableListOf<MidiMessage>()
        val entries = continuous.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (result.isNotEmpty() && result.size + entry.value.size > maxMessages) break
            result.addAll(entry.value)
            entries.remove()
            if (result.size >= maxMessages) break
        }
        result.takeIf { it.isNotEmpty() }
    }

    fun clear() { priority.clear(); continuous.clear() }
}
