package org.terra.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

data class MapArea(val id: Long, val lat: Double, val lon: Double, val radiusM: Double, val color: Int, val visited: Boolean)
data class MapPin(val key: String, val lat: Double, val lon: Double, val glyph: String, val ring: Int, val obscured: Boolean = false)

data class MapModel(
    val areas: List<MapArea> = emptyList(),
    val pins: List<MapPin> = emptyList(),
    val me: Triple<Double, Double, Float?>? = null,
    val paper: Int = 0xFFFBF6E9.toInt(),
    val ink: Int = 0xFF2C2A25.toInt()
)

private class GlyphIcons(private val context: Context) {
    private val cache = HashMap<String, BitmapDrawable>()
    fun get(glyph: String, ring: Int, paper: Int, dashed: Boolean): BitmapDrawable = cache.getOrPut("$glyph$ring$paper$dashed") {
        val d = context.resources.displayMetrics.density
        val size = (34 * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = paper
        c.drawCircle(size / 2f, size / 2f, size / 2f - d, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * d
        p.color = ring
        if (dashed) p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(4 * d, 3 * d), 0f)
        c.drawCircle(size / 2f, size / 2f, size / 2f - 2 * d, p)
        p.pathEffect = null
        p.style = Paint.Style.FILL
        p.textSize = size * 0.5f
        p.textAlign = Paint.Align.CENTER
        p.color = ring
        c.drawText(glyph, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
        BitmapDrawable(context.resources, bmp)
    }

    fun me(ink: Int, paper: Int): BitmapDrawable = cache.getOrPut("me$ink") {
        val d = context.resources.displayMetrics.density
        val size = (22 * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = paper
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.color = ink
        c.drawCircle(size / 2f, size / 2f, size / 2f - 3 * d, p)
        BitmapDrawable(context.resources, bmp)
    }
}

/** OpenStreetMap, washed toward sepia so it sits on the journal page. */
@Composable
fun OsmMap(
    model: MapModel, start: Pair<Double, Double>, recenter: Int, modifier: Modifier = Modifier,
    onArea: (Long) -> Unit, onPin: (String) -> Unit, onCenter: (Double, Double) -> Unit
) {
    val context = LocalContext.current
    val icons = remember { GlyphIcons(context) }
    val map = remember {
        Configuration.getInstance().apply {
            load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = context.packageName
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 4.0
            controller.setZoom(16.5)
            controller.setCenter(GeoPoint(start.first, start.second))
            val m = ColorMatrix().apply { setSaturation(0.25f) }
            m.postConcat(ColorMatrix(floatArrayOf(1.0f, 0f, 0f, 0f, 0f, 0f, 0.96f, 0f, 0f, 0f, 0f, 0f, 0.84f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
            overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(m))
            addMapListener(object : MapListener {
                override fun onScroll(e: ScrollEvent?): Boolean { mapCenter.let { onCenter(it.latitude, it.longitude) }; return false }
                override fun onZoom(e: ZoomEvent?) = false
            })
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) map.onResume() else if (e == Lifecycle.Event.ON_PAUSE) map.onPause()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); map.onDetach() }
    }
    AndroidView(factory = { map }, modifier = modifier, update = { view ->
        val state = view.tag as? Pair<*, *>
        if (state?.first != model) {
            view.overlays.clear()
            for (a in model.areas) view.overlays.add(Polygon(view).apply {
                points = Polygon.pointsAsCircle(GeoPoint(a.lat, a.lon), a.radiusM)
                fillPaint.color = (a.color and 0x00FFFFFF) or (if (a.visited) 0x3A000000 else 0x14000000)
                outlinePaint.color = a.color
                outlinePaint.strokeWidth = 3f
                if (!a.visited) outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
                infoWindow = null
                setOnClickListener { _, _, _ -> onArea(a.id); true }
            })
            for (p in model.pins) view.overlays.add(Marker(view).apply {
                position = GeoPoint(p.lat, p.lon)
                icon = icons.get(p.glyph, p.ring, model.paper, p.obscured)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                infoWindow = null
                setOnMarkerClickListener { _, _ -> onPin(p.key); true }
            })
            model.me?.let { (lat, lon, _) ->
                view.overlays.add(Marker(view).apply {
                    position = GeoPoint(lat, lon)
                    icon = icons.me(model.ink, model.paper)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    infoWindow = null
                    setOnMarkerClickListener { _, _ -> true }
                })
            }
            view.invalidate()
        }
        // Follow the walker on the first fix and whenever "recenter" is pressed.
        val me = model.me
        if (me != null && (state == null || (state.first as? MapModel)?.me == null || state.second != recenter)) {
            view.controller.animateTo(GeoPoint(me.first, me.second))
        }
        view.tag = model to recenter
    })
}
