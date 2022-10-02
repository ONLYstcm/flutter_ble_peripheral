import 'dart:async';
import 'dart:io' show Platform;

import 'package:flutter/services.dart';

class GattCharacteristic {
  final MethodChannel _methodChannel;

  static const int PROPERTY_BROADCAST = 0x01;
  static const int PROPERTY_READ = 0x02;
  static const int PROPERTY_WRITE_NO_RESPONSE = 0x04;
  static const int PROPERTY_WRITE = 0x08;
  static const int PROPERTY_NOTIFY = 0x10;
  static const int PROPERTY_INDICATE = 0x20;
  static const int PROPERTY_SIGNED_WRITE = 0x40;
  static const int PROPERTY_EXTENDED_PROPS = 0x80;
  static const int PROPERTY_NOTIFY_ENCRYPTED = 0x100;
  static const int PROPERTY_INDICATE_ENCRYPTED = 0x200;

  // Check if platform is android otherwise assume iOS
  static const int PERMISSION_READ = 0x01;
  static int PERMISSION_READ_ENCRYPTED = Platform.isAndroid ? 0x02 : 0x04;
  static const int PERMISSION_READ_ENCRYPTED_MITM = 0x04;
  static int PERMISSION_WRITE = Platform.isAndroid ? 0x10 : 0x02;
  static int PERMISSION_WRITE_ENCRYPTED = Platform.isAndroid ? 0x20 : 0x08;
  static const int PERMISSION_WRITE_ENCRYPTED_MITM = 0x40;
  static const int PERMISSION_WRITE_SIGNED = 0x80;
  static const int PERMISSION_WRITE_SIGNED_MITM = 0x100;

  static const String NotifyDescriptorUuid =
      "00002902-0000-1000-8000-00805f9b34fb";

  // Event channel for Gatt server defined in flutter_ble_peripheral
  final Stream<Map<String, dynamic>> _eventStream;

  String uuid;
  String data = "";
  int properties = 0;
  int permissions = 0;

  GattCharacteristic(
    this._methodChannel,
    this._eventStream,
    this.uuid, {
    this.properties = 0,
    this.permissions = 0,
    this.data = "",
  }) {
    /*
    if ((properties & PROPERTY_NOTIFY) != 0) {
      val descriptor = BluetoothGattDescriptor(
          UUID.fromString(NotifyDescriptorUuid),
          BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
      )
      val characteristic = BluetoothGattCharacteristic(UUID.fromString(uuid), properties, permissions)
      descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
      characteristic.addDescriptor(descriptor)
    }
     */
  }

  Future<bool> writeData(String value) async {
    final String previousData = data;
    data = value;
    final bool success = await _methodChannel.invokeMethod<bool>(
          "characteristic/write",
          toMap(),
        ) ??
        false;
    if (!success) {
      data = previousData;
    }
    return success;
  }

  Future<Uint8List> readData() async {
    final Uint8List byteArray = await _methodChannel.invokeMethod(
      "characteristic/read",
      toMap(),
    ) as Uint8List;
    return byteArray;
  }

  StreamSubscription listenRead(void Function(Map event) onRead) {
    return _eventStream
        .where((event) => event['event'] == 'CharacteristicReadRequest')
        .listen((event) {
      print(event);
      onRead(
        event,
      );
    });
  }

  StreamSubscription listenWrite(void Function(Map event) onWrite) {
    return _eventStream
        .where((event) => event['event'] == 'CharacteristicWriteRequest')
        .listen((event) {
      onWrite(
        event,
      );
    });
  }

  StreamSubscription listenNotificationState(
      void Function(Map event) onStateChange) {
    return _eventStream
        .where((event) => event['event'] == 'NotificationStateChange')
        .listen((event) {
      onStateChange(
        event,
      );
    });
  }

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'uuid': uuid,
      'data': data,
      'properties': properties,
      'permissions': permissions,
    };
  }
}
