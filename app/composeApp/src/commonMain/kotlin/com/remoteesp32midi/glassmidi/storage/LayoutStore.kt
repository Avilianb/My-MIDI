package com.remoteesp32midi.glassmidi.storage

import com.remoteesp32midi.glassmidi.model.PersistedState

interface LayoutStore {
    suspend fun load(): PersistedState

    suspend fun save(state: PersistedState)
}
