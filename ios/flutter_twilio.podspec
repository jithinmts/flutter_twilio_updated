#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html
#
Pod::Spec.new do |s|
  s.name             = 'flutter_twilio'
  s.version          = '0.0.3'
  s.summary          = 'Provides an interface to Twilio&#x27;s Programmable Voice SDK to allows adding voice-over-IP (VoIP) calling into your Flutter applications.'
  s.description      = <<-DESC
Provides an interface to Twilio&#x27;s Programmable Voice SDK to allows adding voice-over-IP (VoIP) calling into your Flutter applications.
                       DESC
  s.homepage         = 'https://github.com/fedeamura/flutter_twilio'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Federico Amura' => 'fede.amura@gmail.com' }
  s.source           = { :path => '.' }
  s.source_files = 'Classes/**/*'
  s.public_header_files = 'Classes/**/*.h'
  s.dependency 'Flutter'
  s.dependency 'TwilioVoice','~> 6.0.0'

  # TwilioVoice 6.x requires iOS 12.0+; declaring 10.0 caused a pod-install
  # platform conflict in host apps.
  s.ios.deployment_target = '12.0'
  s.swift_version = '5.0'
end

