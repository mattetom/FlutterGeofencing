// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file

#import "GeofencingPlugin.h"

#import <CoreLocation/CoreLocation.h>

@implementation GeofencingPlugin {
  CLLocationManager *_locationManager;
  FlutterEngine *_headlessRunner;
  FlutterMethodChannel *_callbackChannel;
  FlutterMethodChannel *_mainChannel;
  NSObject<FlutterPluginRegistrar> *_registrar;
  NSUserDefaults *_persistentState;
  NSMutableArray *_eventQueue;
  int64_t _onLocationUpdateHandle;
}

static const NSString *kRegionKey = @"region";
static const NSString *kEventType = @"event_type";
static const int kEnterEvent = 1;
static const int kExitEvent = 2;
static const NSString *kCallbackMapping = @"geofence_region_callback_mapping";
static GeofencingPlugin *instance = nil;
static FlutterPluginRegistrantCallback registerPlugins = nil;
static BOOL initialized = NO;
static BOOL backgroundIsolateRun = NO;
#pragma mark FlutterPlugin Methods

+ (void)registerWithRegistrar:(NSObject<FlutterPluginRegistrar> *)registrar {
  @synchronized(self) {
    if (instance == nil) {
      instance = [[GeofencingPlugin alloc] init:registrar];
      [registrar addApplicationDelegate:instance];
    }
  }
}

+ (void)setPluginRegistrantCallback:(FlutterPluginRegistrantCallback)callback {
  registerPlugins = callback;
}

- (void)handleMethodCall:(FlutterMethodCall *)call result:(FlutterResult)result {
  NSArray *arguments = call.arguments;
  if ([@"GeofencingPlugin.initializeService" isEqualToString:call.method]) {
    NSAssert(arguments.count == 1,
             @"Invalid argument count for 'GeofencingPlugin.initializeService'");
    [self startGeofencingService:[arguments[0] longValue]];
    result(@(YES));
  } else if ([@"GeofencingService.initialized" isEqualToString:call.method]) {
    @synchronized(self) {
      initialized = YES;
        // Send the geofence events that occurred while the background
        // isolate was initializing.
        while ([_eventQueue count] > 0) {
            NSDictionary* event = _eventQueue[0];
            [_eventQueue removeObjectAtIndex:0];
            CLRegion* region = [event objectForKey:kRegionKey];
            int type = [[event objectForKey:kEventType] intValue];
            [self sendLocationEvent:region eventType: type];
        }
    }
    result(nil);
  } else if ([@"GeofencingPlugin.registerGeofence" isEqualToString:call.method]) {
    NSDictionary *registration = [self registerGeofence:arguments];
    BOOL success = [[registration objectForKey:@"success"] boolValue];
    if (success) {
      result(@(YES));
    } else {
      NSString *code = [registration objectForKey:@"errorCode"] ?: @"GEOFENCE_ERROR";
      NSString *message = [registration objectForKey:@"message"] ?: @"Failed to register geofence";
      NSDictionary *details = [registration objectForKey:@"details"] ?: @{};
      result([FlutterError errorWithCode:code message:message details:details]);
    }
  } else if ([@"GeofencingPlugin.removeGeofence" isEqualToString:call.method]) {
    result(@([self removeGeofence:arguments]));
  } else if ([@"GeofencingPlugin.getRegisteredGeofenceIds" isEqualToString:call.method]) {
      result([self getMonitoredRegionIds:arguments]);
  }
  else {
    result(FlutterMethodNotImplemented);
  }
}

- (BOOL)application:(UIApplication *)application
    didFinishLaunchingWithOptions:(NSDictionary *)launchOptions {
  // Check to see if we're being launched due to a location event.
  if (launchOptions[UIApplicationLaunchOptionsLocationKey] != nil) {
    // Restart the headless service.
    [self startGeofencingService:[self getCallbackDispatcherHandle]];
  }

  // Note: if we return NO, this vetos the launch of the application.
  return YES;
}

