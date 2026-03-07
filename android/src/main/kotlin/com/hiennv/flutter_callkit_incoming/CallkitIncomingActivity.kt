package com.hiennv.flutter_callkit_incoming

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.*
import android.text.TextUtils
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.ViewGroup.MarginLayoutParams
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import com.hiennv.flutter_callkit_incoming.widgets.RippleRelativeLayout
import de.hdodenhof.circleimageview.CircleImageView
import kotlin.math.abs

class CallkitIncomingActivity : Activity() {

    companion object {
        private const val ACTION_ENDED_CALL_INCOMING =
            "com.hiennv.flutter_callkit_incoming.ACTION_ENDED_CALL_INCOMING"

        fun getIntent(context: Context, data: Bundle) =
            Intent(CallkitConstants.ACTION_CALL_INCOMING).apply {
                action = "${context.packageName}.${CallkitConstants.ACTION_CALL_INCOMING}"
                putExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA, data)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

        fun getIntentEnded(context: Context, isAccepted: Boolean): Intent =
            Intent("${context.packageName}.$ACTION_ENDED_CALL_INCOMING").apply {
                putExtra("ACCEPTED", isAccepted)
            }
    }

    /* -------------------------------------------------- */
    /* Receiver                                           */
    /* -------------------------------------------------- */

    inner class EndedCallkitIncomingBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isFinishing) return
            if (intent.getBooleanExtra("ACCEPTED", false)) {
                finishDelayed()
            } else {
                finishTask()
            }
        }
    }

    private val endedReceiver = EndedCallkitIncomingBroadcastReceiver()

    /* -------------------------------------------------- */
    /* Views                                              */
    /* -------------------------------------------------- */

    private lateinit var ivBackground: ImageView
    private lateinit var llBackgroundAnimation: RippleRelativeLayout
    private lateinit var tvNameCaller: TextView
    private lateinit var tvNumber: TextView
    private lateinit var ivLogo: ImageView
    private lateinit var ivAvatar: CircleImageView
    private lateinit var llAction: LinearLayout
    private lateinit var ivAcceptCall: ImageView
    private lateinit var tvAccept: TextView
    private lateinit var ivDeclineCall: ImageView
    private lateinit var tvDecline: TextView

    /* -------------------------------------------------- */
    /* Lifecycle                                          */
    /* -------------------------------------------------- */

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation =
            if (!Utils.isTablet(this)) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        setupLockScreenFlags()
        transparentStatusAndNavigation()
        setContentView(R.layout.activity_callkit_incoming)

        initView()
        handleIncomingData(intent)
        registerEndedReceiver()
    }

    /* -------------------------------------------------- */
    /* Setup                                              */
    /* -------------------------------------------------- */

    private fun setupLockScreenFlags() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
    }

    private fun registerEndedReceiver() {
        val filter = IntentFilter("${packageName}.$ACTION_ENDED_CALL_INCOMING")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(endedReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(endedReceiver, filter)
        }
    }

    private fun transparentStatusAndNavigation() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT &&
        Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP
    ) {
        setWindowFlag(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
            true
        )
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        setWindowFlag(
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
            false
        )
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }
}

