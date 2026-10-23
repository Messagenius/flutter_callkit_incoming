package com.hiennv.flutter_callkit_incoming

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

@RequiresApi(Build.VERSION_CODES.M)
class InAppCallManager(private val context: Context) {

    companion object {
        private const val ACCOUNT_ID = "flutter_callkit_incoming_in_app_call_account"
        private const val TAG = "InAppCallManager"

        const val META_SELF_MANAGED = "com.hiennv.flutter_callkit_incoming.SELF_MANAGED_TELECOM"
        private const val PREF_SELF_MANAGED = "self_managed_telecom"

        /** True when the host app sets [META_SELF_MANAGED] to true in its manifest. */
        fun isSelfManagedTelecomCapable(context: Context): Boolean = try {
            context.packageManager
                .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData
                ?.getBoolean(META_SELF_MANAGED, false) ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Could not read $META_SELF_MANAGED meta-data: ${e.message}")
            false
        }

        /** Messagenius fork: the manifest allows Telecom and the app turned it on at runtime. */
        fun isSelfManagedTelecomEnabled(context: Context): Boolean =
            isSelfManagedTelecomCapable(context) && getBoolean(context, PREF_SELF_MANAGED, false)

        /** Messagenius fork: whether a call starting now goes through Telecom. */
        fun shouldUseTelecom(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                isSelfManagedTelecomEnabled(context) &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.MANAGE_OWN_CALLS) ==
                PackageManager.PERMISSION_GRANTED

        fun applySelfManagedTelecom(context: Context, on: Boolean) {
            putBoolean(context, PREF_SELF_MANAGED, on)
            val manager = InAppCallManager(context.applicationContext)
            if (isSelfManagedTelecomEnabled(context)) {
                manager.registerPhoneAccount()
            } else if (CallkitConnection.activeCount() == 0) {
                // Unregistering would drop a Telecom call in progress; the next start cleans up.
                manager.unregisterPhoneAccount()
            }
        }
    }

    fun registerPhoneAccount() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        // Runs on the plugin-attach path, so anything thrown here crashes the host app
        // at startup. Telecom raises SecurityException without MANAGE_OWN_CALLS and
        // IllegalArgumentException on some OEM builds; neither is worth a crash.
        try {
            val telecomManager =
                context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return
            val componentName = ComponentName(context, CallkitConnectionService::class.java)
            val handle = PhoneAccountHandle(componentName, ACCOUNT_ID)

            val phoneAccount = PhoneAccount.builder(handle, "Callkit Incoming In-App Call")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .build()

            telecomManager.registerPhoneAccount(phoneAccount)
            Log.d(TAG, "PhoneAccount registered.")
        } catch (e: Exception) {
            Log.e(TAG, "registerPhoneAccount failed — self-managed Telecom unavailable", e)
        }
    }

    fun unregisterPhoneAccount() {
        try {
            val telecomManager =
                context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return
            val componentName = ComponentName(context, CallkitConnectionService::class.java)
            val handle = PhoneAccountHandle(componentName, ACCOUNT_ID)

            telecomManager.unregisterPhoneAccount(handle)
            Log.d(TAG, "PhoneAccount unregistered.")
        } catch (e: Exception) {
            Log.e(TAG, "unregisterPhoneAccount failed", e)
        }
    }

    fun getPhoneAccountHandle(): PhoneAccountHandle {
        return PhoneAccountHandle(
            ComponentName(context, CallkitConnectionService::class.java),
            ACCOUNT_ID
        )
    }
}
