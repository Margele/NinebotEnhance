package dev.ichinomiya.ninebotenhance.display;

import dev.ichinomiya.ninebotenhance.core.Streams;

import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.view.InputDevice;
import android.view.MotionEvent;
import dev.ichinomiya.ninebotenhance.core.TouchPanel;
import dev.ichinomiya.ninebotenhance.core.TouchPanelMapping;
import dev.ichinomiya.ninebotenhance.core.TouchPanelProbe;
import dev.ichinomiya.ninebotenhance.core.TouchPanelTracker;
import dev.ichinomiya.ninebotenhance.ipc.Ipc;
import java.io.FileDescriptor;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Reads the bound external touch panel straight from its evdev node and turns every report into a touch on the virtual display.
 * The panel covers the whole dashboard, so its axes map onto the frame (through the calibration when one exists, else the mounting
 * rotation); the application picture is the frame's left part above the profile's bottom strip, contacts that first touch outside
 * it are ignored. The node
 * is grabbed exclusively (EVIOCGRAB) for the life of the session so the phone's own screen never sees the panel; closing the
 * descriptor releases it. The shell uid is in the {@code input} group, so this works under Shizuku as well as Root. The panel is
 * looked up again whenever it disappears, so unplugging and replugging it during a cast just resumes.
 */
