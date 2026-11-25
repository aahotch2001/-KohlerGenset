#include <SPI.h>
#include <Ethernet.h>
#include <WiFi.h>
#include <PubSubClient.h>
#include <Wire.h>
#include <Adafruit_ADS1X15.h>
#include <Preferences.h>

Preferences prefs;

// =================================================================
// 1. ELEGOO ESP-32 DUAL NETWORK CONFIG (ETHERNET + WI-FI BACKUP)
// =================================================================
// Wi-Fi Credentials (Automatic backup if Ethernet cable is unplugged)
const char* WIFI_SSID     = "Hotchkin";
const char* WIFI_PASSWORD = "aaronjulie";

// Local Mosquitto Broker Details (Target IP: 192.168.68.91)
const char* MQTT_SERVER   = "192.168.68.91";
const int   MQTT_PORT     = 1883;
const char* MQTT_USER     = "aahotch2001";
const char* MQTT_PASS     = "aaronjulie";

const float TANK_CAPACITY_GALLONS = 500.0; // 500-Gallon Tank Capacity

// Relay Trigger Logic (Active-LOW Relay Module: LOW ground turns Relay ON, HIGH turns Relay OFF)
#define RELAY_ON  LOW
#define RELAY_OFF HIGH

// PRODUCTION MODE: EG4 18kPV Inverter Passive Auto-Start Sense ENABLED!
#define ENABLE_EG4_INVERTER_AUTOSTART true

// =================================================================
// 2. ELEGOO ESP-32 HARDWARE PIN DEFINITIONS (USER BREAKOUT BOARD)
// =================================================================
#define SDA_PIN 21 // Native Hardware I2C SDA -> ADS1115 SDA (Pin D21)
#define SCL_PIN 22 // Native Hardware I2C SCL -> ADS1115 SCL (Pin D22)

#define RELAY_START_PIN     26 // GPIO 26 -> Songle Relay IN1 (Kohler Terminals 3 & 4 - Pin D26)
#define RELAY_HEATER_PIN    27 // GPIO 27 -> Songle Relay IN2 (120V Coolant Heater - Pin D27)
#define VICTRON_CHARGER_PIN 13 // GPIO 13 -> Direct 3.3V Signal to Victron Remote Pin 'H' (Pin D13)
#define DS18B20_TEMP_PIN    25 // GPIO 25 -> DS18B20 3-Wire Digital Temp Sensor (Pin D25)

#define OPT_RPM_SENSE_PIN   18 // GPIO 18 -> PC817 Ch1 Output (Alternator Terminal W RPM - Pin D18)
#define OPT_18KPV_PIN       19 // GPIO 19 -> PC817 Ch2 Output (EG4 Inverter Auto-Start Sense - Pin D19)

// W5500 SPI Ethernet Module Pins:
#define W5500_SCLK_PIN 14 // Pin D14 (GPIO 14 - SPI Clock)
#define W5500_MISO_PIN 12 // Pin D12 (GPIO 12 - SPI MISO)
#define W5500_MOSI_PIN 23 // Pin D23 (GPIO 23 - SPI MOSI)
#define W5500_CS_PIN   5  // Pin D5  (GPIO 5  - SPI SCS/CS)
#define W5500_RST_PIN  4  // Pin D4  (GPIO 4  - W5500 Hardware Reset Pin)

// =================================================================
// 3. HARDWARE INTERRUPT PULSE COUNTER & DS18B20 SENSOR READ
// =================================================================
volatile unsigned long rpmPulseCount = 0;

void IRAM_ATTR rpmPulseISR() {
  rpmPulseCount++;
}

