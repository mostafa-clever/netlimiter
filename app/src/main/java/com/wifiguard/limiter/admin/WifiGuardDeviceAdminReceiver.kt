package com.wifiguard.limiter.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.wifiguard.limiter.data.PreferencesManager
import com.wifiguard.limiter.network.LocalLanClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WifiGuardDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, WifiGuardDeviceAdminReceiver::class.java)
        }

        fun isActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isAdminActive(getComponentName(context))
        }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        notifySupervisor(context, "محاولة إلغاء حماية الحذف من الجهاز")
        return "تحذير: إلغاء هذه الحماية سيُرسل تنبيهاً فورياً لجهاز المشرف."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        notifySupervisor(context, "تم تعطيل حماية الحذف — قد يحاول المستخدم حذف التطبيق")
    }

    private fun notifySupervisor(context: Context, reason: String) {
        val prefs = PreferencesManager(context.applicationContext)
        CoroutineScope(Dispatchers.IO).launch {
            val host = prefs.getSupervisorHost()
            val port = prefs.getSupervisorPort()
            val id = prefs.getOrCreateDeviceId()
            val name = prefs.getDeviceName()
            LocalLanClient.sendTamperAlert(host, port, id, name, "$reason ($name)")
        }
    }
}
