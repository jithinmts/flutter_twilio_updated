import Flutter
import UIKit
import AVFoundation
import PushKit
import TwilioVoice
import CallKit

public class SwiftFlutterTwilioPlugin: NSObject, FlutterPlugin,   NotificationDelegate, AVAudioPlayerDelegate {
        
    var deviceTokenString: Data?
    var callTo: String = ""
    var callData: NSDictionary?
    var callStatus: String = ""
    var callInvite: CallInvite?
    var fromDisplayName: String?
    var toDisplayName: String?
    var call: Call?
    var result: FlutterResult?
    
    var voipRegistry: PKPushRegistry
    var incomingPushCompletionCallback: (()->Swift.Void?)? = nil
    var callKitCompletionCallback: ((Bool)->Swift.Void?)? = nil
    // The SDK marks -init unavailable ("Use `audioDevice` to create a
    // `TVODefaultAudioDevice`"), so go through the documented factory.
    var audioDevice: DefaultAudioDevice = DefaultAudioDevice.audioDevice()
    var callKitProvider: CXProvider
    var callKitCallController: CXCallController
    var userInitiatedDisconnect: Bool = false
    var channel: FlutterMethodChannel?
    /// Set when register() arrived before PushKit had a VoIP token, so the registration
    /// can be retried as soon as the token shows up.
    var registrationPending = false
    /// Kept separate from `result` so settling a hangUp can never steal the reply
    /// belonging to an in-flight makeCall/register.
    var hangUpResult: FlutterResult?
    public override init() {
        
        //isSpinning = false
        voipRegistry = PKPushRegistry.init(queue: DispatchQueue.main)
        // Host apps are not guaranteed to define CFBundleName; force-unwrapping here
        // crashed at plugin registration.
        let appName = (Bundle.main.infoDictionary?["CFBundleName"] as? String)
            ?? (Bundle.main.infoDictionary?["CFBundleDisplayName"] as? String)
            ?? "Call"
        let configuration = CXProviderConfiguration(localizedName: appName)
        configuration.maximumCallGroups = 1
        configuration.maximumCallsPerCallGroup = 1
        configuration.supportsVideo = false
        configuration.includesCallsInRecents = false
        if let callKitIcon = UIImage(named: "iconMask80") {
            configuration.iconTemplateImageData = callKitIcon.pngData()
        }
        
        callKitProvider = CXProvider(configuration: configuration)
        callKitCallController = CXCallController()
        
        //super.init(coder: aDecoder)
        super.init()
        TwilioVoice.audioDevice = self.audioDevice
        callKitProvider.setDelegate(self, queue: nil)
        
        voipRegistry.delegate = self
        voipRegistry.desiredPushTypes = Set([PKPushType.voIP])
        
    }

    func getChannel() -> FlutterMethodChannel? {
        return channel
    }
    
    deinit {
        // CallKit has an odd API contract where the developer must call invalidate or the CXProvider is leaked.
        callKitProvider.invalidate()
    }
    
    
    public static func register(with registrar: FlutterPluginRegistrar) {
        let instance = SwiftFlutterTwilioPlugin()
        let methodChannel = FlutterMethodChannel(
                name: "flutter_twilio",
                binaryMessenger: registrar.messenger()
            )

            instance.channel = FlutterMethodChannel(
                name: "flutter_twilio_response",
                binaryMessenger: registrar.messenger()
            )

            registrar.addMethodCallDelegate(instance, channel: methodChannel)
    }
    
