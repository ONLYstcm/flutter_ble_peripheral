/*
 * Copyright (c) 2020. Julian Steenbakker.
 * All rights reserved. Use of this source code is governed by a
 * BSD-style license that can be found in the LICENSE file.
 */

package dev.steenbakker.flutter_ble_peripheral

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.PeriodicAdvertisingParameters
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.annotation.NonNull
import androidx.annotation.RequiresApi
import dev.steenbakker.flutter_ble_peripheral.callbacks.PeripheralAdvertisingCallback
import dev.steenbakker.flutter_ble_peripheral.callbacks.PeripheralAdvertisingSetCallback
import dev.steenbakker.flutter_ble_peripheral.exceptions.PeripheralException
import dev.steenbakker.flutter_ble_peripheral.exceptions.PermissionNotFoundException
import dev.steenbakker.flutter_ble_peripheral.handlers.StateChangedHandler
import dev.steenbakker.flutter_ble_peripheral.models.*
import io.flutter.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.EventChannel.StreamHandler
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.*


class FlutterBlePeripheralPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {

    private val CCC_DESCRIPTOR_UUID: String = "00002902-0000-1000-8000-00805f9b34fb"
    private var methodChannel: MethodChannel? = null
    private val tag: String = "flutter_ble_peripheral"
    private var flutterBlePeripheralManager: FlutterBlePeripheralManager? = null

    private lateinit var stateChangedHandler: StateChangedHandler
    private lateinit var gattEventChannel: EventChannel
//    private lateinit var mtuChangedHandler: MtuChangedHandler
//        private val dataReceivedHandler = DataReceivedHandler()
    private var context: Context? = null

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext
        methodChannel = MethodChannel(
                flutterPluginBinding.binaryMessenger,
                "dev.steenbakker.flutter_ble_peripheral/ble_state"
        )
        methodChannel?.setMethodCallHandler(this)

        gattEventChannel = EventChannel(
                flutterPluginBinding.binaryMessenger,
                "dev.steenbakker.flutter_ble_peripheral/ble_gatt_event"
        )
        gattEventChannel.setStreamHandler(object : StreamHandler {
            override fun onListen(arguments: Any?, events: EventSink) {
                flutterBlePeripheralManager?.eventSink = events
            }
            override fun onCancel(arguments: Any?) {
                flutterBlePeripheralManager?.eventSink = null
            }
        })

        stateChangedHandler = StateChangedHandler(flutterPluginBinding)
        stateChangedHandler.publishPeripheralState(PeripheralState.poweredOff)
        flutterBlePeripheralManager = FlutterBlePeripheralManager(flutterPluginBinding.applicationContext, stateChangedHandler)
//        mtuChangedHandler = MtuChangedHandler(flutterPluginBinding, flutterBlePeripheralManager!!)
//        dataReceivedHandler.register(flutterPluginBinding, flutterBlePeripheralManager)
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
        flutterBlePeripheralManager = null
        context = null

    }

    private fun checkBluetooth() {
        if (flutterBlePeripheralManager!!.mBluetoothManager == null) throw PeripheralException(PeripheralState.unsupported)

        // Can't check whether ble is turned off or not supported, see https://stackoverflow.com/questions/32092902/why-ismultipleadvertisementsupported-returns-false-when-getbluetoothleadverti
        // !bluetoothAdapter.isMultipleAdvertisementSupported
        flutterBlePeripheralManager!!.mBluetoothLeAdvertiser = flutterBlePeripheralManager!!.mBluetoothManager!!.adapter.bluetoothLeAdvertiser
                ?: throw PeripheralException(PeripheralState.poweredOff)
    }

    private var activityBinding: ActivityPluginBinding? = null

