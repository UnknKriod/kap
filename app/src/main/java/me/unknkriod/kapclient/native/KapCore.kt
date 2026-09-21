package me.unknkriod.kapclient.native

object KapCore {
    var isLoaded = false
        private set

    init {
        try {
            System.loadLibrary("kapgo")
            System.loadLibrary("kapcore")
            isLoaded = true
        } catch (e: Throwable) {
            android.util.Log.e("KapCore", "CRITICAL: Failed to load native libraries", e)
        }
    }

    /**
     * Важно: количество и типы параметров должны строго совпадать с kapcore.cpp
     */
    external fun start(
        serverUrl: String,
        psk: ByteArray,
        uid: String?,
        hwid: String?,
        socks5Addr: String,
        behindCdn: Boolean,
        debug: Boolean,
        downlinkWorkers: Int,
        uplinkPipeline: Int,
        insecure: Boolean,
        cookieUplink: Boolean
    ): Int

    external fun startMulti(
        serverUrl: String,
        endpointsJson: String?,
        endpointsBehindCdnJson: String?,
        endpointsCookieUplinkJson: String?,
        psk: ByteArray,
        uid: String?,
        hwid: String?,
        socks5Addr: String,
        behindCdn: Boolean,
        debug: Boolean,
        downlinkWorkers: Int,
        uplinkPipeline: Int,
        insecure: Boolean,
        cookieUplink: Boolean
    ): Int

    external fun stop(): Int

    external fun startSingBox(configJson: String, tunFd: Int): Int
    external fun stopSingBox(): Int
    
    external fun getStats(): String

    external fun convertDatToSrs(isGeosite: Boolean, inputPath: String, outputPath: String, category: String): Int

    external fun convertDatToSrsAll(isGeosite: Boolean, inputPath: String, outputDir: String, prefix: String): Int
}
