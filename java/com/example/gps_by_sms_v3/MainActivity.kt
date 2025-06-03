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
import android.os.Build
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
    private lateinit var idSpinner: Spinner

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create notification channels first
        createNotificationChannels()

        setContentView(R.layout.main_activity)

        // Initialize UI components
        initializeViews()

        // Setup spinner
        setupSpinner()

        // Load animated GIF using Glide
        val imageView: ImageView = findViewById(R.id.worldImg)
        Glide.with(this).asGif().load(R.drawable.world).into(imageView)

        // Check and request permissions
        checkAndRequestPermissions()

        // Register broadcast receivers
        registerReceivers()

        // Setup toggle button listener
        setupToggleButton()
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            "location_sender",
            "Location Notifications",
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(channel)

        val disconnectionChannel = NotificationChannel(
            "obd_disconnection",
            "OBD Disconnection Alerts",
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(disconnectionChannel)
    }

    private fun initializeViews() {
        sendToggle = findViewById(R.id.sendToggle)
        rpmValueText = findViewById(R.id.rpmValueText)
        obdStatusText = findViewById(R.id.obdStatusText)
        idSpinner = findViewById(R.id.idSpinner)

        // Make RPM TextView visible
        rpmValueText.visibility = View.VISIBLE
    }

    private fun setupSpinner() {
        ArrayAdapter.createFromResource(
            this,
            R.array.idSpinner_array,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            idSpinner.adapter = adapter
        }
    }

    private fun setupToggleButton() {
        sendToggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (hasAllRequiredPermissions()) {
                    startLocationService()
                } else {
                    // Reset toggle and request permissions
                    sendToggle.isChecked = false
                    checkAndRequestPermissions()
                    Toast.makeText(this, "Please grant all required permissions", Toast.LENGTH_LONG).show()
                }
            } else {
                stopLocationService()
            }
        }
    }

    private fun startLocationService() {
        val selectedOption = idSpinner.selectedItem.toString()
        Intent(applicationContext, LocationServiceBack::class.java).also {
            it.action = LocationServiceBack.Actions.START.toString()
            it.putExtra("spinner_value", selectedOption)
            startService(it)
        }
        Toast.makeText(this, "Foreground Service Started", Toast.LENGTH_SHORT).show()
        updateToggleButtonUI(true)
    }

    private fun stopLocationService() {
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

    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_LOCATION
        )

        // Add Bluetooth permissions based on Android version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ requires new Bluetooth permissions
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ))
        } else {
            // Older Android versions use legacy Bluetooth permissions
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            ))
        }

        // Background location permission for Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        return permissions.toTypedArray()
    }

    private fun hasAllRequiredPermissions(): Boolean {
        return getRequiredPermissions().all { permission ->
            ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkAndRequestPermissions() {
        val missingPermissions = getRequiredPermissions().filter { permission ->
            ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val deniedPermissions = mutableListOf<String>()

            for (i in permissions.indices) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    deniedPermissions.add(permissions[i])
                }
            }

            if (deniedPermissions.isEmpty()) {
                Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
            } else {
                val deniedNames = deniedPermissions.map { permission ->
                    when (permission) {
                        Manifest.permission.ACCESS_FINE_LOCATION -> "Fine Location"
                        Manifest.permission.ACCESS_COARSE_LOCATION -> "Coarse Location"
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION -> "Background Location"
                        Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth Connect"
                        Manifest.permission.BLUETOOTH_SCAN -> "Bluetooth Scan"
                        Manifest.permission.BLUETOOTH -> "Bluetooth"
                        Manifest.permission.BLUETOOTH_ADMIN -> "Bluetooth Admin"
                        else -> permission.substringAfterLast(".")
                    }
                }

                Toast.makeText(
                    this,
                    "Denied permissions: ${deniedNames.joinToString(", ")}. App may not work correctly.",
                    Toast.LENGTH_LONG
                ).show()
            }
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
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(rpmUpdateReceiver)
            LocalBroadcastManager.getInstance(this).unregisterReceiver(obdStatusReceiver)
        } catch (e: Exception) {
            // Receivers may not be registered, ignore
        }
    }
}