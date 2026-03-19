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

    private fun notificationManager(): CallkitNotificationManager? =
        FlutterCallkitIncomingPlugin.getInstance()
            ?.getCallkitNotificationManager()

    /* -------------------------------------------------- */
    /* Receiver                                           */
    /* -------------------------------------------------- */

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: run {
            Log.e(TAG, "onReceive: intent action is null, ignoring broadcast")
            return
        }

        val data = intent.extras?.getBundle(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA) ?: run {
            Log.e(TAG, "onReceive: missing EXTRA_CALLKIT_INCOMING_DATA for action=$action")
            return
        }

        Log.d(TAG, "onReceive: action=$action | silenceEvents=$silenceEvents")

        try {
            when (action) {

                /* ---------------- INCOMING ---------------- */

                "${context.packageName}.${CallkitConstants.ACTION_CALL_INCOMING}" -> {
                    val fromUi = data.getBoolean("fromUi", false)
                    Log.i(TAG, "ACTION_CALL_INCOMING — fromUi=$fromUi | callId=${data.getString(CallkitConstants.EXTRA_CALLKIT_ID)}")

                    if (fromUi) {
                        Log.d(TAG, "INCOMING (fromUi): launching CallkitIncomingActivity directly (no notification sound)")
                        notificationManager()?.showIncomingNotification(data)
                        sendEventFlutter(CallkitConstants.ACTION_CALL_INCOMING, data)
                        addCall(context, Data.fromBundle(data))

                        val callIntent =
                            CallkitIncomingActivity.getIntent(context, data).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        context.startActivity(callIntent)
                        Log.d(TAG, "INCOMING (fromUi): CallkitIncomingActivity started")
                    } else {
                        Log.d(TAG, "INCOMING (push): showing incoming notification")
                        notificationManager()?.showIncomingNotification(data)
                        Log.d(TAG, "INCOMING (push): notification shown, sending Flutter event")
                        sendEventFlutter(CallkitConstants.ACTION_CALL_INCOMING, data)
                        addCall(context, Data.fromBundle(data))
                        Log.d(TAG, "INCOMING (push): call added to active calls")
                    }
                }

                /* ---------------- START ---------------- */

                "${context.packageName}.${CallkitConstants.ACTION_CALL_START}" -> {
                    Log.i(TAG, "ACTION_CALL_START | callId=${data.getString(CallkitConstants.EXTRA_CALLKIT_ID)}")
                    CallkitNotificationService.startServiceWithAction(
                        context,
                        CallkitConstants.ACTION_CALL_START,
                        data
                    )
                    Log.d(TAG, "START: CallkitNotificationService started")
                    sendEventFlutter(CallkitConstants.ACTION_CALL_START, data)
                    addCall(context, Data.fromBundle(data), true)
                    Log.d(TAG, "START: call added as active (isAccepted=true)")
                }

                /* ---------------- ACCEPT ---------------- */

                "${context.packageName}.${CallkitConstants.ACTION_CALL_ACCEPT}" -> {
                    Log.i(TAG, "ACTION_CALL_ACCEPT | callId=${data.getString(CallkitConstants.EXTRA_CALLKIT_ID)}")

                    FlutterCallkitIncomingPlugin.notifyEventCallbacks(
                        CallkitEventCallback.CallEvent.ACCEPT,
                        data
                    )
                    Log.d(TAG, "ACCEPT: notified event callbacks")

                    CallkitNotificationService.startServiceWithAction(
                        context,
                        CallkitConstants.ACTION_CALL_ACCEPT,
                        data
                    )
                    Log.d(TAG, "ACCEPT: CallkitNotificationService started with ACCEPT action")

                    sendEventFlutter(CallkitConstants.ACTION_CALL_ACCEPT, data)
                    addCall(context, Data.fromBundle(data), true)
                    Log.d(TAG, "ACCEPT: call marked as active")
                }

                /* ---------------- DECLINE ---------------- */

                "${context.packageName}.${CallkitConstants.ACTION_CALL_DECLINE}" -> {
                    Log.i(TAG, "ACTION_CALL_DECLINE | callId=${data.getString(CallkitConstants.EXTRA_CALLKIT_ID)}")

                    FlutterCallkitIncomingPlugin.notifyEventCallbacks(
                        CallkitEventCallback.CallEvent.DECLINE,
                        data
                    )
                    Log.d(TAG, "DECLINE: notified event callbacks")

                    notificationManager()?.clearIncomingNotification(data, false)
                    Log.d(TAG, "DECLINE: incoming notification cleared")

                    sendEventFlutter(CallkitConstants.ACTION_CALL_DECLINE, data)
                    removeCall(context, Data.fromBundle(data))
                    Log.d(TAG, "DECLINE: call removed from active calls")
                }

                /* ---------------- ENDED ---------------- */

                "${context.packageName}.${CallkitConstants.ACTION_CALL_ENDED}" -> {
                    Log.i(TAG, "ACTION_CALL_ENDED | callId=${data.getString(CallkitConstants.EXTRA_CALLKIT_ID)}")

                    notificationManager()?.clearIncomingNotification(data, false)
                    Log.d(TAG, "ENDED: incoming notification cleared")

                    CallkitNotificationService.stopService(context)
                    Log.d(TAG, "ENDED: CallkitNotificationService stopped")

                    sendEventFlutter(CallkitConstants.ACTION_CALL_ENDED, data)
                    removeCall(context, Data.fromBundle(data))
                    Log.d(TAG, "ENDED: call removed from active calls")
                }

                /* ---------------- TIMEOUT ---------------- */

                "${context.packageName}.${CallkitConstants.ACTION_CALL_TIMEOUT}" -> {
                    Log.i(TAG, "ACTION_CALL_TIMEOUT | callId=${data.getString(CallkitConstants.EXTRA_CALLKIT_ID)}")

                    val manager = notificationManager()
                    manager?.clearIncomingNotification(data, false)
                    Log.d(TAG, "TIMEOUT: incoming notification cleared")

                    manager?.showMissCallNotification(data)
                    Log.d(TAG, "TIMEOUT: missed call notification shown")

                    sendEventFlutter(CallkitConstants.ACTION_CALL_TIMEOUT, data)
                    removeCall(context, Data.fromBundle(data))
                    Log.d(TAG, "TIMEOUT: call removed from active calls")
                }

                /* ---------------- CONNECTED ---------------- */

                "${context.packageName}.${CallkitConstants.ACTION_CALL_CONNECTED}" -> {
                    Log.i(TAG, "ACTION_CALL_CONNECTED | callId=${data.getString(CallkitConstants.EXTRA_CALLKIT_ID)}")

                    notificationManager()?.showOngoingCallNotification(data, true)
                    Log.d(TAG, "CONNECTED: ongoing call notification shown")

                    sendEventFlutter(CallkitConstants.ACTION_CALL_CONNECTED, data)
                }

                /* ---------------- CALLBACK ---------------- */

                "${context.packageName}.${CallkitConstants.ACTION_CALL_CALLBACK}" -> {
                    Log.i(TAG, "ACTION_CALL_CALLBACK | callId=${data.getString(CallkitConstants.EXTRA_CALLKIT_ID)}")

                    notificationManager()?.clearMissCallNotification(data)
                    Log.d(TAG, "CALLBACK: missed call notification cleared")

                    sendEventFlutter(CallkitConstants.ACTION_CALL_CALLBACK, data)

                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        Log.d(TAG, "CALLBACK: closing system dialogs (pre-Android 12)")
                        context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                    } else {
                        Log.d(TAG, "CALLBACK: skipping ACTION_CLOSE_SYSTEM_DIALOGS (Android 12+)")
                    }
                }

                else -> {
                    Log.e(TAG, "onReceive: unhandled action=$action")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onReceive: unhandled exception for action=$action", e)
        }
    }

    /* -------------------------------------------------- */
    /* Flutter Event Bridge                               */
    /* -------------------------------------------------- */

    private fun sendEventFlutter(event: String, data: Bundle) {
        if (silenceEvents) {
            Log.d(TAG, "sendEventFlutter: SKIPPED (silenceEvents=true) | event=$event")
            return
        }

        Log.d(TAG, "sendEventFlutter: sending event=$event to Flutter")

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

        Log.d(TAG, "sendEventFlutter: payload built for callId=${forwardData["id"]} | nameCaller=${forwardData["nameCaller"]}")
        FlutterCallkitIncomingPlugin.sendEvent(event, forwardData)
        Log.i(TAG, "sendEventFlutter: event=$event dispatched successfully to Flutter")
    }
}
