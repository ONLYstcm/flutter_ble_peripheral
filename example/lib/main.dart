/*
 * Copyright (c) 2020. Julian Steenbakker.
 * All rights reserved. Use of this source code is governed by a
 * BSD-style license that can be found in the LICENSE file.
 */
import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_ble_peripheral/flutter_ble_peripheral.dart';
import 'package:permission_handler/permission_handler.dart';

void main() => runApp(const FlutterBlePeripheralExample());

class FlutterBlePeripheralExample extends StatefulWidget {
  const FlutterBlePeripheralExample({Key? key}) : super(key: key);

  @override
  FlutterBlePeripheralExampleState createState() =>
      FlutterBlePeripheralExampleState();
}

class FlutterBlePeripheralExampleState
    extends State<FlutterBlePeripheralExample> {
  final FlutterBlePeripheral blePeripheral = FlutterBlePeripheral();

  final AdvertiseData advertiseData = AdvertiseData(
    serviceUuid: '7CE9A896-5352-48E2-BC85-F38B9BCCBA0A',
    manufacturerId: 1234,
    manufacturerData: Uint8List.fromList([1, 2, 3, 4, 5, 6]),
  );

  final AdvertiseSettings advertiseSettings = AdvertiseSettings(
    advertiseMode: AdvertiseMode.advertiseModeBalanced,
    txPowerLevel: AdvertiseTxPower.advertiseTxPowerMedium,
    timeout: 3000,
  );

  final AdvertiseSetParameters advertiseSetParameters = AdvertiseSetParameters(
    txPowerLevel: txPowerMedium,
    legacyMode: true,
    connectable: true,
    scannable: true,
  );

  bool _isSupported = false;
  int counter = 0;
  late GattServer gattService;
  late GattCharacteristic testCharacteristic;

  StreamSubscription? streamRead;
  StreamSubscription? streamWrite;
  StreamSubscription? streamNotification;

  @override
  void initState() {
    super.initState();
    initPlatformState();
  }

  Future<void> initPlatformState() async {
    final isSupported = await blePeripheral.isSupported;
    setState(() {
      _isSupported = isSupported;
    });

    testCharacteristic = blePeripheral.characteristic(
      characteristicUuid: "94d46d34-6d23-4ef5-bd1d-4774ae25cbf8",
      properties: GattCharacteristic.PROPERTY_READ |
          GattCharacteristic.PROPERTY_WRITE |
          GattCharacteristic.PROPERTY_NOTIFY,
      permissions: GattCharacteristic.PERMISSION_READ |
          GattCharacteristic.PERMISSION_WRITE,
    );

    print(testCharacteristic.writeData("Test Data"));

    streamRead = testCharacteristic.listenRead((event) {
      print("Test read from ${event}");
    });

    streamWrite = testCharacteristic.listenWrite((event) {
      print("Test write from ${event}. "); //Data: ${event['value'].toString()}
    });

    streamNotification = testCharacteristic.listenNotificationState((event) {
      print("Test notification state from ${event}. "); //Enabled: ${event['']}
    });

    List<GattCharacteristic> characteristics = [
      testCharacteristic,
    ];

    gattService = await blePeripheral.server(
      serverUuid: "238c54d0-f5ae-4c32-9ba5-a8569c08316d",
      characteristics: characteristics,
    );
  }

  Future<void> _toggleAdvertise() async {
    if (await blePeripheral.isAdvertising) {
      await blePeripheral.stop();
    } else {
      await blePeripheral.start(
          advertiseData: advertiseData,
          advertiseSetParameters: advertiseSetParameters);
      counter++;
      print(await testCharacteristic
          .writeData("Test Data: " + counter.toString()));
      print(await testCharacteristic.readData());
    }
  }

  Future<void> _toggleAdvertiseSet() async {
    if (await blePeripheral.isAdvertising) {
      await blePeripheral.stop();
    } else {
      await blePeripheral.start(
        advertiseData: advertiseData,
        advertiseSetParameters: advertiseSetParameters,
      );
      counter++;
      print(await testCharacteristic
          .writeData("Test Data: " + counter.toString()));
      print(await testCharacteristic.readData());
    }
  }

  Future<void> _requestPermissions() async {
    final Map<Permission, PermissionStatus> statuses = await [
      Permission.bluetoothAdvertise,
      Permission.bluetoothConnect,
      Permission.bluetoothScan,
      Permission.location,
    ].request();
    for (final status in statuses.keys) {
      if (statuses[status] == PermissionStatus.granted) {
        debugPrint('$status permission granted');
      } else if (statuses[status] == PermissionStatus.denied) {
        debugPrint(
          '$status denied. Show a dialog with a reason and again ask for the permission.',
        );
      } else if (statuses[status] == PermissionStatus.permanentlyDenied) {
        debugPrint(
          '$status permanently denied. Take the user to the settings page.',
        );
      }
    }
  }

  final _messangerKey = GlobalKey<ScaffoldMessengerState>();

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      scaffoldMessengerKey: _messangerKey,
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Flutter BLE Peripheral'),
        ),
        body: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: <Widget>[
              Text('Is supported: $_isSupported'),
              StreamBuilder(
                stream: blePeripheral.onPeripheralStateChanged,
                initialData: PeripheralState.unknown,
                builder:
                    (BuildContext context, AsyncSnapshot<dynamic> snapshot) {
                  return Text(
                    'State: ${describeEnum(snapshot.data as PeripheralState)}',
                  );
                },
              ),
              // StreamBuilder(
              //     stream: blePeripheral.getDataReceived(),
              //     initialData: 'None',
              //     builder:
              //         (BuildContext context, AsyncSnapshot<dynamic> snapshot) {
              //       return Text('Data received: ${snapshot.data}');
              //     },),
              Text('Current UUID: ${advertiseData.serviceUuid}'),
              MaterialButton(
                onPressed: _toggleAdvertise,
                child: Text(
                  'Toggle advertising',
                  style: Theme.of(context)
                      .primaryTextTheme
                      .button!
                      .copyWith(color: Colors.blue),
                ),
              ),
              MaterialButton(
                onPressed: _toggleAdvertiseSet,
                child: Text(
                  'Toggle advertising set for 1 second',
                  style: Theme.of(context)
                      .primaryTextTheme
                      .button!
                      .copyWith(color: Colors.blue),
                ),
              ),
              StreamBuilder(
                stream: blePeripheral.onPeripheralStateChanged,
                initialData: PeripheralState.unknown,
                builder: (
                  BuildContext context,
                  AsyncSnapshot<PeripheralState> snapshot,
                ) {
                  return MaterialButton(
                    onPressed: snapshot.data == PeripheralState.poweredOff
                        ? () async {
                            final bool enabled = await blePeripheral
                                .enableBluetooth(askUser: false);
                            if (enabled) {
                              _messangerKey.currentState!.showSnackBar(
                                const SnackBar(
                                  content: Text('Bluetooth enabled!'),
                                  backgroundColor: Colors.green,
                                ),
                              );
                            } else {
                              _messangerKey.currentState!.showSnackBar(
                                const SnackBar(
                                  content: Text('Bluetooth not enabled!'),
                                  backgroundColor: Colors.red,
                                ),
                              );
                            }
                          }
                        : null,
                    child: Text(
                      'Enable Bluetooth (ANDROID)',
                      style: Theme.of(context)
                          .primaryTextTheme
                          .button!
                          .copyWith(color: Colors.blue),
                    ),
                  );
                },
              ),
              MaterialButton(
                onPressed: () async {
                  final bool enabled = await blePeripheral.enableBluetooth();
                  if (enabled) {
                    _messangerKey.currentState!.showSnackBar(
                      const SnackBar(
                        content: Text('Bluetooth enabled!'),
                        backgroundColor: Colors.green,
                      ),
                    );
                  } else {
                    _messangerKey.currentState!.showSnackBar(
                      const SnackBar(
                        content: Text('Bluetooth not enabled!'),
                        backgroundColor: Colors.red,
                      ),
                    );
                  }
                },
                child: Text(
                  'Ask if enable Bluetooth (ANDROID)',
                  style: Theme.of(context)
                      .primaryTextTheme
                      .button!
                      .copyWith(color: Colors.blue),
                ),
              ),
              MaterialButton(
                onPressed: _requestPermissions,
                child: Text(
                  'Request Permissions',
                  style: Theme.of(context)
                      .primaryTextTheme
                      .button!
                      .copyWith(color: Colors.blue),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