final class RootTouchPanel {
    interface Sink { void touch(MotionEvent event) throws Exception; }
    private static final int EVIOCGRAB = 0x40044590, EVENT_SIZE = 24, RESCAN_MS = 2000, POLL_MS = 500, MARKS_INTERVAL_MS = 40;
    private volatile TouchPanel panel;
    private final int frameWidth, frameHeight, pictureWidth, pictureHeight, pictureTop;
    private final Sink sink;
    private final Consumer<float[]> marks, samples;
    private final Consumer<Boolean> presence;
    private final Consumer<String> log;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean calibrating;
    private long lastSinkError, lastMarks;
    /**
     * {@code marks}, when not null, receives the contacts still down after each report (x / y pairs in frame pixels), moves rate
     * limited; {@code samples} receives the raw normalized position of each tap while calibrating, when nothing is injected;
     * {@code presence} learns when the panel's node is open for the session and when it is gone again.
     */
    RootTouchPanel(TouchPanel panel, int frameWidth, int frameHeight, int pictureWidth, int pictureHeight, int pictureTop, Sink sink,
                   Consumer<float[]> marks, Consumer<float[]> samples, Consumer<Boolean> presence, Consumer<String> log) {
        this.panel = panel; this.frameWidth = frameWidth; this.frameHeight = frameHeight; this.pictureWidth = pictureWidth; this.pictureHeight = pictureHeight; this.pictureTop = pictureTop;
        this.sink = sink; this.marks = marks; this.samples = samples; this.presence = presence; this.log = log;
    }
    TouchPanel panel() { return panel; }
    void setPanel(TouchPanel value) { panel = value; calibrating = false; log.accept("TOUCH mapping " + mapping(value)); }
    void setCalibrating(boolean value) { calibrating = value; log.accept("TOUCH calibration " + (value ? "begin" : "end")); }
    void start() { Thread thread = new Thread(this::run, "Mirror-TouchPanel"); thread.setDaemon(true); thread.start(); }
    void close() { closed.set(true); }
    private static String mapping(TouchPanel panel) {
        return panel.calibrated() ? "calibrated " + panel.calibration().encode() : "rotation=" + panel.rotation() * 90;
    }
    private void run() {
        boolean waiting = false;
        while (!closed.get()) {
            TouchPanelProbe.Device device = null;
            try { device = TouchPanelProbe.find(TouchPanelProbe.parse(listing()), panel); }
            catch (Exception e) { log.accept("TOUCH probe failed: " + Ipc.error(e)); }
            if (device == null) {
                if (!waiting) { waiting = true; log.accept("TOUCH " + panel.label() + " not present, waiting"); }
                sleep(RESCAN_MS); continue;
            }
            waiting = false;
            try { serve(device); }
            catch (Exception e) { if (!closed.get()) log.accept("TOUCH " + device.path() + " lost: " + Ipc.error(e)); }
            finally { presence.accept(false); }
            if (!closed.get()) sleep(RESCAN_MS);
        }
    }
    private static String listing() throws Exception {
        Process process = new ProcessBuilder("/system/bin/getevent", "-pi").redirectErrorStream(true).start();
        try (InputStream input = process.getInputStream()) {
            byte[] data = Streams.readAll(input, 4 * 1024 * 1024);
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("getevent did not finish");
            return new String(data, StandardCharsets.UTF_8);
        } finally { process.destroy(); }
    }
    /** Raw panel position to frame pixels. */
    private float[] frame(int rawX, int rawY, TouchPanelProbe.Device device) {
        TouchPanel current = panel;
        if (current.calibrated()) {
            float[] n = current.calibration().apply(device.x().normalize(rawX), device.y().normalize(rawY));
            return new float[]{ n[0] * frameWidth, n[1] * frameHeight };
        }
        return TouchPanelMapping.map(rawX, rawY, device.x(), device.y(), current.rotation(), frameWidth, frameHeight);
    }
    private boolean insidePicture(float[] point) {
        return point[0] >= 0 && point[0] < pictureWidth && point[1] >= pictureTop && point[1] < pictureTop + pictureHeight;
    }
    private void serve(TouchPanelProbe.Device device) throws Exception {
        FileDescriptor fd = Os.open(device.path(), OsConstants.O_RDONLY | OsConstants.O_NONBLOCK, 0);
        TouchPanelTracker tracker = new TouchPanelTracker(device.slots());
        tracker.setGate((x, y) -> calibrating || insidePicture(frame(x, y, device)));
        try {
            String grab = grab(fd);
            log.accept("TOUCH bound " + device.describe() + " frame=" + frameWidth + "x" + frameHeight + " picture=" + pictureWidth + "x" + pictureHeight
                    + " " + mapping(panel) + " grab=" + grab);
            presence.accept(true);
            StructPollfd[] fds = { new StructPollfd() }; fds[0].fd = fd; fds[0].events = (short) OsConstants.POLLIN;
            byte[] buffer = new byte[EVENT_SIZE * 64]; long downTime = 0;
            while (!closed.get()) {
                fds[0].revents = 0;
                if (Os.poll(fds, POLL_MS) <= 0) continue;
                if ((fds[0].revents & (OsConstants.POLLERR | OsConstants.POLLHUP | OsConstants.POLLNVAL)) != 0) throw new IllegalStateException("device removed");
                int count;
                try { count = Os.read(fd, buffer, 0, buffer.length); }
                catch (ErrnoException e) { if (e.errno == OsConstants.EAGAIN) continue; throw e; }
                for (int i = 0; i + EVENT_SIZE <= count; i += EVENT_SIZE) {
                    int type = (buffer[i + 16] & 0xff) | ((buffer[i + 17] & 0xff) << 8);
                    int code = (buffer[i + 18] & 0xff) | ((buffer[i + 19] & 0xff) << 8);
                    int value = (buffer[i + 20] & 0xff) | ((buffer[i + 21] & 0xff) << 8) | ((buffer[i + 22] & 0xff) << 16) | ((buffer[i + 23] & 0xff) << 24);
                    for (TouchPanelTracker.Report report : tracker.event(type, code, value)) downTime = emit(report, device, downTime);
                }
            }
        } finally {
            TouchPanelTracker.Report cancel = tracker.reset();
            if (cancel != null) try { emit(cancel, device, SystemClock.uptimeMillis()); } catch (RuntimeException ignored) {}
            try { Os.close(fd); } catch (ErrnoException ignored) {}
        }
    }
    /** Exclusive access through libcore's ioctl; a refusal is logged and the panel still works, only shared with the phone screen. */
    private String grab(FileDescriptor fd) {
        try {
            try {
                Method two = Os.class.getMethod("ioctlInt", FileDescriptor.class, int.class);
                two.invoke(null, fd, EVIOCGRAB);
            } catch (NoSuchMethodException none) {
                Class<?> ref = Class.forName("android.system.Int32Ref");
                Method three = Os.class.getMethod("ioctlInt", FileDescriptor.class, int.class, ref);
                three.invoke(null, fd, EVIOCGRAB, ref.getConstructor(int.class).newInstance(1));
            }
            return "exclusive";
        } catch (Exception e) { log.accept("TOUCH grab refused, the phone screen also receives this panel: " + Ipc.error(e)); return "shared"; }
    }
    private long emit(TouchPanelTracker.Report report, TouchPanelProbe.Device device, long downTime) {
        long now = SystemClock.uptimeMillis();
        List<TouchPanelTracker.Contact> contacts = report.contacts();
        int count = contacts.size(), index = 0;
        if (count == 0) return downTime;
        float[][] points = new float[count][];
        for (int i = 0; i < count; i++) {
            TouchPanelTracker.Contact contact = contacts.get(i);
            points[i] = frame(contact.x(), contact.y(), device);
            if (contact.tracking() == report.tracking()) index = i;
        }
        if (calibrating) {
            // Only the tap's first position counts; nothing reaches the display while the targets are being tapped.
            if (report.action() == TouchPanelTracker.DOWN && samples != null && count == 1)
                samples.accept(new float[]{ device.x().normalize(contacts.get(0).x()), device.y().normalize(contacts.get(0).y()) });
            return downTime;
        }
        if (report.action() == TouchPanelTracker.DOWN) downTime = now;
        float top = pictureTop;
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            properties[i] = new MotionEvent.PointerProperties(); properties[i].id = contacts.get(i).pointer(); properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            // A drag that leaves the picture keeps going along its edge.
            coords[i].x = Math.max(0f, Math.min(pictureWidth - 1f, points[i][0])); coords[i].y = Math.max(0f, Math.min(pictureHeight - 1f, points[i][1] - top));
            coords[i].pressure = 1f; coords[i].size = 1f;
        }
        int action = report.action() | (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        MotionEvent event = MotionEvent.obtain(downTime == 0 ? now : downTime, now, action, count, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try { sink.touch(event); }
        catch (Exception e) {
            if (SystemClock.elapsedRealtime() - lastSinkError > 3000) { lastSinkError = SystemClock.elapsedRealtime(); log.accept("TOUCH inject failed: " + Ipc.error(e)); }
        } finally { event.recycle(); }
        if (marks != null && (report.action() != TouchPanelTracker.MOVE || now - lastMarks >= MARKS_INTERVAL_MS)) {
            lastMarks = now;
            boolean ending = report.action() == TouchPanelTracker.UP || report.action() == TouchPanelTracker.CANCEL;
            boolean lifting = report.action() == TouchPanelTracker.POINTER_UP;
            float[] down = new float[ending ? 0 : (count - (lifting ? 1 : 0)) * 2]; int at = 0;
            if (!ending) for (int i = 0; i < count; i++) {
                if (lifting && i == index) continue;
                down[at++] = Math.max(0f, Math.min(frameWidth, points[i][0])); down[at++] = Math.max(0f, Math.min(frameHeight, points[i][1]));
            }
            marks.accept(down);
        }
        return downTime;
    }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
}
