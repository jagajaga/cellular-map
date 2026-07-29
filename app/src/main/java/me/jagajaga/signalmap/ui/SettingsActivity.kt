package me.jagajaga.signalmap.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import me.jagajaga.signalmap.R
import me.jagajaga.signalmap.collect.AppPrefs

class SettingsActivity : AppCompatActivity() {
    private data class Row(val name: String, val mac: String)

    private var rows: List<Row> = emptyList()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadDevices()
            else Toast.makeText(
                this, "Bluetooth permission is required to list paired devices", Toast.LENGTH_LONG
            ).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<MaterialSwitch>(R.id.switchSlowOnly).apply {
            isChecked = AppPrefs.slowOnly(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                AppPrefs.setSlowOnly(this@SettingsActivity, checked)
            }
        }

        findViewById<MaterialSwitch>(R.id.switchAutoStop).apply {
            isChecked = AppPrefs.autoStop(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                AppPrefs.setAutoStop(this@SettingsActivity, checked)
            }
        }

        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            loadDevices()
        }
    }

    private fun loadDevices() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val bonded = try {
            adapter?.bondedDevices ?: emptySet()
        } catch (_: SecurityException) {
            emptySet()
        }
        rows = bonded.map { Row(it.name ?: it.address, it.address) }.sortedBy { it.name }
        val list = findViewById<ListView>(R.id.deviceList)
        list.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_multiple_choice,
            rows.map { "${it.name}\n${it.mac}" }
        )
        val selected = AppPrefs.devices(this)
        rows.forEachIndexed { i, row -> list.setItemChecked(i, row.mac in selected) }
        list.setOnItemClickListener { _, _, _, _ ->
            val macs = rows.filterIndexed { i, _ -> list.isItemChecked(i) }.map { it.mac }.toSet()
            AppPrefs.setDevices(this, macs)
        }
        if (rows.isEmpty()) {
            Toast.makeText(this, "No paired Bluetooth devices found", Toast.LENGTH_LONG).show()
        }
    }
}
