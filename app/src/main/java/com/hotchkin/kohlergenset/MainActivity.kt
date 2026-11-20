package com.hotchkin.kohlergenset

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.hotchkin.kohlergenset.ui.theme.KohlerGensetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KohlerGensetTheme(darkTheme = true) {
                GeneratorAppScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneratorAppScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("kohler_settings", Context.MODE_PRIVATE) }

    // Mosquitto Broker Settings State (Default placeholders for public safety)
    var mqttHost by remember { mutableStateOf(prefs.getString("mqtt_host", "192.168.1.172") ?: "192.168.1.172") }
    var mqttUsername by remember { mutableStateOf(prefs.getString("mqtt_user", "YOUR_MQTT_USER") ?: "YOUR_MQTT_USER") }
    var mqttPassword by remember { mutableStateOf(prefs.getString("mqtt_pass", "YOUR_MQTT_PASS") ?: "YOUR_MQTT_PASS") }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Thermostat & Heater Custom Settings State
    var autoHeaterEnabled by remember { mutableStateOf(prefs.getBoolean("auto_heater_enable", true)) }
    var heaterOnTemp by remember { mutableStateOf(prefs.getString("heater_on_temp", "38") ?: "38") }
    var heaterOffTemp by remember { mutableStateOf(prefs.getString("heater_off_temp", "50") ?: "50") }
    var heaterMaxMins by remember { mutableStateOf(prefs.getString("heater_max_mins", "120") ?: "120") }

    // Live Telemetry States
    var oilPressure by remember { mutableStateOf("0") }
    var waterTemp by remember { mutableStateOf("0") }
    var batteryVoltage by remember { mutableStateOf("0.0") }
    var solarBattery48v by remember { mutableStateOf("0.0") }
    var fuelLevelPct by remember { mutableStateOf("0") }
    var burnRateGph by remember { mutableStateOf("0.0") }
    var lifetimeFuelGal by remember { mutableStateOf("0.0") }
    var engineRpm by remember { mutableStateOf("0") }
    var generatorStatus by remember { mutableStateOf("STOPPED") }
    var coolantHeaterOn by remember { mutableStateOf(false) }

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
                mqttClient?.disconnect()
                mqttClient?.close()
                
                val serverUri = "tcp://$mqttHost:1883"
                val clientId = "Android_App_" + System.currentTimeMillis()
                val client = MqttClient(serverUri, clientId, MemoryPersistence())
                
                val options = MqttConnectOptions().apply {
                    if (mqttUsername.isNotEmpty()) {
                        userName = mqttUsername
                        password = mqttPassword.toCharArray()
                    }
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 30
                }

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
                                "kohler/oil-pressure" -> oilPressure = payload
                                "kohler/water-temp" -> waterTemp = payload
                                "kohler/battery-voltage" -> batteryVoltage = payload
                                "kohler/solar-battery-48v" -> solarBattery48v = payload
                                "kohler/fuel-level" -> fuelLevelPct = payload
                                "kohler/burn-rate" -> burnRateGph = payload
                                "kohler/lifetime-fuel" -> lifetimeFuelGal = payload
                                "kohler/engine-rpm" -> engineRpm = payload
                                "kohler/generator-status" -> generatorStatus = payload
                                "kohler/coolant-heater" -> coolantHeaterOn = payload.contains("ON", ignoreCase = true)
                            }
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                client.connect(options)
                mqttClient = client

                // Subscribe to all kohler topics
                val topics = arrayOf(
                    "kohler/oil-pressure",
                    "kohler/water-temp",
                    "kohler/battery-voltage",
                    "kohler/solar-battery-48v",
                    "kohler/fuel-level",
                    "kohler/burn-rate",
                    "kohler/lifetime-fuel",
                    "kohler/engine-rpm",
                    "kohler/generator-status",
                    "kohler/coolant-heater"
                )
                client.subscribe(topics)

                coroutineScope.launch(Dispatchers.Main) {
                    generatorStatus = "CONNECTED"
                }

            } catch (e: Exception) {
                e.printStackTrace()
                coroutineScope.launch(Dispatchers.Main) {
                    generatorStatus = "CONN ERROR"
                }
            }
        }
    }

    // Auto connect on launch
    LaunchedEffect(mqttHost, mqttUsername, mqttPassword) {
        connectMqtt()
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
                        generatorStatus = if (cmd == "START") "SENDING START..." else "SENDING STOP..."
                    }

                    delay(8000)
                    coroutineScope.launch(Dispatchers.Main) {
                        if (generatorStatus.startsWith("SENDING")) {
                            generatorStatus = "CONNECTED"
                        }
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
                        else -> Color(0xFF2A2A2A)
                    }
                    val textColor = when {
                        generatorStatus.contains("RUNNING", ignoreCase = true) -> Color(0xFF4CAF50)
                        else -> Color.LightGray
                    }
                    
                    Surface(
                        color = badgeColor,
                        shape = RoundedCornerShape(16.dp),
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
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // GAUGES ROW (Oil, Water, Battery)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DashboardGauge(title = "Oil Pressure", value = oilPressure, unit = "psi", modifier = Modifier.weight(1f))
                DashboardGauge(title = "Water Temp", value = waterTemp, unit = "°F", modifier = Modifier.weight(1f))
                DashboardGauge(title = "Battery", value = batteryVoltage, unit = "V", modifier = Modifier.weight(1f))
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

            Spacer(modifier = Modifier.weight(1f))

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
                        .height(100.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF1B3B22),
                        contentColor = Color.White
                    ),
                    border = null
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("START", fontSize = MaterialTheme.typography.titleLarge.fontSize, color = Color.White, fontWeight = FontWeight.Bold)
                        Text("Start Generator", fontSize = MaterialTheme.typography.bodySmall.fontSize, color = Color.LightGray)
                    }
                }

                // STOP Button
                OutlinedButton(
                    onClick = { pendingCommand = "STOP" },
                    modifier = Modifier
                        .weight(1f)
                        .height(100.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF5C2323),
                        contentColor = Color.White
                    ),
                    border = null
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("STOP", fontSize = MaterialTheme.typography.titleLarge.fontSize, color = Color.White, fontWeight = FontWeight.Bold)
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
                            .apply()
                        showSettingsDialog = false
                        connectMqtt()
                        publishHeaterConfig()
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
