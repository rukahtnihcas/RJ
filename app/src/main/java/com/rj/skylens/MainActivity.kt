package com.rj.skylens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.hardware.GeomagneticField
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rj.skylens.astro.*
import com.rj.skylens.data.*
import com.rj.skylens.sensor.OrientationProvider
import com.rj.skylens.ui.Selection
import com.rj.skylens.ui.SkyView
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var preview: PreviewView
    private lateinit var sky: SkyView
    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var toolbar: LinearLayout
    private lateinit var orientation: OrientationProvider

    private val satRepo by lazy { SatelliteRepository(applicationContext) }
    private val flightRepo = FlightRepository()
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var locationManager: LocationManager? = null
    private var satLoadJob: Job? = null
    private val chips = HashMap<String, TextView>()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onPermissionsChanged()
    }

    // Explicit object (not a lambda): Android 8/9 call the provider/status callbacks, which were
    // abstract before API 29.
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = applyLocation(location, save = true)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("skylens", Context.MODE_PRIVATE)

        preview = findViewById(R.id.preview)
        sky = findViewById(R.id.sky)
        status = findViewById(R.id.status)
        info = findViewById(R.id.info)
        toolbar = findViewById(R.id.toolbar)

        preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE // lets the night-mode filter tint it
        preview.scaleType = PreviewView.ScaleType.FILL_CENTER

        orientation = OrientationProvider(this)
        sky.orientation = orientation
        sky.flights = flightRepo
        sky.azimuthOffsetDeg = prefs.getFloat("azOffset", 0f).toDouble()
        restoreLayers()
        sky.cameraMode = prefs.getBoolean("camera", true)
        preview.visibility = if (sky.cameraMode) View.VISIBLE else View.INVISIBLE
        sky.onZoomChanged = { z -> applyCameraZoom(z) }
        sky.onSelectionChanged = { sel ->
            refreshInfo()
            if (sel is Selection.SatSel) updateSelectedTrack()
        }
        info.setOnClickListener { sky.selection = null; refreshInfo() }

        // last known position from a previous session, so the sky appears immediately
        if (prefs.contains("lat")) {
            sky.observer = Observer(prefs.getFloat("lat", 0f).toDouble(), prefs.getFloat("lon", 0f).toDouble(), prefs.getFloat("alt", 0f).toDouble())
            updateDeclination()
        }

        buildToolbar()
        if (!orientation.available) toast("No orientation sensors found – the sky view cannot follow the phone")

        lifecycleScope.launch {
            val cat = withContext(Dispatchers.IO) { Catalog.load(applicationContext) }
            sky.catalog = cat
        }
        reloadSatellites(force = false)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { uiLoop() }
                launch { satelliteLoop() }
                launch { flightLoop() }
            }
        }

        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (needed.any { !granted(it) }) permissionLauncher.launch(needed) else onPermissionsChanged()
    }

    override fun onResume() {
        super.onResume()
        orientation.start()
        startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        orientation.stop()
        locationManager?.removeUpdates(locationListener)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun onPermissionsChanged() {
        if (granted(Manifest.permission.CAMERA)) {
            if (sky.cameraMode) startCamera()
        } else if (sky.cameraMode) {
            setCameraMode(false)
            toast("Camera permission denied – showing sky view without camera")
        }
        startLocationUpdates()
        if (!hasLocationPermission() && sky.observer == null) {
            status.text = "Location permission is needed to compute the sky for where you are."
        }
    }

    private fun hasLocationPermission() =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    // ---------------------------------------------------------------------------------------- camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
            val previewUseCase = Preview.Builder().setResolutionSelector(selector).build()
            previewUseCase.setSurfaceProvider(preview.surfaceProvider)
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase)
                preview.post { computeCameraFocal() }
                applyCameraZoom(sky.zoom)
            } catch (e: Exception) {
                toast("Could not open camera: ${e.message}")
                setCameraMode(false)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        camera = null
    }

    /**
     * Converts the lens focal length to pixels on screen so the overlay lines up with the picture.
     * The 4:3 preview is scaled to fill the view (FILL_CENTER), with the sensor's long side vertical.
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun computeCameraFocal() {
        val cam = camera ?: return
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val focalMm = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val sensor = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (focalMm == null || sensor == null || preview.width == 0) {
            sky.cameraFocalPx = null
            return
        }
        val longMm = max(sensor.width, sensor.height).toDouble()
        val shortMm = min(sensor.width, sensor.height).toDouble()
        val effLong = min(longMm, shortMm * 4.0 / 3.0)
        val effShort = effLong * 3.0 / 4.0
        val viewLong = max(preview.width, preview.height).toDouble()
        val viewShort = min(preview.width, preview.height).toDouble()
        val pxPerMm = max(viewLong / effLong, viewShort / effShort)
        sky.cameraFocalPx = focalMm * pxPerMm
    }

    private fun applyCameraZoom(z: Double) {
        val cam = camera ?: return
        if (!sky.cameraMode) return
        val maxRatio = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
        val ratio = z.toFloat().coerceIn(1f, maxRatio)
        if (abs(ratio - z) > 0.01) sky.setZoom(ratio.toDouble())
        cam.cameraControl.setZoomRatio(ratio)
    }

    private fun setCameraMode(on: Boolean) {
        sky.cameraMode = on
        prefs.edit().putBoolean("camera", on).apply()
        chips["Camera"]?.let { styleChip(it, on) }
        preview.visibility = if (on) View.VISIBLE else View.INVISIBLE
        if (on) {
            if (granted(Manifest.permission.CAMERA)) startCamera() else permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        } else stopCamera()
        sky.setZoom(1.0)
    }

    // -------------------------------------------------------------------------------------- location

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        val lm = locationManager ?: (getSystemService(Context.LOCATION_SERVICE) as LocationManager).also { locationManager = it }
        val providers = lm.getProviders(true)
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }?.let { applyLocation(it, save = true) }
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (p in providers) runCatching { lm.requestLocationUpdates(p, 10_000L, 25f, locationListener, mainLooper) }
        }
        if (providers.isEmpty() && sky.observer == null) status.text = "Turn on Location services to see the sky for your position."
    }

    private fun applyLocation(loc: Location, save: Boolean) {
        val old = sky.observer
        sky.observer = Observer(loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else 0.0)
        updateDeclination()
        if (save) prefs.edit().putFloat("lat", loc.latitude.toFloat()).putFloat("lon", loc.longitude.toFloat())
            .putFloat("alt", (if (loc.hasAltitude()) loc.altitude else 0.0).toFloat()).apply()
        // fetch planes right away for a new place
        if (old == null || abs(old.latDeg - loc.latitude) + abs(old.lonDeg - loc.longitude) > 0.2) flightKick.trySend(Unit)
    }

    private fun updateDeclination() {
        val o = sky.observer ?: return
        val field = GeomagneticField(o.latDeg.toFloat(), o.lonDeg.toFloat(), o.heightM.toFloat(), System.currentTimeMillis())
        sky.declinationDeg = field.declination.toDouble()
    }

    // ---------------------------------------------------------------------------------- data loops

    private val flightKick = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    private suspend fun uiLoop() {
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        var tick = 0
        while (true) {
            val o = sky.observer
            val sb = StringBuilder()
            if (o == null) sb.append("Waiting for location…")
            else {
                val p = sky.pointing()
                sb.append(String.format(Locale.US, "%.2f°%s %.2f°%s · %s",
                    abs(o.latDeg), if (o.latDeg >= 0) "N" else "S", abs(o.lonDeg), if (o.lonDeg >= 0) "E" else "W",
                    timeFmt.format(Date())))
                sb.append(String.format(Locale.US, "\nPointing: Az %.0f° %s · Alt %+.0f°", p.az, SkyView.compass(p.az), p.alt))
                if (sky.zoom > 1.01) sb.append(String.format(Locale.US, " · %.1f×", sky.zoom))
                val parts = ArrayList<String>()
                if (sky.layers.satellites) {
                    val up = sky.satPositions.count { it.look.enu.z > 0 }
                    parts += if (satRepo.satellites.isEmpty()) (satRepo.lastError?.let { "satellites: offline" } ?: "loading satellites…")
                    else "$up satellites up"
                }
                if (sky.layers.planes) {
                    val n = o.let { obs -> flightRepo.aircraft.count { it.enuKm(obs, System.currentTimeMillis()).z > 0 } }
                    parts += if (flightRepo.lastError != null && flightRepo.aircraft.isEmpty()) "flights: offline"
                    else "$n planes" + if (flightRepo.source.isNotEmpty()) " (${flightRepo.source})" else ""
                }
                if (parts.isNotEmpty()) sb.append('\n').append(parts.joinToString(" · "))
            }
            if (orientation.magneticAccuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW)
                sb.append("\n⚠ Compass inaccurate – move the phone in a figure-8")
            status.text = sb
            if (tick++ % 2 == 0) refreshInfo()
            delay(500)
        }
    }

    private fun refreshInfo() {
        val text = sky.describeSelection()
        info.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null) info.text = text
    }

    private suspend fun satelliteLoop() {
        var lastTrack = 0L
        while (true) {
            val o = sky.observer
            if (o != null && sky.layers.satellites && satRepo.satellites.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val list = withContext(Dispatchers.Default) {
                    satRepo.compute(SkyFrame(Time.julianDate(now), o))
                }
                sky.satPositions = list
                sky.satComputedAtMs = now
                if (now - lastTrack > 5000) { updateSelectedTrack(); lastTrack = now }
            }
            delay(1000)
        }
    }

    private fun updateSelectedTrack() {
        val sel = sky.selection as? Selection.SatSel ?: return
        val o = sky.observer ?: return
        lifecycleScope.launch {
            val track = withContext(Dispatchers.Default) {
                satRepo.track(sel.id, SkyFrame(Time.julianDate(System.currentTimeMillis()), o))
            }
            if (sky.selection == sel) sky.selectedTrack = track
        }
    }

    private suspend fun flightLoop() {
        while (true) {
            val o = sky.observer
            if (o != null && sky.layers.planes) {
                withContext(Dispatchers.IO) { flightRepo.refresh(o) }
            }
            // community feeds ask for gentle polling; dead-reckoning fills in between updates
            withTimeoutOrNull(if (flightRepo.lastError == null) 8_000L else 30_000L) { flightKick.receive() }
        }
    }

    private fun reloadSatellites(force: Boolean) {
        satLoadJob?.cancel()
        val groups = satGroups()
        satLoadJob = lifecycleScope.launch {
            withContext(Dispatchers.IO) { satRepo.load(groups, force) }
            satRepo.lastError?.let { toast("Satellite data: $it") }
        }
    }

    private fun satGroups(): Set<SatGroup> {
        val saved = prefs.getStringSet("satGroups", null) ?: setOf(SatGroup.STATIONS.key, SatGroup.VISUAL.key, SatGroup.SCIENCE.key)
        return SatGroup.values().filter { it.key in saved }.toSet()
    }

    // ------------------------------------------------------------------------------------- toolbar

    private fun buildToolbar() {
        val l = sky.layers
        addToggle("Stars", l.stars) { l.stars = it }
        addToggle("Constellations", l.constellations) { l.constellations = it }
        addToggle("Planets", l.planets) { l.planets = it }
        addToggle("Deep sky", l.deepSky) { l.deepSky = it }
        addToggle("Satellites", l.satellites) { l.satellites = it }
        addToggle("Planes", l.planes) { l.planes = it; if (it) flightKick.trySend(Unit) }
        addToggle("Grid", l.grid) { l.grid = it }
        addAction("🔍 Find") { showFind() }
        addAction("🎯 Align") { align() }
        val cam = addAction("Camera") { setCameraMode(!sky.cameraMode) }
        chips["Camera"] = cam
        styleChip(cam, sky.cameraMode)
        val night = addAction("Night") { setNightMode(!prefs.getBoolean("night", false)) }
        chips["Night"] = night
        setNightMode(prefs.getBoolean("night", false))
        addAction("⋮ More") { showMore(it) }
    }

    private fun chip(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        val p = dp(12)
        setPadding(p, dp(8), p, dp(8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(6)
        }
        toolbar.addView(this)
    }

    private fun styleChip(v: TextView, on: Boolean) {
        v.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (on) 0xCC2E6BD9.toInt() else 0x99202633.toInt())
            setStroke(dp(1), if (on) 0xFF6FA0FF.toInt() else 0x66FFFFFF)
        }
        v.alpha = if (on) 1f else 0.75f
    }

    private fun addToggle(name: String, initial: Boolean, onChange: (Boolean) -> Unit) {
        val v = chip(name)
        var state = initial
        styleChip(v, state)
        v.setOnClickListener {
            state = !state
            onChange(state)
            styleChip(v, state)
            prefs.edit().putBoolean("layer_$name", state).apply()
        }
        chips[name] = v
    }

    private fun addAction(name: String, onClick: (View) -> Unit): TextView {
        val v = chip(name)
        styleChip(v, false)
        v.setOnClickListener(onClick)
        return v
    }

    private fun restoreLayers() {
        val l = sky.layers
        l.stars = prefs.getBoolean("layer_Stars", true)
        l.constellations = prefs.getBoolean("layer_Constellations", true)
        l.planets = prefs.getBoolean("layer_Planets", true)
        l.deepSky = prefs.getBoolean("layer_Deep sky", true)
        l.satellites = prefs.getBoolean("layer_Satellites", true)
        l.planes = prefs.getBoolean("layer_Planes", true)
        l.grid = prefs.getBoolean("layer_Grid", false)
    }

    private fun setNightMode(on: Boolean) {
        prefs.edit().putBoolean("night", on).apply()
        chips["Night"]?.let { styleChip(it, on) }
        val root = findViewById<View>(R.id.root)
        if (on) {
            val m = ColorMatrix(floatArrayOf(
                0.4f, 0.5f, 0.1f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            root.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
        } else root.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    private fun showFind() {
        val items = sky.searchables()
        if (items.isEmpty()) { toast("Still loading…"); return }
        val all = items.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList(all))
        val search = EditText(this).apply { hint = "Search planets, stars, satellites, flights…"; isSingleLine = true }
        val list = ListView(this).apply { this.adapter = adapter }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(search)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420)))
        }
        val dialog = AlertDialog.Builder(this).setTitle("Find in the sky").setView(box)
            .setNegativeButton("Close", null).create()
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s.toString().trim()
                adapter.clear()
                adapter.addAll(if (q.isEmpty()) all else all.filter { it.contains(q, ignoreCase = true) })
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            val label = adapter.getItem(pos) ?: return@setOnItemClickListener
            val sel = items.first { it.first == label }.second
            sky.selection = sel
            if (sel is Selection.SatSel) updateSelectedTrack()
            refreshInfo()
            dialog.dismiss()
            toast("Follow the yellow arrow to ${sky.selectionName(sel)}")
        }
        dialog.show()
    }

    private fun align() {
        val corr = sky.alignmentCorrection()
        if (corr == null) {
            AlertDialog.Builder(this).setTitle("Align compass")
                .setMessage("Phone compasses are often a few degrees off.\n\n1. Tap a bright object you can see (the Moon, a planet or a bright star) to select it.\n2. Point the phone so the real object sits in the centre circle.\n3. Tap Align again.")
                .setPositiveButton("OK", null).show()
            return
        }
        if (abs(corr) > 45) {
            toast("That object is ${"%.0f".format(abs(corr))}° away – centre it in the circle first")
            return
        }
        sky.azimuthOffsetDeg += corr
        prefs.edit().putFloat("azOffset", sky.azimuthOffsetDeg.toFloat()).apply()
        toast(String.format(Locale.US, "Aligned on %s (%+.1f°)", sky.selectionName(sky.selection!!), corr))
    }

    private fun showMore(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Satellite groups…")
        menu.menu.add(0, 2, 1, "Refresh satellite data")
        menu.menu.add(0, 3, 2, "Reset compass alignment")
        menu.menu.add(0, 4, 3, "About & data sources")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> chooseSatGroups()
                2 -> { reloadSatellites(force = true); toast("Downloading latest orbits…") }
                3 -> { sky.azimuthOffsetDeg = 0.0; prefs.edit().putFloat("azOffset", 0f).apply(); toast("Alignment reset") }
                4 -> AlertDialog.Builder(this).setTitle("SkyLens").setMessage(
                    "Point your phone at the sky to identify stars, constellations, planets, the Moon, galaxies, nebulae, satellites and aircraft.\n\n" +
                        "• Tap anything for details; tap the info card to clear.\n" +
                        "• Pinch to zoom, double-tap to reset.\n" +
                        "• Find: search and follow the arrow.\n" +
                        "• Align: fix compass error using a known object.\n\n" +
                        "Data: HYG star database, Stellarium constellation lines, OpenNGC deep-sky catalogue, " +
                        "satellite orbits from CelesTrak (SGP4), live aircraft from the adsb.lol / airplanes.live ADS-B networks."
                ).setPositiveButton("OK", null).show()
            }
            true
        }
        menu.show()
    }

    private fun chooseSatGroups() {
        val groups = SatGroup.values()
        val current = satGroups()
        val checked = BooleanArray(groups.size) { groups[it] in current }
        AlertDialog.Builder(this).setTitle("Satellites to track")
            .setMultiChoiceItems(groups.map<SatGroup, CharSequence> { g -> if (g == SatGroup.STARLINK) "${g.label} (thousands – busy!)" else g.label }.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Apply") { _, _ ->
                val keys = groups.filterIndexed { i, _ -> checked[i] }.map { it.key }.toSet()
                prefs.edit().putStringSet("satGroups", keys).apply()
                reloadSatellites(force = false)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
