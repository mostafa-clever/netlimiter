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
import java.util.concurrent.atomic.AtomicBoolean

object DevicePowerState {
    val isShuttingDown = AtomicBoolean(false)
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_SHUTDOWN ||
            action == "android.intent.action.QUICKBOOT_POWEROFF" ||
            action == "com.htc.intent.action.QUICKBOOT_POWEROFF"
        ) {
            // Mark graceful shutdown immediately so ClientGuardService / WifiGuardVpnService never fire false fraud alerts
            DevicePowerState.isShuttingDown.set(true)
            return
        }
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            DevicePowerState.isShuttingDown.set(false)
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
                            // Start VPN FIRST on boot so protectionActive is true before ClientGuardService heartbeats
                            if (runCatching { VpnService.prepare(appContext) == null }.getOrDefault(false)) {
                                runCatching { WifiGuardVpnService.start(appContext) }
                            }
                            runCatching { ClientGuardService.start(appContext) }
                        }
                    }
                } finally {
                    runCatching { pending.finish() }
                }
            }
        }
    }
}
