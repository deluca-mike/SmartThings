/**
 *  Copyright 2017 SmartThings
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
import groovy.transform.Field

// Custom Globals
@Field Boolean hasConfiguredHealthCheck = false
@Field Integer MIN_TEMP = 2710
@Field Integer MAX_TEMP = 6536
@Field Integer MIN_BRIGHTNESS = 0
@Field Integer MIN_VISIBLE_BRIGHTNESS = 1
@Field Integer MAX_BRIGHTNESS = 100
@Field Integer MIRED_NUMERATOR = 1000000
@Field Integer MIN_LEVEL_RATE = 0
@Field Integer MAX_LEVEL_RATE = 100
@Field Integer MIN_TEMP_RATE = 0
@Field Integer MAX_TEMP_RATE = 100
@Field Integer TEMP_RANGE = (MAX_TEMP - MIN_TEMP) as Integer
@Field Integer HEALTH_CHECK_INTERVAL_MINUTES = 12
@Field float RATE_ADJUSTMENT_FACTOR = 0.4
@Field Integer TEMP_ERROR_BUFFER = 50

metadata {
    definition (name: "ZLL White Color Temperature Bulb 6536K", namespace: "mikedeluca", author: "MichaelDeLuca", ocfDeviceType: "oic.d.light") {

        capability "Actuator"
        capability "Color Temperature"
        capability "Configuration"
        capability "Polling"
        capability "Refresh"
        capability "Switch"
        capability "Switch Level"
        capability "Health Check"
        capability "Notification"
        capability "Alarm"

        attribute "colorName", "string"
        command "setGenericName"
        command "blink"
        command "setLevelRate"
        command "setColorTempRate"

        fingerprint profileId: "C05E", deviceId: "0220", inClusters: "0000, 0004, 0003, 0006, 0008, 0005, 0300", outClusters: "0019", manufacturer: "Eaton", model: "Halo_RL5601", deviceJoinName: "Halo RL56"
    }

    // UI tile definitions
    tiles(scale: 2) {
        multiAttributeTile(name:"switch", type: "lighting", width: 6, height: 4, canChangeIcon: true){
            tileAttribute ("device.switch", key: "PRIMARY_CONTROL") {
                attributeState "on", label:'${name}', action:"switch.off", icon:"st.switches.light.on", backgroundColor:"#00A0DC", nextState:"turningOff"
                attributeState "off", label:'${name}', action:"switch.on", icon:"st.switches.light.off", backgroundColor:"#ffffff", nextState:"turningOn"
                attributeState "turningOn", label:'${name}', action:"switch.off", icon:"st.switches.light.on", backgroundColor:"#00A0DC", nextState:"turningOff"
                attributeState "turningOff", label:'${name}', action:"switch.on", icon:"st.switches.light.off", backgroundColor:"#ffffff", nextState:"turningOn"
            }
            tileAttribute ("device.level", key: "SLIDER_CONTROL") {
                attributeState "level", action:"switch level.setLevel"
            }
            tileAttribute ("colorName", key: "SECONDARY_CONTROL") {
                attributeState "colorName", label:'${currentValue}'
            }
        }

        standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
            state "default", label: "", action: "refresh.refresh", icon: "st.secondary.refresh"
        }

        controlTile("colorTempSliderControl", "device.colorTemperature", "slider", width: 2, height: 2, inactiveLabel: false, range:"($MIN_TEMP..$MAX_TEMP)") {
            state "colorTemperature", action: "color temperature.setColorTemperature"
        }
        
        standardTile("alert", "device.alert", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
            state "default", label: "Alert", action: "blink"
        }

		controlTile("levelRateSliderControl", "device.levelRate", "slider", width: 2, height: 2, inactiveLabel: false, range:"($MIN_LEVEL_RATE..$MAX_LEVEL_RATE)") {
            state "levelRate", action: "setLevelRate"
        }
        
        controlTile("colorTempRateSliderControl", "device.colorTempRate", "slider", width: 2, height: 2, inactiveLabel: false, range:"($MIN_TEMP_RATE..$MAX_TEMP_RATE)") {
            state "colorTempRate", action: "setColorTempRate"
        }

        main(["switch"])
        details(["switch", "colorTempSliderControl", "colorName", "refresh", "alert", "levelRateSliderControl", "colorTempRateSliderControl"])
    }
}

// Globals
private getMOVE_TO_COLOR_TEMPERATURE_COMMAND() { 0x0A }
private getCOLOR_CONTROL_CLUSTER() { 0x0300 }
private getATTRIBUTE_COLOR_TEMPERATURE() { 0x0007 }

def initialize() {
    state.lastLevel = MIN_VISIBLE_BRIGHTNESS
    state.lastTemperature = MAX_TEMP
    state.levelRate = MIN_LEVEL_RATE
    state.colorTempRate = MIN_TEMP_RATE
}

// Parse incoming device messages to generate events
def parse(String description) {
    log.debug "description is $description"
    def event = zigbee.getEvent(description)
    if (event) {
        if (event.name == "colorTemperature") {
            event.unit = "K"
        }
        log.debug event
        sendEvent(event)
    }
    else {
        log.warn "DID NOT PARSE MESSAGE for description : $description"
        log.debug zigbee.parseDescriptionAsMap(description)
    }
}

def off() {
	stopBlinking()
    
    // use stateful rate or min rate
    def rate = (state.levelRate != null) ?  state.levelRate : MIN_LEVEL_RATE
    
    dimToOff(rate)
}

def dimToOff(rate) {
	// sanitize inputs
    rate = rate as Integer

    // get the current level and compute the time to MIN_VISIBLE_BRIGHTNESS
    def currentLevel = device.currentState("level") != null ? device.currentState("level").value as Integer : MAX_BRIGHTNESS
    def effectiveRate = (rate * Math.abs(currentLevel - MIN_VISIBLE_BRIGHTNESS)/MAX_BRIGHTNESS)
    
    // compute an adjusted rate (discovered through trial and error)
    def adjustment = 1 + RATE_ADJUSTMENT_FACTOR - (currentLevel*RATE_ADJUSTMENT_FACTOR)/MAX_BRIGHTNESS
    def adjustedRate = (effectiveRate*adjustment) as Integer
    
    // sanitize effectiveRate result
    effectiveRate = effectiveRate as Integer
    
    // convert from tenths of a second to milliseconds
    adjustedRate = (adjustedRate * 100) as Integer
    
    // first dim to MIN_VISIBLE_BRIGHTNESS then off the light after the calculated adjusted wait period
    zigbee.setLevel(MIN_VISIBLE_BRIGHTNESS, effectiveRate) +
    ["delay $adjustedRate"] +
    zigbee.off() +
    ["delay 1000"] +
    zigbee.levelRefresh() +
    zigbee.onOffRefresh()
}

def on() {
	def lastLevel = (state.lastLevel != null) ? state.lastLevel : MIN_VISIBLE_BRIGHTNESS
	setLevel(lastLevel)
}

def blink(times = 1) {
    def payload = zigbee.swapEndianHex(zigbee.convertToHexString(times, 4))
    zigbee.command(0x0003, 0x00, payload)
}

def stopBlinking() {
	def payload = zigbee.swapEndianHex(zigbee.convertToHexString(0, 4))
    zigbee.command(0x0003, 0x00, payload)
}

def deviceNotification(notification) {
	// This will ater be modified to parse the value, and strobe that many times
    log.debug notification
    blink(1)
}

// This is a hack to get 3 notification types. This will blink 1 time.
def siren() {
	blink(1)
}

// This is a hack to get 3 notification types. This will blink 3 times.
def strobe() {
	blink(3)
}

// This is a hack to get 3 notification types. This will stop blinking.
def both() {
	stopBlinking()
}

def setLevel(value) {
	// use stateful rate or min rate given none specified
    def levelRate = (state.levelRate != null) ?  state.levelRate : MIN_LEVEL_RATE
    setLevel(value, levelRate)
}

def setLevel(value, rate) {
	// sanitize inputs
	value = value as Integer
    rate = rate as Integer
    
    if (value == 0) {
    	dimToOff(rate)
    } else {
    	state.lastLevel = value
    	
    	// get the stateful temp or default and check if temp needs to be reset
        def lastTemp = (state.lastTemperature != null) ? state.lastTemperature : MAX_TEMP
    	def currentTemp = device.currentState("colorTemperature") != null ? device.currentState("colorTemperature").value as Integer : MAX_TEMP
        
        // get the current level and compute effective dim rate
        def currentLevel = device.currentState("level") != null ? device.currentState("level").value as Integer : MAX_BRIGHTNESS
        def effectiveRate = (rate * Math.abs(currentLevel - value)/MAX_BRIGHTNESS) as Integer
        
        if (Math.abs(currentTemp - lastTemp) <= TEMP_ERROR_BUFFER) {
            zigbee.setLevel(value, effectiveRate) +
            ["delay 1000"] +
            zigbee.levelRefresh() +
            zigbee.onOffRefresh()
        } else {
        	// convert temp to mired and prep hex for setting temp
            def tempInMired = (MIRED_NUMERATOR / lastTemp) as Integer
            def finalHex = zigbee.swapEndianHex(zigbee.convertToHexString(tempInMired, 4))

            zigbee.setLevel(value, effectiveRate) +
            zigbee.command(COLOR_CONTROL_CLUSTER, MOVE_TO_COLOR_TEMPERATURE_COMMAND, "$finalHex 0000") +
            zigbee.readAttribute(COLOR_CONTROL_CLUSTER, ATTRIBUTE_COLOR_TEMPERATURE) +
            ["delay 1000"] +
            zigbee.levelRefresh() +
            zigbee.onOffRefresh()
        }
    }
}

def setLevelRate(value) {
	value = value as Integer
	state.levelRate = value
    sendEvent(name: "levelRate", value: value)
}

def refresh() {
    def cmds = zigbee.onOffRefresh() + zigbee.levelRefresh() + zigbee.colorTemperatureRefresh()

    // Do NOT config if the device is the Eaton Halo_LT01, it responds with "switch:off" to onOffConfig, and maybe other weird things with the others
    if (!((device.getDataValue("manufacturer") == "Eaton") && (device.getDataValue("model") == "Halo_LT01"))) {
        cmds += zigbee.onOffConfig() + zigbee.levelConfig()
    }

    cmds
}

def poll() {
    zigbee.onOffRefresh() + zigbee.levelRefresh() + zigbee.colorTemperatureRefresh()
}

// PING is used by Device-Watch in attempt to reach the Device
def ping() {
    return zigbee.levelRefresh()
}

def healthPoll() {
    log.debug "healthPoll()"
    def cmds = zigbee.onOffRefresh() + zigbee.levelRefresh()
    cmds.each{ sendHubCommand(new physicalgraph.device.HubAction(it))}
}

def configureHealthCheck() {
    Integer hcIntervalMinutes = HEALTH_CHECK_INTERVAL_MINUTES
    if (!hasConfiguredHealthCheck) {
        log.debug "Configuring Health Check, Reporting"
        unschedule("healthPoll")
        runEvery5Minutes("healthPoll")
        // Device-Watch allows 2 check-in misses from device
        sendEvent(name: "checkInterval", value: hcIntervalMinutes * 60, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])
        hasConfiguredHealthCheck = true
    }
}

def configure() {
    log.debug "configure()"
    configureHealthCheck()
    // Implementation note: for the Eaton Halo_LT01, it responds with "switch:off" to onOffConfig, so be sure this is before the call to onOffRefresh
    zigbee.onOffConfig() + zigbee.levelConfig() + zigbee.onOffRefresh() + zigbee.levelRefresh() + zigbee.colorTemperatureRefresh()
}

def updated() {
    log.debug "updated()"
    if (state.lastLevel == null) {
    	state.lastLevel = MIN_VISIBLE_BRIGHTNESS
    }
    if (state.levelRate == null) {
    	state.levelRate = MIN_LEVEL_RATE
    }
    if (state.lastTemperature == null) {
    	state.lastTemperature = MIN_TEMP
    }
    if (state.colorTempRate == null) {
    	state.colorTempRate = MIN_TEMP_RATE
    }
    configureHealthCheck()
}

def setColorTemperature(value) {
	// sanitize inputs
    value = value as Integer
    
    setGenericName(value)
    
    // update stateful temp even if light is off
    state.lastTemperature = value
    
    // Convert to mired
    def tempInMired = (MIRED_NUMERATOR / value) as Integer
    def finalTempHex = zigbee.swapEndianHex(zigbee.convertToHexString(tempInMired, 4))
    
	// get the stateful tempRate or default
    def colorTempRate = state.colorTempRate != null ? state.colorTempRate : MIN_TEMP_RATE
    
    // get the current temp and compute effective temp rate as hex
    def currentColorTemp = device.currentState("colorTemperature") != null ? device.currentState("colorTemperature").value as Integer : MAX_TEMP
    def effectiveRate = (colorTempRate * Math.abs(currentColorTemp - value)/TEMP_RANGE) as Integer
    def finalRateHex = zigbee.swapEndianHex(zigbee.convertToHexString(effectiveRate, 4))

    zigbee.command(COLOR_CONTROL_CLUSTER, MOVE_TO_COLOR_TEMPERATURE_COMMAND, "$finalTempHex $finalRateHex") +
    zigbee.readAttribute(COLOR_CONTROL_CLUSTER, ATTRIBUTE_COLOR_TEMPERATURE)
}

def setColorTempRate(value) {
	value = value as Integer
	state.colorTempRate = value
    sendEvent(name: "colorTempRate", value: value)
}

// Naming based on the wiki article here: http://en.wikipedia.org/wiki/Color_temperature
// Also naming from https://www.philips.co.in/c-m-li/long-lasting-led-lights/warm-white-led-bulbs#slide_White_(3000K)
// And here https://inspectapedia.com/electric/Bulb_Color_Temperatures.php
// But at the end of the day, I am making this match Google Home's implementation, by trial and error
def setGenericName(value){
    if (value != null) {
        def genericName = ""
        if (value <= 2703) {
            genericName = "Candlelight"
        } else if (value <= 3003) {
            genericName = "Soft White"
        } else if (value <= 4000) {
            genericName = "Cool White"
        } else if (value <= 4115) {
            genericName = "Moonlight"
        } else if (value <= 5000) {
            genericName = "Daylight"
        } else {
            genericName = "Bright White"
        }
        sendEvent(name: "colorName", value: genericName)
    }
}