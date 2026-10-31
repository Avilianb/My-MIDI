package com.remoteesp32midi.glassmidi.midi

import com.remoteesp32midi.glassmidi.model.MidiMessage
import kotlin.test.*

class MidiSendQueueTest {
    @Test fun floodKeepsEveryMappingsFinalValue() {
        val queue = MidiSendQueue()
        for (value in 1..126) {
            queue.offer(listOf(MidiMessage.controlChange(1, 7, value)))
            queue.offer(listOf(MidiMessage.pitchBend(2, value)))
        }
        assertEquals(listOf(MidiMessage.controlChange(1, 7, 126)), queue.poll())
        assertEquals(listOf(MidiMessage.pitchBend(2, 126)), queue.poll())
        assertNull(queue.poll())
    }
    @Test fun releaseIsPrioritizedAndCannotBeOverwrittenByOlderValue() {
        val queue = MidiSendQueue()
        queue.offer(listOf(MidiMessage.controlChange(1, 80, 80)))
        queue.offer(listOf(MidiMessage.controlChange(1, 80, 0)))
        assertEquals(listOf(MidiMessage.controlChange(1, 80, 0)), queue.poll())
        assertNull(queue.poll())
    }
    @Test fun fourteenBitPairRemainsAtomicAtDefaultMtu() {
        val queue = MidiSendQueue()
        val pair = listOf(MidiMessage.controlChange(1, 7, 64), MidiMessage.controlChange(1, 39, 127))
        queue.offer(pair + listOf(MidiMessage.pitchBend(2, 8192)))
        assertEquals(pair, queue.poll())
        assertTrue(BleMidiCodec().encode(pair, 0).size <= 20)
        assertEquals(listOf(MidiMessage.pitchBend(2, 8192)), queue.poll())
    }
    @Test fun portClaimDiscardsOldPortsPendingParameters() {
        val queue = MidiSendQueue()
        queue.offer(listOf(MidiMessage.pitchBend(1, 1234)))
        queue.offer(listOf(MidiMessage.noteOn(1, 60, 127)))
        queue.offer(listOf(MidiMessage.portSelect(1, true)))
        assertEquals(listOf(MidiMessage.portSelect(1, true)), queue.poll())
        assertNull(queue.poll())
    }
    @Test fun defaultMtuBatchesKeepFourFourteenBitControlsComplete() {
        val queue = MidiSendQueue()
        val messages = (1..4).flatMap { channel -> listOf(
            MidiMessage.controlChange(channel, 7, 64),
            MidiMessage.controlChange(channel, 39, 63),
        ) }
        assertTrue(queue.offer(messages))
        val delivered = mutableListOf<MidiMessage>()
        while (true) {
            val batch = queue.poll(maxMessages = 4) ?: break
            assertTrue(BleMidiCodec().encode(batch, 0).size <= 20)
            assertEquals(0, batch.size % 2)
            delivered.addAll(batch)
        }
        assertEquals(messages, delivered)
    }
    @Test fun priorityOverflowRequiresRecoveryInsteadOfSilentReleaseLoss() {
        val queue = MidiSendQueue(priorityCapacity = 1)
        assertTrue(queue.offer(listOf(MidiMessage.noteOn(1, 60, 127))))
        assertFalse(queue.offer(listOf(MidiMessage.noteOff(1, 60))))
        queue.clear()
        assertNull(queue.poll())
    }
}
