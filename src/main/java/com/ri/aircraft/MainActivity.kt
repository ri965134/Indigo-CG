package com.ri.aircraft

import android.annotation.SuppressLint
import android.app.Dialog
import android.app.TimePickerDialog // NEW IMPORT
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar // NEW IMPORT

// --- 1. DATA CLASS ---
data class FlightRecord(
    val flightId: String, val departure: String, val arrival: String, val tat: String,
    val emptyWeight: Double, val emptyArm: Double, val fwdCargo: Double, val aftCargo: Double,
    val totalWeight: Double, val cg: Double, val status: String,
    val rotationY: Double
)

class MainActivity : AppCompatActivity() {

    private lateinit var webView3D: WebView
    private lateinit var etFlightId: TextInputEditText
    private lateinit var etDeparture: TextInputEditText
    private lateinit var etArrival: TextInputEditText
    private lateinit var etEmptyWeight: TextInputEditText
    private lateinit var etEmptyArm: TextInputEditText
    private lateinit var etFwdCargo: TextInputEditText
    private lateinit var etAftCargo: TextInputEditText
    private lateinit var tvTotalWeight: TextView
    private lateinit var tvCg: TextView
    private lateinit var tvStatus: TextView
    private lateinit var recyclerView: RecyclerView

    private val savedFlights = mutableListOf<FlightRecord>()
    private lateinit var adapter: FlightAdapter

    private var lastKnownRotationY: Double = 0.0

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI Elements
        etFlightId = findViewById(R.id.etFlightId)
        etDeparture = findViewById(R.id.etDeparture)
        etArrival = findViewById(R.id.etArrival)
        etEmptyWeight = findViewById(R.id.etEmptyWeight)
        etEmptyArm = findViewById(R.id.etEmptyArm)
        etFwdCargo = findViewById(R.id.etFwdCargo)
        etAftCargo = findViewById(R.id.etAftCargo)
        tvTotalWeight = findViewById(R.id.tvTotalWeight)
        tvCg = findViewById(R.id.tvCg)
        tvStatus = findViewById(R.id.tvStatus)

        // --- NEW: TIME PICKER LOGIC ---
        // Make the boxes read-only so the keyboard doesn't pop up
        etDeparture.isFocusable = false
        etDeparture.isClickable = true
        etArrival.isFocusable = false
        etArrival.isClickable = true

        etDeparture.setOnClickListener { showTimePicker(etDeparture) }
        etArrival.setOnClickListener { showTimePicker(etArrival) }
        // ------------------------------

        // Main 3D WebView setup
        webView3D = findViewById(R.id.webView3D)
        webView3D.settings.javaScriptEnabled = true
        webView3D.webViewClient = WebViewClient()
        webView3D.addJavascriptInterface(WebAppInterface(), "AndroidBridge")
        webView3D.loadUrl("file:///android_asset/plane_3d.html")

        webView3D.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> view.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        loadDataFromStorage()

        adapter = FlightAdapter(savedFlights,
            onViewClicked = { record -> showViewDialog(record) },
            onEditClicked = { record -> loadForEditing(record) },
            onRemoveClicked = { record -> removeRecord(record) }
        )
        recyclerView.adapter = adapter

