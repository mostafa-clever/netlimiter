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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.wifiguard.limiter.admin.WifiGuardDeviceAdminReceiver
import com.wifiguard.limiter.data.PreferencesManager
import com.wifiguard.limiter.model.AppRole
import com.wifiguard.limiter.network.ClientGuardService
import com.wifiguard.limiter.network.SupervisorServerService
import com.wifiguard.limiter.ui.screens.ClientScreen
import com.wifiguard.limiter.ui.screens.OnboardingScreen
import com.wifiguard.limiter.ui.screens.SupervisorScreen
import com.wifiguard.limiter.ui.theme.WifiGuardTheme
import com.wifiguard.limiter.update.GitHubUpdater
import com.wifiguard.limiter.update.ReleaseUpdateInfo
import com.wifiguard.limiter.vpn.WifiGuardVpnService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var prefs: PreferencesManager

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            WifiGuardVpnService.start(this)
        }
    }

    private val multiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(applicationContext)
        requestPermissionsIfNeeded()

        setContent {
            WifiGuardTheme {
                val role by prefs.roleFlow.collectAsState(initial = AppRole.UNSET)
                val selfLimit by prefs.selfLimitEnabledFlow.collectAsState(initial = false)
                val githubRepo by prefs.githubRepoFlow.collectAsState(initial = GitHubUpdater.DEFAULT_REPO)
                val updateScope = rememberCoroutineScope()
                var availableUpdate by remember { mutableStateOf<ReleaseUpdateInfo?>(null) }
                var isDownloadingUpdate by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    val res = GitHubUpdater.checkLatestRelease(this@MainActivity, githubRepo)
                    res.onSuccess { info ->
                        if (info.hasUpdate) availableUpdate = info
                    }
                }

                LaunchedEffect(role, selfLimit) {
                    if (role == AppRole.SUPERVISOR) {
                        ClientGuardService.stop(this@MainActivity)
                        SupervisorServerService.start(this@MainActivity)
                        if (selfLimit) {
                            startVpnWithPermission()
                        }
                    } else if (role == AppRole.CLIENT) {
                        SupervisorServerService.stop(this@MainActivity)
                        ClientGuardService.start(this@MainActivity)
                        startVpnWithPermission()
                    }
                }

                when (role) {
                    AppRole.UNSET -> OnboardingScreen(
                        prefs = prefs,
                        onSetupComplete = { newRole ->
                            if (newRole == AppRole.SUPERVISOR) {
                                ClientGuardService.stop(this@MainActivity)
                                SupervisorServerService.start(this@MainActivity)
                            } else if (newRole == AppRole.CLIENT) {
                                SupervisorServerService.stop(this@MainActivity)
                                ClientGuardService.start(this@MainActivity)
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

                availableUpdate?.let { upd ->
                    AlertDialog(
                        onDismissRequest = { if (!isDownloadingUpdate) availableUpdate = null },
                        title = { Text("يتوفر تحديث جديد (${upd.versionTag})") },
                        text = {
                            val msg = if (isDownloadingUpdate) {
                                "جاري تحميل ملف التحديث..."
                            } else {
                                "الإصدار الحالي: 1.0.${upd.localBuildNumber}\nالإصدار الجديد: ${upd.versionTag}\nهل تريد التحديث الآن؟"
                            }
                            Text(msg)
                        },
                        confirmButton = {
                            TextButton(
                                enabled = !isDownloadingUpdate,
                                onClick = {
                                    isDownloadingUpdate = true
                                    updateScope.launch {
                                        GitHubUpdater.downloadAndInstallApk(this@MainActivity, upd.downloadUrl)
                                        isDownloadingUpdate = false
                                        availableUpdate = null
                                    }
                                }
                            ) { Text("تحديث الآن") }
                        },
                        dismissButton = {
                            TextButton(
                                enabled = !isDownloadingUpdate,
                                onClick = { availableUpdate = null }
                            ) { Text("لاحقاً") }
                        }
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

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.isNotEmpty()) {
            multiPermissionLauncher.launch(needed.toTypedArray())
        }
    }
}
