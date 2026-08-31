package com.hiennv.flutter_callkit_incoming

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.M)
class InAppCallManager(private val context: Context) {

    companion object {
        private const val ACCOUNT_ID = "flutter_callkit_incoming_in_app_call_account"
        private const val TAG = "InAppCallManager"

        const val META_SELF_MANAGED = "com.hiennv.flutter_callkit_incoming.SELF_MANAGED_TELECOM"

        /** True when the host app sets [META_SELF_MANAGED] to true in its manifest. */
        fun isSelfManagedTelecomEnabled(context: Context): Boolean = try {
            context.packageManager
                .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData
                ?.getBoolean(META_SELF_MANAGED, false) ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Could not read $META_SELF_MANAGED meta-data: ${e.message}")
            false
        }
    }

    fun registerPhoneAccount() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val componentName = ComponentName(context, CallkitConnectionService::class.java)
        val handle = PhoneAccountHandle(componentName, ACCOUNT_ID)

        val phoneAccount = PhoneAccount.builder(handle, "Callkit Incoming In-App Call")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .build()

        telecomManager.registerPhoneAccount(phoneAccount)
        Log.d(TAG, "PhoneAccount registered.")
    }

    fun unregisterPhoneAccount() {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val componentName = ComponentName(context, CallkitConnectionService::class.java)
        val handle = PhoneAccountHandle(componentName, ACCOUNT_ID)

        telecomManager.unregisterPhoneAccount(handle)
        Log.d(TAG, "PhoneAccount unregistered.")
    }

    fun getPhoneAccountHandle(): PhoneAccountHandle {
        return PhoneAccountHandle(
            ComponentName(context, CallkitConnectionService::class.java),
            ACCOUNT_ID
        )
    }
}
