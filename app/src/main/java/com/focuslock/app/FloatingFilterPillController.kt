package com.focuslock.app

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Pílula flutuante minimalista estilo Minimalist Phone ("🔕 X notificações filtradas").
 *
 * Não rouba foco do teclado nem do botão Voltar (FLAG_NOT_FOCUSABLE), pode ser arrastada
 * verticalmente pela tela e abre a caixa de notificações filtradas ao tocar.
 */
class FloatingFilterPillController(
    private val serviceContext: Context,
    private val prefsProvider: () -> BlockPreferences
) {

    private var windowManager: WindowManager? = null
    private var pillContainer: LinearLayout? = null
    private var pillText: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isAdded = false

    fun updateVisibilityAndCount(count: Int, isOnHomeOrFocusLock: Boolean) {
        if (!Settings.canDrawOverlays(serviceContext)) {
            hide()
            return
        }

        val prefs = prefsProvider()
        val pillMode = prefs.floatingPillMode
        val shouldDisplay = prefs.isNotificationFilterEnabled &&
                count > 0 &&
                pillMode != BlockPreferences.PILL_MODE_OFF &&
                (pillMode == BlockPreferences.PILL_MODE_ALWAYS || isOnHomeOrFocusLock)

        if (!shouldDisplay) {
            hide()
            return
        }

        val label = if (count == 1) {
            "🔕 1 notificação filtrada"
        } else {
            "🔕 $count notificações filtradas"
        }

        if (isAdded && pillContainer != null && pillText != null) {
            pillText?.text = label
            pillContainer?.visibility = View.VISIBLE
            return
        }

        showAndAttach(label, prefs.floatingPillYOffsetPx, prefs.floatingPillAlignLeft)
    }

    private fun showAndAttach(label: String, initialYOffset: Int, alignLeft: Boolean) {
        try {
            val wm = serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm

            val bgDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(22).toFloat()
                setColor(Color.parseColor("#EB181B26"))
                setStroke(dpToPx(1), Color.parseColor("#3D7BFD"))
            }

            val tv = TextView(serviceContext).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
            }
            pillText = tv

            val container = LinearLayout(serviceContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = bgDrawable
                val hPad = dpToPx(16)
                val vPad = dpToPx(9)
                setPadding(hPad, vPad, hPad, vPad)
                addView(tv)
            }

            val screenH = serviceContext.resources.displayMetrics.heightPixels.coerceAtLeast(dpToPx(640))
            val maxY = (screenH - dpToPx(96)).coerceAtLeast(dpToPx(120))

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or if (alignLeft) Gravity.START else Gravity.END
                x = dpToPx(14)
                y = initialYOffset.coerceIn(dpToPx(56), maxY)
                title = "FocusLockFilteredPill"
            }
            layoutParams = params

            setupDragAndTap(container, params)

            wm.addView(container, params)
            pillContainer = container
            isAdded = true
        } catch (_: Exception) {
            isAdded = false
            pillContainer = null
            pillText = null
        }
    }

    private fun setupDragAndTap(view: View, params: WindowManager.LayoutParams) {
        var initialY = 0
        var initialTouchY = 0f
        var initialTouchX = 0f
        var moved = false
        val slopPx = dpToPx(8)

        view.setOnTouchListener { _, event ->
            val metrics = serviceContext.resources.displayMetrics
            val screenW = metrics.widthPixels.coerceAtLeast(dpToPx(320))
            val screenH = metrics.heightPixels.coerceAtLeast(dpToPx(640))
            val maxY = (screenH - dpToPx(96)).coerceAtLeast(dpToPx(120))

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = params.y
                    initialTouchY = event.rawY
                    initialTouchX = event.rawX
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - initialTouchY).toInt()
                    val dx = (event.rawX - initialTouchX).toInt()
                    if (abs(dy) > slopPx || abs(dx) > slopPx) {
                        moved = true
                    }
                    if (moved) {
                        params.y = (initialY + dy).coerceIn(dpToPx(48), maxY)
                        val isLeftHalf = event.rawX < (screenW / 2f)
                        params.gravity = Gravity.TOP or if (isLeftHalf) Gravity.START else Gravity.END
                        params.x = dpToPx(14)
                        try {
                            windowManager?.updateViewLayout(view, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        val prefs = prefsProvider()
                        prefs.floatingPillYOffsetPx = params.y
                        prefs.floatingPillAlignLeft = event.rawX < (screenW / 2f)
                    } else {
                        openFilteredInbox()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun openFilteredInbox() {
        val intent = Intent(serviceContext, FilteredNotificationsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        try {
            serviceContext.startActivity(intent)
        } catch (_: Exception) {
            try {
                val pi = PendingIntent.getActivity(
                    serviceContext,
                    2105,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val options = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        )
                    pi.send(serviceContext, 0, null, null, null, null, options.toBundle())
                } else {
                    pi.send()
                }
            } catch (_: Exception) {
            }
        }
    }

    fun hide() {
        val container = pillContainer
        val wm = windowManager
        isAdded = false
        pillContainer = null
        pillText = null
        layoutParams = null
        if (container != null && wm != null) {
            try {
                wm.removeViewImmediate(container)
            } catch (_: Exception) {
                try {
                    wm.removeView(container)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            serviceContext.resources.displayMetrics
        ).toInt()
    }
}