//    private fun handlePeripheralException(e: PeripheralException, result: MethodChannel.Result?) {
//        when (e.state) {
//            PeripheralState.unsupported -> {
//                stateChangedHandler.publishPeripheralState(PeripheralState.unsupported)
//                Log.e(tag, "This device does not support bluetooth LE")
//                result?.error("Not Supported", "This device does not support bluetooth LE", e.state.name)
//            }
//            PeripheralState.poweredOff -> {
//                stateChangedHandler.publishPeripheralState(PeripheralState.poweredOff)
//                Log.e(tag, "Bluetooth may be turned off")
//                result?.error("Not powered", "Bluetooth may be turned off", e.state.name)
//            }
//            else -> {
//                stateChangedHandler.publishPeripheralState(e.state)
//                Log.e(tag, e.state.name)
//                result?.error(e.state.name, null, null)
//            }
//        }
//    }

    private fun enable(call: MethodCall, result: MethodChannel.Result) {
        if (activityBinding != null) {
            flutterBlePeripheralManager!!.enable(call, result, activityBinding!!)
        } else {
            result.error("No activity", "FlutterBlePeripheral is not correctly initialized", "null")
        }
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: MethodChannel.Result) {
        if (flutterBlePeripheralManager == null || context == null) {
            result.error("Not initialized", "FlutterBlePeripheral is not correctly initialized", "null")
        }

        if (call.method == "enable") {
            enable(call, result)
        } else {
            checkBluetooth()
            try {
                when (call.method) {
                    "server/create" -> createGattServer(call, result)
                    "server/close" -> closeGattServer(result)
                    "characteristic/write" -> characteristicWrite(call, result)
                    "characteristic/read" -> characteristicRead(call, result)
                    "start" -> startPeripheral(call, result)
                    "stop" -> stopPeripheral(result)
                    "isAdvertising" -> Handler(Looper.getMainLooper()).post {
                        result.success(stateChangedHandler.state == PeripheralState.advertising)
                    }
                    "isSupported" -> isSupported(result, context!!)
                    "isConnected" -> isConnected(result)
                    /*
                    "char/create" -> {
                        CharacteristicDelegate.createCharacteristic(call.arguments())
                        result.success(null)
                    }
                    "char/sendResponse" -> {
                        val device = DeviceDelegate.getDevice(call.argument<String>("deviceAddress")!!)
                        gattServer.sendResponse(
                            device,
                            call.argument<Int>("requestId")!!,
                            BluetoothGatt.GATT_SUCCESS,
                            call.argument<Int>("offset")!!,
                            call.argument<ArrayList<Byte>>("value")!!.toByteArray()
                        )
                        result.success(null)
                    }
                    "char/notify" -> {
                        val device = DeviceDelegate.getDevice(call.argument<String>("deviceAddress")!!)
                        val kChar = CharacteristicDelegate.getKChar(call.argument<String>("charEntityId")!!)
                        val confirm = call.argument<Boolean>("confirm")!!
                        kChar.characteristic.value = call.argument<ArrayList<Byte>>("value")!!.toByteArray()
                        gattServer.notifyCharacteristicChanged(device, kChar.characteristic, confirm)
                        result.success(null)
                    }
                    */
                    else -> Handler(Looper.getMainLooper()).post {
                        result.notImplemented()
                    }
                }

            } catch (e: PeripheralException) {
                stateChangedHandler.publishPeripheralState(e.state)
                result.error(
                        e.state.name,
                        e.localizedMessage,
                        e.stackTrace
                )
            } catch (e: PermissionNotFoundException) {
                result.error(
                        "No Permission",
                        "No permission for ${e.message} Please ask runtime permission.",
                        "Manifest.permission.${e.message}"
                )
            }
        }
    }

    private fun createGattServer(call: MethodCall, result: MethodChannel.Result) {
        //hasPermissions(context!!)

        if (call.arguments !is Map<*, *>) {
            throw IllegalArgumentException("Arguments are not a map! " + call.arguments)
        }

        val arguments = call.arguments as Map<String, Any>

        var characteristicList: MutableList<BluetoothGattCharacteristic> = mutableListOf<BluetoothGattCharacteristic>()
        //val characteristicsMap = arguments["characteristics"] as List<Map<String, dynamic>>
        for (characteristicMap in arguments["characteristics"] as List<Map<String, String>>) {
            //println(characteristicMap)
            val properties: Int = characteristicMap["properties"] as Int
            val permissions: Int = characteristicMap["permissions"] as Int
            val uuidString: String = characteristicMap["uuid"] as String

            var characteristic: BluetoothGattCharacteristic = BluetoothGattCharacteristic(
                    UUID.fromString(uuidString), properties, permissions,
            )
            if ((properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                Log.i(tag, "Adding descriptor...")
                val descriptor: BluetoothGattDescriptor = BluetoothGattDescriptor(UUID.fromString(CCC_DESCRIPTOR_UUID), BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ);
                characteristic.addDescriptor(descriptor);
            }
            characteristicList.add(characteristic)
        }

        flutterBlePeripheralManager!!.gattServer(
                arguments["uuid"] as String,
                arguments["type"] as Int,
                characteristicList,
        )
    }

    private fun closeGattServer(result: MethodChannel.Result) {
        flutterBlePeripheralManager!!.closeGattServer()

        Handler(Looper.getMainLooper()).post {
            Log.i(tag, "Stop gatt server")
            result.success(null)
        }
    }

    private fun characteristicWrite(call: MethodCall, result: MethodChannel.Result) {
        if (call.arguments !is Map<*, *>) {
            throw IllegalArgumentException("Arguments are not a map! " + call.arguments)
        }

        val arguments = call.arguments as Map<String, Any>

        val success: Boolean = flutterBlePeripheralManager!!.characteristicWrite(
            arguments["uuid"] as String,
            arguments["data"] as String
        )

        result.success(success)
    }

    private fun characteristicRead(call: MethodCall, result: MethodChannel.Result) {
        if (call.arguments !is Map<*, *>) {
            throw IllegalArgumentException("Arguments are not a map! " + call.arguments)
        }

        val arguments = call.arguments as Map<String, Any>

        val value: String? = flutterBlePeripheralManager!!.characteristicRead(
            arguments["uuid"] as String
        )

        result.success(value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun startPeripheral(call: MethodCall, result: MethodChannel.Result) {
        hasPermissions(context!!)

        if (call.arguments !is Map<*, *>) {
            throw IllegalArgumentException("Arguments are not a map! " + call.arguments)
        }

        val arguments = call.arguments as Map<String, Any>

        // First build main advertise data.
        val advertiseData: AdvertiseData.Builder = AdvertiseData.Builder()
        (arguments["manufacturerData"] as ByteArray?)?.let { advertiseData.addManufacturerData((arguments["manufacturerId"] as Int), it) }
        (arguments["serviceData"] as ByteArray?)?.let { advertiseData.addServiceData(ParcelUuid(UUID.fromString(arguments["serviceDataUuid"] as String)), it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (arguments["serviceSolicitationUuid"] as String?)?.let { advertiseData.addServiceSolicitationUuid(
                    ParcelUuid(UUID.fromString(it))) }

        (arguments["uuid"] as String?)?.let { advertiseData.addServiceUuid(ParcelUuid(UUID.fromString(it))) }
        //TODO: addTransportDiscoveryData
        (arguments["includeDeviceName"] as Boolean?)?.let { advertiseData.setIncludeDeviceName(it) }
        (arguments["transmissionPowerIncluded"] as Boolean?)?.let {
            advertiseData.setIncludeTxPowerLevel(it)
        }

        // Build advertise response data if provided
        var advertiseResponseData: AdvertiseData.Builder? = null
        if ((arguments["responseManufacturerData"] as ByteArray?) != null || (arguments["responseServiceDataUuid"] as ByteArray?) != null || (arguments["responseServiceUuid"] as String?) != null) {
            advertiseResponseData = AdvertiseData.Builder()
            (arguments["responseManufacturerData"] as ByteArray?)?.let { advertiseData.addManufacturerData((arguments["responseManufacturerId"] as Int), it) }
            (arguments["responseServiceData"] as ByteArray?).let { advertiseData.addServiceData(ParcelUuid(UUID.fromString(arguments["responseServiceDataUuid"] as String)), it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                (arguments["responseServiceSolicitationUuid"] as String?)?.let { advertiseData.addServiceSolicitationUuid(
                        ParcelUuid(UUID.fromString(it))) }

            (arguments["responseServiceUuid"] as String?)?.let { advertiseData.addServiceUuid(ParcelUuid(UUID.fromString(it))) }
            //TODO: addTransportDiscoveryData
            (arguments["responseIncludeDeviceName"] as Boolean?)?.let { advertiseData.setIncludeDeviceName(it) }
            (arguments["responseTransmissionPowerIncluded"] as Boolean?)?.let {
                advertiseData.setIncludeTxPowerLevel(it)
            }
        }

        // Check if we should use the advertiseSet method instead of advertise
        if (arguments["advertiseSet"] as Boolean? == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {


            val advertiseSettingsSet: AdvertisingSetParameters.Builder = AdvertisingSetParameters.Builder()
            (arguments["anonymous"] as Boolean?)?.let { advertiseSettingsSet.setAnonymous(it) }
            (arguments["connectable"] as Boolean?)?.let { advertiseSettingsSet.setConnectable(it) }
            (arguments["setIncludeTxPower"] as Boolean?)?.let { advertiseSettingsSet.setIncludeTxPower(it) }
            (arguments["interval"] as Int?)?.let { advertiseSettingsSet.setInterval(it) }
            (arguments["legacyMode"] as Boolean?)?.let { advertiseSettingsSet.setLegacyMode(it) }
            (arguments["primaryPhy"] as Int?)?.let { advertiseSettingsSet.setPrimaryPhy(it) }
            (arguments["scannable"] as Boolean?)?.let { advertiseSettingsSet.setScannable(it) }
            (arguments["secondaryPhy"] as Int?)?.let { advertiseSettingsSet.setSecondaryPhy(it) }
            (arguments["txPowerLevel"] as Int?)?.let { advertiseSettingsSet.setTxPowerLevel(it) }

            var periodicAdvertiseData: AdvertiseData.Builder? = null
            var periodicAdvertiseDataSettings: PeriodicAdvertisingParameters.Builder? = null
            if ((arguments["periodicManufacturerData"] as ByteArray?) != null || (arguments["periodicServiceDataUuid"] as ByteArray?) != null || (arguments["periodicServiceUuid"] as String?) != null) {
                periodicAdvertiseData = AdvertiseData.Builder()
                periodicAdvertiseDataSettings = PeriodicAdvertisingParameters.Builder()

                (arguments["periodicManufacturerData"] as ByteArray?)?.let {
                    periodicAdvertiseData.addManufacturerData(
                            (arguments["periodicManufacturerId"] as Int),
                            it
                    )
                }
                (arguments["periodicServiceData"] as ByteArray?).let {
                    periodicAdvertiseData.addServiceData(
                            ParcelUuid(UUID.fromString(arguments["periodicServiceDataUuid"] as String)),
                            it
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    (arguments["periodicServiceSolicitationUuid"] as String?)?.let {
                        periodicAdvertiseData.addServiceSolicitationUuid(
                                ParcelUuid(UUID.fromString(it))
                        )
                    }

                (arguments["periodicServiceUuid"] as String?)?.let {
                    periodicAdvertiseData.addServiceUuid(
                            ParcelUuid(UUID.fromString(it))
                    )
                }
                //TODO: addTransportDiscoveryData
                (arguments["periodicIncludeDeviceName"] as Boolean?)?.let {
                    periodicAdvertiseData.setIncludeDeviceName(
                            it
                    )
                }
                (arguments["periodicTransmissionPowerIncluded"] as Boolean?)?.let {
                    periodicAdvertiseData.setIncludeTxPowerLevel(it)
                }

                (arguments["periodicTransmissionPowerIncluded"] as Boolean?)?.let {
                    periodicAdvertiseDataSettings.setIncludeTxPower(it)
                }

                (arguments["interval"] as Int?)?.let {
                    periodicAdvertiseDataSettings.setInterval(it)
                }

            }

            var maxExtendedAdvertisingEvents = 0
            var duration = 0
            (arguments["maxExtendedAdvertisingEvents"] as Int?)?.let { maxExtendedAdvertisingEvents = it }
            (arguments["duration"] as Int?)?.let { duration = it }

            advertisingSetCallback = PeripheralAdvertisingSetCallback(result, stateChangedHandler)

            flutterBlePeripheralManager!!.startSet(advertiseData.build(), advertiseSettingsSet.build(), advertiseResponseData?.build(), periodicAdvertiseData?.build(), periodicAdvertiseDataSettings?.build(),
                    maxExtendedAdvertisingEvents, duration, advertisingSetCallback!!)
        } else {
            // Setup the advertiseSettings
            val advertiseSettings: AdvertiseSettings.Builder = AdvertiseSettings.Builder()

            (arguments["advertiseMode"] as Int?)?.let { advertiseSettings.setAdvertiseMode(it) }
            (arguments["connectable"] as Boolean?)?.let { advertiseSettings.setConnectable(it) }
            (arguments["timeout"] as Int?)?.let { advertiseSettings.setTimeout(it) }
            (arguments["txPowerLevel"] as Int?)?.let { advertiseSettings.setTxPowerLevel(it) }

            advertisingCallback = PeripheralAdvertisingCallback(result, stateChangedHandler)

            flutterBlePeripheralManager!!.start(advertiseData.build(), advertiseSettings.build(), advertiseResponseData?.build(), advertisingCallback!!)
        }

//
//        Handler(Looper.getMainLooper()).post {
//            Log.i(tag, "Start advertise: $advertiseData")
//            result.success(null)
//        }
    }

    private var advertisingSetCallback: PeripheralAdvertisingSetCallback? = null
    private var advertisingCallback: PeripheralAdvertisingCallback? = null

    private fun stopPeripheral(result: MethodChannel.Result) {
        if (advertisingSetCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flutterBlePeripheralManager!!.mBluetoothLeAdvertiser!!.stopAdvertisingSet(advertisingSetCallback)
        } else {
            flutterBlePeripheralManager!!.mBluetoothLeAdvertiser!!.stopAdvertising(advertisingCallback)
        }

        stateChangedHandler.publishPeripheralState(PeripheralState.idle)

        Handler(Looper.getMainLooper()).post {
            Log.i(tag, "Stop advertise")
            result.success(null)
        }
    }

    private fun isSupported(result: MethodChannel.Result, context: Context) {
        val isSupported = context.packageManager?.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

        Handler(Looper.getMainLooper()).post {
            result.success(isSupported)
        }
    }

    private fun isConnected(result: MethodChannel.Result) {
        val isConnected = stateChangedHandler.state == PeripheralState.connected

        Handler(Looper.getMainLooper()).post {
            Log.i(tag, "Is BLE connected: $isConnected")
            result.success(isConnected)
        }
    }

    private fun hasPermissions(context: Context): Boolean {
        // Required for API > 31
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasBluetoothAdvertisePermission(context)) {
                throw PermissionNotFoundException("BLUETOOTH_ADVERTISE")
            }
//            if (!hasBluetoothConnectPermission(context)) {
//                throw PermissionNotFoundException("BLUETOOTH_CONNECT")
//            }
//            if (!hasBluetoothScanPermission(context)) {
//                throw PermissionNotFoundException("BLUETOOTH_SCAN")
//            }

            // Required for API > 28
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (!hasLocationFinePermission(context)) {
                throw PermissionNotFoundException("ACCESS_FINE_LOCATION")
            }

            // Required for API < 28
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M){
            if (!hasLocationCoarsePermission(context)) {
                throw PermissionNotFoundException("ACCESS_COARSE_LOCATION")
            }
        }
        return true

    }

    // Permissions for Bluetooth API > 31
    @RequiresApi(Build.VERSION_CODES.S)
    private fun hasBluetoothAdvertisePermission(context: Context): Boolean {
        return (context.checkSelfPermission(
                Manifest.permission.BLUETOOTH_ADVERTISE
        )
                == PackageManager.PERMISSION_GRANTED)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun hasBluetoothConnectPermission(context: Context): Boolean {
        return (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun hasBluetoothScanPermission(context: Context): Boolean {
        return (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED)
    }
    
    @RequiresApi(Build.VERSION_CODES.M)
    private fun hasLocationFinePermission(context: Context): Boolean {
        return (context.checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
        )
                == PackageManager.PERMISSION_GRANTED)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun hasLocationCoarsePermission(context: Context): Boolean {
        return (context.checkSelfPermission(
                Manifest.permission.ACCESS_COARSE_LOCATION
        )
                == PackageManager.PERMISSION_GRANTED)
    }



    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        binding.addActivityResultListener { requestCode, resultCode, _ ->
            when (requestCode) {
                flutterBlePeripheralManager?.requestEnableBt -> {
                    // @TODO - used underlying value of `Activity.RESULT_CANCELED` since we tend to use `androidx` in which I were not able to find the constant.
                    if (flutterBlePeripheralManager?.pendingResultForActivityResult != null) {
                        flutterBlePeripheralManager!!.pendingResultForActivityResult!!.success(resultCode == Activity.RESULT_OK)
                    }
                    return@addActivityResultListener true
                }
//                REQUEST_DISCOVERABLE_BLUETOOTH -> {
//                    pendingResultForActivityResult.success(if (resultCode === 0) -1 else resultCode)
//                    return@addActivityResultListener true
//                }
                else -> return@addActivityResultListener false
            }
        }
        activityBinding = binding
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        activityBinding = null
    }
}