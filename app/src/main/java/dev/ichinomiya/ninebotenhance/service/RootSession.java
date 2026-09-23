package dev.ichinomiya.ninebotenhance.service;

import dev.ichinomiya.ninebotenhance.core.AppRecoveryState;
import dev.ichinomiya.ninebotenhance.core.CallerPolicy;
import dev.ichinomiya.ninebotenhance.core.DisplaySettings;
import dev.ichinomiya.ninebotenhance.core.KeyboardPolicy;
import dev.ichinomiya.ninebotenhance.core.SessionLease;
import dev.ichinomiya.ninebotenhance.core.TouchCalibration;
import dev.ichinomiya.ninebotenhance.core.TouchPanel;
import dev.ichinomiya.ninebotenhance.diagnostics.Diagnostics;
import dev.ichinomiya.ninebotenhance.diagnostics.LogDigest;
import dev.ichinomiya.ninebotenhance.display.RootDisplayMain;
import dev.ichinomiya.ninebotenhance.ipc.Ipc;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;
import dev.ichinomiya.ninebotenhance.platform.AppCatalog;

import android.content.*;
import android.os.*;
import android.view.*;
import dev.ichinomiya.ninebotenhance.privilege.PrivilegeManager;
import dev.ichinomiya.ninebotenhance.privilege.RootAuthorization;
import dev.ichinomiya.ninebotenhance.core.StartPermission;
import java.io.*;
import java.util.UUID;

