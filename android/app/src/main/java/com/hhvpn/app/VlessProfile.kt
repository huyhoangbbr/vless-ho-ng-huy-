package com.hhvpn.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

internal data class VlessProfile(
    val name: String,
    val host: String,
    val port: Int,
    val userId: String,
    val transport: String,
    val security: String,
    val params: Map<String, String>,
) {
    fun toXrayConfig(): JSONObject {
        val stream = JSONObject()
            .put("network", transport)
            .put("security", security)

        when (security) {
            "tls" -> {
                val tls = JSONObject()
                    .put("serverName", params["sni"] ?: host)
                    .put("allowInsecure", false)
                params["fp"]?.let { tls.put("fingerprint", it) }
                params["alpn"]?.let { value ->
                    tls.put("alpn", JSONArray(value.split(',').map(String::trim).filter(String::isNotEmpty)))
                }
                stream.put("tlsSettings", tls)
            }
            "reality" -> stream.put(
                "realitySettings",
                JSONObject()
                    .put("serverName", params.getValue("sni"))
                    .put("fingerprint", params["fp"]?.takeIf(String::isNotEmpty) ?: "chrome")
                    .put("publicKey", params.getValue("pbk"))
                    .put("shortId", params["sid"] ?: "")
                    .put("spiderX", params["spx"] ?: "/")
                    .apply { params["pqv"]?.takeIf(String::isNotEmpty)?.let { put("mldsa65Verify", it) } },
            )
        }

        when (transport) {
            "ws" -> {
                val ws = JSONObject().put("path", params["path"] ?: "/")
                params["host"]?.takeIf(String::isNotEmpty)?.let {
                    ws.put("headers", JSONObject().put("Host", it))
                }
                stream.put("wsSettings", ws)
            }
            "grpc" -> stream.put(
                "grpcSettings",
                JSONObject()
                    .put("serviceName", params["serviceName"] ?: "")
                    .put("authority", params["authority"] ?: params["host"] ?: "")
                    .put("multiMode", params["mode"] == "multi"),
            )
            "httpupgrade", "xhttp" -> {
                val settings = JSONObject()
                    .put("path", params["path"] ?: "/")
                    .put("host", params["host"] ?: "")
                if (transport == "xhttp") {
                    settings.put("mode", params["mode"]?.takeIf(String::isNotEmpty) ?: "auto")
                    params["extra"]?.let { settings.put("extra", JSONObject(it)) }
                }
                stream.put("${transport}Settings", settings)
            }
        }

        val user = JSONObject()
            .put("id", userId)
            .put("encryption", params["encryption"] ?: "none")
        params["flow"]?.takeIf(String::isNotEmpty)?.let { user.put("flow", it) }

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put(
                "inbounds",
                JSONArray().put(
                    JSONObject()
                        .put("tag", "tun")
                        .put("protocol", "tun")
                        .put("settings", JSONObject().put("name", "hhvpn0").put("mtu", 1500)),
                ),
            )
            .put(
                "outbounds",
                JSONArray().put(
                    JSONObject()
                        .put("tag", "proxy")
                        .put("protocol", "vless")
                        .put(
                            "settings",
                            JSONObject().put(
                                "vnext",
                                JSONArray().put(
                                    JSONObject()
                                        .put("address", host)
                                        .put("port", port)
                                        .put("users", JSONArray().put(user)),
                                ),
                            ),
                        )
                        .put("streamSettings", stream),
                ),
            )
    }

    companion object {
        private val knownParams = setOf(
            "type", "security", "encryption", "flow", "sni", "fp", "alpn", "pbk", "sid", "spx", "pqv",
            "host", "path", "serviceName", "authority", "mode", "extra", "headerType", "allowInsecure",
            "insecure",
        )

        fun parse(link: String): VlessProfile {
            val text = link.trim()
            require(text.isNotEmpty() && text.length <= 32_768 && text.none(Char::isWhitespace)) {
                "Paste one complete vless:// link with no internal whitespace."
            }
            val uri = try {
                URI(text)
            } catch (_: Exception) {
                throw IllegalArgumentException("The VLESS link is malformed.")
            }
            val rawUserInfo = uri.rawUserInfo
                ?: throw IllegalArgumentException("Expected a vless://UUID@server:port link.")
            require(uri.scheme.equals("vless", ignoreCase = true)) {
                "Expected a vless://UUID@server:port link."
            }
            require(rawUserInfo.none { it == ':' } && (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")) {
                "The VLESS link has an unsupported user-info or path."
            }
            val uuid = try {
                UUID.fromString(decode(rawUserInfo))
            } catch (_: Exception) {
                throw IllegalArgumentException("The VLESS link must contain a valid UUID.")
            }

            val authority = uri.rawAuthority ?: throw IllegalArgumentException("The link has no server.")
            val at = authority.lastIndexOf('@')
            require(at > 0 && at == authority.indexOf('@')) { "The server address is malformed." }
            val hostPort = authority.substring(at + 1)
            val host: String
            val portText: String
            if (hostPort.startsWith("[")) {
                val end = hostPort.indexOf(']')
                require(end > 1 && hostPort.getOrNull(end + 1) == ':') { "IPv6 addresses must use [brackets]." }
                host = hostPort.substring(1, end)
                portText = hostPort.substring(end + 2)
                require(host.contains(':') && !host.contains('%') && InetAddress.getByName(host) is Inet6Address) {
                    "The IPv6 server address is invalid."
                }
            } else {
                val colon = hostPort.lastIndexOf(':')
                require(colon > 0 && hostPort.indexOf(':') == colon) { "IPv6 addresses must use [brackets]." }
                val suppliedHost = hostPort.substring(0, colon)
                host = try {
                    IDN.toASCII(suppliedHost, IDN.USE_STD3_ASCII_RULES).lowercase()
                } catch (_: Exception) {
                    throw IllegalArgumentException("The server hostname is invalid.")
                }
                val labels = host.removeSuffix(".").split('.')
                require(host.length <= 253 && labels.all {
                    it.length in 1..63 && it.first().isLetterOrDigit() && it.last().isLetterOrDigit()
                }) { "The server hostname is invalid." }
                portText = hostPort.substring(colon + 1)
            }
            val port = portText.takeIf { it.matches(Regex("[0-9]{1,5}")) }?.toIntOrNull()
            require(port != null && port in 1..65_535) { "The server port must be between 1 and 65535." }

            val params = linkedMapOf<String, String>()
            uri.rawQuery?.takeIf(String::isNotEmpty)?.split('&')?.forEach { part ->
                val split = part.indexOf('=')
                val key = decode(if (split < 0) part else part.substring(0, split))
                val value = decode(if (split < 0) "" else part.substring(split + 1))
                require(key !in params) { "Duplicate query parameter: $key" }
                require((key + value).none { it.code < 32 || it.code == 127 }) {
                    "Control characters are not allowed in link parameters."
                }
                params[key] = value
            }
            require(params.keys.all(knownParams::contains)) {
                "The VLESS link contains unsupported query parameters."
            }

            val transportAlias = params["type"]?.lowercase() ?: "tcp"
            val transport = when (transportAlias) {
                "raw" -> "tcp"
                "websocket" -> "ws"
                "splithttp" -> "xhttp"
                else -> transportAlias
            }
            require(transport in setOf("tcp", "ws", "grpc", "httpupgrade", "xhttp")) {
                "Supported transports are TCP, WebSocket, gRPC, HTTPUpgrade, and XHTTP."
            }
            val security = params["security"]?.lowercase() ?: "none"
            require(security in setOf("none", "tls", "reality")) {
                "Supported security modes are none, TLS, and REALITY."
            }
            val encryption = params["encryption"] ?: "none"
            require(encryption == "none" || encryption.startsWith("mlkem768x25519plus.")) {
                "The VLESS encryption setting is unsupported."
            }
            val flow = params["flow"] ?: ""
            require(flow in setOf("", "xtls-rprx-vision", "xtls-rprx-vision-udp443")) {
                "The VLESS flow is unsupported."
            }
            require(flow.isEmpty() || (transport == "tcp" && security != "none")) {
                "Vision flow requires TCP with TLS or REALITY."
            }
            if (security == "reality") {
                require(transport in setOf("tcp", "grpc", "xhttp")) { "REALITY requires TCP, gRPC, or XHTTP." }
                val publicKey = params["pbk"] ?: ""
                require(publicKey.matches(Regex("[A-Za-z0-9_-]{43}")) &&
                    runCatching { Base64.getUrlDecoder().decode("$publicKey=").size == 32 }.getOrDefault(false)
                ) { "REALITY needs a valid X25519 public key in pbk=." }
                require(!params["sni"].isNullOrEmpty()) { "REALITY needs the server name in sni=." }
                require((params["sid"] ?: "").matches(Regex("(?:[0-9a-fA-F]{2}){0,8}"))) {
                    "REALITY sid must be an even-length hex string, up to 16 characters."
                }
            }
            require(listOf("allowInsecure", "insecure").all {
                (params[it] ?: "0").lowercase() in setOf("0", "false", "")
            }) { "Insecure TLS links are refused." }
            require((params["headerType"] ?: "none") == "none") { "Legacy TCP HTTP-header camouflage is unsupported." }
            require(transport != "xhttp" || (params["mode"] ?: "") in setOf("", "auto", "packet-up", "stream-up", "stream-one")) {
                "The XHTTP mode is unsupported."
            }
            require(transport != "grpc" || (params["mode"] ?: "") in setOf("", "gun", "multi")) {
                "gRPC mode must be gun or multi."
            }
            params["extra"]?.let {
                require(transport == "xhttp") { "extra= is only supported for XHTTP." }
                try {
                    JSONObject(it)
                } catch (_: Exception) {
                    throw IllegalArgumentException("XHTTP extra must be a URL-encoded JSON object.")
                }
                require(it.trimStart().startsWith("{")) { "XHTTP extra must be a JSON object." }
            }

            val label = uri.rawFragment?.let(::decode).orEmpty().filterNot { it.code < 32 || it.code == 127 }
                .take(120)
            return VlessProfile(label.ifEmpty { host }, host, port, uuid.toString(), transport, security, params)
        }

        private fun decode(value: String): String =
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }
}
