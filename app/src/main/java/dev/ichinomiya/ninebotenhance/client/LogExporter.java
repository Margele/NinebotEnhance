package dev.ichinomiya.ninebotenhance.client;

import android.net.Uri;
import android.os.*;
import dev.ichinomiya.ninebotenhance.ipc.Ipc;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** File contents cross an FD, never a String in a Binder transaction or the clipboard. */
final class LogExporter {
    private final ServiceBridge bridge;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean();
    LogExporter(ServiceBridge bridge) { this.bridge = bridge; }
    void export(Supplier<String> snapshot, Consumer<Uri> done, Consumer<String> failed) {
        if (!busy.compareAndSet(false, true)) { failed.accept("上一份日志仍在生成，请稍后重试"); return; }
        AtomicBoolean completed = new AtomicBoolean();
        Runnable timeout = () -> { if (completed.compareAndSet(false, true)) failed.accept("日志生成超时，请稍后重试；当前摘要仍可查看"); };
        main.postDelayed(timeout, 8000); bridge.ensure();
        new Thread(() -> {
            Bundle ticket = new Bundle(); boolean published = false;
            try {
                String local = snapshot.get();
                while (!bridge.connected() && !completed.get()) { bridge.ensure(); Thread.sleep(100); }
                if (completed.get()) return;
                Bundle prepared = bridge.call(Protocol.LOG_EXPORT_BEGIN, new Bundle());
                ticket.putString("log_token", prepared.getString("log_token"));
                ParcelFileDescriptor fd = Ipc.parcelable(prepared, "log_fd", ParcelFileDescriptor.class);
                if (fd == null) throw new IOException("模块未返回日志文件");
                try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(fd)) {
                    if (completed.get()) return;
                    output.write(local.getBytes(StandardCharsets.UTF_8)); output.flush();
                }
                if (completed.get()) return;
                Uri uri = Ipc.parcelable(bridge.call(Protocol.LOG_EXPORT_FINISH, ticket), "log_uri", Uri.class);
                if (uri == null) throw new IOException("模块未返回分享地址");
                published = true;
                main.post(() -> { if (completed.compareAndSet(false, true)) { main.removeCallbacks(timeout); done.accept(uri); } });
            } catch (Exception e) {
                String message = Ipc.error(e);
                main.post(() -> { if (completed.compareAndSet(false, true)) { main.removeCallbacks(timeout); failed.accept(message); } });
            } finally {
                if (!published && ticket.containsKey("log_token")) try { bridge.call(Protocol.LOG_EXPORT_CANCEL, ticket); } catch (Exception ignored) {}
                busy.set(false);
            }
        }, "Ninebot-LogExport").start();
    }
}
