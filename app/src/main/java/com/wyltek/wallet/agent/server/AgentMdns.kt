package com.wyltek.wallet.agent.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/** Advertises _blackbox-agent._tcp on the LAN while the gateway runs. Instance name = service_name;
 *  a device_id TXT record lets the POS match the exact instance if Android renames on collision. */
class AgentMdns(context: Context) {
    private val TAG = "AgentMdns"
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun register(serviceName: String, deviceId: String, port: Int) {
        unregister()
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = "_blackbox-agent._tcp."
            this.port = port
            setAttribute("device_id", deviceId)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) { Log.i(TAG, "mDNS registered: ${s.serviceName}") }
            override fun onRegistrationFailed(s: NsdServiceInfo, code: Int) { Log.w(TAG, "mDNS register failed: $code") }
            override fun onServiceUnregistered(s: NsdServiceInfo) { Log.i(TAG, "mDNS unregistered") }
            override fun onUnregistrationFailed(s: NsdServiceInfo, code: Int) { Log.w(TAG, "mDNS unregister failed: $code") }
        }
        listener = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun unregister() {
        listener?.let { try { nsd.unregisterService(it) } catch (_: Exception) {} }
        listener = null
    }
}
