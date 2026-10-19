package io.github.nexalloy.revanced.instagram.download

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL

internal object ProfilePictureViewer {

    fun show(ctx: Context, url: String) {
        MediaSaver.executor.submit {
            val bitmap = runCatching { load(url) }.getOrNull()
            MediaSaver.ui {
                if (bitmap == null) return@ui MediaSaver.toast(ctx, I18n.t(ctx, io.github.nexalloy.R.string.ig_toast_profile_pic_url_not_found))
                runCatching { open(ctx, bitmap) }
            }
        }
    }

    private fun load(url: String): Bitmap? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        return try {
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(ctx: Context, bitmap: Bitmap) {
        val dialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        dialog.setContentView(ZoomImageView(ctx, bitmap) { dialog.dismiss() },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialog.setOnDismissListener { bitmap.recycle() }
        dialog.show()
    }

    @SuppressLint("ViewConstructor")
    private class ZoomImageView(ctx: Context, private val bitmap: Bitmap, private val onTap: () -> Unit) : ImageView(ctx) {
        private val m = Matrix()
        private var minScale = 1f

        private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val current = scaleOf()
                val factor = (current * d.scaleFactor).coerceIn(minScale, minScale * 6) / current
                m.postScale(factor, factor, d.focusX, d.focusY)
                keepInside()
                return true
            }
        })

        private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                m.postTranslate(-dx, -dy)
                keepInside()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean = true.also { onTap() }
        })

        init {
            scaleType = ScaleType.MATRIX
            setImageBitmap(bitmap)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            minScale = minOf(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
            m.reset()
            m.postScale(minScale, minScale)
            m.postTranslate((w - bitmap.width * minScale) / 2, (h - bitmap.height * minScale) / 2)
            imageMatrix = m
        }

        private fun scaleOf(): Float = FloatArray(9).also { m.getValues(it) }[Matrix.MSCALE_X]

        private fun keepInside() {
            val r = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()).also { m.mapRect(it) }
            val dx = when {
                r.width() <= width -> width / 2f - r.centerX()
                r.left > 0 -> -r.left
                r.right < width -> width - r.right
                else -> 0f
            }
            val dy = when {
                r.height() <= height -> height / 2f - r.centerY()
                r.top > 0 -> -r.top
                r.bottom < height -> height - r.bottom
                else -> 0f
            }
            m.postTranslate(dx, dy)
            imageMatrix = m
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaler.onTouchEvent(event)
            if (!scaler.isInProgress) gestures.onTouchEvent(event)
            return true
        }
    }
}
