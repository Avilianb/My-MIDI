package com.remoteesp32midi.glassmidi.storage

import android.content.Context
import androidx.core.util.AtomicFile
import com.remoteesp32midi.glassmidi.model.Control
import com.remoteesp32midi.glassmidi.model.ControlKind
import com.remoteesp32midi.glassmidi.model.Layout
import com.remoteesp32midi.glassmidi.model.MidiMapping
import com.remoteesp32midi.glassmidi.model.MidiResolution
import com.remoteesp32midi.glassmidi.model.MidiType
import com.remoteesp32midi.glassmidi.model.PersistedState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * layout.json 存在 App 私有目录；AtomicFile 保证写入不会留下半个文件。
 */
class AndroidLayoutStore(context: Context) : LayoutStore {
    private val file = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))

    override suspend fun load(): PersistedState = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists()) return@withContext PersistedState()
        runCatching {
            val root = JSONObject(file.readFully().decodeToString())
            val layoutObject = root.optJSONObject("layout")
            val controls = layoutObject?.optJSONArray("controls")?.toControls().orEmpty()
            PersistedState(
                schemaVersion = root.optInt("schemaVersion", SCHEMA_VERSION),
                layout = Layout(
                    schemaVersion = layoutObject?.optInt("schemaVersion", SCHEMA_VERSION)
                        ?: SCHEMA_VERSION,
                    controls = controls,
                ),
                deviceAddress = root.optString("deviceAddress").takeIf { it.isNotBlank() },
                deviceName = root.optString("deviceName").takeIf { it.isNotBlank() },
            )
        }.getOrElse { PersistedState() }
    }

    override suspend fun save(state: PersistedState) = withContext(Dispatchers.IO) {
        val root = JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("deviceAddress", state.deviceAddress ?: "")
            put("deviceName", state.deviceName ?: "")
            put(
                "layout",
                JSONObject().apply {
                    put("schemaVersion", state.layout.schemaVersion)
                    put("controls", JSONArray().apply {
                        state.layout.controls.forEach { control ->
                            put(
                                JSONObject().apply {
                                    put("id", control.id)
                                    put("label", control.label)
                                    put("kind", control.kind.name)
                                    put("value", control.value)
                                    put(
                                        "mapping",
                                        JSONObject().apply {
                                            put("type", control.mapping.type.name)
                                            put("channel", control.mapping.channel)
                                            put("number", control.mapping.number)
                                            put("resolution", control.mapping.resolution.name)
                                        }
                                    )
                                }
                            )
                        }
                    })
                }
            )
        }
        val bytes = root.toString(2).encodeToByteArray()
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun JSONArray.toControls(): List<Control> {
        val result = ArrayList<Control>(length())
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val mapping = item.optJSONObject("mapping") ?: continue
            val kind = runCatching {
                ControlKind.valueOf(item.optString("kind"))
            }.getOrNull() ?: continue
            val type = runCatching {
                MidiType.valueOf(mapping.optString("type"))
            }.getOrNull() ?: continue
            val resolution = runCatching {
                MidiResolution.valueOf(mapping.optString("resolution"))
            }.getOrNull() ?: continue
            val control = Control(
                id = item.optString("id").ifBlank { "c${index + 1}" },
                label = item.optString("label"),
                kind = kind,
                mapping = MidiMapping(
                    type = type,
                    channel = mapping.optInt("channel", 1),
                    number = mapping.optInt("number", 0),
                    resolution = resolution,
                ),
                value = item.optInt("value", 0),
            )
            result += control
        }
        return result
    }

    private companion object {
        const val FILE_NAME = "layout.json"
        const val SCHEMA_VERSION = 1
    }
}
