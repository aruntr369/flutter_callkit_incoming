package com.hiennv.flutter_callkit_incoming

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import kotlin.math.abs

class CallkitIncomingBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallkitIncomingReceiver"
        var silenceEvents = false

        fun getIntent(context: Context, action: String, data: Bundle?) =
            Intent(context, CallkitIncomingBroadcastReceiver::class.java).apply {
                this.action = "${context.packageName}.$action"
                putExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA, data)
            }

        fun getIntentIncoming(context: Context, data: Bundle?) =
            getIntent(context, CallkitConstants.ACTION_CALL_INCOMING, data)

        fun getIntentStart(context: Context, data: Bundle?) =
            getIntent(context, CallkitConstants.ACTION_CALL_START, data)

        fun getIntentAccept(context: Context, data: Bundle?) =
            getIntent(context, CallkitConstants.ACTION_CALL_ACCEPT, data)

        fun getIntentDecline(context: Context, data: Bundle?) =
            getIntent(context, CallkitConstants.ACTION_CALL_DECLINE, data)

        fun getIntentEnded(context: Context, data: Bundle?) =
            getIntent(context, CallkitConstants.ACTION_CALL_ENDED, data)

        fun getIntentTimeout(context: Context, data: Bundle?) =
            getIntent(context, CallkitConstants.ACTION_CALL_TIMEOUT, data)

        fun getIntentCallback(context: Context, data: Bundle?) =
            getIntent(context, CallkitConstants.ACTION_CALL_CALLBACK, data)

        fun getIntentHeldByCell(context: Context, data: Bundle?) =
            getIntent(context, CallkitConstants.ACTION_CALL_HELD, data)

        fun getIntentUnHeldByCell(context: Context, data: Bundle?) =
            getIntent(context, CallkitConstants.ACTION_CALL_UNHELD, data)

        fun getIntentConnected(context: Context, data: Bundle?) =
            getIntent(context, CallkitConstants.ACTION_CALL_CONNECTED, data)
    }

    /* -------------------------------------------------- */
    /* Helpers                                            */
    /* -------------------------------------------------- */

    /**
     * Returns the shared [CallkitNotificationManager] from the plugin when
     * available, or creates a standalone fallback instance so that
     * notifications still work even when the OS has killed the Flutter
     * engine / plugin (common on low-RAM devices).
     */
    private fun getOrCreateNotificationManager(context: Context): CallkitNotificationManager {
        FlutterCallkitIncomingPlugin.getInstance()
            ?.getCallkitNotificationManager()?.let { return it }

        Log.w(TAG, "Plugin instance is null – creating fallback CallkitNotificationManager")
        val soundPlayer = CallkitSoundPlayerManager(context.applicationContext)
        return CallkitNotificationManager(context.applicationContext, soundPlayer)
    }

    /* -------------------------------------------------- */
    /* Receiver                                           */
    /* -------------------------------------------------- */

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val data =
            intent.extras?.getBundle(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA) ?: return

        try {
            when (action) {

                /* ---------------- INCOMING ---------------- */

                "${context.packageName}.${CallkitConstants.ACTION_CALL_INCOMING}" -> {
                    val fromUi = data.getBoolean("fromUi", false)

                    if (fromUi) {
                        // UI-triggered incoming (no notification sound)
                        sendEventFlutter(CallkitConstants.ACTION_CALL_INCOMING, data)
                        addCallAsync(context, Data.fromBundle(data))

                        val callIntent =
                            CallkitIncomingActivity.getIntent(context, data).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        context.startActivity(callIntent)
                    } else {
                        getOrCreateNotificationManager(context).showIncomingNotification(data)
                        sendEventFlutter(CallkitConstants.ACTION_CALL_INCOMING, data)
                        addCallAsync(context, Data.fromBundle(data))
                    }
                }

                /* ---------------- START ---------------- */

                "${context.packageName}.${CallkitConstants.ACTION_CALL_START}" -> {
                    CallkitNotificationService.startServiceWithAction(
                        context,
                        CallkitConstants.ACTION_CALL_START,
                        data
                    )
                    sendEventFlutter(CallkitConstants.ACTION_CALL_START, data)
                    addCallAsync(context, Data.fromBundle(data), true)
                }

                /* ---------------- ACCEPT ---------------- */

                "${context.packageName}.${CallkitConstants.ACTION_CALL_ACCEPT}" -> {
                    FlutterCallkitIncomingPlugin.notifyEventCallbacks(
                        CallkitEventCallback.CallEvent.ACCEPT,
                        data
                    )

                    // Send the event to Dart FIRST so the callback always fires,
                    // even if starting the foreground service fails below.
                    sendEventFlutter(CallkitConstants.ACTION_CALL_ACCEPT, data)
                    addCallAsync(context, Data.fromBundle(data), true)

                    try {
                        CallkitNotificationService.startServiceWithAction(
                            context,
                            CallkitConstants.ACTION_CALL_ACCEPT,
                            data
                        )
                    } catch (e: Exception) {
                        // On Android 12+ starting a foreground service from a
                        // BroadcastReceiver may throw. Clear the notification
                        // manually in that case.
                        Log.e(TAG, "Failed to start accept service", e)
                        getOrCreateNotificationManager(context)
                            .clearIncomingNotification(data, true)
                    }
                }

                /* ---------------- DECLINE ---------------- */

                "${context.packageName}.${CallkitConstants.ACTION_CALL_DECLINE}" -> {
                    FlutterCallkitIncomingPlugin.notifyEventCallbacks(
                        CallkitEventCallback.CallEvent.DECLINE,
                        data
                    )

                    getOrCreateNotificationManager(context).clearIncomingNotification(data, false)
                    sendEventFlutter(CallkitConstants.ACTION_CALL_DECLINE, data)
                    removeCallAsync(context, Data.fromBundle(data))
                }

                /* ---------------- ENDED ---------------- */

                "${context.packageName}.${CallkitConstants.ACTION_CALL_ENDED}" -> {
                    getOrCreateNotificationManager(context).clearIncomingNotification(data, false)
                    CallkitNotificationService.stopService(context)
                    sendEventFlutter(CallkitConstants.ACTION_CALL_ENDED, data)
                    removeCallAsync(context, Data.fromBundle(data))
                }

                /* ---------------- TIMEOUT ---------------- */

                "${context.packageName}.${CallkitConstants.ACTION_CALL_TIMEOUT}" -> {
                    val manager = getOrCreateNotificationManager(context)
                    manager.clearIncomingNotification(data, false)
                    manager.showMissCallNotification(data)
                    sendEventFlutter(CallkitConstants.ACTION_CALL_TIMEOUT, data)
                    removeCallAsync(context, Data.fromBundle(data))
                }

                /* ---------------- CONNECTED ---------------- */

                "${context.packageName}.${CallkitConstants.ACTION_CALL_CONNECTED}" -> {
                    getOrCreateNotificationManager(context).showOngoingCallNotification(data, true)
                    sendEventFlutter(CallkitConstants.ACTION_CALL_CONNECTED, data)
                }

                /* ---------------- CALLBACK ---------------- */

                "${context.packageName}.${CallkitConstants.ACTION_CALL_CALLBACK}" -> {
                    getOrCreateNotificationManager(context).clearMissCallNotification(data)
                    sendEventFlutter(CallkitConstants.ACTION_CALL_CALLBACK, data)

                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Receiver error", e)
        }

    }

    /* -------------------------------------------------- */
    /* Async Call Storage (off main thread)               */
    /* -------------------------------------------------- */

    private fun addCallAsync(context: Context, data: Data, isAccepted: Boolean = false) {
        Thread {
            try {
                addCall(context, data, isAccepted)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist call", e)
            }
        }.start()
    }

    private fun removeCallAsync(context: Context, data: Data) {
        Thread {
            try {
                removeCall(context, data)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove call", e)
            }
        }.start()
    }

    /* -------------------------------------------------- */
    /* Flutter Event Bridge                               */
    /* -------------------------------------------------- */

    private fun sendEventFlutter(event: String, data: Bundle) {
        if (silenceEvents) return

        val android = mapOf(
            "isCustomNotification" to data.getBoolean(
                CallkitConstants.EXTRA_CALLKIT_IS_CUSTOM_NOTIFICATION, false
            ),
            "isCustomSmallExNotification" to data.getBoolean(
                CallkitConstants.EXTRA_CALLKIT_IS_CUSTOM_SMALL_EX_NOTIFICATION, false
            ),
            "ringtonePath" to data.getString(
                CallkitConstants.EXTRA_CALLKIT_RINGTONE_PATH, ""
            ),
            "backgroundColor" to data.getString(
                CallkitConstants.EXTRA_CALLKIT_BACKGROUND_COLOR, ""
            ),
            "backgroundUrl" to data.getString(
                CallkitConstants.EXTRA_CALLKIT_BACKGROUND_URL, ""
            ),
            "actionColor" to data.getString(
                CallkitConstants.EXTRA_CALLKIT_ACTION_COLOR, ""
            ),
            "textColor" to data.getString(
                CallkitConstants.EXTRA_CALLKIT_TEXT_COLOR, ""
            ),
            "incomingCallNotificationChannelName" to data.getString(
                CallkitConstants.EXTRA_CALLKIT_INCOMING_CALL_NOTIFICATION_CHANNEL_NAME, ""
            ),
            "missedCallNotificationChannelName" to data.getString(
                CallkitConstants.EXTRA_CALLKIT_MISSED_CALL_NOTIFICATION_CHANNEL_NAME, ""
            ),
            "isImportant" to data.getBoolean(
                CallkitConstants.EXTRA_CALLKIT_IS_IMPORTANT, true
            ),
            "isBot" to data.getBoolean(
                CallkitConstants.EXTRA_CALLKIT_IS_BOT, false
            )
        )

        val missedCallNotification = mapOf(
            "id" to data.getInt(CallkitConstants.EXTRA_CALLKIT_MISSED_CALL_ID),
            "showNotification" to data.getBoolean(
                CallkitConstants.EXTRA_CALLKIT_MISSED_CALL_SHOW
            ),
            "count" to data.getInt(
                CallkitConstants.EXTRA_CALLKIT_MISSED_CALL_COUNT
            ),
            "subtitle" to data.getString(
                CallkitConstants.EXTRA_CALLKIT_MISSED_CALL_SUBTITLE
            ),
            "callbackText" to data.getString(
                CallkitConstants.EXTRA_CALLKIT_MISSED_CALL_CALLBACK_TEXT
            ),
            "isShowCallback" to data.getBoolean(
                CallkitConstants.EXTRA_CALLKIT_MISSED_CALL_CALLBACK_SHOW
            )
        )

        val callingNotification = mapOf(
            "id" to data.getString(CallkitConstants.EXTRA_CALLKIT_CALLING_ID),
            "showNotification" to data.getBoolean(
                CallkitConstants.EXTRA_CALLKIT_CALLING_SHOW
            ),
            "subtitle" to data.getString(
                CallkitConstants.EXTRA_CALLKIT_CALLING_SUBTITLE
            ),
            "callbackText" to data.getString(
                CallkitConstants.EXTRA_CALLKIT_CALLING_HANG_UP_TEXT
            ),
            "isShowCallback" to data.getBoolean(
                CallkitConstants.EXTRA_CALLKIT_CALLING_HANG_UP_SHOW
            )
        )

        val forwardData = mapOf(
            "id" to data.getString(CallkitConstants.EXTRA_CALLKIT_ID, ""),
            "nameCaller" to data.getString(
                CallkitConstants.EXTRA_CALLKIT_NAME_CALLER, ""
            ),
            "avatar" to data.getString(
                CallkitConstants.EXTRA_CALLKIT_AVATAR, ""
            ),
            "number" to data.getString(
                CallkitConstants.EXTRA_CALLKIT_HANDLE, ""
            ),
            "type" to data.getInt(
                CallkitConstants.EXTRA_CALLKIT_TYPE, 0
            ),
            "duration" to data.getLong(
                CallkitConstants.EXTRA_CALLKIT_DURATION, 0L
            ),
            "textAccept" to data.getString(
                CallkitConstants.EXTRA_CALLKIT_TEXT_ACCEPT, ""
            ),
            "textDecline" to data.getString(
                CallkitConstants.EXTRA_CALLKIT_TEXT_DECLINE, ""
            ),
            "extra" to data.getSerializable(
                CallkitConstants.EXTRA_CALLKIT_EXTRA
            ),
            "missedCallNotification" to missedCallNotification,
            "callingNotification" to callingNotification,
            "android" to android
        )

        FlutterCallkitIncomingPlugin.sendEvent(event, forwardData)
    }
}