// Simple 1-Wire Bit-Bang Temperature Reader for DS18B20 Digital Thermometer on GPIO 25
float readDS18B20TempF() {
  uint8_t pin = DS18B20_TEMP_PIN;
  pinMode(pin, OUTPUT);
  digitalWrite(pin, LOW);
  delayMicroseconds(500);
  pinMode(pin, INPUT_PULLUP);
  delayMicroseconds(70);
  if (digitalRead(pin) == HIGH) return 0.0; // Sensor not present
  delayMicroseconds(430);

  // Write 0xCC (Skip ROM) & 0x44 (Convert T)
  uint8_t cmds[2] = {0xCC, 0x44};
  for (int c = 0; c < 2; c++) {
    uint8_t byteVal = cmds[c];
    for (int b = 0; b < 8; b++) {
      pinMode(pin, OUTPUT);
      digitalWrite(pin, LOW);
      if (byteVal & (1 << b)) {
        delayMicroseconds(6);
        pinMode(pin, INPUT_PULLUP);
        delayMicroseconds(64);
      } else {
        delayMicroseconds(60);
        pinMode(pin, INPUT_PULLUP);
        delayMicroseconds(10);
      }
    }
  }

  // DS18B20 12-Bit Temperature Conversion Delay (750ms required by Dallas/Maxim datasheet!)
  delay(750);

  // Reset & Read Scratchpad (0xCC, 0xBE)
  pinMode(pin, OUTPUT);
  digitalWrite(pin, LOW);
  delayMicroseconds(500);
  pinMode(pin, INPUT_PULLUP);
  delayMicroseconds(70);
  if (digitalRead(pin) == HIGH) return 0.0;
  delayMicroseconds(430);

  uint8_t rcmds[2] = {0xCC, 0xBE};
  for (int c = 0; c < 2; c++) {
    uint8_t byteVal = rcmds[c];
    for (int b = 0; b < 8; b++) {
      pinMode(pin, OUTPUT);
      digitalWrite(pin, LOW);
      if (byteVal & (1 << b)) {
        delayMicroseconds(6);
        pinMode(pin, INPUT_PULLUP);
        delayMicroseconds(64);
      } else {
        delayMicroseconds(60);
        pinMode(pin, INPUT_PULLUP);
        delayMicroseconds(10);
      }
    }
  }

  // Read 16-bit temperature
  uint16_t tempRaw = 0;
  for (int b = 0; b < 16; b++) {
    pinMode(pin, OUTPUT);
    digitalWrite(pin, LOW);
    delayMicroseconds(3);
    pinMode(pin, INPUT_PULLUP);
    delayMicroseconds(10);
    if (digitalRead(pin) == HIGH) {
      tempRaw |= (1 << b);
    }
    delayMicroseconds(53);
  }

  float tempC = (int16_t)tempRaw * 0.0625;
  if (tempC < -55.0 || tempC > 125.0) return 0.0;

  float rawF = (tempC * 9.0 / 5.0) + 32.0;
  return rawF;
}

// =================================================================
// 4. HARDWARE OBJECTS & LOCAL KOHLER MQTT TOPICS
// =================================================================
EthernetClient ethClient;
WiFiClient wifiClient;
PubSubClient client;

Adafruit_ADS1115 ads1; // Address 0x48 (Engine Sensors & Voltages)
Adafruit_ADS1115 ads2; // Address 0x49 (5 PSI Hydrostatic Fuel Transducer)

const char* topicCommand     = "kohler/generator-command";
const char* topicStatus      = "kohler/generator-status";
const char* topicOil         = "kohler/oil-pressure";
const char* topicTemp        = "kohler/water-temp";
const char* topicBlockTemp   = "kohler/block-temp";
const char* topicBatt        = "kohler/battery-voltage";
const char* topicBattHealth  = "kohler/battery-health";
const char* topicSolar48v    = "kohler/solar-battery-48v";
const char* topicFuel        = "kohler/fuel-level";
const char* topicBurnRate    = "kohler/burn-rate";
const char* topicLifetime    = "kohler/lifetime-fuel";
const char* topicRpm         = "kohler/engine-rpm";
const char* topicHeaterCmd   = "kohler/coolant-heater-cmd";
const char* topicHeater      = "kohler/coolant-heater";
const char* topicChargerCmd  = "kohler/victron-charger-cmd";
const char* topicCharger     = "kohler/victron-charger";
const char* topicAutoEnable  = "kohler/heater-auto-enable";
const char* topicSetOnTemp   = "kohler/heater-set-on-temp";
const char* topicSetOffTemp  = "kohler/heater-set-off-temp";
const char* topicSetMaxMins  = "kohler/heater-set-max-mins";

