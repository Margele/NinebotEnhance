package dev.ichinomiya.ninebotenhance.ui;

import dev.ichinomiya.ninebotenhance.client.FrameClient;
import dev.ichinomiya.ninebotenhance.core.AppRecoveryState;
import dev.ichinomiya.ninebotenhance.core.DisplaySettings;
import dev.ichinomiya.ninebotenhance.core.KeyboardPolicy;
import dev.ichinomiya.ninebotenhance.core.PreviewTransform;

import android.content.Context;
import android.graphics.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.Toast;

/** One phone-only rendering/input path shared by local and cruise previews. */
public final class PreviewPicture extends View {
    private final FrameClient frames;
    private final String request;
    private boolean touching, keyboard, paused, sawIme;
    private int connectionGeneration;
    private MotionEvent lastTouch;
    private boolean hudGesture;
    private BaseInputConnection activeConnection;
    private final java.util.Set<Integer> heldKeys = new java.util.HashSet<>();
    public Runnable onControlsChanged = () -> {};
    /** Whether the picture takes the "横屏" quarter turn itself; false where the whole window turns instead. */
    private final boolean turns;
    public PreviewPicture(Context context, String request, FrameClient frames) { this(context, request, frames, true); }
    public PreviewPicture(Context context, String request, FrameClient frames, boolean turns) {
        super(context); this.request = request; this.frames = frames; this.turns = turns;
        setContentDescription("虚拟屏画面，支持触摸操作");
        setOnGenericMotionListener((v, event) -> true);
    }
    public boolean rotated() { return turns && frames.previewRotated(request); }
    public boolean keyboardActive() { return keyboard; }
    public void toggleRotation() {
        if (paused || !turns) return;
        cancelTouch(); frames.setPreviewRotated(request, !rotated()); invalidate(); onControlsChanged.run();
    }
    public void back() { cancelTouch(); if (frames.readyFor(request)) frames.back(request); }
    public void toggleKeyboard() {
        if (paused || frames.debugModeEnabled() || !frames.readyFor(request)) return;
        if (keyboard) { hideKeyboard(); return; }
        cancelTouch(); keyboard = true; sawIme = false; connectionGeneration++;
        setFocusableInTouchMode(true); setFocusable(true); requestFocus();
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) { imm.restartInput(this); post(() -> {
            if (keyboard && !paused) imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
        }); }
        Toast.makeText(getContext(), "先点击虚拟屏中的输入框，再输入文字", Toast.LENGTH_SHORT).show();
        onControlsChanged.run();
    }
    public void imeVisibility(boolean visible) {
        if (!keyboard) return;
        if (visible) sawIme = true;
        else if (sawIme) hideKeyboard();
    }
    public void hideKeyboard() {
        if (!keyboard) return;
        if (!paused && activeConnection != null) activeConnection.finishComposingText();
        activeConnection = null;
        for (int code : heldKeys) frames.typingKey(request, new KeyEvent(KeyEvent.ACTION_UP, code)); heldKeys.clear();
        keyboard = false; connectionGeneration++; sawIme = false;
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
        clearFocus(); setFocusable(false); setFocusableInTouchMode(false); onControlsChanged.run();
    }
    public void pause() { paused = true; cancelTouch(); hideKeyboard(); frames.detachInline(this); }
    public void resume() { paused = false; frames.attachInline(request, this); }
    @Override protected void onDetachedFromWindow() { pause(); super.onDetachedFromWindow(); }
    @Override protected void onDraw(Canvas canvas) { frames.drawInline(request, canvas, getWidth(), getHeight(), rotated()); }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { cancelTouch(); }
    @Override public boolean onCheckIsTextEditor() { return keyboard && !paused; }
    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        if (!keyboard || paused) return null;
        info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_ACTION_NONE;
        info.packageName = getContext().getPackageName(); info.initialSelStart = info.initialSelEnd = 0;
        final int generation = ++connectionGeneration;
        BaseInputConnection connection = new BaseInputConnection(this, true) {
            private boolean connectionClosed;
            private boolean valid() { return !connectionClosed && keyboard && !paused && !frames.debugModeEnabled() && generation == connectionGeneration && frames.readyFor(request); }
            private void clearDraft() { Editable e = getEditable(); e.clear(); removeComposingSpans(e); Selection.setSelection(e, 0); }
            private boolean commitDraft() {
                if (!valid()) return false;
                String value = getEditable().toString(); if (!value.isEmpty()) frames.text(request, value);
                clearDraft(); return true;
            }
            @Override public boolean setComposingText(CharSequence text, int position) {
                if (!valid() || text == null || text.length() > 2048) return false;
                return super.setComposingText(text, position); // Keep candidate composition on the phone until committed.
            }
            @Override public boolean commitText(CharSequence text, int position) {
                if (!valid() || text == null || text.length() > 2048) return false;
                frames.text(request, text.toString()); clearDraft(); return true;
            }
            @Override public boolean finishComposingText() { return commitDraft(); }
            @Override public boolean deleteSurroundingText(int before, int after) {
                if (!valid()) return false;
                try { KeyboardPolicy.deletion(before, after); } catch (IllegalArgumentException e) { return false; }
                if (getEditable().length() > 0) return deleteDraft(before, after, false);
                frames.deleteText(request, before, after); return true;
            }
            @Override public boolean deleteSurroundingTextInCodePoints(int before, int after) {
                if (!valid()) return false;
                try { KeyboardPolicy.deletion(before, after); } catch (IllegalArgumentException e) { return false; }
                if (getEditable().length() > 0) return deleteDraft(before, after, true);
                frames.deleteText(request, before, after); return true;
            }
            @Override public boolean sendKeyEvent(KeyEvent event) {
                if (!valid() || !KeyboardPolicy.key(event.getKeyCode())) return false;
                if (event.getKeyCode() == KeyEvent.KEYCODE_DEL && getEditable().length() > 0) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) deleteDraft(1, 0, true);
                    return true;
                }
                commitDraft(); forwardKey(event); return true;
            }
            @Override public boolean performEditorAction(int action) {
                if (!commitDraft()) return false;
                frames.typingKey(request, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                frames.typingKey(request, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)); return true;
            }
            private boolean deleteDraft(int before, int after, boolean codePoints) {
                Editable value = getEditable(); int a = Selection.getSelectionStart(value), b = Selection.getSelectionEnd(value);
                if (a < 0 || b < 0) a = b = value.length();
                int left = Math.min(a, b), right = Math.max(a, b);
                int from = codePoints ? Character.offsetByCodePoints(value, left, -Math.min(before, Character.codePointCount(value, 0, left))) : Math.max(0, left - before);
                int to = codePoints ? Character.offsetByCodePoints(value, right, Math.min(after, Character.codePointCount(value, right, value.length()))) : Math.min(value.length(), right + after);
                value.delete(from, to); Selection.setSelection(value, from); return true;
            }
            @Override public void closeConnection() { connectionClosed = true; clearDraft(); super.closeConnection(); }
        };
        activeConnection = connection; return connection;
    }
    private boolean editingKey(KeyEvent event) {
        if (!keyboard || paused || !KeyboardPolicy.key(event.getKeyCode()) || !frames.readyFor(request)) return false;
        forwardKey(event); return true;
    }
    private void forwardKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) heldKeys.add(event.getKeyCode());
        else if (event.getAction() == KeyEvent.ACTION_UP) heldKeys.remove(event.getKeyCode());
        frames.typingKey(request, event);
    }
    @Override public boolean onKeyDown(int code, KeyEvent event) { return editingKey(event) || super.onKeyDown(code, event); }
    @Override public boolean onKeyUp(int code, KeyEvent event) { return editingKey(event) || super.onKeyUp(code, event); }
    @Override public boolean onTouchEvent(MotionEvent original) {
        if (paused || frames.debugModeEnabled() || !frames.readyFor(request) || getWidth() < 1 || getHeight() < 1) {
            cancelTouch(); return true;
        }
        DisplaySettings settings = frames.displaySettings();
        PreviewTransform transform = new PreviewTransform(settings,getWidth(),getHeight(),rotated());
        float[] m=transform.frameInput;float x=m[0]*original.getX()+m[1]*original.getY()+m[2],y=m[3]*original.getX()+m[4]*original.getY()+m[5];
        if(original.getActionMasked()==MotionEvent.ACTION_DOWN){cancelTouch();if(transform.fit.contains(original.getX(),original.getY()))hudGesture=frames.hudTouch(request,x,y)!=null;}
        if(hudGesture){
            if(original.getPointerCount()>1||original.getActionMasked()==MotionEvent.ACTION_CANCEL){cancelTouch();return true;}
            if(original.getActionMasked()==MotionEvent.ACTION_UP)cancelTouch();
            else getParent().requestDisallowInterceptTouchEvent(true);return true;
        }
        if(frames.appRecoveryFor(request)!=AppRecoveryState.HIDDEN){cancelTouch();return true;}
        if (original.getActionMasked() == MotionEvent.ACTION_DOWN) {
            cancelTouch(); touching = transform.contains(original.getX(), original.getY());
            if (touching) getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (!touching) return true;
        if (original.getActionMasked() == MotionEvent.ACTION_CANCEL) { cancelTouch(); return true; }
        // Background and reserved right column must never inject touches into the app.
        for (int i = 0; i < original.getPointerCount(); i++) {
            if (!transform.contains(original.getX(i), original.getY(i))) { cancelTouch(); return true; }
            for (int h = 0; h < original.getHistorySize(); h++) {
                if (!transform.contains(original.getHistoricalX(i, h), original.getHistoricalY(i, h))) { cancelTouch(); return true; }
            }
        }
        MotionEvent event = MotionEvent.obtain(original); Matrix matrix = new Matrix(); matrix.setValues(transform.input); event.transform(matrix);
        if (lastTouch != null) lastTouch.recycle(); lastTouch = MotionEvent.obtain(event); frames.input(request, event);
        if (original.getActionMasked() == MotionEvent.ACTION_UP || original.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            touching = false; lastTouch.recycle(); lastTouch = null; getParent().requestDisallowInterceptTouchEvent(false);
        }
        return true;
    }
    public void cancelTouch() {
        hudGesture=false;
        if (touching && lastTouch != null) {
            MotionEvent cancel = MotionEvent.obtain(lastTouch); cancel.setAction(MotionEvent.ACTION_CANCEL); frames.input(request, cancel);
        }
        touching = false; if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        if (lastTouch != null) { lastTouch.recycle(); lastTouch = null; }
    }
}
