Pod::Spec.new do |s|
  s.name             = 'pathfinder_ffi'
  s.version          = '0.3.0'
  s.summary          = 'Offline routing engine for Flutter (dart:ffi).'
  s.homepage         = 'https://github.com/fussy0064/pathfinder-app'
  s.license          = { :type => 'MIT' }
  s.author           = { 'pathfinder' => 'pathfinder@example.com' }
  s.source           = { :path => '.' }
  s.source_files     = 'Classes/**/*'
  s.dependency 'Flutter'
  s.platform         = :ios, '12.0'
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'CLANG_CXX_LANGUAGE_STANDARD' => 'c++17',
    'HEADER_SEARCH_PATHS' => '"${PODS_TARGET_SRCROOT}/../../../core/include" "${PODS_TARGET_SRCROOT}/../../../core/src"'
  }
end
