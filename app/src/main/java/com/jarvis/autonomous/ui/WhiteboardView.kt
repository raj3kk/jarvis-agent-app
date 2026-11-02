package com.jarvis.autonomous.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.jarvis.autonomous.data.WhiteboardPoint
import com.jarvis.autonomous.data.WhiteboardStroke

// Classic Android View for finger drawing. Usable in two hosts with no
// second implementation: inside a WindowManager overlay layout (see
// JarvisWhiteboardService) and inside Compose via AndroidView (see
// WhiteboardScreen). The host owns colour selection and persistence —
// this view only draws, and reports each finished stroke through
// onStrokeFinished so the host can store it in WhiteboardStore.
//
// HONEST LIMIT: this view draws pixels for the USER to see. Nothing here
// (and nothing in the app) interprets those pixels — see WhiteboardStore
// for what the engine actually receives (text context only).
class WhiteboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        // Creamy-theme palette: terracotta primary first, then dark brown,
        // green, blue and pink. At least 4 colours, as required.
        val COLORS: List<Int> = listOf(
            0xFFB85C38.toInt(),
            0xFF3B2F2A.toInt(),
            0xFF2E7D5B.toInt(),
            0xFF1E88E5.toInt(),
            0xFFD81B60.toInt()
        )
        const val DEFAULT_STROKE_WIDTH = 8f
    }

    private val drawPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = COLORS.first()
        strokeWidth = DEFAULT_STROKE_WIDTH
    }
    private val strokes = mutableListOf<WhiteboardStroke>()
    private var currentPoints = mutableListOf<WhiteboardPoint>()
    // Backing fields are NOT named currentColor/currentWidth: a Kotlin
    // var already generates a JVM setter setCurrentColor/setCurrentWidth,
    // so a fun of the same name would be a platform declaration clash.
    private var strokeColorValue: Int = COLORS.first()
    private var strokeWidthValue: Float = DEFAULT_STROKE_WIDTH

    // Called by the host when the user lifts their finger. The host
    // (overlay service or Compose screen) stores the stroke; this view
    // never writes files itself, so both hosts observe one shared store.
    var onStrokeFinished: ((WhiteboardStroke) -> Unit)? = null

    init {
        // White drawing surface inside the creamy app theme.
        setBackgroundColor(Color.WHITE)
    }

    fun setCurrentColor(color: Int) {
        strokeColorValue = color
        drawPaint.color = color
    }

    fun setCurrentWidth(width: Float) {
        strokeWidthValue = width
        drawPaint.strokeWidth = width
    }

    fun setStrokes(newStrokes: List<WhiteboardStroke>) {
        strokes.clear()
        strokes.addAll(newStrokes)
        currentPoints = mutableListOf()
        invalidate()
    }

    fun getStrokes(): List<WhiteboardStroke> = strokes.toList()

    fun clearDrawing() {
        strokes.clear()
        currentPoints = mutableListOf()
        invalidate()
    }

    private fun pathFor(points: List<WhiteboardPoint>): Path {
        val path = Path()
        if (points.isEmpty()) return path
        path.moveTo(points.first().x, points.first().y)
        for (i in 1 until points.size) {
            path.lineTo(points[i].x, points[i].y)
        }
        return path
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (stroke in strokes) {
            drawPaint.color = stroke.color
            drawPaint.strokeWidth = stroke.width
            canvas.drawPath(pathFor(stroke.points), drawPaint)
        }
        if (currentPoints.isNotEmpty()) {
            drawPaint.color = strokeColorValue
            drawPaint.strokeWidth = strokeWidthValue
            canvas.drawPath(pathFor(currentPoints), drawPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val point = WhiteboardPoint(event.x, event.y)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                currentPoints = mutableListOf(point)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentPoints.add(point)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                currentPoints.add(point)
                if (currentPoints.size >= 2) {
                    val finished = WhiteboardStroke(
                        points = currentPoints.toList(),
                        color = strokeColorValue,
                        width = strokeWidthValue
                    )
                    strokes.add(finished)
                    onStrokeFinished?.invoke(finished)
                }
                currentPoints = mutableListOf()
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                currentPoints = mutableListOf()
                invalidate()
                return true
            }
            else -> return super.onTouchEvent(event)
        }
    }
}
