package com.asl.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import androidx.annotation.RequiresApi
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface BleConnectionState {
    data object Idle : BleConnectionState
    data object Scanning : BleConnectionState
    data object Connecting : BleConnectionState
    data object Discovering : BleConnectionState
    data object Connected : BleConnectionState
    data class Failed(val message: String) : BleConnectionState
}

/**
 * Process-wide owner of the BLE connection. Survives screen changes and rotation, so both the
 * setup screen and the display screen work from the same GATT connection.
 */
@SuppressLint("MissingPermission") // Runtime permissions are handled by the screens.
object BleGloveClient {

    val SERVICE_UUID: UUID = UUID.fromString("19B10000-E8F2-537E-4F6C-D104768A1214")
    val TEXT_CHARACTERISTIC_UUID: UUID = UUID.fromString("19B10001-E8F2-537E-4F6C-D104768A1214")

    private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val SCAN_TIMEOUT_MS = 20_000L
    private const val MAX_CAPTION_CHARS = 400

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var adapter: BluetoothAdapter? = null
    private var appContext: Context? = null
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var scanTimeout: Job? = null

    private val _state = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    val state: StateFlow<BleConnectionState> = _state.asStateFlow()

    private val _caption = MutableStateFlow("")
    val caption: StateFlow<String> = _caption.asStateFlow()

    fun initialize(context: Context) {
        if (adapter != null) return
        appContext = context.applicationContext
        adapter =
            (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
                .adapter
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun clearCaption() {
        _caption.value = ""
    }

    fun scanAndConnect() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            _state.value = BleConnectionState.Failed("Bluetooth is unavailable or not enabled.")
            return
        }

        closeGatt()
        _state.value = BleConnectionState.Scanning

        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true

            scanTimeout?.cancel()
            scanTimeout = scope.launch {
                delay(SCAN_TIMEOUT_MS)
                if (scanning) {
                    fail(
                        "Arduino not found. Make sure it is powered on and advertising, then try again."
                    )
                }
            }
        } catch (_: Exception) {
            fail("Unable to start the Bluetooth scan.")
        }
    }

    fun disconnect() {
        stopScan()
        closeGatt()
        _state.value = BleConnectionState.Idle
    }

    fun showError(message: String) {
        stopScan()
        closeGatt()
        _state.value = BleConnectionState.Failed(message)
    }

    private val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!scanning) return

                stopScan()
                _state.value = BleConnectionState.Connecting

                gatt =
                    try {
                        result.device.connectGatt(
                            appContext,
                            false,
                            gattCallback,
                            BluetoothDevice.TRANSPORT_LE,
                        )
                    } catch (_: Exception) {
                        null
                    }

                if (gatt == null) fail("Unable to start the Arduino connection.")
            }

            override fun onScanFailed(errorCode: Int) {
                fail("Bluetooth scan failed with code $errorCode.")
            }
        }

    private val gattCallback =
        object : BluetoothGattCallback() {

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                // Ignore callbacks from a connection we have already abandoned.
                if (gatt !== this@BleGloveClient.gatt) return

                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    _state.value = BleConnectionState.Discovering
                    if (!gatt.discoverServices()) {
                        fail("Connected, but service discovery could not start.")
                    }
                } else if (
                    newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS
                ) {
                    fail("Connection failed or was lost. Please pair again.")
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (gatt !== this@BleGloveClient.gatt) return

                val characteristic =
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        gatt.getService(SERVICE_UUID)?.getCharacteristic(TEXT_CHARACTERISTIC_UUID)
                    } else {
                        null
                    }

                if (characteristic == null) {
                    fail(
                        "The board connected, but the ASL text characteristic was not found. " +
                                "Check the Arduino sketch and characteristic UUID."
                    )
                    return
                }

                enableNotifications(gatt, characteristic)
                _state.value = BleConnectionState.Connected
            }

            @RequiresApi(Build.VERSION_CODES.TIRAMISU)
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (characteristic.uuid != TEXT_CHARACTERISTIC_UUID) return
                appendLetters(String(value, Charsets.UTF_8))
            }

            // Android 12L and older only call this overload.
            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (characteristic.uuid != TEXT_CHARACTERISTIC_UUID) return
                @Suppress("DEPRECATION") val value = characteristic.value ?: return
                appendLetters(String(value, Charsets.UTF_8))
            }
        }

    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        gatt.setCharacteristicNotification(characteristic, true)

        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    /**
     * Appends a chunk of streamed text. `\b` (backspace) deletes the last letter; other control
     * characters (e.g. `\r`) are dropped. The oldest letters are discarded once the cap is reached,
     * because the display only ever shows the tail of the stream anyway.
     */
    private fun appendLetters(raw: String) {
        if (raw.isEmpty()) return

        val builder = StringBuilder(_caption.value)
        for (ch in raw) {
            when {
                ch == '\b' -> if (builder.isNotEmpty()) builder.deleteCharAt(builder.length - 1)
                ch == '\n' -> builder.append(ch)
                ch.isISOControl() -> Unit // ignore \r and friends
                else -> builder.append(ch)
            }
        }

        val next = builder.toString().takeLast(MAX_CAPTION_CHARS)
        if (next != _caption.value) _caption.value = next
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        scanTimeout?.cancel()
        scanTimeout = null

        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
            // Bluetooth may have been disabled or permission revoked.
        }
    }

    private fun closeGatt() {
        val oldGatt = gatt
        gatt = null

        try {
            oldGatt?.disconnect()
        } catch (_: Exception) {
        }

        try {
            oldGatt?.close()
        } catch (_: Exception) {
        }
    }

    private fun fail(message: String) {
        stopScan()
        closeGatt()
        _state.value = BleConnectionState.Failed(message)
    }
}
