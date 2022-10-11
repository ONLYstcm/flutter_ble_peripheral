/*
 * Copyright (c) 2020. Julian Steenbakker.
 * All rights reserved. Use of this source code is governed by a
 * BSD-style license that can be found in the LICENSE file.
 */

package dev.steenbakker.flutter_ble_peripheral

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import dev.steenbakker.flutter_ble_peripheral.callbacks.PeripheralAdvertisingCallback
import dev.steenbakker.flutter_ble_peripheral.callbacks.PeripheralAdvertisingSetCallback
import dev.steenbakker.flutter_ble_peripheral.handlers.StateChangedHandler
import dev.steenbakker.flutter_ble_peripheral.models.PeripheralState
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.UUID
import kotlin.collections.MutableMap


class FlutterBlePeripheralManager(appContext: Context, stateHandler: StateChangedHandler) {

    private lateinit var context: Context
    private lateinit var stateChangedHandler: StateChangedHandler

    var eventSink: EventSink? = null
    private val uiThreadHandler: Handler = Handler(Looper.getMainLooper())

    var mBluetoothManager: BluetoothManager?
    var mBluetoothLeAdvertiser: BluetoothLeAdvertiser? = null

    var pendingResultForActivityResult: MethodChannel.Result? = null
    val requestEnableBt = 4

    private val CCC_DESCRIPTOR_UUID: String = "00002902-0000-1000-8000-00805f9b34fb"
    private lateinit var mBluetoothGattServer: BluetoothGattServer
    private lateinit var mGattService : BluetoothGattService
    private var mBluetoothGatt: MutableMap<String, BluetoothGatt> = mutableMapOf<String, BluetoothGatt>()
    private var mBluetoothDevices: MutableMap<String, BluetoothDevice> = mutableMapOf<String, BluetoothDevice>()
    private var mGattServiceCharacteristics: MutableMap<String, BluetoothGattCharacteristic> = mutableMapOf<String, BluetoothGattCharacteristic>()

