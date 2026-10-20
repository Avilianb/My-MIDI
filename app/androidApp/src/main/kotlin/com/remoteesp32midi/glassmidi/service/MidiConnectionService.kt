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
import com.remoteesp32midi.glassmidi.ble.ConnectionPhase
import com.remoteesp32midi.glassmidi.store.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MidiConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var container: com.remoteesp32midi.glassmidi.AppContainer
    private var notificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        container = (application as GlassMidiApplication).container
        createChannel()
        startForeground()
        notificationJob = scope.launch {
            container.store.state.collectLatest { state ->
                notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
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
        return START_NOT_STICKY
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
            buildNotification(container.store.state.value),
            type,
        )
    }

    private fun buildNotification(state: UiState): Notification {
        val connection = state.connection
        val (title, text) = when (connection.phase) {
            ConnectionPhase.READY -> "已连接" to (connection.deviceName ?: "ESP32-S3 MIDI")
            ConnectionPhase.CONNECTING -> "正在连接" to (connection.deviceName ?: "ESP32-S3 MIDI")
            ConnectionPhase.RECONNECTING -> "等待重连" to (connection.deviceName ?: "ESP32-S3 MIDI")
            ConnectionPhase.SCANNING -> "正在扫描" to "Glass MIDI"
            ConnectionPhase.ERROR -> "连接异常" to (connection.detail ?: "请回到 App 检查")
            ConnectionPhase.IDLE -> "等待连接" to "打开 Glass MIDI 选择设备"
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this,
            1,
            Intent(this, MidiConnectionService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Glass MIDI · $title")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, "断开", disconnect)
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
