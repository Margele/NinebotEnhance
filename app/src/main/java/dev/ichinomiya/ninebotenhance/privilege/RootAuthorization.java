package dev.ichinomiya.ninebotenhance.privilege;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import dev.ichinomiya.ninebotenhance.core.AuthorizedShell;
import dev.ichinomiya.ninebotenhance.core.DisplaySettings;
import dev.ichinomiya.ninebotenhance.diagnostics.Diagnostics;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;

/** Re-establishes and verifies the manager-authorized connection before any VirtualDisplay exists. */
public final class RootAuthorization {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Shell uid with the input group (AID_INPUT 1004) so the daemon may read an external touch panel; the plain form is the fallback. */
    private static final String[] SHELL_WITH_INPUT = {"su", "2000", "-g", "2000", "-G", "1004", "-c", "exec /system/bin/sh"},
            SHELL_PLAIN = {"su", "2000", "-c", "exec /system/bin/sh"};
    /** The "keep root" option: uid 0 throughout, for ROMs whose shell lacks INJECT_EVENTS. Root reads input devices without the group. */
    private static final String[] ROOT_SHELL = {"su", "-c", "exec /system/bin/sh"};
    private static Attempt current;
    private static final class Attempt {
        Process process;
        AuthorizedShell shell;
        String error;
        boolean automatic;
    }
    public static synchronized void status(Bundle data) {
        boolean ready = current != null && current.shell != null && current.shell.ready();
        boolean pending = current != null && current.error == null && (current.shell == null || current.shell.pending());
        data.putBoolean("root_ready", ready); data.putBoolean("root_pending", pending);
        data.putString("root_status", ready ? "Root 权限可用" : pending ? current.automatic ? "正在检查 Root 权限并连接服务…" : "Root 正在申请 / 验证，请处理权限管理器提示"
                : current == null ? "Root 尚未连接，开始投屏时会自动检查权限"
                : current.error != null ? current.error : current.shell.failure());
    }
    public static void request(Context context) {
        connect(context, false);
    }
    public static void check(Context context) {
        connect(context, true);
    }
    private static void connect(Context context, boolean automatic) {
        if (!Protocol.MODULE.equals(context.getPackageName())) throw new SecurityException("Root authorization belongs to the module");
        Attempt attempt;
        synchronized (RootAuthorization.class) {
            Bundle state = new Bundle(); status(state);
            if (state.getBoolean("root_ready") || state.getBoolean("root_pending")) return;
            attempt = new Attempt(); attempt.automatic = automatic; current = attempt;
        }
        boolean keepRoot = PrivilegeManager.keepRoot(context);
        Diagnostics.add((automatic ? "PRIVILEGE checking Root before cast" : "PRIVILEGE Root request from settings") + (keepRoot ? " (keep root)" : ""));
        new Thread(() -> {
            try {
                AuthorizedShell shell = null;
                for (boolean inputGroup : keepRoot ? new boolean[]{false} : new boolean[]{true, false}) {
                    // Shell with the input group lets the daemon read an external touch panel; a su that rejects the option is retried
                    // without it. Keep-root skips the drop entirely and verifies uid 0 instead.
                    Process process = new ProcessBuilder(keepRoot ? ROOT_SHELL : inputGroup ? SHELL_WITH_INPUT : SHELL_PLAIN).redirectErrorStream(true).start();
                    synchronized (RootAuthorization.class) {
                        if (current != attempt || attempt.error != null) { process.destroy(); return; }
                        attempt.process = process;
                    }
                    shell = new AuthorizedShell(process, keepRoot ? 0 : 2000);
                    long deadline = android.os.SystemClock.elapsedRealtime() + 1200;
                    while (!shell.ready() && shell.pending() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
                    if (shell.ready() || shell.pending() || !inputGroup) break;
                    Diagnostics.add("PRIVILEGE su rejected the input group option, retrying without it");
                }
                synchronized (RootAuthorization.class) {
                    if (current != attempt || attempt.error != null) { shell.close(); return; }
                    attempt.shell = shell;
                }
            } catch (Exception e) {
                synchronized (RootAuthorization.class) {
                    if (current == attempt) attempt.error = "Root 连接失败，请在 KernelSU / Magisk 中允许 Ninebot Enhance 后重试（" + e.getClass().getSimpleName() + "）";
                    if (attempt.process != null) attempt.process.destroy();
                }
            }
        }, "Enhance-RootAuthorization").start();
        MAIN.postDelayed(() -> {
            synchronized (RootAuthorization.class) {
                if (current != attempt || attempt.error != null || attempt.shell != null && attempt.shell.ready()) return;
                attempt.error = automatic ? "Root 权限检查超时，请确认管理器已允许 Ninebot Enhance 后重试" : "Root 申请超时或未获授权，请在设置中重新申请 / 验证";
                if (attempt.shell != null) attempt.shell.close(attempt.error);
                else if (attempt.process != null) attempt.process.destroy();
            }
        }, automatic ? 8000 : 45000);
    }
    public static Process launch(Context context, String secret) throws Exception {
        if (!Protocol.validRequest(secret)) throw new SecurityException("Invalid session");
        AuthorizedShell shell;
        synchronized (RootAuthorization.class) { shell = current == null ? null : current.shell; }
        if (shell == null || !shell.ready()) throw new IllegalStateException("请先到设置申请 / 验证 Root 权限");
        return shell.launch("CLASSPATH=" + DisplaySettings.shellQuote(context.getApplicationInfo().sourceDir)
                + " /system/bin/app_process / " + Protocol.DAEMON_CLASS + " " + DisplaySettings.shellQuote(secret));
    }
    public static void close() {
        Attempt previous; synchronized (RootAuthorization.class) { previous = current; current = null; }
        if (previous == null) return;
        if (previous.shell != null) previous.shell.close();
        else if (previous.process != null) previous.process.destroy();
    }
    private RootAuthorization() {}
}
