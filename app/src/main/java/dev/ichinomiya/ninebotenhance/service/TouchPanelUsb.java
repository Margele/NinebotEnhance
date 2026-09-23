package dev.ichinomiya.ninebotenhance.service;

import android.app.PendingIntent;
import android.content.*;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import dev.ichinomiya.ninebotenhance.core.TouchPanel;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;
import java.util.function.Consumer;

/**
 * Keeps a USB touch panel awake while a cast runs. The kernel suspends an idle USB device after two seconds and cheap panels
 * never wake up from that, so the module holds the device's usbfs node open for the session: the system hands the descriptor
 * to this application once the user allows it, which works without Root and without Shizuku. Nothing is written to the device.
 */
public final class TouchPanelUsb {
    public static final String ACTION = Protocol.MODULE + ".USB_PANEL";
    private static final int REQUEST = 806;
    private static UsbDeviceConnection held;
    private static BroadcastReceiver pending;
    private static Context pendingContext;
    public static UsbDevice find(Context context, TouchPanel panel) {
        if (!panel.bound()) return null;
        UsbManager manager = context.getSystemService(UsbManager.class);
        if (manager == null) return null;
        for (UsbDevice device : manager.getDeviceList().values())
            if (device.getVendorId() == panel.vendor() && device.getProductId() == panel.product()) return device;
        return null;
    }
    public static boolean permitted(Context context, UsbDevice device) {
        UsbManager manager = context.getSystemService(UsbManager.class);
        return manager != null && device != null && manager.hasPermission(device);
    }
    public static void request(Context context, UsbDevice device) {
        UsbManager manager = context.getSystemService(UsbManager.class);
        if (manager == null || device == null) return;
        Intent intent = new Intent(ACTION).setPackage(context.getPackageName());
        manager.requestPermission(device, PendingIntent.getBroadcast(context, REQUEST, intent, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    }
    /** Hold the bound panel for the session; asks for the permission first when it is missing and opens on the answer. */
    public static synchronized void acquire(Context context, TouchPanel panel, Consumer<String> log) {
        release();
        UsbDevice device = find(context, panel);
        if (device == null) { if (panel.bound()) log.accept("TOUCH usb device " + panel.label() + " not attached, no hold"); return; }
        if (permitted(context, device)) { open(context, device, log); return; }
        Context app = context.getApplicationContext();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                UsbDevice answered = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                synchronized (TouchPanelUsb.class) {
                    if (pending != this) return;
                    unregister();
                    if (granted && answered != null) open(app, answered, log);
                    else log.accept("TOUCH usb permission denied, the panel may sleep");
                }
            }
        };
        app.registerReceiver(receiver, new IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED);
        pending = receiver; pendingContext = app;
        log.accept("TOUCH usb permission missing, asking");
        request(app, device);
    }
    public static synchronized void release() {
        unregister();
        if (held != null) { try { held.close(); } catch (RuntimeException ignored) {} held = null; }
    }
    public static synchronized boolean holding() { return held != null; }
    private static void open(Context context, UsbDevice device, Consumer<String> log) {
        UsbManager manager = context.getSystemService(UsbManager.class);
        UsbDeviceConnection connection = manager == null ? null : manager.openDevice(device);
        if (connection == null) { log.accept("TOUCH usb open refused for " + device.getDeviceName()); return; }
        if (held != null) try { held.close(); } catch (RuntimeException ignored) {}
        held = connection; log.accept("TOUCH usb held " + device.getDeviceName() + ", autosuspend off for the session");
    }
    private static void unregister() {
        if (pending != null && pendingContext != null) try { pendingContext.unregisterReceiver(pending); } catch (RuntimeException ignored) {}
        pending = null; pendingContext = null;
    }
    private TouchPanelUsb() {}
}
