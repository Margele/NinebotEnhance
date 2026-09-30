package dev.ichinomiya.ninebotenhance.hook;

/**
 * Capture replacement and narrowly scoped read-only observers, never vehicle command mutation. The class depends on nothing but
 * java.lang, so the tool that patches a Ninebot APK ahead of time asks the same questions about class names.
 */
public final class HookPolicy {
    public static final String BLE_SENDER="cn.ninebot.library.screencast.BluetoothRtpSender",NB_BLE_SENDER="cn.ninebot.mapcapture.NBBluetoothRtpSender";
    public static final String WIFI_SENDER="cn.ninebot.library.screencast.RtpSender",FRAME_SENDER="cn.ninebot.library.screencast.FrameSender";
    public static final String SEND_QUEUE="cn.ninebot.library.screencast.NormalSendQueue";
    public static final String UDP_SESSION="jlibrtp.RTPSession",BLE_WRITER="cn.ninebot.mapcapture.NBBleSender";
    public static final String[] ENCODE_SINKS={"cn.ninebot.screencast.ScreenCastRequest$setListener$2","cn.ninebot.screencast.ScreenCastRequest$createNavigationScreenCast$7",
            "cn.ninebot.mapcapture.DeviceScreenCastRequest$createNavigationScreenCast$2"};
    public static final String VIEW_HOLDER="cn.ninebot.library.bluetooth.dynamic.viewHolder.DynamicViewHolder";
    public static final String VIEW_MODELS="cn.ninebot.device.dynamic.DynamicViewModels$Companion";
    public static final String VISIBILITY_STORE="cn.ninebot.device.motor.manager.HardkeyRemoteControlVisibilityStore";
    public static final String NAVIGATION_CARD="cn.ninebot.device.motor.viewHolder.NavigationCardViewHolder";
    /** The classes {@link StatisticsHooks} observes. */
    public static boolean statisticsClass(String name){
        if(name.equals(BLE_SENDER)||name.equals(NB_BLE_SENDER)||name.equals(WIFI_SENDER)||name.equals(FRAME_SENDER)||name.equals(SEND_QUEUE)||name.equals(UDP_SESSION)||name.equals(BLE_WRITER))return true;
        for(String sink:ENCODE_SINKS)if(name.equals(sink))return true;
        return false;
    }
    /** The classes {@link FeatureHooks} observes. */
    public static boolean featureClass(String name){return name.equals(VIEW_HOLDER)||name.equals(VIEW_MODELS)||name.equals(VISIBILITY_STORE)||name.equals(NAVIGATION_CARD);}
    public static boolean captureClass(String name) {
        return name.startsWith("cn.ninebot.capture.") && !name.endsWith(".R") && !name.contains(".R$")
                && !name.endsWith("BuildConfig");
    }
    /** Capture classes whose constructors are observed for the encoder diagnostics: the top-level ones. */
    public static boolean captureConstructors(String name) { return captureClass(name) && !name.contains("$"); }
    public static boolean interestingClass(String name) {
        return captureClass(name) || statisticsClass(name) || featureClass(name) || name.equals("cn.ninebot.device.motor.thirdparts.TirePressureStateParser")
                || name.equals("cn.ninebot.device.DeviceManager") || name.equals("cn.ninebot.library.bluetooth.dynamic.DynamicDevice")
                || name.equals("cn.ninebot.mapcapture.DeviceScreenCastManager")
                || name.startsWith("cn.ninebot.mapcapture.DeviceScreenCastManager$")
                || name.startsWith("cn.ninebot.mapcapture.DeviceScreenCastRequest")
                || name.equals("cn.ninebot.mapcapture.NBBluetoothRtpSender")
                || name.equals("cn.ninebot.library.screencast.BluetoothRtpSender")
                || name.equals("cn.ninebot.device.motor.navi.DashNaviDataMessenger")
                || name.equals("cn.ninebot.device.motor.navi.DashNaviDataMessenger$Companion")
                || name.equals("cn.ninebot.device.motor.navi.CruiseModeActivity")
                || name.equals("cn.ninebot.device.motor.navi.CruiseModeActivity$Companion")
                || name.equals("cn.ninebot.device.motor.navi.ScreenCastHelper");
    }
    public static boolean vehiclePowerMethod(String className, String method) {
        return (className.equals("cn.ninebot.device.motor.navi.DashNaviDataMessenger$Companion")
                || className.equals("cn.ninebot.device.motor.navi.DashNaviDataMessenger")) && method.equals("isPowerOn");
    }
    public static boolean coroutineDrawingMethod(String className, String method) {
        return captureClass(className) && className.contains("$")
                && (method.equals("run") || method.equals("invoke") || method.equals("invokeSuspend") || method.equals("call"));
    }
    public static boolean bitmapGetter(String className, String method, int parameterCount, boolean bitmapReturn) {
        // No skipping coroutine bodies, timers or methods with transport/lifecycle side effects.
        return captureClass(className) && !className.contains("$") && method.equals("getBitmap") && parameterCount == 0 && bitmapReturn;
    }
    public static boolean terminalCastMethod(String className, String method) {
        return className.equals("cn.ninebot.mapcapture.DeviceScreenCastManager")
                && (method.equals("stop") || method.equals("stopScreenCast") || method.equals("stopCapture") || method.equals("release"));
    }
    /**
     * A patched Ninebot APK keeps the original body of each wrapped method under this suffix, and of each wrapped constructor behind
     * a trailing marker parameter; neither is a hook target.
     */
    public static final String TWIN_SUFFIX = "$$ne", TWIN_MARKER = "dev.ichinomiya.ninebotenhance.embedded.Twin";
    public static boolean twin(java.lang.reflect.Executable executable) {
        if (!executable.isSynthetic()) return false;
        if (executable instanceof java.lang.reflect.Method) return executable.getName().endsWith(TWIN_SUFFIX);
        Class<?>[] types = executable.getParameterTypes();
        return types.length > 0 && types[types.length - 1].getName().equals(TWIN_MARKER);
    }
    private HookPolicy() {}
}
