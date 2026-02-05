#ifndef GeofencingPlugin_h
#define GeofencingPlugin_h

#import <Flutter/Flutter.h>

#import <CoreLocation/CoreLocation.h>

@interface GeofencingPlugin : NSObject<FlutterPlugin, CLLocationManagerDelegate>

+ (void)setPluginRegistrantCallback:(FlutterPluginRegistrantCallback)callback;

@end
#endif