    public func handle(_ flutterCall: FlutterMethodCall, result: @escaping FlutterResult) {
        let arguments = flutterCall.arguments as? NSDictionary

        if flutterCall.method == "makeCall" {
            guard let callTo = arguments?["to"] as? String else {
                result(FlutterError(code: "BAD_ARGS", message: "Missing 'to'", details: nil))
                return
            }
            guard let callData = arguments?["data"] as? NSDictionary else {
                result(FlutterError(code: "BAD_ARGS", message: "Missing 'data'", details: nil))
                return
            }


            var fromDisplayName: String? = nil
            var toDisplayName: String? = nil

            if callData["fromDisplayName"] != nil {
                fromDisplayName = callData["fromDisplayName"] as? String ?? ""
            }

            if callData["toDisplayName"] != nil {
                toDisplayName = callData["toDisplayName"]  as? String ?? ""
            }

            self.callInvite = nil
            self.callTo = callTo
            self.callData = callData
            self.callStatus = "callConnecting"
            self.fromDisplayName = fromDisplayName
            self.toDisplayName = toDisplayName
            self.result = result

            makeCall(to: callTo)
            self.getChannel()?.invokeMethod("callConnecting", arguments: self.getCallResult())
            return
        }
        
        if flutterCall.method == "toggleMute"
        {
            if self.call == nil{
                result(FlutterError.init(
                    code: "No call",
                    message: "There is no an active call",
                    details: "There is no an active call"
                ))
                return
            }
            
            let isMuted: Bool = !self.call!.isMuted
            self.call!.isMuted = isMuted
            self.getChannel()?.invokeMethod(self.callStatus, arguments: self.getCallResult())
            result(isMuted)
            return
        }
        
        if flutterCall.method == "isMuted"
        {
            if self.call == nil{
                result(FlutterError.init(
                    code: "No call",
                    message: "There is no an active call",
                    details: "There is no an active call"
                ))
                return
            }
            
            result(self.call?.isMuted ?? false)
            return
        }
        
        if flutterCall.method == "toggleSpeaker"
        {
            if self.call == nil{
                result(FlutterError.init(
                    code: "No call",
                    message: "There is no an active call",
                    details: "There is no an active call"
                ))
                return
            }
            
            // Apply synchronously, then report the route we actually ended up on rather
            // than the one we asked for - if the override fails the UI must not claim
            // speaker is on.
            self.toggleAudioRoute(toSpeaker: !self.isSpeaker())
            let isSpeaker = self.isSpeaker()
            self.getChannel()?.invokeMethod(self.callStatus, arguments: self.getCallResult())
            result(isSpeaker)
            return
        }
        
        if flutterCall.method == "isSpeaker"
        {
            if self.call == nil{
                result(FlutterError.init(
                    code: "No call",
                    message: "There is no an active call",
                    details: "There is no an active call"
                ))
                return
            }
            
            result(self.isSpeaker())
            return
        }
        
        if flutterCall.method == "register"
        {
            guard let accessToken = arguments?["accessToken"] as? String else {
                self.getChannel()?.invokeMethod("registrationFailed", arguments: "")
                result(FlutterError(code: "BAD_ARGS", message: "Missing 'accessToken'", details: nil))
                return
            }

            self.storeAccessToken(token: accessToken)

            self.result = result;
            self.registerTwilio()
            return
        }
        
        if flutterCall.method == "unregister"
        {
            self.result = result
            self.unregisterTwilio()
            return
        }
        
        if flutterCall.method == "hangUp"
        {
            if let activeCall = self.call {
                // The reply is settled from clearCallState() once Twilio reports the
                // disconnect. Previously nothing ever called it and hangUp() hung forever.
                self.hangUpResult = result
                self.userInitiatedDisconnect = true
                performEndCallAction(uuid: activeCall.uuid ?? UUID())
            } else if let pendingInvite = self.callInvite {
                // Declining a still-ringing invite from the Flutter UI: reject it and tell
                // CallKit, otherwise the incoming-call screen stays up and the caller
                // keeps ringing until iOS times out.
                pendingInvite.reject()
                let uuid = pendingInvite.uuid
                self.callInvite = nil
                self.callTo = ""
                self.fromDisplayName = nil
                self.toDisplayName = nil
                self.callKitProvider.reportCall(with: uuid, endedAt: Date(), reason: .declinedElsewhere)
                self.callStatus = "callDisconnected"
                self.getChannel()?.invokeMethod("callDisconnected", arguments: nil)
                result("")
            } else {
                self.callTo = ""
                self.fromDisplayName = nil
                self.toDisplayName = nil
                result("")
            }
            return
        }
        
        if flutterCall.method == "activeCall"
        {
            if(self.call == nil){
                result("")
            }else{
                result(self.getCallResult())
            }
            return
        }
        
        if flutterCall.method == "setContactData"
        {
            guard let data = arguments?["contacts"] as? NSDictionary else {
                result(FlutterError(code: "BAD_ARGS", message: "Missing 'contacts'", details: nil))
                return
            }

            let defaultDisplayName = arguments?["defaultDisplayName"] as? String ?? ""

            self.storeContactData(data: data, defaultDisplayName: defaultDisplayName)
            result("")
            return
        }
        if flutterCall.method == "sendDigits"
                {
                    guard let digits = arguments?["digits"] as? String else {
                        result(FlutterError(code: "BAD_ARGS", message: "Missing 'digits'", details: nil))
                        return
                    }

                    self.sendDigits(digits: digits)
                    result("")
                    return

                }

        // Anything unhandled must still reply, or the Dart Future never completes.
        result(FlutterMethodNotImplemented)
    }
    
    func registerTwilio() {
        guard let accessToken = getAccessToken() else {
            self.getChannel()?.invokeMethod("registrationFailed", arguments: "")
            self.result?(FlutterError.init(
                code: "No access token",
                message: "No access token",
                details: "No access token"
            ))
            self.result = nil
            return
        }

        guard let deviceToken = self.deviceTokenString else {
            // PushKit has not handed us a VoIP token yet. Remember that a registration is
            // owed so pushRegistry(didUpdate:) can complete it, otherwise the device is
            // never registered and no incoming calls arrive at all.
            NSLog("No device token yet. Deferring registration until PushKit delivers one.")
            self.registrationPending = true
            self.result?("")
            self.result = nil
            return
        }

        self.registrationPending = false


        TwilioVoice.register(accessToken: accessToken, deviceToken: deviceToken) { (error) in
            if(error != nil){
                NSLog(error!.localizedDescription)

                // Send event to Flutter
                        DispatchQueue.main.async {
                            self.getChannel()?.invokeMethod("registrationFailed", arguments: "")
                        }

                        // Return error to Flutter
                        self.result?(FlutterError(
                            code: "REGISTER_ERROR",
                            message: "Twilio registration failed",
                            details: error?.localizedDescription ?? "Unknown error"
                        ))
            } else {
                DispatchQueue.main.async {
                            self.getChannel()?.invokeMethod("registrationSuccess", arguments: "")
                        }

                        self.result?("")
            }
            self.result = nil
        }
    }
    
