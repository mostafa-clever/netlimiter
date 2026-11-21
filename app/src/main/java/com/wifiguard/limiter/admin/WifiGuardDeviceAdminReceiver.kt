package com.wifiguard.limiter.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.wifiguard.limiter.data.PreferencesManager
import com.wifiguard.limiter.model.AppRole
import com.wifiguard.limiter.network.LocalLanClient
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class WifiGuardDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, WifiGuardDeviceAdminReceiver::class.java)
        }

        fun isActive(context: Context): Boolean {
            return runCatching {
                val appContext = context.applicationContext
                val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                    ?: return false
                dpm.isAdminActive(getComponentName(appContext))
            }.getOrDefault(false)
        }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // Do not call goAsync() here because DeviceAdminReceiver synchronously sets result extras from the return value
        notifySupervisor(context, "محاولة إلغاء حماية الحذف من الجهاز", useGoAsync = false)
        return "تحذير: إلغاء هذه الحماية سيُرسل تنبيهاً فورياً لجهاز المشرف."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        notifySupervisor(context, "تم تعطيل حماية الحذف — قد يحاول المستخدم حذف التطبيق", useGoAsync = true)
    }

    private fun notifySupervisor(context: Context, reason: String, useGoAsync: Boolean) {
        val appContext = context.applicationContext
        val pending = if (useGoAsync) runCatching { goAsync() }.getOrNull() else null
        val handler = CoroutineExceptionHandler { _, _ -> }
        CoroutineScope(SupervisorJob() + Dispatchers.IO + handler).launch {
            try {
                withTimeoutOrNull(8_000L) {
                    runCatching {
                        val prefs = PreferencesManager(appContext)
                        if (prefs.roleFlow.first() != AppRole.CLIENT) return@runCatching
                        val host = prefs.getSupervisorHost()
                        if (host.isBlank()) return@runCatching
                        val port = prefs.getSupervisorPort()
                        val id = prefs.getOrCreateDeviceId()
                        val name = prefs.getDeviceName()
                        val token = prefs.getClientAuthToken()
                        LocalLanClient.sendTamperAlert(host, port, id, name, "$reason ($name)", authToken = token)
                    }
                }
            } finally {
                runCatching { pending?.finish() }
            }
        }
    }
}