    init {
        context = appContext
        stateChangedHandler = stateHandler
        mBluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    /**
     * Enables bluetooth with a dialog or without.
     */
    fun enableBluetooth(call: MethodCall, result: MethodChannel.Result, activityBinding: ActivityPluginBinding) {
        if (mBluetoothManager!!.adapter.isEnabled) {
            result.success(true)
        } else {
            if (call.arguments as Boolean) {
                pendingResultForActivityResult = result
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                ActivityCompat.startActivityForResult(
                        activityBinding.activity,
                        intent,
                        requestEnableBt,
                        null
                )
            } else {
                mBluetoothManager!!.adapter.enable()
            }
        }
    }

    /**
     * Start advertising using the startAdvertising() method.
     */
    fun start(peripheralData: AdvertiseData, peripheralSettings: AdvertiseSettings, peripheralResponse: AdvertiseData?, mAdvertiseCallback: PeripheralAdvertisingCallback) {

        mBluetoothLeAdvertiser!!.startAdvertising(
                peripheralSettings,
                peripheralData,
                peripheralResponse,
                mAdvertiseCallback
        )
    }

    /**
     * Start advertising using the startAdvertisingSet method.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun startSet(advertiseData: AdvertiseData, advertiseSettingsSet: AdvertisingSetParameters, peripheralResponse: AdvertiseData?,
                 periodicResponse: AdvertiseData?, periodicResponseSettings: PeriodicAdvertisingParameters?, maxExtendedAdvertisingEvents: Int = 0, duration: Int = 0, mAdvertiseSetCallback: PeripheralAdvertisingSetCallback) {

        mBluetoothLeAdvertiser!!.startAdvertisingSet(
                advertiseSettingsSet,
                advertiseData,
                peripheralResponse,
                periodicResponseSettings,
                periodicResponse,
                duration,
                maxExtendedAdvertisingEvents,
                mAdvertiseSetCallback,
        )
    }


    fun gattServer(
            serviceUuid : String,
            serviceType : Int,
            serviceCharacteristics : List<BluetoothGattCharacteristic>
        ) {
        Log.i("FlutterBlePeripheralManager","Starting GattServer")

        val gattCallback = object : BluetoothGattCallback() {
            override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                super.onMtuChanged(gatt, mtu, status)
                Log.i("BluetoothGattCallback", "ATT MTU changed to $mtu, success: ${status == BluetoothGatt.GATT_SUCCESS}")
            }

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val deviceAddress = gatt.device.address
         
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.i("BluetoothGattCallback", "Successfully connected to $deviceAddress")
                        Handler(Looper.getMainLooper()).post {
                            mBluetoothGatt[deviceAddress]!!.discoverServices()
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        Log.i("BluetoothGattCallback", "Successfully disconnected from $deviceAddress")
                        gatt.close()
                    }
                } else {
                    Log.i("BluetoothGattCallback", "Error $status encountered for $deviceAddress! Disconnecting...")
                    gatt.close()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                with(gatt) {
                    val services: List<BluetoothGattService> = gatt.getServices()
                    val device: BluetoothDevice = gatt.getDevice()

                    Log.w("BluetoothGattCallback", "Discovered ${services.size} services for ${device.address}")
                    // Consider connection setup as complete here
                    if (services.isEmpty()) {
                        Log.i("onServicesDiscovered", "No service and characteristic available, call discoverServices() first?")
                        return
                    }
                    services.forEach { service ->
                        val characteristicsTable = service.characteristics.joinToString(
                            separator = "\n|--",
                            prefix = "|--"
                        ) { it.uuid.toString() }
                        Log.i("onServicesDiscovered", "\nService ${service.uuid}\nCharacteristics:\n$characteristicsTable")

                        service.getCharacteristics().forEach { characteristic -> 
                            enableNotifications(device.address, characteristic) 
                            /*
                            if ((characteristic.getProperties() and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                                // 0x2902 org.bluetooth.descriptor.gatt.client_characteristic_configuration.xml
                                val notifyUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                                val descriptor: BluetoothGattDescriptor = characteristic.getDescriptor(notifyUuid)
                                if (descriptor != null) {
                                    enableNotifications(address: String, characteristic)
                                } else {
                                    Log.e("FlutterBlePeripheralManager", "Characteristic can not be configured with descriptor");
                                }
                            }
                            */
                        }
                    }
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                with(characteristic) {
                    Log.i("BluetoothGattCallback", "Characteristic $uuid changed")
                }
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                with(characteristic) {
                    when (status) {
                        BluetoothGatt.GATT_SUCCESS -> {
                            Log.i("BluetoothGattCallback", "Read characteristic $uuid")
                        }
                        BluetoothGatt.GATT_READ_NOT_PERMITTED -> {
                            Log.e("BluetoothGattCallback", "Read not permitted for $uuid!")
                        }
                        else -> {
                            Log.e("BluetoothGattCallback", "Characteristic read failed for $uuid, error: $status")
                        }
                    }
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                with(characteristic) {
                    when (status) {
                        BluetoothGatt.GATT_SUCCESS -> {
                            Log.i("BluetoothGattCallback", "Wrote to characteristic $uuid")
                        }
                        BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> {
                            Log.e("BluetoothGattCallback", "Write exceeded connection ATT MTU!")
                        }
                        BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> {
                            Log.e("BluetoothGattCallback", "Write not permitted for $uuid!")
                        }
                        else -> {
                            Log.e("BluetoothGattCallback", "Characteristic write failed for $uuid, error: $status")
                        }
                    }
                }
            }
        }

        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onPhyUpdate(device: BluetoothDevice?, txPhy: Int, rxPhy: Int, status: Int) {
                Log.d("FlutterBlePeripheralManager", "BluetoothGattServerCallback::onPhyUpdate")
                super.onPhyUpdate(device, txPhy, rxPhy, status)
            }

            override fun onPhyRead(device: BluetoothDevice?, txPhy: Int, rxPhy: Int, status: Int) {
                Log.d("FlutterBlePeripheralManager", "BluetoothGattServerCallback::onPhyRead")
                super.onPhyRead(device, txPhy, rxPhy, status)
            }

            override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
                Log.d("FlutterBlePeripheralManager", "BluetoothGattServerCallback::onMtuChanged")
                super.onMtuChanged(device, mtu)
                //onMtuChanged?.invoke(device, mtu)
            }

            override fun onExecuteWrite(device: BluetoothDevice?, requestId: Int, execute: Boolean) {
                Log.d("FlutterBlePeripheralManager", "BluetoothGattServerCallback::onExecuteWrite")
                super.onExecuteWrite(device, requestId, execute)
            }

            override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
                Log.d("FlutterBlePeripheralManager", "BluetoothGattServerCallback::onNotificationSent")
                super.onNotificationSent(device, status)
            }

            override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
                Log.d("FlutterBlePeripheralManager", "BluetoothGattServerCallback::onServiceAdded")
                super.onServiceAdded(status, service)
            }

            override fun onConnectionStateChange(
                device: BluetoothDevice?,
                status: Int,
                newState: Int
            ) {
                Log.d("FlutterBlePeripheralManager", "BluetoothGattServerCallback::onConnectionStateChange")
                super.onConnectionStateChange(device, status, newState)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            mBluetoothDevices.put(device!!.address, device!!)
                            mBluetoothGatt.put(device!!.address, device!!.connectGatt(context, false, gattCallback))
                            stateChangedHandler.publishPeripheralState(PeripheralState.connected)
                            Log.i("BluetoothGattServerCallback::onConnectionStateChange", "Device connected $device")
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            mBluetoothDevices.remove(device!!.address)
                            mBluetoothGatt.remove(device!!.address)
                            if (mBluetoothDevices.isEmpty()) {
                                stateChangedHandler.publishPeripheralState(PeripheralState.idle)
                            }
                            Log.i("BluetoothGattServerCallback::onConnectionStateChange", "Device disconnect $device")
                        }
                    }
                }
                uiThreadHandler.post {
                    eventSink?.success(
                        mapOf(
                            Pair("event", "ConnectionStateChange"),
                            Pair("device", device.toString()),
                            Pair("status", status),
                            Pair("newState", newState)
                        )
                    )
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic
            ) {
                Log.d("FlutterBlePeripheralManager", "BluetoothGattServerCallback::onCharacteristicReadRequest")
                super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
                if (mBluetoothGattServer == null) return

                if (mGattServiceCharacteristics.containsKey(characteristic.uuid.toString())) {
                    Log.i("FlutterBlePeripheralManager", "Characteristic Read Request: " + characteristic.uuid)
                    mBluetoothGattServer.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        mGattServiceCharacteristics[characteristic.uuid.toString()]!!.getValue()
                    )
                    uiThreadHandler.post {
                        eventSink?.success(
                            mapOf(
                                Pair("event", "CharacteristicReadRequest"),
                                Pair("device", device.toString()),
                                Pair("requestId", requestId),
                                Pair("offset", offset),
                                Pair("characteristic", characteristic.uuid.toString()),
                            )
                        )
                    }
                }
                else {
                    Log.w("FlutterBlePeripheralManager", "Invalid Characteristic Read Request: " + characteristic.uuid)
                    mBluetoothGattServer.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        0,
                        null
                    )
                }

            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                Log.i("BluetoothGattServerCallback::onCharacteristicWriteRequest", "BLE Write Request")
                super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
                if (mBluetoothGattServer == null) return

                when {
                    mGattServiceCharacteristics.containsKey(characteristic.uuid.toString()) -> {
                        if (value!!.isNotEmpty()) {
                            Log.i("FlutterBlePeripheralManager", "Characteristic Write Request: " + characteristic.uuid)

                            characteristicWrite(
                                characteristic.uuid.toString(), 
                                String(value!!)
                            )

                            mBluetoothGattServer.sendResponse(
                                    device,
                                    requestId,
                                    BluetoothGatt.GATT_SUCCESS,
                                    0,
                                    null
                            )
                            uiThreadHandler.post {
                                eventSink?.success(
                                    mapOf(
                                        Pair("event", "CharacteristicWriteRequest"),
                                        Pair("device", device.toString()),
                                        Pair("requestId", requestId),
                                        Pair("characteristic", characteristic.uuid.toString()),
                                        Pair("preparedWrite", preparedWrite),
                                        Pair("responseNeeded", responseNeeded),
                                        Pair("offset", offset),
                                        Pair("value", value),
                                    )
                                )
                            }
                        }

                    }
                    else -> {
                        Log.w("FlutterBlePeripheralManager", "Invalid Characteristic Write Request: " + characteristic.uuid)
                        mBluetoothGattServer.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            0,
                            null
                        )
                    }
                }

                if (responseNeeded) {
                    Log.i("BluetoothGattServerCallback::onCharacteristicWriteRequest", "BLE Write Request - Response")
                    mBluetoothGattServer.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        null
                    )
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice, 
                requestId: Int, 
                descriptor: BluetoothGattDescriptor, 
                preparedWrite: Boolean, 
                responseNeeded: Boolean, 
                offset: Int, 
                value: ByteArray,
            ) {
                if (responseNeeded) {
                    Log.i("BluetoothGattServerCallback::onDescriptorWriteRequest", "Sending Response")
                    mBluetoothGattServer.sendResponse(
                        device, 
                        requestId, 
                        BluetoothGatt.GATT_SUCCESS, 
                        offset, 
                        value
                    )
                }
            }
        }


        mGattService = BluetoothGattService(UUID.fromString(serviceUuid), serviceType)

        for (characteristic in serviceCharacteristics) {
            mGattService.addCharacteristic(characteristic)
            mGattServiceCharacteristics[characteristic.uuid.toString()] = characteristic
        }

        mBluetoothGattServer = mBluetoothManager!!.openGattServer(context, serverCallback)
        val serviceAdded = mBluetoothGattServer.addService(mGattService)
        if (serviceAdded)
        Log.d("FlutterBlePeripheralManager", "BluetoothGattServer::BLE Service added")
        else
        Log.e("FlutterBlePeripheralManager", "BluetoothGattServer::BLE Service NOT added")

    }

    fun characteristicWrite(charUuid: String, charData: String) : Boolean {
        if (mGattServiceCharacteristics.containsKey(charUuid)) {
            
            val success: Boolean = mGattServiceCharacteristics[charUuid]!!.setValue(charData)
            if (success) {
                for ((address, device) in mBluetoothDevices) {
                    mBluetoothGattServer.notifyCharacteristicChanged(
                        device, 
                        mGattServiceCharacteristics[charUuid], 
                        false
                    );
                }
            }
            return success
        }
        return false
    }

    fun characteristicRead(charUuid: String) : String? {
        if (mGattServiceCharacteristics.containsKey(charUuid)) {
            val value: String = String(mGattServiceCharacteristics[charUuid]!!.getValue(), charset("UTF-8"))
            return value
        }
        return null
    }

    fun BluetoothGattDescriptor.isReadable(): Boolean =
        containsPermission(BluetoothGattDescriptor.PERMISSION_READ) ||
        containsPermission(BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED) ||
        containsPermission(BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED_MITM)

    fun BluetoothGattDescriptor.isWritable(): Boolean = 
        containsPermission(BluetoothGattDescriptor.PERMISSION_WRITE) ||
        containsPermission(BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED) ||
        containsPermission(BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED_MITM) ||
        containsPermission(BluetoothGattDescriptor.PERMISSION_WRITE_SIGNED) ||
        containsPermission(BluetoothGattDescriptor.PERMISSION_WRITE_SIGNED_MITM)
        
    fun BluetoothGattDescriptor.containsPermission(permission: Int): Boolean = permissions and permission != 0

    fun BluetoothGattCharacteristic.isIndicatable(): Boolean = containsProperty(BluetoothGattCharacteristic.PROPERTY_INDICATE)
    
    fun BluetoothGattCharacteristic.isNotifiable(): Boolean = containsProperty(BluetoothGattCharacteristic.PROPERTY_NOTIFY)

    fun BluetoothGattCharacteristic.containsProperty(property: Int): Boolean = properties and property != 0

    fun writeDescriptor(descriptor: BluetoothGattDescriptor, payload: ByteArray, address: String) {
        mBluetoothGatt[address]!!.let { gatt ->
            descriptor.value = payload
            gatt.writeDescriptor(descriptor)
        } ?: error("Not connected to a BLE device!")
    }

    fun enableNotifications(address: String, characteristic: BluetoothGattCharacteristic) {
        val cccdUuid = UUID.fromString(CCC_DESCRIPTOR_UUID)
        val payload = when {
            characteristic.isIndicatable() -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            characteristic.isNotifiable() -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> {
                Log.e("ConnectionManager", "${characteristic.uuid} doesn't support notifications/indications")
                return
            }
        }
     
        characteristic.getDescriptor(cccdUuid)?.let { cccDescriptor ->
            if (mBluetoothGatt[address]!!.setCharacteristicNotification(characteristic, true) == false) {
                Log.e("ConnectionManager", "setCharacteristicNotification failed for ${characteristic.uuid}")
                return
            }
            writeDescriptor(cccDescriptor, payload, address)
        } ?: Log.e("ConnectionManager", "${characteristic.uuid} doesn't contain the CCC descriptor!")
    }
     
    fun disableNotifications(address: String, characteristic: BluetoothGattCharacteristic) {
        if (!characteristic.isNotifiable() && !characteristic.isIndicatable()) {
            Log.e("ConnectionManager", "${characteristic.uuid} doesn't support indications/notifications")
            return
        }
     
        val cccdUuid = UUID.fromString(CCC_DESCRIPTOR_UUID)
        characteristic.getDescriptor(cccdUuid)?.let { cccDescriptor ->
            if (mBluetoothGatt[address]!!.setCharacteristicNotification(characteristic, false) == false) {
                Log.e("ConnectionManager", "setCharacteristicNotification failed for ${characteristic.uuid}")
                return
            }
            writeDescriptor(cccDescriptor, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, address)
        } ?: Log.e("ConnectionManager", "${characteristic.uuid} doesn't contain the CCC descriptor!")
    }


    fun closeGattServer () {
        mBluetoothGattServer.clearServices()
        mBluetoothGattServer.close()
    }
}