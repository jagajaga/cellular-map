package me.jagajaga.signalmap.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.jagajaga.signalmap.R
import me.jagajaga.signalmap.collect.RecordingService
import me.jagajaga.signalmap.data.AppDb
import me.jagajaga.signalmap.render.HeatOverlay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MainActivity : AppCompatActivity() {
    private lateinit var map: MapView
    private lateinit var heat: HeatOverlay
    private lateinit var myLocation: MyLocationNewOverlay
    private lateinit var fabRecord: FloatingActionButton

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true &&
                grants[Manifest.permission.READ_PHONE_STATE] == true
            ) {
                startRecording()
            } else {
                Toast.makeText(
                    this, "Location and phone permissions are required to record", Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            load(this@MainActivity, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
        }
        setContentView(R.layout.activity_main)

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(16.0)
        map.controller.setCenter(GeoPoint(52.52, 13.405))

        heat = HeatOverlay(AppDb.get(this).dao(), lifecycleScope)
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
            if (RecordingService.running.get()) {
                RecordingService.stop(this)
                updateRecordIcon()
            } else {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.READ_PHONE_STATE,
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                )
            }
        }

        findViewById<FloatingActionButton>(R.id.fabFollow).setOnClickListener {
            if (myLocation.isFollowLocationEnabled) myLocation.disableFollowLocation()
            else myLocation.enableFollowLocation()
        }

        findViewById<FloatingActionButton>(R.id.fabSessions).setOnClickListener {
            startActivity(Intent(this, SessionsActivity::class.java))
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

    private fun startRecording() {
        RecordingService.start(this)
        updateRecordIcon()
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