const char* topicChargerAutoEnable = "kohler/charger-auto-enable";
const char* topicChargerSetOnVolts = "kohler/charger-set-on-volts";
const char* topicChargerSetOffVolts = "kohler/charger-set-off-volts";
const char* topicChargerSetMaxMins = "kohler/charger-set-max-mins";

bool isGeneratorRunning = false;
bool manualHeaterOn = false;
bool autoHeaterOn = false;
bool autoHeaterEnabled = true;

bool manualChargerOn = false;
bool autoChargerOn = false;
bool autoChargerEnabled = true;

float heaterOnTemp = 38.0;
float heaterOffTemp = 50.0;
unsigned long heaterMaxRunMinutes = 120;
unsigned long autoHeaterStartTimeMs = 0;

float chargerOnVolts = 12.2;
float chargerOffVolts = 13.8;
unsigned long chargerMaxRunMinutes = 120;
unsigned long autoChargerStartTimeMs = 0;

float minCrankVolts = 15.0;
String battHealthStr = "HEALTHY";

bool ads1Present = false;
bool ads2Present = false;
bool w5500Present = false;
String runSource = "Stopped";
unsigned long lastSensorReadTime = 0;
unsigned long crankStartTimeMs = 0;
const unsigned long MAX_CRANK_TIME_MS = 15000; // 15-second over-crank safety limit

float startRunFuelPct = 100.0;
unsigned long runStartTimeMs = 0;
float currentBurnRateGph = 0.0;
float sessionGallonsBurned = 0.0;
float lifetimeGallonsBurned = 0.0;
int currentRpm = 0;
float currentWaterTemp = 70.0;
float currentBlockTemp = 0.0;

// Hydrostatic Real-Time Transducer Burn Rate Memory
float prevFuelGallons = -1.0;
unsigned long prevFuelTimeMs = 0;

// =================================================================
// 5. MQTT CALLBACK (Receives Commands & Config from Phone App)
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
      crankStartTimeMs = millis();
      runSource = "App Remote Manual";
      client.publish(topicStatus, "CRANKING");
      Serial.println("COMMAND: Engine START Requested -> Relay ENGAGED (CRANKING)");
    } else if (message == "STOP") {
      digitalWrite(RELAY_START_PIN, RELAY_OFF); // Disengage Start Relay IMMEDIATELY
      runSource = "Stopped";
      isGeneratorRunning = false;
      client.publish(topicStatus, "STOPPED");
      Serial.println("COMMAND: Engine STOP Requested -> Relay DISENGAGED (STOPPED)");
    }
  } else if (String(topic) == topicHeaterCmd) {
    manualHeaterOn = (message == "ON");
  } else if (String(topic) == topicChargerCmd) {
    manualChargerOn = (message == "ON");
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
  } else if (String(topic) == topicChargerAutoEnable) {
    autoChargerEnabled = (message == "ON");
    prefs.putBool("autoChgEna", autoChargerEnabled);
  } else if (String(topic) == topicChargerSetOnVolts) {
    chargerOnVolts = message.toFloat();
    prefs.putFloat("chgOnV", chargerOnVolts);
  } else if (String(topic) == topicChargerSetOffVolts) {
    chargerOffVolts = message.toFloat();
    prefs.putFloat("chgOffV", chargerOffVolts);
  } else if (String(topic) == topicChargerSetMaxMins) {
    chargerMaxRunMinutes = message.toInt();
    prefs.putULong("chgMaxM", chargerMaxRunMinutes);
  }
}

