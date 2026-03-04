package federico.amura.flutter_twilio.Utils;
public class CallManager {

    private static CallManager instance;

    public long callConnectedTime = 0L;
    public boolean isCallConnected = false;

    private CallManager() {}

    public static CallManager getInstance() {
        if (instance == null) {
            instance = new CallManager();
        }
        return instance;
    }
}