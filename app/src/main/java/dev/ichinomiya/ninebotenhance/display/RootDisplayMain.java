package dev.ichinomiya.ninebotenhance.display;

import dev.ichinomiya.ninebotenhance.core.AppRecoveryState;
import android.net.Uri;
import dev.ichinomiya.ninebotenhance.core.DisplayInputTransform;
import dev.ichinomiya.ninebotenhance.core.DisplaySettings;
import dev.ichinomiya.ninebotenhance.core.FramePacer;
import dev.ichinomiya.ninebotenhance.core.TouchPanel;
import dev.ichinomiya.ninebotenhance.diagnostics.Diagnostics;
import dev.ichinomiya.ninebotenhance.diagnostics.LogDigest;
import dev.ichinomiya.ninebotenhance.ipc.Ipc;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;
import dev.ichinomiya.ninebotenhance.platform.AppCatalog;

/* Root display bootstrap adapted from the Apache-2.0 scrcpy/VirtualDisplay approach.
 * Copyright (C) 2018 Genymobile; Copyright (C) 2018-2026 Romain Vimont.
 * VirtualDisplay reference: Copyright 2026 ynk. See THIRD_PARTY_NOTICES.md for pinned sources.
 * Modified for Ninebot Enhance: Binder Surfaces replace the TCP/video protocol;
 * display lifetime, authorization, app recovery and layout policies are module-specific. */

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.Point;
import android.hardware.display.*;
import android.os.*;
import android.view.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs only under app_process, launched by su. It never runs inside Ninebot or zygote. */
public final class RootDisplayMain {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final String secret;
    private final Context context;
    private final Object activityManager, provider;
    private final IBinder providerToken = new Binder();
    private VirtualDisplay display;
    private RootDisplayPower displayPower;
    private RootDisplayOrientation displayOrientation;
    private RootKeyboard keyboard;
    private RootTouchPanel touchPanel;
    private Surface surface;
    private String request;
    private int moduleUid, displayId = -1;
    private IBinder owner, host;
    private long lastInputError;
    private final java.util.concurrent.atomic.AtomicBoolean inputDeniedReported = new java.util.concurrent.atomic.AtomicBoolean();
    private Object inputManager;
    private Method inject, setDisplayId;
    private DisplaySettings settings;
    private MotionEvent lastTouch;
    private int gestureRotation = -1;
    private volatile long launchGeneration;
    private ComponentName selectedApp;
    private final AppRecoveryState appRecovery = new AppRecoveryState();
    private final AtomicBoolean restartQueued = new AtomicBoolean();
    private String recoveryDetail = "", lastRecoveryLog = "";
    private Object taskManager;
    private Method getTasks;
    private long lastTaskError;
    /** Route to request again once the app settled on the virtual display; null when nothing was being navigated. */
    private String resumeUri;private int resumePolls;
    private static final long RESUME_POLL_MS=500,RESUME_SETTLE_MS=2500;private static final int RESUME_POLLS=40;

