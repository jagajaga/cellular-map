package me.jagajaga.signalmap.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.jagajaga.signalmap.data.SampleDao
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.lang.ref.WeakReference
import kotlin.math.max

/**
 * Draws a smooth value-based signal gradient. Aggregated cells (max dBm) are
 * splatted into a low-res HeatField, IDW-normalized, colorized, and the
 * resulting bitmap is drawn over the map with bilinear scaling.
 */
class HeatOverlay(
    private val dao: SampleDao,
    private val scope: CoroutineScope
) : Overlay() {
    /** Map modes: dBm gradient over all or one network generation, or worst-tech view. */
    enum class Mode(val types: List<String>?) {
        SIGNAL(null),                 // dBm, all samples
        NR(listOf("NR")),             // dBm, 5G only
        LTE(listOf("LTE")),           // dBm, 4G only
        WCDMA(listOf("WCDMA")),       // dBm, 3G only
        GSM(listOf("GSM", "OTHER")),  // dBm, 2G/EDGE only
        TECH(null),                   // worst generation per cell: green 4G/5G, yellow 3G, red 2G
        PING(null),                   // YouTube: red where blocked, else latency gradient
        SPEED(null)                   // best measured downlink per cell: log scale red..green
    }

    var simSlot: Int = 0
        set(value) { field = value; requestRender() }

    var mode: Mode = Mode.SIGNAL
        set(value) { field = value; requestRender() }

    private var mapRef = WeakReference<MapView>(null)
    private var bitmap: Bitmap? = null
    private var covN = 0.0
    private var covS = 0.0
    private var covE = 0.0
    private var covW = 0.0
    private var job: Job? = null
    private val paint = Paint().apply { isFilterBitmap = true; alpha = 150 }

    private companion object {
        const val DOWN = 8        // bitmap at 1/8 view resolution
        const val MARGIN = 1.3f   // render 30% beyond the viewport
    }

    fun attach(map: MapView) {
        mapRef = WeakReference(map)
        map.overlays.add(this)
        map.addMapListener(DelayedMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { requestRender(); return false }
            override fun onZoom(event: ZoomEvent?): Boolean { requestRender(); return false }
        }, 250))
        requestRender()
    }

    fun requestRender() {
        val map = mapRef.get() ?: return
        job?.cancel()
        job = scope.launch(Dispatchers.Default) {
            delay(50)
            renderOnce(map)
        }
    }

    private data class Viewport(
        val n: Double, val s: Double, val e: Double, val w: Double,
        val zoom: Double, val pw: Int, val ph: Int
    )

    private suspend fun renderOnce(map: MapView) {
        val v = withContext(Dispatchers.Main) {
            val bb = map.boundingBox.increaseByScale(MARGIN)
            Viewport(
                bb.latNorth, bb.latSouth, bb.lonEast, bb.lonWest,
                map.zoomLevelDouble, map.width, map.height
            )
        }
        if (v.pw == 0 || v.ph == 0) return

        val shift = Mercator.shiftForZoom(v.zoom)
        val x0 = Mercator.lonToX(v.w); val x1 = Mercator.lonToX(v.e)
        val y0 = Mercator.latToY(v.n); val y1 = Mercator.latToY(v.s) // y grows southward
        if (x1 <= x0 || y1 <= y0) return
        val filterAll = if (mode.types == null) 1 else 0
        val types = mode.types ?: listOf("-")
        val cells = dao.aggregate(simSlot, shift, x0, x1, y0, y1, filterAll, types)

        val bw = max(8, (v.pw * MARGIN / DOWN).toInt())
        val bh = max(8, (v.ph * MARGIN / DOWN).toInt())
        val field = HeatField(bw, bh)
        val cellUnits = (1L shl shift).toFloat()
        val sx = bw.toFloat() / (x1 - x0)
        val sy = bh.toFloat() / (y1 - y0)
        val radius = max(2f, cellUnits * sx * 2f)
        for (c in cells) {
            val value = when (mode) {
                Mode.TECH -> ColorMap.genNorm(c.minGen)
                Mode.PING -> {
                    val ratio = c.ytRatio ?: continue
                    if (ratio < 0.5) 0f // YouTube mostly failed here -> red
                    else c.minPing?.let { ColorMap.pingNorm(it) } ?: 0f
                }
                Mode.SPEED -> c.maxSpeed?.let { ColorMap.speedNorm(it) } ?: continue
                else -> ColorMap.norm(c.maxDbm)
            }
            val centerX = (c.cx.toLong() shl shift) + (1L shl (shift - 1))
            val centerY = (c.cy.toLong() shl shift) + (1L shl (shift - 1))
            val px = (centerX - x0) * sx
            val py = (centerY - y0) * sy
            field.splat(px, py, value, radius)
        }
        val pixels = field.colorize()
        val bmp = Bitmap.createBitmap(pixels, bw, bh, Bitmap.Config.ARGB_8888)

        withContext(Dispatchers.Main) {
            bitmap = bmp
            covN = v.n; covS = v.s; covE = v.e; covW = v.w
            map.invalidate()
        }
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val bmp = bitmap ?: return
        val proj = mapView.projection
        val tl = proj.toPixels(GeoPoint(covN, covW), null)
        val br = proj.toPixels(GeoPoint(covS, covE), null)
        canvas.drawBitmap(bmp, null, Rect(tl.x, tl.y, br.x, br.y), paint)
    }
}