    func unregisterTwilio(){
        self.registrationPending = false

        // Nothing to unregister, but the Dart Future still has to complete.
        guard let accessToken = getAccessToken() else {
            self.result?("")
            self.result = nil
            return
        }

        guard let deviceToken = self.deviceTokenString else {
            self.removeAccessToken()
            self.result?("")
            self.result = nil
            return
        }


        self.removeAccessToken()
        TwilioVoice.unregister(accessToken: accessToken, deviceToken: deviceToken) {(error) in
            if(error != nil){
                NSLog(error!.localizedDescription)

                self.result?(FlutterError.init(
                    code: "Error",
                    message: "Error",
                    details: "Error"
                ))
            } else {
                self.result?("")
            }
            self.result = nil
        }
    }
    
    func makeCall(to: String) {
        // ✅ Clear stale call references
            if let activeCall = self.call {
                if activeCall.state == .disconnected {
                    self.call = nil
                } else {
                    self.result?(FlutterError(
                        code: "CALL_ACTIVE",
                        message: "Another call is already active",
                        details: "Disconnect existing call first"
                    ))
                    self.result = nil
                    return
                }
            }

            let uuid = UUID()
            self.performStartCallAction(uuid: uuid)
    }
    
    
    func storeContactData(data: NSDictionary, defaultDisplayName: String)->Void{
        UserDefaults.standard.set(defaultDisplayName, forKey: "_defaultDisplayName")

        for phoneNumber in data.allKeys{
            let item = data[phoneNumber] as? NSDictionary
            let displayName = item?["displayName"] as? String ?? ""
            let photoURL = item?["photoURL"] as? String ?? ""
            UserDefaults.standard.set(displayName + ";" + photoURL, forKey: phoneNumber as? String ?? "")
        }
    }
    
    func hasContactDisplayName(phoneNumber: String) -> Bool{
        if let value = UserDefaults.standard.string(forKey: phoneNumber) {
            let parts = value.components(separatedBy: ";")
            if parts.count == 2 {
                let name =  String(parts[0])
                if name == "" {
                    NSLog("Contact name saved is blanck " + value + " for number " + phoneNumber)
                    return false
                }
                return true
            }else{
                NSLog("Wrong contact name saved " + value + ". Parts length: " + parts.count.description + " for number " + phoneNumber)
                return false
            }
        } else {
            NSLog("No contact name saved for number " + phoneNumber)
            return false
        }
    }
    
    
    func getContactDisplayName(phoneNumber: String)-> String{
        let defaultDisplayName = UserDefaults.standard.string(forKey: "_defaultDisplayName") ?? ""
        
        if let value = UserDefaults.standard.string(forKey: phoneNumber) {
            let parts = value.components(separatedBy: ";")
            if parts.count == 2 {
                let name =  parts[0]
                if name == "" {
                    NSLog("Contact name saved is blanck " + value + " for number " + phoneNumber)
                    return defaultDisplayName
                }
                return name
            }else{
                NSLog("Wrong contact name saved " + value + ". Parts length: " + parts.count.description + " for number " + phoneNumber)
                return defaultDisplayName
            }
            
        } else {
            NSLog("No contact name saved for number " + phoneNumber)
            return phoneNumber
        }
    }
    
    func getContactPhotoURL(phoneNumber: String)-> String{
        if let value = UserDefaults.standard.string(forKey: phoneNumber) {
            let parts = value.components(separatedBy: ";")
            if parts.count == 2 {
                return parts[1]
            }else{
                NSLog("Wrong contact photo URL saved " + value + ". Parts length: " + parts.count.description + " for number " + phoneNumber)
                return ""
            }
        } else {
            NSLog("No contact url saved for number " + phoneNumber)
            return ""
        }
    }

    func storeAccessToken(token: String) -> Void{
        UserDefaults.standard.set(token, forKey: "_accessToken");
    }
    
    func getAccessToken() -> String? {
        return UserDefaults.standard.string(forKey: "_accessToken")
    }
    
    func removeAccessToken() -> Void{
        UserDefaults.standard.removeObject(forKey: "_accessToken")
    }
    
    func getCallResult() -> [String: Any?]{
        var callResult: [String: Any] = [:];
        if(self.call != nil) {
            callResult["id"] = self.call!.sid
            callResult["mute"] = self.call!.isMuted
            callResult["speaker"] = self.isSpeaker()
        } else {
            callResult["id"] = ""
            callResult["mute"] = false
            callResult["speaker"] = false
        }

        if self.callInvite != nil {
            callResult["customParameters"] = self.callInvite?.customParameters
        }

        // Was never populated, so Dart's Call.to was always "".
        let to = self.callInvite?.to ?? self.callTo
        callResult["to"] = to.replacingOccurrences(of: "client:", with: "")

        callResult["toDisplayName"] = self.getToDisplayName()
        callResult["fromDisplayName"] = self.getFromDisplayName()
        callResult["outgoing"] = self.callInvite == nil
        callResult["status"] = self.callStatus
        return callResult;
    }
    
