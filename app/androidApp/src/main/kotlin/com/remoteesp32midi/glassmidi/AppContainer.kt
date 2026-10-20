package com.remoteesp32midi.glassmidi

import android.content.Context
import com.remoteesp32midi.glassmidi.ble.AndroidBleMidiTransport
import com.remoteesp32midi.glassmidi.storage.AndroidLayoutStore
import com.remoteesp32midi.glassmidi.store.ControllerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application 级依赖容器。Activity、前台服务共用同一个 BLE 连接和 store，
 * 避免旋转/切后台时重建连接。手写依赖，不引入 DI 框架。
 */
class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val layoutStore = AndroidLayoutStore(applicationContext)
    val transport = AndroidBleMidiTransport(applicationContext, applicationScope)
    val store = ControllerStore(transport, layoutStore, applicationScope)
}
