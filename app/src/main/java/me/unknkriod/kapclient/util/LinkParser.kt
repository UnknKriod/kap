package me.unknkriod.kapclient.util

import android.net.Uri
import me.unknkriod.kapclient.data.VpnConfig
import java.net.URLDecoder

object LinkParser {

    data class ParseResult(
        val config: VpnConfig,
        val psk: String
    )

    fun parse(link: String): ParseResult? {
        try {
            val uri = Uri.parse(link)
            if (uri.scheme != "kap-proxy" && uri.scheme != "cap-proxy") {
                android.util.Log.w("KAP_DEBUG", "Invalid scheme: ${uri.scheme} in link: $link")
                return null
            }

            val uid = uri.userInfo ?: ""
            val host = uri.host ?: run {
                android.util.Log.w("KAP_DEBUG", "Missing host in link: $link")
                return null
            }
            val port = uri.port
            val psk = uri.getQueryParameter("psk") ?: ""
            val security = uri.getQueryParameter("security") // tls | none
            val cdn = uri.getQueryParameter("cdn") == "1"
            val sni = uri.getQueryParameter("sni")
            val remark = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: host

            val serverUrl = if (security == "tls") {
                "https://$host${if (port != -1 && port != 443) ":$port" else ""}"
            } else {
                "http://$host${if (port != -1 && port != 80) ":$port" else ""}"
            }

            val config = VpnConfig(
                name = remark,
                server = serverUrl,
                uid = uid.ifBlank { null },
                behindCdn = cdn,
                insecure = security != "tls",
                sni = sni
            )

            return ParseResult(config, psk)
        } catch (e: Exception) {
            return null
        }
    }
}
