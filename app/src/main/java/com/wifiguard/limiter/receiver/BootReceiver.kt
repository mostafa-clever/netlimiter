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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val prefs = PreferencesManager(context.applicationContext)
                    val role = prefs.roleFlow.first()
                    val selfLimit = prefs.selfLimitEnabledFlow.first()
                    if (role == AppRole.SUPERVISOR) {
                        SupervisorServerService.start(context.applicationContext)
                        if (selfLimit && VpnService.prepare(context.applicationContext) == null) {
                            WifiGuardVpnService.start(context.applicationContext)
                        }
                    } else if (role == AppRole.CLIENT) {
                        ClientGuardService.start(context.applicationContext)
                        if (VpnService.prepare(context.applicationContext) == null) {
                            WifiGuardVpnService.start(context.applicationContext)
                        }
                    }
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
