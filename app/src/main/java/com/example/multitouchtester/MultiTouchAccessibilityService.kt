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
 *   AccessibilityService. There is no way to hold a gesture "forever" in one call, so a
 *   single stroke is expressed as a short segment with willContinue(true); when the OS
 *   reports it completed, we immediately continue it with continueStroke() at the same
 *   coordinates. Done back-to-back this reads to the receiving app as one uninterrupted
 *   press, not a series of taps - satisfying "never convert into sequential taps".
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

    // Segment length for each continued chunk of the held stroke. Short enough that a
    // rotation/resize/emergency-stop is honored quickly; long enough to avoid excessive
    // rescheduling. Kept at a few seconds rather than a few hundred ms: very tight
    // continuation loops (<500ms) have been observed to race with busy launcher/system
    // UI threads on some OEM builds and get cancelled by the system before the next
    // continueStroke() lands.
    private val segmentDurationMs = 3000L

    private var strokeId1: GestureDescription.StrokeDescription? = null
    private var strokeId2: GestureDescription.StrokeDescription? = null
    private var sessionActive = false
    private var sessionGeneration = 0 // bumped on stop/release to invalidate stale callbacks
    private var continuationCount = 0
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
        continuationCount = 0
        sessionStartMs = System.currentTimeMillis()

        val path1 = Path().apply { moveTo(point1[0], point1[1]) }
        val path2 = Path().apply { moveTo(point2[0], point2[1]) }

        val stroke1 = GestureDescription.StrokeDescription(
            path1, 0, segmentDurationMs, /* willContinue = */ true
        )
        val stroke2 = GestureDescription.StrokeDescription(
            path2, 0, segmentDurationMs, /* willContinue = */ true
        )

        val gesture = GestureDescription.Builder()
            .addStroke(stroke1)
            .addStroke(stroke2)
            .build()

        strokeId1 = stroke1
        strokeId2 = stroke2

        Log.d(TAG, "startTest: dispatching initial gesture at " +
                "(${point1[0]},${point1[1]}) and (${point2[0]},${point2[1]})")

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                val elapsed = System.currentTimeMillis() - sessionStartMs
                Log.d(TAG, "onCompleted: initial segment ok after ${elapsed}ms")
                if (myGeneration != sessionGeneration) return // superseded by stop/release
                continueHolding(myGeneration)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                val elapsed = System.currentTimeMillis() - sessionStartMs
                Log.w(TAG, "onCancelled: initial segment cancelled after ${elapsed}ms, " +
                        "$continuationCount prior continuations")
                if (myGeneration != sessionGeneration) return
                sessionActive = false
                setState(
                    TouchState.ERROR,
                    "Gesture was cancelled by the system after ${elapsed}ms " +
                            "(0 continuations completed)."
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

    /** Keeps re-issuing continueStroke() at the same coordinates so the press never lifts. */
    private fun continueHolding(myGeneration: Int) {
        if (!sessionActive || myGeneration != sessionGeneration) return
        val s1 = strokeId1 ?: return
        val s2 = strokeId2 ?: return

        val path1 = Path().apply { moveTo(point1[0], point1[1]) }
        val path2 = Path().apply { moveTo(point2[0], point2[1]) }

        val nextStroke1 = s1.continueStroke(path1, 0, segmentDurationMs, true)
        val nextStroke2 = s2.continueStroke(path2, 0, segmentDurationMs, true)

        val gesture = GestureDescription.Builder()
            .addStroke(nextStroke1)
            .addStroke(nextStroke2)
            .build()

        strokeId1 = nextStroke1
        strokeId2 = nextStroke2

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (myGeneration != sessionGeneration) return
                continuationCount++
                setState(TouchState.ACTIVE, "Two touches active ($continuationCount).")
                continueHolding(myGeneration)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                val elapsed = System.currentTimeMillis() - sessionStartMs
                Log.w(TAG, "onCancelled: continuation cancelled after ${elapsed}ms, " +
                        "$continuationCount prior continuations completed")
                if (myGeneration != sessionGeneration) return
                sessionActive = false
                setState(
                    TouchState.ERROR,
                    "Touch hold was interrupted by the system after " +
                            "$continuationCount continuation(s) (${elapsed}ms)."
                )
            }
        }, null)

        if (!dispatched) {
            sessionActive = false
            setState(TouchState.ERROR, "Could not continue holding the touches.")
        }
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

    /** Ends both strokes together in one gesture so both contacts lift simultaneously. */
    private fun endStrokes(finalState: TouchState, message: String) {
        sessionGeneration++ // invalidate any in-flight continueHolding callbacks
        val s1 = strokeId1
        val s2 = strokeId2
        sessionActive = false
        strokeId1 = null
        strokeId2 = null

        if (s1 == null || s2 == null) {
            setState(finalState, message)
            return
        }

        val path1 = Path().apply { moveTo(point1[0], point1[1]) }
        val path2 = Path().apply { moveTo(point2[0], point2[1]) }

        // Short final segment, willContinue = false -> lifts both fingers together.
        val finalStroke1 = s1.continueStroke(path1, 0, 50L, false)
        val finalStroke2 = s2.continueStroke(path2, 0, 50L, false)

        val gesture = GestureDescription.Builder()
            .addStroke(finalStroke1)
            .addStroke(finalStroke2)
            .build()

        val dispatched = dispatchGesture(gesture, null, null)
        if (!dispatched) {
            setState(TouchState.ERROR, "Could not confirm release; touches may still be held.")
            return
        }
        setState(finalState, message)
    }
}
