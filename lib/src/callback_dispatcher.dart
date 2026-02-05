// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'package:geofencing/src/geofencing.dart';
import 'package:geofencing/src/location.dart';

@pragma('vm:entry-point') 
void callbackDispatcher() {
  const MethodChannel _backgroundChannel =
      MethodChannel('plugins.flutter.io/geofencing_plugin_background');
  WidgetsFlutterBinding.ensureInitialized();

  _backgroundChannel.setMethodCallHandler((MethodCall call) async {
    try {
      final List<dynamic> args =
          (call.arguments as List<dynamic>?) ?? <dynamic>[];

      if (args.length < 4) {
        print('GeofencingPlugin: Invalid callback arguments received');
        return;
      }
      
      final Function? callback = PluginUtilities.getCallbackFromHandle(
          CallbackHandle.fromRawHandle(args[0]));
      
      // Use proper null check instead of assert (assert is stripped in release mode)
      if (callback == null) {
        print('GeofencingPlugin: Failed to retrieve callback from handle ${args[0]}');
        return;
      }
      
      final List<String> triggeringGeofences = args[1]?.cast<String>() ?? <String>[];
      final List<double> locationList = <double>[];
      
      // 0.0 becomes 0 somewhere during the method call, resulting in wrong
      // runtime type (int instead of double). This is a simple way to get
      // around casting in another complicated manner.
      if (args[2] != null) {
        args[2].forEach((dynamic e) => locationList.add(double.parse(e.toString())));
      }
      
      final Location triggeringLocation = locationFromList(locationList);
      final GeofenceEvent event = intToGeofenceEvent(args[3]);
      
      // Call the user's callback with try-catch to prevent crashes
      try {
        callback(triggeringGeofences, triggeringLocation, event);
      } catch (e, stackTrace) {
        print('GeofencingPlugin: Error in user callback: $e');
        print(stackTrace);
      }
    } catch (e, stackTrace) {
      print('GeofencingPlugin: Error in callback dispatcher: $e');
      print(stackTrace);
    }
  });
  
  _backgroundChannel.invokeMethod('GeofencingService.initialized');
}
