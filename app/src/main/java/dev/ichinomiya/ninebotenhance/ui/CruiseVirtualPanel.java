package dev.ichinomiya.ninebotenhance.ui;

import dev.ichinomiya.ninebotenhance.client.FrameClient;
import dev.ichinomiya.ninebotenhance.core.DisplaySettings;
import dev.ichinomiya.ninebotenhance.core.InlineBounds;

import android.app.Activity;
import android.graphics.*;
import android.view.*;
import android.widget.*;

/** Replaces the visible cruise-map region, leaving the original bound map object and cast session alive. */
public final class CruiseVirtualPanel {
    private final Activity activity;
    private final String request;
    private final FrameClient frames;
    private final FrameLayout host;
    private final View map;
    private final LinearLayout panel;
    private final PreviewPicture picture;
    private final PreviewToolbar toolbar;
    private PreviewSystemBars systemBars;
    private final AppRecoveryOverlay recovery;
    private final DisplaySettings settings;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutObserver = this::align;
    private boolean closed;

    public static CruiseVirtualPanel mount(Activity activity, String request, FrameClient frames, Runnable end, Runnable diagnostic) {
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) throw new IllegalStateException("巡航页面容器不兼容，请查看日志");
        View map = findMap(content, new int[]{512});
        if (map == null || !map.isAttachedToWindow() || map.getWidth() < 1 || map.getHeight() < 1) return null;
        return new CruiseVirtualPanel(activity, request, frames, (FrameLayout)content, map, end, diagnostic);
    }
    private static View findMap(View view, int[] budget) {
        if (--budget[0] < 0) return null;
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().equals("cn.ninebot.library.navi.NinebotNaviView")) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount() && budget[0] > 0; i++) {
                View found = findMap(group.getChildAt(i), budget); if (found != null) return found;
            }
        }
        return null;
    }
    private CruiseVirtualPanel(Activity activity, String request, FrameClient frames, FrameLayout host, View map,
                               Runnable end, Runnable diagnostic) {
        this.activity = activity; this.request = request; this.frames = frames; this.host = host; this.map = map;
        settings = frames.displaySettings();
        panel = new LinearLayout(activity); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setTag(MirrorUi.PREVIEW_TAG); panel.setForceDarkAllowed(false);
        MirrorUi theme = new MirrorUi(activity, host);
        panel.setBackgroundColor(theme.surface); panel.setClickable(true); panel.setKeepScreenOn(true);
        panel.setElevation(dp(32));
        picture = new PreviewPicture(activity, request, frames);
        // Mounted inside the host's page, the panel cannot turn the window: "横屏" turns the picture itself.
        toolbar = new PreviewToolbar(activity, theme, "仪表虚拟屏", picture, diagnostic, picture::rotated, picture::toggleRotation, frames::dashboardDark, frames::toggleDashboardTheme,
                frames::calibrationAction, () -> frames.toggleTouchCalibration(request), "结束", end);
        panel.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout screen = new FrameLayout(activity);
        screen.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        recovery = new AppRecoveryOverlay(activity, frames, request, picture::cancelTouch);
        screen.addView(recovery, new FrameLayout.LayoutParams(-1, -1));
        panel.addView(screen, new LinearLayout.LayoutParams(-1, 0, 1));
        panel.setOnApplyWindowInsetsListener((v, insets) -> { applyInsets(); return insets; });
        host.addView(panel, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.LEFT));
        try {
            systemBars = new PreviewSystemBars(activity, activity.getWindow(), host, next -> {
                panel.setBackgroundColor(next.surface); toolbar.applyTheme(next);
            }, frames);
            host.getViewTreeObserver().addOnGlobalLayoutListener(layoutObserver);
            align(); panel.requestApplyInsets(); picture.resume();
            recovery.resume();
            frames.report("INLINE mounted " + map.getClass().getName() + " " + settings.label());
        } catch (RuntimeException e) { close(); throw e; }
    }
    public boolean owns(Activity owner) { return activity == owner; }
    public boolean attached() { return !closed && panel.isAttachedToWindow() && map.isAttachedToWindow(); }
    private void align() {
        if (closed || !map.isAttachedToWindow()) return;
        int[] origin = new int[2], position = new int[2]; host.getLocationInWindow(origin); map.getLocationInWindow(position);
        int[] bounds = InlineBounds.clip(position[0] - origin[0], position[1] - origin[1], map.getWidth(), map.getHeight(), host.getWidth(), host.getHeight());
        if (bounds == null) { panel.setVisibility(View.INVISIBLE); picture.cancelTouch(); return; }
        panel.setVisibility(View.VISIBLE);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)panel.getLayoutParams();
        int left = bounds[0] - host.getPaddingLeft(), top = bounds[1] - host.getPaddingTop();
        if (params.width != bounds[2] || params.height != bounds[3] || params.leftMargin != left || params.topMargin != top) {
            picture.cancelTouch(); params.width = bounds[2]; params.height = bounds[3]; params.leftMargin = left; params.topMargin = top;
            panel.setLayoutParams(params);
        }
        applyInsets();
    }
    private void applyInsets() {
        if (systemBars != null) systemBars.updateInsets();
        WindowInsets insets = host.getRootWindowInsets(); if (insets == null) return;
        picture.imeVisibility(insets.isVisible(WindowInsets.Type.ime()));
        Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
        int[] at = new int[2]; panel.getLocationInWindow(at); View decor = activity.getWindow().getDecorView();
        int left = Math.max(0, bars.left - at[0]), top = Math.max(0, bars.top - at[1]);
        int right = Math.max(0, at[0] + panel.getWidth() - (decor.getWidth() - bars.right));
        int bottom = Math.max(0, at[1] + panel.getHeight() - (decor.getHeight() - bars.bottom));
        if (panel.getPaddingLeft() != left || panel.getPaddingTop() != top || panel.getPaddingRight() != right || panel.getPaddingBottom() != bottom)
            panel.setPadding(left, top, right, bottom);
    }
    public void pause() { picture.pause(); recovery.pause(); }
    public void resume() { if (!closed) { if (systemBars != null) systemBars.resume(); recovery.resume(); picture.resume(); } }
    public void close() {
        if (closed) return;
        closed = true; pause();
        if (systemBars != null) { systemBars.close(); systemBars = null; }
        if (host.getViewTreeObserver().isAlive()) host.getViewTreeObserver().removeOnGlobalLayoutListener(layoutObserver);
        if (panel.getParent() == host) host.removeView(panel);
        frames.report("INLINE removed");
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
