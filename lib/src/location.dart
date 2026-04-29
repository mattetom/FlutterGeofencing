// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Internal.
Location locationFromList(List<double> l) => Location._fromList(l);

Location locationFromCallback({
  required List<double> coords,
  int? timeMillisSinceEpoch,
}) =>
    Location(
      coords.isNotEmpty ? coords[0] : 0,
      coords.length > 1 ? coords[1] : 0,
      time: timeMillisSinceEpoch == null
          ? null
          : DateTime.fromMillisecondsSinceEpoch(timeMillisSinceEpoch,
              isUtc: true),
    );

/// A simple representation of a geographic location.
class Location {
  final double latitude;
  final double longitude;

  /// Time the underlying OS-side trigger fix was produced, when available.
  /// On Android this is `triggeringLocation.time` from the Play Services
  /// `GeofencingEvent` (i.e. when the GPS/network fix that satisfied the
  /// transition was acquired). On iOS this is the wall-clock timestamp
  /// captured the moment `CLLocationManager` invoked
  /// `didEnter`/`didExitRegion`, since `CLRegion` itself does not carry one.
  /// Null on plugin versions < 1.2.0 or when the platform did not provide it.
  final DateTime? time;

  const Location(this.latitude, this.longitude, {this.time});

  Location._fromList(List<double> l)
      : assert(l.length == 2),
        latitude = l[0],
        longitude = l[1],
        time = null;

  @override
  String toString() => '($latitude, $longitude${time != null ? ', $time' : ''})';
}
