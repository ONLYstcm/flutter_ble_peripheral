/*
 * Copyright (c) 2020. Julian Steenbakker.
 * All rights reserved. Use of this source code is governed by a
 * BSD-style license that can be found in the LICENSE file.
 */


import Foundation
import CoreBluetooth
import CoreLocation

class FlutterBlePeripheralManager : NSObject, CBPeripheralManagerDelegate {
    
    let stateChangedHandler: StateChangedHandler
    let gattEventHandler: GattEventHandler

    var mGattServiceCharacteristics: [String: CBMutableCharacteristic] = [:]
    var mGattService: CBMutableService?

    init(stateChangedHandler: StateChangedHandler, gattEventHandler: GattEventHandler) {
        self.stateChangedHandler = stateChangedHandler
        self.gattEventHandler = gattEventHandler
    }

    lazy var peripheralManager: CBPeripheralManager  = CBPeripheralManager(delegate: self, queue: nil)

    // min MTU before iOS 10
//    var mtu: Int = 158 {
//        didSet {
//          onMtuChanged?(mtu)
//        }
//    }

    func enable() {
      // no-op
    }

    func start(advertiseData: PeripheralData) {
        
        var dataToBeAdvertised: [String: Any]! = [:]
        if (advertiseData.uuid != nil) {
            dataToBeAdvertised[CBAdvertisementDataServiceUUIDsKey] = [CBUUID(string: advertiseData.uuid!)]
        }
        
        if (advertiseData.localName != nil) {
            dataToBeAdvertised[CBAdvertisementDataLocalNameKey] = advertiseData.localName
        }

        peripheralManager.startAdvertising(dataToBeAdvertised)
    }

    func gattServer(
            serviceUuid : String,
            primaryServiceType : Bool,
            serviceCharacteristics : [CBMutableCharacteristic]
        ) {
        print("FlutterBlePeripheralManager::Starting GattServer")
        peripheralManager.removeAllServices()

        mGattService = CBMutableService(type: CBUUID(string: serviceUuid), primary: primaryServiceType)
        mGattService!.characteristics = serviceCharacteristics
        
        for characteristic in serviceCharacteristics {
            mGattServiceCharacteristics[characteristic.uuid.uuidString] = characteristic
        }

        print("FlutterBlePeripheralManager::BLE Service Prepared")
    }

    func characteristicWrite(characteristicUuid : String, value : String) -> Bool {
        print("FlutterBlePeripheralManager::Writing Characteristic")
        if let characteristic = mGattServiceCharacteristics[characteristicUuid.uppercased()] {
            let value: Data = Data(value.utf8)
            characteristic.value = value
            let notified = peripheralManager.updateValue(
                value,
                for: characteristic,
                onSubscribedCentrals: nil
            )
            if notified {
                print("Characteristic Write: Notification Sent")
            }
            return notified
        }
        return false
    }
    
    func characteristicRead(characteristicUuid : String) -> String? {
        print("FlutterBlePeripheralManager::Reading Characteristic")
        if let characteristic = mGattServiceCharacteristics[characteristicUuid.uppercased()] {
            return String(data: characteristic.value ?? Data("".utf8), encoding: .utf8)
        }
        return nil
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        var state: PeripheralState
        switch peripheral.state {
        case .poweredOn:
            state = .idle
            if (mGattService != nil) {
                peripheralManager.add(mGattService!)
                print("FlutterBlePeripheralManager::BLE Service Added")
            }
        case .poweredOff:
            state = .poweredOff
        case .resetting:
            state = .idle
        case .unsupported:
            state = .unsupported
        case .unauthorized:
            state = .unauthorized
        case .unknown:
            state = .unknown
        @unknown default:
            state = .unknown
        }
        stateChangedHandler.publishPeripheralState(state: state)
    }
    
    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        print("didAdd:", service, error ?? "success")
    }
    
    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        print("[flutter_ble_peripheral] didStartAdvertising:", error ?? "success")
        
        guard error == nil else {
            return
        }

        stateChangedHandler.publishPeripheralState(state: .advertising)
    }

    
    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        print("didReceiveRead:", request)
        if let characteristic = mGattServiceCharacteristics[request.characteristic.uuid.uuidString] {
            request.value = characteristic.value
            peripheralManager.respond(to: request, withResult: .success)
            self.gattEventHandler.publishEventData(
                data: [
                    "event": "CharacteristicReadRequest",
                    "device": request.central.identifier.uuidString as String,
                    "characteristic": characteristic.uuid.uuidString as String,
                    "offset": request.offset as Int,
                    "value": String(data: request.value ?? Data("".utf8), encoding: .utf8) as String?,
                ]
            )
            print("didReceiveRead: Success")
        } else {
            peripheralManager.respond(to: request, withResult: .requestNotSupported)
            print("didReceiveRead: Failed")
        }
    }
    
    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        print("didReceiveWrite:", requests)
        
        guard let firstRequest = requests.first else {
            fatalError()
        }
        
        for request in requests {
            if let value: Data = request.value, request.offset > value.count {
                peripheralManager.respond(to: request, withResult: .invalidOffset)
                print("didReceiveWrite: Error offset")
                return
            }
            if let characteristic = mGattServiceCharacteristics[request.characteristic.uuid.uuidString] {
                let value: String = String(data: request.value ?? Data("".utf8), encoding: .utf8)!
                self.gattEventHandler.publishEventData(
                    data: [
                        "event": "CharacteristicWriteRequest",
                        "device": request.central.identifier.uuidString as String,
                        "characteristic": characteristic.uuid.uuidString as String,
                        "offset": request.offset as Int,
                        "value": value as String,
                    ]
                )
                characteristicWrite(characteristicUuid: characteristic.uuid.uuidString, value: value)
            } else {
                peripheralManager.respond(to: firstRequest, withResult: .requestNotSupported)
                print("didReceiveWrite: Failed")
                return
            }
        }
        peripheralManager.respond(to: firstRequest, withResult: .success)
    }
    
    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        print("didSubscribeTo:", central, characteristic)
    }
    
    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        print("didUnsubscribeFrom:", central, characteristic)
    }

}
