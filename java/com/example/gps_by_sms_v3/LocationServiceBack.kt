package com.example.gps_by_sms_v3

import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class LocationServiceBack : Service() {

    private var fusedLocationProviderClient: FusedLocationProviderClient? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private val hostList = listOf(
        "alito.ddns.net",
        "mygaby.ddns.net",
        "lucianadelarosa.ddnsking.com",
        "andrewp1s4.ddns.net",
        "andresfabregas.ddnsking.com"
    )

    // Variables para almacenar la última ubicación y RPM recibidos
    private var lastLocation: Location? = null
    private var lastRpm: Int = 0

    // OBD2 Connection variables
    private val OBD2_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val OBD2_DEVICE_NAME: String = "OBDII" // Ajusta al nombre de tu dispositivo
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private val BUFFER_SIZE: Int = 1024
    private val READ_TIMEOUT: Long = 2000 // 2 seconds timeout
    private var isOBDConnected = false
    private var selectedSpinnerValue: String? = null // <--- Aquí guardas el valor
    private val OBD_RECONNECTION_INTERVAL = 30_000L // 30 segundos (para pruebas)
    private var reconnectionHandler = Handler(Looper.getMainLooper())
    private var reconnectionRunnable: Runnable? = null
    private var lastSuccessfulOBDRead = 0L
    private var emptyResponseCount = 0
    private val MAX_EMPTY_RESPONSES = 5 // Allow up to 5 consecutive empty reads

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            lastLocation = locationResult.lastLocation // Solo almacena la última ubicación
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        // Initialize Bluetooth adapter
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Actions.START.toString() -> {
                selectedSpinnerValue = intent.getStringExtra("spinner_value") // <--- Guardas el valor aquí
                Log.d("LocationServiceBack", "Spinner value: $selectedSpinnerValue")
                start()
            }
            Actions.STOP.toString() -> stop()
        }

        return START_STICKY
    }

    private fun start() {
        if (isRunning) return
        isRunning = true

        val notification = NotificationCompat.Builder(this, "location_sender")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Sending location and RPM")
            .build()
        startForeground(1, notification)

        // Iniciar conexión OBD en un hilo separado
        Thread {
            connectToOBD()
        }.start()

        startLocationUpdates() // Mantiene el GPS activo y actualizando `lastLocation`
        handler.post(dataRunnable) // Inicia el envío de datos cada 5 segundos
    }

    private fun stop() {
        if (!isRunning) return
        isRunning = false

        handler.removeCallbacks(dataRunnable)
        fusedLocationProviderClient?.removeLocationUpdates(locationCallback)
        stopReconnectionAttempts()
        closeOBDConnection()
        stopForeground(true)
        stopSelf()

        Log.d("LocationServiceBack", "Service stopped successfully")

        handler.post {
            Toast.makeText(this, "stop", Toast.LENGTH_SHORT).show()
        }
    }

    // Ejecuta el envío de datos cada 5 segundos
    private val dataRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                // Leer RPM si la conexión OBD está activa
                if (isOBDConnected) {
                    try {
                        lastRpm = readRPM()
                        // Broadcast RPM to update the UI
                        sendRpmBroadcast(lastRpm)
                    } catch (e: Exception) {
                        Log.e("OBD", "Error reading RPM: ${e.message}")
                        lastRpm = 0
                    }
                }

                lastLocation?.let { location ->
                    val message = locationAndRpmMessage(location, lastRpm)
                    sendLocationUDP(hostList, message)
                }
                handler.postDelayed(this, 5_000)
            }
        }
    }

    private fun sendRpmBroadcast(rpm: Int) {
        val intent = Intent("RPM_UPDATE")
        intent.putExtra("rpm_value", rpm)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun startLocationUpdates() {
        if (fusedLocationProviderClient == null) {
            fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 1000
        ).setMinUpdateIntervalMillis(500).build()

        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            fusedLocationProviderClient?.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        }
    }

    private fun locationAndRpmMessage(location: Location, rpm: Int): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val formattedDate = dateFormat.format(Date(location.time))
        val formattedTime = timeFormat.format(Date(location.time))

        return """
        {
            "ID_TAXI": ${selectedSpinnerValue},
            "LONGITUDE": ${location.longitude},
            "LATITUDE": ${location.latitude},
            "RPM": $rpm,
            "DATE": "$formattedDate",
            "TIME": "$formattedTime"
        }
        """.trimIndent()
    }

    private fun sendLocationUDP(hostnames: List<String>, message: String) {
        Thread {
            try {
                DatagramSocket().use { socket ->
                    val data = message.toByteArray()
                    val port = 50505

                    for (hostname in hostnames) {
                        try {
                            val address = InetAddress.getByName(hostname)
                            val packet = DatagramPacket(data, data.size, address, port)
                            socket.send(packet)
                        } catch (e: Exception) {
                            Log.e("UDP", "Error sending packet to $hostname: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("UDP", "Error creating socket: ${e.message}")
            }
        }.start()
    }

    // OBD2 Connection methods
    private fun connectToOBD() {
        try {
            if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
                Log.e("OBD", "Bluetooth not available or enabled")
                sendObdStatusBroadcast(false, "Bluetooth not available")
                return
            }

            sendObdStatusBroadcast(false, "Attempting to connect...")

            // Get paired devices
            val pairedDevices = bluetoothAdapter!!.bondedDevices
            var obd2Device: BluetoothDevice? = null

            // Find OBD device
            for (device in pairedDevices) {
                if (device.name != null && device.name.contains(OBD2_DEVICE_NAME)) {
                    obd2Device = device
                    break
                }
            }

            if (obd2Device == null) {
                Log.e("OBD", "OBD device not found")
                sendObdStatusBroadcast(false, "OBD device not found")
                return
            }

            // Create socket and connect
            socket = obd2Device.createRfcommSocketToServiceRecord(OBD2_UUID)
            socket!!.connect()
            outputStream = socket!!.outputStream
            inputStream = socket!!.inputStream

            // Initialize OBD
            Thread.sleep(1000)
            sendCommand("ATZ") // Reset
            Thread.sleep(1000)
            sendCommand("ATE0") // Echo off
            Thread.sleep(500)
            sendCommand("ATSP0") // Automatic protocol
            Thread.sleep(1000)

            isOBDConnected = true
            lastSuccessfulOBDRead = System.currentTimeMillis()
            sendObdStatusBroadcast(true, "Connected to OBD")
            Log.d("OBD", "Successfully connected to OBD device")

            // Detener intentos de reconexión si estaban en curso
            stopReconnectionAttempts()

        } catch (e: Exception) {
            Log.e("OBD", "Error connecting to OBD: ${e.message}")
            sendObdStatusBroadcast(false, "Error: ${e.message}")
            closeOBDConnection()
        }
    }

    private fun readRPM(): Int {
        var rpm = 0
        try {
            if (!isOBDConnected || outputStream == null || inputStream == null) {
                // Si detectamos que ya no estamos conectados, intentamos reconectar
                handleOBDDisconnection("Connection lost")
                return 0
            }

            // Command to read RPM
            sendCommand("01 0C")
            val response: String = readResponse()

            if (response.contains("41 0C")) {
                // Valid response received
                emptyResponseCount = 0 // Reset counter
                val data = response.split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                if (data.size >= 4) {
                    val a = data[2].toInt(16)
                    val b = data[3].toInt(16)
                    rpm = ((a * 256) + b) / 4
                    lastSuccessfulOBDRead = System.currentTimeMillis()
                }
            } else if (response.isEmpty()) {
                emptyResponseCount++

                if (emptyResponseCount >= MAX_EMPTY_RESPONSES) {
                    // Too many consecutive empty responses, assume disconnected
                    handleOBDDisconnection("No response from OBD device (after multiple attempts)")
                    emptyResponseCount = 0 // Reset counter to avoid repeated calls
                    return 0
                }
            }

        } catch (e: Exception) {
            Log.e("OBD", "Error reading RPM: ${e.message}")
            // Si hay una excepción, probablemente perdimos la conexión
            handleOBDDisconnection("Error: ${e.message}")
            rpm = 0
        }
        return rpm
    }

    private fun handleOBDDisconnection(reason: String) {
        // Solo procesar si estábamos conectados
        if (isOBDConnected) {
            isOBDConnected = false

            // Notificar al usuario sobre la desconexión
            sendObdStatusBroadcast(false, "Disconnected: $reason")

            // Mostrar notificación
            showDisconnectionNotification(reason)

            // Cerrar la conexión actual
            closeOBDConnection()

            // Iniciar intento de reconexión periódica
            startReconnectionAttempts()
        }
    }

    private fun showDisconnectionNotification(reason: String) {
        val notification = NotificationCompat.Builder(this, "location_sender")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("OBD Connection Lost")
            .setContentText("Reason: $reason. Attempting to reconnect...")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Usar un ID diferente para no reemplazar la notificación del servicio
        notificationManager.notify(2, notification)
    }

    private fun startReconnectionAttempts() {
        // Cancelar cualquier intento anterior
        stopReconnectionAttempts()

        reconnectionRunnable = object : Runnable {
            override fun run() {
                if (isRunning && !isOBDConnected) {
                    Log.d("OBD", "Attempting to reconnect to OBD device...")
                    // Intentar conectar nuevamente
                    Thread {
                        connectToOBD()
                    }.start()

                    // Programar próximo intento
                    reconnectionHandler.postDelayed(this, OBD_RECONNECTION_INTERVAL)
                }
            }
        }

        // Iniciar primer intento de reconexión
        reconnectionHandler.postDelayed(reconnectionRunnable!!, OBD_RECONNECTION_INTERVAL)
    }

    private fun stopReconnectionAttempts() {
        reconnectionRunnable?.let {
            reconnectionHandler.removeCallbacks(it)
            reconnectionRunnable = null
        }
    }

    @Throws(IOException::class)
    private fun readResponse(): String {
        if (inputStream == null) return ""

        val response = StringBuilder()
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead: Int
        val startTime = System.currentTimeMillis()

        // Wait for data with timeout
        while ((System.currentTimeMillis() - startTime) < READ_TIMEOUT) {
            if (inputStream!!.available() > 0) {
                bytesRead = inputStream!!.read(buffer)
                response.append(String(buffer, 0, bytesRead))

                // Check if we have a complete response
                if (response.toString().contains("\r")) {
                    break
                }
            }
        }

        // Clean and format the response
        val cleanResponse = response.toString()
            .replace("\r", "")
            .replace("\n", "")
            .trim { it <= ' ' }

        // Convert response to uppercase and split into lines
        val lines = cleanResponse.uppercase(Locale.getDefault()).split(">".toRegex())
            .dropLastWhile { it.isEmpty() }.toTypedArray()

        // Get the last meaningful response
        for (i in lines.indices.reversed()) {
            val line = lines[i].trim { it <= ' ' }
            if (!line.isEmpty() && !line.contains("SEARCHING") && line != "OK") {
                return line
            }
        }

        return ""
    }

    private fun sendCommand(command: String) {
        if (outputStream != null) {
            try {
                // Clear any existing data in the input buffer
                if (inputStream != null && inputStream!!.available() > 0) {
                    inputStream!!.skip(inputStream!!.available().toLong())
                }
                // Send the command with carriage return
                outputStream!!.write((command + "\r").toByteArray())
                outputStream!!.flush()
                // Small delay to ensure command is processed
                Thread.sleep(200)
            } catch (e: Exception) {
                Log.e("OBD", "Error sending command: ${e.message}")
            }
        }
    }

    private fun closeOBDConnection() {
        isOBDConnected = false

        try {
            if (outputStream != null) outputStream!!.close()
            if (inputStream != null) inputStream!!.close()
            if (socket != null) socket!!.close()
        } catch (e: IOException) {
            Log.e("OBD", "Error closing OBD connection: ${e.message}")
        }

        outputStream = null
        inputStream = null
        socket = null
    }

    private fun sendObdStatusBroadcast(connected: Boolean, statusMessage: String) {
        val intent = Intent("OBD_STATUS_UPDATE")
        intent.putExtra("connected", connected)
        intent.putExtra("status_message", statusMessage)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    enum class Actions {
        START, STOP
    }

    override fun onDestroy() {
        super.onDestroy()
        stopReconnectionAttempts()
        closeOBDConnection()
    }
}