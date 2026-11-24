package com.hotchkin.kohlergenset

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hotchkin.kohlergenset.ui.theme.KohlerGensetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.entries.forEach {
            println("Permission ${it.key} = ${it.value}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Create Notification Channel for Genset Alerts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "kohler_alerts_channel",
                "Kohler Genset Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Push notifications for generator start/stop events and out-of-spec fault alerts"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        // Ask for permissions on Android 13+
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())

        // Start 24/7 Ultra-Low Battery Background Monitor Service
        try {
            val serviceIntent = Intent(this, GensetMqttService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setContent {
            KohlerGensetTheme(darkTheme = true) {
                GeneratorAppScreen()
            }
        }
    }
}

fun sendPushNotification(context: Context, title: String, text: String) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val builder = NotificationCompat.Builder(context, "kohler_alerts_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(context)) {
            notify((System.currentTimeMillis() % 10000).toInt(), builder.build())
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneratorAppScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("kohler_settings", Context.MODE_PRIVATE) }

    // Mosquitto Broker Settings State (Local Network Broker: 192.168.68.91 | Tailscale: 100.108.173.127)
    var mqttHost by remember { mutableStateOf(prefs.getString("mqtt_host", "100.108.173.127") ?: "100.108.173.127") }
    var mqttUsername by remember { mutableStateOf(prefs.getString("mqtt_user", "aahotch2001") ?: "aahotch2001") }
    var mqttPassword by remember { mutableStateOf(prefs.getString("mqtt_pass", "aaronjulie") ?: "aaronjulie") }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Auto-migrate legacy settings stored on physical phone
    LaunchedEffect(Unit) {
        val editor = prefs.edit()
        var updated = false
        if (mqttHost == "192.168.65.91") {
            mqttHost = "192.168.68.91"
            editor.putString("mqtt_host", "192.168.68.91")
            updated = true
        }
        if (mqttUsername == "YOUR_MQTT_USER") {
            mqttUsername = "aahotch2001"
            editor.putString("mqtt_user", "aahotch2001")
            updated = true
        }
        if (mqttPassword == "YOUR_MQTT_PASS") {
            mqttPassword = "aaronjulie"
            editor.putString("mqtt_pass", "aaronjulie")
            updated = true
        }
        if (updated) editor.apply()
    }

    // Thermostat & Heater Custom Settings State
    var autoHeaterEnabled by remember { mutableStateOf(prefs.getBoolean("auto_heater_enable", true)) }
    var heaterOnTemp by remember { mutableStateOf(prefs.getString("heater_on_temp", "38") ?: "38") }
    var heaterOffTemp by remember { mutableStateOf(prefs.getString("heater_off_temp", "50") ?: "50") }
    var heaterMaxMins by remember { mutableStateOf(prefs.getString("heater_max_mins", "120") ?: "120") }

    // Victron Smart Charger Custom Settings State
    var autoChargerEnabled by remember { mutableStateOf(prefs.getBoolean("auto_charger_enable", true)) }
    var chargerOnVolts by remember { mutableStateOf(prefs.getString("charger_on_volts", "12.2") ?: "12.2") }
    var chargerOffVolts by remember { mutableStateOf(prefs.getString("charger_off_volts", "13.8") ?: "13.8") }
    var chargerMaxMins by remember { mutableStateOf(prefs.getString("charger_max_mins", "120") ?: "120") }

    // Live Telemetry States
    var oilPressure by remember { mutableStateOf("0") }
    var waterTemp by remember { mutableStateOf("0") }
    var blockTemp by remember { mutableStateOf("0") }
    var batteryVoltage by remember { mutableStateOf("0.0") }
    var solarBattery48v by remember { mutableStateOf("0.0") }
    var fuelLevelPct by remember { mutableStateOf("0") }
    var burnRateGph by remember { mutableStateOf("0.0") }
    var lifetimeFuelGal by remember { mutableStateOf("0.0") }
    var engineRpm by remember { mutableStateOf("0") }
    var generatorStatus by remember { mutableStateOf("STOPPED") }
    var coolantHeaterOn by remember { mutableStateOf(false) }
    var victronChargerOn by remember { mutableStateOf(false) }
    var lastCommand by remember { mutableStateOf("") }
    var lastCommandTime by remember { mutableStateOf(0L) }
    var lastOilAlertTime by remember { mutableStateOf(0L) }
    var lastTempAlertTime by remember { mutableStateOf(0L) }
    var lastBattAlertTime by remember { mutableStateOf(0L) }
    var lastSolarAlertTime by remember { mutableStateOf(0L) }
    var lastFuelAlertTime by remember { mutableStateOf(0L) }

    // Live Event Logs List
    val systemLogs = remember { mutableStateListOf<String>() }

    fun addLog(msg: String) {
        val timeStr = java.text.SimpleDateFormat("hh:mm:ss a", java.util.Locale.US).format(java.util.Date())
        systemLogs.add(0, "[$timeStr] $msg")
        if (systemLogs.size > 100) systemLogs.removeAt(systemLogs.size - 1)
    }

    // Safety Confirmation Modal States
    var pendingCommand by remember { mutableStateOf<String?>(null) } // "START" or "STOP"

    // MQTT Client Ref
    var mqttClient by remember { mutableStateOf<MqttClient?>(null) }

    // Function to connect to Mosquitto MQTT Broker
    fun connectMqtt() {
        if (mqttHost.isEmpty()) return
        
        coroutineScope.launch(Dispatchers.Main) {
            generatorStatus = "CONNECTING..."
        }

        coroutineScope.launch(Dispatchers.IO) {
            try {
                try { mqttClient?.disconnect() } catch (ignored: Exception) {}
                try { mqttClient?.close() } catch (ignored: Exception) {}
                mqttClient = null

                // Clean host string (strip tcp://, http://, ports, whitespace)
                val cleanHost = mqttHost.trim()
                    .removePrefix("tcp://")
                    .removePrefix("http://")
                    .removePrefix("https://")
                    .substringBefore(":")
                
                val hostsToTry = mutableListOf<String>()
                if (cleanHost.isNotEmpty()) hostsToTry.add(cleanHost)
                if ("100.108.173.127" !in hostsToTry) hostsToTry.add("100.108.173.127")
                if ("192.168.68.91" !in hostsToTry) hostsToTry.add("192.168.68.91")

                var lastException: Exception? = null
                var connectedClient: MqttClient? = null

                for (targetHost in hostsToTry) {
                    val optionsList = mutableListOf<MqttConnectOptions>()
                    if (mqttUsername.isNotEmpty()) {
                        optionsList.add(MqttConnectOptions().apply {
                            userName = mqttUsername
                            password = mqttPassword.toCharArray()
                            isCleanSession = true
                            connectionTimeout = 2 // Fast 2-second timeout per attempt
                            keepAliveInterval = 30
                        })
                    }
                    optionsList.add(MqttConnectOptions().apply {
                        isCleanSession = true
                        connectionTimeout = 2 // Fast 2-second timeout per attempt
                        keepAliveInterval = 30
                    })

                    for (opts in optionsList) {
                        try {
                            val serverUri = "tcp://$targetHost:1883"
                            val clientId = "Android_App_" + System.currentTimeMillis()
                            val client = MqttClient(serverUri, clientId, MemoryPersistence())

                            client.setCallback(object : MqttCallback {
                                override fun connectionLost(cause: Throwable?) {
                                    coroutineScope.launch(Dispatchers.Main) {
                                        generatorStatus = "DISCONNECTED"
                                    }
                                }

                                override fun messageArrived(topic: String?, message: MqttMessage?) {
                                    val payload = message?.toString() ?: return
                                    val topicName = topic ?: ""
                                    
                                    coroutineScope.launch(Dispatchers.Main) {
                                        when (topicName) {
                                            "kohler/oil-pressure" -> {
                                                oilPressure = payload
                                                val psi = payload.toFloatOrNull() ?: 0f
                                                val now = System.currentTimeMillis()
                                                if (generatorStatus.contains("RUNNING", ignoreCase = true) && psi < 15.0f && psi > 0f && (now - lastOilAlertTime >= 15 * 60 * 1000L)) {
                                                    lastOilAlertTime = now
                                                    sendPushNotification(context, "⚠️ CRITICAL: Low Oil Pressure", "Oil pressure dropped to $payload PSI!")
                                                }
                                            }
                                            "kohler/water-temp" -> {
                                                waterTemp = payload
                                                val temp = payload.toFloatOrNull() ?: 0f
                                                val now = System.currentTimeMillis()
                                                if (generatorStatus.contains("RUNNING", ignoreCase = true) && temp > 225.0f && (now - lastTempAlertTime >= 15 * 60 * 1000L)) {
                                                    lastTempAlertTime = now
                                                    sendPushNotification(context, "⚠️ CRITICAL: Engine Overheating", "Water temp reached $payload °F!")
                                                }
                                            }
                                            "kohler/battery-voltage" -> {
                                                batteryVoltage = payload
                                                val v12 = payload.toFloatOrNull() ?: 0f
                                                val now = System.currentTimeMillis()
                                                if (v12 < 11.8f && v12 > 5.0f && (now - lastBattAlertTime >= 15 * 60 * 1000L)) {
                                                    lastBattAlertTime = now
                                                    sendPushNotification(context, "⚠️ BATTERY WARNING", "12V Starter Battery low ($payload V)!")
                                                }
                                            }
                                            "kohler/solar-battery-48v" -> {
                                                solarBattery48v = payload
                                                val v48 = payload.toFloatOrNull() ?: 0f
                                                val now = System.currentTimeMillis()
                                                if (v48 < 48.0f && v48 > 10.0f && (now - lastSolarAlertTime >= 15 * 60 * 1000L)) {
                                                    lastSolarAlertTime = now
                                                    sendPushNotification(context, "⚠️ SOLAR BANK WARNING", "48V Battery Bank low ($payload V)!")
                                                }
                                            }
                                            "kohler/fuel-level" -> {
                                                fuelLevelPct = payload
                                                val pct = payload.toFloatOrNull() ?: 0f
                                                val now = System.currentTimeMillis()
                                                if (pct < 15.0f && pct > 0f && (now - lastFuelAlertTime >= 15 * 60 * 1000L)) {
                                                    lastFuelAlertTime = now
                                                    sendPushNotification(context, "⛽ FUEL WARNING", "Fuel Tank low ($payload %)!")
                                                }
                                            }
                                            "kohler/burn-rate" -> burnRateGph = payload
                                            "kohler/lifetime-fuel" -> lifetimeFuelGal = payload
                                            "kohler/engine-rpm" -> engineRpm = payload
                                            "kohler/block-temp" -> blockTemp = payload
                                            "kohler/generator-status" -> {
                                                val oldStatus = generatorStatus
                                                generatorStatus = payload
                                                if (oldStatus != payload) {
                                                    addLog("Status update: $oldStatus ➔ $payload")
                                                }
                                                if ((oldStatus.equals("STOPPED", ignoreCase = true) || oldStatus.contains("CRANKING")) && payload.contains("RUNNING", ignoreCase = true)) {
                                                    addLog("🟢 Event: Generator Started ($payload)")
                                                    sendPushNotification(context, "🟢 Generator Started", "Kohler 60kW Genset is now RUNNING")
                                                } else if ((oldStatus.contains("RUNNING", ignoreCase = true) || oldStatus.contains("COOLDOWN")) && payload.equals("STOPPED", ignoreCase = true)) {
                                                    addLog("🔴 Event: Generator Stopped")
                                                    sendPushNotification(context, "🔴 Generator Stopped", "Kohler 60kW Genset is now STOPPED")
                                                }
                                            }
                                            "kohler/coolant-heater" -> coolantHeaterOn = payload.contains("ON", ignoreCase = true)
                                            "kohler/victron-charger" -> victronChargerOn = payload.contains("ON", ignoreCase = true)
                                        }
                                    }
                                }

                                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                            })

                            client.connect(opts)
                            connectedClient = client
                            break
                        } catch (e: Exception) {
                            lastException = e
                        }
                    }
                    if (connectedClient != null && connectedClient.isConnected) {
                        break
                    }
                }

                if (connectedClient != null && connectedClient.isConnected) {
                    mqttClient = connectedClient
                    val topics = arrayOf(
                        "kohler/oil-pressure",
                        "kohler/water-temp",
                        "kohler/block-temp",
                        "kohler/battery-voltage",
                        "kohler/solar-battery-48v",
                        "kohler/fuel-level",
                        "kohler/burn-rate",
                        "kohler/lifetime-fuel",
                        "kohler/engine-rpm",
                        "kohler/generator-status",
                        "kohler/coolant-heater",
                        "kohler/victron-charger"
                    )
                    connectedClient.subscribe(topics)

                    coroutineScope.launch(Dispatchers.Main) {
                        generatorStatus = "CONNECTED"
                        addLog("Connected to Mosquitto Broker")
                    }
                } else {
                    throw lastException ?: Exception("Connection Failed")
                }

            } catch (e: Exception) {
                e.printStackTrace()
                val errReason = if (e is MqttException) {
                    val causeMsg = e.cause?.localizedMessage ?: e.message
                    "MQTT (rc=${e.reasonCode}): $causeMsg"
                } else {
                    e.localizedMessage ?: e.message ?: "Connection Failed"
                }
                coroutineScope.launch(Dispatchers.Main) {
                    generatorStatus = "ERR: $errReason"
                    addLog("Connection failed: $errReason")
                }
            }
        }
    }

    // Auto connect on launch & continuous 5s auto-reconnect loop when disconnected
    LaunchedEffect(mqttHost, mqttUsername, mqttPassword) {
        while (true) {
            val client = mqttClient
            if (client == null || !client.isConnected) {
                connectMqtt()
            }
            delay(5000)
        }
    }

    fun publishHeaterConfig() {
        val client = mqttClient
        if (client != null && client.isConnected) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    client.publish("kohler/heater-auto-enable", MqttMessage((if (autoHeaterEnabled) "ON" else "OFF").toByteArray()))
                    client.publish("kohler/heater-set-on-temp", MqttMessage(heaterOnTemp.toByteArray()))
                    client.publish("kohler/heater-set-off-temp", MqttMessage(heaterOffTemp.toByteArray()))
                    client.publish("kohler/heater-set-max-mins", MqttMessage(heaterMaxMins.toByteArray()))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun publishChargerConfig() {
        val client = mqttClient
        if (client != null && client.isConnected) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    client.publish("kohler/charger-auto-enable", MqttMessage((if (autoChargerEnabled) "ON" else "OFF").toByteArray()))
                    client.publish("kohler/charger-set-on-volts", MqttMessage(chargerOnVolts.toByteArray()))
                    client.publish("kohler/charger-set-off-volts", MqttMessage(chargerOffVolts.toByteArray()))
                    client.publish("kohler/charger-set-max-mins", MqttMessage(chargerMaxMins.toByteArray()))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun toggleAutoCharger(enabled: Boolean) {
        autoChargerEnabled = enabled
        prefs.edit().putBoolean("auto_charger_enable", enabled).apply()
        publishChargerConfig()
    }

    fun sendCommand(cmd: String) {
        val client = mqttClient
        if (client != null && client.isConnected) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val topic = "kohler/generator-command"
                    val message = MqttMessage(cmd.toByteArray())
                    client.publish(topic, message)
                    
                    coroutineScope.launch(Dispatchers.Main) {
                        lastCommand = cmd
                        lastCommandTime = System.currentTimeMillis()
                        generatorStatus = if (cmd == "START") "CRANKING..." else "COOLDOWN..."
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else {
            coroutineScope.launch(Dispatchers.Main) {
                generatorStatus = "NOT CONNECTED"
                showSettingsDialog = true
            }
        }
    }

    fun toggleCoolantHeater(enabled: Boolean) {
        val client = mqttClient
        if (client != null && client.isConnected) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val topic = "kohler/coolant-heater-cmd"
                    val message = MqttMessage((if (enabled) "ON" else "OFF").toByteArray())
                    client.publish(topic, message)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun toggleVictronCharger(enabled: Boolean) {
        val client = mqttClient
        if (client != null && client.isConnected) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val topic = "kohler/victron-charger-cmd"
                    val message = MqttMessage((if (enabled) "ON" else "OFF").toByteArray())
                    client.publish(topic, message)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun toggleAutoHeater(enabled: Boolean) {
        autoHeaterEnabled = enabled
        prefs.edit().putBoolean("auto_heater_enable", enabled).apply()
        publishHeaterConfig()
    }

    Scaffold(
        containerColor = Color(0xFF0D0D0D),
        topBar = {
            TopAppBar(
                title = { Text("Generator", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Text("⚙", fontSize = MaterialTheme.typography.titleLarge.fontSize, color = Color.White)
                    }
                },
                actions = {
                    // Dynamic Status Badge
                    val badgeColor = when {
                        generatorStatus.contains("RUNNING", ignoreCase = true) -> Color(0xFF1B3B22)
                        generatorStatus.contains("COOLDOWN", ignoreCase = true) || generatorStatus.contains("CRANKING", ignoreCase = true) -> Color(0xFF3E2723)
                        else -> Color(0xFF2A2A2A)
                    }
                    val textColor = when {
                        generatorStatus.contains("RUNNING", ignoreCase = true) -> Color(0xFF4CAF50)
                        generatorStatus.contains("COOLDOWN", ignoreCase = true) || generatorStatus.contains("CRANKING", ignoreCase = true) -> Color(0xFFFF9800)
                        else -> Color.LightGray
                    }
                    
                    Surface(
                        color = badgeColor,
                        shape = RoundedCornerShape(16.dp),
                        onClick = { connectMqtt() },
                        modifier = Modifier.padding(end = 16.dp)
                    ) {
                        Text(
                            text = generatorStatus,
                            color = textColor,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // GAUGES ROW (Oil, Water Temp, Block Temp, 12V Battery)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DashboardGauge(title = "Oil Pressure", value = oilPressure, unit = "psi", modifier = Modifier.weight(1f))
                    DashboardGauge(title = "Water Temp", value = waterTemp, unit = "°F", modifier = Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DashboardGauge(title = "Block Temp", value = blockTemp, unit = "°F", modifier = Modifier.weight(1f))
                    DashboardGauge(title = "12V Battery", value = batteryVoltage, unit = "V", modifier = Modifier.weight(1f))
                }
            }

            // ENGINE SPEED & FREQUENCY CARD
            Surface(
                color = Color(0xFF1E1E1E),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Engine Speed", color = Color.LightGray, fontSize = MaterialTheme.typography.bodySmall.fontSize)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("${engineRpm} RPM", color = Color.White, fontSize = MaterialTheme.typography.titleLarge.fontSize, fontWeight = FontWeight.Bold)
                    }

                    val rpmNum = engineRpm.toFloatOrNull() ?: 0f
                    val freqHz = if (rpmNum > 100f) String.format(java.util.Locale.US, "%.1f Hz", (rpmNum * 60.0f) / 1800.0f) else "0.0 Hz"
                    
                    Surface(
                        color = if (rpmNum in 1700f..1900f) Color(0xFF1B3B22) else Color(0xFF2A2A2A),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = freqHz,
                            color = if (rpmNum in 1700f..1900f) Color(0xFF4CAF50) else Color.LightGray,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            // FUEL LEVEL & CONSUMPTION CARD
            Surface(
                color = Color(0xFF1E1E1E),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val floatFuel = fuelLevelPct.toFloatOrNull() ?: 0f
                    val floatBurnRate = burnRateGph.toFloatOrNull() ?: 0f
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Fuel Level", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("${fuelLevelPct}%", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    LinearProgressIndicator(
                        progress = { (floatFuel / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp),
                        color = Color(0xFF4CAF50),
                        trackColor = Color(0xFF2A2A2A)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Burn Rate: ${burnRateGph} GPH", color = Color.LightGray, fontSize = MaterialTheme.typography.bodySmall.fontSize)
                        
                        val estHours = if (floatBurnRate > 0.1f) String.format(java.util.Locale.US, "%.1fh", (floatFuel * 1.0f) / floatBurnRate) else "--"
                        Text("Est. Remaining: $estHours", color = Color.LightGray, fontSize = MaterialTheme.typography.bodySmall.fontSize)
                    }
                }
            }

            // START / STOP BUTTONS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // START Button
                OutlinedButton(
                    onClick = { pendingCommand = "START" },
                    modifier = Modifier
                        .weight(1f)
                        .height(75.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF1B3B22),
                        contentColor = Color.White
                    ),
                    border = null
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("START", fontSize = MaterialTheme.typography.titleMedium.fontSize, color = Color.White, fontWeight = FontWeight.Bold)
                        Text("Start Generator", fontSize = MaterialTheme.typography.bodySmall.fontSize, color = Color.LightGray)
                    }
                }

                // STOP Button
                OutlinedButton(
                    onClick = { pendingCommand = "STOP" },
                    modifier = Modifier
                        .weight(1f)
                        .height(75.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF5C2323),
                        contentColor = Color.White
                    ),
                    border = null
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("STOP", fontSize = MaterialTheme.typography.titleMedium.fontSize, color = Color.White, fontWeight = FontWeight.Bold)
                        Text("Stop Generator", fontSize = MaterialTheme.typography.bodySmall.fontSize, color = Color.LightGray)
                    }
                }
            }

            // 48V SOLAR BATTERY BANK CARD
            Surface(
                color = Color(0xFF1E1E1E),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("48V Solar Battery Bank", color = Color.LightGray, fontSize = MaterialTheme.typography.bodySmall.fontSize)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("${solarBattery48v} V", color = Color.White, fontSize = MaterialTheme.typography.titleLarge.fontSize, fontWeight = FontWeight.Bold)
                    }

                    val float48v = solarBattery48v.toFloatOrNull() ?: 0f
                    val socPct = when {
                        float48v >= 53.3f -> "100%"
                        float48v >= 52.5f -> "80%"
                        float48v >= 51.5f -> "50%"
                        float48v >= 50.0f -> "20%"
                        float48v > 10.0f  -> "LOW"
                        else -> "--"
                    }
                    val socColor = when {
                        float48v >= 51.5f -> Color(0xFF4CAF50)
                        float48v >= 50.0f -> Color(0xFFFF9800)
                        float48v > 10.0f  -> Color(0xFFF44336)
                        else -> Color.LightGray
                    }

                    Surface(
                        color = Color(0xFF2A2A2A),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Charge: $socPct",
                            color = socColor,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            // RUNTIME & LIFETIME FUEL CARD
            Surface(
                color = Color(0xFF1E1E1E),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Lifetime Fuel:", color = Color.LightGray, fontSize = MaterialTheme.typography.bodyMedium.fontSize)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("${lifetimeFuelGal} Gal", color = Color.White, fontSize = MaterialTheme.typography.titleLarge.fontSize, fontWeight = FontWeight.Bold)
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(50.dp)
                            .padding(horizontal = 8.dp)
                    ) {
                        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF333333)) {}
                    }

                    Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                        Text("Total Runtime:", color = Color.LightGray, fontSize = MaterialTheme.typography.bodyMedium.fontSize)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("0h 0m", color = Color.White, fontSize = MaterialTheme.typography.titleLarge.fontSize, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 120V COOLANT HEATER CONTROL CARD
            Surface(
                color = Color(0xFF1E1E1E),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("120V Coolant Heater Control", color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))

                    // Manual Pre-Heat Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Manual Pre-Heat", color = Color.White, fontWeight = FontWeight.SemiBold)
                            val statusDesc = if (coolantHeaterOn) "HEATER ACTIVE" else "OFF"
                            Text(statusDesc, color = if (coolantHeaterOn) Color(0xFF4CAF50) else Color.LightGray, fontSize = MaterialTheme.typography.bodySmall.fontSize)
                        }
                        Switch(
                            checked = coolantHeaterOn,
                            onCheckedChange = { isChecked ->
                                coolantHeaterOn = isChecked
                                toggleCoolantHeater(isChecked)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF4CAF50))
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color(0xFF333333))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Auto Thermostat Protection Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Auto-Freeze Thermostat", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("Auto < ${heaterOnTemp}°F ➔ ${heaterOffTemp}°F (Max ${heaterMaxMins}m)", color = Color.LightGray, fontSize = MaterialTheme.typography.bodySmall.fontSize)
                        }
                        Switch(
                            checked = autoHeaterEnabled,
                            onCheckedChange = { isChecked ->
                                toggleAutoHeater(isChecked)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF4CAF50))
                        )
                    }
                }
            }

            // VICTRON 48V-TO-12V SMART CHARGER CARD
            Surface(
                color = Color(0xFF1E1E1E),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Victron 48V-to-12V Smart Charger", color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))

                    // Manual 12V Charger Override Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Manual 12V Charger Override", color = Color.White, fontWeight = FontWeight.SemiBold)
                            val chargerDesc = if (victronChargerOn) "CHARGER ACTIVE" else "OFF (0mA 48V Standby)"
                            Text(chargerDesc, color = if (victronChargerOn) Color(0xFF4CAF50) else Color.LightGray, fontSize = MaterialTheme.typography.bodySmall.fontSize)
                        }
                        Switch(
                            checked = victronChargerOn,
                            onCheckedChange = { isChecked ->
                                victronChargerOn = isChecked
                                toggleVictronCharger(isChecked)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF4CAF50))
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color(0xFF333333))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Auto Smart Charging Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Auto Smart 12V Charging", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("Auto < ${chargerOnVolts}V ➔ ${chargerOffVolts}V (Max ${chargerMaxMins}m)", color = Color.LightGray, fontSize = MaterialTheme.typography.bodySmall.fontSize)
                        }
                        Switch(
                            checked = autoChargerEnabled,
                            onCheckedChange = { isChecked ->
                                toggleAutoCharger(isChecked)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF4CAF50))
                        )
                    }
                }
            }
        }

        // --- SAFETY CONFIRMATION DIALOG ---
        if (pendingCommand != null) {
            val isStart = pendingCommand == "START"
            val dialogTitle = if (isStart) "Confirm Generator Start" else "Confirm Generator Shutdown"
            val dialogMessage = if (isStart) 
                "Are you sure you want to initiate the engine start sequence on your Kohler Genset? Ensure the generator area is clear of hazards." 
                else 
                "Are you sure you want to shut down the generator? Any active power output will cease."
            val btnColor = if (isStart) Color(0xFF1B3B22) else Color(0xFF5C2323)
            val actionText = if (isStart) "START GENERATOR" else "STOP GENERATOR"

            AlertDialog(
                onDismissRequest = { pendingCommand = null },
                title = { Text(dialogTitle, color = Color.White, fontWeight = FontWeight.Bold) },
                text = { Text(dialogMessage, color = Color.LightGray) },
                confirmButton = {
                    Button(
                        onClick = {
                            val cmd = pendingCommand!!
                            pendingCommand = null
                            sendCommand(cmd)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = btnColor)
                    ) {
                        Text(actionText, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingCommand = null }) {
                        Text("Cancel", color = Color.LightGray)
                    }
                },
                containerColor = Color(0xFF1E1E1E)
            )
        }

        // --- SETTINGS DIALOG (MOSQUITTO BROKER & THERMOSTAT TUNING) ---
        if (showSettingsDialog) {
            AlertDialog(
                onDismissRequest = { showSettingsDialog = false },
                title = { Text("Settings & Thermostat Tuning", color = Color.White) },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Mosquitto MQTT Broker:", color = Color.White, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = mqttHost,
                            onValueChange = { mqttHost = it },
                            label = { Text("Broker IP Address") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = mqttUsername,
                            onValueChange = { mqttUsername = it },
                            label = { Text("MQTT Username") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = mqttPassword,
                            onValueChange = { mqttPassword = it },
                            label = { Text("MQTT Password") },
                            singleLine = true
                        )

                        HorizontalDivider(color = Color(0xFF333333))
                        Text("120V Coolant Heater Thermostat:", color = Color.White, fontWeight = FontWeight.Bold)

                        OutlinedTextField(
                            value = heaterOnTemp,
                            onValueChange = { heaterOnTemp = it },
                            label = { Text("Low Temp Turn-ON (°F)") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = heaterOffTemp,
                            onValueChange = { heaterOffTemp = it },
                            label = { Text("High Temp Turn-OFF (°F)") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = heaterMaxMins,
                            onValueChange = { heaterMaxMins = it },
                            label = { Text("Max Run Time (Minutes)") },
                            singleLine = true
                        )

                        HorizontalDivider(color = Color(0xFF333333))
                        Text("Victron 48V-to-12V Smart Charger:", color = Color.White, fontWeight = FontWeight.Bold)

                        OutlinedTextField(
                            value = chargerOnVolts,
                            onValueChange = { chargerOnVolts = it },
                            label = { Text("Low Volts Turn-ON (V)") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = chargerOffVolts,
                            onValueChange = { chargerOffVolts = it },
                            label = { Text("High Volts Turn-OFF (V)") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = chargerMaxMins,
                            onValueChange = { chargerMaxMins = it },
                            label = { Text("Max Charge Time (Minutes)") },
                            singleLine = true
                        )

                        HorizontalDivider(color = Color(0xFF333333))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📜 System Event Logs:", color = Color.White, fontWeight = FontWeight.Bold)
                            TextButton(onClick = { systemLogs.clear() }) {
                                Text("Clear", color = Color(0xFFFF5252), fontSize = MaterialTheme.typography.bodySmall.fontSize)
                            }
                        }

                        Surface(
                            color = Color(0xFF121212),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                if (systemLogs.isEmpty()) {
                                    Text("No event logs recorded yet.", color = Color.Gray, fontSize = MaterialTheme.typography.bodySmall.fontSize)
                                } else {
                                    systemLogs.forEach { logLine ->
                                        Text(
                                            text = logLine,
                                            color = when {
                                                logLine.contains("⚠️") || logLine.contains("ERR") || logLine.contains("FAULT") -> Color(0xFFFF5252)
                                                logLine.contains("🟢") || logLine.contains("Connected") -> Color(0xFF66BB6A)
                                                else -> Color.LightGray
                                            },
                                            fontSize = MaterialTheme.typography.bodySmall.fontSize
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        prefs.edit()
                            .putString("mqtt_host", mqttHost.trim())
                            .putString("mqtt_user", mqttUsername.trim())
                            .putString("mqtt_pass", mqttPassword.trim())
                            .putString("heater_on_temp", heaterOnTemp.trim())
                            .putString("heater_off_temp", heaterOffTemp.trim())
                            .putString("heater_max_mins", heaterMaxMins.trim())
                            .putString("charger_on_volts", chargerOnVolts.trim())
                            .putString("charger_off_volts", chargerOffVolts.trim())
                            .putString("charger_max_mins", chargerMaxMins.trim())
                            .putBoolean("auto_charger_enable", autoChargerEnabled)
                            .apply()
                        showSettingsDialog = false
                        connectMqtt()
                        publishHeaterConfig()
                        publishChargerConfig()
                    }) {
                        Text("Save & Apply")
                    }
                },
                containerColor = Color(0xFF1E1E1E)
            )
        }
    }
}

@Composable
fun DashboardGauge(title: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Surface(
        color = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.aspectRatio(1f)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                color = Color.LightGray,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                maxLines = 1
            )

            Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f).fillMaxWidth()) {
                Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    val strokeWidth = 8.dp.toPx()
                    val startAngle = 140f
                    val sweepAngle = 260f

                    drawArc(
                        color = Color(0xFF333333),
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                        size = Size(size.width, size.height),
                        topLeft = Offset(0f, 0f)
                    )

                    val numVal = value.toFloatOrNull() ?: 0f
                    val maxVal = when(title) {
                        "Oil Pressure" -> 100f
                        "Water Temp" -> 250f
                        "Battery" -> 15f
                        else -> 100f
                    }
                    val pct = (numVal / maxVal).coerceIn(0f, 1f)
                    drawArc(
                        color = Color(0xFF4CAF50),
                        startAngle = startAngle,
                        sweepAngle = sweepAngle * pct,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                        size = Size(size.width, size.height),
                        topLeft = Offset(0f, 0f)
                    )
                }

                Text(
                    text = value,
                    color = Color.White,
                    fontSize = MaterialTheme.typography.titleLarge.fontSize,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = unit,
                color = Color.Gray,
                fontSize = MaterialTheme.typography.bodySmall.fontSize
            )
        }
    }
}

