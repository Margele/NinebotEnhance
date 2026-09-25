package dev.ichinomiya.ninebotenhance.display;

import dev.ichinomiya.ninebotenhance.core.KeyboardPolicy;
import dev.ichinomiya.ninebotenhance.ipc.Ipc;

import android.content.*;
import android.os.*;
import android.view.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;

/** Main-phone IME proxy transport. Only injects editing keys into this session's owned display. */
public final class RootKeyboard {
    interface Sender { void send(KeyEvent event) throws Exception; }
    private final Display display;
    private final int id;
    private final Handler main;
    private final Sender sender;
    private final Consumer<String> log;
    private final String clipLabel = "NinebotMirrorInput:" + UUID.randomUUID();
    private final Map<Integer, Long> held = new HashMap<>();
    private final Method unique;
    private final String identity;
    private Object clipboard, wm;
    private Method getClip, setClip, clearClip, setIme;
    /** Android 13's clipboard calls carry no attribution tag or device id. */
    private boolean clipWide;
    private int previousIme;
    private boolean changedIme, pendingClip, closed;
    private ClipData previousClip;
    private String pastedText;
    public RootKeyboard(Display display, Handler main, Sender sender, Consumer<String> log) throws Exception {
        this.display = display; id = display.getDisplayId(); this.main = main; this.sender = sender; this.log = log;
        unique = Display.class.getMethod("getUniqueId"); identity = (String)unique.invoke(display); requireOwned();
    }
    public synchronized void configure() {
        try {
            requireOwned(); wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
            Class<?> api = Class.forName("android.view.IWindowManager");
            previousIme = (int)api.getMethod("getDisplayImePolicy", int.class).invoke(wm, id);
            if (previousIme < 0 || previousIme > 2) throw new IllegalStateException("副屏输入法策略不可用");
            setIme = api.getMethod("setDisplayImePolicy", int.class, int.class);
            setIme.invoke(wm, id, 2); changedIme = true; // DISPLAY_IME_POLICY_HIDE: the explicit proxy editor lives on the phone.
            log.accept("IME proxy ready id=" + id + " displayImePolicy=hide");
        } catch (Exception e) { log.accept("IME display policy unavailable: " + Ipc.error(e)); }
    }
    private void requireOwned() throws Exception {
        if (closed || id <= 0 || !display.isValid() || display.getDisplayId() != id || identity == null || !identity.equals(unique.invoke(display)))
            throw new IllegalStateException("虚拟屏输入会话已关闭");
    }
    public synchronized void key(int code, int action, int meta) throws Exception {
        if (!KeyboardPolicy.key(code) || action < 0 || action > 1) throw new IllegalArgumentException("不支持的输入按键");
        requireOwned(); injectKey(code, action, meta);
    }
    private void injectKey(int code, int action, int meta) throws Exception {
        long now = SystemClock.uptimeMillis();
        long down = action == KeyEvent.ACTION_DOWN ? held.computeIfAbsent(code, ignored -> now) : held.getOrDefault(code, now);
        meta &= KeyEvent.META_SHIFT_MASK | KeyEvent.META_ALT_MASK | KeyEvent.META_CTRL_MASK | KeyEvent.META_CAPS_LOCK_ON;
        sender.send(new KeyEvent(down, now, action, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
        if (action == KeyEvent.ACTION_UP) held.remove(code);
    }
    private void press(int code) throws Exception { injectKey(code, 0, 0); injectKey(code, 1, 0); }
    public synchronized void delete(int before, int after) throws Exception {
        KeyboardPolicy.deletion(before, after); requireOwned();
        for (int i = 0; i < before; i++) press(KeyEvent.KEYCODE_DEL);
        for (int i = 0; i < after; i++) press(KeyEvent.KEYCODE_FORWARD_DEL);
    }
    public synchronized void text(String text) throws Exception {
        KeyboardPolicy.text(text); requireOwned(); if (text.isEmpty()) return;
        KeyEvent[] keys = text.length() <= 32 ? KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray()) : null;
        if (keys != null) { for (KeyEvent event : keys) key(event.getKeyCode(), event.getAction(), event.getMetaState()); return; }
        // CJK/emoji cannot be synthesized by the virtual hardware key map. Paste a complete IME
        // commit, then restore the clipboard only while our unique clip is still current.
        ensureClipboard(); ClipData current = readClip();
        if (!ours(current)) {
            pendingClip = false; previousClip = null; pastedText = null;
            if (current != null) for (int i = 0; i < current.getItemCount(); i++) {
                if (current.getItemAt(i).getUri() != null || current.getItemAt(i).getIntent() != null)
                    throw new IllegalStateException("剪贴板含文件，无法安全恢复；请先复制一段文字再输入中文");
            }
            previousClip = current;
        }
        ClipData clip = ClipData.newPlainText(clipLabel, text); PersistableBundle extras = new PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true); clip.getDescription().setExtras(extras);
        writeClip(clip); pastedText = text; pendingClip = true;
        main.removeCallbacks(restoreClip);
        try {
            requireOwned(); press(KeyEvent.KEYCODE_PASTE);
            // Ordered Binder control queue plus WAIT_FOR_FINISH injection. Allow asynchronous
            // editors a short read window before a subsequent commit replaces the clip.
            Thread.sleep(150);
        } finally { main.postDelayed(restoreClip, 1200); }
    }
    private void ensureClipboard() throws Exception {
        if (clipboard != null) return;
        IBinder binder = (IBinder)Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "clipboard");
        Class<?> api = Class.forName("android.content.IClipboard");
        Object service = Class.forName("android.content.IClipboard$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        try {
            getClip = api.getMethod("getPrimaryClip", String.class, String.class, int.class, int.class);
            setClip = api.getMethod("setPrimaryClip", ClipData.class, String.class, String.class, int.class, int.class);
            clearClip = api.getMethod("clearPrimaryClip", String.class, String.class, int.class, int.class);
            clipWide = true;
        } catch (NoSuchMethodException e) {
            getClip = api.getMethod("getPrimaryClip", String.class, int.class);
            setClip = api.getMethod("setPrimaryClip", ClipData.class, String.class, int.class);
            clearClip = api.getMethod("clearPrimaryClip", String.class, int.class);
            clipWide = false;
        }
        if (service == null) throw new IllegalStateException("文字输入服务不可用"); clipboard = service;
    }
    private ClipData readClip() throws Exception {
        return (ClipData)(clipWide ? getClip.invoke(clipboard, "com.android.shell", null, 0, 0)
                : getClip.invoke(clipboard, "com.android.shell", 0));
    }
    private void writeClip(ClipData clip) throws Exception {
        if (clipWide) setClip.invoke(clipboard, clip, "com.android.shell", null, 0, 0);
        else setClip.invoke(clipboard, clip, "com.android.shell", 0);
    }
    private void dropClip() throws Exception {
        if (clipWide) clearClip.invoke(clipboard, "com.android.shell", null, 0, 0);
        else clearClip.invoke(clipboard, "com.android.shell", 0);
    }
    private boolean ours(ClipData clip) {
        return pendingClip && clip != null && clipLabel.equals(String.valueOf(clip.getDescription().getLabel())) && clip.getItemCount() == 1
                && pastedText != null && pastedText.contentEquals(clip.getItemAt(0).getText() == null ? "" : clip.getItemAt(0).getText());
    }
    private final Runnable restoreClip = this::restoreClipboard;
    private synchronized void restoreClipboard() {
        if (!pendingClip) return;
        try {
            ClipData current = readClip();
            if (ours(current)) {
                if (previousClip == null) dropClip();
                else writeClip(previousClip);
            }
        } catch (Exception e) { log.accept("IME clipboard restore failed"); }
        finally { pendingClip = false; previousClip = null; pastedText = null; }
    }
    public synchronized void close() {
        if (closed) return;
        main.removeCallbacks(restoreClip); restoreClipboard();
        try {
            requireOwned();
            for (int code : new ArrayList<>(held.keySet())) try { injectKey(code, 1, 0); } catch (Exception ignored) {}
            if (changedIme) setIme.invoke(wm, id, previousIme);
        } catch (Exception e) { log.accept("IME cleanup unavailable"); }
        finally { closed = true; held.clear(); }
    }
}
