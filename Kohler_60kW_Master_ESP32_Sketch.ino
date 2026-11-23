#include <ETH.h>
#include <WiFi.h>
#include <PubSubClient.h>
#include <Wire.h>
#include <Adafruit_ADS1X15.h>
#include <Preferences.h>

Preferences prefs;

// =================================================================
// 1. WT32-ETH01 BUILT-IN LAN8720 ETHERNET & NETWORK CONFIG
// =================================================================
#define ETH_ADDR        1
#define ETH_POWER_PIN   16
#define ETH_MDC_PIN     23
#define ETH_MDIO_PIN    18
#define ETH_TYPE        ETH_PHY_LAN8720
#define ETH_CLK_MODE    ETH_CLOCK_GPIO0_IN

// Wi-Fi Credentials (Fallback if RJ45 Ethernet cable is disconnected)
const char* WIFI_SSID     = "YOUR_WIFI_NAME";
const char* WIFI_PASSWORD = "YOUR_WIFI_PASSWORD";

// Local Mosquitto Broker Details (Target IP: 192.168.68.91)
const char* MQTT_SERVER   = "192.168.68.91";
const int   MQTT_PORT     = 1883;
const char* MQTT_USER     = "aahotch2001";
const char* MQTT_PASS     = "aaronjulie";

const float TANK_CAPACITY_GALLONS = 100.0;

// Relay Trigger Logic (Active-LOW Relay Module: LOW ground turns Relay ON, HIGH turns Relay OFF)
#define RELAY_ON  LOW
#define RELAY_OFF HIGH

// Set to true when EG4 18kPV inverter wire is attached to GPIO 36 (set false for bench testing)
#define ENABLE_EG4_INVERTER_AUTOSTART false

// PC817 Optocoupler Engine 70R Logic Level
// Set to HIGH if optocoupler outputs 3.3V when 12V is present, or LOW if it outputs 0V.
#define OPT_ENGINE_RUN_LEVEL  HIGH

// =================================================================
// 2. WT32-ETH01 HARDWARE PIN DEFINITIONS
// =================================================================
#define SDA_PIN 14 // I2C SDA -> ADS1115 SDA (Pin IO14)
#define SCL_PIN 15 // I2C SCL -> ADS1115 SCL (Pin IO15)

#define RELAY_START_PIN  4 // GPIO 4 -> Songle Relay IN1 (Kohler Terminals 3 & 4 - Pin IO4)
#define RELAY_HEATER_PIN 2 // GPIO 2 -> SSR-40DA / Relay Control (Pin IO2)

#define OPT_RPM_SENSE_PIN     5  // GPIO 5 -> PC817 Ch1 Output (Alternator Terminal W RPM & Engine Sense - Pin RXD)
#define OPT_18KPV_PIN         36 // GPIO 36 -> PC817 Ch2 Output (EG4 Inverter - Pin IO36)

// =================================================================
// 3. HARDWARE INTERRUPT PULSE COUNTER
// =================================================================
volatile unsigned long rpmPulseCount = 0;

void IRAM_ATTR rpmPulseISR() {
  rpmPulseCount++;
}

// =================================================================
// 4. HARDWARE OBJECTS & LOCAL KOHLER MQTT TOPICS
// =================================================================
WiFiClient ethWifiClient;
PubSubClient client(ethWifiClient);

Adafruit_ADS1115 ads;

const char* topicCommand     = "kohler/generator-command";
const char* topicStatus      = "kohler/generator-status";
const char* topicOil         = "kohler/oil-pressure";
const char* topicTemp        = "kohler/water-temp";
const char* topicBatt        = "kohler/battery-voltage";
const char* topicSolar48v    = "kohler/solar-battery-48v";
const char* topicFuel        = "kohler/fuel-level";
const char* topicBurnRate    = "kohler/burn-rate";
const char* topicLifetime    = "kohler/lifetime-fuel";
const char* topicRpm         = "kohler/engine-rpm";
const char* topicHeaterCmd   = "kohler/coolant-heater-cmd";
const char* topicHeater      = "kohler/coolant-heater";
const char* topicAutoEnable  = "kohler/heater-auto-enable";
const char* topicSetOnTemp   = "kohler/heater-set-on-temp";
const char* topicSetOffTemp  = "kohler/heater-set-off-temp";
const char* topicSetMaxMins  = "kohler/heater-set-max-mins";

bool ethConnected = false;
bool isGeneratorRunning = false;
bool manualHeaterOn = false;
bool autoHeaterOn = false;
bool autoHeaterEnabled = true;

