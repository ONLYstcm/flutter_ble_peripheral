import 'dart:async';

class GattCharacteristic {
  static const int PROPERTY_BROADCAST = 0x01;
  static const int PROPERTY_READ = 0x02;
  static const int PROPERTY_WRITE_NO_RESPONSE = 0x04;
  static const int PROPERTY_WRITE = 0x08;
  static const int PROPERTY_NOTIFY = 0x10;
  static const int PROPERTY_INDICATE = 0x20;
  static const int PROPERTY_SIGNED_WRITE = 0x40;
  static const int PROPERTY_EXTENDED_PROPS = 0x80;

  static const int PERMISSION_READ = 0x01;
  static const int PERMISSION_READ_ENCRYPTED = 0x02;
  static const int PERMISSION_READ_ENCRYPTED_MITM = 0x04;
  static const int PERMISSION_WRITE = 0x10;
  static const int PERMISSION_WRITE_ENCRYPTED = 0x20;
  static const int PERMISSION_WRITE_ENCRYPTED_MITM = 0x40;
  static const int PERMISSION_WRITE_SIGNED = 0x80;
  static const int PERMISSION_WRITE_SIGNED_MITM = 0x100;

  static const String NotifyDescriptorUuid = "00002902-0000-1000-8000-00805f9b34fb";

  // Event channel for Gatt server defined in flutter_ble_peripheral
  final Stream<Map<String, dynamic>> _eventStream;

  String uuid;
  int properties = 0;
  int permissions = 0;

  GattCharacteristic(this._eventStream, this.uuid, {this.properties = 0, this.permissions = 0}) {
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


  StreamSubscription listenRead(void Function(String device, int requestId, int offset) onRead) {
    return _eventStream
        .where((event) => event['event'] == 'CharacteristicReadRequest')
        .listen((event) {
          print(event);
          onRead(
              event['device'] as String,
              event['requestId'] as int,
              event['offset'] as int,
          );
    });
  }

  StreamSubscription listenWrite(void Function(
      String device,
      int requestId,
      int offset,
      bool preparedWrite,
      bool responseNeeded,
      List value,
    ) onWrite) {
    return _eventStream
        .where((event) => event['event'] == 'CharacteristicWriteRequest')
        .listen((event) {
          onWrite(
            event['device'] as String,
            event['requestId'] as int,
            event['offset'] as int,
            event['preparedWrite'] as bool,
            event['responseNeeded'] as bool,
            event['value'] as List,
          );
        });
  }

  StreamSubscription listenNotificationState(void Function(String device, bool enabled) onStateChange) {
    return _eventStream
        .where((event) => event['event'] == 'NotificationStateChange')
        .listen((event) {
          onStateChange(
              event['device'] as String,
              event['enabled'] as bool,
          );
        });
  }

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'uuid': uuid,
      'properties': properties,
      'permissions': permissions,
    };
  }
}
