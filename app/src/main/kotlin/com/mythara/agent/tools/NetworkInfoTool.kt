package com.mythara.agent.tools

import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `network_info` — list every active network interface on the device
 * with its IPv4/IPv6 addresses and MAC.
 *
 * Why this exists when we have `run_shell` already:
 *
 * Android restricts netlink-socket access for normal app UIDs. The
 * traditional Linux tools — `ip addr`, `ifconfig`, `hostname -I`,
 * `netstat -i` — therefore fail with "Permission denied" from inside
 * Mythara's process. The agent kept reaching for them and getting
 * back unusable errors.
 *
 * Java's [NetworkInterface] API is the documented way to enumerate
 * the device's interfaces from app code. It works without any
 * special permission, returns the same data those shell tools
 * would, and doesn't require netlink.
 *
 * Returns JSON shaped like:
 *   {
 *     "interfaces": [
 *       {
 *         "name": "wlan0",
 *         "up": true,
 *         "loopback": false,
 *         "mtu": 1500,
 *         "mac": "aa:bb:cc:dd:ee:ff",
 *         "ipv4": ["10.0.0.146"],
 *         "ipv6": ["fe80::200:ff:fe00:0%wlan0"]
 *       },
 *       ...
 *     ]
 *   }
 */
@Singleton
class NetworkInfoTool @Inject constructor() : Tool {
    override val name = "network_info"
    override val description =
        "List every network interface (wlan/cellular/loopback) with IPv4/IPv6 addresses and MAC. " +
            "Use this instead of `ip addr` / `ifconfig` / `hostname -I` — those fail on Android due to " +
            "netlink permissions, but this Java API works without any extra grant."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        return runCatching {
            val out = StringBuilder("{\"interfaces\":[")
            var first = true
            val ifs = NetworkInterface.getNetworkInterfaces() ?: return ToolResult.ok("""{"interfaces":[]}""")
            for (nif in ifs) {
                if (!first) out.append(',')
                first = false
                val mac = nif.hardwareAddress
                    ?.joinToString(":") { "%02x".format(it) }
                    .orEmpty()
                val v4 = mutableListOf<String>()
                val v6 = mutableListOf<String>()
                for (addr in nif.inetAddresses) {
                    when (addr) {
                        is Inet4Address -> v4 += addr.hostAddress.orEmpty()
                        is Inet6Address -> v6 += addr.hostAddress.orEmpty()
                    }
                }
                out.append('{')
                out.append("\"name\":\"${nif.name.escape()}\",")
                out.append("\"up\":${runCatching { nif.isUp }.getOrDefault(false)},")
                out.append("\"loopback\":${runCatching { nif.isLoopback }.getOrDefault(false)},")
                out.append("\"mtu\":${runCatching { nif.mtu }.getOrDefault(-1)},")
                out.append("\"mac\":\"${mac.escape()}\",")
                out.append("\"ipv4\":[${v4.joinToString(",") { "\"${it.escape()}\"" }}],")
                out.append("\"ipv6\":[${v6.joinToString(",") { "\"${it.escape()}\"" }}]")
                out.append('}')
            }
            out.append("]}")
            ToolResult.ok(out.toString())
        }.getOrElse { ToolResult.fail("network_info_failed: ${it.message ?: it.javaClass.simpleName}") }
    }

    private fun String.escape(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")
}
