package com.remoteesp32midi.glassmidi.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.remoteesp32midi.glassmidi.midi.MidiSendQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import com.remoteesp32midi.glassmidi.midi.BleMidiCodec
import com.remoteesp32midi.glassmidi.model.MidiMessage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Android 标准 BLE-MIDI central。
 *
 * 一个 GATT 连接、一个写队列、单写者；Write Without Response。
 * BLE 回调只投递事件，不直接碰 Compose 状态。
 */
class AndroidBleMidiTransport(
    private val context: Context,
    private val scope: CoroutineScope,
) : BleMidiTransport {

    private val codec = BleMidiCodec()
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val _events = MutableSharedFlow<TransportEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<TransportEvent> = _events.asSharedFlow()

    private val _incoming = MutableSharedFlow<List<MidiMessage>>(
        extraBufferCapacity = 32,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val incoming: Flow<List<MidiMessage>> = _incoming.asSharedFlow()

    private val queueLock = Any()
    private val sendQueue = MidiSendQueue()
    @Volatile private var negotiatedMtu = 23
    @Volatile private var sessionGeneration = 0L
    @Volatile private var writeCompletion: CompletableDeferred<Int>? = null
    private val sendSignal = Channel<Unit>(capacity = Channel.CONFLATED)
    private val writeMutex = Mutex()
    @OptIn(ExperimentalCoroutinesApi::class)
    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val foundDevices = ConcurrentHashMap<String, BleDevice>()
    private var scanTimeoutJob: Job? = null
    private var scanPublishJob: Job? = null
    @Volatile private var scanDevicesDirty = false
    private var connectTimeoutJob: Job? = null
    private var reconnectJob: Job? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var midiCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var desiredAddress: String? = null
    @Volatile private var manualDisconnect = false
    @Volatile private var retryAttempt = 0
    @Volatile private var lastConnection = BleConnection()

    init {
        scope.launch(writeDispatcher) {
            for (signal in sendSignal) {
                while (true) {
                    val entry = synchronized(queueLock) {
                        sendQueue.poll(((negotiatedMtu - 4) / 4).coerceIn(2, 8))?.let { it to sessionGeneration }
                    } ?: break
                    val packet = codec.encode(entry.first, SystemClock.elapsedRealtime())
                    val priority = entry.first.any { it.isNoteOn || it.isNoteOff || it.isPortHandshake }
                    writePacket(packet, priority, entry.second)
                    // Android 的 GATT 操作一次只能有一个；留出连接间隔给协议栈。
                    delay(WRITE_PACING_MS)
                }
            }
        }
    }

    override fun send(messages: List<MidiMessage>) {
        if (messages.isEmpty()) return
        // 未 Ready 时不排队：旧按键/旋钮值在重连后重放会造成卡音或状态跳变。
        if (lastConnection.phase != ConnectionPhase.READY) return
        val accepted = synchronized(queueLock) {
            lastConnection.phase == ConnectionPhase.READY && sendQueue.offer(messages)
        }
        if (!accepted) {
            recoverWriteFailure("BLE 发送拥塞，正在重新建立安全连接")
            return
        }
        sendSignal.trySend(Unit)
    }

    @SuppressLint("MissingPermission")
    override fun startScan() {
        val adapter = adapter
        if (adapter == null) {
            publishError("这台设备没有蓝牙适配器")
            return
        }
        if (!hasScanPermission()) {
            publishError("缺少蓝牙扫描权限")
            return
        }
        if (!adapter.isEnabled) {
            publishError("请先打开系统蓝牙")
            return
        }

        if (lastConnection.phase in listOf(ConnectionPhase.READY, ConnectionPhase.CONNECTING, ConnectionPhase.RECONNECTING)) return
        stopScan()
        foundDevices.clear()
        _events.tryEmit(TransportEvent.Devices(emptyList()))
        emitConnection(BleConnection(phase = ConnectionPhase.SCANNING))

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            publishError("蓝牙扫描器不可用")
            return
        }
        val settings = ScanSettings.Builder()
            // LOW_LATENCY 会让 BLE 栈持续以最高占空比扫描；附近广播一多，
            // UI 线程就会被抢 CPU，表现为未连接也卡顿。配网扫描用 BALANCED
            // 足够，10 秒超时内仍能发现 ESP32。
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            // Firmware puts its UUID in legacy ADV and name in scan response.
            // This also works on BLE 4.x Android controllers.
            .setLegacy(true)
            .build()
        Log.d(TAG, "startScan legacy=true")
        scanDevicesDirty = false
        scanPublishJob?.cancel()
        scanPublishJob = scope.launch {
            // 把广播风暴合并成 4Hz 的设备列表更新，避免每个广播都触发整页重组。
            while (isActive) {
                delay(DEVICE_PUBLISH_INTERVAL_MS)
                if (scanDevicesDirty) {
                    scanDevicesDirty = false
                    publishDevices()
                }
            }
        }
        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (error: SecurityException) {
            scanPublishJob?.cancel()
            scanPublishJob = null
            publishError("蓝牙扫描权限被拒绝")
            return
        }

        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(SCAN_TIMEOUT_MS)
            stopScan()
            emitConnection(BleConnection(phase = ConnectionPhase.IDLE))
        }
    }

    @SuppressLint("MissingPermission")
    override fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        scanPublishJob?.cancel()
        scanPublishJob = null
        scanDevicesDirty = false
        if (hasScanPermission()) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        }
    }

    @SuppressLint("MissingPermission")
    override fun connect(address: String) {
        stopScan()
        reconnectJob?.cancel()
        reconnectJob = null
        desiredAddress = address
        manualDisconnect = false
        retryAttempt = 0
        emitConnection(BleConnection(phase = ConnectionPhase.CONNECTING, deviceAddress = address))
        connectInternal(address, autoConnect = false)
    }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        manualDisconnect = true
        desiredAddress = null
        stopScan()
        reconnectJob?.cancel()
        reconnectJob = null
        closeGatt()
        emitConnection(BleConnection(phase = ConnectionPhase.IDLE))
    }

    @SuppressLint("MissingPermission")
    private fun connectInternal(address: String, autoConnect: Boolean) {
        val adapter = adapter ?: run {
            publishError("这台设备没有蓝牙适配器")
            return
        }
        if (!hasConnectPermission()) {
            publishError("缺少蓝牙连接权限")
            return
        }
        closeGatt()
        if (!adapter.isEnabled) {
            scheduleReconnect()
            return
        }
        midiCharacteristic = null
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (error: IllegalArgumentException) {
            publishError("无效的蓝牙地址")
            return
        }
        emitConnection(
            BleConnection(
                phase = if (autoConnect) ConnectionPhase.RECONNECTING else ConnectionPhase.CONNECTING,
                deviceAddress = address,
                deviceName = safeDeviceName(device),
            )
        )
        startConnectWatchdog(address, autoConnect)
        gatt = runCatching {
            device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (gatt == null) scheduleReconnect()
    }

    private fun startConnectWatchdog(address: String, autoConnect: Boolean) {
        connectTimeoutJob?.cancel()
        // autoConnect 走的是系统后台扫描，正常也可能超过 10 s；用更长的窗口，避免把正在成功的重连掐掉。
        val timeoutMs = if (autoConnect) RECONNECT_TIMEOUT_MS else CONNECT_TIMEOUT_MS
        connectTimeoutJob = scope.launch {
            delay(timeoutMs)
            if (lastConnection.phase != ConnectionPhase.READY && !manualDisconnect) {
                Log.w(TAG, "connect watchdog timeout ($timeoutMs ms) for $address; scheduling reconnect")
                closeGatt()
                scheduleReconnect()
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord
            val advertisedUuid = record?.serviceUuids?.any {
                it.uuid == SERVICE_UUID
            } == true
            // 先过滤再取 name：result.device.name 可能走 binder，且无关广播
            // 不应产生日志/设备列表更新。
            val advertisedName = record?.deviceName
            if (!advertisedUuid && advertisedName?.contains("ESP32-S3 MIDI") != true) return
            val device = BleDevice(
                name = advertisedName ?: "ESP32-S3 MIDI",
                address = result.device.address,
                rssi = result.rssi,
            )
            foundDevices[device.address] = device
            scanDevicesDirty = true
            Log.d(TAG, "matched scan result ${device.address} rssi=${device.rssi} uuid=$advertisedUuid")
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed code=$errorCode")
            scanPublishJob?.cancel()
            scanPublishJob = null
            publishError("BLE 扫描失败，code=$errorCode")
            emitConnection(BleConnection(phase = ConnectionPhase.IDLE))
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "conn state status=$status newState=$newState address=${gatt.device.address}")
            if (gatt !== this@AndroidBleMidiTransport.gatt) {
                runCatching { gatt.close() }
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                emitConnection(
                    BleConnection(
                        phase = ConnectionPhase.CONNECTING,
                        deviceName = safeDeviceName(gatt.device),
                        deviceAddress = gatt.device.address,
                    )
                )
                // 物理连接已建立：把看门狗重置为"服务发现"窗口，
                // 否则 autoConnect 慢到接近超时时，会在 MTU/发现服务过程中被自己掐断。
                startConnectWatchdog(gatt.device.address, autoConnect = false)
                runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                if (!runCatching { gatt.requestMtu(MTU_REQUEST) }.getOrDefault(false)) {
                    if (!runCatching { gatt.discoverServices() }.getOrDefault(false)) recoverWriteFailure("无法发现 BLE 服务")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                closeGatt()
                if (manualDisconnect || desiredAddress == null) {
                    emitConnection(BleConnection(phase = ConnectionPhase.IDLE))
                } else {
                    scheduleReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt !== this@AndroidBleMidiTransport.gatt) return
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu.coerceAtLeast(23) else 23
            if (!gatt.discoverServices()) {
                publishError("无法发现 BLE 服务")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== this@AndroidBleMidiTransport.gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "service discovery failed status=$status")
                publishError("BLE 服务发现失败，code=$status")
                gatt.disconnect()
                return
            }
            val characteristic = gatt
                .getService(SERVICE_UUID)
                ?.getCharacteristic(CHARACTERISTIC_UUID)
            if (characteristic == null) {
                publishError("没有找到 BLE-MIDI characteristic")
                gatt.disconnect()
                return
            }
            midiCharacteristic = characteristic
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                publishError("无法启用 BLE 通知")
                gatt.disconnect()
                return
            }
            val cccd = characteristic.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                publishError("BLE-MIDI characteristic 没有 CCCD")
                gatt.disconnect()
                return
            }
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
            if (!started) {
                publishError("无法订阅 BLE 通知")
                gatt.disconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (gatt !== this@AndroidBleMidiTransport.gatt) return
            if (descriptor.uuid != CCCD_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "descriptor write failed status=$status")
                publishError("订阅 BLE 通知失败，code=$status")
                gatt.disconnect()
                return
            }
            connectTimeoutJob?.cancel()
            connectTimeoutJob = null
            requestHighPriority(gatt)
            retryAttempt = 0
            clearSendQueue()
            Log.i(TAG, "BLE-MIDI READY ${gatt.device.address}")
            emitConnection(
                BleConnection(
                    phase = ConnectionPhase.READY,
                    deviceName = safeDeviceName(gatt.device),
                    deviceAddress = gatt.device.address,
                )
            )
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (gatt === this@AndroidBleMidiTransport.gatt && characteristic.uuid == CHARACTERISTIC_UUID) {
                writeCompletion?.complete(status)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (gatt === this@AndroidBleMidiTransport.gatt) handleNotification(characteristic, value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: return
            if (gatt === this@AndroidBleMidiTransport.gatt) handleNotification(characteristic, value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestHighPriority(gatt: BluetoothGatt) {
        runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                gatt.setPreferredPhy(
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_OPTION_NO_PREFERRED,
                )
            }
        }
    }

    private fun handleNotification(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (characteristic.uuid != CHARACTERISTIC_UUID || value.isEmpty()) return
        val messages = runCatching { codec.decode(value) }.getOrElse {
            return
        }
        if (messages.any { it.isPortHandshake }) clearSendQueue()
        if (messages.isNotEmpty()) {
            _incoming.tryEmit(messages)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun writePacket(packet: ByteArray, priority: Boolean, generation: Long) {
        val gatt = gatt ?: return
        val characteristic = midiCharacteristic ?: return
        if (lastConnection.phase != ConnectionPhase.READY || generation != sessionGeneration) return

        // Note / 端口握手比 CC 重要得多：丢一帧 CC 只是参数跳一下，丢一条 Note Off 就是永久卡音。
        val retries = if (priority) PRIORITY_WRITE_RETRIES else WRITE_RETRIES

        writeMutex.withLock {
            var attempt = 0
            while (attempt < retries) {
                // 断开/切换中不要对着已关闭的 gatt 空转，也不要弹假的"写入失败"。
                if (this@AndroidBleMidiTransport.gatt !== gatt ||
                    lastConnection.phase != ConnectionPhase.READY || generation != sessionGeneration
                ) {
                    return
                }
                val completion = CompletableDeferred<Int>()
                writeCompletion = completion
                val accepted = writeCharacteristic(gatt, characteristic, packet)
                if (accepted) {
                    val status = withTimeoutOrNull(WRITE_TIMEOUT_MS) { completion.await() }
                    if (writeCompletion === completion) writeCompletion = null
                    if (status != BluetoothGatt.GATT_SUCCESS && this@AndroidBleMidiTransport.gatt === gatt) {
                        recoverWriteFailure("BLE 写入未完成，正在重连")
                    }
                    return
                }
                if (writeCompletion === completion) writeCompletion = null
                delay(WRITE_RETRY_DELAY_MS)
                attempt++
            }
            if (lastConnection.phase == ConnectionPhase.READY) {
                recoverWriteFailure("BLE 写入失败，正在重连")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        packet: ByteArray,
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    packet,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                characteristic.value = packet
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        } catch (error: Exception) {
            // 蓝牙栈被回收/句柄已关闭时可能抛 IllegalStateException、DeadObjectException 等，
            // 只抓 SecurityException 会让唯一的写协程崩溃，之后所有发送永久失效。
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun scheduleReconnect() {
        if (manualDisconnect || desiredAddress == null) return
        val address = desiredAddress ?: return
        reconnectJob?.cancel()
        val attempt = retryAttempt.coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)
        retryAttempt++
        emitConnection(
            BleConnection(
                phase = ConnectionPhase.RECONNECTING,
                deviceAddress = address,
                detail = "第 ${attempt + 1} 次重连",
            )
        )
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAYS_MS[attempt])
            if (!manualDisconnect && desiredAddress == address) {
                connectInternal(address, autoConnect = true)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        writeCompletion?.complete(BluetoothGatt.GATT_FAILURE)
        writeCompletion = null
        clearSendQueue()
        val current = gatt
        gatt = null
        midiCharacteristic = null
        negotiatedMtu = 23
        if (current != null) {
            runCatching { current.disconnect() }
            runCatching { current.close() }
        }
    }

    private fun clearSendQueue() {
        synchronized(queueLock) {
            sessionGeneration++
            sendQueue.clear()
        }
    }

    private fun recoverWriteFailure(message: String) {
        val failedGatt = gatt
        val failedGeneration = sessionGeneration
        scope.launch {
            if (manualDisconnect || gatt !== failedGatt || sessionGeneration != failedGeneration) return@launch
            publishError(message)
            closeGatt()
            scheduleReconnect()
        }
    }

    private fun publishDevices() {
        _events.tryEmit(
            TransportEvent.Devices(foundDevices.values.sortedByDescending { it.rssi })
        )
    }

    private fun emitConnection(connection: BleConnection) {
        lastConnection = connection
        _events.tryEmit(TransportEvent.ConnectionChanged(connection))
    }

    private fun publishError(message: String) {
        Log.w(TAG, "error: $message")
        _events.tryEmit(TransportEvent.Error(message))
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice): String? =
        runCatching { device.name }.getOrNull()

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "GlassMidiBle"
        val SERVICE_UUID: UUID = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("7772E5DB-3868-4112-A1A9-F2669D106BF3")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        private const val MTU_REQUEST = 247
        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val RECONNECT_TIMEOUT_MS = 30_000L
        private const val DEVICE_PUBLISH_INTERVAL_MS = 250L
        private const val WRITE_TIMEOUT_MS = 1_500L
        private const val WRITE_PACING_MS = 3L
        private const val WRITE_RETRY_DELAY_MS = 4L
        private const val WRITE_RETRIES = 5
        private const val PRIORITY_WRITE_RETRIES = 25
        private val RECONNECT_DELAYS_MS = longArrayOf(500L, 1_000L, 2_000L, 5_000L)
    }
}
