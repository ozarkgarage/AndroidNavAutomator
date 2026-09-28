package com.nav.automator

import android.Manifest
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

class MainActivity : AppCompatActivity() {

    private lateinit var tvSelectedAppName: TextView
    private lateinit var tvSelectedAppPackage: TextView
    private lateinit var btnSelectApp: Button
    private lateinit var rgOrientation: RadioGroup
    private lateinit var rbLandscape: RadioButton
    private lateinit var rbPortrait: RadioButton
    private lateinit var tvServiceStatus: TextView
    private lateinit var btnToggleService: Button
    private lateinit var tvOverlayStatus: TextView
    private lateinit var btnGrantOverlay: Button
    private lateinit var tvAdminStatus: TextView
    private lateinit var btnEnableAdmin: Button
    private lateinit var cardNotification: View
    private lateinit var tvNotificationStatus: TextView
    private lateinit var btnGrantNotification: Button
    private lateinit var tvBatteryStatus: TextView
    private lateinit var btnBatteryOpt: Button

    private data class AppAdapterItem(
        val name: String,
        val packageName: String,
        val icon: Drawable,
    )

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            updateStatuses()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvSelectedAppName = findViewById(R.id.tvSelectedAppName)
        tvSelectedAppPackage = findViewById(R.id.tvSelectedAppPackage)
        btnSelectApp = findViewById(R.id.btnSelectApp)
        rgOrientation = findViewById(R.id.rgOrientation)
        rbLandscape = findViewById(R.id.rbLandscape)
        rbPortrait = findViewById(R.id.rbPortrait)
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        btnToggleService = findViewById(R.id.btnToggleService)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay)
        tvAdminStatus = findViewById(R.id.tvAdminStatus)
        btnEnableAdmin = findViewById(R.id.btnEnableAdmin)
        cardNotification = findViewById(R.id.cardNotification)
        tvNotificationStatus = findViewById(R.id.tvNotificationStatus)
        btnGrantNotification = findViewById(R.id.btnGrantNotification)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        btnBatteryOpt = findViewById(R.id.btnBatteryOpt)

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateStatuses()
    }

    private fun setupListeners() {
        btnSelectApp.setOnClickListener {
            showAppSelectionDialog()
        }

        rgOrientation.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.rbPortrait) {
                OrientationMode.PORTRAIT
            } else {
                OrientationMode.LANDSCAPE
            }
            AppPreferences.setOrientationMode(this, mode)
        }

        btnToggleService.setOnClickListener {
            if (isServiceRunning(NavService::class.java)) {
                stopService(Intent(this, NavService::class.java))
            } else {
                startNavService()
            }
            updateStatuses()
        }

        btnGrantOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:$packageName".toUri(),
                )
                startActivity(intent)
            }
        }

        btnEnableAdmin.setOnClickListener {
            val adminComponent = ComponentName(this, AdminReceiver::class.java)
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Nav Automator needs Device Admin rights to turn off and lock the screen when ignition power is disconnected.",
                )
            }
            startActivity(intent)
        }

        btnGrantNotification.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        btnBatteryOpt.setOnClickListener {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            try {
                startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }

    private fun showAppSelectionDialog() {
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfoList = packageManager.queryIntentActivities(mainIntent, 0)
        val apps = resolveInfoList.map { ri ->
            AppAdapterItem(
                name = ri.loadLabel(packageManager).toString(),
                packageName = ri.activityInfo.packageName,
                icon = ri.loadIcon(packageManager),
            )
        }.sortedBy { it.name.lowercase() }

        val adapter = object : ArrayAdapter<AppAdapterItem>(this, R.layout.item_app_info, apps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_app_info, parent, false)
                val item = getItem(position) ?: return view
                val ivIcon = view.findViewById<ImageView>(R.id.ivAppIcon)
                val tvName = view.findViewById<TextView>(R.id.tvAppName)
                val tvPackage = view.findViewById<TextView>(R.id.tvAppPackage)

                ivIcon.setImageDrawable(item.icon)
                tvName.text = item.name
                tvPackage.text = item.packageName
                return view
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_title_select_app)
            .setAdapter(adapter) { _, which ->
                val selectedApp = apps[which]
                AppPreferences.setTargetApp(this, selectedApp.packageName, selectedApp.name)
                updateStatuses()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startNavService() {
        val serviceIntent = Intent(this, NavService::class.java)
        startForegroundService(serviceIntent)
    }

    private fun updateStatuses() {
        // 0. Selected Target App Status
        val targetPackage = AppPreferences.getTargetPackage(this)
        val targetName = AppPreferences.getTargetAppName(this)
        if (targetPackage.isNotEmpty()) {
            tvSelectedAppName.text = getString(R.string.target_app_label, targetName)
            tvSelectedAppPackage.text = getString(R.string.target_app_package, targetPackage)
        } else {
            tvSelectedAppName.setText(R.string.no_app_selected)
            tvSelectedAppPackage.text = ""
        }

        // 0.1 Orientation Preference Status
        val mode = AppPreferences.getOrientationMode(this)
        if (mode == OrientationMode.PORTRAIT) {
            rbPortrait.isChecked = true
        } else {
            rbLandscape.isChecked = true
        }

        // 1. Service Status
        val running = isServiceRunning(NavService::class.java)
        if (running) {
            tvServiceStatus.setText(R.string.service_status_running)
            btnToggleService.setText(R.string.btn_stop_service)
        } else {
            tvServiceStatus.setText(R.string.service_status_stopped)
            btnToggleService.setText(R.string.btn_start_service)
        }

        // 2. Overlay Status
        val hasOverlay = Settings.canDrawOverlays(this)
        if (hasOverlay) {
            tvOverlayStatus.setText(R.string.overlay_status_granted)
            btnGrantOverlay.isEnabled = false
        } else {
            tvOverlayStatus.setText(R.string.overlay_status_not_granted)
            btnGrantOverlay.isEnabled = true
        }

        // 3. Device Admin Status
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val adminComponent = ComponentName(this, AdminReceiver::class.java)
        val isAdminActive = dpm?.isAdminActive(adminComponent) == true
        if (isAdminActive) {
            tvAdminStatus.setText(R.string.admin_status_active)
            btnEnableAdmin.isEnabled = false
        } else {
            tvAdminStatus.setText(R.string.admin_status_not_active)
            btnEnableAdmin.isEnabled = true
        }

        // 4. Notification Status (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cardNotification.visibility = View.VISIBLE
            val hasNotificationPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

            if (hasNotificationPermission) {
                tvNotificationStatus.setText(R.string.notification_status_granted)
                btnGrantNotification.isEnabled = false
            } else {
                tvNotificationStatus.setText(R.string.notification_status_not_granted)
                btnGrantNotification.isEnabled = true
            }
        } else {
            cardNotification.visibility = View.GONE
        }

        // 5. Battery Optimization Status
        val powerManager = getSystemService(PowerManager::class.java)
        val isIgnoringBattery = powerManager?.isIgnoringBatteryOptimizations(packageName) == true
        if (isIgnoringBattery) {
            tvBatteryStatus.setText(R.string.battery_status_excluded)
            btnBatteryOpt.isEnabled = false
        } else {
            tvBatteryStatus.setText(R.string.battery_status_not_excluded)
            btnBatteryOpt.isEnabled = true
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(ActivityManager::class.java) ?: return false
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
}
