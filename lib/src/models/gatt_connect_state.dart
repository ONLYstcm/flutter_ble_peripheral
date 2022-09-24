import 'package:flutter_ble_peripheral/src/models/gatt_device.dart';

class GattConnextState {
  static const int STATE_DISCONNECTED = 0;
  static const int STATE_CONNECTED = 2;

  GattDevice device;
  int status;
  int newState;

  GattConnextState(this.device, this.status, this.newState);
}
