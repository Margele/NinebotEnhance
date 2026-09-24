package dev.ichinomiya.ninebotenhance.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

/** A verified, retained shell. Checking readiness never starts su or requests permission. */
public final class AuthorizedShell implements AutoCloseable {
    private final Process process;
    /** The uid the shell must report: shell (2000) after the usual drop, root (0) with the keep-root authorization option. */
    private final int expectedUid;
    private final OutputStream commands;
    private final String readyMarker = marker();
    private volatile boolean ready, closed;
    private volatile String failure = "正在等待 Root 授权与身份验证";
    private Job active;

    public AuthorizedShell(Process process) throws IOException { this(process, 2000); }
    public AuthorizedShell(Process process, int expectedUid) throws IOException {
        this.process = process; this.expectedUid = expectedUid; commands = process.getOutputStream();
        Thread reader = new Thread(this::read, "Enhance-RootOutput"); reader.setDaemon(true); reader.start();
        try { write("printf '\\n%s:%s\\n' '" + readyMarker + "' \"$(/system/bin/id -u)\"\n"); }
        catch (IOException e) { close("Root 验证通道已关闭"); throw e; }
    }
    private static String marker() { return "ENHANCE_" + UUID.randomUUID().toString().replace("-", ""); }
    public boolean ready() { return ready && !closed && process.isAlive(); }
    public boolean pending() { return !ready && !closed && process.isAlive(); }
    public String failure() { return failure; }

    /** Internal fixed daemon command only. One display job at a time; its output has an independent EOF. */
    public synchronized Process launch(String command) throws IOException {
        if (!ready()) throw new IllegalStateException("Root 连接未就绪，请先到设置申请 / 验证 Root 权限");
        if (active != null) throw new IllegalStateException("上一虚拟屏辅助进程尚未退出，请稍后再试");
        Job job = new Job(); active = job;
        try {
            write("( " + command + " ); _enhance_exit=$?; printf '\\n%s:%s\\n' '" + job.endMarker + "' \"$_enhance_exit\"\n");
            return job;
        } catch (IOException e) { close("Root 启动通道已关闭"); throw e; }
    }
    private void write(String text) throws IOException { commands.write(text.getBytes(StandardCharsets.UTF_8)); commands.flush(); }
    private void read() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!ready && line.startsWith(readyMarker + ":")) {
                    if (!line.equals(readyMarker + ":" + expectedUid)) { close("Root 验证失败：辅助进程未取得 " + (expectedUid == 0 ? "root" : "shell") + " 身份"); return; }
                    synchronized (this) { if (!closed) { ready = true; failure = ""; } }
                    continue;
                }
                Job job; synchronized (this) { job = active; }
                if (job == null) continue;
                if (line.startsWith(job.endMarker + ":")) {
                    int code;
                    try { code = Integer.parseInt(line.substring(job.endMarker.length() + 1)); }
                    catch (NumberFormatException e) { close("Root 辅助进程返回无效状态"); return; }
                    synchronized (this) { if (active == job) active = null; }
                    job.finish(code);
                } else job.output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // The next start checks the manager again instead of treating this dead handle as a denial.
        } finally { close("Root 连接已失效，开始投屏时将重新检查权限"); }
    }
    public void close(String reason) {
        Job job;
        synchronized (this) {
            if (closed) return;
            closed = true; ready = false; failure = reason; job = active; active = null;
        }
        if (job != null) job.finish(-1);
        process.destroy();
        try { commands.close(); } catch (IOException ignored) {}
    }
    @Override public void close() { close("Root 连接已关闭，开始投屏时将重新检查权限"); }

    private final class Job extends Process {
        final String endMarker = marker();
        final PipedInputStream input = new PipedInputStream(16384);
        final PipedOutputStream output;
        final CountDownLatch ended = new CountDownLatch(1);
        private volatile int exit;
        Job() throws IOException { output = new PipedOutputStream(input); }
        synchronized void finish(int code) {
            if (ended.getCount() == 0) return;
            exit = code;
            try { output.close(); } catch (IOException ignored) {}
            ended.countDown();
        }
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException("Daemon stdin is not exposed"); }
        @Override public int waitFor() throws InterruptedException { ended.await(); return exit; }
        @Override public int exitValue() { if (ended.getCount() != 0) throw new IllegalThreadStateException(); return exit; }
        @Override public void destroy() {
            synchronized (AuthorizedShell.this) {
                if (active != this || ended.getCount() == 0) return;
                AuthorizedShell.this.close("Root 辅助进程已中断，开始投屏时将重新检查权限");
            }
        }
    }
}