/** Module-process broker. Never performs su or remote Binder calls while holding the session lock. */
public final class RootSession {
    /** The daemon could not force the display size: the client switches keep-DPI to compat scaling from the next session. */
    private volatile boolean renderFallback;
    private static RootSession instance;
    public static synchronized RootSession get(Context context) {
        if (instance == null) instance = new RootSession(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final SessionLease lease = new SessionLease();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler commands;
    private final IBinder host = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            data.enforceInterface(Protocol.DESCRIPTOR);
            if (Binder.getCallingUid() != 2000 || code != Protocol.READ) throw new SecurityException();
            Bundle args = data.readBundle(getClass().getClassLoader()); Bundle result = new Bundle();
            synchronized (RootSession.this) { result.putBoolean("active", args != null && lease.owns(args.getString(Protocol.REQUEST))); }
            reply.writeNoException(); reply.writeBundle(result); return true;
        }
    };
    private Surface surface;
    private IBinder owner, root;
    private IBinder.DeathRecipient ownerDeath, rootDeath;
    private DisplaySettings current;
    private ComponentName launchApp;
    private int displayId = -1, ownerUid;
    private String state = "未启动", lastRequest;
    private String backend = "未启动";
    private volatile String previousExit = "系统退出记录尚未读取";
    private long lastInputError;
    private int appRecovery;
    private long appRecoveryAt;
    private String appRecoveryDetail = "";
    private String appLayoutPolicy = "not-created";
    private final java.util.concurrent.atomic.AtomicInteger pendingInput = new java.util.concurrent.atomic.AtomicInteger();
    private RootSession(Context context) {
        this.context = context;
        HandlerThread thread = new HandlerThread("Mirror-Control"); thread.start(); commands = new Handler(thread.getLooper());
        // Only inspect this module's own exit history, outside Binder/session locks and the UI thread.
        commands.post(() -> {
            try {
                android.app.ActivityManager manager = context.getSystemService(android.app.ActivityManager.class);
                StringBuilder history = new StringBuilder();
                for (android.app.ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 2)) {
                    String reason = exit.getReason() == android.app.ApplicationExitInfo.REASON_CRASH ? "JAVA_CRASH"
                            : exit.getReason() == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ? "NATIVE_CRASH"
                            : "reason=" + exit.getReason();
                    String line = "EXIT " + new java.util.Date(exit.getTimestamp()) + " pid=" + exit.getPid() + " " + reason
                            + " " + LogDigest.head(exit.getDescription(), 240);
                    Diagnostics.add(line); history.append(line).append('\n');
                }
                previousExit = history.length() == 0 ? "系统未提供此前模块进程退出记录" : history.toString();
            } catch (RuntimeException e) { previousExit = "系统退出记录不可用：" + Ipc.error(e); }
        });
    }
    public synchronized DisplaySettings settings() {
        android.content.SharedPreferences p = context.getSharedPreferences("virtual_display", 0);
        try { return DisplaySettings.read(p::getInt); }
        catch (IllegalArgumentException e) { return DisplaySettings.defaults(); }
    }
    /** The external touch panel bound on the module's own touch screen page; the daemon reads and grabs it for the session. */
    public TouchPanel touchPanel() {
        android.content.SharedPreferences p = context.getSharedPreferences(dev.ichinomiya.ninebotenhance.ui.TouchSettingsActivity.PREFERENCES, 0);
        return TouchPanel.read(p::getInt, p::getString);
    }
    public synchronized Bundle settingsBundle() {
        Bundle data = new Bundle(); Ipc.settings(data, settings());
        data.putString(AppCatalog.SELECTED, context.getSharedPreferences("virtual_display", 0).getString(AppCatalog.SELECTED, ""));
        data.putString("privilege_mode", PrivilegeManager.mode(context).name());
        return data;
    }
    public void saveSettings(DisplaySettings value, String selected) {
        // PackageManager is a remote call: validate outside the session lock.
        ComponentName app = AppCatalog.requireLauncher(context.getPackageManager(), selected);
        synchronized (this) {
            if (lease.request() != null) throw new IllegalStateException("请先停止投屏再修改设置");
            context.getSharedPreferences("virtual_display", 0).edit().putInt("width", value.width)
                    .putInt("height",value.height).putInt("dpi",value.dpi)
                    .putInt("layout_version",DisplaySettings.LAYOUT_VERSION).putInt("virtual_width",value.virtualWidth).putInt("virtual_height",value.virtualHeight)
                    .putInt("background_color",value.backgroundColor).putInt("keep_phone_dpi",value.keepPhoneDpi?1:0).putInt("compat_scale",value.compatScale?1:0).putInt("virtual_override",value.virtualOverride?1:0).putInt("light_background_color",value.lightBackgroundColor).remove("top_inset").remove("top_color")
                    .putString(AppCatalog.SELECTED, app.flattenToString()).apply();
        }
    }
    /** Compat scaling: the capture surface size and density the client chose for this session (0 when not in use). */
    private int renderWidth, renderHeight, renderDpi;
    public synchronized void setRenderPlan(int width, int height, int dpi) { renderWidth = width; renderHeight = height; renderDpi = dpi; }
    public void begin(String request, Surface output, IBinder client, int uid, DisplaySettings requested, String selected) throws RemoteException {
        if (!Protocol.validRequest(request) || output == null || !output.isValid() || client == null || !client.isBinderAlive()) {
            if (output != null) output.release(); throw new IllegalArgumentException("虚拟屏接收端未就绪");
        }
        ComponentName app;
        StartPermission.Backend authorized;
        try {
            Bundle permission = PrivilegeManager.status(context);
            if (!permission.getBoolean("start_allowed")) throw new IllegalStateException(permission.getString("start_permission_message"));
            authorized = StartPermission.Backend.valueOf(permission.getString("start_backend"));
            if (authorized == StartPermission.Backend.MEDIA_PROJECTION) throw new IllegalStateException("录屏模式不能创建独立虚拟屏");
            app = AppCatalog.requireLauncher(context.getPackageManager(), selected);
        }
        catch (RuntimeException e) { output.release(); throw e; }
        String secret = UUID.randomUUID().toString().replace("-", "");
        synchronized (this) {
            if (android.os.Process.myUid() / 100000 != 0) { output.release(); throw new IllegalStateException("此版本仅支持手机主用户"); }
            if (!lease.begin(request, secret)) { output.release(); throw new IllegalStateException("已有投屏正在运行"); }
            current = requested; launchApp = app; surface = output; owner = client; ownerUid = uid; root = null; displayId = -1;
            appRecovery = AppRecoveryState.HIDDEN; appRecoveryAt = 0; appRecoveryDetail = "";
            appLayoutPolicy = "not-created";
            lastRequest = request; backend = "正在选择授权方式"; state = backend;
            ownerDeath = () -> stop(request, "九号进程已退出");
            try { client.linkToDeath(ownerDeath, 0); }
            catch (RemoteException e) { stop(request, "九号已退出"); throw e; }
        }
        Diagnostics.add("VD begin " + current.label() + "; raw Surface, no MediaProjection/JPEG");
        TouchPanel panel = touchPanel();
        if (panel.bound()) { Diagnostics.add("TOUCH panel " + panel.label() + " rotation=" + panel.rotation() * 90); TouchPanelUsb.acquire(context, panel, Diagnostics::add); }
        new Thread(() -> launchRoot(request, secret, authorized), "Mirror-RootBootstrap").start();
        main.postDelayed(() -> { synchronized (RootSession.this) { if (!lease.owns(request) || lease.isReady()) return; }
            stop(request, "创建超时，请检查设置中的授权方式与服务状态，并查看日志"); }, 60000);
    }
    private void launchRoot(String request, String secret, StartPermission.Backend authorized) {
        java.lang.Process process = null;
        try {
            Bundle available = PrivilegeManager.status(context);
            boolean shizuku = authorized == StartPermission.Backend.SHIZUKU;
            if (!(shizuku ? available.getBoolean("privilege_granted") : available.getBoolean("root_ready")))
                throw new IllegalStateException("授权连接已失效，请先到设置 → 授权方式重新授权");
            synchronized (this) {
                if (!lease.owns(request)) return;
                backend = shizuku ? available.getString("privilege_status") : "Root 授权的 shell 辅助进程";
                state = shizuku ? "正在启动 Shizuku / Sui 辅助进程" : "正在使用已授权的 Root 连接启动虚拟屏";
            }
            Diagnostics.add("BACKEND " + backend);
            process = shizuku ? PrivilegeManager.launch(context, secret, host)
                    : RootAuthorization.launch(context, secret);
            // Drain continuously to avoid pipe backpressure; log only bounded diagnostic lines, never the nonce.
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line; int count = 0;
                while ((line = reader.readLine()) != null) if (count++ < 40) Diagnostics.add("ROOT " + line.replace(secret, "<session>"));
            }
            int code = process.waitFor();
            synchronized (this) { if (!lease.owns(request)) return; }
            stop(request, "辅助进程已退出（" + code + "），请查看日志检查创建或权限错误");
        } catch (Exception e) { stop(request, "无法启动虚拟屏：" + Ipc.error(e)); }
        finally { if (process != null) process.destroy(); }
    }
    public synchronized Bundle handshake(String method, String secret, Bundle args) {
        Bundle result = new Bundle();
        if ("attach".equals(method)) {
            if (!lease.attach(secret)) throw new SecurityException("启动请求已取消或已使用");
            result.putString(Protocol.REQUEST, lease.request()); result.putParcelable("surface", surface);
            result.putBinder("owner", owner); result.putBinder("host", host);
            result.putInt("uid", android.os.Process.myUid()); result.putInt("ownerUid", ownerUid);
            result.putParcelable(AppCatalog.SELECTED, launchApp);
            Ipc.settings(result, current); state = "正在创建虚拟显示器";
            result.putInt(Protocol.CAPTURE_WIDTH, renderWidth); result.putInt(Protocol.CAPTURE_HEIGHT, renderHeight); result.putInt("render_dpi", renderDpi);
            touchPanel().write(result::putInt, result::putString);
        } else {
            if (!lease.authorize(secret)) throw new SecurityException("过期的辅助进程");
            if ("ready".equals(method)) {
                if (root != null) throw new SecurityException("重复注册");
                IBinder endpoint = args.getBinder("root"); int id = args.getInt("displayId", -1);
                if (endpoint == null || id <= 0) throw new IllegalArgumentException("无效的虚拟屏");
                String request = lease.request(); rootDeath = () -> stop(request, "Root 虚拟屏进程已结束");
                try { endpoint.linkToDeath(rootDeath, 0); } catch (RemoteException e) { throw new IllegalStateException("辅助进程已退出"); }
                root = endpoint; displayId = id; lease.ready(secret); state = "虚拟屏已创建：" + current.label();
                appLayoutPolicy = LogDigest.head(args.getString(Protocol.APP_LAYOUT_POLICY, "unreported"), 80);
                Diagnostics.add("VD ready displayId=" + id + " " + current.label());
            } else if ("app_state".equals(method)) {
                if (!lease.isReady() || root == null) throw new SecurityException("副屏尚未就绪");
                appRecovery = args.getInt(Protocol.APP_RECOVERY); appRecoveryDetail = LogDigest.head(args.getString(Protocol.APP_RECOVERY_DETAIL, ""), 500);
                appRecoveryAt = SystemClock.elapsedRealtime();
            } else if ("render_fallback".equals(method)) {
                renderFallback = true; Diagnostics.add("ROOT RENDER fallback reported; compat scaling will be forced for the next session");
            } else if ("error".equals(method)) {
                String request = lease.request(), error = args.getString("error", "未知错误");
                main.post(() -> stop(request, error));
            } else if ("log".equals(method)) Diagnostics.add("ROOT " + args.getString("message", ""));
            else if ("navi_resume".equals(method)) {
                String uri = dev.ichinomiya.ninebotenhance.navi.NaviHub.get().resumeUri(args.getString("package", ""));
                if (uri != null) result.putString("uri", uri);
            }
            else throw new IllegalArgumentException("未知操作");
        }
        return result;
    }
    public synchronized Bundle status() {
        Bundle result = new Bundle(); result.putString(Protocol.REQUEST, lease.request() == null ? lastRequest : lease.request());
        result.putInt("brokerPid", android.os.Process.myPid()); result.putString("brokerVersion", Protocol.VERSION);
        result.putBoolean("active", lease.request() != null); result.putBoolean("ready", lease.isReady());
        result.putInt("displayId", displayId); result.putString("state", state);
        result.putString("backend", backend);
        result.putString(Protocol.APP_LAYOUT_POLICY, appLayoutPolicy); result.putBoolean("render_fallback", renderFallback);
        result.putBoolean("touch_bound", touchPanel().bound());
        result.putString(AppCatalog.SELECTED, launchApp == null ? "" : launchApp.flattenToString());
        boolean recent = appRecoveryAt != 0 && SystemClock.elapsedRealtime() - appRecoveryAt < 5000 && lease.isReady();
        result.putInt(Protocol.APP_RECOVERY, recent ? appRecovery : AppRecoveryState.HIDDEN);
        result.putString(Protocol.APP_RECOVERY_DETAIL, recent ? appRecoveryDetail : "");
        Ipc.settings(result, current == null ? settings() : current); return result;
    }
    public synchronized boolean owns(String id) { return lease.owns(id); }
    public synchronized boolean ready(String id) { return lease.owns(id) && lease.isReady(); }
    public synchronized int displayId() { return displayId; }
    public synchronized String state() { return state; }
    public synchronized void savePrivilege(String mode) {
        if (lease.request() != null) throw new IllegalStateException("请先结束投屏再修改授权方式");
        PrivilegeManager.save(context, mode);
    }
    public String previousExit() { return previousExit; }
    public void stopCurrent(String reason) { String request; synchronized (this) { request = lease.request(); } stop(request, reason); }
    public void stop(String request, String reason) {
        IBinder endpoint, client; Surface output;
        synchronized (this) {
            if (!lease.end(request)) return;
            endpoint = root; client = owner; output = surface;
            if (client != null && ownerDeath != null) try { client.unlinkToDeath(ownerDeath, 0); } catch (RuntimeException ignored) {}
            if (endpoint != null && rootDeath != null) try { endpoint.unlinkToDeath(rootDeath, 0); } catch (RuntimeException ignored) {}
            root = owner = null; surface = null; displayId = -1; state = reason;
        }
        Diagnostics.add("VD stopped: " + reason);
        TouchPanelUsb.release();
        if (output != null) output.release();
        // Per-display lease Binder dies logically as soon as the broker revokes it (daemon polls STATUS below).
        commands.post(() -> { if (endpoint != null) try { Ipc.call(endpoint, Protocol.ROOT_STOP, Ipc.request(request)); } catch (Exception ignored) {} });
    }
    public synchronized void requireController(String request, int uid) {
        if (!CallerPolicy.controls(uid, ownerUid, lease.owns(request) && lease.isReady()))
            throw new SecurityException("仅允许当前投屏的九号进程控制虚拟屏");
    }
    public void key(String request, int code) { Bundle args = Ipc.request(request); args.putInt("key", code); send(request, Protocol.ROOT_KEY, args); }
    /** Preview toolbar calibration: the daemon stops injecting and reports each tap's raw position until told otherwise. */
    public void touchCalibrate(String request, boolean calibrating) {
        Bundle args = Ipc.request(request); args.putBoolean("calibrating", calibrating); send(request, Protocol.ROOT_TOUCH_CALIBRATE, args);
    }
    /** Stores the solved map with the panel binding and hands it to the running daemon; an empty text clears it. */
    public void saveTouchCalibration(String request, String calibration) {
        TouchCalibration parsed = TouchCalibration.decode(calibration);
        if (calibration != null && !calibration.trim().isEmpty() && parsed == null) throw new IllegalArgumentException("无效的校准数据");
        String text = parsed == null ? "" : parsed.encode();
        context.getSharedPreferences(dev.ichinomiya.ninebotenhance.ui.TouchSettingsActivity.PREFERENCES, 0).edit().putString("touch_calibration", text).apply();
        Diagnostics.add("TOUCH calibration " + (parsed == null ? "cleared" : text));
        Bundle args = Ipc.request(request); args.putString("calibration", text); send(request, Protocol.ROOT_TOUCH_CALIBRATION, args);
    }
    public void restartApp(String request) { send(request, Protocol.ROOT_RESTART_APP, Ipc.request(request)); }
    public void keyboard(String request, int code, Bundle args) {
        if (code == Protocol.UI_TEXT) { KeyboardPolicy.text(args.getString("text")); send(request, Protocol.ROOT_TEXT, args); }
        else if (code == Protocol.UI_DELETE) { KeyboardPolicy.deletion(args.getInt("before"), args.getInt("after")); send(request, Protocol.ROOT_DELETE, args); }
        else if (code == Protocol.UI_TYPING_KEY) {
            if (!KeyboardPolicy.key(args.getInt("key")) || args.getInt("action", -1) < 0 || args.getInt("action", -1) > 1)
                throw new IllegalArgumentException("不支持的输入按键");
            send(request, Protocol.ROOT_TYPING_KEY, args);
        }
    }
    public void input(String request, MotionEvent event) {
        // Keep gesture boundaries, but drop superseded motion under backpressure.
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && pendingInput.get() >= 3) { event.recycle(); return; }
        Bundle args = Ipc.request(request); args.putParcelable("event", event);
        IBinder endpoint; synchronized (this) { endpoint = lease.owns(request) ? root : null; }
        pendingInput.incrementAndGet();
        commands.post(() -> { try { if (endpoint != null) {
                String error = Ipc.call(endpoint, Protocol.ROOT_INPUT, args).getString("error");
                if (error != null && SystemClock.elapsedRealtime() - lastInputError > 5000) {
                    lastInputError = SystemClock.elapsedRealtime(); Diagnostics.add("INPUT " + error);
                    main.post(() -> android.widget.Toast.makeText(context, "触控注入失败，请查看日志：" + error, 1).show());
                }
            } }
            catch (Exception e) { Diagnostics.add("INPUT " + Ipc.error(e)); }
            finally { pendingInput.decrementAndGet(); event.recycle(); } });
    }
    private void send(String request, int code, Bundle args) {
        IBinder endpoint; synchronized (this) { endpoint = lease.owns(request) ? root : null; }
        commands.post(() -> { try {
            if (endpoint == null) return;
            Bundle result = Ipc.call(endpoint, code, args); String error = result.getString("error");
            if (error != null) { Diagnostics.add(error); main.post(() -> android.widget.Toast.makeText(context, error, 1).show()); }
        } catch (Exception e) { stop(request, "虚拟屏连接中断：" + Ipc.error(e)); } });
    }
}
