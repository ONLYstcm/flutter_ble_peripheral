import Foundation

public class GattEventHandler: NSObject, FlutterStreamHandler {
    private var eventSink: FlutterEventSink?

    init(registrar: FlutterPluginRegistrar) {
        let eventChannel = FlutterEventChannel(
            name: "dev.steenbakker.flutter_ble_peripheral/ble_gatt_event", 
            binaryMessenger: registrar.messenger()
        )
        super.init()
        eventChannel.setStreamHandler(self)
    }

    func publishEventData(data: Dictionary<String,Any>) {
        if let eventSink = self.eventSink {
            print("Posting event \(data)")
            eventSink(data)
        }
    }

    public func onListen(withArguments arguments: Any?, eventSink: @escaping FlutterEventSink) -> FlutterError? {
        self.eventSink = eventSink
        return nil
    }
        
    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        eventSink = nil
        return nil
    }
}
