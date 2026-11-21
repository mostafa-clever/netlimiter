package com.wifiguard.limiter

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wifiguard.limiter.admin.WifiGuardDeviceAdminReceiver
import com.wifiguard.limiter.data.PreferencesManager
import com.wifiguard.limiter.model.AppRole
import com.wifiguard.limiter.network.ClientGuardService
import com.wifiguard.limiter.network.SupervisorServerService
import com.wifiguard.limiter.ui.screens.ClientScreen
import com.wifiguard.limiter.ui.screens.OnboardingScreen
import com.wifiguard.limiter.ui.screens.SupervisorScreen
import com.wifiguard.limiter.ui.theme.PrimaryTeal
import com.wifiguard.limiter.ui.theme.WifiGuardTheme
import com.wifiguard.limiter.update.GitHubUpdater
import com.wifiguard.limiter.update.ReleaseUpdateInfo
import com.wifiguard.limiter.vpn.WifiGuardVpnService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var prefs: PreferencesManager
    private var isRequestingVpnPermission = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isRequestingVpnPermission = false
        if (result.resultCode == RESULT_OK) {
            WifiGuardVpnService.start(this)
        } else {
            Toast.makeText(this, "لم يتم منح إذن الحماية (VPN) — لن يتم تطبيق الحدود حتى توافق.", Toast.LENGTH_LONG).show()
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
                // Collect with null initial so OnboardingScreen never flashes on cold start / rotation
                val role: AppRole? by prefs.roleFlow.collectAsState(initial = null)
                val selfLimit by prefs.selfLimitEnabledFlow.collectAsState(initial = false)
                val githubRepo: String? by prefs.githubRepoFlow.collectAsState(initial = null)
                var availableUpdate by remember { mutableStateOf<ReleaseUpdateInfo?>(null) }
                var isDownloadingUpdate by remember { mutableStateOf(false) }
                var updateError by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(githubRepo) {
                    val repo = githubRepo ?: return@LaunchedEffect
                    if (BuildConfig.ENABLE_GITHUB_UPDATER) {
                        val res = GitHubUpdater.checkLatestRelease(this@MainActivity, repo)
                        res.onSuccess { info ->
                            if (info.hasUpdate) availableUpdate = info
                        }
                    }
                }

                LaunchedEffect(role, selfLimit) {
                    if (role == null || role == AppRole.UNSET) return@LaunchedEffect
                    // Wait cleanly until Activity reaches STARTED (fixes cold-launch service skip during onCreate)
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        when (role) {
                            AppRole.SUPERVISOR -> {
                                ClientGuardService.stop(this@MainActivity)
                                SupervisorServerService.start(this@MainActivity)
                                if (selfLimit) {
                                    startVpnWithPermission()
                                }
                            }
                            AppRole.CLIENT -> {
                                SupervisorServerService.stop(this@MainActivity)
                                ClientGuardService.start(this@MainActivity)
                                startVpnWithPermission()
                            }
                            else -> {}
                        }
                    }
                }

                when (role) {
                    null -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = PrimaryTeal)
                        }
                    }

                    AppRole.UNSET -> OnboardingScreen(
                        prefs = prefs,
                        onSetupComplete = {
                            // Service startup is handled cleanly by LaunchedEffect(role, selfLimit)
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
                        onDismissRequest = {
                            if (!isDownloadingUpdate) {
                                availableUpdate = null
                                updateError = null
                            }
                        },
                        title = { Text("يتوفر تحديث جديد (${upd.versionTag})") },
                        text = {
                            if (isDownloadingUpdate) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = PrimaryTeal, strokeWidth = 2.5.dp)
                                    Text("جاري تحميل ملف التحديث...")
                                }
                            } else {
                                val msg = if (updateError != null) {
                                    "تعذر التحديث: $updateError"
                                } else {
                                    "الإصدار الحالي: 1.0.${upd.localBuildNumber}\nالإصدار الجديد: ${upd.versionTag}\nهل تريد التحديث الآن؟"
                                }
                                Text(msg)
                            }
                        },
                        confirmButton = {
                            TextButton(
                                enabled = !isDownloadingUpdate,
                                onClick = {
                                    isDownloadingUpdate = true
                                    updateError = null
                                    lifecycleScope.launch {
                                        val res = GitHubUpdater.downloadAndInstallApk(this@MainActivity, upd.downloadUrl)
                                        isDownloadingUpdate = false
                                        res.onSuccess {
                                            availableUpdate = null
                                            updateError = null
                                        }.onFailure { err ->
                                            updateError = err.message ?: "خطأ غير متوقع أثناء التحميل"
                                        }
                                    }
                                }
                            ) { Text(if (updateError != null) "إعادة المحاولة" else "تحديث الآن") }
                        },
                        dismissButton = {
                            TextButton(
                                enabled = !isDownloadingUpdate,
                                onClick = {
                                    availableUpdate = null
                                    updateError = null
                                }
                            ) { Text("لاحقاً") }
                        }
                    )
                }
            }
        }
    }

    private fun startVpnWithPermission() {
        runCatching {
            if (WifiGuardVpnService.isRunning.value) return
            val intent = VpnService.prepare(this)
            if (intent != null) {
                if (!isRequestingVpnPermission) {
                    isRequestingVpnPermission = true
                    runCatching {
                        vpnPermissionLauncher.launch(intent)
                    }.onFailure {
                        isRequestingVpnPermission = false
                        Toast.makeText(this, "تعذر فتح نافذة إذن الحماية (VPN) على هذا الجهاز.", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                WifiGuardVpnService.start(this)
            }
        }.onFailure {
            isRequestingVpnPermission = false
            Toast.makeText(this, "تعذر تشغيل خدمة الحماية حالياً.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAlwaysOnVpnSettings() {
        val intent = Intent(Settings.ACTION_VPN_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, WifiGuardDeviceAdminReceiver.getComponentName(this@MainActivity))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "تفعيل هذه الحماية يمنع حذف التطبيق بسهولة ويرسل تنبيهاً للمشرف عند محاولة إزالته."
            )
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "تعذر فتح إعدادات مسؤول الجهاز.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (needed.isNotEmpty()) {
            runCatching { multiPermissionLauncher.launch(needed.toTypedArray()) }
        }
    }
}
