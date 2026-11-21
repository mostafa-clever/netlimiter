package com.wifiguard.limiter.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.wifiguard.limiter.data.PreferencesManager
import com.wifiguard.limiter.model.AppRole
import com.wifiguard.limiter.network.ClientGuardService
import com.wifiguard.limiter.network.SupervisorServerService
import com.wifiguard.limiter.vpn.WifiGuardVpnService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val appContext = context.applicationContext
            val pending = goAsync()
            val handler = CoroutineExceptionHandler { _, _ -> }
            CoroutineScope(SupervisorJob() + Dispatchers.IO + handler).launch {
                try {
                    withTimeoutOrNull(8_000L) {
                        val prefs = PreferencesManager(appContext)
                        val role = runCatching { prefs.roleFlow.first() }.getOrDefault(AppRole.UNSET)
                        val selfLimit = runCatching { prefs.selfLimitEnabledFlow.first() }.getOrDefault(false)
                        if (role == AppRole.SUPERVISOR) {
                            runCatching { SupervisorServerService.start(appContext) }
                            if (selfLimit && runCatching { VpnService.prepare(appContext) == null }.getOrDefault(false)) {
                                runCatching { WifiGuardVpnService.start(appContext) }
                            }
                        } else if (role == AppRole.CLIENT) {
                            runCatching { ClientGuardService.start(appContext) }
                            if (runCatching { VpnService.prepare(appContext) == null }.getOrDefault(false)) {
                                runCatching { WifiGuardVpnService.start(appContext) }
                            }
                        }
                    }
                } finally {
                    runCatching { pending.finish() }
                }
            }
        }
    }
}
