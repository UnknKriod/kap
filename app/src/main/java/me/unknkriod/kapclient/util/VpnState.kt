package me.unknkriod.kapclient.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VpnStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    STOPPING,
    PAUSED,
    OPTIMIZING
}

object VpnState {
    private val _status = MutableStateFlow(VpnStatus.DISCONNECTED)
    val status = _status.asStateFlow()

    private val _upSpeed = MutableStateFlow(0L)
    val upSpeed = _upSpeed.asStateFlow()

    private val _downSpeed = MutableStateFlow(0L)
    val downSpeed = _downSpeed.asStateFlow()

    private val _connectionStartTime = MutableStateFlow(0L)
    val connectionStartTime = _connectionStartTime.asStateFlow()

    private val _activeConfigId = MutableStateFlow(-1L)
    val activeConfigId = _activeConfigId.asStateFlow()

    fun updateActiveConfigId(id: Long) {
        _activeConfigId.value = id
    }

    fun updateStatus(newStatus: VpnStatus) {
        _status.value = newStatus
        when (newStatus) {
            VpnStatus.CONNECTED -> {
                if (_connectionStartTime.value <= 0L) {
                    _connectionStartTime.value = System.currentTimeMillis()
                }
            }
            VpnStatus.CONNECTING -> {
                _connectionStartTime.value = 0L
            }
            VpnStatus.DISCONNECTED, VpnStatus.STOPPING -> {
                _connectionStartTime.value = 0L
            }
            VpnStatus.PAUSED -> {
                // Keep the start time or reset? Usually pause stops the timer.
                _connectionStartTime.value = 0L
            }
            VpnStatus.OPTIMIZING -> {
                // Keep current start time
            }
        }
    }

    fun updateStats(up: Long, down: Long) {
        _upSpeed.value = up
        _downSpeed.value = down
    }
}