    func incomingPushHandled() {
        if let completion = self.incomingPushCompletionCallback {
            completion()
            self.incomingPushCompletionCallback = nil
        }
    }
    
    // MARK: TVONotificaitonDelegate
    public func callInviteReceived(callInvite: CallInvite) {
        NSLog("callInviteReceived:")

        // iOS requires that EVERY VoIP push results in a reportNewIncomingCall. If we
        // are already busy we must still report this invite and then immediately end
        // it, otherwise the system terminates the app and, after repeat offences,
        // stops launching it for VoIP pushes at all.
        if (self.call != nil || self.callInvite != nil) {
            NSLog("Already handling a call. Reporting then ending invite from \(String(describing: callInvite.from))")
            reportAndEndUnpresentableCall(
                uuid: callInvite.uuid,
                handleValue: callInvite.from ?? "Unknown name",
                reason: .declinedElsewhere
            ) {
                callInvite.reject()
            }
            return
        }

        self.callInvite = callInvite
        self.callStatus = "callIncoming"
        reportIncomingCall(uuid: callInvite.uuid)

        // Let Flutter know a call is ringing so it can show its own incoming UI.
        DispatchQueue.main.async {
            self.getChannel()?.invokeMethod("callIncoming", arguments: self.getCallResult())
        }
    }


    public func cancelledCallInviteReceived(cancelledCallInvite: CancelledCallInvite, error: Error) {
        NSLog("cancelledCallInviteReceived:")

        guard let pendingInvite = self.callInvite,
              pendingInvite.callSid == cancelledCallInvite.callSid else {
            NSLog("No matching pending CallInvite for the Cancelled CallInvite")

            // Only report a call if a VoIP push is actually still outstanding — that is
            // the case where iOS demands a report before it will let us go back to sleep.
            // This delegate can also fire outside any push (the invite was already
            // answered or rejected), and reporting there would show a phantom call.
            if self.incomingPushCompletionCallback != nil {
                reportAndEndUnpresentableCall(
                    uuid: UUID(),
                    handleValue: cancelledCallInvite.from ?? "Unknown name",
                    reason: .unanswered
                )
            }
            return
        }

        audioDevice.isEnabled = true
        performMissedCallAction(uuid: pendingInvite.uuid, cancelledCallInvite: cancelledCallInvite)
        self.incomingPushHandled()
    }

