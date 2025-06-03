package com.example.gps_by_sms_v3

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.bumptech.glide.Glide

class MainActivity : AppCompatActivity() {
    private lateinit var sendToggle: ToggleButton
    private lateinit var rpmValueText: TextView
    private lateinit var obdStatusText: TextView
    private  lateinit var  idSpinner: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_LOCATION,  // Añadir esto
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_CONNECT
        )

        ActivityCompat.requestPermissions(this, permissions, 1)

        val channel = NotificationChannel(
            "location_sender",
            "Location Notifications",
            NotificationManager.IMPORTANCE_HIGH
        )
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        val disconnectionChannel = NotificationChannel(
            "obd_disconnection",
            "OBD Disconnection Alerts",
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(disconnectionChannel)

        setContentView(R.layout.main_activity)

        // Initialize UI components
        sendToggle = findViewById(R.id.sendToggle)
        rpmValueText = findViewById(R.id.rpmValueText)
        obdStatusText = findViewById(R.id.obdStatusText)
        idSpinner = findViewById(R.id.idSpinner)
        val imageView: ImageView = findViewById(R.id.worldImg)

        ArrayAdapter.createFromResource(
            this,
            R.array.idSpinner_array,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            // Specify the layout to use when the list of choices appears.
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            // Apply the adapter to the spinner.
            idSpinner.adapter = adapter
        }

        // Make RPM TextView visible
        rpmValueText.visibility = View.VISIBLE

        // Load animated GIF using Glide
        Glide.with(this).asGif().load(R.drawable.world).into(imageView)

        checkAndRequestPermissions()

        // Register broadcast receivers
        registerReceivers()

        // Toggle button listener to start/stop sending location
        sendToggle.setOnCheckedChangeListener { _, isChecked ->
            val selectedOption = idSpinner.selectedItem.toString()
            if (isChecked) {
                Intent(applicationContext, LocationServiceBack::class.java).also {
                    it.action = LocationServiceBack.Actions.START.toString()
                    it.putExtra("spinner_value", selectedOption) // <-- Send spinner value here
                    startService(it)
                }
                Toast.makeText(this, "Foreground Service Started", Toast.LENGTH_SHORT).show()
                updateToggleButtonUI(true)
            } else {
                Intent(applicationContext, LocationServiceBack::class.java).also {
                    it.action = LocationServiceBack.Actions.STOP.toString()
                    startService(it)
                }
                Toast.makeText(this, "Foreground Service Stopped", Toast.LENGTH_SHORT).show()
                updateToggleButtonUI(false)
                rpmValueText.text = "RPM: -"
                obdStatusText.text = "OBD Status: Not Connected"
                obdStatusText.setTextColor(Color.RED)
            }
        }
    }

    private fun registerReceivers() {
        // Register for RPM updates
        LocalBroadcastManager.getInstance(this).registerReceiver(
            rpmUpdateReceiver,
            IntentFilter("RPM_UPDATE")
        )

        // Register for OBD connection status updates
        LocalBroadcastManager.getInstance(this).registerReceiver(
            obdStatusReceiver,
            IntentFilter("OBD_STATUS_UPDATE")
        )
    }

    // Receiver for RPM updates from service
    private val rpmUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val rpm = intent.getIntExtra("rpm_value", 0)
            runOnUiThread {
                rpmValueText.text = "RPM: $rpm"
            }
        }
    }

    // Receiver for OBD status updates
    private val obdStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val connected = intent.getBooleanExtra("connected", false)
            val statusMessage = intent.getStringExtra("status_message") ?: "Unknown status"

            runOnUiThread {
                if (connected) {
                    obdStatusText.text = "OBD Status: $statusMessage"
                    obdStatusText.setTextColor(Color.GREEN)
                } else {
                    obdStatusText.text = "OBD Status: $statusMessage"
                    obdStatusText.setTextColor(Color.RED)
                }
            }
        }
    }

    /**
     * Checks and requests location permissions if not granted
     */
    private fun checkAndRequestPermissions() {
        if (!hasLocationPermission()) requestLocationPermissions()
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permissions granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Location permissions denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateToggleButtonUI(isActive: Boolean) {
        if (isActive) {
            sendToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#93fab4"))
            sendToggle.setTextColor(Color.parseColor("#023269"))
        } else {
            sendToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#023269"))
            sendToggle.setTextColor(Color.parseColor("#93fab4"))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister broadcast receivers
        LocalBroadcastManager.getInstance(this).unregisterReceiver(rpmUpdateReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(obdStatusReceiver)
    }
}