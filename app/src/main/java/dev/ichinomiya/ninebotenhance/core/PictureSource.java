package dev.ichinomiya.ninebotenhance.core;

/**
 * Where the vehicle picture comes from: the system's screen capture of an app, the daemon's virtual display, or the module's
 * own drawing of vehicle data. Only the virtual display needs Root or Shizuku, and only it needs Android 14's display API.
 */
public enum PictureSource {
    CAST, VIRTUAL, DRAW;
    public static final int VIRTUAL_MIN_SDK = 34;
    public static final String KEY = "picture_source";
    public boolean virtual() { return this == VIRTUAL; }
    public boolean draws() { return this == DRAW; }
    public boolean captures() { return this == CAST; }
    /** Whether the platform can run it: the virtual display is created through a display API that exists from Android 14. */
    public boolean allowed(int sdk) { return this != VIRTUAL || sdk >= VIRTUAL_MIN_SDK; }
    public String label() {
        switch (this) { case CAST: return "投屏"; case VIRTUAL: return "虚拟显示器"; default: return "绘制"; }
    }
    public static PictureSource parse(String name) {
        try { return valueOf(name); } catch (Exception e) { throw new IllegalArgumentException("无效的画面提供方式"); }
    }
    /** A saved value, or the fallback when it is missing or unknown. */
    public static PictureSource read(String name, PictureSource fallback) {
        try { return name == null ? fallback : valueOf(name); } catch (RuntimeException e) { return fallback; }
    }
    /**
     * Builds before this setting stored only the privilege mode: NONE meant recording, anything else the virtual display. A
     * phone below Android 14, and a fresh install, start with the capture.
     */
    public static PictureSource migrate(String legacyMode, int sdk) {
        return legacyMode == null || "NONE".equals(legacyMode) || sdk < VIRTUAL_MIN_SDK ? CAST : VIRTUAL;
    }
}
