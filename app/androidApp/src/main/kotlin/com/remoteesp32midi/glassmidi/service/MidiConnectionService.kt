package com.remoteesp32midi.glassmidi.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.remoteesp32midi.glassmidi.GlassMidiApplication
import com.remoteesp32midi.glassmidi.MainActivity
import com.remoteesp32midi.glassmidi.ble.BleConnection
import com.remoteesp32midi.glassmidi.ble.BleDevice
import com.remoteesp32midi.glassmidi.ble.ConnectionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MidiConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var container: com.remoteesp32midi.glassmidi.AppContainer
    private var notificationJob: Job? = null
    private val openAppIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
    private val disconnectIntent: PendingIntent by lazy {
        PendingIntent.getService(
            this,
            1,
            Intent(this, MidiConnectionService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun onCreate() {
        super.onCreate()
        container = (application as GlassMidiApplication).container
        createChannel()
        startForeground()
        notificationJob = scope.launch {
            container.store.state
                .map { it.connection }
                .distinctUntilChanged()
                .collectLatest { connection ->
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(connection))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            container.store.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground()
        // F-11：若进程被系统回收后由 START_STICKY 重建服务（intent == null），自动恢复上次连接的设备。
        if (intent == null) {
            scope.launch {
                val state = container.store.state.first { it.loaded }
                val address = state.lastDeviceAddress
                if (address != null && state.connection.phase == ConnectionPhase.IDLE) {
                    container.store.connect(
                        BleDevice(
                            name = state.lastDeviceName ?: "ESP32-S3 MIDI",
                            address = address,
                            rssi = 0,
                        )
                    )
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        notificationJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(container.store.state.value.connection),
            type,
        )
    }

    private fun buildNotification(connection: BleConnection): Notification {
        val (title, text) = when (connection.phase) {
            ConnectionPhase.READY -> "已连接" to (connection.deviceName ?: "ESP32-S3 MIDI")
            ConnectionPhase.CONNECTING -> "正在连接" to (connection.deviceName ?: "ESP32-S3 MIDI")
            ConnectionPhase.RECONNECTING -> "等待重连" to (connection.deviceName ?: "ESP32-S3 MIDI")
            ConnectionPhase.SCANNING -> "正在扫描" to "My MIDI"
            ConnectionPhase.ERROR -> "连接异常" to (connection.detail ?: "请回到 App 检查")
            ConnectionPhase.IDLE -> "等待连接" to "打开 My MIDI 选择设备"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("My MIDI · $title")
            .setContentText(text)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, "断开", disconnectIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MIDI 连接",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持 BLE-MIDI 连接"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_ID = "midi_connection"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.remoteesp32midi.glassmidi.START"
        private const val ACTION_DISCONNECT = "com.remoteesp32midi.glassmidi.DISCONNECT"

        fun start(context: Context) {
            val intent = Intent(context, MidiConnectionService::class.java)
                .setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MidiConnectionService::class.java))
        }
    }
}
