package dev.ichinomiya.ninebotenhance.privilege;

import android.content.Context;
import android.os.*;
import dev.ichinomiya.ninebotenhance.core.DisplaySettings;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;
import java.io.*;

/**
 * Shizuku UserService: launches only our fixed display daemon and force-stops only the target package, never an arbitrary
 * shell command.
 */
public final class PrivilegedLauncher extends Binder {
    public static final String DESCRIPTOR = Protocol.MODULE + ".PrivilegedLauncher.v1";
    public static final int START = 1, EXIT_CODE = 2, STOP = 3, FORCE_STOP = 4, DESTROY = 16777115;
    private final int appUid;
    private final String apk;
    private java.lang.Process child;
    private IBinder owner;
    private boolean used;
    public PrivilegedLauncher(Context context) {
        if (!Protocol.MODULE.equals(context.getPackageName())) throw new SecurityException("Unexpected launcher context");
        appUid = context.getApplicationInfo().uid; apk = context.getApplicationInfo().sourceDir;
        if (appUid < 10000 || apk == null) throw new SecurityException("Invalid owner");
    }
    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
        // The documented Shizuku destroy transaction is sent by the privileged server.
        int caller = Binder.getCallingUid();
        if (code == DESTROY && (caller == appUid || caller == 0 || caller == 2000)) { shutdown(); return true; }
        if (caller != appUid) throw new SecurityException("Only the owning module may launch the daemon");
        data.enforceInterface(DESCRIPTOR);
        long identity = Binder.clearCallingIdentity();
        try {
            Bundle result = new Bundle();
            switch (code) {
                case START:
                    Bundle args = data.readBundle(getClass().getClassLoader());
                    String secret = args == null ? null : args.getString("secret");
                    IBinder lifecycle = args == null ? null : args.getBinder("owner");
                    boolean keepRoot = args != null && args.getBoolean("keep_root");
                    if (!Protocol.validRequest(secret) || lifecycle == null || !lifecycle.isBinderAlive()) throw new SecurityException("Invalid session");
                    synchronized (this) {
                        if (used) throw new IllegalStateException("Launcher already used"); used = true;
                        owner = lifecycle; owner.linkToDeath(this::shutdown, 0);
                        String command = "CLASSPATH=" + DisplaySettings.shellQuote(apk)
                                + " /system/bin/app_process / " + Protocol.DAEMON_CLASS + " " + DisplaySettings.shellQuote(secret);
                        int uid = android.os.Process.myUid();
                        // Shizuku over ADB is already shell. A root-run Shizuku / Sui drops to shell unless the module asked to keep root.
                        if (uid == 2000 || (uid == 0 && keepRoot)) child = new ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start();
                        else if (uid == 0) child = asShell(command);
                        else throw new SecurityException("Unsupported Shizuku UID");
                        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                        result.putParcelable("output", pipe[0]); result.putInt("uid", uid);
                        java.lang.Process process = child;
                        new Thread(() -> {
                            try (InputStream input = process.getInputStream(); OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                                byte[] buffer = new byte[4096]; int count;
                                while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
                            } catch (IOException ignored) { process.destroy(); }
                        }, "Enhance-DaemonOutput").start();
                        reply.writeNoException(); reply.writeBundle(result); pipe[0].close(); return true;
                    }
                case EXIT_CODE:
                    synchronized (this) { if (child == null) throw new IllegalStateException("Not started");
                        try { result.putInt("exit", child.exitValue()); result.putBoolean("finished", true); }
                        catch (IllegalThreadStateException ignored) { result.putBoolean("finished", false); } }
                    break;
                case STOP: shutdown(); break;
                case FORCE_STOP: {
                    // The one fixed command besides the daemon: stop the target so LSPosed injects the module on its next start.
                    java.lang.Process stop = new ProcessBuilder("/system/bin/sh", "-c", "am force-stop " + Protocol.TARGET).redirectErrorStream(true).start();
                    boolean finished = stop.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                    if (!finished) stop.destroy();
                    result.putBoolean("finished", finished); result.putInt("exit", finished ? stop.exitValue() : -1);
                    break;
                }
                default: return false;
            }
            reply.writeNoException(); reply.writeBundle(result); return true;
        } catch (IOException e) { throw new IllegalStateException("无法启动辅助进程：" + e.getClass().getSimpleName()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("等待命令结束时被中断"); }
        finally { Binder.restoreCallingIdentity(identity); }
    }
    /** Shell uid plus the input group (external touch panels); a su that rejects the group options is retried without them. */
    private static java.lang.Process asShell(String command) throws IOException {
        java.lang.Process first = new ProcessBuilder("su", "2000", "-g", "2000", "-G", "1004", "-c", command).redirectErrorStream(true).start();
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        if (first.isAlive() || first.exitValue() == 0) return first;
        return new ProcessBuilder("su", "2000", "-c", command).redirectErrorStream(true).start();
    }
    private void shutdown() {
        synchronized (this) { if (child != null) child.destroy(); }
        new Thread(() -> { try { Thread.sleep(100); } catch (InterruptedException ignored) {} System.exit(0); }, "Enhance-LauncherExit").start();
    }
}
