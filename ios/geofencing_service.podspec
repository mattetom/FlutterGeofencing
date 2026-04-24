#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint geofencing_service.podspec' to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'geofencing_service'
  s.version          = '1.1.0'
  s.summary          = 'Geofencing plugin for Flutter (enter, exit, dwell, background).'
  s.description      = <<-DESC
Register circular geofences and receive events in foreground or background
via a dedicated Dart callback. Supports Android and iOS with platform limits
and optional diagnostics (Dart API).
                       DESC
  s.homepage         = 'https://github.com/mattetom/FlutterGeofencing'
  s.license          = { :file => '../LICENSE' }
  s.author           = 'Matteo Tomasini'
  s.source           = { :path => '.' }
  s.source_files = 'Classes/**/*'
  s.public_header_files = 'Classes/**/*.h'
  s.dependency 'Flutter'
  s.platform = :ios, '8.0'

  # Flutter.framework does not contain a i386 slice. Only x86_64 simulators are supported.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'VALID_ARCHS[sdk=iphonesimulator*]' => 'x86_64' }
end
