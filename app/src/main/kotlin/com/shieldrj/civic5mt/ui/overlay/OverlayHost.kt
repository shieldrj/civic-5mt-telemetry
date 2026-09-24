package com.shieldrj.civic5mt.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.shieldrj.civic5mt.service.loadOverlayPosition
import com.shieldrj.civic5mt.service.saveOverlayPosition
import kotlin.math.roundToInt

/**
 * Hosts a Compose window on top of whatever else is on screen.
 *
 * This is the reason the telemetry pipeline lives in a service rather than in a ViewModel.
 * There is no Activity behind this window - the thing on screen is Google Maps - so nothing
 * here can depend on one existing. It reads [com.shieldrj.civic5mt.service.TelemetryState]
 * like every other consumer.
 *
 * Compose outside an Activity needs three owners wired onto the view by hand: a lifecycle, a
 * saved-state registry and a ViewModel store. Without them the ComposeView throws the moment
 * it tries to compose, and the message it throws does not mention any of this.
 */
class OverlayHost(
    private val context: Context,
    private val content: @Composable () -> Unit,
    private val onTap: (() -> Unit)? = null,
    private val onLongPress: (() -> Unit)? = null,
    /** Dropping the bubble on the cross that appears at the bottom of the screen mid-drag. */
    private val onClose: (() -> Unit)? = null,
) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private var view: ComposeView? = null

    /**
     * Whether the saved-state registry has been restored.
     *
     * Once, for the life of the host. [SavedStateRegistryController.performRestore] refuses a
     * second call, and it refuses it by throwing.
     */
    private var restored = false

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    val isShowing: Boolean get() = view != null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (view != null) return
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
            Log.w(TAG, "Host has been destroyed; refusing to show")
            return
        }
        if (!canDrawOverlays(context)) {
            Log.w(TAG, "Overlay permission not granted; refusing to show")
            return
        }

        // Restore once ever, not once per show. performRestore requires the lifecycle to be
        // INITIALIZED and the registry to be un-restored, and throws on either count - so
        // calling it on the second show is what used to take the HUD out for the rest of the
        // drive.
        if (!restored) {
            savedStateController.performRestore(null)
            restored = true
        }
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayHost)
            setViewTreeViewModelStoreOwner(this@OverlayHost)
            setViewTreeSavedStateRegistryOwner(this@OverlayHost)
            setContent(content)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            // NOT_FOCUSABLE so the map underneath keeps receiving input - a HUD that
            // swallows taps while you are navigating is worse than no HUD. NOT_TOUCH_MODAL
            // limits this window's touches to its own bounds.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Wherever the driver last parked it. The default is only for the very first
            // show, before anyone has had an opinion about where this belongs.
            val saved = loadOverlayPosition(context)
            x = saved?.first ?: DEFAULT_X
            y = saved?.second ?: DEFAULT_Y
        }

        // Draggable, because where it should sit depends on the phone mount and on which
        // corner of the map matters right now, and that is not something to decide for
        // someone else.
        composeView.setOnTouchListener(
            DragHandler(
                windowManager,
                params,
                onMoved = { x, y -> saveOverlayPosition(context, x, y) },
                onTap = onTap,
                onLongPress = onLongPress,
                onClose = onClose,
                dismiss = DismissTarget(context, windowManager),
            )
        )

        runCatching { windowManager.addView(composeView, params) }
            .onFailure { Log.e(TAG, "Could not add the overlay window", it); return }

        view = composeView
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /**
     * Takes the window down, leaving the host able to put it back up.
     *
     * Stopped, not destroyed - and that distinction is the whole of a bug worth describing,
     * because it presented as the HUD flickering rather than as anything crashing.
     *
     * Hiding used to move the lifecycle to DESTROYED and clear the ViewModel store, which
     * reads like tidying up and is really a one-way door. A destroyed [LifecycleRegistry]
     * cannot host another composition and a restored [SavedStateRegistry] refuses a second
     * restore, so the next [show] threw on its first line. That throw landed in the collector
     * watching the HUD's three conditions, killing it - so the card did not merely fail to
     * come back, it stopped following the connection at all for the life of the service.
     *
     * Which meant the HUD worked exactly once per drive. Anything that hides it - ten minutes
     * parked, a Bluetooth drop in a tunnel, toggling the switch in the app - retired it until
     * the next ignition cycle built a new service and a new host.
     *
     * So this is a stop: the window goes, the composition is disposed with it when the view
     * detaches, anything collecting against this lifecycle stops, and the host stays usable.
     * The one-way part now lives in [destroy], which the service calls when it is going away.
     */
    fun hide() {
        val current = view ?: return
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
        runCatching { windowManager.removeView(current) }
            .onFailure { Log.w(TAG, "Overlay window was already gone", it) }
        view = null
    }

    /**
     * Retires the host for good.
     *
     * The half of the old [hide] that was genuinely a teardown: the ViewModel store is cleared
     * and the lifecycle is destroyed, so anything still observing lets go. Only the service's
     * own shutdown calls this, because after it the host cannot show again.
     *
     * The INITIALIZED guard is not defensive noise - a registry that never reached CREATED
     * throws on the way down to DESTROYED, so a host built and never shown would fail here.
     */
    fun destroy() {
        hide()
        if (lifecycleRegistry.currentState != Lifecycle.State.INITIALIZED) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
        store.clear()
    }

    /**
     * Drags the window, and tells a tap from a long-press from a drag.
     *
     * The distinctions matter because the bubble does four jobs: it can be moved out of the
     * way, tapping it opens the app on the Fuel screen, long-pressing it cycles its light/dark
     * look to match whatever theme Google Maps has picked, and dragging it onto the cross that
     * appears at the bottom of the screen closes it - the pattern Android's chat bubbles use,
     * because a circle has no corner to put a close button in. A press that never travelled
     * beyond the touch slop is a tap or a long-press depending on how long it was held;
     * anything that travelled was a drag.
     */
    private class DragHandler(
        private val windowManager: WindowManager,
        private val params: WindowManager.LayoutParams,
        private val onMoved: (x: Int, y: Int) -> Unit,
        private val onTap: (() -> Unit)?,
        private val onLongPress: (() -> Unit)?,
        private val onClose: (() -> Unit)?,
        private val dismiss: DismissTarget,
    ) : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var downAt = 0L
        private var dragged = false

        override fun onTouch(v: View, event: MotionEvent): Boolean = when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                touchX = event.rawX
                touchY = event.rawY
                downAt = System.currentTimeMillis()
                dragged = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - touchX
                val dy = event.rawY - touchY
                if (!dragged && dx * dx + dy * dy > TOUCH_SLOP_PX * TOUCH_SLOP_PX) {
                    dragged = true
                    if (onClose != null) dismiss.show()
                }
                if (dragged) {
                    params.x = initialX + dx.roundToInt()
                    params.y = initialY + dy.roundToInt()
                    runCatching { windowManager.updateViewLayout(v, params) }
                    dismiss.setHot(dismiss.contains(event.rawX, event.rawY))
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                val dropped = dragged && onClose != null && dismiss.contains(event.rawX, event.rawY)
                dismiss.hide()
                when {
                    dropped -> {
                        // Put it back where it was before the drag, so the next time it
                        // appears it is not sitting on top of the cross.
                        params.x = initialX
                        params.y = initialY
                        runCatching { windowManager.updateViewLayout(v, params) }
                        onClose?.invoke()
                    }
                    dragged -> onMoved(params.x, params.y)
                    System.currentTimeMillis() - downAt >= LONG_PRESS_MS -> onLongPress?.invoke()
                    else -> onTap?.invoke()
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                dismiss.hide()
                true
            }
            else -> false
        }
    }

    /**
     * The cross at the bottom of the screen that a dragged bubble can be dropped on to close.
     *
     * A plain view in a window of its own, shown only while a drag is under way. It takes no
     * touches - the drag keeps going to the bubble - and grows when the finger is over it, so
     * it is plain that letting go will close the bubble.
     */
    private class DismissTarget(
        private val context: Context,
        private val windowManager: WindowManager,
    ) {
        private val density = context.resources.displayMetrics.density
        private var view: TextView? = null
        private var hot = false

        private val idle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xDD202124.toInt())
        }
        private val armed = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFD93025.toInt())
        }

        fun show() {
            if (view != null) return
            val size = (TARGET_DP * density).roundToInt()
            val target = TextView(context).apply {
                text = "✕"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                background = idle
            }
            val lp = WindowManager.LayoutParams(
                size,
                size,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = (TARGET_MARGIN_DP * density).roundToInt()
            }
            runCatching { windowManager.addView(target, lp) }
                .onSuccess { view = target }
                .onFailure { Log.w(TAG, "Could not show the close target", it) }
        }

        fun hide() {
            view?.let { runCatching { windowManager.removeView(it) } }
            view = null
            hot = false
        }

        /** Whether a finger at this screen position is over the target, generously. */
        fun contains(rawX: Float, rawY: Float): Boolean {
            val v = view ?: return false
            if (v.width == 0) return false
            val at = IntArray(2)
            v.getLocationOnScreen(at)
            val cx = at[0] + v.width / 2f
            val cy = at[1] + v.height / 2f
            val r = CATCH_RADIUS_DP * density
            val dx = rawX - cx
            val dy = rawY - cy
            return dx * dx + dy * dy <= r * r
        }

        fun setHot(over: Boolean) {
            if (over == hot) return
            hot = over
            val v = view ?: return
            v.background = if (over) armed else idle
            val scale = if (over) 1.25f else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        }
    }

    companion object {
        private const val TAG = "OverlayHost"

        /**
         * Whether Android will let this app draw over other apps.
         *
         * A special permission rather than a runtime one: it cannot be requested with a
         * dialog, only by sending the user to a Settings screen and checking again when they
         * come back.
         */
        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        private fun overlayWindowType(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        private const val DEFAULT_X = 24
        private const val DEFAULT_Y = 220

        /** Below this travel a press is a tap, not a drag. Roughly the framework's own slop. */
        private const val TOUCH_SLOP_PX = 12

        /** Holding still this long is a long-press, not a tap. */
        private const val LONG_PRESS_MS = 400L

        /** The close target's size, and how far above the bottom edge it sits. */
        private const val TARGET_DP = 56f
        private const val TARGET_MARGIN_DP = 72f

        /**
         * How near the target a finger has to be to close the bubble. Larger than the target,
         * because this is aimed at a phone on a dashboard mount.
         */
        private const val CATCH_RADIUS_DP = 64f
    }
}
