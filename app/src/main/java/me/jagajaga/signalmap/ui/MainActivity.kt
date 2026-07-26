package me.jagajaga.signalmap.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.jagajaga.signalmap.R
import me.jagajaga.signalmap.collect.RecordingService
import me.jagajaga.signalmap.data.AppDb
import me.jagajaga.signalmap.data.SpotInfo
import me.jagajaga.signalmap.render.HeatOverlay
import me.jagajaga.signalmap.render.Mercator
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MainActivity : AppCompatActivity() {
    private lateinit var map: MapView
    private lateinit var heat: HeatOverlay
    private lateinit var myLocation: MyLocationNewOverlay
    private lateinit var fabRecord: FloatingActionButton
    private var pendingRecord = false

    private val requiredPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val ok = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true &&
                grants[Manifest.permission.READ_PHONE_STATE] == true
            if (ok && pendingRecord) {
                startRecording()
            } else if (!ok) {
                Toast.makeText(
                    this, "Location and phone permissions are required to record", Toast.LENGTH_LONG
                ).show()
            }
            pendingRecord = false
        }

    private fun hasCorePermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            load(this@MainActivity, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
        }
        setContentView(R.layout.activity_main)
        applySystemBarInsets()

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(16.0)
        map.controller.setCenter(GeoPoint(52.52, 13.405))

        heat = HeatOverlay(AppDb.get(this).dao(), lifecycleScope)

        // tap a spot -> per-SIM stats dialog (added first so other overlays get priority)
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                p?.let { showSpotInfo(it) }
                return true
            }
            override fun longPressHelper(p: GeoPoint?): Boolean = false
        }))
        heat.attach(map)

        myLocation = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        myLocation.enableMyLocation()
        map.overlays.add(myLocation)
        myLocation.runOnFirstFix {
            runOnUiThread {
                myLocation.myLocation?.let { map.controller.animateTo(it) }
            }
        }

        findViewById<MaterialButtonToggleGroup>(R.id.simToggle).apply {
            check(R.id.btnSim1)
            addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) heat.simSlot = if (checkedId == R.id.btnSim2) 1 else 0
            }
        }

        fabRecord = findViewById(R.id.fabRecord)
        fabRecord.setOnClickListener {
            when {
                RecordingService.running.get() -> {
                    RecordingService.stop(this)
                    updateRecordIcon()
                }
                hasCorePermissions() -> startRecording()
                else -> {
                    pendingRecord = true
                    permissionLauncher.launch(requiredPermissions)
                }
            }
        }

        findViewById<FloatingActionButton>(R.id.fabFollow).setOnClickListener {
            if (myLocation.isFollowLocationEnabled) myLocation.disableFollowLocation()
            else myLocation.enableFollowLocation()
        }

        findViewById<FloatingActionButton>(R.id.fabSessions).setOnClickListener {
            startActivity(Intent(this, SessionsActivity::class.java))
        }

        // ask for permissions right away on first launch
        if (!hasCorePermissions()) {
            pendingRecord = false
            permissionLauncher.launch(requiredPermissions)
        }

        // periodic refresh while recording
        lifecycleScope.launch {
            while (true) {
                delay(3000)
                if (RecordingService.running.get()) heat.requestRender()
                updateRecordIcon()
            }
        }
    }

    /** Keep controls out of the status/navigation bars (edge-to-edge on Android 15). */
    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.root)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            findViewById<View>(R.id.simToggle).updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = bars.top + dp(8)
            }
            findViewById<View>(R.id.fabSessions).updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = bars.top + dp(8)
            }
            findViewById<View>(R.id.legendRow).updatePadding(bottom = dp(12) + bars.bottom)
            findViewById<View>(R.id.fabRecord).updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = bars.bottom + dp(72)
            }
            findViewById<View>(R.id.fabFollow).updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = bars.bottom + dp(72)
            }
            insets
        }
    }

    private fun showSpotInfo(p: GeoPoint) {
        val shift = Mercator.shiftForZoom(map.zoomLevelDouble)
        val half = 1L shl shift // one grid cell in each direction around the tap
        val x = Mercator.lonToX(p.longitude).toLong()
        val y = Mercator.latToY(p.latitude).toLong()
        val maxC = (1L shl 30) - 1
        lifecycleScope.launch {
            val samples = AppDb.get(this@MainActivity).dao().samplesIn(
                (x - half).coerceAtLeast(0).toInt(),
                (x + half).coerceAtMost(maxC).toInt(),
                (y - half).coerceAtLeast(0).toInt(),
                (y + half).coerceAtMost(maxC).toInt()
            )
            AlertDialog.Builder(this@MainActivity)
                .setTitle("%.5f, %.5f".format(p.latitude, p.longitude))
                .setMessage(SpotInfo.summarize(samples))
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun startRecording() {
        ensureUnrestrictedBattery()
        RecordingService.start(this)
        updateRecordIcon()
    }

    /** Ask Android to exempt us from battery optimization so background recording is never killed. */
    private fun ensureUnrestrictedBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                // Some OEM builds hide this screen; recording still runs as a foreground service.
            }
        }
    }

    private fun updateRecordIcon() {
        fabRecord.setImageResource(
            if (RecordingService.running.get()) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
}
