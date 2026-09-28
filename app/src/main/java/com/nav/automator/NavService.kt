package com.nav.automator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class NavService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    companion object {
        private const val CHANNEL_ID = "NavServiceChannel"
        private const val NOTIF_ID = 1001
        private const val OSMAND_PACKAGE = "net.osmand.plus"
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> handlePowerConnected()
                Intent.ACTION_POWER_DISCONNECTED -> handlePowerDisconnected()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            startForeground(NOTIF_ID, createNotification(), foregroundServiceType)
        } else {
            startForeground(NOTIF_ID, createNotification())
        }

        windowManager = getSystemService(WindowManager::class.java)

        // Register power receiver dynamically since ACTION_POWER_CONNECTED/DISCONNECTED
        // cannot be received via Manifest on API 26+
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(powerReceiver, filter)

        // Check if power is currently connected on service start
        checkInitialPowerState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Intent.ACTION_POWER_CONNECTED -> handlePowerConnected()
            Intent.ACTION_POWER_DISCONNECTED -> handlePowerDisconnected()
        }
        return START_STICKY
    }

    private fun checkInitialPowerState() {
        val batteryStatus: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING) ||
                (status == BatteryManager.BATTERY_STATUS_FULL)

        if (isCharging) {
            handlePowerConnected()
        }
    }

    private fun handlePowerConnected() {
        // 1. Hardware Screen Wake Lock (Turn Screen ON)
        val powerManager = getSystemService(PowerManager::class.java)
        if (wakeLock?.isHeld != true) {
            @Suppress("DEPRECATION")
            wakeLock = powerManager?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "NavAutomator::WakeLock",
            )?.apply {
                acquire(10 * 60 * 1000L /* 10 mins fallback timeout */)
            }
        }

        // 2. Force Orientation via System Overlay
        applyOrientationOverlay()

        // 3. Launch Target App
        launchTargetApp()
    }

    private fun handlePowerDisconnected() {
        // 1. Release Hardware Screen Wake Lock
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            wakeLock = null
        }

        // 2. Remove Orientation Overlay
        removeOrientationOverlay()

        // 3. Turn screen off via Device Admin policy (or fallback to Home screen)
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, AdminReceiver::class.java)
        if (dpm.isAdminActive(adminComponent)) {
            dpm.lockNow()
        } else {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
        }

        // Do NOT call stopSelf() - service must remain running in background to listen for next power connect event
    }

    private fun applyOrientationOverlay() {
        if (overlayView != null) return

        if (!Settings.canDrawOverlays(this)) {
            return
        }

        val mode = AppPreferences.getOrientationMode(this)
        val requestedOrientation = if (mode == OrientationMode.PORTRAIT) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        overlayView = View(this)
        val params = WindowManager.LayoutParams(
            0, 0, // Zero size so it is transparent and non-blocking
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            screenOrientation = requestedOrientation
        }

        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeOrientationOverlay() {
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            overlayView = null
        }
    }

    private fun launchTargetApp() {
        val targetPackage = AppPreferences.getTargetPackage(this)
        val launchIntent = if (targetPackage.isNotEmpty()) {
            packageManager.getLaunchIntentForPackage(targetPackage)
        } else {
            null
        } ?: packageManager.getLaunchIntentForPackage(OSMAND_PACKAGE)
        ?: packageManager.getLaunchIntentForPackage("net.osmand")
        ?: packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")

        launchIntent?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            try {
                startActivity(this)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Nav Automator Controller",
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nav Automator Active")
            .setContentText("Monitoring ignition power state...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            unregisterReceiver(powerReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        removeOrientationOverlay()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            wakeLock = null
        }
        super.onDestroy()
    }
}
