package dev.ichinomiya.ninebotenhance.platform;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * The module's own Bluetooth grants for the lamp and BMS links. Android 12 introduced the two runtime permissions; before it
 * the install-time BLUETOOTH pair covers connecting and a scan needs fine location instead.
 */
public final class BlePermissions {
    public static boolean connectGranted(Context context) {
        return Build.VERSION.SDK_INT < 31 || granted(context, Manifest.permission.BLUETOOTH_CONNECT);
    }
    public static boolean scanGranted(Context context) {
        return granted(context, Build.VERSION.SDK_INT >= 31 ? Manifest.permission.BLUETOOTH_SCAN : Manifest.permission.ACCESS_FINE_LOCATION);
    }
    public static boolean granted(Context context) { return connectGranted(context) && scanGranted(context); }
    /** What to ask the user for on this platform. */
    public static String[] request() {
        return Build.VERSION.SDK_INT >= 31 ? new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }
    private static boolean granted(Context context, String permission) {
        try { return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; } catch (RuntimeException e) { return false; }
    }
    private BlePermissions() {}
}
