package dev.ichinomiya.ninebotenhance.privilege;

import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import dev.ichinomiya.ninebotenhance.core.PrivilegeMode;
import dev.ichinomiya.ninebotenhance.core.StartPermission;
import dev.ichinomiya.ninebotenhance.diagnostics.Diagnostics;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;
import dev.ichinomiya.ninebotenhance.ipc.Ipc;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import rikka.shizuku.Shizuku;
import rikka.sui.Sui;

/** Used only in the module's own process. Ninebot requests metadata/authorization through the broker. */
public final class PrivilegeManager {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int PERMISSION_REQUEST = 4101;
    private static final AtomicBoolean initialized = new AtomicBoolean();
    private static final AtomicBoolean permissionPending = new AtomicBoolean();
    private static volatile boolean binderReady;
    private static volatile String permissionError = "";

    /** Process-scoped listeners hold no Activity. The manifest provider already calls Sui.init once. */
    public static void initialize(Context context) {
        if (!Protocol.MODULE.equals(context.getPackageName())) throw new SecurityException("Authorization belongs to the module process");
        if (!initialized.compareAndSet(false, true)) return;
        Shizuku.addRequestPermissionResultListener((requestCode, result) -> {
            if (requestCode != PERMISSION_REQUEST) return;
            permissionPending.set(false);
            boolean granted = result == PackageManager.PERMISSION_GRANTED;
            permissionError = granted ? "" : "未授予权限，请重新授权或在管理界面修改。";
            Diagnostics.add("PRIVILEGE permission result=" + (granted ? "granted" : "denied"));
        }, MAIN);
        Shizuku.addBinderDeadListener(() -> {
            binderReady = false; permissionPending.set(false); permissionError = "";
            Diagnostics.add("PRIVILEGE binder disconnected");
        }, MAIN);
        Shizuku.addBinderReceivedListenerSticky(() -> {
            binderReady = true; permissionPending.set(false); permissionError = "";
            Diagnostics.add("PRIVILEGE binder ready: " + (Sui.isSui() ? "Sui" : "Shizuku"));
        }, MAIN);
    }
    public static Bundle status(Context context) {
        Bundle result = new Bundle(); result.putString("privilege_mode", mode(context).name()); result.putBoolean("keep_root", keepRoot(context));
        boolean running = Shizuku.pingBinder(), allowed = false, supported = false, blocked = false;
        String backend = Sui.isSui() ? "Sui" : "Shizuku / Sui";
        String detail;
        try {
            supported = running && binderReady && Shizuku.getVersion() >= 13;
            allowed = supported && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            blocked = supported && !allowed && Shizuku.shouldShowRequestPermissionRationale();
            if (!running) detail = "未连接 Shizuku / Sui，请先启动 Shizuku，或确认 Sui 已正常运行。";
            else if (!binderReady) detail = backend + " 正在等待服务初始化";
            else if (!supported) detail = "请更新 Shizuku / Sui：需要服务端 API 13 或以上";
            else if (allowed) detail = (Sui.isSui() ? "Sui" : "Shizuku") + " 已授权（UID " + Shizuku.getUid() + "）";
            else if (blocked) detail = blockedMessage();
            else if (permissionPending.get()) detail = backend + " 正在等待系统授权结果\n如果未出现弹窗，请检查服务是否正常，或到管理界面为 Ninebot Enhance 授权。";
            else detail = backend + " 未授权，点击下方按钮申请权限。";
        } catch (RuntimeException e) { running = false; allowed = false; supported = false; detail = "Shizuku / Sui 连接已失效"; }
        if (allowed) { permissionError = ""; permissionPending.set(false); }
        if (!blocked && !permissionError.isEmpty()) detail += "\n" + permissionError;
        result.putBoolean("privilege_running", running); result.putBoolean("privilege_granted", allowed);
        result.putBoolean("privilege_supported", supported);
        result.putBoolean("privilege_pending", permissionPending.get());
        result.putBoolean("privilege_can_request", supported && !allowed && !blocked && !permissionPending.get());
        result.putString("privilege_status", detail);
        RootAuthorization.status(result);
        PrivilegeMode selected = PrivilegeMode.parse(result.getString("privilege_mode"));
        StartPermission.Backend choice = StartPermission.select(selected, allowed, result.getBoolean("root_ready"));
        result.putBoolean("start_allowed", choice != StartPermission.Backend.NONE);
        result.putBoolean("start_pending", StartPermission.needsRootCheck(selected, allowed, result.getBoolean("root_ready")) && result.getBoolean("root_pending"));
        result.putString("start_backend", choice.name());
        result.putString("start_permission_message", selected == PrivilegeMode.NONE
                ? "开始投屏时通过系统窗口选择单个应用或整个屏幕。"
                : selected == PrivilegeMode.SHIZUKU
                ? "Shizuku / Sui 尚未连接或授权，请先到设置 → 授权方式申请权限。"
                : selected == PrivilegeMode.ROOT ? "Root 权限检查未通过，请到设置 → 授权方式检查权限。\n" + result.getString("root_status")
                : "当前没有可用授权，请先到设置 → 授权方式授权 Shizuku / Sui，或申请 / 验证 Root 权限。");
        return result;
    }
    public static void prepareStart(Context context) {
        Bundle current = status(context);
        if (StartPermission.needsRootCheck(mode(context), current.getBoolean("privilege_granted"), current.getBoolean("root_ready")))
            RootAuthorization.check(context);
    }
    private static String blockedMessage() {
        return "已拒绝且不再询问，请在 " + (Sui.isSui() ? "Sui 管理界面" : "Shizuku 的应用管理") + "中为 Ninebot Enhance 开启权限。";
    }
    public static PrivilegeMode mode(Context context) {
        try { return PrivilegeMode.parse(context.getSharedPreferences("privilege", 0).getString("mode", "AUTO")); }
        catch (IllegalArgumentException e) { return PrivilegeMode.AUTO; }
    }
    public static void save(Context context, String mode) {
        context.getSharedPreferences("privilege", 0).edit().putString("mode", PrivilegeMode.parse(mode).name()).apply();
    }
    /** The daemon keeps uid 0 instead of dropping to shell: for ROMs that deny shell INJECT_EVENTS. Only Root and root-run Shizuku / Sui can honour it. */
    public static boolean keepRoot(Context context) { return context.getSharedPreferences("privilege", 0).getBoolean("keep_root", false); }
    public static void saveKeepRoot(Context context, boolean keep) { context.getSharedPreferences("privilege", 0).edit().putBoolean("keep_root", keep).apply(); }
    public static void requestPermission() {
        if (!Shizuku.pingBinder() || !binderReady) throw new IllegalStateException("授权服务尚未就绪，请先启动 Shizuku，或确认 Sui 已正常运行");
        if (Shizuku.getVersion() < 13) throw new IllegalStateException("请更新 Shizuku / Sui：需要服务端 API 13 或以上");
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return;
        if (Shizuku.shouldShowRequestPermissionRationale()) throw new IllegalStateException(blockedMessage());
        if (!permissionPending.compareAndSet(false, true)) return;
        permissionError = "";
        MAIN.post(() -> { try {
                if (!binderReady || !Shizuku.pingBinder()) throw new IllegalStateException("授权服务已断开");
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { permissionPending.set(false); return; }
                if (Shizuku.shouldShowRequestPermissionRationale()) throw new IllegalStateException(blockedMessage());
                Shizuku.requestPermission(PERMISSION_REQUEST);
                Diagnostics.add("PRIVILEGE permission requested");
            }
            catch (RuntimeException e) {
                permissionPending.set(false);
                permissionError = "请求授权失败：" + Ipc.error(e);
                Diagnostics.add("PRIVILEGE " + permissionError);
            } });
    }
    public static java.lang.Process launch(Context context, String secret, IBinder owner) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Cannot wait for UserService on UI thread");
        Bundle available = status(context);
        if (!available.getBoolean("privilege_granted")) throw new IllegalStateException(available.getString("privilege_status"));
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(new ComponentName(context, PrivilegedLauncher.class))
                .tag("ninebot-enhance-display-" + java.util.UUID.randomUUID()).version(Protocol.VERSION_CODE).daemon(false).processNameSuffix("privileged");
        CompletableFuture<IBinder> connected = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                if (cancelled.get()) { cleanup(args, this); return; }
                connected.complete(service);
            }
            @Override public void onServiceDisconnected(ComponentName name) { connected.completeExceptionally(new IOException("授权服务已断开")); }
        };
        MAIN.post(() -> { try { Shizuku.bindUserService(args, connection); } catch (RuntimeException e) { connected.completeExceptionally(e); } });
        try {
            IBinder service = connected.get(15, TimeUnit.SECONDS);
            Bundle request = new Bundle(); request.putString("secret", secret); request.putBinder("owner", owner); request.putBoolean("keep_root", keepRoot(context));
            Bundle response = call(service, PrivilegedLauncher.START, request);
            ParcelFileDescriptor output = response.getParcelable("output", ParcelFileDescriptor.class);
            if (output == null) throw new IOException("授权服务没有返回输出通道");
            return new UserProcess(service, output, args, connection);
        } catch (Exception e) { cancelled.set(true); cleanup(args, connection); throw e; }
    }
    /** The fixed force-stop of the target through a short-lived UserService; blocks, so call it off the main thread. */
    public static int forceStopTarget(Context context) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Cannot wait for UserService on UI thread");
        Bundle available = status(context);
        if (!available.getBoolean("privilege_granted")) throw new IllegalStateException(available.getString("privilege_status"));
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(new ComponentName(context, PrivilegedLauncher.class))
                .tag("ninebot-enhance-stop-" + java.util.UUID.randomUUID()).version(Protocol.VERSION_CODE).daemon(false).processNameSuffix("privileged");
        CompletableFuture<IBinder> connected = new CompletableFuture<>();
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) { connected.complete(service); }
            @Override public void onServiceDisconnected(ComponentName name) { connected.completeExceptionally(new IOException("授权服务已断开")); }
        };
        MAIN.post(() -> { try { Shizuku.bindUserService(args, connection); } catch (RuntimeException e) { connected.completeExceptionally(e); } });
        try {
            IBinder service = connected.get(15, TimeUnit.SECONDS);
            Bundle response = call(service, PrivilegedLauncher.FORCE_STOP, new Bundle());
            return response.getBoolean("finished") ? response.getInt("exit") : -1;
        } finally { cleanup(args, connection); }
    }
    private static Bundle call(IBinder binder, int code, Bundle args) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PrivilegedLauncher.DESCRIPTOR); data.writeBundle(args);
            if (!binder.transact(code, data, reply, 0)) throw new RemoteException("UserService protocol mismatch");
            reply.readException(); return reply.readBundle(PrivilegeManager.class.getClassLoader());
        } finally { data.recycle(); reply.recycle(); }
    }
    private static void cleanup(Shizuku.UserServiceArgs args, ServiceConnection connection) {
        MAIN.post(() -> { try { Shizuku.unbindUserService(args, connection, true); } catch (RuntimeException ignored) {} });
    }
    private static final class UserProcess extends java.lang.Process {
        private final IBinder service;
        private final InputStream output;
        private final Shizuku.UserServiceArgs args;
        private final ServiceConnection connection;
        private boolean destroyed;
        UserProcess(IBinder service, ParcelFileDescriptor fd, Shizuku.UserServiceArgs args, ServiceConnection connection) {
            this.service = service; this.output = new ParcelFileDescriptor.AutoCloseInputStream(fd); this.args = args; this.connection = connection;
        }
        @Override public InputStream getInputStream() { return output; }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException("Daemon stdin is not exposed"); }
        @Override public int waitFor() throws InterruptedException {
            while (true) { try { return exitValue(); } catch (IllegalThreadStateException e) { Thread.sleep(200); } }
        }
        @Override public int exitValue() {
            try { Bundle result = call(service, PrivilegedLauncher.EXIT_CODE, new Bundle());
                if (!result.getBoolean("finished")) throw new IllegalThreadStateException(); return result.getInt("exit"); }
            catch (RemoteException e) { return -1; }
        }
        @Override public synchronized void destroy() {
            if (destroyed) return; destroyed = true;
            try { call(service, PrivilegedLauncher.STOP, new Bundle()); } catch (Exception ignored) {}
            try { output.close(); } catch (IOException ignored) {} cleanup(args, connection);
        }
    }
    private PrivilegeManager() {}
}