// =================================================================
// 6. SETUP FUNCTION
// =================================================================
void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("\n=======================================================");
  Serial.println(" KOHLER 60kW GENSET - ELEGOO ESP32 MASTER CONTROLLER");
  Serial.println("=======================================================");

  // Load Saved Thermostat & Charger Parameters from NVS Flash
  prefs.begin("kohler_cfg", false);
  autoHeaterEnabled     = prefs.getBool("autoEnable", true);
  heaterOnTemp          = prefs.getFloat("onTemp", 38.0);
  heaterOffTemp         = prefs.getFloat("offTemp", 50.0);
  heaterMaxRunMinutes   = prefs.getULong("maxMins", 120);

  autoChargerEnabled    = prefs.getBool("autoChgEna", true);
  chargerOnVolts        = prefs.getFloat("chgOnV", 12.2);
  chargerOffVolts       = prefs.getFloat("chgOffV", 13.8);
  chargerMaxRunMinutes  = prefs.getULong("chgMaxM", 120);

  lifetimeGallonsBurned = prefs.getFloat("lifetimeFuel", 0.0);

  // Configure Relays & Victron Control Pins
  pinMode(RELAY_START_PIN, OUTPUT);
  pinMode(RELAY_HEATER_PIN, OUTPUT);
  pinMode(VICTRON_CHARGER_PIN, OUTPUT);

  digitalWrite(RELAY_START_PIN, RELAY_OFF);
  digitalWrite(RELAY_HEATER_PIN, RELAY_OFF);
  digitalWrite(VICTRON_CHARGER_PIN, LOW); // Victron Charger OFF at boot (0mA standby)

  // Configure Optocoupler & Digital Temp Sensor Pins
  pinMode(OPT_RPM_SENSE_PIN, INPUT_PULLUP);
  pinMode(OPT_18KPV_PIN, INPUT_PULLUP);
  pinMode(DS18B20_TEMP_PIN, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(OPT_RPM_SENSE_PIN), rpmPulseISR, FALLING);

  // Hardware Reset Pulse for W5500 SPI Ethernet Module on D4 (GPIO 4)
  pinMode(W5500_RST_PIN, OUTPUT);
  digitalWrite(W5500_RST_PIN, LOW);  // Hold W5500 in hardware reset
  delay(50);
  digitalWrite(W5500_RST_PIN, HIGH); // Release W5500 from reset
  delay(50);

  // Initialize W5500 SPI Ethernet Module on Pins D14 (SCLK), D23 (MOSI), D12 (MISO), D5 (CS)
  pinMode(W5500_CS_PIN, OUTPUT);
  SPI.begin(W5500_SCLK_PIN, W5500_MISO_PIN, W5500_MOSI_PIN, W5500_CS_PIN);
  Ethernet.init(W5500_CS_PIN);

  byte mac[] = { 0xDE, 0xAD, 0xBE, 0xEF, 0xFE, 0xED };
  IPAddress ip(192, 168, 68, 199);
  IPAddress gateway(192, 168, 68, 1);
  IPAddress subnet(255, 255, 255, 0);
  IPAddress dns(192, 168, 68, 1);

  Serial.println("Initializing W5500 Hardwired Cat6 Ethernet (IP: 192.168.68.199)...");
  Ethernet.begin(mac, ip, dns, gateway, subnet);

  if (Ethernet.hardwareStatus() == EthernetNoHardware || Ethernet.localIP() == IPAddress(0, 0, 0, 0)) {
    Serial.println("W5500 Ethernet SPI inactive or searching. Enabling Wi-Fi backup ('Hotchkin')...");
    w5500Present = false;
    client.setClient(wifiClient);
    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  } else {
    w5500Present = true;
    client.setClient(ethClient);
    Serial.print("W5500 Hardwired Ethernet ACTIVE! IP Address: ");
    Serial.println(Ethernet.localIP());
  }

  // Initialize DUAL ADS1115 16-Bit I2C ADCs on GPIO 21 (SDA) and GPIO 22 (SCL)
  Wire.begin(SDA_PIN, SCL_PIN);
  if (ads1.begin(0x48)) { // ADS1115 #1 (Engine Sensors & Voltages)
    ads1Present = true;
    ads1.setGain(GAIN_ONE); // +/- 4.096V range
    Serial.println("ADS1115 #1 (0x48 - Engine Sensors) Initialized");
  }

  if (ads2.begin(0x49)) { // ADS1115 #2 (5 PSI Hydrostatic Fuel Transducer)
    ads2Present = true;
    ads2.setGain(GAIN_ONE); // +/- 4.096V range
    Serial.println("ADS1115 #2 (0x49 - 16-Bit Fuel Transducer) Initialized");
  }

  // Setup MQTT Client
  client.setServer(MQTT_SERVER, MQTT_PORT);
  client.setCallback(mqttCallback);
}