    /// Satisfies the iOS "every VoIP push must report a call" contract for invites we
    /// cannot actually present, then tears the reported call straight back down.
    /// `onReported` runs after the report so callers can reject the underlying invite.
    private func reportAndEndUnpresentableCall(
        uuid: UUID,
        handleValue: String,
        reason: CXCallEndedReason,
        onReported: (() -> Void)? = nil
    ) {
        let callUpdate = CXCallUpdate()
        callUpdate.remoteHandle = CXHandle(type: .generic, value: handleValue)
        callUpdate.hasVideo = false
        callUpdate.supportsDTMF = false
        callUpdate.supportsHolding = false
        callUpdate.supportsGrouping = false
        callUpdate.supportsUngrouping = false

        callKitProvider.reportNewIncomingCall(with: uuid, update: callUpdate) { error in
            if let error = error {
                NSLog("Failed to report unpresentable incoming call: \(error.localizedDescription)")
            }
            self.callKitProvider.reportCall(with: uuid, endedAt: Date(), reason: reason)
            onReported?()
            self.incomingPushHandled()
        }
    }
    func showMissedCallNotification(from:String?, to:String?){
       // guard UserDefaults.standard.set(forKey: "show-notifications") ?? true else{return}
        let notificationCenter = UNUserNotificationCenter.current()
        notificationCenter.getNotificationSettings { (settings) in
             NSLog("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!1")
          if settings.authorizationStatus == .authorized {
             NSLog("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!2")
            let content = UNMutableNotificationContent()
            var userName:String?
            if var from = from{
             NSLog("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!3")
                from = from.replacingOccurrences(of: "client:", with: "")
                content.userInfo = ["type":"twilio-missed-call", "From":from]
                if let to = to{
             NSLog("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!4")
                    content.userInfo["To"] = to
                }
                userName = UserDefaults.standard.string(forKey: "_defaultDisplayName") ?? from ?? ""
            }
            let caller = userName
                ?? UserDefaults.standard.string(forKey: "_defaultDisplayName")
                ?? from
                ?? "Unknown name"
            content.title = NSLocalizedString("Missed Call", comment: "")
            content.subtitle = caller
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false)
            let request = UNNotificationRequest(identifier: UUID().uuidString,
                                                content: content,
                                                trigger: trigger)

                notificationCenter.add(request) { (error) in
                    if let error = error {
                        print("Notification Error: ", error)
                    }
                }

          }
        }
    }
    /// Drops all per-call state. Also settles a pending `hangUp` reply so the Dart
    /// Future cannot hang forever waiting on a call that is already gone.
    func clearCallState() {
        self.call = nil
        self.callInvite = nil
        self.fromDisplayName = nil
        self.toDisplayName = nil
        self.callKitCompletionCallback = nil
        self.userInitiatedDisconnect = false

        if let pendingHangUp = self.hangUpResult {
            self.hangUpResult = nil
            pendingHangUp("")
        }

        DispatchQueue.main.async {
            self.callStatus = "callDisconnected"
            self.getChannel()?.invokeMethod("callDisconnected", arguments: nil)
        }
    }

    func callDisconnected(id: UUID, error: String?) {
            NSLog("callDisconnected")

                var reason = CXCallEndedReason.remoteEnded

                if error != nil {
                    NSLog("Call disconnected due to error")
                    reason = .failed
                }

                // Report to CallKit first
                self.callKitProvider.reportCall(with: id, endedAt: Date(), reason: reason)

                // Then cleanup
                clearCallState()
    }
    func callDisconnectedMissCall(id: UUID, error: String?,cancelledCallInvite: CancelledCallInvite) {
        self.call = nil
        self.callInvite = nil
        self.fromDisplayName = cancelledCallInvite.from
        self.toDisplayName =  cancelledCallInvite.to
        self.callKitCompletionCallback = nil
        self.userInitiatedDisconnect = false

        DispatchQueue.main.async {
            self.callStatus = "missedCall"
            self.getChannel()?.invokeMethod("missedCall", arguments: self.getCallResult())
        }


        var reason = CXCallEndedReason.remoteEnded

        if error != nil {
            NSLog("Hubo un error en el did disconect, entonces pongo que se corta por un error")
            reason = .failed
        }

        self.callKitProvider.reportCall(with: id, endedAt: Date(), reason: reason)

    }
    
    
    func isSpeaker() -> Bool{
        var speaker: Bool = false
        let currentRoute = AVAudioSession.sharedInstance().currentRoute
        for output in currentRoute.outputs {
            switch output.portType {
            case AVAudioSession.Port.builtInSpeaker:
                speaker = true
            default:
                break
            }
        }
        return speaker
    }
    
    func getFromDisplayName() -> String{
        var result: String? = nil
        if self.callInvite != nil {
            let params = self.callInvite?.customParameters
            if params?["fromDisplayName"] != nil {
                result = params?["fromDisplayName"]
            }
            
            if result == nil || result == "" {
                let contactName = self.getContactDisplayName(phoneNumber: self.callInvite?.from ?? "")
                if contactName != "" {
                    result = contactName
                }
            }
        }else{
            result = self.fromDisplayName ?? ""
        }
                
        if result == nil || result == "" {
            return "Unknown name"
        }
        
        return result!
    }
    
    func getToDisplayName() -> String{
        var result: String? = nil
        if self.callInvite != nil {
            let params = self.callInvite?.customParameters
            if params?["toDisplayName"] != nil {
                result = params?["toDisplayName"]
            }
            
            if result == nil {
                let contactName = self.getContactDisplayName(phoneNumber: self.callInvite?.to ?? "")
                if contactName != "" {
                    result = contactName
                }
            }
        }else{
            result = self.toDisplayName ?? ""
        }
                
        if result == nil || result == "" {
            return "Unknown name"
        }
        
        return result!
    }
    
    // MARK: AVAudioSession
    func toggleAudioRoute(toSpeaker: Bool) {
        // The override has to live inside the audio device's configuration block. Applied
        // directly to the shared session it was silently reverted every time Twilio
        // reconfigured the session (route change, reconnect), leaving the Flutter UI
        // showing speaker-on while audio played from the earpiece.
        audioDevice.block = {
            DefaultAudioDevice.DefaultAVAudioSessionConfigurationBlock()

            do {
                try AVAudioSession.sharedInstance()
                    .overrideOutputAudioPort(toSpeaker ? .speaker : .none)
                NSLog("Speaker toggled: \(toSpeaker)")
            } catch {
                NSLog("Audio route error: \(error.localizedDescription)")
            }
        }

        audioDevice.block()
    }
    
    
    // MARK: Call Kit Actions
    func performStartCallAction(uuid: UUID) {
        let toDisplayName = self.getToDisplayName()
        let callHandle = CXHandle(type: .generic, value: toDisplayName)
        let startCallAction = CXStartCallAction(call: uuid, handle: callHandle)
        let transaction = CXTransaction(action: startCallAction)
        
        callKitCallController.request(transaction)  { error in
            if let error = error {
                NSLog("StartCallAction transaction request failed: \(error.localizedDescription)")
                self.result?(FlutterError.init(
                    code: "Error",
                    message: "Error",
                    details: "Error"
                ))

                self.result = nil
                return
            }
            
            NSLog("StartCallAction transaction request successful")
            
            let callUpdate = CXCallUpdate()
            callUpdate.remoteHandle = callHandle
            callUpdate.supportsDTMF = true
            callUpdate.supportsHolding = true
            callUpdate.supportsGrouping = false
            callUpdate.supportsUngrouping = false
            callUpdate.hasVideo = false
            self.callKitProvider.reportCall(with: uuid, updated: callUpdate)

            // Must clear the slot, or a later reply re-invokes this same FlutterResult
            // ("Reply already submitted") and steals the next call's reply.
            let pendingResult = self.result
            self.result = nil
            pendingResult?(self.getCallResult())
        }
    }
    
    func reportIncomingCall(uuid: UUID) {
        let fromDisplayName = self.getFromDisplayName()
        let callHandle = CXHandle(type: .generic, value: fromDisplayName)
        
        let callUpdate = CXCallUpdate()
        callUpdate.remoteHandle = callHandle
        callUpdate.supportsDTMF = true
        callUpdate.supportsHolding = true
        callUpdate.supportsGrouping = false
        callUpdate.supportsUngrouping = false
        callUpdate.hasVideo = false

        callKitProvider.reportNewIncomingCall(with: uuid, update: callUpdate) { error in
            if let error = error {
                // DND / call-blocking / maximumCallGroups can all reject the report. The
                // call will never be presented, so reject the invite instead of leaving
                // the caller ringing and a stale invite behind.
                NSLog("Failed to report incoming call successfully: \(error.localizedDescription).")
                if let invite = self.callInvite, invite.uuid == uuid {
                    invite.reject()
                    self.callInvite = nil
                    self.callStatus = "callDisconnected"
                    DispatchQueue.main.async {
                        self.getChannel()?.invokeMethod("callDisconnected", arguments: nil)
                    }
                }
            } else {
                NSLog("Incoming call successfully reported.")
            }
            self.incomingPushHandled()
        }
    }
    
    func performEndCallAction(uuid: UUID) {
        NSLog("performEndCallAction")
        if let activeCall = self.call {
                activeCall.disconnect()
            }

            self.userInitiatedDisconnect = true
    }
    func performMissedCallAction(uuid: UUID,cancelledCallInvite: CancelledCallInvite) {

            if self.call == nil {
    //            return;
            }

            self.call?.disconnect()
    //         self.call = nil
    //         self.callInvite = nil
    //         self.result?("")
    //         self.result = nil

            self.callDisconnectedMissCall(id: uuid, error: nil,cancelledCallInvite: cancelledCallInvite)
    //         let endCallAction = CXEndCallAction(call: uuid)
    //         let transaction = CXTransaction(action: endCallAction)
    //
    //         callKitCallController.request(transaction) { error in
    //             if error != nil {
    //                 NSLog("Error ending call:")
    //                 self.result?(FlutterError.init(
    //                     code: "Error",
    //                     message: "Error",
    //                     details: "Error"
    //                 ))
    //
    //                 self.result = nil
    //             } else {
    //                 self.call = nil
    //                 self.callInvite = nil
    //                 self.result?("")
    //                 self.result = nil
    //             }
    //         }
        }
    func performVoiceCall(uuid: UUID, completionHandler: @escaping (Bool) -> Swift.Void) {
        guard let accessToken = getAccessToken() else {
            completionHandler(false)
            return
        }

        var params: [String: String] = [:]
        if self.callData != nil {
            for (key, value) in self.callData! {
                params[String(describing: key)] = String(describing: value)
            }
        }

        params["To"] = self.callTo

        let connectOptions: ConnectOptions = ConnectOptions(accessToken: accessToken) { (builder) in
            builder.params = params
            builder.uuid = uuid
        }
                
        call = TwilioVoice.connect(options: connectOptions, delegate: self)
        self.callKitCompletionCallback = completionHandler
    }
    
    func performAnswerVoiceCall(uuid: UUID, completionHandler: @escaping (Bool) -> Swift.Void) {

       guard let invite = self.callInvite else {
               NSLog("No call invite")
               completionHandler(false)
               return
           }

           let acceptOptions = AcceptOptions(callInvite: invite) { builder in
               builder.uuid = invite.uuid
           }

           self.callStatus = "callConnecting"
           //self.getChannel()?.invokeMethod("callConnecting", arguments: self.getCallResult())

           self.call = invite.accept(options: acceptOptions, delegate: self)

           self.callKitCompletionCallback = completionHandler
           self.incomingPushHandled()
    }
}


