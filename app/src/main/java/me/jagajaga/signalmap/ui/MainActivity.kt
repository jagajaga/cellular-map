package me.jagajaga.signalmap.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
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
import me.jagajaga.signalmap.collect.AppPrefs
import me.jagajaga.signalmap.collect.RecordingService
import me.jagajaga.signalmap.collect.SignalReader
import me.jagajaga.signalmap.collect.SpeedStream
import me.jagajaga.signalmap.data.AppDb
import me.jagajaga.signalmap.data.SpotInfo
import me.jagajaga.signalmap.render.HeatOverlay
import me.jagajaga.signalmap.render.Mercator
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
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
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(16.0)
        map.controller.setCenter(GeoPoint(52.52, 13.405))

        heat = HeatOverlay(AppDb.get(this).dao(), lifecycleScope)
        heat.onSpeedRange = { lo, hi ->
            if (heat.mode == HeatOverlay.Mode.SPEED) {
                findViewById<TextView>(R.id.legendLow).text = mbps(lo)
                findViewById<TextView>(R.id.legendHigh).text = mbps(hi)
            }
        }

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

        findViewById<MaterialButtonToggleGroup>(R.id.modeToggle).apply {
            check(R.id.btnModeSignal)
            addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) {
                    heat.mode = when (checkedId) {
                        R.id.btnModeNr -> HeatOverlay.Mode.NR
                        R.id.btnModeLte -> HeatOverlay.Mode.LTE
                        R.id.btnModeWcdma -> HeatOverlay.Mode.WCDMA
                        R.id.btnModeGsm -> HeatOverlay.Mode.GSM
                        R.id.btnModeTech -> HeatOverlay.Mode.TECH
                        R.id.btnModePing -> HeatOverlay.Mode.PING
                        R.id.btnModeSpeed -> HeatOverlay.Mode.SPEED
                        else -> HeatOverlay.Mode.SIGNAL
                    }
                    updateLegend(heat.mode)
                }
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

        findViewById<FloatingActionButton>(R.id.fabRadio).setOnClickListener {
            openRadioSettings()
        }

        findViewById<FloatingActionButton>(R.id.fabSpeed).setOnClickListener { fab ->
            RecordingService.speedTestEnabled = !RecordingService.speedTestEnabled
            styleSpeedFab(fab as FloatingActionButton)
            Toast.makeText(
                this,
                if (RecordingService.speedTestEnabled)
                    "Continuous speed measurement ON — uses a lot of mobile data"
                else "Speed measurement OFF",
                Toast.LENGTH_SHORT
            ).show()
        }
        styleSpeedFab(findViewById(R.id.fabSpeed))

        findViewById<FloatingActionButton>(R.id.fabBt).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // ask for permissions right away on first launch
        if (!hasCorePermissions()) {
            pendingRecord = false
            permissionLauncher.launch(requiredPermissions)
        }

        // periodic refresh while recording + live network-type labels + speed status
        lifecycleScope.launch {
            while (true) {
                updateSimLabels()
                updateSpeedStatus()
                delay(3000)
                if (RecordingService.running.get()) heat.requestRender()
                updateRecordIcon()
            }
        }
    }

    private fun mbps(kbps: Int): String =
        String.format(java.util.Locale.US, "%.1f Mbps", kbps / 1000.0)

    private fun updateSpeedStatus() {
        val label = findViewById<TextView>(R.id.speedStatus)
        if (!RecordingService.speedTestEnabled) {
            label.visibility = View.GONE
            return
        }
        label.visibility = View.VISIBLE
        val kbps = SpeedStream.currentKbps
        val mb = SpeedStream.bytesTotal / 1_000_000.0
        label.text = when {
            !RecordingService.running.get() -> "Speed: starts with recording"
            kbps != null -> String.format(
                java.util.Locale.US, "%.1f Mbps · %.0f MB used", kbps / 1000.0, mb
            )
            else -> "Speed: measuring…"
        }
    }

    /** Inverted colors while speed testing is on: black button = active. */
    private fun styleSpeedFab(fab: FloatingActionButton) {
        val on = RecordingService.speedTestEnabled
        fab.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (on) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        )
        fab.imageTintList = android.content.res.ColorStateList.valueOf(
            if (on) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        )
    }

    private fun updateLegend(mode: HeatOverlay.Mode) {
        val low = findViewById<TextView>(R.id.legendLow)
        val high = findViewById<TextView>(R.id.legendHigh)
        when (mode) {
            HeatOverlay.Mode.TECH -> { low.text = "2G"; high.text = "4G/5G" }
            HeatOverlay.Mode.PING -> { low.text = "blocked / 1s+"; high.text = "50 ms" }
            HeatOverlay.Mode.SPEED -> { low.text = "slowest here"; high.text = "fastest here" }
            else -> { low.text = "-120"; high.text = "-70 dBm" }
        }
    }

    /** Show each SIM's current radio tech on its button, e.g. "SIM 2 · EDGE". */
    @SuppressLint("MissingPermission")
    private fun updateSimLabels() {
        if (!hasCorePermissions()) return
        val subMgr = getSystemService(SubscriptionManager::class.java) ?: return
        val baseTm = getSystemService(TelephonyManager::class.java) ?: return
        val infos = subMgr.activeSubscriptionInfoList ?: return
        val btn1 = findViewById<Button>(R.id.btnSim1)
        val btn2 = findViewById<Button>(R.id.btnSim2)
        btn2.visibility = if (infos.any { it.simSlotIndex == 1 }) View.VISIBLE else View.GONE
        for (info in infos) {
            val net = SignalReader.read(baseTm.createForSubscriptionId(info.subscriptionId))?.second
            val label = "SIM ${info.simSlotIndex + 1}" + if (net != null) " · $net" else ""
            when (info.simSlotIndex) {
                0 -> btn1.text = label
                1 -> btn2.text = label
            }
        }
    }

    /**
     * Open the hidden radio testing screen (*#*#4636#*#*) where "Set preferred
     * network type" can force both SIMs to e.g. LTE-only for comparable walks.
     * Falls back to regular mobile-network settings where the screen is hidden.
     */
    private fun openRadioSettings() {
        val candidates = listOf(
            Intent(Intent.ACTION_MAIN).setClassName(
                "com.android.settings", "com.android.settings.TestingSettings"
            ),
            Intent(Intent.ACTION_MAIN).setClassName(
                "com.android.phone", "com.android.phone.settings.RadioInfo"
            ),
            Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS)
        )
        for (intent in candidates) {
            try {
                startActivity(intent)
                Toast.makeText(
                    this,
                    "Set 'Preferred network type' (e.g. LTE only) for each phone/SIM",
                    Toast.LENGTH_LONG
                ).show()
                return
            } catch (_: Exception) {
                // try next candidate
            }
        }
        Toast.makeText(this, "Radio settings screen not available on this phone", Toast.LENGTH_LONG).show()
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
            findViewById<View>(R.id.fabRadio).updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = bars.top + dp(72)
            }
            findViewById<View>(R.id.fabSpeed).updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = bars.top + dp(136)
            }
            findViewById<View>(R.id.fabBt).updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = bars.top + dp(200)
            }
            findViewById<View>(R.id.legendRow).updatePadding(bottom = dp(12) + bars.bottom)
            findViewById<View>(R.id.modeScroll).updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = bars.bottom + dp(48)
            }
            findViewById<View>(R.id.speedStatus).updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = bars.bottom + dp(100)
            }
            findViewById<View>(R.id.fabRecord).updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = bars.bottom + dp(140)
            }
            findViewById<View>(R.id.fabFollow).updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = bars.bottom + dp(140)
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
        RecordingService.start(this)?.let { failure ->
            Toast.makeText(this, "Could not start recording: $failure", Toast.LENGTH_LONG).show()
        }
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

    override fun onResume() {
        super.onResume()
        map.onResume()
        // Pick up the "slow samples only" setting when returning from Settings.
        heat.motionMax = if (AppPrefs.slowOnly(this)) AppPrefs.SLOW_MPS else -1f
    }
    override fun onPause() { super.onPause(); map.onPause() }
}
