package com.hiennv.flutter_callkit_incoming

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.OutcomeReceiver
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.ConcurrentHashMap

/**
 * Self-managed Telecom [Connection] implementation for flutter_callkit_incoming.
 *
 * Hosts the call in the Android Telecom framework with `PROPERTY_SELF_MANAGED` so
 * the OS treats it as a first-party phone call — granting it keyguard-bypass /
 * full-screen-intent priority on strict OEMs (Samsung Knox, Xiaomi, etc.) that
 * otherwise ignore [android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED]
 * on ordinary Activities.
 *
 * Lifecycle:
 *  1. [CallkitConnectionService.onCreateIncomingConnection] returns a
 *     ringing Connection instance.
 *  2. User taps Accept in our notification → plugin's BroadcastReceiver maps the
 *     broadcast to the matching connection via [find] and calls [setActive].
 *  3. User taps Decline / End → [setDisconnected] + [destroy] → unregister.
 *
 * Connection lookup uses a process-wide [ConcurrentHashMap] keyed by the call id
 * (the plugin's existing `Data.id` field). The OS holds the Connection object in
 * the Telecom framework; we keep a parallel reference so our BroadcastReceiver
 * can drive state transitions.
 */
@RequiresApi(Build.VERSION_CODES.M)
class CallkitConnection(
    private val appContext: Context,
    val callId: String,
    val bundle: Bundle,
) : Connection() {

    companion object {
        private const val TAG = "CallkitConnection"
        private const val RESUME_POLL_GRACE_MS = 2000L
        private const val RESUME_POLL_INTERVAL_MS = 1000L
        private const val RING_GUARD_INTERVAL_MS = 2000L

        /** Bundle key — pass the full call Data bundle through Telecom extras. */
        const val EXTRA_CALL_BUNDLE = "com.hiennv.flutter_callkit_incoming.CALL_BUNDLE"

        private val activeConnections = ConcurrentHashMap<String, CallkitConnection>()

        fun find(callId: String): CallkitConnection? = activeConnections[callId]

        fun register(callId: String, conn: CallkitConnection) {
            activeConnections[callId] = conn
        }

        fun unregister(callId: String) {
            activeConnections.remove(callId)
        }

        /** For testing / cleanup — release all refs (Connection objects already destroyed by OS). */
        fun clearAll() {
            activeConnections.clear()
        }

        fun activeCount(): Int = activeConnections.size

        const val ROUTE_EARPIECE = "earpiece"
        const val ROUTE_SPEAKER = "speaker"
        const val ROUTE_BLUETOOTH = "bluetooth"
        const val ROUTE_WIRED = "wired"

        private val audioStateRoutes = mapOf(
            CallAudioState.ROUTE_EARPIECE to ROUTE_EARPIECE,
            CallAudioState.ROUTE_SPEAKER to ROUTE_SPEAKER,
            CallAudioState.ROUTE_BLUETOOTH to ROUTE_BLUETOOTH,
            CallAudioState.ROUTE_WIRED_HEADSET to ROUTE_WIRED,
        )

        fun videoStateOf(data: Data): Int =
            if (data.type == 1) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY
    }

    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        audioModeIsVoip = true
        // CAPABILITY_HOLD ("can be held right now") and CAPABILITY_SUPPORT_HOLD
        // ("hold feature exists") are distinct flags and Telecom requires BOTH:
        // when the user answers another call, CallsManager only holds the active
        // call if it has CAPABILITY_HOLD — otherwise onHold() is never invoked
        // and the call is disconnected or left running unheld.
        connectionCapabilities = CAPABILITY_MUTE or CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD
        register(callId, this)
        Log.d(TAG, "Connection created id=$callId active=${activeCount()}")
    }

    // -------------------------------------------------------------------------
    // Telecom → app lifecycle callbacks
    //
    // These fire when the user interacts with the OS-level call UI (system
    // dialer, car Bluetooth, watch, etc.). In our app-driven model we still
    // handle the primary Accept/Decline via our own notification buttons, but
    // the OS can also trigger these — we must honor both paths.
    // -------------------------------------------------------------------------

    override fun onAnswer() {
        super.onAnswer()
        Log.d(TAG, "onAnswer id=$callId")
        setActive()
        applyPendingRoute()
    }

    override fun onReject() {
        super.onReject()
        Log.d(TAG, "onReject id=$callId")
        finishWithCause(DisconnectCause.REJECTED)
    }

    override fun onDisconnect() {
        super.onDisconnect()
        Log.d(TAG, "onDisconnect id=$callId")
        finishWithCause(DisconnectCause.LOCAL)
    }

    override fun onAbort() {
        super.onAbort()
        Log.d(TAG, "onAbort id=$callId")
        finishWithCause(DisconnectCause.UNKNOWN)
    }

    // Telecom holds this self-managed connection when another call takes over
    // the audio (e.g. the user dials or answers a cellular call). Forward the
    // transition to Dart so the app can pause its media — mirrors what iOS
    // does via CXSetHeldCallAction.
    //
    // Telecom does NOT unhold self-managed calls when the other call ends —
    // the self-managed contract is that the app resumes its own call. So on
    // hold we start a watcher that polls for the managed call's presence and
    // self-resumes once it is gone (see resumeWhenManagedCallEnds).
    override fun onHold() {
        super.onHold()
        Log.d(TAG, "onHold id=$callId")
        setOnHold()
        FlutterCallkitIncomingPlugin.sendEvent(
            CallkitConstants.ACTION_CALL_TOGGLE_HOLD,
            mapOf("id" to callId, "isOnHold" to true),
        )
        startResumeWatcher()
    }

    /**
     * While a Telecom call is ringing, volume-key presses never reach
     * AudioService — PhoneWindowManager intercepts them and calls
     * [android.telecom.TelecomManager.silenceRinger], which lands here.
     * Self-managed connections do their own ringing, so stop our ringtone.
     */
    override fun onSilence() {
        super.onSilence()
        Log.d(TAG, "onSilence id=$callId")
        FlutterCallkitIncomingPlugin.getInstance()?.getCallkitSoundPlayerManager()?.fadeOutAndStop()
    }

    // Messagenius fork: the ring guard of the ringing foreground service, which Telecom calls skip.
    // The ring stops once the user can no longer see the call (incoming channel turned off).
    private val ringGuard = object : Runnable {
        override fun run() {
            if (state != STATE_RINGING) return
            val plugin = FlutterCallkitIncomingPlugin.getInstance()
            val player = plugin?.getCallkitSoundPlayerManager()
            if (player?.isPlaying == true && plugin.getCallkitNotificationManager()?.incomingChannelEnabled() == false) {
                Log.w(TAG, "Ring guard: no visible incoming-call notification, silencing id=$callId")
                player.stop()
            }
            watcherHandler.postDelayed(this, RING_GUARD_INTERVAL_MS)
        }
    }

    override fun onStateChanged(state: Int) {
        super.onStateChanged(state)
        watcherHandler.removeCallbacks(ringGuard)
        if (state == STATE_RINGING) watcherHandler.postDelayed(ringGuard, RING_GUARD_INTERVAL_MS)
    }

    override fun onUnhold() {
        super.onUnhold()
        Log.d(TAG, "onUnhold id=$callId")
        resume()
    }

    private fun resume() {
        stopResumeWatcher()
        setActive()
        FlutterCallkitIncomingPlugin.sendEvent(
            CallkitConstants.ACTION_CALL_TOGGLE_HOLD,
            mapOf("id" to callId, "isOnHold" to false),
        )
    }

    // -------------------------------------------------------------------------
    // Self-resume watcher
    // -------------------------------------------------------------------------

    private val watcherHandler = Handler(Looper.getMainLooper())
    private var resumeWatcher: Runnable? = null

    private fun startResumeWatcher() {
        stopResumeWatcher()
        val watcher = object : Runnable {
            override fun run() {
                if (state != STATE_HOLDING) {
                    resumeWatcher = null
                    return
                }
                if (isManagedCallActive()) {
                    watcherHandler.postDelayed(this, RESUME_POLL_INTERVAL_MS)
                    return
                }
                Log.d(TAG, "managed call gone — self-resuming id=$callId")
                resumeWatcher = null
                resume()
            }
        }
        resumeWatcher = watcher
        // Grace delay before the first check: at the instant Telecom holds us
        // the native call may still be setting up, so an immediate poll could
        // read "no managed call" and resume prematurely.
        watcherHandler.postDelayed(watcher, RESUME_POLL_GRACE_MS)
    }

    private fun stopResumeWatcher() {
        resumeWatcher?.let { watcherHandler.removeCallbacks(it) }
        resumeWatcher = null
    }

    /**
     * Is a managed (carrier/telephony) call ringing, dialing or active?
     *
     * Primary signal: [TelecomManager.isInManagedCall] — precise, counts only
     * managed calls (never our own self-managed one), but needs the
     * READ_PHONE_STATE runtime permission. Fallback when not granted: the
     * audio mode, which telephony sets to MODE_IN_CALL (active) or
     * MODE_RINGTONE (ringing) for the lifetime of a native call.
     */
    private fun isManagedCallActive(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val telecom =
                appContext.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (telecom != null) {
                try {
                    return telecom.isInManagedCall
                } catch (e: SecurityException) {
                    Log.d(TAG, "isInManagedCall needs READ_PHONE_STATE — audio-mode fallback")
                }
            }
        }
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return false
        return audio.mode == AudioManager.MODE_IN_CALL ||
            audio.mode == AudioManager.MODE_RINGTONE
    }

    // -------------------------------------------------------------------------
    // App → Telecom driving helpers (invoked by the plugin's BroadcastReceiver)
    // -------------------------------------------------------------------------

    /** Mark the call as answered — user accepted via app notification. */
    fun markAccepted() {
        Log.d(TAG, "markAccepted id=$callId")
        setActive()
        applyPendingRoute()
    }

    /**
     * Mark the call as declined/ended — user declined via app notification.
     *
     * Cold-launch DECLINE recovery is fired
     * from inside the self-managed [Connection] context (and *before*
     * [setDisconnected] runs) so the call is still in the RINGING state when
     * [Context.startActivity] is invoked. The hope: Android 14+ BAL grants a
     * PHONE_CALL exemption for active self-managed Telecom calls. Previous
     * placement inside the BroadcastReceiver hit BAL_BLOCK with
     * `callingUidProcState: BOUND_FOREGROUND_SERVICE; callingUidHasVisibleActivity: false`
     * (Galaxy logcat 2026-05-05 03:49:04.039).
     *
     * If this position still BAL_BLOCK's, the next escalation is to promote
     * `CallkitConnectionService` to a `foregroundServiceType="phoneCall"`
     * FGS for the lifetime of the connection.
     */
    fun markDeclined(context: Context) {
        Log.d(TAG, "markDeclined id=$callId")
        // Do not launch the app on decline. End the self-managed Telecom call
        // immediately so declining from the notification/lock screen leaves a
        // backgrounded or terminated app closed (3.0.0 behavior).
        finishWithCause(DisconnectCause.REJECTED)
    }

    /**
     * Persist declined nonce and start MainActivity. Idempotent — safe even
     * if the BroadcastReceiver fallback also fires (single-task launch flag,
     * one-shot consumePending).
     */
    private fun triggerDeclineRecovery(context: Context) {
        try {
            val prefs = context.getSharedPreferences(
                "flutter_callkit_incoming_decline",
                Context.MODE_PRIVATE,
            )
            prefs.edit()
                .putString("pending_nonce", callId)
                .putLong("pending_at", System.currentTimeMillis())
                .apply()
            Log.d(TAG, "[DIAG-DECLINE-CS] prefs written nonce=$callId")
            val launchIntent = AppUtils.getAppIntent(context, null, null)
            launchIntent?.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                Log.d(TAG, "[DIAG-DECLINE-CS] startActivity dispatched (CS ctx, RINGING)")
            } else {
                Log.w(TAG, "[DIAG-DECLINE-CS] launchIntent null — recovery skipped")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[DIAG-DECLINE-CS] recovery failed: ${e.message}")
        }
    }

    /** Mark the call as terminated — call ended (either side hung up). */
    fun markEnded() {
        Log.d(TAG, "markEnded id=$callId")
        finishWithCause(DisconnectCause.LOCAL)
    }

    /** Mark the call as missed — timeout without answer. */
    fun markMissed() {
        Log.d(TAG, "markMissed id=$callId")
        finishWithCause(DisconnectCause.MISSED)
    }

    private fun finishWithCause(cause: Int) {
        stopResumeWatcher()
        try {
            setDisconnected(DisconnectCause(cause))
        } catch (e: Exception) {
            Log.w(TAG, "setDisconnected failed: ${e.message}")
        }
        unregister(callId)
        try {
            destroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroy failed: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Messagenius fork: audio route. Telecom owns the route of this call, so the
    // app reads it from these callbacks and changes it through requestRoute().
    // -------------------------------------------------------------------------

    private val isVideo = bundle.getInt(CallkitConstants.EXTRA_CALLKIT_TYPE, 0) == 1
    private var routeBody: Map<String, Any?>? = null
    private var pendingRoute: Pair<String, String?>? = null
    private var verifyRoute: Pair<String, String?>? = null
    private var videoRouteChecked = false
    private var currentEndpoint: CallEndpoint? = null
    private var availableEndpoints: List<CallEndpoint> = emptyList()
    private var endpointMuted = false

    fun currentRoute(): Map<String, Any?>? = routeBody

    /** Applied now when the call is live and its route known, otherwise once it is. */
    fun requestRoute(kind: String, address: String?) {
        pendingRoute = kind to address
        applyPendingRoute()
    }

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(state: CallAudioState?) {
        super.onCallAudioStateChanged(state)
        // API 34+ reports the same change through the call endpoint callbacks.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE || state == null) return
        val available = audioStateRoutes.filterKeys { state.supportedRouteMask and it != 0 }.values.toList()
        var devices = emptyList<Map<String, String>>()
        var activeAddress: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            devices = state.supportedBluetoothDevices.map { mapOf("name" to deviceName(it), "address" to it.address) }
            activeAddress = state.activeBluetoothDevice?.address
        }
        emitRoute(audioStateRoutes[state.route] ?: ROUTE_EARPIECE, available, devices, activeAddress, state.isMuted)
    }

    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        super.onCallEndpointChanged(callEndpoint)
        currentEndpoint = callEndpoint
        emitEndpoints()
    }

    override fun onAvailableCallEndpointsChanged(availableEndpoints: List<CallEndpoint>) {
        super.onAvailableCallEndpointsChanged(availableEndpoints)
        this.availableEndpoints = availableEndpoints
        emitEndpoints()
    }

    override fun onMuteStateChanged(isMuted: Boolean) {
        super.onMuteStateChanged(isMuted)
        endpointMuted = isMuted
        emitEndpoints()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun emitEndpoints() {
        val current = currentEndpoint ?: return
        val devices = availableEndpoints.filter { it.endpointType == CallEndpoint.TYPE_BLUETOOTH }
            .map { mapOf("name" to it.endpointName.toString(), "address" to it.identifier.toString()) }
        val activeAddress =
            if (current.endpointType == CallEndpoint.TYPE_BLUETOOTH) current.identifier.toString() else null
        val available = availableEndpoints.mapNotNull { endpointKind(it) }.distinct()
        emitRoute(endpointKind(current) ?: ROUTE_EARPIECE, available, devices, activeAddress, endpointMuted)
    }

    private fun emitRoute(
        route: String,
        available: List<String>,
        devices: List<Map<String, String>>,
        activeAddress: String?,
        muted: Boolean,
    ) {
        routeBody = mapOf(
            "id" to callId,
            "route" to route,
            "available" to available,
            "btDevices" to devices,
            "address" to activeAddress,
            "muted" to muted,
        )
        Log.d(TAG, "audio route id=$callId route=$route available=$available")
        sendRoute()
        // Telecom can settle on its own route right after activation: insist once.
        verifyRoute?.let { (kind, address) ->
            verifyRoute = null
            if (kind != route) applyRoute(kind, address)
        }
        applyPendingRoute()
    }

    private fun sendRoute() {
        routeBody?.let { FlutterCallkitIncomingPlugin.sendEvent(CallkitConstants.ACTION_CALL_AUDIO_ROUTE, it) }
    }

    private fun applyPendingRoute() {
        val body = routeBody ?: return
        if (state == STATE_NEW || state == STATE_INITIALIZING || state == STATE_RINGING) return
        if (isVideo && !videoRouteChecked) {
            videoRouteChecked = true
            val available = body["available"] as? List<*> ?: emptyList<Any>()
            if (pendingRoute == null && ROUTE_BLUETOOTH !in available && ROUTE_WIRED !in available) {
                pendingRoute = ROUTE_SPEAKER to null
            }
        }
        val route = pendingRoute ?: return
        pendingRoute = null
        verifyRoute = route
        applyRoute(route.first, route.second)
    }

    @Suppress("DEPRECATION")
    private fun applyRoute(kind: String, address: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            requestEndpoint(kind, address)
            return
        }
        if (kind == ROUTE_BLUETOOTH && address != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val device = callAudioState?.supportedBluetoothDevices?.firstOrNull { it.address == address }
            if (device != null) {
                requestBluetoothAudio(device)
                return
            }
        }
        val route = audioStateRoutes.entries.firstOrNull { it.value == kind }?.key ?: return
        setAudioRoute(route)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun requestEndpoint(kind: String, address: String?) {
        val candidates = availableEndpoints.filter { endpointKind(it) == kind }
        val target = candidates.firstOrNull { address == null || it.identifier.toString() == address }
            ?: candidates.firstOrNull()
        if (target == null) {
            sendRoute()
            return
        }
        requestCallEndpointChange(
            target,
            appContext.mainExecutor,
            object : OutcomeReceiver<Void?, CallEndpointException> {
                override fun onResult(result: Void?) {}

                override fun onError(error: CallEndpointException) {
                    Log.w(TAG, "requestCallEndpointChange failed id=$callId: ${error.message}")
                    sendRoute()
                }
            },
        )
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun endpointKind(endpoint: CallEndpoint): String? = when (endpoint.endpointType) {
        CallEndpoint.TYPE_EARPIECE -> ROUTE_EARPIECE
        CallEndpoint.TYPE_SPEAKER -> ROUTE_SPEAKER
        CallEndpoint.TYPE_BLUETOOTH -> ROUTE_BLUETOOTH
        CallEndpoint.TYPE_WIRED_HEADSET -> ROUTE_WIRED
        else -> null
    }

    // Without BLUETOOTH_CONNECT the name is unreadable on Android 12+.
    private fun deviceName(device: BluetoothDevice): String =
        try {
            device.name ?: device.address
        } catch (e: SecurityException) {
            device.address
        }
}