float heaterOnTemp = 38.0;
float heaterOffTemp = 50.0;
unsigned long heaterMaxRunMinutes = 120;
unsigned long autoHeaterStartTimeMs = 0;

bool adsPresent = false;
String runSource = "Stopped";
unsigned long lastSensorReadTime = 0;

float startRunFuelPct = 100.0;
unsigned long runStartTimeMs = 0;
float currentBurnRateGph = 0.0;
float sessionGallonsBurned = 0.0;
float lifetimeGallonsBurned = 0.0;
int currentRpm = 0;
float currentWaterTemp = 70.0;

// =================================================================
// 5. ETHERNET EVENT HANDLER (WT32-ETH01 LAN8720)
// =================================================================
void WiFiEvent(WiFiEvent_t event) {
  switch (event) {
    case ARDUINO_EVENT_ETH_START:
      Serial.println("WT32-ETH01 Ethernet Started");
      ETH.setHostname("WT32-Kohler-Controller");
      break;
    case ARDUINO_EVENT_ETH_CONNECTED:
      Serial.println("WT32-ETH01 Cable Connected");
      break;
    case ARDUINO_EVENT_ETH_GOT_IP:
      Serial.print("WT32-ETH01 IP Address: ");
      Serial.println(ETH.localIP());
      ethConnected = true;
      break;
    case ARDUINO_EVENT_ETH_DISCONNECTED:
      Serial.println("WT32-ETH01 Cable Disconnected");
      ethConnected = false;
      break;
    case ARDUINO_EVENT_ETH_STOP:
      Serial.println("WT32-ETH01 Ethernet Stopped");
      ethConnected = false;
      break;
    default:
      break;
  }
}

// =================================================================
// 6. MQTT CALLBACK (Receives Commands & Config from Phone App)
// =================================================================
void mqttCallback(char* topic, byte* payload, unsigned int length) {
  String message;
  for (unsigned int i = 0; i < length; i++) {
    message += (char)payload[i];
  }
  message.trim();

  Serial.print("MQTT Received [");
  Serial.print(topic);
  Serial.print("]: ");
  Serial.println(message);

  if (String(topic) == topicCommand) {
    if (message == "START") {
      digitalWrite(RELAY_START_PIN, RELAY_ON); // Engage Start Relay
      runSource = "App Remote Manual";
      client.publish(topicStatus, "CRANKING");
      Serial.println("COMMAND: Engine START Requested -> Relay ENGAGED (CRANKING)");
    } else if (message == "STOP") {
      digitalWrite(RELAY_START_PIN, RELAY_OFF); // Disengage Start Relay
      runSource = "Stopped";
      isGeneratorRunning = false;
      client.publish(topicStatus, "STOPPED");
      Serial.println("COMMAND: Engine STOP Requested -> Relay DISENGAGED (STOPPED)");
    }
  } else if (String(topic) == topicHeaterCmd) {
    if (message == "ON") {
      manualHeaterOn = true;
    } else if (message == "OFF") {
      manualHeaterOn = false;
    }
  } else if (String(topic) == topicAutoEnable) {
    autoHeaterEnabled = (message == "ON");
    prefs.putBool("autoEnable", autoHeaterEnabled);
  } else if (String(topic) == topicSetOnTemp) {
    heaterOnTemp = message.toFloat();
    prefs.putFloat("onTemp", heaterOnTemp);
  } else if (String(topic) == topicSetOffTemp) {
    heaterOffTemp = message.toFloat();
    prefs.putFloat("offTemp", heaterOffTemp);
  } else if (String(topic) == topicSetMaxMins) {
    heaterMaxRunMinutes = message.toInt();
    prefs.putULong("maxMins", heaterMaxRunMinutes);
  }
}

// =================================================================
// 7. SETUP FUNCTION
// =================================================================
void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("\n=======================================================");
  Serial.println(" KOHLER 60kW GENSET - WT32-ETH01 MASTER CONTROLLER");
  Serial.println("=======================================================");

  // Load Saved Thermostat Parameters from NVS Flash
  prefs.begin("kohler_cfg", false);
  autoHeaterEnabled   = prefs.getBool("autoEnable", true);
  heaterOnTemp        = prefs.getFloat("onTemp", 38.0);
  heaterOffTemp       = prefs.getFloat("offTemp", 50.0);
  heaterMaxRunMinutes = prefs.getULong("maxMins", 120);
  lifetimeGallonsBurned = prefs.getFloat("lifetimeFuel", 0.0);

  // Configure Relays & Optocoupler Pins
  pinMode(RELAY_START_PIN, OUTPUT);
  pinMode(RELAY_HEATER_PIN, OUTPUT);
  digitalWrite(RELAY_START_PIN, RELAY_OFF);
  digitalWrite(RELAY_HEATER_PIN, RELAY_OFF);

  pinMode(OPT_RPM_SENSE_PIN, INPUT_PULLUP);
  pinMode(OPT_18KPV_PIN, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(OPT_RPM_SENSE_PIN), rpmPulseISR, FALLING);

  // Enable LAN8720 50MHz Crystal Oscillator Power (GPIO 16)
  pinMode(ETH_POWER_PIN, OUTPUT);
  digitalWrite(ETH_POWER_PIN, HIGH);
  delay(50);

  // Initialize WT32-ETH01 LAN8720 Ethernet PHY
  WiFi.onEvent(WiFiEvent);
