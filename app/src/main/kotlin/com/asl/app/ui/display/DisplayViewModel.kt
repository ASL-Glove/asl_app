package com.asl.app.ui.display

import androidx.lifecycle.ViewModel
import com.asl.app.ble.BleConnectionState
import com.asl.app.ble.BleGloveClient
import kotlinx.coroutines.flow.StateFlow

class DisplayViewModel : ViewModel() {
    val caption: StateFlow<String> = BleGloveClient.caption
    val connection: StateFlow<BleConnectionState> = BleGloveClient.state

    fun clear() = BleGloveClient.clearCaption()
}
