package com.peerlink.app.network

import com.peerlink.app.cast.CastQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PeerSessionHolder {
    @Volatile
    var session: PeerSession? = null

    private val _casting = MutableStateFlow(false)
    val casting: StateFlow<Boolean> = _casting.asStateFlow()

    private val _quality = MutableStateFlow(CastQuality.Smooth)
    val quality: StateFlow<CastQuality> = _quality.asStateFlow()

    fun setCasting(active: Boolean) {
        _casting.value = active
    }

    fun setQuality(q: CastQuality) {
        _quality.value = q
    }
}
