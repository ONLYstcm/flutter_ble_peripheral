/*
 * Copyright (c) 2020. Julian Steenbakker.
 * All rights reserved. Use of this source code is governed by a
 * BSD-style license that can be found in the LICENSE file.
 */

import Flutter
import UIKit
import CoreBluetooth
import CoreLocation

public class SwiftFlutterBlePeripheralPlugin: NSObject, FlutterPlugin {
    
    private let flutterBlePeripheralManager: FlutterBlePeripheralManager
    
    private let stateChangedHandler: StateChangedHandler
    private let gattEventHandler: GattEventHandler

//    private let mtuChangedHandler = MtuChangedHandler()

    init(stateChangedHandler: StateChangedHandler, gattEventHandler: GattEventHandler) {
        self.stateChangedHandler = stateChangedHandler
        self.gattEventHandler = gattEventHandler
        
        flutterBlePeripheralManager = FlutterBlePeripheralManager(
            stateChangedHandler: stateChangedHandler,
            gattEventHandler: gattEventHandler
        )
        super.init()
    }
    
    public static func register(with registrar: FlutterPluginRegistrar) {
        let instance = SwiftFlutterBlePeripheralPlugin(
            stateChangedHandler: StateChangedHandler(registrar: registrar),
            gattEventHandler: GattEventHandler(registrar: registrar)
        )
        
        // Method channel
        let methodChannel = FlutterMethodChannel(name: "dev.steenbakker.flutter_ble_peripheral/ble_state", binaryMessenger: registrar.messenger())
        registrar.addMethodCallDelegate(instance, channel: methodChannel)
        
        // Event channels
        //instance.gattEventHandler.register(with: registrar, peripheral: instance.flutterBlePeripheralManager)
//        instance.mtuChangedHandler.register(with: registrar, peripheral: instance.flutterBlePeripheralManager)
    }
    
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch (call.method) {
        case "server/create":
            createGattServer(call, result)
        case "server/close":
            closeGattServer(result)
        case "start":
            startPeripheral(call, result)
        case "stop":
            stopPeripheral(result)
        case "isAdvertising":
            result(stateChangedHandler.state == PeripheralState.advertising)
        case "isSupported":
            isSupported(result)
        case "isConnected":
            result(stateChangedHandler.state == PeripheralState.connected)
//        case "sendData":
//            sendData(call, result)
        default:
            result(FlutterMethodNotImplemented)
        }
    }

    private func createGattServer(_ call: FlutterMethodCall, _ result: @escaping FlutterResult) {
        //hasPermissions(context!!)
        print("Creating server")

        let arguments = call.arguments as! Dictionary<String,AnyObject>
        let primaryServiceType: Bool = arguments["type"] as! Bool ? false : true

        print(arguments)
        print(primaryServiceType)

        var characteristicList: [CBMutableCharacteristic] = []
        
        for characteristicMap in arguments["characteristics"] as! [Dictionary<String,AnyObject>] {
            print(characteristicMap)
            var characteristic: CBMutableCharacteristic = CBMutableCharacteristic(
                type: CBUUID(string: characteristicMap["uuid"] as! String),
                properties: CBCharacteristicProperties(rawValue: characteristicMap["properties"] as! UInt),
                value: nil,
                permissions: CBAttributePermissions(rawValue: characteristicMap["permissions"] as! UInt)
            )
            characteristicList.append(characteristic)
        }

        flutterBlePeripheralManager.gattServer(
            serviceUuid: arguments["uuid"] as! String,
            primaryServiceType: primaryServiceType,
            serviceCharacteristics: characteristicList
        )
    }
    
    private func closeGattServer(_ result: @escaping FlutterResult) {
        // flutterBlePeripheralManager.closeGattServer()

        //Handler(Looper.getMainLooper()).post {
        //    Log.i(tag, "Stop gatt server")
        //    result.success(null)
        //}
    }
    
    private func startPeripheral(_ call: FlutterMethodCall, _ result: @escaping FlutterResult) {
        let map = call.arguments as? Dictionary<String, Any>
        let advertiseData = PeripheralData(
            uuid: map?["uuid"] as? String ,
            localName: map?["localName"] as? String
        )
        flutterBlePeripheralManager.start(advertiseData: advertiseData)
        result(nil)
    }
    
    private func stopPeripheral(_ result: @escaping FlutterResult) {
        flutterBlePeripheralManager.peripheralManager.stopAdvertising()
        stateChangedHandler.publishPeripheralState(state: PeripheralState.idle)
        result(nil)
    }
    
    // We can check if advertising is supported by checking if the ios device supports iBeacons since that uses BLE.
    private func isSupported(_ result: @escaping FlutterResult) {
        if (CLLocationManager.isMonitoringAvailable(for: CLBeaconRegion.self)){
            result(true)
        } else {
            result(false)
        }
    }
    
//    private func sendData(_ call: FlutterMethodCall,
//                          _ result: @escaping FlutterResult) {
//
//        if let flutterData = call.arguments as? FlutterStandardTypedData {
//          flutterBlePeripheralManager.send(data: flutterData.data)
//        }
//        result(nil)
//    }
}
