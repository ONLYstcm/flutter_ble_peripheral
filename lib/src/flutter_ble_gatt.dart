import 'package:flutter_ble_peripheral/src/models/gatt_device.dart';
import 'package:flutter_ble_peripheral/src/models/gatt_server.dart';

import 'models/handler/gatt_handler.dart';

class Gatt {
  late final Stream<GattConnextState> connectState;

  factory Gatt() => _getInstance();
  static Gatt? _instance;

  Gatt._internal() {
    connectState = GattHandler()
        .eventStream
        .where((event) => event['event'] == 'ConnectionStateChange')
        .map(
          (event) => GattConnextState(
            GattDevice.fromMap(Map<String, dynamic>.from(event["device"])),
            event["status"] as int,
            event["newState"] as int,
          ),
        );
  }
  static Gatt _getInstance() {
    if (_instance == null) {
      _instance = new Gatt._internal();
    }
    return _instance!;
  }
}
