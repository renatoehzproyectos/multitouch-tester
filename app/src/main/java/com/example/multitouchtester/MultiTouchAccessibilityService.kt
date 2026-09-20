package com.example.multitouchtester

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Holds two simultaneous synthetic touch contacts down until the user releases them.
 *
 * Design notes / why this approach and not something else:
 * - dispatchGesture() is the only public Android API that can inject touch input outside
 *   the app's own window, and it is gated behind an explicitly user-enabled
 *   AccessibilityService. It cannot hold a gesture literally forever, but a single
 *   StrokeDescription can be held continuously for the platform's maximum gesture
 *   duration (GestureDescription.getMaxGestureDuration(), ~60s) in one uninterrupted
 *   call - satisfying "never convert into sequential taps". If that duration is reached
 *   while the user still wants it held, the same hold is re-dispatched seamlessly.
 *   An earlier version used continueStroke()/willContinue(true) to chain short segments
 *   into an indefinite hold; on this project's test hardware that chain was cancelled by
 *   the system within milliseconds regardless of segment length, so it was abandoned in
 *   favor of this single-long-stroke approach, which is the better-supported path.
 * - Two StrokeDescriptions are always dispatched together inside a single
 *   GestureDescription, so both contacts start and continue in lockstep - genuine
 *   multi-touch, not two independent single-touch sequences.
 * - This never touches accessibility content APIs, never simulates key events, and
 *   never targets anything other than the coordinates the user explicitly placed. It
 *   cannot see or read other apps (canRetrieveWindowContent = false in the config).
 */
class MultiTouchAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MultiTouchTester"
    }

    enum class TouchState { READY, ACTIVE, RELEASED, ERROR }

    private var windowManager: WindowManager? = null
    private var panelView: View? = null
    private var marker1View: View? = null
    private var marker2View: View? = null

    private var point1 = FloatArray(2)
    private var point2 = FloatArray(2)

    private var state = TouchState.READY
    private var stateText: TextView? = null

    // Segment-based continuation (willContinue/continueStroke) was tried and abandoned:
    // see startTest() for why a single long-duration stroke is used instead.

    private var sessionActive = false
    private var sessionGeneration = 0 // bumped on stop/release to invalidate stale callbacks
    private var sessionStartMs = 0L

    private val showPanelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MainActivity.ACTION_SHOW_PANEL) {
                showPanelIfNeeded()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        registerReceiver(
            showPanelReceiver,
            IntentFilter(MainActivity.ACTION_SHOW_PANEL),
            Context.RECEIVER_NOT_EXPORTED.takeIf { Build.VERSION.SDK_INT >= 33 } ?: 0
        )
        showPanelIfNeeded()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty: this service never inspects accessibility events/content.
    }

    override fun onInterrupt() {
        // System asked us to stop; treat exactly like emergency stop.
        emergencyStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Screen rotated or display size changed mid-test. Holding synthetic coordinates
        // across a coordinate-space change is unsafe (they'd land on the wrong element),
        // so we fail safe: release everything and surface an error rather than silently
        // continuing to touch the wrong place.
        if (sessionActive) {
            emergencyStop()
            setState(TouchState.ERROR, "Display changed mid-test - touches released for safety.")
        }
        repositionPanel()
    }

    override fun onDestroy() {
        // Never leave synthetic touches held if the service goes away.
        emergencyStop()
        try { unregisterReceiver(showPanelReceiver) } catch (_: Exception) {}
        removeOverlayViews()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        emergencyStop()
        removeOverlayViews()
        return super.onUnbind(intent)
    }

    // ---------------------------------------------------------------------
    // Overlay UI
    // ---------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    private fun showPanelIfNeeded() {
        if (panelView != null) return
        val wm = windowManager ?: return

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        point1 = floatArrayOf(metrics.widthPixels * 0.3f, metrics.heightPixels * 0.5f)
        point2 = floatArrayOf(metrics.widthPixels * 0.7f, metrics.heightPixels * 0.5f)

        val overlayType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

        // --- control panel ---
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(230, 30, 30, 30))
            setPadding(24, 24, 24, 24)
        }
        stateText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val p1Text = TextView(this).apply { setTextColor(Color.CYAN); textSize = 12f }
        val p2Text = TextView(this).apply { setTextColor(Color.YELLOW); textSize = 12f }

        fun refreshCoordLabels() {
            p1Text.text = "Point 1: (${point1[0].toInt()}, ${point1[1].toInt()})"
            p2Text.text = "Point 2: (${point2[0].toInt()}, ${point2[1].toInt()})"
        }
        refreshCoordLabels()

        val startBtn = Button(this).apply {
            text = "Start Test"
            setOnClickListener { startTest() }
        }
        val releaseBtn = Button(this).apply {
            text = "Release"
            setOnClickListener { releaseTest() }
        }
        val stopBtn = Button(this).apply {
            text = "EMERGENCY STOP"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.RED)
            setOnClickListener { emergencyStop(); setState(TouchState.READY, "Stopped.") }
        }

        panel.addView(stateText)
        panel.addView(p1Text)
        panel.addView(p2Text)
        panel.addView(startBtn)
        panel.addView(releaseBtn)
        panel.addView(stopBtn)

        val panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 20
            y = 100
        }
        makeDraggable(panel, panelParams)
        wm.addView(panel, panelParams)
        panelView = panel

        // --- marker 1 ---
        val m1 = makeMarkerView(Color.CYAN)
        val m1Params = markerParams(point1)
        makeMarkerDraggable(m1, m1Params, isPoint1 = true) { refreshCoordLabels() }
        wm.addView(m1, m1Params)
        marker1View = m1

        // --- marker 2 ---
        val m2 = makeMarkerView(Color.YELLOW)
        val m2Params = markerParams(point2)
        makeMarkerDraggable(m2, m2Params, isPoint1 = false) { refreshCoordLabels() }
        wm.addView(m2, m2Params)
        marker2View = m2

        setState(TouchState.READY, "Ready. Drag markers, then Start Test.")
    }

    private fun makeMarkerView(color: Int): View {
        return FrameLayout(this).apply {
            setBackgroundColor(color)
            alpha = 0.7f
        }
    }

    private fun markerParams(point: FloatArray): WindowManager.LayoutParams {
        val size = 60
        return WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = point[0].toInt() - size / 2
            y = point[1].toInt() - size / 2
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeMarkerDraggable(
        view: View,
        params: WindowManager.LayoutParams,
        isPoint1: Boolean,
        onMoved: () -> Unit
    ) {
        var startX = 0f
        var startY = 0f
        var startTouchX = 0f
        var startTouchY = 0f
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x.toFloat()
                    startY = params.y.toFloat()
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + (event.rawX - startTouchX)).toInt()
                    params.y = (startY + (event.rawY - startTouchY)).toInt()
                    windowManager?.updateViewLayout(v, params)
                    val size = 60
                    val target = if (isPoint1) point1 else point2
                    target[0] = (params.x + size / 2).toFloat()
                    target[1] = (params.y + size / 2).toFloat()
                    onMoved()
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(view: View, params: WindowManager.LayoutParams) {
        var startX = 0f
        var startY = 0f
        var startTouchX = 0f
        var startTouchY = 0f
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x.toFloat()
                    startY = params.y.toFloat()
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    false // allow child buttons to still receive clicks
                }
                MotionEvent.ACTION_MOVE -> {
                    // Only drag the panel by its background, not while pressing a button;
                    // buttons consume MOVE themselves once ACTION_DOWN targeted them.
                    false
                }
                else -> false
            }
        }
    }

    private fun removeOverlayViews() {
        val wm = windowManager ?: return
        listOf(panelView, marker1View, marker2View).forEach { v ->
            if (v != null) {
                try { wm.removeView(v) } catch (_: Exception) {}
            }
        }
        panelView = null
        marker1View = null
        marker2View = null
    }

    private fun repositionPanel() {
        // Left as a no-op placeholder: on rotation we already abort the session and the
        // user can drag the panel/markers back into view manually, since guessing a new
        // "correct" position for arbitrary launchers/layouts is out of scope here.
    }

    private fun setState(newState: TouchState, message: String) {
        state = newState
        stateText?.text = "State: ${newState.name} - $message"
    }

    // ---------------------------------------------------------------------
    // Touch injection
    // ---------------------------------------------------------------------

    private fun startTest() {
        if (sessionActive) return
        sessionGeneration++
        val myGeneration = sessionGeneration
        sessionStartMs = System.currentTimeMillis()

        // Single non-continuing stroke held for the platform's maximum gesture duration
        // (~60s on API 26+). This is one continuous press at the API level - never
        // sequential taps - and deliberately avoids continueStroke()/willContinue(true):
        // on this device that continuation-chaining technique was observed to get
        // cancelled by the system within tens of milliseconds regardless of segment
        // length, while a single uninterrupted long stroke is the well-supported path.
        val maxDuration = GestureDescription.getMaxGestureDuration()

        val path1 = Path().apply { moveTo(point1[0], point1[1]) }
        val path2 = Path().apply { moveTo(point2[0], point2[1]) }

        val stroke1 = GestureDescription.StrokeDescription(path1, 0, maxDuration)
        val stroke2 = GestureDescription.StrokeDescription(path2, 0, maxDuration)

        val gesture = GestureDescription.Builder()
            .addStroke(stroke1)
            .addStroke(stroke2)
            .build()

        Log.d(TAG, "startTest: dispatching single ${maxDuration}ms hold at " +
                "(${point1[0]},${point1[1]}) and (${point2[0]},${point2[1]})")

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                val elapsed = System.currentTimeMillis() - sessionStartMs
                Log.d(TAG, "onCompleted: hold ran its full course after ${elapsed}ms")
                if (myGeneration != sessionGeneration) return // release/stop already handled it
                if (!sessionActive) return
                // Reached the platform's max duration while the user still wants it held -
                // seamlessly re-dispatch the same hold rather than surfacing an error.
                startTest()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                val elapsed = System.currentTimeMillis() - sessionStartMs
                // A cancellation with this generation stale means WE caused it on purpose
                // via releaseTest()/emergencyStop() (see endStrokes) - that's success, not
                // an error, so ignore it here.
                if (myGeneration != sessionGeneration) {
                    Log.d(TAG, "onCancelled: expected, this generation was released ($elapsed" +
                            "ms in)")
                    return
                }
                Log.w(TAG, "onCancelled: UNEXPECTED cancellation after ${elapsed}ms")
                sessionActive = false
                setState(
                    TouchState.ERROR,
                    "Touch hold was interrupted by the system after ${elapsed}ms " +
                            "(not initiated by Release/Stop)."
                )
            }
        }, null)

        if (!dispatched) {
            Log.w(TAG, "startTest: dispatchGesture() returned false immediately")
            setState(
                TouchState.ERROR,
                "Android rejected the gesture request (accessibility service not " +
                        "active, or the API declined to inject input). No touches were sent."
            )
            return
        }

        sessionActive = true
        setState(TouchState.ACTIVE, "Two touches active.")
    }

    private fun releaseTest() {
        if (!sessionActive) {
            setState(TouchState.READY, "Nothing active to release.")
            return
        }
        endStrokes(finalState = TouchState.RELEASED, message = "Released cleanly.")
    }

    private fun emergencyStop() {
        if (!sessionActive) return
        endStrokes(finalState = TouchState.RELEASED, message = "Emergency stop - released.")
    }

    /**
     * Forces the currently-held long stroke to lift by dispatching a brand-new, trivial
     * gesture at the same coordinates. Per AccessibilityService's documented contract,
     * dispatching a new gesture cancels whatever gesture is currently in progress - so
     * this is the standard, supported way to end a long hold early, not a workaround.
     * sessionGeneration is bumped first so the original hold's onCancelled callback
     * recognizes this as an intentional release rather than a system-caused failure.
     */
    private fun endStrokes(finalState: TouchState, message: String) {
        sessionGeneration++
        sessionActive = false

        val path1 = Path().apply { moveTo(point1[0], point1[1]) }
        val path2 = Path().apply { moveTo(point2[0], point2[1]) }

        val liftStroke1 = GestureDescription.StrokeDescription(path1, 0, 1L)
        val liftStroke2 = GestureDescription.StrokeDescription(path2, 0, 1L)

        val gesture = GestureDescription.Builder()
            .addStroke(liftStroke1)
            .addStroke(liftStroke2)
            .build()

        val dispatched = dispatchGesture(gesture, null, null)
        if (!dispatched) {
            setState(TouchState.ERROR, "Could not confirm release; touches may still be held.")
            return
        }
        setState(finalState, message)
    }
}
