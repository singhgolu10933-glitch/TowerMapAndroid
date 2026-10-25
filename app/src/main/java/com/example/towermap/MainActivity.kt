package com.example.towermap

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.view.ViewGroup
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        private const val PERMISSION_REQUEST = 100
        // Personal prototype only. A key embedded in an APK can be extracted.
        // Replace this with your OpenCelliD key for local testing.
        private const val OPENCELLID_API_KEY = "PASTE_YOUR_OPENCELLID_KEY_HERE"
    }

    private lateinit var statusText: TextView
    private lateinit var mapView: WebView
    private lateinit var findButton: Button
    private lateinit var progress: ProgressBar
    private val executor = Executors.newSingleThreadExecutor()

    data class Cell(
        val mcc: Int,
        val mnc: Int,
        val areaCode: Int,
        val cellId: Long,
        val radio: String,
        val signalDbm: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 10, 14, 0)
        }

        statusText = TextView(this).apply {
            text = "Ready. Grant permissions, then tap Find location."
            textSize = 15f
            setPadding(2, 8, 2, 8)
        }

        findButton = Button(this).apply {
            text = "Find location"
            setOnClickListener { findLocation() }
        }

        progress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = ProgressBar.GONE
        }

        mapView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            webViewClient = WebViewClient()
        }

        root.addView(statusText)
        root.addView(findButton)
        root.addView(progress)
        root.addView(
            mapView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        setContentView(root)

        mapView.loadDataWithBaseURL(
            "https://appassets.androidplatform.net/",
            mapHtml(),
            "text/html",
            "UTF-8",
            null
        )

        requestNeededPermissionsIfMissing()
    }

    private fun requestNeededPermissionsIfMissing() {
        val missing = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        ).filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
        }
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun findLocation() {
        if (!hasLocationPermission()) {
            statusText.text = "Location permission is required. Please allow it."
            requestNeededPermissionsIfMissing()
            return
        }

        if (OPENCELLID_API_KEY == "PASTE_YOUR_OPENCELLID_KEY_HERE") {
            statusText.text =
                "Setup needed: add your OpenCelliD API key in MainActivity.kt."
            return
        }

        setBusy(true, "Reading registered cell information…")
        executor.execute {
            try {
                val cell = readRegisteredCell()
                if (cell == null) {
                    showCachedLocation(
                        "No supported registered cell was available. "
                    )
                    return@execute
                }

                runOnUiThread {
                    statusText.text =
                        "Cell ${cell.cellId} • ${cell.radio} • ${cell.signalDbm} dBm\nLooking up tower…"
                }

                val endpoint = "https://opencellid.org/cell/get" +
                    "?key=${encode(OPENCELLID_API_KEY)}" +
                    "&mcc=${cell.mcc}" +
                    "&mnc=${cell.mnc}" +
                    "&lac=${cell.areaCode}" +
                    "&cellid=${cell.cellId}" +
                    "&radio=${cell.radio}" +
                    "&format=json"

                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 15000
                }

                val responseCode: Int
                val responseBody: String
                try {
                    responseCode = connection.responseCode
                    val stream = if (responseCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }
                    responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                } finally {
                    connection.disconnect()
                }

                if (responseCode !in 200..299) {
                    showCachedLocation(
                        "Tower lookup returned HTTP $responseCode. "
                    )
                    return@execute
                }

                val json = JSONObject(responseBody)
                if (json.has("error") || !json.has("lat") || !json.has("lon")) {
                    showCachedLocation(
                        "Tower not found in OpenCelliD. "
                    )
                    return@execute
                }

                val latitude = json.getDouble("lat")
                val longitude = json.getDouble("lon")
                val range = json.optDouble("range", Double.NaN)

                runOnUiThread {
                    val rangeText = if (range.isFinite() && range >= 0) {
                        "\nDatabase range estimate: ${range.toInt()} m (not guaranteed accuracy)"
                    } else {
                        "\nAccuracy/range not supplied by database."
                    }
                    statusText.text =
                        "Estimated cell-tower location\n" +
                        "Lat: $latitude, Lon: $longitude" +
                        rangeText +
                        "\nSignal: ${cell.signalDbm} dBm"
                    setBusy(false)
                    moveMap(latitude, longitude, 15)
                }
            } catch (e: Exception) {
                showCachedLocation(
                    "Lookup error (${e.message ?: "network error"}). "
                )
            }
        }
    }

    @Suppress("MissingPermission")
    private fun readRegisteredCell(): Cell? {
        if (!hasLocationPermission()) return null

        val telephonyManager =
            getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        val cellInfos: List<CellInfo> = try {
            telephonyManager.allCellInfo ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        val registered = cellInfos.firstOrNull { it.isRegistered } ?: return null

        return try {
            when (registered) {
                is CellInfoLte -> {
                    val identity = registered.cellIdentity
                    makeCell(
                        identity.mccString,
                        identity.mncString,
                        identity.tac,
                        identity.ci.toLong(),
                        "LTE",
                        registered.cellSignalStrength.dbm
                    )
                }
                is CellInfoGsm -> {
                    val identity = registered.cellIdentity
                    makeCell(
                        identity.mccString,
                        identity.mncString,
                        identity.lac,
                        identity.cid.toLong(),
                        "GSM",
                        registered.cellSignalStrength.dbm
                    )
                }
                is CellInfoWcdma -> {
                    val identity = registered.cellIdentity
                    makeCell(
                        identity.mccString,
                        identity.mncString,
                        identity.lac,
                        identity.cid.toLong(),
                        "UMTS",
                        registered.cellSignalStrength.dbm
                    )
                }
                // NR cell identity access differs across Android versions and
                // requires careful API-level handling; added in a later version.
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun makeCell(
        mccString: String?,
        mncString: String?,
        areaCode: Int,
        cellId: Long,
        radio: String,
        signalDbm: Int
    ): Cell? {
        val mcc = mccString?.toIntOrNull() ?: return null
        val mnc = mncString?.toIntOrNull() ?: return null
        if (areaCode < 0 || cellId < 0) return null
        return Cell(mcc, mnc, areaCode, cellId, radio, signalDbm)
    }

    @Suppress("MissingPermission")
    private fun showCachedLocation(prefix: String) {
        val cached: Location? = try {
            if (!hasLocationPermission()) null
            else {
                val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER
                ).mapNotNull { provider ->
                    try {
                        manager.getLastKnownLocation(provider)
                    } catch (_: Exception) {
                        null
                    }
                }.maxByOrNull { it.time }
            }
        } catch (_: Exception) {
            null
        }

        runOnUiThread {
            setBusy(false)
            if (cached != null) {
                statusText.text =
                    prefix + "Showing last known GPS/network location. " +
                    "It may be old and is not a cell-tower result."
                moveMap(cached.latitude, cached.longitude, 16)
            } else {
                statusText.text =
                    prefix + "No tower result or cached location available. " +
                    "Check permissions, Location setting, signal and internet."
            }
        }
    }

    private fun setBusy(busy: Boolean, message: String? = null) {
        runOnUiThread {
            if (message != null) statusText.text = message
            progress.visibility = if (busy) ProgressBar.VISIBLE else ProgressBar.GONE
            findButton.isEnabled = !busy
        }
    }

    private fun moveMap(latitude: Double, longitude: Double, zoom: Int) {
        mapView.evaluateJavascript(
            "showLocation($latitude,$longitude,$zoom);",
            null
        )
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private fun mapHtml(): String = """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width,initial-scale=1">
          <link rel="stylesheet"
            href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
          <script
            src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
          <style>
            html, body, #map { height:100%; width:100%; margin:0; }
            body { font-family: sans-serif; }
          </style>
        </head>
        <body>
          <div id="map"></div>
          <script>
            const map = L.map('map').setView([20.5937, 78.9629], 4);
            L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
              maxZoom: 19,
              attribution: '&copy; OpenStreetMap contributors'
            }).addTo(map);
            let marker = null;
            function showLocation(lat, lon, zoom) {
              map.setView([lat, lon], zoom);
              if (marker) map.removeLayer(marker);
              marker = L.marker([lat, lon]).addTo(map)
                .bindPopup('Estimated location (not guaranteed exact)')
                .openPopup();
            }
          </script>
        </body>
        </html>
    """.trimIndent()

    override fun onDestroy() {
        executor.shutdownNow()
        mapView.destroy()
        super.onDestroy()
    }
}
