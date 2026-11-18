package com.asl.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.asl.app.ble.BleConnectionState
import com.asl.app.ble.BleGloveClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class SetupUiState(
    val message: String = "Power on the Arduino, then tap Find and connect.",
    val busy: Boolean = false,
    val connected: Boolean = false,
)

class BleSetupViewModel(application: Application) : AndroidViewModel(application) {

    init {
        BleGloveClient.initialize(application)
    }

    /** Set when the screen itself rejects the flow (missing permission, Bluetooth off). */
    private val localError = MutableStateFlow<String?>(null)

    val state: StateFlow<SetupUiState> =
        combine(BleGloveClient.state, localError) { connection, error ->
            error?.let { SetupUiState(message = it) } ?: connection.toUiState()
        }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                BleGloveClient.state.value.toUiState(),
            )

    fun isBluetoothEnabled(): Boolean = BleGloveClient.isBluetoothEnabled()

    fun scanAndConnect() {
        localError.value = null
        BleGloveClient.scanAndConnect()
    }

    fun showError(message: String) {
        BleGloveClient.disconnect()
        localError.value = message
    }

    fun reset() {
        localError.value = null
        BleGloveClient.disconnect()
    }
}

private fun BleConnectionState.toUiState(): SetupUiState =
    when (this) {
        BleConnectionState.Idle -> SetupUiState()
        BleConnectionState.Scanning -> SetupUiState("Scanning for ASL-Glove…", busy = true)
        BleConnectionState.Connecting -> SetupUiState("Arduino found. Connecting…", busy = true)
        BleConnectionState.Discovering ->
            SetupUiState("Connected. Checking Arduino services…", busy = true)

        BleConnectionState.Connected ->
            SetupUiState("Connected to ASL-Glove. Tap Continue to start the display.", connected = true)

        is BleConnectionState.Failed -> SetupUiState(message)
    }
