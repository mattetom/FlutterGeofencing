// Copyright 2019 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.geofencing;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

class GeofencingRebootBroadcastReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "GeofencingPlugin"
    }
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "GeofencingRebootBroadcastReceiver.onReceive " + intent.getAction())
        if (intent.getAction().equals("android.intent.action.BOOT_COMPLETED")) {
            Log.e(TAG, "GeofencingRebootBroadcastReceiver.onReceive BOOT_COMPLETED")
            GeofencingPlugin.reRegisterAfterReboot(context)
        }
        if (intent.getAction().equals("android.intent.action.MY_PACKAGE_REPLACED")) {
            Log.i(TAG, "GeofencingRebootBroadcastReceiver.onReceive MY_PACKAGE_REPLACED")
            GeofencingPlugin.reRegisterAfterReboot(context)
        }
    }
}