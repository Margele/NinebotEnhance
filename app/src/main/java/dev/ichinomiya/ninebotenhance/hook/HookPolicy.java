package dev.ichinomiya.ninebotenhance.hook;

/** Capture replacement and narrowly scoped read-only observers, never vehicle command mutation. */
public final class HookPolicy {
    public static boolean captureClass(String name) {
        return name.startsWith("cn.ninebot.capture.") && !name.endsWith(".R") && !name.contains(".R$")
                && !name.endsWith("BuildConfig");
    }
    public static boolean interestingClass(String name) {
        return captureClass(name) || StatisticsHooks.interesting(name) || FeatureHooks.interesting(name) || name.equals("cn.ninebot.device.motor.thirdparts.TirePressureStateParser")
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