private fun setWindowFlag(bits: Int, on: Boolean) {
    val win = window
    val winParams = win.attributes
    winParams.flags = if (on) {
        winParams.flags or bits
    } else {
        winParams.flags and bits.inv()
    }
    win.attributes = winParams
}


    /* -------------------------------------------------- */
    /* Incoming Data                                      */
    /* -------------------------------------------------- */

    private fun handleIncomingData(intent: Intent) {
        val data = intent.extras?.getBundle(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)
            ?: return finish()

        val showOnLock =
            data.getBoolean(CallkitConstants.EXTRA_CALLKIT_IS_SHOW_FULL_LOCKED_SCREEN, true)
        if (showOnLock && Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }

        val textColor = data.getString(CallkitConstants.EXTRA_CALLKIT_TEXT_COLOR, "#ffffff")
        tvNameCaller.text = data.getString(CallkitConstants.EXTRA_CALLKIT_NAME_CALLER, "")
        tvNumber.text = data.getString(CallkitConstants.EXTRA_CALLKIT_HANDLE, "")
        tvNumber.visibility =
            if (data.getBoolean(CallkitConstants.EXTRA_CALLKIT_IS_SHOW_CALL_ID, false))
                View.VISIBLE else View.INVISIBLE

        applyTextColor(textColor)

        loadImages(data)
        configureButtons(data, textColor)
        handleTimeout(data)
    }

    private fun applyTextColor(color: String) {
        try {
            tvNameCaller.setTextColor(Color.parseColor(color))
            tvNumber.setTextColor(Color.parseColor(color))
            tvAccept.setTextColor(Color.parseColor(color))
            tvDecline.setTextColor(Color.parseColor(color))
        } catch (_: Exception) {
        }
    }

    private fun loadImages(data: Bundle) {
        val headers =
            data.getSerializable(CallkitConstants.EXTRA_CALLKIT_HEADERS) as? HashMap<String, Any?>
                ?: hashMapOf()

        data.getString(CallkitConstants.EXTRA_CALLKIT_AVATAR)?.takeIf { it.isNotEmpty() }?.let {
            ivAvatar.visibility = View.VISIBLE
            ImageLoaderProvider.loadImage(
                this, it, headers, R.drawable.ic_default_avatar, ivAvatar
            )
        }

        data.getString(CallkitConstants.EXTRA_CALLKIT_BACKGROUND_URL)?.takeIf { it.isNotEmpty() }
            ?.let {
                ImageLoaderProvider.loadImage(
                    this, it, headers, R.drawable.transparent, ivBackground
                )
            }
    }

    private fun configureButtons(data: Bundle, textColor: String) {
        tvAccept.text =
            data.getString(CallkitConstants.EXTRA_CALLKIT_TEXT_ACCEPT)
                ?.takeIf { it.isNotEmpty() } ?: getString(R.string.text_accept)

        tvDecline.text =
            data.getString(CallkitConstants.EXTRA_CALLKIT_TEXT_DECLINE)
                ?.takeIf { it.isNotEmpty() } ?: getString(R.string.text_decline)
    }

    /* -------------------------------------------------- */
    /* Timeout & WakeLock                                 */
    /* -------------------------------------------------- */

    private fun handleTimeout(data: Bundle) {
        val duration = data.getLong(CallkitConstants.EXTRA_CALLKIT_DURATION, 0L)
        val start =
            data.getLong(CallkitNotificationManager.EXTRA_TIME_START_CALL, System.currentTimeMillis())

        wakeLock(duration)

        val remaining = duration - abs(System.currentTimeMillis() - start)
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) finishTask()
        }, remaining)
    }

    private fun wakeLock(duration: Long) {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Callkit:WakeLock"
        ).apply { acquire(duration) }
    }

    /* -------------------------------------------------- */
    /* UI                                                 */
    /* -------------------------------------------------- */

    private fun initView() {
        ivBackground = findViewById(R.id.ivBackground)
        llBackgroundAnimation = findViewById(R.id.llBackgroundAnimation)
        tvNameCaller = findViewById(R.id.tvNameCaller)
        tvNumber = findViewById(R.id.tvNumber)
        ivLogo = findViewById(R.id.ivLogo)
        ivAvatar = findViewById(R.id.ivAvatar)
        llAction = findViewById(R.id.llAction)
        ivAcceptCall = findViewById(R.id.ivAcceptCall)
        tvAccept = findViewById(R.id.tvAccept)
        ivDeclineCall = findViewById(R.id.ivDeclineCall)
        tvDecline = findViewById(R.id.tvDecline)

        (llAction.layoutParams as MarginLayoutParams).bottomMargin =
            Utils.getNavigationBarHeight(this)

        llBackgroundAnimation.startRippleAnimation()
        ivAcceptCall.animation =
            AnimationUtils.loadAnimation(this, R.anim.shake_anim)

        ivAcceptCall.setOnClickListener { onAcceptClick() }
        ivDeclineCall.setOnClickListener { onDeclineClick() }
    }

    /* -------------------------------------------------- */
    /* Actions                                            */
    /* -------------------------------------------------- */

    private fun onAcceptClick() {
        val data = intent.extras?.getBundle(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)

        CallkitNotificationService.startServiceWithAction(
            this, CallkitConstants.ACTION_CALL_ACCEPT, data
        )

        startActivity(
            TransparentActivity.getIntent(
                this, CallkitConstants.ACTION_CALL_ACCEPT, data
            )
        )

        dismissKeyguard()
        finish()
    }

    private fun onDeclineClick() {
        sendBroadcast(
            CallkitIncomingBroadcastReceiver.getIntentDecline(
                this, intent.extras?.getBundle(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)
            )
        )
        finishTask()
    }

    private fun dismissKeyguard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
                .requestDismissKeyguard(this, null)
        }
    }

    /* -------------------------------------------------- */
    /* Finish                                             */
    /* -------------------------------------------------- */

    private fun finishDelayed() =
        Handler(Looper.getMainLooper()).postDelayed({ finishTask() }, 1000)

    private fun finishTask() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            finishAndRemoveTask()
        else finish()
    }

    override fun onDestroy() {
        unregisterReceiver(endedReceiver)
        super.onDestroy()
    }

    override fun onBackPressed() {}
}
