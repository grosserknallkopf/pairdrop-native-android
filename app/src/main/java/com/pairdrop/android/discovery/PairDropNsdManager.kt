package com.pairdrop.android.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.pairdrop.android.server.ServiceEndpoint
import com.pairdrop.android.util.Constants
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class PairDropNsdManager(
    context: Context,
    private val nodeId: String
) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val endpoints = ConcurrentHashMap<String, ServiceEndpoint>()
    private val resolving = AtomicBoolean(false)
    private var registered = false
    private var discovering = false

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            registered = true
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "NSD registration failed: $errorCode")
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            registered = false
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "NSD unregistration failed: $errorCode")
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            discovering = true
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (serviceInfo.serviceType != Constants.NSD_SERVICE_TYPE) return
            resolve(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            val keyPrefix = serviceInfo.serviceName
            endpoints.keys.filter { it.startsWith("$keyPrefix|") }.forEach { endpoints.remove(it) }
        }

        override fun onDiscoveryStopped(serviceType: String) {
            discovering = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            discovering = false
            Log.w(TAG, "NSD discovery start failed: $errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            discovering = false
            Log.w(TAG, "NSD discovery stop failed: $errorCode")
        }
    }

    fun start(port: Int) {
        register(port)
        discover()
    }

    fun stop() {
        if (discovering) {
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        }
        if (registered) {
            runCatching { nsdManager.unregisterService(registrationListener) }
        }
        endpoints.clear()
    }

    fun snapshot(): List<ServiceEndpoint> = endpoints.values.toList()

    private fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "PairDrop-${Build.MODEL}-${nodeId.take(6)}"
            serviceType = Constants.NSD_SERVICE_TYPE
            setPort(port)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAttribute("nodeId", nodeId)
            }
        }
        runCatching {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        }.onFailure { Log.w(TAG, "Could not register NSD service", it) }
    }

    private fun discover() {
        runCatching {
            nsdManager.discoverServices(
                Constants.NSD_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
        }.onFailure { Log.w(TAG, "Could not start NSD discovery", it) }
    }

    private fun resolve(serviceInfo: NsdServiceInfo) {
        if (!resolving.compareAndSet(false, true)) return
        nsdManager.resolveService(
            serviceInfo,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    resolving.set(false)
                    Log.w(TAG, "NSD resolve failed: $errorCode")
                }

                override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                    resolving.set(false)
                    val host = resolvedInfo.host?.hostAddress ?: return
                    val port = resolvedInfo.port
                    val remoteNodeId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        resolvedInfo.attributes["nodeId"]?.toString(Charsets.UTF_8)
                    } else {
                        null
                    }
                    if (remoteNodeId == nodeId || port <= 0) return
                    val endpoint = ServiceEndpoint(host = host, port = port, nodeId = remoteNodeId)
                    endpoints["${resolvedInfo.serviceName}|${endpoint.key}"] = endpoint
                }
            }
        )
    }

    companion object {
        private const val TAG = "PairDropNsd"
    }
}