    /** Keep-DPI with compat scaling: the display is created at this size and density and the capture path scales it into the app area. Without it applyRenderPlan forces the logical size instead. */
    private int renderDpi, captureWidth, captureHeight; private boolean scaledRender;
    public static void main(String[] args) {
        if (args.length != 1 || !Protocol.validRequest(args[0])) System.exit(2);
        RootDisplayMain daemon = null;
        try {
            // Root was only needed to start app_process; the daemon normally runs as shell and every Android service call carries the
            // shell uid/package. With the "keep root" authorization option it stays uid 0 (for ROMs that deny shell INJECT_EVENTS);
            // the attribution still names shell, which root is allowed to use, as scrcpy does under adb root.
            int uid = android.os.Process.myUid();
            if (uid != 2000 && uid != 0) throw new SecurityException("Root 辅助进程 UID 不是 shell 或 root");
            Looper.prepareMainLooper();
            Class<?> runtime = Class.forName("dalvik.system.VMRuntime");
            try { runtime.getDeclaredMethod("setHiddenApiExemptions", String[].class)
                    .invoke(runtime.getDeclaredMethod("getRuntime").invoke(null), (Object)new String[]{"L"}); }
            catch (ReflectiveOperationException ignored) { /* app_process normally has no app hidden-API restriction */ }
            daemon = new RootDisplayMain(args[0]); daemon.start(); Looper.loop();
        } catch (Throwable e) {
            String error = Ipc.error(e); System.err.println("VD ERROR " + error);
            if (daemon != null) daemon.reportError(error);
        } finally { if (daemon != null) daemon.release(); System.exit(0); }
    }
    private RootDisplayMain(String secret) throws Exception {
        this.secret = secret; context = createShellContext();
        activityManager = ActivityManager.class.getDeclaredMethod("getService").invoke(null);
        Class<?> am = Class.forName("android.app.IActivityManager");
        Object holder = am.getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class)
                .invoke(activityManager, Protocol.ROOT_AUTHORITY, 0, providerToken, "ninebot-virtual-display");
        if (holder == null) throw new IllegalStateException("模块握手 Provider 不可用");
        Field field = holder.getClass().getDeclaredField("provider"); field.setAccessible(true); provider = field.get(holder);
    }
    private void start() throws Exception {
        Bundle config = providerCall("attach", new Bundle());
        request = config.getString(Protocol.REQUEST); moduleUid = config.getInt("uid", -1);
        if (!Protocol.validRequest(request) || moduleUid < 10000) throw new SecurityException("无效的模块会话");
        owner = config.getBinder("owner"); host = config.getBinder("host");
        if (owner == null || host == null) throw new IllegalArgumentException("缺少生命周期所有者");
        log("DAEMON uid=" + android.os.Process.myUid());
        owner.linkToDeath(this::exit, 0); host.linkToDeath(this::exit, 0);
        // Independent watchdog also handles an interrupted/blocked create call and logical lease revocation.
        new Thread(() -> {
            long deadline = SystemClock.elapsedRealtime() + 25000;
            while (!stopped.get()) {
                try {
                    if (!owner.isBinderAlive() || !host.isBinderAlive()) { exit(); return; }
                    Bundle status = Ipc.call(host, Protocol.READ, Ipc.request(request));
                    if (!status.getBoolean("active") || (displayId <= 0 && SystemClock.elapsedRealtime() > deadline)) { exit(); return; }
                    Thread.sleep(1000);
                } catch (Exception e) { exit(); return; }
            }
        }, "Mirror-OwnerWatchdog").start();
        settings = Ipc.settings(config); surface = config.getParcelable("surface", Surface.class);
        renderDpi = config.getInt("render_dpi", 0); captureWidth = config.getInt(Protocol.CAPTURE_WIDTH, 0); captureHeight = config.getInt(Protocol.CAPTURE_HEIGHT, 0);
        scaledRender = false;
        selectedApp = config.getParcelable(AppCatalog.SELECTED, ComponentName.class);
        if (selectedApp == null) throw new IllegalArgumentException("缺少已选择的启动应用，请回设置选择");
        if (surface == null || !surface.isValid()) throw new IllegalStateException("接收 Surface 已关闭");
        Constructor<DisplayManager> constructor = DisplayManager.class.getDeclaredConstructor(Context.class); constructor.setAccessible(true);
        DisplayManager manager = constructor.newInstance(context);
        if (settings.keepPhoneDpi && settings.compatScale) {
            // Keep-DPI, compat scaling: the display is created at the phone's density and the plan's logical size. The surface the
            // client created is already plan sized; the display must be created at exactly that size, or the system paints a small
            // display into the top-left corner of the large buffer. Without compat scaling the display is created at the buffer size
            // and applyRenderPlan forces the logical size and density through WindowManager after creation.
            if (renderDpi > 0 && captureWidth > 0 && captureHeight > 0) scaledRender = true;
            else {
                DisplaySettings.RenderPlan plan = settings.renderPlan(phoneDensityDpi(manager));
                if (plan != null) { scaledRender = true; captureWidth = plan.width(); captureHeight = plan.height(); renderDpi = plan.dpi(); }
            }
            log("RENDER plan " + (scaledRender ? captureWidth + "x" + captureHeight + "@" + renderDpi : "not needed, phone density equals the layout density"));
        }
        // Public own-content display, touch input, destroy its tasks on removal, trusted independent focus.
        // Independent keyguard state, like VirtualDisplay's default ALWAYS_UNLOCKED option.
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | (1 << 6) | (1 << 7) | (1 << 8)
                | (1 << 10) | (1 << 11) | (1 << 12) | (1 << 14);
        // No SHOULD_SHOW_SYSTEM_DECORATIONS: do not create a secondary desktop, taskbar or app drawer.
        // VirtualDisplay's default ROTATES_WITH_CONTENT couples the logical orientation to
        // the output Surface. Without it Android treats this as a fixed external panel and
        // may pillarbox rotated content inside the landscape RGBA buffer.
        try { flags |= DisplayManager.class.getField("VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED").getInt(null); }
        catch (ReflectiveOperationException ignored) {}
        VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(Protocol.DISPLAY_NAME, scaledRender ? captureWidth : settings.virtualWidth,
                scaledRender ? captureHeight : settings.virtualHeight, scaledRender ? renderDpi : settings.dpi)
                .setSurface(surface).setFlags(flags).setRequestedRefreshRate(FramePacer.TARGET_FPS);
        log("DISPLAY captureTargetFps=" + FramePacer.TARGET_FPS);
        try { VirtualDisplayConfig.Builder.class.getMethod("setHomeSupported", boolean.class).invoke(builder, false); }
        catch (NoSuchMethodException ignored) { /* Older platforms default to no home when system decorations are absent. */ }
        // Locking the display's rotation alone still lets Android letterbox a fixed-portrait
        // Activity. Android 16 has a separate, trusted-display-only policy for app constraints.
        // It must be in the creation config: DisplayContent caches it when the display is added.
        String appLayoutPolicy = "unavailable";
        boolean requestedAppLayout = false;
        try {
            VirtualDisplayConfig.Builder.class.getMethod("setIgnoreActivitySizeRestrictions", boolean.class).invoke(builder, true);
            requestedAppLayout = true; appLayoutPolicy = "requested-unverified";
        } catch (ReflectiveOperationException | RuntimeException e) {
            log("APP_LAYOUT request unavailable: " + Ipc.error(e));
        }
        VirtualDisplayConfig displayConfig = builder.build();
        if (requestedAppLayout) try {
            // AOSP gates this getter with a platform feature flag. Do not report success just
            // because the setter exists. A true config readback is not a live window check.
            boolean enabled = Boolean.TRUE.equals(VirtualDisplayConfig.class.getMethod("isIgnoreActivitySizeRestrictions").invoke(displayConfig));
            appLayoutPolicy = enabled ? "config-enabled" : "config-disabled";
        } catch (ReflectiveOperationException | RuntimeException e) {
            log("APP_LAYOUT readback unavailable: " + Ipc.error(e));
        }
        log("APP_LAYOUT policy=" + appLayoutPolicy + " requested=" + requestedAppLayout + " trusted=" + ((flags & (1 << 10)) != 0));
        // The public name/size overload omits requestedRefreshRate. Call the config overload with a null projection.
        Method create = DisplayManager.class.getMethod("createVirtualDisplay", Class.forName("android.media.projection.MediaProjection"),
                VirtualDisplayConfig.class, VirtualDisplay.Callback.class, Handler.class);
        VirtualDisplay.Callback callback = new VirtualDisplay.Callback() {
            @Override public void onPaused() { log("DISPLAY power paused id=" + displayId); }
            @Override public void onResumed() { log("DISPLAY power resumed id=" + displayId); }
            @Override public void onStopped() { log("DISPLAY stopped by system id=" + displayId); exit(); }
        };
        display = (VirtualDisplay)create.invoke(manager, null, displayConfig, callback, main);
        if (display == null || display.getDisplay() == null) throw new IllegalStateException("系统拒绝创建虚拟显示器");
        displayId = display.getDisplay().getDisplayId();
        if (displayId <= 0) throw new IllegalStateException("系统返回了非独立显示器");
        log("DISPLAY created id=" + displayId + " buffer=" + settings.label() + " flags=0x" + Integer.toHexString(flags) + " sharedMemory=" + sharedMemoryStatus);
        if (scaledRender) log("RENDER logical=" + captureWidth + "x" + captureHeight + " dpi=" + renderDpi + " scaled by the capture path into "
                + settings.virtualWidth + "x" + settings.virtualHeight + " layoutDpi=" + settings.dpi);
        else try { applyRenderPlan(manager); }
        catch (Exception e) {
            // Some ROMs deny WRITE_SECURE_SETTINGS to shell: this session keeps the buffer size and layout density, exactly as with
            // keep-DPI off, and the module switches compat scaling on for the next one.
            log("RENDER unavailable, keeping buffer size and layout density: " + Ipc.error(e)); clearRenderPlan();
            try { providerCall("render_fallback", new Bundle()); } catch (Exception ignored) {}
        }
        try {
            displayOrientation = new RootDisplayOrientation(display.getDisplay(), this::log);
            displayOrientation.start();
        } catch (Exception e) {
            if (displayOrientation != null) displayOrientation.close(); displayOrientation = null;
            log("ORIENTATION unavailable: " + Ipc.error(e));
        }
        try {
            displayPower = new RootDisplayPower(display.getDisplay(), manager, main, this::log);
            displayPower.start();
        } catch (Exception e) {
            if (displayPower != null) displayPower.close();
            log("POWER unavailable: " + Ipc.error(e));
        }
        inputManager = context.getSystemService(Context.INPUT_SERVICE);
        inject = android.hardware.input.InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class);
        setDisplayId = InputEvent.class.getMethod("setDisplayId", int.class);
        keyboard = new RootKeyboard(display.getDisplay(), main, event -> {
            if (displayId <= 0 || stopped.get()) throw new IllegalStateException("虚拟屏已关闭");
            if (!injectEvent(event, 2)) throw new IllegalStateException("系统拒绝副屏键盘输入");
        }, this::log);
        keyboard.configure();
        TouchPanel panel = TouchPanel.read(config::getInt, config::getString);
        if (panel.bound()) {
            // The panel covers the whole frame; its contacts arrive in RGBA buffer coordinates like the phone preview, so sendTouch serves both.
            touchPanel = new RootTouchPanel(panel, settings.width, settings.height, settings.virtualWidth, settings.virtualHeight, settings.contentTop(), this::sendTouch,
                    panel.marks() ? this::sendMarks : null, this::sendSample, this::publishTouchPresence, this::log);
            touchPanel.start();
        }
        Bundle ready = new Bundle(); ready.putBinder("root", endpoint); ready.putInt("displayId", displayId);
        ready.putString(Protocol.APP_LAYOUT_POLICY, appLayoutPolicy); providerCall("ready", ready);
        launch(selectedApp);
        appRecovery.started(SystemClock.elapsedRealtime()); main.post(appWatch);
        System.out.println("VD ready id=" + displayId + " " + settings.label());
    }
    private final Binder endpoint = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(Protocol.DESCRIPTOR); return true; }
            data.enforceInterface(Protocol.DESCRIPTOR);
            if (Binder.getCallingUid() != moduleUid) throw new SecurityException("仅允许创建者控制虚拟屏");
            Bundle args = data.readBundle(getClass().getClassLoader());
            if (args == null || !request.equals(args.getString(Protocol.REQUEST)) || stopped.get()) throw new SecurityException("会话已失效");
            Bundle result = new Bundle();
            // Clear caller identity: these privileged service calls belong to the shell process.
            long identity = Binder.clearCallingIdentity();
            try {
                switch (code) {
                    case Protocol.ROOT_TEXT: keyboard.text(args.getString("text")); break;
                    case Protocol.ROOT_TYPING_KEY: keyboard.key(args.getInt("key"), args.getInt("action", -1), args.getInt("meta")); break;
                    case Protocol.ROOT_DELETE: keyboard.delete(args.getInt("before"), args.getInt("after")); break;
                    case Protocol.ROOT_STOP: main.post(RootDisplayMain.this::exit); break;
                    case Protocol.ROOT_TOUCH_CALIBRATE:
                        if (touchPanel == null) throw new IllegalStateException("未绑定触摸屏");
                        touchPanel.setCalibrating(args.getBoolean("calibrating")); break;
                    case Protocol.ROOT_TOUCH_CALIBRATION:
                        if (touchPanel == null) throw new IllegalStateException("未绑定触摸屏");
                        touchPanel.setPanel(touchPanel.panel().withCalibration(dev.ichinomiya.ninebotenhance.core.TouchCalibration.decode(args.getString("calibration")))); break;
                    case Protocol.ROOT_RESTART_APP:
                        if (restartQueued.compareAndSet(false, true)) main.post(() -> {
                            try { if (!stopped.get()) restartSelectedApp(); }
                            finally { restartQueued.set(false); }
                        });
                        break;
                    case Protocol.ROOT_KEY:
                        int key = args.getInt("key");
                        if (key != KeyEvent.KEYCODE_BACK) throw new SecurityException("不支持的按键");
                        long time = SystemClock.uptimeMillis();
                        sendInput(new KeyEvent(time, time, KeyEvent.ACTION_DOWN, key, 0));
                        sendInput(new KeyEvent(time, time, KeyEvent.ACTION_UP, key, 0)); break;
                    case Protocol.ROOT_INPUT:
                        MotionEvent event = args.getParcelable("event", MotionEvent.class);
                        if (event == null) throw new IllegalArgumentException("缺少触控事件");
                        try { if (event.getPointerCount() > 10) throw new IllegalArgumentException("触点过多");
                            sendTouch(event); }
                        finally { event.recycle(); } break;
                    default: return false;
                }
            } catch (Exception e) {
                String error = Ipc.error(e); result.putString("error", error);
                if (SystemClock.elapsedRealtime() - lastInputError > 3000) {
                    lastInputError = SystemClock.elapsedRealtime(); System.err.println("VD CONTROL " + error);
                }
            } finally { Binder.restoreCallingIdentity(identity); }
            reply.writeNoException(); reply.writeBundle(result); return true;
        }
    };
    private void sendInput(InputEvent event) throws Exception {
        if (displayId <= 0 || stopped.get()) return;
        if (!injectEvent(event, 0)) throw new SecurityException("系统拒绝虚拟屏输入注入");
    }
    /** Every injection (preview touch, external panel, keyboard) passes here; the first permission refusal is reported to the module once. */
    private boolean injectEvent(InputEvent event, int mode) throws Exception {
        setDisplayId.invoke(event, displayId);
        try { return Boolean.TRUE.equals(inject.invoke(inputManager, event, mode)); }
        catch (Exception e) { noteInputDenial(e); throw e; }
    }
    private void noteInputDenial(Exception e) {
        String error = Ipc.error(e);
        if (!dev.ichinomiya.ninebotenhance.core.InputDenial.matches(error) || !inputDeniedReported.compareAndSet(false, true)) return;
        try { Bundle data = new Bundle(); data.putString("error", LogDigest.head(error, 360)); providerCall("input_denied", data); }
        catch (Exception ignored) { /* Advisory only; the injection error itself still reaches the caller. */ }
    }
    /** Shared by the phone preview (Binder threads) and the external touch panel (its reader thread); one gesture state. */
    private synchronized void sendTouch(MotionEvent event) throws Exception {
        if (display == null || stopped.get()) return;
        Display target = display.getDisplay(); Point size = new Point(); target.getRealSize(size);
        int rotation = target.getRotation(), action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            cancelTouch(); gestureRotation = rotation;
        } else if (lastTouch == null) return;
        else if (rotation != gestureRotation) {
            // A rotation in the middle of a gesture cannot reinterpret an old drag as a new one.
            cancelTouch(); return;
        }
        Matrix transform = new Matrix();
        transform.setValues(DisplayInputTransform.matrix(rotation, settings.virtualWidth, settings.virtualHeight, size.x, size.y));
        event.transform(transform); event.setSource(InputDevice.SOURCE_TOUCHSCREEN); sendInput(event);
        if (lastTouch != null) { lastTouch.recycle(); lastTouch = null; }
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) lastTouch = MotionEvent.obtainNoHistory(event);
    }
    private long lastMarksError;
    /** The panel's contacts go straight to the Ninebot process on the session owner Binder, which paints them on the frame. */
    private void sendMarks(float[] points) { ownerPost(Protocol.OWNER_TOUCH_MARKS, "points", points, "marks"); }
    /** One calibration tap, raw normalized; the Ninebot process collects the targets and solves the map. */
    private void sendSample(float[] raw) { ownerPost(Protocol.OWNER_TOUCH_SAMPLE, "raw", raw, "sample"); }
    private void ownerPost(int code, String key, float[] values, String what) {
        if (stopped.get() || owner == null) return;
        try { Bundle data = Ipc.request(request); data.putFloatArray(key, values); Ipc.call(owner, code, data); }
        catch (Exception e) {
            if (SystemClock.elapsedRealtime() - lastMarksError > 5000) { lastMarksError = SystemClock.elapsedRealtime(); log("TOUCH " + what + " " + Ipc.error(e)); }
        }
    }
    private synchronized void cancelTouch() throws Exception {
        if (lastTouch == null) return;
        MotionEvent cancel = lastTouch; lastTouch = null;
        try { cancel.setAction(MotionEvent.ACTION_CANCEL); sendInput(cancel); }
        finally { cancel.recycle(); }
    }
    private void launch(ComponentName component) throws Exception {
        if (displayId <= 0) throw new IllegalStateException("虚拟屏已关闭");
        if (displayOrientation != null) try { displayOrientation.apply(); }
        catch (Exception e) { log("ORIENTATION before launch " + Ipc.error(e)); }
        Intent intent = AppCatalog.launchIntent(context.getPackageManager(), component);
        // The reference uses NEW_TASK only. MULTIPLE_TASK can select a different app initialization path.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Asked before the launch: moving the task relaunches the app and ends the phone navigation.
        String resume = navigationResume(component.getPackageName());
        int result = startOnDisplay(intent);
        if (result < 0) throw new IllegalStateException("应用无法在虚拟屏启动，系统返回 " + result);
        log("LAUNCH id=" + displayId + " entry=" + intent.getComponent().flattenToShortString()
                + " flags=0x" + Integer.toHexString(intent.getFlags()) + " result=" + result + " resume=" + (resume != null));
        long generation = ++launchGeneration;
        resumeUri = resume; resumePolls = 0; main.removeCallbacks(resumeWatch);
        if (resume != null) main.postDelayed(resumeWatch, RESUME_POLL_MS);
        // Observe both the splash screen and the settled activity. No hooks in target apps.
        for (long delay : new long[]{800, 3500}) main.postDelayed(() -> {
            if (!stopped.get() && launchGeneration == generation) logDisplayState();
        }, delay);
    }
    private int startOnDisplay(Intent intent) throws Exception {
        Bundle options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle();
        Method start = Class.forName("android.app.IActivityManager").getMethod("startActivityAsUser",
                Class.forName("android.app.IApplicationThread"), String.class, Intent.class, String.class,
                IBinder.class, String.class, int.class, int.class, Class.forName("android.app.ProfilerInfo"), Bundle.class, int.class);
        return (int)start.invoke(activityManager, null, "com.android.shell", intent, null, null, null, 0, 0, null, options, 0);
    }
    /**
     * AMap's map activity is relaunched by the system when its task moves to the virtual display (density and touchscreen
     * differ from the phone and are outside its configChanges), which destroys the navigation page. The module service knows
     * the destination and travel mode of the navigation that was live; its public route-plan URI is sent to the app once it
     * has settled on this display, so the same route is planned again there. Nothing else in the app is touched.
     */
    private String navigationResume(String pkg) {
        try {
            Bundle args = new Bundle(); args.putString("package", pkg);
            Bundle reply = providerCall("navi_resume", args);
            String uri = reply == null ? null : reply.getString("uri");
            return uri == null || uri.isEmpty() ? null : uri;
        } catch (Exception e) { log("NAVI RESUME query failed: " + Ipc.error(e)); return null; }
    }
    private final Runnable resumeWatch = new Runnable() {
        @Override public void run() {
            if (stopped.get() || resumeUri == null) return;
            boolean occupied;
            try { occupied = displayOccupied(); } catch (Exception e) { log("NAVI RESUME abandoned: " + Ipc.error(e)); resumeUri = null; return; }
            if (!occupied) {
                if (++resumePolls < RESUME_POLLS) main.postDelayed(this, RESUME_POLL_MS);
                else { log("NAVI RESUME abandoned: app never settled on the display"); resumeUri = null; }
                return;
            }
            main.postDelayed(RootDisplayMain.this::sendResume, RESUME_SETTLE_MS);
        }
    };
    private void sendResume() {
        String uri = resumeUri; resumeUri = null;
        if (uri == null || stopped.get() || selectedApp == null) return;
        try {
            if (!displayOccupied()) { log("NAVI RESUME skipped: display empty"); return; }
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(selectedApp.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int result = startOnDisplay(intent);
            log("NAVI RESUME id=" + displayId + " result=" + result + " " + uri.replaceAll("dname=[^&]*", "dname=…"));
        } catch (Exception e) { log("NAVI RESUME failed: " + Ipc.error(e)); }
    }
    private java.util.List<?> displayTasks() throws Exception {
        if (displayId <= 0 || display == null || !display.getDisplay().isValid()) throw new IllegalStateException("虚拟屏已关闭");
        if (getTasks == null) {
            taskManager = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            getTasks = Class.forName("android.app.IActivityTaskManager").getMethod("getTasks", int.class, boolean.class, boolean.class, int.class);
        }
        // Ask only about this display. We never enumerate, close or change unrelated tasks on the phone.
        Object result = getTasks.invoke(taskManager, 16, false, false, displayId);
        if (!(result instanceof java.util.List<?>)) throw new IllegalStateException("副屏任务状态不可用");
        return (java.util.List<?>)result;
    }
    private boolean displayOccupied() throws Exception {
        for (Object task : displayTasks()) {
            Class<?> type = task.getClass();
            if (type.getField("displayId").getInt(task) != displayId) continue;
            if (type.getField("topActivity").get(task) != null && type.getField("numActivities").getInt(task) > 0)
                return true; // Also retain other apps opened by the selected app, including permission/chooser activities.
        }
        return false;
    }
    private final Runnable appWatch = new Runnable() {
        @Override public void run() {
            if (stopped.get()) return;
            try {
                boolean occupied = displayOccupied();
                int before = appRecovery.state(); appRecovery.sample(SystemClock.elapsedRealtime(), occupied);
                if (occupied) recoveryDetail = "";
                else if (before == AppRecoveryState.RESTARTING && appRecovery.state() == AppRecoveryState.MISSING)
                    recoveryDetail = "应用未返回虚拟屏，可再次尝试";
            } catch (Exception e) {
                appRecovery.unknown();
                if (SystemClock.elapsedRealtime() - lastTaskError > 10000 || lastTaskError == 0) {
                    lastTaskError = SystemClock.elapsedRealtime(); log("APP WATCH unavailable: " + Ipc.error(e));
                }
            }
            publishAppRecovery();
            if (!stopped.get()) main.postDelayed(this, 1000);
        }
    };
    /** The module service learns when the panel's node is open and when it is gone, so the preview offers calibration only while it can be tapped. */
    private void publishTouchPresence(boolean present) {
        try { Bundle data = new Bundle(); data.putBoolean("present", present); providerCall("touch_state", data); }
        catch (Exception ignored) { /* Advisory only; the panel keeps working. */ }
    }
    private void publishAppRecovery() {
        try {
            Bundle data = new Bundle(); data.putInt(Protocol.APP_RECOVERY, appRecovery.state());
            data.putString(Protocol.APP_RECOVERY_DETAIL, recoveryDetail); providerCall("app_state", data);
            String value = "state=" + appRecovery.state() + (recoveryDetail.isEmpty() ? "" : " " + recoveryDetail);
            if (!value.equals(lastRecoveryLog)) { lastRecoveryLog = value; log("APP WATCH " + value); }
        } catch (Exception ignored) { /* A failed status update must not tear down a working cast. */ }
    }
    private void restartSelectedApp() {
        try {
            // Recheck immediately: the app may have returned since the user saw the prompt.
            if (displayOccupied()) { appRecovery.sample(SystemClock.elapsedRealtime(), true); recoveryDetail = ""; return; }
            if (!appRecovery.restart(SystemClock.elapsedRealtime())) return;
            recoveryDetail = ""; publishAppRecovery(); cancelTouch();
            launch(selectedApp);
            log("APP RESTART requested id=" + displayId + " package=" + selectedApp.getPackageName());
        } catch (Exception e) {
            appRecovery.failed(); recoveryDetail = "重新启动失败：" + Ipc.error(e); log("APP RESTART " + recoveryDetail);
        } finally { publishAppRecovery(); }
    }
    private void logDisplayState() {
        try {
            if (display == null || displayId <= 0) return;
            Display target = display.getDisplay(); Point size = new Point(); target.getRealSize(size);
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics(); target.getRealMetrics(metrics);
            log("DISPLAY id=" + displayId + " logical=" + size.x + "x" + size.y + " rotation=" + target.getRotation()
                    + " dpi=" + metrics.densityDpi + " buffer=" + settings.virtualWidth + "x" + settings.virtualHeight);
            Class<?> atm = Class.forName("android.app.ActivityTaskManager");
            Object service = atm.getMethod("getService").invoke(null);
            Object tasks = Class.forName("android.app.IActivityTaskManager")
                    .getMethod("getTasks", int.class, boolean.class, boolean.class, int.class)
                    .invoke(service, 1, false, false, displayId);
            for (Object task : (java.util.List<?>)tasks) {
                // The system-side query and this check both restrict diagnostics to our own display.
                if (task.getClass().getField("displayId").getInt(task) != displayId) continue;
                ComponentName top = (ComponentName)task.getClass().getField("topActivity").get(task);
                Configuration config = (Configuration)task.getClass().getField("configuration").get(task);
                String activity = top == null ? "none" : top.flattenToShortString();
                log("TASK id=" + displayId + " top=" + activity + " taskOrientation=" + config.orientation
                        + " dp=" + config.screenWidthDp + "x" + config.screenHeightDp + " sw=" + config.smallestScreenWidthDp);
                // Task bounds can be landscape while the app is letterboxed in a portrait window.
                try {
                    Object compat = task.getClass().getField("appCompatTaskInfo").get(task);
                    Class<?> type = compat.getClass();
                    log("APP_WINDOW id=" + displayId + " bounds=" + type.getField("topActivityAppBounds").get(compat)
                            + " letterboxed=" + type.getMethod("isTopActivityLetterboxed").invoke(compat)
                            + " sizeCompat=" + type.getMethod("isTopActivityInSizeCompat").invoke(compat));
                } catch (ReflectiveOperationException | NullPointerException e) { log("APP_WINDOW unavailable " + Ipc.error(e)); }
            }
        } catch (Exception e) { log("DISPLAY diagnostics unavailable " + Ipc.error(e)); }
    }
    private void log(String message) {
        try { Bundle data = new Bundle(); data.putString("message", LogDigest.head(message, 360)); providerCall("log", data); }
        catch (Exception ignored) { /* Diagnostics must not stop display creation, input or app launch. */ }
    }
    private Bundle providerCall(String method, Bundle args) throws Exception {
        Method call = Class.forName("android.content.IContentProvider").getMethod("call", AttributionSource.class,
                String.class, String.class, String.class, Bundle.class);
        return (Bundle)call.invoke(provider, new AttributionSource.Builder(2000).setPackageName("com.android.shell").build(),
                Protocol.ROOT_AUTHORITY, method, secret, args);
    }
    private void reportError(String error) { try { Bundle data = new Bundle(); data.putString("error", error); providerCall("error", data); } catch (Exception ignored) {} }
    private void exit() {
        if (!stopped.compareAndSet(false, true)) return;
        // Binder death also releases the display if release itself stalls in a vendor service.
        new Thread(() -> { try { Thread.sleep(1500); } catch (InterruptedException ignored) {} System.exit(0); }, "Mirror-ExitDeadline").start();
        release(); System.exit(0);
    }
    private void release() {
        main.removeCallbacks(appWatch); main.removeCallbacks(resumeWatch); resumeUri = null;
        if (touchPanel != null) { touchPanel.close(); touchPanel = null; }
        if (keyboard != null) keyboard.close();
        if (displayOrientation != null) displayOrientation.close();
        if (displayPower != null) displayPower.close();
        if (lastTouch != null) { lastTouch.recycle(); lastTouch = null; }
        if (display != null) { try { display.release(); } catch (RuntimeException ignored) {} display = null; }
        if (surface != null) { surface.release(); surface = null; }
        try { Class.forName("android.app.IActivityManager").getMethod("removeContentProviderExternal", String.class, IBinder.class)
                .invoke(activityManager, Protocol.ROOT_AUTHORITY, providerToken); } catch (Exception ignored) {}
    }
    /**
     * Keep-DPI without compat scaling: the display renders at the phone's density with a proportionally larger logical size, so a
     * navigation app moving between the phone and this display never sees a density change. WindowManager's forced size and density
     * change the logical display only; the physical size stays the RGBA buffer and DisplayManager's letterbox projection scales the
     * content back into it at no cost to the module. WindowManager creates its DisplayContent from the display-added event after
     * createVirtualDisplay returns, so the override is retried until the display reports it. Needs WRITE_SECURE_SETTINGS: root has
     * it, shell not on every ROM.
     */
    private void applyRenderPlan(DisplayManager manager) throws Exception {
        if (!settings.keepPhoneDpi) return;
        int phoneDpi = phoneDensityDpi(manager);
        DisplaySettings.RenderPlan plan = settings.renderPlan(phoneDpi);
        if (plan == null) { log("RENDER phoneDpi=" + phoneDpi + " needs no override"); return; }
        Object windowManager = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
        Class<?> api = Class.forName("android.view.IWindowManager");
        Method size = api.getMethod("setForcedDisplaySize", int.class, int.class, int.class);
        Method density = api.getMethod("setForcedDisplayDensityForUser", int.class, int.class, int.class);
        int user = moduleUid / 100000;
        long deadline = SystemClock.elapsedRealtime() + 3000; Point actual = new Point(); android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        while (true) {
            size.invoke(windowManager, displayId, plan.width(), plan.height()); density.invoke(windowManager, displayId, plan.dpi(), user);
            Display target = display.getDisplay(); target.getRealSize(actual); target.getRealMetrics(metrics);
            if (actual.x == plan.width() && actual.y == plan.height() && metrics.densityDpi == plan.dpi()) break;
            if (SystemClock.elapsedRealtime() > deadline)
                throw new IllegalStateException("系统未接受保持 DPI 的渲染尺寸：" + actual.x + "x" + actual.y + "@" + metrics.densityDpi
                        + "，需要 " + plan.width() + "x" + plan.height() + "@" + plan.dpi());
            Thread.sleep(100);
        }
        log("RENDER forced logical=" + plan.width() + "x" + plan.height() + " dpi=" + plan.dpi() + " buffer=" + settings.virtualWidth + "x" + settings.virtualHeight
                + " layoutDpi=" + settings.dpi + " phoneDpi=" + phoneDpi);
    }
    /** Undo a partially applied render plan so the display is left at its buffer size; failures here are already reported. */
    private void clearRenderPlan() {
        try {
            Object windowManager = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
            Class<?> api = Class.forName("android.view.IWindowManager");
            try { api.getMethod("clearForcedDisplaySize", int.class).invoke(windowManager, displayId); } catch (Exception ignored) {}
            try { api.getMethod("clearForcedDisplayDensityForUser", int.class, int.class).invoke(windowManager, displayId, moduleUid / 100000); } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }
    private static int phoneDensityDpi(DisplayManager manager) {
        Display phone = manager.getDisplay(Display.DEFAULT_DISPLAY);
        if (phone == null) throw new IllegalStateException("读不到手机主屏密度");
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics(); phone.getRealMetrics(metrics);
        if (metrics.densityDpi < 100 || metrics.densityDpi > 1000) throw new IllegalStateException("手机主屏密度异常：" + metrics.densityDpi);
        return metrics.densityDpi;
    }
    private static String sharedMemoryStatus = "unchecked";
    /**
     * Android 16 and later back several framework caches with {@code com.android.internal.os.ApplicationSharedMemory}. Only
     * {@code ActivityThread.attach} of a real application installs the region an app_process daemon has none, so the framework
     * throws "ApplicationSharedMemory not initialized" on first use. The daemon creates its own region before any framework call.
     */
    private static String initApplicationSharedMemory() {
        Class<?> type;
        try { type = Class.forName("com.android.internal.os.ApplicationSharedMemory"); }
        catch (ClassNotFoundException e) { try { type = Class.forName("android.app.ApplicationSharedMemory"); } catch (ClassNotFoundException e2) { return "absent"; } }
        try { if (type.getMethod("getInstance").invoke(null) != null) return "present"; } catch (Throwable ignored) { /* not initialized */ }
        try {
            Object instance = type.getMethod("create").invoke(null);
            type.getMethod("setInstance", type).invoke(null, instance);
            return "created";
        } catch (Throwable e) { return "unavailable " + Ipc.error(e); }
    }
    private static Context createShellContext() throws Exception {
        sharedMemoryStatus = initApplicationSharedMemory();
        // A minimal ActivityThread and ConfigurationController, following scrcpy's app_process workarounds.
        Class<?> type = Class.forName("android.app.ActivityThread"); Constructor<?> ctor = type.getDeclaredConstructor(); ctor.setAccessible(true);
        Object thread = ctor.newInstance(); field(type, "sCurrentActivityThread").set(null, thread); field(type, "mSystemThread").setBoolean(thread, true);
        Class<?> cc = Class.forName("android.app.ConfigurationController");
        Constructor<?> ccCtor = cc.getDeclaredConstructor(Class.forName("android.app.ActivityThreadInternal")); ccCtor.setAccessible(true);
        field(type, "mConfigurationController").set(thread, ccCtor.newInstance(thread));
        Context base = (Context)type.getDeclaredMethod("getSystemContext").invoke(thread);
        Context shell = new ContextWrapper(base) {
            @Override public String getPackageName() { return "com.android.shell"; }
            @Override public String getOpPackageName() { return "com.android.shell"; }
            @Override public AttributionSource getAttributionSource() { return new AttributionSource.Builder(2000).setPackageName("com.android.shell").build(); }
            @Override public Context getApplicationContext() { return this; }
            @Override public Context createPackageContext(String name, int flags) { return this; }
        };
        Class<?> bind = Class.forName("android.app.ActivityThread$AppBindData"); Constructor<?> bc = bind.getDeclaredConstructor(); bc.setAccessible(true);
        Object data = bc.newInstance(); ApplicationInfo info = new ApplicationInfo(); info.packageName = "com.android.shell"; info.uid = 2000;
        info.targetSdkVersion = 36; field(bind, "appInfo").set(data, info); field(type, "mBoundApplication").set(thread, data);
        field(type, "mInitialApplication").set(thread, Instrumentation.newApplication(Application.class, shell));
        return shell;
    }
    private static Field field(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
}