// MARK: PKPushRegistryDelegate
extension SwiftFlutterTwilioPlugin : PKPushRegistryDelegate {

        public func pushRegistry(_ registry: PKPushRegistry,
                                 didUpdate credentials: PKPushCredentials,
                                 for type: PKPushType) {

            NSLog("pushRegistry:didUpdatePushCredentials")

                guard type == .voIP else { return }

                self.deviceTokenString = credentials.token

                let deviceToken = credentials.token.map { String(format: "%02x", $0) }.joined()
                NSLog("VoIP Device token: \(deviceToken)")

                // Complete a registration that raced ahead of the token, otherwise the
                // device is never registered with Twilio and no calls ever arrive.
                if self.registrationPending {
                    NSLog("VoIP token arrived. Completing the deferred Twilio registration.")
                    self.registrationPending = false
                    self.registerTwilio()
                }
        }

    public func pushRegistry(_ registry: PKPushRegistry, didInvalidatePushTokenFor type: PKPushType) {
        NSLog("pushRegistry:didInvalidatePushTokenForType:")
        
        if (type != .voIP) {
            return
        }
        
        guard let deviceToken = deviceTokenString, let accessToken = getAccessToken() else {
            return
        }
        
        TwilioVoice.unregister(accessToken: accessToken, deviceToken: deviceToken) { (error) in
            if let error = error {
                NSLog("An error occurred while unregistering: \(error.localizedDescription)")
            }
            else {
                NSLog("Successfully unregistered from VoIP push notifications.")
            }
        }
        
        self.deviceTokenString = nil
    }
    
