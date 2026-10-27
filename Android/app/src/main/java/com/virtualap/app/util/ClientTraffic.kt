package com.virtualap.app.util

import java.util.Locale

data class ConnectedClient(
    val macAddress: String,
    val name: String? = null,
    val ipAddress: String? = null,
    val receivedBytes: Long? = null,
    val transmittedBytes: Long? = null
) {
    // Both directions count as usage; the UI shows their sum.
    val totalBytes: Long?
        get() {
            val rx = receivedBytes ?: return null
            val tx = transmittedBytes ?: return null
            return if (Long.MAX_VALUE - rx < tx) Long.MAX_VALUE else rx + tx
        }
}

/** Parses backend output as data. A DHCP hostname never becomes a shell command. */
object ClientTraffic {
    private val macPattern = Regex("(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")
    private val whitespace = Regex("\\s+")
    private val sections = setOf("[stations]", "[connected]", "[driver]", "[leases]", "[accounting]", "[end_clients]")

    private class Station(val mac: String) {
        var flags: String? = null
        var name: String? = null
        var rx: Long? = null
        var tx: Long? = null
        var rx64: Long? = null
        var tx64: Long? = null
    }

    fun parse(text: String): List<ConnectedClient> {
        val stations = linkedMapOf<String, Station>()
        val driver = linkedMapOf<String, Station>()
        val leases = mutableMapOf<String, Pair<String?, String?>>()
        val connected = linkedSetOf<String>()
        val accountedRx = mutableMapOf<String, Long>()
        val accountedTx = mutableMapOf<String, Long>()
        val selfMac = text.lineSequence().firstOrNull { it.startsWith("ap_mac=") }
            ?.substringAfter('=')?.trim()?.lowercase(Locale.ROOT)
        var hasConnectedList = false
        var section = ""
        var station: Station? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line in sections) {
                if (line == "[connected]") hasConnectedList = true
                section = line
                station = null
                continue
            }
            when (section) {
                "[stations]" -> {
                    if (macPattern.matches(line)) {
                        val mac = line.lowercase(Locale.ROOT)
                        station = stations.getOrPut(mac) { Station(mac) }
                    } else {
                        val key = line.substringBefore('=')
                        val value = line.substringAfter('=', "")
                        val count = value.toLongOrNull()?.takeIf { it >= 0 }
                        when (key) {
                            "flags" -> station?.flags = value
                            "wps_model_name", "wps_device_name" -> station?.name = cleanName(value)
                            "rx_bytes" -> station?.rx = count
                            "tx_bytes" -> station?.tx = count
                            "rx_bytes_64" -> station?.rx64 = count
                            "tx_bytes_64" -> station?.tx64 = count
                        }
                    }
                }
                "[connected]" -> {
                    val mac = line.lowercase(Locale.ROOT)
                    if (isClientMac(mac) && mac != selfMac) connected.add(mac)
                }
                "[accounting]" -> {
                    val fields = line.split(whitespace)
                    if (fields.size == 3 && fields[1].length == 12) {
                        val mac = fields[1].chunked(2).joinToString(":").lowercase(Locale.ROOT)
                        val bytes = fields[2].toLongOrNull()?.takeIf { it >= 0 }
                        if (isClientMac(mac) && bytes != null) {
                            when (fields[0]) {
                                "rx" -> accountedRx[mac] = bytes
                                "tx" -> accountedTx[mac] = bytes
                            }
                        }
                    }
                }
                "[driver]" -> {
                    if (line.startsWith("Station ")) {
                        val mac = line.substringAfter("Station ").substringBefore(' ').lowercase(Locale.ROOT)
                        station = if (macPattern.matches(mac)) driver.getOrPut(mac) { Station(mac) } else null
                    } else {
                        val value = line.substringAfter(':', "").trim().toLongOrNull()?.takeIf { it >= 0 }
                        when (line.substringBefore(':').trim()) {
                            "rx bytes" -> station?.rx = value
                            "tx bytes" -> station?.tx = value
                        }
                    }
                }
                "[leases]" -> {
                    val fields = line.split(whitespace)
                    if (fields.size >= 4 && macPattern.matches(fields[1])) {
                        val ip = fields[2].takeIf(::isIpv4)
                        leases[fields[1].lowercase(Locale.ROOT)] = cleanName(fields[3]) to ip
                    }
                }
            }
        }
        // Only hostapd establishes presence. A driver dump can contain the AP,
        // broadcast placeholders or stale stations even when nobody is connected.
        val peers = if (hasConnectedList) {
            connected.map { stations[it] ?: Station(it) }
        } else {
            stations.values.filter { "[ASSOC]" in it.flags.orEmpty() && "[AUTHORIZED]" in it.flags.orEmpty() }
        }
        return peers.filter { isClientMac(it.mac) && it.mac != selfMac }.map { peer ->
            val fallback = driver[peer.mac]
            val accounted = accountedRx.containsKey(peer.mac) && accountedTx.containsKey(peer.mac)
            val rx = if (accounted) accountedRx[peer.mac] else peer.rx64 ?: peer.rx ?: fallback?.rx
            val tx = if (accounted) accountedTx[peer.mac] else peer.tx64 ?: peer.tx ?: fallback?.tx
            val lease = leases[peer.mac]
            ConnectedClient(peer.mac, peer.name ?: lease?.first, lease?.second, rx, tx)
        }
    }

    private fun isClientMac(mac: String): Boolean = macPattern.matches(mac) &&
        mac != "00:00:00:00:00:00" && (mac.substring(0, 2).toInt(16) and 1) == 0

    private fun cleanName(value: String): String? = value
        .filter { !it.isISOControl() && it != '\u202E' && it != '\u202D' }
        .trim().take(64).takeUnless { it.isEmpty() || it == "*" || it.equals("unknown", ignoreCase = true) }

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull()?.let { n -> n in 0..255 } == true }
    }

    fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")
        var value = bytes.coerceAtLeast(0).toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val number = if (unit == 0 || value >= 10 || value % 1.0 < 0.05) {
            String.format(Locale.US, "%.0f", value)
        } else {
            String.format(Locale.US, "%.1f", value)
        }
        return "$number ${units[unit]}"
    }
}