#if defined(ESP_ARDUINO_VERSION_MAJOR) && (ESP_ARDUINO_VERSION_MAJOR >= 3)
  ETH.begin(ETH_TYPE, ETH_ADDR, ETH_MDC_PIN, ETH_MDIO_PIN, ETH_POWER_PIN, ETH_CLK_MODE);
#else
  ETH.begin(ETH_ADDR, ETH_POWER_PIN, ETH_MDC_PIN, ETH_MDIO_PIN, ETH_TYPE, ETH_CLK_MODE);
#endif

  // Connect Wi-Fi as Automatic Backup
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  // Initialize ADS1115 16-Bit I2C ADC on GPIO 32 (SDA) and GPIO 33 (SCL)
  Wire.begin(SDA_PIN, SCL_PIN);
  if (ads.begin(0x48)) {
    adsPresent = true;
    ads.setGain(GAIN_ONE); // +/- 4.096V range
    Serial.println("ADS1115 16-Bit ADC Initialized Successfully");
  } else {
    Serial.println("WARNING: ADS1115 ADC Not Detected on I2C Bus!");
  }

  // Setup MQTT Client
  client.setServer(MQTT_SERVER, MQTT_PORT);
  client.setCallback(mqttCallback);
}

// =================================================================
// 8. MQTT RECONNECT LOGIC
// =================================================================
void reconnectMqtt() {
  while (!client.connected()) {
    if (!ethConnected && WiFi.status() != WL_CONNECTED) {
      Serial.println("Waiting for Ethernet / Wi-Fi IP Address...");
      delay(2000);
      continue;
    }
    Serial.print("Connecting to Mosquitto Broker at 192.168.68.91:1883... ");
    String clientId = "WT32_Genset_Controller_";
    clientId += String(random(0xffff), HEX);

    if (client.connect(clientId.c_str(), MQTT_USER, MQTT_PASS)) {
      Serial.println("CONNECTED!");
      client.subscribe(topicCommand);
      client.subscribe(topicHeaterCmd);
      client.subscribe(topicAutoEnable);
      client.subscribe(topicSetOnTemp);
      client.subscribe(topicSetOffTemp);
      client.subscribe(topicSetMaxMins);
    } else {
      Serial.print("Failed, rc=");
      Serial.print(client.state());
      Serial.println(" (Retrying in 3 seconds)");
      delay(3000);
    }
  }
}