    /**
     * Try using the `pushRegistry:didReceiveIncomingPushWithPayload:forType:withCompletionHandler:` method if
     * your application is targeting iOS 11. According to the docs, this delegate method is deprecated by Apple.
     */
    public func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload, for type: PKPushType) {
        NSLog("pushRegistry:didReceiveIncomingPushWithPayload:forType:")

        if (type == PKPushType.voIP) {
            TwilioVoice.handleNotification(payload.dictionaryPayload, delegate: self, delegateQueue: DispatchQueue.main)
        }
    }
    
    /**
     * This delegate method is available on iOS 11 and above. Call the completion handler once the
     * notification payload is passed to the `TwilioVoice.handleNotification()` method.
     */
    public func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload, for type: PKPushType, completion: @escaping () -> Void) {
        NSLog("pushRegistry:didReceiveIncomingPushWithPayload:forType:completion:")

        guard type == PKPushType.voIP else {
            completion()
            return
        }

        // A previous push may still be pending if its delegate callback never fired.
        // Drain it now so its completion is never dropped on the floor.
        self.incomingPushHandled()

        // Save for later when the notification is properly handled.
        self.incomingPushCompletionCallback = completion

        // If the SDK does not recognise the payload, no delegate callback will fire, so
        // nothing would ever invoke the completion. Detect that here and settle the push
        // ourselves rather than letting iOS kill us for an unhandled VoIP wake-up.
        if !TwilioVoice.handleNotification(payload.dictionaryPayload, delegate: self, delegateQueue: DispatchQueue.main) {
            NSLog("Payload was not a valid Twilio Voice push. Settling the push without a call.")
            self.incomingPushHandled()
        }
    }
}

/**
 Call provider delegate
 // MARK: CXProviderDelegate
 */
extension SwiftFlutterTwilioPlugin : CXProviderDelegate {
    
    public func providerDidReset(_ provider: CXProvider) {
        NSLog("providerDidReset:")
        audioDevice.isEnabled = true

        // CallKit has dropped every call it knew about. Tear ours down too, otherwise the
        // Twilio call keeps running (and billing) with no UI and no way to end it.
        self.call?.disconnect()
        self.callInvite?.reject()
        self.call = nil
        self.callInvite = nil
        self.fromDisplayName = nil
        self.toDisplayName = nil
        self.callKitCompletionCallback = nil
        self.userInitiatedDisconnect = false

        DispatchQueue.main.async {
            self.callStatus = "callDisconnected"
            self.getChannel()?.invokeMethod("callDisconnected", arguments: nil)
        }
    }

    public func providerDidBegin(_ provider: CXProvider) {
        NSLog("providerDidBegin")
    }

    public func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        NSLog("provider:didActivateAudioSession:")

        // DefaultAudioDevice owns the category/mode and activation. Configuring the
        // session behind its back here is what caused one-way / dead audio, so only
        // hand control back to it.
        audioDevice.isEnabled = true
    }

    public func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        NSLog("provider:didDeactivateAudioSession:")
        audioDevice.isEnabled = false
    }

    public func provider(_ provider: CXProvider, timedOutPerforming action: CXAction) {
        NSLog("provider:timedOutPerformingAction:")
        // Never leave a timed-out action pending, or the CallKit UI wedges.
        action.fulfill()
    }

    public func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        NSLog("provider:performStartCallAction:")

        provider.reportOutgoingCall(with: action.callUUID, startedConnectingAt: Date())

        // Fulfill immediately. Deferring until Twilio connects means a stalled connect
        // leaves the action unfulfilled and CallKit stuck on "Connecting…" forever.
        // `connectedAt` is reported later from callDidConnect.
        self.performVoiceCall(uuid: action.callUUID) { (success) in
            NSLog("performVoiceCall completed. Success: \(success)")
        }

        action.fulfill()
    }

    public func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        NSLog("provider:performAnswerCallAction:")

        self.performAnswerVoiceCall(uuid: action.callUUID) { success in
            NSLog("performAnswerVoiceCall completed. Success: \(success)")
        }

        action.fulfill()
    }

    public func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        NSLog("provider:performEndCallAction:")

            if let invite = self.callInvite {
                invite.reject()
                self.callInvite = nil

                // Declining from the CallKit UI must still tell Flutter the call is over.
                self.callStatus = "callDisconnected"
                DispatchQueue.main.async {
                    self.getChannel()?.invokeMethod("callDisconnected", arguments: nil)
                }
            } else if let call = self.call {
                self.userInitiatedDisconnect = true
                call.disconnect()
            }


            action.fulfill()
    }

    public func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
        NSLog("provider:performSetHeldAction:")
        if (self.call?.state == .connected) {
            self.call?.isOnHold = action.isOnHold
            action.fulfill()
        } else {
            action.fail()
        }
    }

    public func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        NSLog("provider:performSetMutedAction:")
        // Without this, muting from the CallKit UI shows as muted while the microphone
        // stays live — the user keeps transmitting believing they are private.
        guard let call = self.call else {
            action.fail()
            return
        }

        call.isMuted = action.isMuted
        self.getChannel()?.invokeMethod(self.callStatus, arguments: self.getCallResult())
        action.fulfill()
    }

    public func provider(_ provider: CXProvider, perform action: CXPlayDTMFCallAction) {
        NSLog("provider:performPlayDTMFCallAction:")
        guard let call = self.call else {
            action.fail()
            return
        }

        call.sendDigits(action.digits)
        action.fulfill()
    }
}