        findViewById<MaterialButton>(R.id.btnCalculate).setOnClickListener {
            val newRecord = performStandardCalculation(update3D = true)

            if (newRecord != null) {
                savedFlights.add(0, newRecord)
                adapter.notifyItemInserted(0)
                recyclerView.scrollToPosition(0)
                saveDataToStorage()
                Toast.makeText(this, "Balance Calculated & Saved!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- NEW: TIME PICKER FUNCTION ---
    private fun showTimePicker(targetEditText: TextInputEditText) {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)

        val timePickerDialog = TimePickerDialog(this,
            { _, selectedHour, selectedMinute ->
                // Format the time to always show two digits (e.g., 09:05)
                val formattedTime = String.format("%02d:%02d", selectedHour, selectedMinute)
                targetEditText.setText(formattedTime)
            }, hour, minute, true) // true = 24 hour format

        timePickerDialog.show()
    }
    // ---------------------------------

    private fun saveDataToStorage() {
        val sharedPreferences = getSharedPreferences("FlightPrefs", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        val gson = Gson()
        val json = gson.toJson(savedFlights)
        editor.putString("saved_flights", json)
        editor.apply()
    }

    private fun loadDataFromStorage() {
        val sharedPreferences = getSharedPreferences("FlightPrefs", Context.MODE_PRIVATE)
        val gson = Gson()
        val json = sharedPreferences.getString("saved_flights", null)
        if (json != null) {
            val type = object : TypeToken<MutableList<FlightRecord>>() {}.type
            val loadedList: MutableList<FlightRecord> = gson.fromJson(json, type)
            savedFlights.clear()
            savedFlights.addAll(loadedList)
        }
    }

    private fun performStandardCalculation(update3D: Boolean): FlightRecord? {
        return try {
            val fId = etFlightId.text.toString().ifEmpty { "UNKNOWN" }
            val dep = etDeparture.text.toString()
            val arr = etArrival.text.toString()

            var tatStr = "N/A"
            try {
                if (arr.isNotEmpty() && dep.isNotEmpty()) {
                    val format = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    val arrTime = format.parse(arr)
                    val depTime = format.parse(dep)
                    if (arrTime != null && depTime != null) {
                        var diff = depTime.time - arrTime.time
                        if (diff < 0) diff += 24 * 60 * 60 * 1000 // Fix overnight flights
                        val mins = diff / (60 * 1000)
                        tatStr = "${mins / 60}h ${mins % 60}m"
                    }
                }
            } catch (e: Exception) {
                tatStr = "N/A"
            }

            val eWeight = etEmptyWeight.text.toString().toDouble()
            val eArm = etEmptyArm.text.toString().toDouble()
            val fCargo = etFwdCargo.text.toString().toDouble()
            val aCargo = etAftCargo.text.toString().toDouble()

            val totalWt = eWeight + fCargo + aCargo
            val cg = ((eWeight * eArm) + (fCargo * 400.0) + (aCargo * 800.0)) / totalWt

            tvTotalWeight.text = "Total Weight: ${totalWt.toInt()} kg"
            tvCg.text = String.format("Center of Gravity: %.2f", cg)

            val statusStr = if (cg in 580.0..620.0) "SAFE" else "DANGER"
            tvStatus.text = "Status: $statusStr"
            tvStatus.setTextColor(if (statusStr == "SAFE") Color.parseColor("#4CAF50") else Color.parseColor("#F44336"))

            if (update3D) {
                webView3D.evaluateJavascript("javascript:updatePlanePitch($cg, $lastKnownRotationY)", null)
            }

            FlightRecord(fId, dep, arr, tatStr, eWeight, eArm, fCargo, aCargo, totalWt, cg, statusStr, lastKnownRotationY)
        } catch (e: Exception) {
            if (update3D) {
                Toast.makeText(this, "Please enter all weights to calculate.", Toast.LENGTH_SHORT).show()
            }
            null
        }
    }

    private fun loadForEditing(record: FlightRecord) {
        etFlightId.setText(record.flightId)
        etDeparture.setText(record.departure)
        etArrival.setText(record.arrival)
        etEmptyWeight.setText(record.emptyWeight.toString())
        etEmptyArm.setText(record.emptyArm.toString())
        etFwdCargo.setText(record.fwdCargo.toString())
        etAftCargo.setText(record.aftCargo.toString())

        lastKnownRotationY = record.rotationY

        performStandardCalculation(update3D = true)
        Toast.makeText(this, "Loaded for editing. Click Calculate to save as new.", Toast.LENGTH_LONG).show()
    }

    private fun removeRecord(record: FlightRecord) {
        val index = savedFlights.indexOf(record)
        if (index != -1) {
            savedFlights.removeAt(index)
            adapter.notifyItemRemoved(index)
            saveDataToStorage()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showViewDialog(record: FlightRecord) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_view_record)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        dialog.findViewById<TextView>(R.id.tvDialogFlightId).text = "Flight: ${record.flightId}"

        val details = """
            Status: ${record.status}
            Center of Gravity: ${String.format("%.2f", record.cg)}
            Total Weight: ${record.totalWeight.toInt()} kg
            
            TAT: ${record.tat}
            Departure: ${record.departure.ifEmpty { "N/A" }}
            Arrival: ${record.arrival.ifEmpty { "N/A" }}
            
            Fwd Cargo: ${record.fwdCargo} kg | Aft Cargo: ${record.aftCargo} kg
        """.trimIndent()

        dialog.findViewById<TextView>(R.id.tvDialogDetails).text = details

        val dialogWeb = dialog.findViewById<WebView>(R.id.dialogWebView3D)
        dialogWeb.settings.javaScriptEnabled = true

        dialogWeb.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                dialogWeb.evaluateJavascript("javascript:updatePlanePitch(${record.cg}, ${record.rotationY})", null)
            }
        }
        dialogWeb.loadUrl("file:///android_asset/plane_3d.html")

        dialog.findViewById<Button>(R.id.btnDialogClose).setOnClickListener { dialog.dismiss() }

        // Share PDF Button
        dialog.findViewById<Button>(R.id.btnDialogSharePdf).setOnClickListener {
            generateAndSharePDF(record)
        }

        dialog.show()
    }

    // --- PDF GENERATOR ---
    private fun generateAndSharePDF(record: FlightRecord) {
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas

        val titlePaint = Paint().apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 24f; color = Color.parseColor("#001B94") }
        val normalPaint = Paint().apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL); textSize = 16f; color = Color.BLACK }
        val statusPaint = Paint().apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 18f; color = if (record.status == "SAFE") Color.parseColor("#4CAF50") else Color.parseColor("#F44336") }

        canvas.drawText("AIRCRAFT WEIGHT & BALANCE REPORT", 50f, 80f, titlePaint)
        canvas.drawLine(50f, 100f, 545f, 100f, normalPaint)
        canvas.drawText("Flight ID: ${record.flightId}", 50f, 150f, normalPaint)
        canvas.drawText("Arrival: ${record.arrival}", 50f, 180f, normalPaint)
        canvas.drawText("Departure: ${record.departure}", 50f, 210f, normalPaint)
        canvas.drawText("Turn Around Time: ${record.tat}", 50f, 240f, normalPaint)
        canvas.drawLine(50f, 270f, 545f, 270f, normalPaint)
        canvas.drawText("Empty Weight: ${record.emptyWeight} kg", 50f, 310f, normalPaint)
        canvas.drawText("Empty Arm: ${record.emptyArm}", 50f, 340f, normalPaint)
        canvas.drawText("Fwd Cargo: ${record.fwdCargo} kg", 50f, 370f, normalPaint)
        canvas.drawText("Aft Cargo: ${record.aftCargo} kg", 50f, 400f, normalPaint)
        canvas.drawLine(50f, 430f, 545f, 430f, normalPaint)
        canvas.drawText("FINAL RESULTS", 50f, 470f, titlePaint)
        canvas.drawText("Total Weight: ${record.totalWeight.toInt()} kg", 50f, 510f, normalPaint)
        canvas.drawText("Center of Gravity (CG): ${String.format("%.2f", record.cg)}", 50f, 540f, normalPaint)
        canvas.drawText("SYSTEM STATUS: ${record.status}", 50f, 590f, statusPaint)

        pdfDocument.finishPage(page)

        try {
            val pdfDir = File(cacheDir, "pdfs")
            if (!pdfDir.exists()) pdfDir.mkdir()

            val file = File(pdfDir, "WnB_Report_${record.flightId}.pdf")
            val outputStream = FileOutputStream(file)
            pdfDocument.writeTo(outputStream)
            pdfDocument.close()
            outputStream.close()

            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Weight & Balance Report: ${record.flightId}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share PDF Report"))

        } catch (e: Exception) {
            Toast.makeText(this, "Error generating PDF: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun updateCargoFromCG(targetCG: Double, rotationY: Double) {
            runOnUiThread {
                try {
                    lastKnownRotationY = rotationY

                    val eWText = etEmptyWeight.text.toString()
                    val eAText = etEmptyArm.text.toString()
                    val fCText = etFwdCargo.text.toString()

                    if (eWText.isEmpty() || eAText.isEmpty() || fCText.isEmpty()) {
                        return@runOnUiThread
                    }

                    val eW = eWText.toDouble()
                    val eA = eAText.toDouble()
                    val fC = fCText.toDouble()

                    var nAft = ((eW * eA) + (fC * 400.0) - (targetCG * (eW + fC))) / (targetCG - 800.0)
                    if (nAft < 0) nAft = 0.0

                    etAftCargo.setText(nAft.toInt().toString())
                    performStandardCalculation(update3D = false)

                } catch (e: Exception) {}
            }
        }
    }
}

