package com.asl.app

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SetupUiState(
    val message: String = "Power on the Arduino, then tap Find and connect.",
    val busy: Boolean = false,
    val connected: Boolean = false
)

@SuppressLint("MissingPermission") // Runtime permissions are handled by the screen.
class BleSetupViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private val SERVICE_UUID: UUID =
            UUID.fromString("19B10000-E8F2-537E-4F6C-D104768A1214")
    }

    private val bluetoothAdapter: BluetoothAdapter? =
        (application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter

    private val _state = MutableStateFlow(SetupUiState())
    val state = _state.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var scanTimeout: Job? = null
    private var scanActive = false

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun scanAndConnect() {
        cancelOperations()

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            showError("Bluetooth is unavailable or not enabled.")
            return
        }

        _state.value = SetupUiState(
            message = "Scanning for ASL-Glove…",
            busy = true
        )

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanActive = true

            scanTimeout = viewModelScope.launch {
                delay(20_000)

                if (scanActive) {
                    showError(
                        "Arduino not found. Make sure it is powered on and advertising, " +
                            "then try again."
                    )
                }
            }
        } catch (_: Exception) {
            showError("Unable to start the Bluetooth scan.")
        }
    }

    fun showError(message: String) {
        cancelOperations()
        _state.value = SetupUiState(message = message)
    }

    fun reset() {
        cancelOperations()
        _state.value = SetupUiState()
    }

    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Ignore duplicate results after the first connection attempt starts.
            if (!scanActive) return

            stopScan()
            scanTimeout?.cancel()

            _state.value = SetupUiState(
                message = "Arduino found. Connecting…",
                busy = true
            )

            try {
                bluetoothGatt = result.device.connectGatt(
                    getApplication<Application>(),
                    false,
                    gattCallback,
                    android.bluetooth.BluetoothDevice.TRANSPORT_LE
                )

                if (bluetoothGatt == null) {
                    showError("Unable to start the Arduino connection.")
                }
            } catch (_: Exception) {
                showError("Unable to connect to the Arduino.")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            showError("Bluetooth scan failed with code $errorCode.")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            // Ignore callbacks from an old connection.
            if (gatt != bluetoothGatt) return

            if (
                newState == BluetoothProfile.STATE_CONNECTED &&
                status == BluetoothGatt.GATT_SUCCESS
            ) {
                _state.value = SetupUiState(
                    message = "Connected. Checking Arduino services…",
                    busy = true
                )

                if (!gatt.discoverServices()) {
                    showError("Connected, but service discovery could not start.")
                }
            } else if (
                newState == BluetoothProfile.STATE_DISCONNECTED ||
                status != BluetoothGatt.GATT_SUCCESS
            ) {
                showError("Connection failed or was lost. Please try again.")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt != bluetoothGatt) return

            val expectedServiceFound =
                status == BluetoothGatt.GATT_SUCCESS &&
                    gatt.getService(SERVICE_UUID) != null

            if (expectedServiceFound) {
                _state.value = SetupUiState(
                    message = "Connected to ASL-Glove. Setup is complete.",
                    connected = true
                )
            } else {
                showError(
                    "The board connected, but the expected ASL service was not found. " +
                        "Check the Arduino sketch and service UUID."
                )
            }
        }
    }

    private fun stopScan() {
        if (!scanActive) return

        scanActive = false

        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
            // Bluetooth may have been disabled or permission revoked.
        }
    }

    private fun closeGatt() {
        val oldGatt = bluetoothGatt
        bluetoothGatt = null

        try {
            oldGatt?.disconnect()
        } catch (_: Exception) {
        }

        try {
            oldGatt?.close()
        } catch (_: Exception) {
        }
    }

    private fun cancelOperations() {
        scanTimeout?.cancel()
        scanTimeout = null
        stopScan()
        closeGatt()
    }

    override fun onCleared() {
        cancelOperations()
        super.onCleared()
    }
}
