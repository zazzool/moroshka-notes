package app.moroshka.notes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The list below the graph remains the accessible, untruncated representation. */
class KnowledgeGraph(
    context: Context,
    notes: List<Note>,
    private val open: (Note) -> Unit,
    private val explain: (Note, Note) -> Unit,
    private val centerFirst: Boolean = false
) : View(context) {
    private val design = Design(context)
    private val nodes = notes.take(16)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val positions = mutableListOf<Pair<Float, Float>>()
    private val edges = mutableListOf<Pair<Int, Int>>()

    init {
        val titles = nodes.map { it.title }
        nodes.forEachIndexed { from, note ->
            note.links.distinct().forEach { link ->
                titles.forEachIndexed { to, title ->
                    if (from != to && title.equals(link, true)) edges.add(from to to)
                }
            }
        }
        contentDescription = "Граф заметок. Список связей доступен ниже."
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = true
        minimumHeight = design.dp(220)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (nodes.isEmpty()) return
        val cx = width / 2f
        val cy = height / 2f
        val radiusX = (width / 2f - design.dp(52)).coerceAtLeast(0f)
        val radiusY = (height / 2f - design.dp(40)).coerceAtLeast(0f)
        positions.clear()
        nodes.indices.forEach { index ->
            if (nodes.size == 1 || (centerFirst && index == 0)) positions.add(cx to cy)
            else {
                val offset = if (centerFirst) 1 else 0
                val angle = 2 * Math.PI * (index - offset) / (nodes.size - offset) - Math.PI / 2
                positions.add((cx + radiusX * cos(angle)).toFloat() to (cy + radiusY * sin(angle)).toFloat())
            }
        }
        paint.color = Color.parseColor("#E3BDF5")
        paint.strokeWidth = design.dp(2).toFloat()
        paint.alpha = 165
        edges.forEach { (from, to) ->
            val a = positions[from]
            val b = positions[to]
            canvas.drawLine(a.first, a.second, b.first, b.second, paint)
        }
        paint.alpha = 255
        positions.forEachIndexed { index, point ->
            val highlighted = centerFirst && index == 0
            val radius = design.dp(if (highlighted) 20 else 17).toFloat()
            val colors = if (highlighted) intArrayOf(0xFFFFC180.toInt(), 0xFFB84F3B.toInt())
                else intArrayOf(0xFFC9A1EC.toInt(), 0xFF79518C.toInt())
            paint.shader = RadialGradient(point.first - radius / 4, point.second - radius / 4,
                radius * 1.4f, colors, null, Shader.TileMode.CLAMP)
            canvas.drawCircle(point.first, point.second, radius, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = design.dp(1).toFloat()
            paint.color = 0xFFEED0FF.toInt()
            canvas.drawCircle(point.first, point.second, radius, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.typeface = design.medium
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = design.dp(13).toFloat()
            val name = android.text.TextUtils.ellipsize(nodes[index].title, android.text.TextPaint(paint), design.dp(90).toFloat(), android.text.TextUtils.TruncateAt.END)
            paint.setShadowLayer(design.dp(2).toFloat(), 0f, 1f, 0xFF211B29.toInt())
            canvas.drawText(name.toString(), point.first, point.second + design.dp(32), paint)
            paint.clearShadowLayer()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        performClick()
        val hit = positions.indexOfFirst { (x, y) ->
            val dx = event.x - x
            val dy = event.y - y
            sqrt(dx * dx + dy * dy) <= design.dp(24)
        }
        if (hit >= 0) {
            open(nodes[hit])
            return true
        }
        val edge = edges.firstOrNull { (a, b) ->
            val p = positions[a]
            val q = positions[b]
            val dx = q.first - p.first
            val dy = q.second - p.second
            val length = dx * dx + dy * dy
            if (length == 0f) false else {
                val t = (((event.x - p.first) * dx + (event.y - p.second) * dy) / length).coerceIn(0f, 1f)
                val x = p.first + t * dx
                val y = p.second + t * dy
                sqrt((event.x - x) * (event.x - x) + (event.y - y) * (event.y - y)) < design.dp(12)
            }
        }
        if (edge != null) explain(nodes[edge.first], nodes[edge.second])
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
