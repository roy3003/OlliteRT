/*
 * Copyright 2025-2026 @NightMean (https://github.com/NightMean)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ollitert.llm.server.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import com.ollitert.llm.server.data.ServerPrefs
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

private const val TAG = "OlliteRT.WifiUtils"

// ── Multi-interface endpoint discovery ──────────────────────────────────────

/** Type of network interface for endpoint selection. */
enum class EndpointType { LOCAL, VPN, LOOPBACK, ALL_INTERFACES }

/**
 * A discovered network endpoint: an interface name + IPv4 address + type.
 * Used by the Status screen's Active API Endpoint selector.
 */
data class EndpointInfo(
  val interfaceName: String,
  val ipAddress: String,
  val type: EndpointType,
) {
  /** Human-friendly display label, e.g. "Wi-Fi（192.168.1.10）" or "Tailscale（100.64.0.5）". */
  val displayName: String get() = "${interfaceLabel()}（$ipAddress）"

  /** Short type label for display, e.g. "Wi-Fi", "Tailscale", "Loopback", "All interfaces". */
  fun interfaceLabel(): String = when (type) {
    EndpointType.LOCAL -> "Wi-Fi" // wlan* and eth* mapped to Wi-Fi; Ethernet rare on phones
    EndpointType.VPN -> vpnInterfaceLabel(interfaceName)
    EndpointType.LOOPBACK -> "Loopback"
    EndpointType.ALL_INTERFACES -> "All interfaces"
  }
}

/** Map VPN interface names to human-friendly labels. */
private fun vpnInterfaceLabel(name: String): String = when {
  name.startsWith("tailscale") -> "Tailscale"
  name.startsWith("wg") -> "WireGuard"
  name.startsWith("tun") -> "VPN"
  else -> "VPN"
}

/** Prefixes for cellular interfaces — always excluded. */
private val CELLULAR_PREFIXES = listOf("rmnet", "pdp", "ccmni")

/** Prefixes for local (non-VPN, non-loopback) interfaces. */
private val LOCAL_PREFIXES = listOf("wlan", "eth", "usb")

/** Prefixes for VPN interfaces. */
private val VPN_PREFIXES = listOf("tun", "tailscale", "wg")

/**
 * Scans all up network interfaces and returns available endpoints, grouped by type:
 * LOCAL (Wi-Fi/Ethernet) first, then VPN, then Loopback, then All-interfaces (0.0.0.0).
 * Cellular interfaces are always excluded.
 */
fun getAvailableEndpoints(): List<EndpointInfo> {
  val local = mutableListOf<EndpointInfo>()
  val vpn = mutableListOf<EndpointInfo>()
  var loopback: EndpointInfo? = null

  try {
    for (iface in NetworkInterface.getNetworkInterfaces()) {
      if (!iface.isUp || iface.isLoopback) continue
      val name = iface.name
      if (CELLULAR_PREFIXES.any { name.startsWith(it) }) continue

      val type = when {
        LOCAL_PREFIXES.any { name.startsWith(it) } -> EndpointType.LOCAL
        VPN_PREFIXES.any { name.startsWith(it) } -> EndpointType.VPN
        else -> null // unknown interface type — skip
      } ?: continue

      for (addr in iface.inetAddresses) {
        if (addr is Inet4Address && !addr.isLoopbackAddress) {
          val ep = EndpointInfo(name, addr.hostAddress ?: continue, type)
          when (type) {
            EndpointType.LOCAL -> local.add(ep)
            EndpointType.VPN -> vpn.add(ep)
            else -> {}
          }
        }
      }
    }
  } catch (e: SocketException) {
    Log.d(TAG, "NetworkInterface enumeration failed", e)
  }

  // Loopback
  try {
    for (iface in NetworkInterface.getNetworkInterfaces()) {
      if (iface.isUp && iface.isLoopback) {
        for (addr in iface.inetAddresses) {
          if (addr is Inet4Address) {
            loopback = EndpointInfo(iface.name, addr.hostAddress ?: "127.0.0.1", EndpointType.LOOPBACK)
            break
          }
        }
        if (loopback != null) break
      }
    }
  } catch (e: SocketException) {
    Log.d(TAG, "Loopback enumeration failed", e)
  }
  if (loopback == null) loopback = EndpointInfo("lo", "127.0.0.1", EndpointType.LOOPBACK)

  val result = mutableListOf<EndpointInfo>()
  result.addAll(local)
  result.addAll(vpn)
  result.add(loopback)
  result.add(EndpointInfo("0.0.0.0", "0.0.0.0", EndpointType.ALL_INTERFACES))
  return result
}

