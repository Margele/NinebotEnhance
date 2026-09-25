package dev.ichinomiya.ninebotenhance.ipc;

import dev.ichinomiya.ninebotenhance.core.DisplaySettings;

import android.os.*;

public final class Ipc {
    public static Bundle call(IBinder remote, int code, Bundle extras) throws RemoteException {
        if (remote == null) throw new RemoteException("服务未连接");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(Protocol.DESCRIPTOR); data.writeBundle(extras);
            if (!remote.transact(code, data, reply, 0)) throw new RemoteException("模块版本不匹配，请强制停止九号后重开");
            reply.readException(); Bundle result = reply.readBundle(Ipc.class.getClassLoader());
            return result == null ? new Bundle() : result;
        } finally { data.recycle(); reply.recycle(); }
    }
    public static Bundle request(String id) { Bundle data = new Bundle(); data.putString(Protocol.REQUEST, id); return data; }
    public static void settings(Bundle data, DisplaySettings settings) {
        data.putInt("width", settings.width); data.putInt("height", settings.height); data.putInt("dpi", settings.dpi);
        data.putInt("layout_version",DisplaySettings.LAYOUT_VERSION);data.putInt("virtual_width",settings.virtualWidth);
        data.putInt("virtual_height",settings.virtualHeight);data.putInt("background_color",settings.backgroundColor);
        data.putInt("keep_phone_dpi",settings.keepPhoneDpi?1:0);data.putInt("compat_scale",settings.compatScale?1:0);data.putInt("virtual_override",settings.virtualOverride?1:0);data.putInt("light_background_color",settings.lightBackgroundColor);data.putInt("bottom_inset",settings.bottomInset);
    }
    public static DisplaySettings settings(Bundle data) { return DisplaySettings.read(data::getInt); }
    /** Typed Parcelable reads on both API generations; the typed getters exist from Android 13. */
    @SuppressWarnings({"deprecation", "unchecked"})
    public static <T> T parcelable(Bundle data, String key, Class<T> type) {
        if (data == null) return null;
        if (Build.VERSION.SDK_INT >= 33) return data.getParcelable(key, type);
        Object value = data.getParcelable(key); return type.isInstance(value) ? (T) value : null;
    }
    @SuppressWarnings({"deprecation", "unchecked"})
    public static <T> T parcelableExtra(android.content.Intent intent, String key, Class<T> type) {
        if (intent == null) return null;
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(key, type);
        Object value = intent.getParcelableExtra(key); return type.isInstance(value) ? (T) value : null;
    }
    @SuppressWarnings({"deprecation", "unchecked"})
    public static <T> java.util.ArrayList<T> parcelableList(Bundle data, String key, Class<T> type) {
        if (data == null) return null;
        if (Build.VERSION.SDK_INT >= 33) return data.getParcelableArrayList(key, type);
        return (java.util.ArrayList<T>) data.getParcelableArrayList(key);
    }
    /** Options for the host launching one of the module's PendingIntents; the background-start allowance exists from Android 14. */
    public static Bundle launchOptions() {
        if (Build.VERSION.SDK_INT < 34) return null;
        android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
        options.setPendingIntentBackgroundActivityStartMode(Build.VERSION.SDK_INT >= 36
                ? android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE : android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        return options.toBundle();
    }
    public static String error(Throwable error) {
        while (error.getCause() != null && error.getCause() != error) error = error.getCause();
        String text = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
    private Ipc() {}
}