// =================================================================
// 7. MQTT RECONNECT LOGIC
// =================================================================
void reconnectMqtt() {
  while (!client.connected()) {
    if (!w5500Present && WiFi.status() != WL_CONNECTED) {
      Serial.println("Waiting for Network Connection...");
      delay(2000);
      continue;
    }
    Serial.print("Connecting to Mosquitto Broker at 192.168.68.91:1883... ");
    String clientId = "ELEGOO_Genset_Controller_";
    clientId += String(random(0xffff), HEX);

    if (client.connect(clientId.c_str(), MQTT_USER, MQTT_PASS)) {
      Serial.println("CONNECTED!");
      client.subscribe(topicCommand);
      client.subscribe(topicHeaterCmd);
      client.subscribe(topicChargerCmd);
      client.subscribe(topicAutoEnable);
      client.subscribe(topicSetOnTemp);
      client.subscribe(topicSetOffTemp);
      client.subscribe(topicSetMaxMins);
      client.subscribe(topicChargerAutoEnable);
      client.subscribe(topicChargerSetOnVolts);
      client.subscribe(topicChargerSetOffVolts);
      client.subscribe(topicChargerSetMaxMins);
    } else {
      Serial.print("Failed, rc=");
      Serial.print(client.state());
      Serial.println(" (Retrying in 3 seconds)");
      delay(3000);
    }
  }
}

