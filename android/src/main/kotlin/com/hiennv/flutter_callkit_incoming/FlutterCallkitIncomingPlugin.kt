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

                "showCallkitIncoming",
                "showCallkitIncomingDirect" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    data.from = "notification"

                    val bundle = data.toBundle().apply {
                        if (call.method == "showCallkitIncomingDirect") {
                            putBoolean("fromUi", true)
                        }
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