// =================================================================
// 9. MAIN LOOP
// =================================================================
void loop() {
  if (!client.connected()) {
    reconnectMqtt();
  }
  client.loop();

  unsigned long now = millis();

  // Smart Adaptive Telemetry Rate: 1s when running/cranking, 10s when stopped (99% packet/battery saver!)
  unsigned long telemetryInterval = (isGeneratorRunning || (digitalRead(RELAY_START_PIN) == RELAY_ON)) ? 1000 : 10000;

  if (now - lastSensorReadTime >= telemetryInterval) {
    lastSensorReadTime = now;

    // --- A. READ OPTOCOUPLERS ---
    bool optInverterStart = (digitalRead(OPT_18KPV_PIN) == LOW);

    // Calculate RPM & Frequency from Hardware Interrupt
    noInterrupts();
    unsigned long pulses = rpmPulseCount;
    rpmPulseCount = 0;
    interrupts();
    currentRpm = (pulses * 60) / 2; // Assuming 2 pulses per revolution

    // Determine engine state from Alternator Terminal W RPM vs Start Relay
    bool startRelayEngaged = (digitalRead(RELAY_START_PIN) == RELAY_ON);
    String currentStatusStr = "STOPPED";
    if (currentRpm > 400) {
      isGeneratorRunning = true;
      if (startRelayEngaged) {
        currentStatusStr = "RUNNING";
      } else {
        // Start relay is disengaged (STOP was pressed), but engine is still spinning in Kohler internal cooldown!
        currentStatusStr = "COOLDOWN";
      }
    } else if (startRelayEngaged) {
      isGeneratorRunning = false;
      currentStatusStr = "CRANKING";
    } else {
      isGeneratorRunning = false;
      currentStatusStr = "STOPPED";
    }

    // Handle Auto-Start from EG4 18kPV Inverter (disabled on bench to prevent floating pin triggers)
    if (ENABLE_EG4_INVERTER_AUTOSTART && optInverterStart && !isGeneratorRunning) {
      digitalWrite(RELAY_START_PIN, RELAY_ON);
      runSource = "EG4 18kPV Auto-Start";
    }

    // --- B. READ ADS1115 ADC SENSORS ---
    float oilPressure = 0.0;
    float batteryVoltage = 0.0;
    float solar48v = 0.0;
    float fuelLevelPct = 100.0;

    if (adsPresent) {
      int16_t adc0 = ads.readADC_SingleEnded(0); // A0: Oil Pressure
      int16_t adc1 = ads.readADC_SingleEnded(1); // A1: Water Temp
      int16_t adc2 = ads.readADC_SingleEnded(2); // A2: 12V Battery
      int16_t adc3 = ads.readADC_SingleEnded(3); // A3: 48V Solar Bank

      float volts0 = ads.computeVolts(adc0);
      float volts1 = ads.computeVolts(adc1);
      float volts2 = ads.computeVolts(adc2);
      float volts3 = ads.computeVolts(adc3);

      oilPressure = (volts0 - 0.5) * (100.0 / 4.0);
      if (oilPressure < 0 || volts0 < 0.2) oilPressure = 0;

      // Kohler NTC Temp Sender Inverse Calibration (High Voltage = Cold, Low Voltage = Hot)
      currentWaterTemp = 240.0 - (volts1 * 49.0);
      if (volts1 < 0.1 || currentWaterTemp < 0) currentWaterTemp = 0.0;

      batteryVoltage = volts2 * 6.047;       // Calibrated for 10k/2.2k divider (13.60V actual vs 10.22V displayed)
      if (volts2 < 0.3) batteryVoltage = 0.0; // Noise filter for unconnected bench pin

      solar48v = volts3 * (46.7 / 2.7);       // 44k (two 22k in series) + 2.7k divider ratio
      if (volts3 < 0.3) solar48v = 0.0;       // Noise filter for unconnected bench pin
    }

    // --- C. COOLANT HEATER THERMOSTAT CONTROL ---
    // Auto-Freeze thermostat only triggers if ADS1115 sensor is present and reading valid temp (>1.0 F)
    if (autoHeaterEnabled && !isGeneratorRunning && adsPresent && currentWaterTemp > 1.0) {
      if (currentWaterTemp <= heaterOnTemp) {
        if (!autoHeaterOn) {
          autoHeaterOn = true;
          autoHeaterStartTimeMs = now;
        }
      } else if (currentWaterTemp >= heaterOffTemp) {
        autoHeaterOn = false;
      }

      // Safety Max Runtime Protection
      if (autoHeaterOn && (now - autoHeaterStartTimeMs >= (heaterMaxRunMinutes * 60000))) {
        autoHeaterOn = false;
        Serial.println("THERMOSTAT: Max runtime safety limit reached! Heater turned OFF.");
      }
    } else {
      autoHeaterOn = false;
    }

    // Drive Coolant Heater Relay Output (GPIO 12)
    bool finalHeaterState = manualHeaterOn || autoHeaterOn;
    digitalWrite(RELAY_HEATER_PIN, finalHeaterState ? RELAY_ON : RELAY_OFF);

    // --- D. PUBLISH LIVE TELEMETRY TO MOSQUITTO BROKER ---
    client.publish(topicStatus, currentStatusStr.c_str());
    client.publish(topicOil, String(oilPressure, 1).c_str());
    client.publish(topicTemp, String(currentWaterTemp, 1).c_str());
    client.publish(topicBatt, String(batteryVoltage, 2).c_str());
    client.publish(topicSolar48v, String(solar48v, 1).c_str());
    client.publish(topicFuel, String(fuelLevelPct, 0).c_str());
    client.publish(topicBurnRate, String(currentBurnRateGph, 1).c_str());
    client.publish(topicLifetime, String(lifetimeGallonsBurned, 1).c_str());
    client.publish(topicRpm, String(currentRpm).c_str());
    client.publish(topicHeater, finalHeaterState ? "ON" : "OFF");
  }
}
