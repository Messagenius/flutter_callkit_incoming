package com.hiennv.flutter_callkit_incoming

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

class CallkitNotificationService : Service() {

    companion object {

        private const val TAG = "CallkitNotificationService"

        private val ActionForeground = listOf(
            CallkitConstants.ACTION_CALL_START,
            CallkitConstants.ACTION_CALL_ACCEPT,
            CallkitConstants.ACTION_CALL_CONNECTED
        )


        fun startServiceWithAction(context: Context, action: String, data: Bundle?) {
            val intent = Intent(context, CallkitNotificationService::class.java).apply {
                this.action = action
                putExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && intent.action in ActionForeground) {
                data?.let {
                    if (it.getBoolean(CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW, true)) {
                        ContextCompat.startForegroundService(context, intent)
                    } else {
                        context.startService(intent)
                    }
                }
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, CallkitNotificationService::class.java)
            context.stopService(intent)
        }

    }

    // Get notification manager dynamically to handle plugin lifecycle properly
    private fun getCallkitNotificationManager(): CallkitNotificationManager? {
        return FlutterCallkitIncomingPlugin.getInstance()?.getCallkitNotificationManager()
    }


    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val bundle = intent?.getBundleExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)
        val isForegroundAction = intent?.action in ActionForeground
        val needsPlaceholder = isForegroundAction &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                bundle?.getBoolean(CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW, true) == true
        if (needsPlaceholder) {
            if (!showPlaceholderForegroundNotification(bundle)) {
                return START_NOT_STICKY
            }
        }

        try {
            when (intent?.action) {
                CallkitConstants.ACTION_CALL_START -> {
                    intent.getBundleExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)
                        ?.let {
                            if (it.getBoolean(CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW, true)) {
                                getCallkitNotificationManager()?.createNotificationChanel(it)
                                showOngoingCallNotification(it)
                            } else {
                                stopSelf()
                            }
                        }
                }

                CallkitConstants.ACTION_CALL_ACCEPT -> {
                    intent.getBundleExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)
                        ?.let {
                            getCallkitNotificationManager()?.clearIncomingNotification(it, true)
                            if (it.getBoolean(CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW, true)) {
                                showOngoingCallNotification(it)
                            } else {
                                stopSelf()
                            }
                        }
                }

                CallkitConstants.ACTION_CALL_CONNECTED -> {
                    // Re-emit the ongoing notification via startForeground so the FGS
                    // (FOREGROUND_SERVICE_TYPE_PHONE_CALL) binding is preserved. Going through
                    // NotificationManager.notify() here would demote the notification to a
                    // regular user notification and re-enable user channel settings.
                    intent.getBundleExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)
                        ?.let {
                            if (it.getBoolean(CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW, true)) {
                                showOngoingCallNotification(it, isConnected = true)
                            }
                        }
                }

                null -> {
                    // OS restarted the service after kill (START_STICKY with null intent).
                    // Flutter engine may not be running yet — create a standalone manager
                    // using only Context so we can restore startForeground() without the plugin.
                    val activeCalls = getDataActiveCalls(this)
                    val bundle = activeCalls.firstOrNull()?.toBundle()
                    if (bundle != null) {
                        val pluginManager = getCallkitNotificationManager()
                        val manager = pluginManager
                            ?: CallkitNotificationManager(this, CallkitSoundPlayerManager(this))
                        manager.createNotificationChanel(bundle)
                        val notification = manager.getOnGoingCallNotification(bundle, false)
                        if (notification != null) {
                            val typeCall = bundle.getInt(CallkitConstants.EXTRA_CALLKIT_TYPE, -1)
                            startForeground(
                                notification.id,
                                notification.notification,
                                typeCall > 0
                            )
                            if (pluginManager == null) manager.destroy()
                        } else {
                            if (pluginManager == null) manager.destroy()
                            stopSelf()
                        }
                    } else {
                        stopSelf()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show the ongoing call notification", e)
            if (needsPlaceholder) {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun showOngoingCallNotification(bundle: Bundle, isConnected: Boolean = false) {

        val callkitNotification =
            getCallkitNotificationManager()?.getOnGoingCallNotification(bundle, isConnected)
        if (callkitNotification != null) {
            val typeCall = bundle.getInt(CallkitConstants.EXTRA_CALLKIT_TYPE, -1)
            startForeground(
                callkitNotification.id,
                callkitNotification.notification,
                typeCall > 0
            )
        }
    }

    private fun getOnGoingNotificationId(bundle: Bundle): Int {
        return bundle.getString(
            CallkitConstants.EXTRA_CALLKIT_CALLING_ID,
            bundle.getString(CallkitConstants.EXTRA_CALLKIT_ID, "callkit_incoming")
        ).hashCode()
    }

    private fun showPlaceholderForegroundNotification(bundle: Bundle?): Boolean {
        val notificationId = bundle?.let { getOnGoingNotificationId(it) }
            ?: "callkit_incoming".hashCode()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            if (notificationManager?.getNotificationChannel(CallkitNotificationManager.NOTIFICATION_CHANNEL_ID_ONGOING) == null) {
                notificationManager?.createNotificationChannel(
                    NotificationChannel(
                        CallkitNotificationManager.NOTIFICATION_CHANNEL_ID_ONGOING,
                        "Ongoing Call",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }

        val smallIcon =
            applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.sym_call_incoming

        val notification =
            androidx.core.app.NotificationCompat.Builder(
                this,
                CallkitNotificationManager.NOTIFICATION_CHANNEL_ID_ONGOING
            )
                .setContentTitle(applicationInfo.loadLabel(packageManager))
                .setContentText("Calling...")
                .setSmallIcon(smallIcon)
                .setOngoing(true)
                .build()

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                )
            } else {
                startForeground(notificationId, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground with the placeholder notification", e)
            stopSelf()
            false
        }
    }

    private fun startForeground(notificationId: Int, notification: Notification, isVideo: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(notificationId, notification)
            return
        }

        // On Android 14+ (targetSdk 34+, hard-enforced on 15/16) the `microphone` and
        // `camera` foreground-service types are "while-in-use" permissions: an FGS that
        // declares them CANNOT be started while the app is in the background. The system
        // throws SecurityException ("the app must be in the eligible state/exemptions to
        // access the foreground only permission") and kills the process. This service is
        // started from the background when a call is accepted from a notification / lock
        // screen (ACTION_CALL_ACCEPT) — exactly the disallowed case.
        //
        // `phoneCall` is exempt from that restriction for a self-managed calling app
        // (MANAGE_OWN_CALLS + CallkitConnectionService). So we attempt the full type set
        // (preferable when we ARE foreground-eligible) and degrade to phoneCall-only when
        // the OS rejects the while-in-use types. Mic/camera capture still works during the
        // active Telecom call under the phoneCall type.
        var mask = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // 30+
            // Only add the types whose runtime permission is granted: the user may
            // answer a call before the app ever requested RECORD_AUDIO / CAMERA.
            if (isPermissionGranted(Manifest.permission.RECORD_AUDIO)) {
                mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (isVideo && isPermissionGranted(Manifest.permission.CAMERA)) {
                mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
        }

        try {
            startForeground(notificationId, notification, mask)
        } catch (e: Exception) {
            // Covers SecurityException (while-in-use type not allowed from background) and
            // ForegroundServiceStartNotAllowedException. Retry with phoneCall only — the
            // type that is legal to start from the background for a self-managed call.
            Log.w(
                "CallkitNotificationService",
                "FGS start with mic/camera type rejected, falling back to phoneCall only",
                e
            )
            try {
                startForeground(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                )
            } catch (e2: Exception) {
                // Last resort: promote without a type so we still satisfy the
                // startForegroundService() contract instead of crashing the process.
                Log.w("CallkitNotificationService", "FGS start with phoneCall type also rejected", e2)
                startForeground(notificationId, notification)
            }
        }
    }

    private fun isPermissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED


    override fun onDestroy() {
        super.onDestroy()
        // Don't destroy the notification manager here as it's shared across the app
        // The plugin will handle cleanup when all engines are detached
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }


    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Don't kill the FGS. The app might be closed by user but the call is still ongoing
    }
}