#pragma mark LocationManagerDelegate Methods
- (void)locationManager:(CLLocationManager *)manager didEnterRegion:(CLRegion *)region {
  @synchronized(self) {
    if (initialized) {
      [self sendLocationEvent:region eventType:kEnterEvent];
    } else {
      NSDictionary *dict = @{
        kRegionKey: region,
        kEventType: @(kEnterEvent)
      };
      [_eventQueue addObject:dict];
    }
  }
}

- (void)locationManager:(CLLocationManager *)manager didExitRegion:(CLRegion *)region {
  @synchronized(self) {
    if (initialized) {
      [self sendLocationEvent:region eventType:kExitEvent];
    } else {
      NSDictionary *dict = @{
        kRegionKey: region,
        kEventType: @(kExitEvent)
      };
      [_eventQueue addObject:dict];
    }
  }
}

- (void)locationManager:(CLLocationManager *)manager
    monitoringDidFailForRegion:(CLRegion *)region
                     withError:(NSError *)error {
  NSString *identifier = region != nil ? region.identifier : @"";
  NSLog(@"GeofencingPlugin: Monitoring failed for region '%@': %@", identifier, error.localizedDescription);

  NSString *errorCode;
  switch (error.code) {
    case kCLErrorRegionMonitoringDenied:
      errorCode = @"REGION_MONITORING_DENIED";
      break;
    case kCLErrorRegionMonitoringFailure:
      errorCode = @"REGION_MONITORING_FAILURE";
      break;
    case kCLErrorRegionMonitoringSetupDelayed:
      errorCode = @"REGION_MONITORING_SETUP_DELAYED";
      break;
    case kCLErrorRegionMonitoringResponseDelayed:
      errorCode = @"REGION_MONITORING_RESPONSE_DELAYED";
      break;
    default:
      errorCode = [NSString stringWithFormat:@"UNKNOWN_%ld", (long)error.code];
      break;
  }

  // Remove the failed region from our callback mapping since it's not being
  // monitored, but keep Dart-side state so the host app can decide whether to
  // re-register once the underlying condition (permission, services) changes.
  if (region != nil) {
    [self removeCallbackHandleForRegionId:identifier];
  }

  // Surface the failure to the main isolate diagnostic stream. The main
  // channel targets the host app's main isolate; if the app is suspended the
  // event will be delivered when it resumes.
  if (_mainChannel != nil) {
    [_mainChannel invokeMethod:@"GeofencingPlugin.monitoringFailed"
                     arguments:@{
                       @"id": identifier,
                       @"errorCode": errorCode,
                       @"message": error.localizedDescription ?: @"",
                     }];
  }
}

#pragma mark GeofencingPlugin Methods

- (void)sendLocationEvent:(CLRegion *)region eventType:(int)event {
  NSAssert([region isKindOfClass:[CLCircularRegion class]], @"region must be CLCircularRegion");
  CLLocationCoordinate2D center = region.center;
  int64_t handle = [self getCallbackHandleForRegionId:region.identifier];
  if (handle != 0 && _callbackChannel != nil) {
      [_callbackChannel
       invokeMethod:@""
        arguments:@[
            @(handle), @[ region.identifier ], @[ @(center.latitude), @(center.longitude) ], @(event)
        ]];
  }
}