class FlightAdapter(
    private val records: List<FlightRecord>,
    private val onViewClicked: (FlightRecord) -> Unit,
    private val onEditClicked: (FlightRecord) -> Unit,
    private val onRemoveClicked: (FlightRecord) -> Unit
) : RecyclerView.Adapter<FlightAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvId: TextView = view.findViewById(R.id.tvItemFlightId)
        val tvStatus: TextView = view.findViewById(R.id.tvItemStatus)
        val tvDetails: TextView = view.findViewById(R.id.tvItemDetails)
        val btnView: Button = view.findViewById(R.id.btnItemView)
        val btnEdit: Button = view.findViewById(R.id.btnItemEdit)
        val btnRemove: Button = view.findViewById(R.id.btnItemRemove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_flight, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = records[position]
        holder.tvId.text = "Flight: ${record.flightId}"
        holder.tvStatus.text = record.status
        holder.tvStatus.setTextColor(if (record.status == "SAFE") Color.parseColor("#4CAF50") else Color.parseColor("#F44336"))

        holder.tvDetails.text = "CG: ${String.format("%.2f", record.cg)} | Weight: ${record.totalWeight.toInt()}kg"

        holder.btnView.setOnClickListener { onViewClicked(record) }
        holder.btnEdit.setOnClickListener { onEditClicked(record) }
        holder.btnRemove.setOnClickListener { onRemoveClicked(record) }
    }

    override fun getItemCount() = records.size
}