/**
 Call state delegate
 // MARK: TVOCallDelegate
 */
extension SwiftFlutterTwilioPlugin : CallDelegate {
    
    // NOTE: these events are deliberately NOT gated on applicationState. Answering from
    // the CallKit lock screen leaves the app backgrounded, and gating meant Flutter never
    // heard "callConnected" and sat on "Connecting…" for the whole call.

    public func callDidStartRinging(call: Call) {
        NSLog("callDidStartRinging:")

        self.callStatus = "callRinging"
        DispatchQueue.main.async {
            self.getChannel()?.invokeMethod("callRinging", arguments: self.getCallResult())
        }
    }

    public func callDidConnect(call: Call) {
        NSLog("callDidConnect")

            let isOutgoing = self.callInvite == nil

            self.call = call
            self.callStatus = "callConnected"

            audioDevice.isEnabled = true

            self.callKitCompletionCallback?(true)
            self.callKitCompletionCallback = nil

            // CXStartCallAction is now fulfilled up front, so the connect time has to be
            // reported here or the CallKit timer never starts for outgoing calls.
            if isOutgoing, let uuid = call.uuid {
                self.callKitProvider.reportOutgoingCall(with: uuid, connectedAt: Date())
            }

            DispatchQueue.main.async {
                self.getChannel()?.invokeMethod("callConnected", arguments: self.getCallResult())
            }
    }

    public func callIsReconnecting(call: Call, error: Error) {
        NSLog("call:isReconnectingWithError:")

        self.callStatus = "callReconnecting"
        DispatchQueue.main.async {
            self.getChannel()?.invokeMethod("callReconnecting", arguments: self.getCallResult())
        }
    }

    public func callDidReconnect(call: Call) {
        NSLog("callDidReconnect:")

        self.callStatus = "callReconnected"
        DispatchQueue.main.async {
            self.getChannel()?.invokeMethod("callReconnected", arguments: self.getCallResult())
        }
    }

    public func callDidFailToConnect(call: Call, error: Error) {
        NSLog("Call failed to connect: \(error.localizedDescription)")

        if let completion = self.callKitCompletionCallback {
            completion(false)
            self.callKitCompletionCallback = nil
        }

        guard let uuid = call.uuid else {
            NSLog("callDidFailToConnect with no uuid. Cleaning up without a CallKit report.")
            clearCallState()
            return
        }

        callDisconnected(id: uuid, error: error.localizedDescription)
    }

    public func callDidDisconnect(call: Call, error: Error?) {
        NSLog("callDidDisconnect: \(String(describing: error?.localizedDescription))")

        guard let uuid = call.uuid else {
            NSLog("callDidDisconnect with no uuid. Cleaning up without a CallKit report.")
            clearCallState()
            return
        }

        // Pass the real error through so CallKit gets .failed rather than .remoteEnded and
        // Flutter can tell a dropped call apart from a normal hang-up.
        callDisconnected(id: uuid, error: error?.localizedDescription)
    }

    public func sendDigits (digits: String) {
            self.call?.sendDigits(digits)
        }
}

extension UIWindow {
    func topMostViewController() -> UIViewController? {
        guard let rootViewController = self.rootViewController else {
            return nil
        }
        return topViewController(for: rootViewController)
    }
    
    func topViewController(for rootViewController: UIViewController?) -> UIViewController? {
        guard let rootViewController = rootViewController else {
            return nil
        }
        guard let presentedViewController = rootViewController.presentedViewController else {
            return rootViewController
        }
        switch presentedViewController {
        case is UINavigationController:
            let navigationController = presentedViewController as! UINavigationController
            return topViewController(for: navigationController.viewControllers.last)
        case is UITabBarController:
            let tabBarController = presentedViewController as! UITabBarController
            return topViewController(for: tabBarController.selectedViewController)
        default:
            return topViewController(for: presentedViewController)
        }
    }
}