// =====================================================================
// 24/7 ULTRA-LOW BATTERY BACKGROUND MONITOR SERVICE
// =====================================================================
class GensetMqttService : Service() {

    private var mqttClient: MqttClient? = null
    private var isConnected = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
        connectBackgroundMqtt()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isConnected) {
            connectBackgroundMqtt()
        }
        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "genset_service_channel",
                "Genset Background Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, "genset_service_channel")
            .setContentTitle("Kohler Genset Monitor")
            .setContentText("24/7 Low-Battery Generator Guard Active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    private fun connectBackgroundMqtt() {
        val prefs = getSharedPreferences("kohler_settings", Context.MODE_PRIVATE)
        val host = prefs.getString("mqtt_host", "100.108.173.127") ?: "100.108.173.127"
        val user = prefs.getString("mqtt_user", "aahotch2001") ?: "aahotch2001"
        val pass = prefs.getString("mqtt_pass", "aaronjulie") ?: "aaronjulie"

        val cleanHost = host.trim().removePrefix("tcp://").removePrefix("http://").substringBefore(":")
        val hostsToTry = mutableListOf<String>()
        if (cleanHost.isNotEmpty()) hostsToTry.add(cleanHost)
        if ("100.108.173.127" !in hostsToTry) hostsToTry.add("100.108.173.127")
        if ("192.168.68.91" !in hostsToTry) hostsToTry.add("192.168.68.91")

        Thread {
            for (targetHost in hostsToTry) {
                try {
                    val serverUri = "tcp://$targetHost:1883"
                    val clientId = "Background_Service_" + System.currentTimeMillis()
                    val client = MqttClient(serverUri, clientId, MemoryPersistence())

                    val opts = MqttConnectOptions().apply {
                        if (user.isNotEmpty()) {
                            userName = user
                            password = pass.toCharArray()
                        }
                        isCleanSession = true
                        connectionTimeout = 5
                        keepAliveInterval = 60 // 60s Keep-Alive: Ultra-low battery consumption!
                    }

                    client.setCallback(object : MqttCallback {
                        private var lastGenStatus = "STOPPED"

                        override fun connectionLost(cause: Throwable?) {
                            isConnected = false
                        }

                        override fun messageArrived(topic: String?, message: MqttMessage?) {
                            val payload = message?.toString() ?: return
                            val topicName = topic ?: ""

                            when (topicName) {
                                "kohler/oil-pressure" -> {
                                    val psi = payload.toFloatOrNull() ?: 0f
                                    if (lastGenStatus.contains("RUNNING", ignoreCase = true) && psi < 15.0f && psi > 0f) {
                                        sendPushNotification(applicationContext, "⚠️ CRITICAL: Low Oil Pressure", "Oil pressure dropped to $payload PSI!")
                                    }
                                }
                                "kohler/water-temp" -> {
                                    val temp = payload.toFloatOrNull() ?: 0f
                                    if (lastGenStatus.contains("RUNNING", ignoreCase = true) && temp > 225.0f) {
                                        sendPushNotification(applicationContext, "⚠️ CRITICAL: Engine Overheating", "Water temp reached $payload °F!")
                                    }
                                }
                                "kohler/battery-voltage" -> {
                                    val v12 = payload.toFloatOrNull() ?: 0f
                                    if (v12 < 11.8f && v12 > 5.0f) {
                                        sendPushNotification(applicationContext, "⚠️ BATTERY WARNING", "12V Starter Battery low ($payload V)!")
                                    }
                                }
                                "kohler/generator-status" -> {
                                    val old = lastGenStatus
                                    lastGenStatus = payload
                                    if (old == "STOPPED" && payload.contains("RUNNING", ignoreCase = true)) {
                                        sendPushNotification(applicationContext, "🟢 Generator Started", "Kohler 60kW Genset is now RUNNING")
                                    } else if (old.contains("RUNNING", ignoreCase = true) && payload == "STOPPED") {
                                        sendPushNotification(applicationContext, "🔴 Generator Stopped", "Kohler 60kW Genset is now STOPPED")
                                    }
                                }
                            }
                        }

                        override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                    })

                    client.connect(opts)
                    mqttClient = client
                    isConnected = true

                    val topics = arrayOf(
                        "kohler/generator-status"
                    )
                    client.subscribe(topics)
                    break
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { mqttClient?.disconnect() } catch (ignored: Exception) {}
        try { mqttClient?.close() } catch (ignored: Exception) {}
    }
}

// =====================================================================
// AUTO-START SERVICE ON PHONE BOOT
// =====================================================================
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, GensetMqttService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
