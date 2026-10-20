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
import com.remoteesp32midi.glassmidi.midi.BleMidiCodec
import com.remoteesp32midi.glassmidi.model.MidiMessage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    private val sendQueue = Channel<ByteArray>(capacity = 256)
    private val writeMutex = Mutex()

    private val foundDevices = ConcurrentHashMap<String, BleDevice>()
    private var scanTimeoutJob: Job? = null
    private var reconnectJob: Job? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var midiCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var desiredAddress: String? = null
    @Volatile private var manualDisconnect = false
    @Volatile private var retryAttempt = 0
    @Volatile private var lastConnection = BleConnection()

    init {
        scope.launch {
            for (packet in sendQueue) {
                writePacket(packet)
                // Android 的 GATT 操作一次只能有一个；留出连接间隔给协议栈。
                delay(WRITE_PACING_MS)
            }
        }
    }

    override fun send(messages: List<MidiMessage>) {
        if (messages.isEmpty()) return
        // 未 Ready 时不排队：旧按键/旋钮值在重连后重放会造成卡音或状态跳变。
        if (lastConnection.phase != ConnectionPhase.READY) return
        val packet = codec.encode(messages, SystemClock.elapsedRealtime())
        if (!sendQueue.trySend(packet).isSuccess) {
            _events.tryEmit(TransportEvent.Error("BLE 发送队列已满，已丢弃一帧"))
        }
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

        foundDevices.clear()
        _events.tryEmit(TransportEvent.Devices(emptyList()))
        emitConnection(BleConnection(phase = ConnectionPhase.SCANNING))

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            publishError("蓝牙扫描器不可用")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            // 固件用 BLE 5 扩展广播（UUID+名称超过 31 字节）。Android 默认
            // legacy=true，只回传传统广播，因此这里必须显式接收扩展广播。
            .setLegacy(false)
            .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            .build()
        Log.d(TAG, "startScan legacy=false phy=ALL")
        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (error: SecurityException) {
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
        if (hasScanPermission()) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        }
    }

    @SuppressLint("MissingPermission")
    override fun connect(address: String) {
        stopScan()
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
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, autoConnect, gattCallback)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord
            val advertisedUuid = record?.serviceUuids?.any {
                it.uuid == SERVICE_UUID
            } == true
            val name = record?.deviceName
                ?: runCatching { result.device.name }.getOrNull()
            Log.d(TAG, "scan result ${result.device.address} name=$name uuid=$advertisedUuid")
            if (!advertisedUuid && name?.contains("ESP32-S3 MIDI") != true) return
            val device = BleDevice(
                name = name ?: "ESP32-S3 MIDI",
                address = result.device.address,
                rssi = result.rssi,
            )
            foundDevices[device.address] = device
            _events.tryEmit(
                TransportEvent.Devices(foundDevices.values.sortedByDescending { it.rssi })
            )
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed code=$errorCode")
            publishError("BLE 扫描失败，code=$errorCode")
            emitConnection(BleConnection(phase = ConnectionPhase.IDLE))
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "conn state status=$status newState=$newState address=${gatt.device.address}")
            if (gatt !== this@AndroidBleMidiTransport.gatt) {
                gatt.close()
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
                runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                if (!gatt.requestMtu(MTU_REQUEST)) {
                    gatt.discoverServices()
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

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(characteristic, value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: return
            handleNotification(characteristic, value)
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
        if (messages.isNotEmpty()) {
            _incoming.tryEmit(messages)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun writePacket(packet: ByteArray) {
        val gatt = gatt ?: return
        val characteristic = midiCharacteristic ?: return
        if (lastConnection.phase != ConnectionPhase.READY) return

        writeMutex.withLock {
            var attempt = 0
            while (attempt < WRITE_RETRIES) {
                val accepted = writeCharacteristic(gatt, characteristic, packet)
                if (accepted) return
                delay(WRITE_RETRY_DELAY_MS)
                attempt++
            }
            publishError("BLE 写入失败")
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
        } catch (error: SecurityException) {
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
        clearSendQueue()
        val current = gatt
        gatt = null
        midiCharacteristic = null
        if (current != null) {
            runCatching { current.disconnect() }
            runCatching { current.close() }
        }
    }

    private fun clearSendQueue() {
        while (sendQueue.tryReceive().isSuccess) {
            // 清空断线前排队的旧状态。
        }
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
        private const val WRITE_PACING_MS = 3L
        private const val WRITE_RETRY_DELAY_MS = 4L
        private const val WRITE_RETRIES = 5
        private val RECONNECT_DELAYS_MS = longArrayOf(500L, 1_000L, 2_000L, 5_000L)
    }
}