- (instancetype)init:(NSObject<FlutterPluginRegistrar> *)registrar {
  self = [super init];
  NSAssert(self, @"super init cannot be nil");
  _persistentState = [NSUserDefaults standardUserDefaults];
  _eventQueue = [[NSMutableArray alloc] init];
  _locationManager = [[CLLocationManager alloc] init];
  [_locationManager setDelegate:self];
  // Authorization is requested by the host app via permission_handler at the
  // appropriate moment in its UX flow, so we intentionally do not call
  // requestAlwaysAuthorization here.
  // Required for CLLocationManager to deliver region events when the app is
  // backgrounded or terminated by the system. Must be set together with
  // UIBackgroundModes:[location] in the host app's Info.plist.
  _locationManager.allowsBackgroundLocationUpdates = YES;
  _locationManager.pausesLocationUpdatesAutomatically = NO;

  _headlessRunner = [[FlutterEngine alloc] initWithName:@"GeofencingIsolate" project:nil allowHeadlessExecution:YES];
  _registrar = registrar;

  _mainChannel = [FlutterMethodChannel methodChannelWithName:@"plugins.flutter.io/geofencing_plugin"
                                             binaryMessenger:[registrar messenger]];
  [registrar addMethodCallDelegate:self channel:_mainChannel];

  _callbackChannel =
      [FlutterMethodChannel methodChannelWithName:@"plugins.flutter.io/geofencing_plugin_background"
                                  binaryMessenger:_headlessRunner];
  return self;
}

- (void)startGeofencingService:(int64_t)handle {
  [self setCallbackDispatcherHandle:handle];
  FlutterCallbackInformation *info = [FlutterCallbackCache lookupCallbackInformation:handle];
  NSAssert(info != nil, @"failed to find callback");
  NSString *entrypoint = info.callbackName;
  NSString *uri = info.callbackLibraryPath;
  [_headlessRunner runWithEntrypoint:entrypoint libraryURI:uri];
  NSAssert(registerPlugins != nil, @"failed to set registerPlugins");

  // Once our headless runner has been started, we need to register the application's plugins
  // with the runner in order for them to work on the background isolate. `registerPlugins` is
  // a callback set from AppDelegate.m in the main application. This callback should register
  // all relevant plugins (excluding those which require UI).
  if (!backgroundIsolateRun) {
    registerPlugins(_headlessRunner);
  }
  [_registrar addMethodCallDelegate:self channel:_callbackChannel];
  backgroundIsolateRun = YES;
}

- (NSDictionary *)registerGeofence:(NSArray *)arguments {
  int64_t callbackHandle = [arguments[0] longLongValue];
  NSString *identifier = arguments[1];
  double latitude = [arguments[2] doubleValue];
  double longitude = [arguments[3] doubleValue];
  double radius = [arguments[4] doubleValue];
  int64_t triggerMask = [arguments[5] longLongValue];

  // Check iOS region limit (max 20 regions)
  NSUInteger currentRegionCount = [[self->_locationManager monitoredRegions] count];

  // Check if we're replacing an existing region or adding a new one
  BOOL isReplacing = NO;
  for (CLRegion *existingRegion in [self->_locationManager monitoredRegions]) {
    if ([existingRegion.identifier isEqualToString:identifier]) {
      isReplacing = YES;
      break;
    }
  }

  if (!isReplacing && currentRegionCount >= 20) {
    NSString *message = [NSString stringWithFormat:
        @"iOS limit of 20 regions reached (current: %lu)",
        (unsigned long)currentRegionCount];
    NSLog(@"GeofencingPlugin: Cannot register geofence '%@': %@", identifier, message);
    return @{
      @"success": @(NO),
      @"errorCode": @"IOS_REGION_LIMIT",
      @"message": message,
      @"details": @{@"id": identifier, @"current": @(currentRegionCount), @"max": @(20)},
    };
  }

  // Validate coordinates
  if (latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) {
    NSString *message = [NSString stringWithFormat:
        @"Invalid coordinates: lat=%f, lon=%f", latitude, longitude];
    NSLog(@"GeofencingPlugin: %@ for geofence '%@'", message, identifier);
    return @{
      @"success": @(NO),
      @"errorCode": @"INVALID_COORDINATES",
      @"message": message,
      @"details": @{@"id": identifier, @"latitude": @(latitude), @"longitude": @(longitude)},
    };
  }

  // Validate radius (iOS minimum is ~100m for reliable detection)
  if (radius < 100.0) {
    NSLog(@"GeofencingPlugin: Warning - radius %.1fm for geofence '%@' is below recommended minimum of 100m", radius, identifier);
  }

  // Clamp radius to iOS maximum
  double maxRadius = self->_locationManager.maximumRegionMonitoringDistance;
  if (radius > maxRadius) {
    NSLog(@"GeofencingPlugin: Clamping radius from %.1fm to maximum %.1fm for geofence '%@'", radius, maxRadius, identifier);
    radius = maxRadius;
  }

  CLCircularRegion *region =
      [[CLCircularRegion alloc] initWithCenter:CLLocationCoordinate2DMake(latitude, longitude)
                                        radius:radius
                                    identifier:identifier];
  region.notifyOnEntry = ((triggerMask & 0x1) != 0);
  region.notifyOnExit = ((triggerMask & 0x2) != 0);

  [self setCallbackHandleForRegionId:callbackHandle regionId:identifier];
  [self->_locationManager startMonitoringForRegion:region];

  NSLog(@"GeofencingPlugin: Registered geofence '%@' at (%.6f, %.6f) with radius %.1fm", identifier, latitude, longitude, radius);
  return @{@"success": @(YES), @"details": @{@"id": identifier}};
}

