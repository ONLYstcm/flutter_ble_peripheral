import 'package:flutter/services.dart';

class GattHandler {
  static const method = const MethodChannel('m:kbp/gatt');
  static const event = const EventChannel("e:kbp/gatt");

  late final Stream<Map<String, dynamic>> eventStream;

  factory GattHandler() => _getInstance();
  static GattHandler? _instance;

  GattHandler._internal() {
    eventStream = event
        .receiveBroadcastStream()
        .map((event) => Map<String, dynamic>.from(event));
  }
  static GattHandler _getInstance() {
    if (_instance == null) {
      _instance = new GattHandler._internal();
    }
    return _instance!;
  }
}
