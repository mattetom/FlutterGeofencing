// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:async';
import 'dart:io';
import 'dart:isolate';
import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';

import 'package:geofencing_service/geofencing_service.dart';
import 'package:permission_handler/permission_handler.dart';

void main() => runApp(MyApp());

class MyApp extends StatefulWidget {
  @override
  _MyAppState createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  String geofenceState = 'N/A';
  List<String> registeredGeofences = [];
  double latitude = 45.675120;
  double longitude = 8.952792;
  double radius = 200.0;
  ReceivePort port = ReceivePort();
  final List<GeofenceEvent> triggers = <GeofenceEvent>[
    GeofenceEvent.enter,
    GeofenceEvent.exit
  ];
  final AndroidGeofencingSettings androidSettings = AndroidGeofencingSettings(
    initialTrigger: <GeofenceEvent>[GeofenceEvent.enter, GeofenceEvent.exit],
    loiteringDelay: 0,
    notificationResponsiveness: 0,
  );

  @override
  void initState() {
    super.initState();
    IsolateNameServer.registerPortWithName(
      port.sendPort,
      'geofencing_send_port',
    );
    port.listen((dynamic data) {
      print('Event: $data');
      sendGeofenceNotification(
          data == '0' ? GeofenceEvent.enter : GeofenceEvent.exit);
      setState(() {
        geofenceState = data;
      });
    });
    initPlatformState();
  }

  void registerGeofence() async {
    final firstPermission = await Permission.locationWhenInUse.request();
    final secondPermission = await Permission.locationAlways.request();
    if (firstPermission.isGranted && secondPermission.isGranted) {
      await GeofencingManager.registerGeofence(
        GeofenceRegion(
          'gfp',
          latitude,
          longitude,
          radius,
          triggers,
          androidSettings,
        ),
        callback,
      );
      final registeredIds = await GeofencingManager.getRegisteredGeofenceIds();
      setState(() {
        registeredGeofences = registeredIds;
      });
    }
  }

  void unregisteGeofence() async {
    await GeofencingManager.removeGeofenceById('gfp');
    final registeredIds = await GeofencingManager.getRegisteredGeofenceIds();
    setState(() {
      registeredGeofences = registeredIds;
    });
  }

  @pragma('vm:entry-point')
  static void callback(List<String> ids, Location l, GeofenceEvent e) async {
    print('Fences: $ids Location $l Event: $e');
    final SendPort? send =
        IsolateNameServer.lookupPortByName('geofencing_send_port');
    if (send != null)
      send.send(e == GeofenceEvent.enter ? '0' : '1');
    else {
      print("SendPort is null");
      sendGeofenceNotification(e);
    }
  }

  Future<void> initPlatformState() async {
    print('Initializing...');
    await GeofencingManager.initialize();
    print('Initialization done');
    print('Retrieving registered geofence ids...');
    final registeredIds = await GeofencingManager.getRegisteredGeofenceIds();
    setState(() {
      registeredGeofences = registeredIds;
    });
    print('Retrieving registered geofence ids done');
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
          appBar: AppBar(
            title: const Text('Flutter Geofencing Example'),
          ),
          body: Container(
              padding: const EdgeInsets.all(20.0),
              child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: <Widget>[
                    Text('Current state: $geofenceState'),
                    Center(
                      child: TextButton(
                        child: const Text('Register'),
                        onPressed: registerGeofence,
                      ),
                    ),
                    Text('Registered Geofences: $registeredGeofences'),
                    Center(
                      child: TextButton(
                        child: const Text('Unregister'),
                        onPressed: unregisteGeofence,
                      ),
                    ),
                    TextField(
                      decoration: const InputDecoration(
                        hintText: 'Latitude',
                      ),
                      keyboardType: TextInputType.number,
                      controller:
                          TextEditingController(text: latitude.toString()),
                      onChanged: (String s) {
                        latitude = double.tryParse(s)!;
                      },
                    ),
                    TextField(
                        decoration:
                            const InputDecoration(hintText: 'Longitude'),
                        keyboardType: TextInputType.number,
                        controller:
                            TextEditingController(text: longitude.toString()),
                        onChanged: (String s) {
                          longitude = double.tryParse(s)!;
                        }),
                    TextField(
                        decoration: const InputDecoration(hintText: 'Radius'),
                        keyboardType: TextInputType.number,
                        controller:
                            TextEditingController(text: radius.toString()),
                        onChanged: (String s) {
                          radius = double.tryParse(s)!;
                        }),
                  ]))),
    );
  }

  /// Banco di prova: invece di notificare, appende una riga al log con la
  /// scomposizione del ritardo di consegna. E' la stessa misura usata sul
  /// device reale, ridotta al minimo indispensabile e senza dipendenze.
  ///
  ///   gms    = dal fix di posizione all'ingresso del broadcast receiver
  ///   loader = init sincrona del Flutter loader dentro il receiver
  ///   queue  = attesa nella coda del JobScheduler (il sospetto principale)
  ///   engine = avvio dell'isolate di background fino a qui
  static Future<void> sendGeofenceNotification(GeofenceEvent e) async {
    final timings = GeofencingManager.lastEventDeliveryTimings;
    final nowMs = DateTime.now().millisecondsSinceEpoch;
    final parts = <String>[
      DateTime.now().toIso8601String(),
      e == GeofenceEvent.enter ? 'enter' : 'exit',
      'initial=${GeofencingManager.lastEventWasInitialTrigger}',
    ];
    if (timings != null && timings.length >= 3 && timings[0] != 0) {
      parts.addAll(<String>[
        'loader_ms=${timings[1] - timings[0]}',
        'queue_ms=${timings[2] - timings[1]}',
        'engine_ms=${nowMs - timings[2]}',
      ]);
    }
    try {
      final dir = await getExternalStorageDirectory() ??
          await getApplicationDocumentsDirectory();
      await File('${dir.path}/geofence_bench.log')
          .writeAsString('${parts.join("|")}\n',
              mode: FileMode.append, flush: true);
    } catch (err) {
      print('bench log failed: $err');
    }
    print('BENCH ${parts.join("|")}');
  }
}