- (BOOL)removeGeofence:(NSArray *)arguments {
  NSString *identifier = arguments[0];
  for (CLRegion *region in [self->_locationManager monitoredRegions]) {
    if ([region.identifier isEqual:identifier]) {
      [self->_locationManager stopMonitoringForRegion:region];
      [self removeCallbackHandleForRegionId:identifier];
      return YES;
    }
  }
  return NO;
}

-(NSArray*)getMonitoredRegionIds:()arguments{
    NSMutableArray *geofenceIds = [[NSMutableArray alloc] init];
    for (CLRegion *region in [self->_locationManager monitoredRegions]) {
        [geofenceIds addObject:region.identifier];
    }
    return [NSArray arrayWithArray:geofenceIds];
}

- (int64_t)getCallbackDispatcherHandle {
  id handle = [_persistentState objectForKey:@"callback_dispatcher_handle"];
  if (handle == nil) {
    return 0;
  }
  return [handle longLongValue];
}

- (void)setCallbackDispatcherHandle:(int64_t)handle {
  [_persistentState setObject:[NSNumber numberWithLongLong:handle]
                       forKey:@"callback_dispatcher_handle"];
}

- (NSMutableDictionary *)getRegionCallbackMapping {
  const NSString *key = kCallbackMapping;
  NSMutableDictionary *callbackDict = [_persistentState dictionaryForKey:key];
  if (callbackDict == nil) {
    callbackDict = @{};
    [_persistentState setObject:callbackDict forKey:key];
  }
  return [callbackDict mutableCopy];
}

- (void)setRegionCallbackMapping:(NSMutableDictionary *)mapping {
  const NSString *key = kCallbackMapping;
  NSAssert(mapping != nil, @"mapping cannot be nil");
  [_persistentState setObject:mapping forKey:key];
}

- (int64_t)getCallbackHandleForRegionId:(NSString *)identifier {
  NSMutableDictionary *mapping = [self getRegionCallbackMapping];
  id handle = [mapping objectForKey:identifier];
  if (handle == nil) {
    return 0;
  }
  return [handle longLongValue];
}

- (void)setCallbackHandleForRegionId:(int64_t)handle regionId:(NSString *)identifier {
  NSMutableDictionary *mapping = [self getRegionCallbackMapping];
  [mapping setObject:[NSNumber numberWithLongLong:handle] forKey:identifier];
  [self setRegionCallbackMapping:mapping];
}

- (void)removeCallbackHandleForRegionId:(NSString *)identifier {
  NSMutableDictionary *mapping = [self getRegionCallbackMapping];
  [mapping removeObjectForKey:identifier];
  [self setRegionCallbackMapping:mapping];
}

@end