// =================================================================
// 8. MAIN LOOP
// =================================================================
void loop() {
  if (!client.connected()) {
    reconnectMqtt();
  }
  client.loop();

  unsigned long now = millis();

  // Smart Adaptive Telemetry Rate: 1s when running/cranking, 10s when stopped (99% packet/battery saver!)
  bool startRelayEngaged = (digitalRead(RELAY_START_PIN) == RELAY_ON);
  unsigned long telemetryInterval = (isGeneratorRunning || startRelayEngaged) ? 1000 : 10000;

  if (now - lastSensorReadTime >= telemetryInterval) {
    lastSensorReadTime = now;

    // --- A. READ OPTOCOUPLERS & DS18B20 DIGITAL TEMP SENSOR ---
    bool optInverterStart = (digitalRead(OPT_18KPV_PIN) == LOW);
    currentBlockTemp = readDS18B20TempF(); // Read 3-wire digital thermometer on GPIO 25

    // Calculate RPM & Frequency from Hardware Interrupt
    noInterrupts();
    unsigned long pulses = rpmPulseCount;
    rpmPulseCount = 0;
    interrupts();

    // Calibrated divisor = 45 for exact 1755 RPM / 58.5 Hz actual (from 3630 RPM / 121 Hz raw)
    currentRpm = (pulses * 60) / 45;

    // 15-SECOND OVER-CRANK SAFETY PROTECTION
    if (startRelayEngaged && (currentRpm <= 400) && (now - crankStartTimeMs >= MAX_CRANK_TIME_MS)) {
      digitalWrite(RELAY_START_PIN, RELAY_OFF); // Disengage start relay for starter protection!
      startRelayEngaged = false;
      client.publish(topicStatus, "OVERCRANK FAULT");
      Serial.println("SAFETY FAULT: Starter crank exceeded 15 seconds! Relay DISENGAGED.");
    }

    // Determine engine state from Alternator Terminal W RPM vs Start Relay / Inverter Start Sense
    String currentStatusStr = "STOPPED";
    if (currentRpm > 400) {
      isGeneratorRunning = true;
      if (startRelayEngaged || optInverterStart) {
        currentStatusStr = "RUNNING"; // Displays RUNNING when started by inverter or manual!
      } else {
        currentStatusStr = "COOLDOWN";
      }
    } else if (startRelayEngaged) {
      isGeneratorRunning = false;
      currentStatusStr = "CRANKING";
    } else {
      isGeneratorRunning = false;
      currentStatusStr = "STOPPED";
    }

    // PASSIVE INVERTER AUTO-START SENSING (Inverter closes Kohler Terminals 3 & 4 directly!)
    if (ENABLE_EG4_INVERTER_AUTOSTART && optInverterStart) {
      runSource = "EG4 18kPV Auto-Start";
    }

    // --- B. READ ADS1115 ADC #1 (0x48) ENGINE SENSORS & VOLTAGES ---
    float oilPressure = 0.0;
    float batteryVoltage = 0.0;
    float solar48v = 0.0;

    if (ads1Present) {
      int16_t adc0 = ads1.readADC_SingleEnded(0); // A0: Oil Pressure
      int16_t adc1 = ads1.readADC_SingleEnded(1); // A1: Water Temp
      int16_t adc2 = ads1.readADC_SingleEnded(2); // A2: 12V Battery
      int16_t adc3 = ads1.readADC_SingleEnded(3); // A3: 48V Solar Bank

      float volts0 = ads1.computeVolts(adc0);
      float volts1 = ads1.computeVolts(adc1);
      float volts2 = ads1.computeVolts(adc2);
      float volts3 = ads1.computeVolts(adc3);

      // Calibrated for 45.0 PSI actual (from 48.0 PSI raw)
      oilPressure = (volts0 - 0.5) * 12.513;
      if (oilPressure < 0 || volts0 < 0.2) oilPressure = 0;

      // Kohler NTC Temp Sender Inverse Calibration (140°F actual when running)
      currentWaterTemp = 240.0 - (volts1 * 48.0);
      if (volts1 < 0.1 || currentWaterTemp < 0) currentWaterTemp = 0.0;

      // Calibrated for 13.15V actual multimeter reading
      batteryVoltage = volts2 * 5.598;
      if (volts2 < 0.3) batteryVoltage = 0.0; // Noise filter for unconnected bench pin

      // Cranking Voltage Sag & Battery Health Tracking
      if (startRelayEngaged && batteryVoltage > 5.0) {
        if (batteryVoltage < minCrankVolts) {
          minCrankVolts = batteryVoltage;
        }
      }
      if (minCrankVolts >= 10.0 || batteryVoltage >= 12.5) {
        battHealthStr = "HEALTHY";
      } else if (minCrankVolts >= 9.0) {
        battHealthStr = "FAIR";
      } else if (minCrankVolts < 9.0 && minCrankVolts > 5.0) {
        battHealthStr = "WEAK";
      }

      // Calibrated for 51.0V actual multimeter reading
      solar48v = volts3 * 18.663;
      if (volts3 < 0.3) solar48v = 0.0; // Noise filter for unconnected bench pin
    }

    // --- C. READ ADS1115 ADC #2 (0x49) 16-BIT 5 PSI HYDROSTATIC FUEL TRANSDUCER & BURN RATE ---
    float fuelLevelPct = 100.0;
    if (ads2Present) {
      int16_t fuelAdc = ads2.readADC_SingleEnded(0); // 2nd ADS1115 Pin A0 (0.5V - 4.5V Direct Input!)
      float fuelVolts = ads2.computeVolts(fuelAdc);

      if (fuelVolts >= 0.45) {
        // Calibrated 500-Gallon Hydrostatic Scale (1.40V max span for 3-4ft tank depth)
        fuelLevelPct = ((fuelVolts - 0.5) / 1.40) * 100.0;
        if (fuelLevelPct <= 1.5) fuelLevelPct = 0.0; // Empty tank threshold
        if (fuelLevelPct > 100.0) fuelLevelPct = 100.0;
      } else {
        fuelLevelPct = 0.0; // Sensor disconnected / empty
      }
    }

    // REAL-TIME HYDROSTATIC TRANSDUCER BURN RATE (GPH) CALCULATION
    float currentFuelGallons = (fuelLevelPct / 100.0) * TANK_CAPACITY_GALLONS;
    if (isGeneratorRunning) {
      if (prevFuelGallons < 0.0) {
        prevFuelGallons = currentFuelGallons;
        prevFuelTimeMs = now;
        currentBurnRateGph = 2.4; // Initial John Deere 1800 RPM nominal rate
      } else {
        unsigned long elapsedMs = now - prevFuelTimeMs;
        if (elapsedMs >= 60000) { // Measure rolling 1-minute transducer fuel consumption
          float gallonsBurned = prevFuelGallons - currentFuelGallons;
          float hoursElapsed = elapsedMs / 3600000.0;

          if (gallonsBurned >= 0.0 && gallonsBurned < 10.0) {
            currentBurnRateGph = gallonsBurned / hoursElapsed; // Measured GPH from Transducer!
            sessionGallonsBurned += gallonsBurned;
            lifetimeGallonsBurned += gallonsBurned;
            prefs.putFloat("lifetimeFuel", lifetimeGallonsBurned);
          } else {
            // If fuel was extracted for tractors or added, fallback to John Deere nominal rate
            currentBurnRateGph = 2.4;
          }

          prevFuelGallons = currentFuelGallons;
          prevFuelTimeMs = now;
        }
      }
    } else {
      currentBurnRateGph = 0.0; // 0.0 GPH when generator is stopped
      prevFuelGallons = -1.0;
    }

    // --- D. COOLANT HEATER THERMOSTAT CONTROL (Uses 3-Wire Digital Temp Sensor!) ---
    float activeThermostatTemp = (currentBlockTemp > 1.0) ? currentBlockTemp : currentWaterTemp;
    if (autoHeaterEnabled && !isGeneratorRunning && ads1Present && activeThermostatTemp > 1.0) {
      if (activeThermostatTemp <= heaterOnTemp) {
        if (!autoHeaterOn) {
          autoHeaterOn = true;
          autoHeaterStartTimeMs = now;
        }
      } else if (activeThermostatTemp >= heaterOffTemp) {
        autoHeaterOn = false;
      }

      if (autoHeaterOn && (now - autoHeaterStartTimeMs >= (heaterMaxRunMinutes * 60000))) {
        autoHeaterOn = false;
        Serial.println("THERMOSTAT: Max runtime safety limit reached! Heater turned OFF.");
      }
    } else {
      autoHeaterOn = false;
    }

    // Drive Coolant Heater Relay Output (GPIO 27)
    bool finalHeaterState = manualHeaterOn || autoHeaterOn;
    digitalWrite(RELAY_HEATER_PIN, finalHeaterState ? RELAY_ON : RELAY_OFF);

    // --- E. VICTRON 48V-TO-12V SMART CHARGER AUTOMATED CONTROL ---
    if (autoChargerEnabled && ads1Present && batteryVoltage > 5.0) {
      if (batteryVoltage <= chargerOnVolts) {
        if (!autoChargerOn) {
          autoChargerOn = true;
          autoChargerStartTimeMs = now;
        }
      } else if (batteryVoltage >= chargerOffVolts) {
        autoChargerOn = false;
      }

      // Safety Max Charge Time Protection
      if (autoChargerOn && (now - autoChargerStartTimeMs >= (chargerMaxRunMinutes * 60000))) {
        autoChargerOn = false;
        Serial.println("VICTRON CHARGER: Max charge time limit reached! Charger turned OFF.");
      }
    } else {
      autoChargerOn = false;
    }

    // Drive Victron Remote Pin 'H' Output (GPIO 13)
    bool finalChargerState = manualChargerOn || autoChargerOn;
    digitalWrite(VICTRON_CHARGER_PIN, finalChargerState ? HIGH : LOW);

    // --- F. PUBLISH LIVE TELEMETRY TO MOSQUITTO BROKER ---
    client.publish(topicStatus, currentStatusStr.c_str());
    client.publish(topicOil, String(oilPressure, 1).c_str());
    client.publish(topicTemp, String(currentWaterTemp, 1).c_str());
    client.publish(topicBlockTemp, String(currentBlockTemp, 1).c_str());
    client.publish(topicBatt, String(batteryVoltage, 2).c_str());
    client.publish(topicBattHealth, battHealthStr.c_str());
    client.publish(topicSolar48v, String(solar48v, 1).c_str());
    client.publish(topicFuel, String(fuelLevelPct, 0).c_str());
    client.publish(topicBurnRate, String(currentBurnRateGph, 1).c_str());
    client.publish(topicLifetime, String(lifetimeGallonsBurned, 1).c_str());
    client.publish(topicRpm, String(currentRpm).c_str());
    client.publish(topicHeater, finalHeaterState ? "ON" : "OFF");
    client.publish(topicCharger, finalChargerState ? "ON" : "OFF");
  }
}
```