/**
 * Picks the default endpoint from a scan result list using the priority:
 * 1. First LOCAL, 2. First VPN, 3. Loopback. Never returns 0.0.0.0.
 */
fun pickDefaultEndpoint(endpoints: List<EndpointInfo>): EndpointInfo =
  endpoints.firstOrNull { it.type == EndpointType.LOCAL }
    ?: endpoints.firstOrNull { it.type == EndpointType.VPN }
    ?: endpoints.firstOrNull { it.type == EndpointType.LOOPBACK }
    ?: EndpointInfo("lo", "127.0.0.1", EndpointType.LOOPBACK)

/**
 * Resolves the active endpoint at server start time.
 *
 * 1. Read saved ip + interface from prefs.
 * 2. Scan available endpoints.
 * 3. If saved IP is still present, keep using it.
 * 4. Otherwise, pick the default by priority (LOCAL > VPN > Loopback).
 */
fun resolveActiveEndpoint(context: Context): EndpointInfo {
  val savedIp = ServerPrefs.getSelectedEndpointIp(context)
  val savedIface = ServerPrefs.getSelectedEndpointInterface(context)
  val endpoints = getAvailableEndpoints()

  if (savedIp != null) {
    // Match by IP first, then by interface name as fallback.
    val match = endpoints.firstOrNull { it.ipAddress == savedIp }
      ?: endpoints.firstOrNull { savedIface != null && it.interfaceName == savedIface }
    if (match != null && match.type != EndpointType.ALL_INTERFACES) {
      return match
    }
    // If saved was 0.0.0.0, keep it only if user explicitly chose it before
    if (savedIp == "0.0.0.0") {
      val allIf = endpoints.firstOrNull { it.type == EndpointType.ALL_INTERFACES }
      if (allIf != null) return allIf
    }
  }

  // No saved match — pick default and persist it.
  val chosen = pickDefaultEndpoint(endpoints)
  ServerPrefs.setSelectedEndpointIp(context, chosen.ipAddress)
  ServerPrefs.setSelectedEndpointInterface(context, chosen.interfaceName)
  return chosen
}

// ── Legacy Wi-Fi helpers (retained for HomeAssistantCard, DownloadAndTryButton) ──

/** Checks whether the device currently has an active Wi-Fi connection. */
fun isWifiConnected(context: Context): Boolean {
  val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    ?: return false
  val network = cm.activeNetwork ?: return false
  val caps = cm.getNetworkCapabilities(network) ?: return false
  return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}

/**
 * Returns the device's Wi-Fi IPv4 address, or `null` if unavailable.
 *
 * Tries the WifiManager first, then falls back to enumerating network interfaces
 * (works better on newer Android versions where WifiInfo is deprecated).
 */
fun getWifiIpAddress(context: Context): String? {
  // Approach 1: WifiManager (works on most devices)
  val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
  if (wifiManager != null) {
    @Suppress("DEPRECATION")
    val ip = wifiManager.connectionInfo.ipAddress
    if (ip != 0) {
      return formatIpAddress(ip)
    }
  }

  // Approach 2: Enumerate network interfaces
  try {
    for (iface in NetworkInterface.getNetworkInterfaces()) {
      if (!iface.isUp || iface.isLoopback) continue
      // Look for wlan interfaces
      if (!iface.name.startsWith("wlan")) continue
      for (addr in iface.inetAddresses) {
        if (addr is Inet4Address && !addr.isLoopbackAddress) {
          return addr.hostAddress
        }
      }
    }
  } catch (e: SocketException) {
    // Expected when no WiFi (mobile-data only, airplane mode); fall through to null IP.
    Log.d(TAG, "NetworkInterface enumeration failed; will fall through to null IP", e)
  }

  return null
}

private fun formatIpAddress(ip: Int): String {
  return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
}
