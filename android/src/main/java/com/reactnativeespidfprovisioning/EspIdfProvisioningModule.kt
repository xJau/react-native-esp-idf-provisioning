package com.reactnativeespidfprovisioning

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.espressif.provisioning.*
import com.espressif.provisioning.listeners.BleScanListener
import com.espressif.provisioning.listeners.ProvisionListener
import com.espressif.provisioning.listeners.ResponseListener
import com.espressif.provisioning.listeners.WiFiScanListener
import com.facebook.react.bridge.*
import com.facebook.react.common.StandardCharsets
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe


class EspIdfProvisioningModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    val foundBLEDevices = HashMap<String, BluetoothDevice>();

    init {
      EventBus.getDefault().register(this);
    }

    override fun getName(): String {
        return "EspIdfProvisioning"
    }

    // Searches for BLE devices with a name starting with the given prefix.
    // The prefix must match the string in '/main/app_main.c'
    // Resolves to an array of BLE devices
    @ReactMethod
    fun getBleDevices(prefix: String, promise: Promise) {
      Log.e("ESPProvisioning", "getBleDevices")

      if (ActivityCompat.checkSelfPermission(reactApplicationContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        promise.reject("Location Permission denied")
        return;
      }

      // Search for BLE devices
      ESPProvisionManager.getInstance(reactApplicationContext).searchBleEspDevices(prefix, object : BleScanListener {
        override fun scanStartFailed() {
          promise.reject("Scan start failed")
        }

        override fun onPeripheralFound(device: BluetoothDevice, scanResult: ScanResult) {
          foundBLEDevices[device.address] = device;
        }

        @SuppressLint("MissingPermission")
        override fun scanCompleted() {
          val result = WritableNativeArray();

          foundBLEDevices.keys.forEach {
            val device: WritableMap = Arguments.createMap();
            device.putString("address", it);
            device.putString("name", foundBLEDevices[it]?.name);
            result.pushMap(device)
          }

          // Return found BLE devices
          promise.resolve(result);
        }

        override fun onFailure(p0: Exception?) {
          promise.reject(p0.toString())
        }
      });
    }

    // Send event to JS
    fun sendEvent(name: String, value: String) {
      reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(name, value);
    }

    // Subscribe on a DeviceConnectionEvent to get more info about the device connection.
    @Subscribe
    fun onConnectionEvent(event: DeviceConnectionEvent) {
      Log.e("ESPProvisioning", "DeviceConnectionEvent")

      when (event.getEventType()) {
        ESPConstants.EVENT_DEVICE_CONNECTED -> sendEvent("DeviceConnectionEvent", "EVENT_DEVICE_CONNECTED")
        ESPConstants.EVENT_DEVICE_DISCONNECTED -> sendEvent("DeviceConnectionEvent", "EVENT_DEVICE_DISCONNECTED")
        ESPConstants.EVENT_DEVICE_CONNECTION_FAILED -> sendEvent("DeviceConnectionEvent", "EVENT_DEVICE_CONNECTION_FAILED")
      }
    }

    // Connects to a BLE device
    // We need the Service UUID from the config.service_uuid in app_prov.c
    // We need the proof of possestion (pop) specified in '/main/app_main.c'
    // The deviceAddress is the address we got from the "searchBleEspDevices" function
    // Resolves when connected to device
    @ReactMethod
    fun connectBleDevice(deviceAddress: String, deviceProofOfPossession: String, serviceUUID:String, promise: Promise) {
      Log.e("ESPProvisioning", "connectBleDevice")
      if (ActivityCompat.checkSelfPermission(reactApplicationContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        promise.reject("Location Permission denied");
        return;
      }

      // SECURITY_0 is plain text communication.
      // SECURITY_1 is encrypted.
      // This must match 'wifi_prov_security_t security' in app_main.c
      val esp : ESPDevice = ESPProvisionManager.getInstance(reactApplicationContext).createESPDevice(ESPConstants.TransportType.TRANSPORT_BLE, ESPConstants.SecurityType.SECURITY_1);
      esp.proofOfPossession = deviceProofOfPossession
      val device = foundBLEDevices[deviceAddress]
      if (device == null) {
        promise.reject("Invalid address $deviceAddress")
        return;
      }

      esp.connectBLEDevice(device, serviceUUID);

      promise.resolve(esp.deviceName);
    }

    @ReactMethod
    fun createDevice(ssid: String, password: String, devicePop: String,
                     callback: Callback ) {
      val device : ESPDevice = ESPProvisionManager.getInstance(reactApplicationContext).createESPDevice(ESPConstants.TransportType.TRANSPORT_BLE, ESPConstants.SecurityType.SECURITY_1)
      device.proofOfPossession = devicePop
      if (ActivityCompat.checkSelfPermission(reactApplicationContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
        device.connectWiFiDevice(ssid, password)
        callback.invoke("success")
      } else {
        Toast.makeText(reactApplicationContext, "Location permission denied", Toast.LENGTH_SHORT).show()
      }
    }

    @ReactMethod
    fun scanWifiList(promise: Promise) {
      val device = ESPProvisionManager.getInstance(reactApplicationContext).espDevice

      if(device == null) {
        promise.reject("No device found")
        return;
      }

      device.scanNetworks(object: WiFiScanListener {
        override fun onWifiListReceived(wifiList: java.util.ArrayList<WiFiAccessPoint>?) {
          val result = WritableNativeArray();
          wifiList?.forEach {
            val network: WritableMap = Arguments.createMap();
            network.putString("name", it.wifiName);
            network.putInt("rssi", it.rssi);
            network.putInt("security", it.security);

            result.pushMap(network)
          }
          promise.resolve(result)
        }

        override fun onWiFiScanFailed(p0: java.lang.Exception?) {
          promise.reject("Failed to get Wi-Fi scan list")
        }
      })
    }

    @ReactMethod
    fun sendCustomData(customEndPoint: String, customData: String, promise: Promise ) {
      val device = ESPProvisionManager.getInstance(reactApplicationContext).espDevice
      if(device == null) {
        promise.reject("No device found")
      return;
      }
      device.sendDataToCustomEndPoint(customEndPoint,
        customData.toByteArray(StandardCharsets.UTF_16),
        object: ResponseListener {
          override fun onSuccess(returnData: ByteArray){
            val response: WritableMap = Arguments.createMap();
            val data = String(returnData);
            response.putBoolean("success", true);
            response.putString("data", data);
            Log.e("ESPProvisioning", data);
            promise.resolve(response);
          }

          override fun onFailure(e: Exception?) {
            Log.e("ESPProvisioning", "Custom data provision has failed", e);
            promise.reject("Custom data provision failed");
          }
        });
    }

  @ReactMethod
  fun sendCustomDataWithByteData(
    customEndPoint: String?,
    customData: ReadableArray,
    promise: Promise
  ) {
    val device = ESPProvisionManager.getInstance(reactApplicationContext).espDevice
    if(device == null) {
      promise.reject("No device found")
      return;
    }
    // customData must be an array of strings, with each string being a hexidecimal value 00-FF, ie ["52", "0"]
    val length = customData.size()
    val output = ByteArray(length)
    for (i in 0 until length) output[i] = customData.getString(i).toInt(16).toByte()
    try {
      device.sendDataToCustomEndPoint(
        customEndPoint,
        output,
        object: ResponseListener {
          override fun onSuccess(returnData: ByteArray){
            val response: WritableMap = Arguments.createMap();
            val data = String(returnData);
            response.putBoolean("success", true);
            response.putString("data", data);
            Log.e("ESPProvisioning", data);
            promise.resolve(response);
          }

          override fun onFailure(e: Exception?) {
            Log.e("ESPProvisioning", "Custom data provision has failed", e);
            promise.reject("Custom data provision failed");
          }
        });
    } catch (e: Exception) {
      Log.e("ESPProvisioning", "Custom data provision has failed", e);
      promise.reject(
        "Custom data provision with bytes error, " + e.message,
        "An error has occurred in init of provisioning of custom data", e
      )
    }
  }

  @ReactMethod
  fun provision(ssid: String, password: String, promise: Promise) {
    ESPProvisionManager.getInstance(reactApplicationContext).espDevice?.provision(
      ssid,
      password,
      object : ProvisionListener {
        override fun wifiConfigApplyFailed(p0: Exception?) {
          Log.e("ESPProvisioning", "provision-wifiConfigApplyFailed");
          promise.reject(p0.toString())
        }

        override fun wifiConfigApplied() {
          Log.e("ESPProvisioning", "provision-wifiConfigApplied");
        }

        override fun onProvisioningFailed(p0: Exception?) {
          Log.e("ESPProvisioning", "provision-onProvisioningFailed");
          promise.reject(p0.toString())
        }

        override fun deviceProvisioningSuccess() {
          Log.e("ESPProvisioning", "provision-deviceProvisioningSuccess");
          promise.resolve(ssid)
        }

        override fun createSessionFailed(p0: Exception?) {
          Log.e("ESPProvisioning", "provision-createSessionFailed");
          promise.reject(p0.toString())
        }

        override fun wifiConfigFailed(p0: Exception?) {
          Log.e("ESPProvisioning", "provision-wifiConfigFailed");
          promise.reject(p0.toString())
        }

        override fun provisioningFailedFromDevice(p0: ESPConstants.ProvisionFailureReason?) {
          Log.e("ESPProvisioning", "provision-provisioningFailedFromDevice");
          promise.reject(p0.toString())
        }

        override fun wifiConfigSent() {
          Log.e("ESPProvisioning", "provision-wifiConfigSent");
        }
      })
  }
}
