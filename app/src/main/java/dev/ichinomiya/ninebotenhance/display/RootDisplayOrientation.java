package dev.ichinomiya.ninebotenhance.display;

import dev.ichinomiya.ninebotenhance.ipc.Ipc;

import android.view.Display;
import android.view.Surface;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Per-session rotation policy, applied before app launch. Never targets the phone's display. */
public final class RootDisplayOrientation {
    private static final String CALLER = "NinebotMirror:VirtualDisplay";
    private final Display display;
    private final int id;
    private final Consumer<String> log;
    private Object windowManager;
    private Method freeze, thaw, fixed, ignore, unique;
    private String identity;
    private int previousRotation;
    private boolean previousFrozen, previousIgnore, changedFixed, changedIgnore, changedRotation, closed;
    public RootDisplayOrientation(Display display, Consumer<String> log) {
        this.display = display; id = display.getDisplayId(); this.log = log;
        if (id <= Display.DEFAULT_DISPLAY) throw new IllegalArgumentException("不能修改手机主屏方向");
    }
    public synchronized void start() throws Exception {
        unique = Display.class.getMethod("getUniqueId"); identity = (String)unique.invoke(display); requireOwned();
        windowManager = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
        Class<?> api = Class.forName("android.view.IWindowManager");
        try { freeze = api.getMethod("freezeDisplayRotation", int.class, int.class, String.class); }
        catch (NoSuchMethodException e) { freeze = api.getMethod("freezeDisplayRotation", int.class, int.class); }
        try { thaw = api.getMethod("thawDisplayRotation", int.class, String.class); }
        catch (NoSuchMethodException e) { thaw = api.getMethod("thawDisplayRotation", int.class); }
        fixed = api.getMethod("setFixedToUserRotation", int.class, int.class);
        ignore = api.getMethod("setIgnoreOrientationRequest", int.class, boolean.class);
        Method userRotation = null, ignoreRequest = null;
        try { userRotation = api.getMethod("getDisplayUserRotation", int.class); }
        catch (NoSuchMethodException e) { /* Android 13 and 14 expose no per-display user rotation; the session starts on the panel's natural rotation. */ }
        previousRotation = userRotation == null ? display.getRotation() : (int)userRotation.invoke(windowManager, id);
        previousFrozen = (boolean)api.getMethod("isDisplayRotationFrozen", int.class).invoke(windowManager, id);
        try { ignoreRequest = api.getMethod("getIgnoreOrientationRequest", int.class); }
        catch (NoSuchMethodException e) { /* Same platforms have only the setter; a fresh session display is left at the platform default on restore. */ }
        previousIgnore = ignoreRequest != null && (boolean)ignoreRequest.invoke(windowManager, id);
        if (previousRotation < 0 || previousRotation > 3) throw new IllegalStateException("副屏旋转状态不可用");
        apply();
    }
    public synchronized void apply() throws Exception {
        requireOwned();
        // Rotation 0 is the configured width x height (860 x 480 is naturally landscape).
        // Fixed-to-user keeps sensors from changing it; ignore-orientation stops app requests rotating the display.
        ignore.invoke(windowManager, id, true); changedIgnore = true;
        requireOwned(); fixed.invoke(windowManager, id, 2); changedFixed = true;
        requireOwned(); freeze(Surface.ROTATION_0); changedRotation = true;
        log.accept("ORIENTATION id=" + id + " natural rotation=0 fixedToUser=enabled ignoreRequests=true");
    }
    private void requireOwned() throws Exception {
        if (closed || id <= 0 || !display.isValid() || display.getDisplayId() != id || identity == null
                || !identity.equals(unique.invoke(display))) throw new IllegalStateException("副屏已关闭或身份已改变");
    }
    private void freeze(int rotation) throws Exception {
        if (freeze.getParameterCount() == 3) freeze.invoke(windowManager, id, rotation, CALLER);
        else freeze.invoke(windowManager, id, rotation);
    }
    public synchronized void close() {
        if (closed) return;
        try {
            requireOwned();
            if (changedIgnore) try { ignore.invoke(windowManager, id, previousIgnore); } catch (Exception e) { log.accept("ORIENTATION restore ignore " + Ipc.error(e)); }
            // This is a display created by this session. Remove its explicit fixed-to-user override before destruction.
            if (changedFixed) try { fixed.invoke(windowManager, id, 0); } catch (Exception e) { log.accept("ORIENTATION restore fixed " + Ipc.error(e)); }
            if (changedRotation) try {
                freeze(previousRotation);
                if (!previousFrozen) {
                    if (thaw.getParameterCount() == 2) thaw.invoke(windowManager, id, CALLER); else thaw.invoke(windowManager, id);
                }
            } catch (Exception e) { log.accept("ORIENTATION restore rotation " + Ipc.error(e)); }
        } catch (Exception e) { if (changedFixed || changedIgnore || changedRotation) log.accept("ORIENTATION cleanup " + Ipc.error(e)); }
        finally { closed = true; }
    }
}
