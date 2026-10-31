package com.realme.tvremote.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.ConcurrentHashMap

class TvDiscovery(context: Context, private val onDevicesChanged: (List<TvDevice>) -> Unit) {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val devices = ConcurrentHashMap<String, TvDevice>()
    @Volatile private var active = false

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val host = serviceInfo.host?.hostAddress ?: return
            devices[host] = TvDevice(
                name = serviceInfo.serviceName.ifBlank { "Android TV" },
                host = host,
                port = serviceInfo.port,
            )
            onDevicesChanged(devices.values.sortedBy { it.name.lowercase() })
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            active = true
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (serviceInfo.serviceType.startsWith(SERVICE_TYPE.removeSuffix("."))) {
                runCatching { manager.resolveService(serviceInfo, resolveListener) }
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            val removed = devices.entries.removeIf { it.value.name == serviceInfo.serviceName }
            if (removed) onDevicesChanged(devices.values.sortedBy { it.name.lowercase() })
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            active = false
            runCatching { manager.stopServiceDiscovery(this) }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            runCatching { manager.stopServiceDiscovery(this) }
        }

        override fun onDiscoveryStopped(serviceType: String) {
            active = false
        }
    }

    fun start() {
        if (active) return
        devices.clear()
        onDevicesChanged(emptyList())
        manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stop() {
        if (active) runCatching { manager.stopServiceDiscovery(discoveryListener) }
    }

    companion object {
        private const val SERVICE_TYPE = "_androidtvremote2._tcp."
    }
}