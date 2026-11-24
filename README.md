# Kohler 60kW Genset + WT32-ETH01 Monitoring System

## Complete Backup & Project Documentation
**Updated:** November 20, 2026

### Included Files:
1. `MainActivity.kt` — Complete Android App code (Kotlin + Jetpack Compose + Paho MQTT + Tailscale 5G Remote Access + Security Confirmations + Mosquitto settings)
2. `Kohler_60kW_Master_ESP32_Sketch.ino` — Complete WT32-ETH01 Arduino C++ Sketch (Built-in LAN8720 Ethernet, Wi-Fi failover, ADS1115 16-Bit ADC, PC817 Optocouplers, Relays, NVS Flash Memory, Mosquitto MQTT, SSR-40DA 120V Coolant Heater & Auto-Freeze Protect)
3. `Master_Wiring_Blueprint.txt` — Full color-coded wiring diagram for WT32-ETH01, gauge terminal pinouts, and hardware connections.
4. `Printer_Friendly_Master_Wiring_Blueprint.txt` — Black-on-white text blueprint for easy paper printing.

---

### Hardware Module:
- **Board:** Wireless-Tag **WT32-ETH01** (ESP32 with built-in LAN8720 PHY Ethernet)
- **Primary Connection:** Built-in LAN8720 RJ45 Ethernet Port
- **Secondary Connection:** Built-in ESP32 Wi-Fi (Automatic Failover)

### Mosquitto Broker Configuration:
- **Broker IP:** `192.168.68.91` (Local Network Mosquitto Broker)
- **Tailscale 5G Worldwide IP:** `100.108.173.127` (For Android Phone App anywhere on Earth via 5G)
- **Port:** `1883`
- **Username:** `aahotch2001`
- **Password:** `aaronjulie`

### ELEGOO ESP32 USB-C Breakout Adapter Pinout Map:
- **`D21` Pin (GPIO 21):** Native I2C SDA (ADS1115 ADC SDA)
- **`D22` Pin (GPIO 22):** Native I2C SCL (ADS1115 ADC SCL)
- **`D26` Pin (GPIO 26):** Songle Start Relay IN1 (Kohler Terminals 3 & 4)
- **`D27` Pin (GPIO 27):** Songle Heater Relay IN2 (120V Coolant Block Heater Control)
- **`D4` Pin (GPIO 4):** Victron Orion 48V-to-12V Charger Remote Pin 'H' (Direct 3.3V Control)
- **`D18` Pin (GPIO 18):** Optocoupler Ch1 Output O1 (Alternator Terminal 'W' Engine RPM & Running Sense - No VCC Needed!)
- **`D19` Pin (GPIO 19):** Optocoupler Ch2 Output O2 (EG4 18kPV Inverter Auto-Start)
- **`D25` Pin (GPIO 25):** DS18B20 3-Wire Digital Temp Sensor (Data Wire + 4.7k Ω resistor to 3.3V)
- **`VIN` Pin:** 5.0V Regulated Main Power Input (from DROK Buck Converter)
- **`GND` Pins:** Common Ground (tied to All Grounds)

### MQTT Topics:
- `kohler/generator-command` (START / STOP)
- `kohler/generator-status` (STOPPED / RUNNING)
- `kohler/oil-pressure` (PSI)
- `kohler/water-temp` (°F)
- `kohler/battery-voltage` (12V Starter)
- `kohler/solar-battery-48v` (48V Solar Bank)
- `kohler/fuel-level` (%)
- `kohler/burn-rate` (GPH)
- `kohler/lifetime-fuel` (Gallons)
- `kohler/engine-rpm` (RPM / Hz)
- `kohler/coolant-heater-cmd` (ON / OFF)
- `kohler/coolant-heater` (ON / OFF)
- `kohler/heater-auto-enable` (ON / OFF)
- `kohler/heater-set-on-temp` (°F)
- `kohler/heater-set-off-temp` (°F)
- `kohler/heater-set-max-mins` (Minutes)
