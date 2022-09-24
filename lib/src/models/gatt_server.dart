import 'package:flutter/services.dart';
import 'package:flutter_ble_peripheral/src/models/gatt_characteristic.dart';

class GattServer {
  final MethodChannel _methodChannel;
  static const int SERVICE_TYPE_PRIMARY = 0;
  static const int SERVICE_TYPE_SECONDARY = 1;
  String uuid;
  int serviceType = SERVICE_TYPE_PRIMARY;
  List<GattCharacteristic> characteristics = [];

  GattServer(
      this._methodChannel, this.uuid, {
      bool primaryServiceType = true,
      List<GattCharacteristic>? characteristics,
  }) {
      serviceType = primaryServiceType ? SERVICE_TYPE_PRIMARY : SERVICE_TYPE_SECONDARY;
      if (characteristics != null) {
        this.characteristics.addAll(characteristics);
      }
      _methodChannel.invokeMethod("server/create", toMap());
  }

  Future<void> close() async {
    await _methodChannel.invokeMethod("server/close");
  }



  /*
  addCharacteristic(GattCharacteristic characteristic) async {
    await GattHandler.method.invokeListMethod(
      "server/addCharacteristic",
      characteristic.entityId,
    );
    characteristics.add(characteristic);
  }
  */


  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'uuid': uuid,
      'type': serviceType,
      'characteristics': characteristics.map((e) => e.toMap()).toList()
    };
  }
}
