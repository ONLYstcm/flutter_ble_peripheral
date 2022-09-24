//
//  PeripheralData.swift
//  flutter_ble_peripheral
//
//  Created by Julian Steenbakker on 06/12/2021.
//

import Foundation

class PeripheralData {
    var uuid: String?
    var localName: String?     //CBAdvertisementDataLocalNameKey
    
    // TODO: add service data
    var serviceUUID: String = ""
    var characteristicUUIDs: [String?] = []
    
    init(uuid: String?, localName: String?) {
        self.uuid = uuid //uuid;
        self.localName = localName
    }
}
