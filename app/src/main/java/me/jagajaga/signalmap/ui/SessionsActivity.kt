package me.jagajaga.signalmap.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.jagajaga.signalmap.R
import me.jagajaga.signalmap.data.AppDb
import me.jagajaga.signalmap.data.Exporter
import me.jagajaga.signalmap.data.SessionRow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionsActivity : AppCompatActivity() {
    private var rows: List<SessionRow> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sessions)
        val list = findViewById<ListView>(R.id.sessionList)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        lifecycleScope.launch {
            rows = AppDb.get(this@SessionsActivity).dao().sessions()
            val labels = rows.map { r ->
                val mins = (r.endMs - r.startMs) / 60000
                "${fmt.format(Date(r.startMs))}  ·  $mins min  ·  ${r.n} samples"
            }
            list.adapter = ArrayAdapter(
                this@SessionsActivity, android.R.layout.simple_list_item_1, labels
            )
        }

        list.setOnItemClickListener { _, _, pos, _ ->
            val r = rows[pos]
            AlertDialog.Builder(this)
                .setTitle(fmt.format(Date(r.startMs)))
                .setItems(arrayOf("Export CSV", "Export GeoJSON", "Delete")) { _, which ->
                    when (which) {
                        0 -> export(r.sessionId, "csv", "text/csv")
                        1 -> export(r.sessionId, "geojson", "application/geo+json")
                        2 -> delete(r.sessionId)
                    }
                }
                .show()
        }
    }

    private fun export(sessionId: Long, ext: String, mime: String) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                val samples = AppDb.get(this@SessionsActivity).dao().samplesForSession(sessionId)
                val content = if (ext == "csv") Exporter.csv(samples) else Exporter.geoJson(samples)
                val dir = File(cacheDir, "exports").apply { mkdirs() }
                File(dir, "session-$sessionId.$ext").apply { writeText(content) }
            }
            val uri = FileProvider.getUriForFile(
                this@SessionsActivity, "me.jagajaga.signalmap.files", file
            )
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType(mime)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "Export session"
                )
            )
        }
    }

    private fun delete(sessionId: Long) {
        lifecycleScope.launch {
            AppDb.get(this@SessionsActivity).dao().deleteSession(sessionId)
            recreate()
        }
    }
}
