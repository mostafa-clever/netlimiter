package com.wifiguard.limiter

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.wifiguard.limiter.admin.WifiGuardDeviceAdminReceiver
import com.wifiguard.limiter.data.PreferencesManager
import com.wifiguard.limiter.model.AppRole
import com.wifiguard.limiter.network.SupervisorServerService
import com.wifiguard.limiter.ui.screens.ClientScreen
import com.wifiguard.limiter.ui.screens.OnboardingScreen
import com.wifiguard.limiter.ui.screens.SupervisorScreen
import com.wifiguard.limiter.ui.theme.WifiGuardTheme
import com.wifiguard.limiter.vpn.WifiGuardVpnService

class MainActivity : ComponentActivity() {

    private lateinit var prefs: PreferencesManager

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            WifiGuardVpnService.start(this)
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(applicationContext)
        requestNotificationPermissionIfNeeded()

        setContent {
            WifiGuardTheme {
                val role by prefs.roleFlow.collectAsState(initial = AppRole.UNSET)
                val selfLimit by prefs.selfLimitEnabledFlow.collectAsState(initial = false)

                LaunchedEffect(role, selfLimit) {
                    if (role == AppRole.SUPERVISOR) {
                        SupervisorServerService.start(this@MainActivity)
                        if (selfLimit) {
                            startVpnWithPermission()
                        }
                    } else if (role == AppRole.CLIENT) {
                        startVpnWithPermission()
                    }
                }

                when (role) {
                    AppRole.UNSET -> OnboardingScreen(
                        prefs = prefs,
                        onSetupComplete = { newRole ->
                            if (newRole == AppRole.SUPERVISOR) {
                                SupervisorServerService.start(this@MainActivity)
                            } else if (newRole == AppRole.CLIENT) {
                                startVpnWithPermission()
                            }
                        }
                    )

                    AppRole.SUPERVISOR -> SupervisorScreen(
                        prefs = prefs,
                        onRequestStartVpn = { startVpnWithPermission() },
                        onStopVpn = { WifiGuardVpnService.stop(this@MainActivity) },
                        onOpenAlwaysOnSettings = { openAlwaysOnVpnSettings() },
                        onRequestDeviceAdmin = { requestDeviceAdmin() },
                        onResetRole = {}
                    )

                    AppRole.CLIENT -> ClientScreen(
                        prefs = prefs,
                        onRequestStartVpn = { startVpnWithPermission() },
                        onOpenAlwaysOnSettings = { openAlwaysOnVpnSettings() },
                        onRequestDeviceAdmin = { requestDeviceAdmin() }
                    )
                }
            }
        }
    }

    private fun startVpnWithPermission() {
        runCatching {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnPermissionLauncher.launch(intent)
            } else {
                WifiGuardVpnService.start(this)
            }
        }
    }

    private fun openAlwaysOnVpnSettings() {
        val intent = Intent(Settings.ACTION_VPN_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { startActivity(intent) }
    }

    private fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, WifiGuardDeviceAdminReceiver.getComponentName(this@MainActivity))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "تفعيل هذه الحماية يمنع حذف التطبيق بسهولة ويرسل تنبيهاً للمشرف عند محاولة إزالته."
            )
        }
        runCatching { startActivity(intent) }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
