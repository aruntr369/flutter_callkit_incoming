package com.hiennv.flutter_callkit_incoming

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.NonNull
import com.hiennv.flutter_callkit_incoming.Utils.Companion.reapCollection
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.*
import java.lang.ref.WeakReference

/** FlutterCallkitIncomingPlugin */
class FlutterCallkitIncomingPlugin :
    FlutterPlugin,
    MethodChannel.MethodCallHandler,
    ActivityAware,
    PluginRegistry.RequestPermissionsResultListener {

    companion object {

        const val EXTRA_CALLKIT_CALL_DATA = "EXTRA_CALLKIT_CALL_DATA"

        @SuppressLint("StaticFieldLeak")
        private lateinit var instance: FlutterCallkitIncomingPlugin

        fun getInstance(): FlutterCallkitIncomingPlugin? =
            if (::instance.isInitialized) instance else null

        fun hasInstance(): Boolean = ::instance.isInitialized

        private val methodChannels = mutableMapOf<BinaryMessenger, MethodChannel>()
        private val eventChannels = mutableMapOf<BinaryMessenger, EventChannel>()
        private val eventHandlers = mutableListOf<WeakReference<EventCallbackHandler>>()
        private val nativeCallbacks = mutableListOf<WeakReference<CallkitEventCallback>>()

        /* ---------------- EVENTS ---------------- */

        fun sendEvent(event: String, body: Map<String, Any?>) {
            eventHandlers.reapCollection().forEach {
                it.get()?.send(event, body)
            }
        }

        fun sendEventCustom(event: String, body: Map<String, Any>) {
            eventHandlers.reapCollection().forEach {
                it.get()?.send(event, body)
            }
        }

        fun registerEventCallback(callback: CallkitEventCallback) {
            nativeCallbacks.add(WeakReference(callback))
        }

        fun unregisterEventCallback(callback: CallkitEventCallback) {
            nativeCallbacks.removeAll { it.get() == callback || it.get() == null }
        }

        internal fun notifyEventCallbacks(
            event: CallkitEventCallback.CallEvent,
            data: android.os.Bundle
        ) {
            nativeCallbacks.reapCollection().forEach {
                it.get()?.onCallEvent(event, data)
            }
        }

        /* ---------------- INIT ---------------- */

        fun sharePluginWithRegister(binding: FlutterPlugin.FlutterPluginBinding) {
            initSharedInstance(binding.applicationContext, binding.binaryMessenger)
        }

        fun initSharedInstance(context: Context, messenger: BinaryMessenger) {
            if (!::instance.isInitialized) {
                instance = FlutterCallkitIncomingPlugin().apply {
                    this.context = context
                    callkitSoundPlayerManager = CallkitSoundPlayerManager(context)
                    callkitNotificationManager =
                        CallkitNotificationManager(context, callkitSoundPlayerManager)
                }
            } else if (instance.callkitNotificationManager == null) {
                instance.callkitSoundPlayerManager = CallkitSoundPlayerManager(context)
                instance.callkitNotificationManager =
                    CallkitNotificationManager(context, instance.callkitSoundPlayerManager)
            }

            val methodChannel = MethodChannel(messenger, "flutter_callkit_incoming")
            methodChannels[messenger] = methodChannel
            methodChannel.setMethodCallHandler(instance)

            val eventChannel = EventChannel(messenger, "flutter_callkit_incoming_events")
            eventChannels[messenger] = eventChannel

            val handler = EventCallbackHandler()
            eventHandlers.add(WeakReference(handler))
            eventChannel.setStreamHandler(handler)
        }
    }

    /* ---------------- INSTANCE ---------------- */

    private var activity: Activity? = null
    private var context: Context? = null
    private var callkitNotificationManager: CallkitNotificationManager? = null
    private var callkitSoundPlayerManager: CallkitSoundPlayerManager? = null

    fun getCallkitNotificationManager() = callkitNotificationManager
    fun getCallkitSoundPlayerManager() = callkitSoundPlayerManager

    /* ---------------- ENGINE ---------------- */

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        sharePluginWithRegister(binding)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            InAppCallManager(binding.applicationContext).registerPhoneAccount()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannels.remove(binding.binaryMessenger)?.setMethodCallHandler(null)
        eventChannels.remove(binding.binaryMessenger)?.setStreamHandler(null)

        if (methodChannels.isEmpty() && eventChannels.isEmpty()) {
            callkitSoundPlayerManager?.destroy()
            callkitNotificationManager?.destroy()
            callkitSoundPlayerManager = null
            callkitNotificationManager = null
        }
    }

    /* ---------------- ACTIVITY ---------------- */

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        context = binding.activity.applicationContext
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivity() {
        activity = null
        context = null
    }

    override fun onDetachedFromActivityForConfigChanges() {}
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    /* ---------------- METHODS ---------------- */

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: MethodChannel.Result) {
        try {
            when (call.method) {

                /* ---- INCOMING ---- */

                "showCallkitIncoming" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    data.from = "notification"
                    val bundle = data.toBundle()

                    // Show notification DIRECTLY from the plugin – bypass the
                    // broadcast system entirely for the fastest possible display
                    // on low-RAM devices.
                    callkitNotificationManager?.showIncomingNotification(bundle)

                    // Send event to Flutter
                    sendEvent(
                        CallkitConstants.ACTION_CALL_INCOMING,
                        buildFlutterEventData(bundle)
                    )

                    // Persist call data on a background thread
                    Thread {
                        try { addCall(context, data) }
                        catch (_: Exception) {}
                    }.start()

                    result.success(true)
                }

                "showCallkitIncomingDirect" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    data.from = "notification"
                    val bundle = data.toBundle().apply {
                        putBoolean("fromUi", true)
                    }

                    context?.sendBroadcast(
                        CallkitIncomingBroadcastReceiver.getIntentIncoming(
                            requireNotNull(context),
                            bundle
                        )
                    )
                    result.success(true)
                }

                "showCallkitIncomingSilently" -> result.success(true)

                "showMissCallNotification" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    callkitNotificationManager?.showMissCallNotification(data.toBundle())
                    result.success(true)
                }

                /* ---- CALL FLOW ---- */

                "startCall" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    context?.sendBroadcast(
                        CallkitIncomingBroadcastReceiver.getIntentStart(
                            requireNotNull(context),
                            data.toBundle()
                        )
                    )
                    result.success(true)
                }

                "endCall" -> {
                    val calls = getDataActiveCalls(context)
                    val data = Data(call.arguments() ?: HashMap())
                    val current = calls.firstOrNull { it.id == data.id }

                    current?.let {
                        val intent =
                            if (it.isAccepted)
                                CallkitIncomingBroadcastReceiver.getIntentEnded(
                                    requireNotNull(context), it.toBundle()
                                )
                            else
                                CallkitIncomingBroadcastReceiver.getIntentDecline(
                                    requireNotNull(context), it.toBundle()
                                )
                        context?.sendBroadcast(intent)
                    }
                    result.success(true)
                }

                "callConnected" -> {
                    val calls = getDataActiveCalls(context)
                    val data = Data(call.arguments() ?: HashMap())
                    calls.firstOrNull { it.id == data.id }?.let {
                        context?.sendBroadcast(
                            CallkitIncomingBroadcastReceiver.getIntentConnected(
                                requireNotNull(context),
                                it.toBundle()
                            )
                        )
                    }
                    result.success(true)
                }

                "endAllCalls" -> {
                    val calls = getDataActiveCalls(context)
                    calls.forEach {
                        val intent =
                            if (it.isAccepted)
                                CallkitIncomingBroadcastReceiver.getIntentEnded(
                                    requireNotNull(context), it.toBundle()
                                )
                            else
                                CallkitIncomingBroadcastReceiver.getIntentDecline(
                                    requireNotNull(context), it.toBundle()
                                )
                        context?.sendBroadcast(intent)
                    }
                    removeAllCalls(context)
                    result.success(true)
                }

                /* ---- CONTROLS ---- */

                "muteCall" -> {
                    sendEvent(
                        CallkitConstants.ACTION_CALL_TOGGLE_MUTE,
                        call.arguments as? Map<String, Any?> ?: emptyMap()
                    )
                    result.success(true)
                }

                "holdCall" -> {
                    sendEvent(
                        CallkitConstants.ACTION_CALL_TOGGLE_HOLD,
                        call.arguments as? Map<String, Any?> ?: emptyMap()
                    )
                    result.success(true)
                }

                "isMuted" -> result.success(true)

                /* ---- PERMISSIONS ---- */

                "requestNotificationPermission" -> {
                    callkitNotificationManager?.requestNotificationPermission(
                        activity,
                        call.arguments as? Map<String, Any> ?: emptyMap()
                    )
                    result.success(true)
                }

                "requestFullIntentPermission" -> {
                    callkitNotificationManager?.requestFullIntentPermission(activity)
                    result.success(true)
                }

                "canUseFullScreenIntent" -> {
                    result.success(
                        callkitNotificationManager?.canUseFullScreenIntent() ?: true
                    )
                }

                /* ---- CLEANUP ---- */

                "hideCallkitIncoming" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    callkitSoundPlayerManager?.stop()
                    callkitNotificationManager?.clearIncomingNotification(
                        data.toBundle(),
                        false
                    )
                    result.success(true)
                }

                "activeCalls" ->
                    result.success(getDataActiveCallsForFlutter(context))

                "getDevicePushTokenVoIP" ->
                    result.success("")

                "silenceEvents" -> {
                    CallkitIncomingBroadcastReceiver.silenceEvents =
                        call.arguments as? Boolean ?: false
                    result.success(true)
                }

                else -> result.notImplemented()
            }
        } catch (e: Exception) {
            result.error("error", e.message, null)
        }
    }

    /* ---------------- EVENT DATA BUILDER ---------------- */

    private fun buildFlutterEventData(data: android.os.Bundle): Map<String, Any?> {
        val android = mapOf(
            "isCustomNotification" to data.getBoolean(CallkitConstants.EXTRA_CALLKIT_IS_CUSTOM_NOTIFICATION, false),
            "isCustomSmallExNotification" to data.getBoolean(CallkitConstants.EXTRA_CALLKIT_IS_CUSTOM_SMALL_EX_NOTIFICATION, false),
            "ringtonePath" to data.getString(CallkitConstants.EXTRA_CALLKIT_RINGTONE_PATH, ""),
            "backgroundColor" to data.getString(CallkitConstants.EXTRA_CALLKIT_BACKGROUND_COLOR, ""),
            "backgroundUrl" to data.getString(CallkitConstants.EXTRA_CALLKIT_BACKGROUND_URL, ""),
            "actionColor" to data.getString(CallkitConstants.EXTRA_CALLKIT_ACTION_COLOR, ""),
            "textColor" to data.getString(CallkitConstants.EXTRA_CALLKIT_TEXT_COLOR, ""),
            "incomingCallNotificationChannelName" to data.getString(CallkitConstants.EXTRA_CALLKIT_INCOMING_CALL_NOTIFICATION_CHANNEL_NAME, ""),
            "missedCallNotificationChannelName" to data.getString(CallkitConstants.EXTRA_CALLKIT_MISSED_CALL_NOTIFICATION_CHANNEL_NAME, ""),
            "isImportant" to data.getBoolean(CallkitConstants.EXTRA_CALLKIT_IS_IMPORTANT, true),
            "isBot" to data.getBoolean(CallkitConstants.EXTRA_CALLKIT_IS_BOT, false)
        )

        val missedCallNotification = mapOf(
            "id" to data.getInt(CallkitConstants.EXTRA_CALLKIT_MISSED_CALL_ID),
            "showNotification" to data.getBoolean(CallkitConstants.EXTRA_CALLKIT_MISSED_CALL_SHOW),
            "count" to data.getInt(CallkitConstants.EXTRA_CALLKIT_MISSED_CALL_COUNT),
            "subtitle" to data.getString(CallkitConstants.EXTRA_CALLKIT_MISSED_CALL_SUBTITLE),
            "callbackText" to data.getString(CallkitConstants.EXTRA_CALLKIT_MISSED_CALL_CALLBACK_TEXT),
            "isShowCallback" to data.getBoolean(CallkitConstants.EXTRA_CALLKIT_MISSED_CALL_CALLBACK_SHOW)
        )

        val callingNotification = mapOf(
            "id" to data.getString(CallkitConstants.EXTRA_CALLKIT_CALLING_ID),
            "showNotification" to data.getBoolean(CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW),
            "subtitle" to data.getString(CallkitConstants.EXTRA_CALLKIT_CALLING_SUBTITLE),
            "callbackText" to data.getString(CallkitConstants.EXTRA_CALLKIT_CALLING_HANG_UP_TEXT),
            "isShowCallback" to data.getBoolean(CallkitConstants.EXTRA_CALLKIT_CALLING_HANG_UP_SHOW)
        )

        return mapOf(
            "id" to data.getString(CallkitConstants.EXTRA_CALLKIT_ID, ""),
            "nameCaller" to data.getString(CallkitConstants.EXTRA_CALLKIT_NAME_CALLER, ""),
            "avatar" to data.getString(CallkitConstants.EXTRA_CALLKIT_AVATAR, ""),
            "number" to data.getString(CallkitConstants.EXTRA_CALLKIT_HANDLE, ""),
            "type" to data.getInt(CallkitConstants.EXTRA_CALLKIT_TYPE, 0),
            "duration" to data.getLong(CallkitConstants.EXTRA_CALLKIT_DURATION, 0L),
            "textAccept" to data.getString(CallkitConstants.EXTRA_CALLKIT_TEXT_ACCEPT, ""),
            "textDecline" to data.getString(CallkitConstants.EXTRA_CALLKIT_TEXT_DECLINE, ""),
            "extra" to data.getSerializable(CallkitConstants.EXTRA_CALLKIT_EXTRA),
            "missedCallNotification" to missedCallNotification,
            "callingNotification" to callingNotification,
            "android" to android
        )
    }

    /* ---------------- EVENTS ---------------- */

    class EventCallbackHandler : EventChannel.StreamHandler {
        private var sink: EventChannel.EventSink? = null

        override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
            sink = events
        }

        fun send(event: String, body: Map<String, Any?>) {
            Handler(Looper.getMainLooper()).post {
                sink?.success(mapOf("event" to event, "body" to body))
            }
        }

        override fun onCancel(arguments: Any?) {
            sink = null
        }
    }

    /* ---------------- PERMISSIONS ---------------- */

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        callkitNotificationManager?.onRequestPermissionsResult(
            activity,
            requestCode,
            grantResults
        )
        return true
    }
}
