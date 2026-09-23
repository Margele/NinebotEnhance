package dev.ichinomiya.ninebotenhance.ui;

import dev.ichinomiya.ninebotenhance.client.FrameClient;

import android.app.*;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.view.*;
import android.widget.*;

/** Phone-only window hosted by the current Ninebot Activity. Does not open a cruise or a new Activity. */
public final class LocalVirtualDisplayDialog {
    private final Activity activity;
    private final String request;
    private final FrameClient frames;
    private final Dialog dialog;
    private final PreviewPicture picture;
    private final TextView status;
    private final AppRecoveryOverlay recovery;
    private final LinearLayout root;
    private final PreviewToolbar toolbar;
    private PreviewSystemBars systemBars;
    private boolean closed;

    public LocalVirtualDisplayDialog(Activity activity, String request, FrameClient frames, Runnable end, Runnable diagnostics) {
        this.activity = activity; this.request = request; this.frames = frames;
        MirrorUi theme = new MirrorUi(activity, activity.findViewById(android.R.id.content));
        dialog = new Dialog(activity, theme.dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(false);
        root = new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL);
        root.setTag(MirrorUi.PREVIEW_TAG); root.setForceDarkAllowed(false);
        root.setBackgroundColor(theme.surface);
        picture = new PreviewPicture(activity, request, frames);
        toolbar = new PreviewToolbar(activity, theme, "虚拟屏预览", picture, end, diagnostics, frames::simulateNotification, frames::dashboardDark, frames::toggleDashboardTheme,
                () -> frames.touchBound() ? (frames.calibrating() ? "取消校准" : "校准") : null, () -> frames.toggleTouchCalibration(request));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout screen = new FrameLayout(activity);
        screen.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(activity); status.setText("正在创建虚拟屏并打开所选应用…\n无需连接车辆");
        status.setTextColor(Color.WHITE); status.setGravity(Gravity.CENTER); status.setPadding(dp(16), dp(16), dp(16), dp(16));
        screen.addView(status, new FrameLayout.LayoutParams(-1, -1));
        recovery = new AppRecoveryOverlay(activity, frames, request, picture::cancelTouch);
        screen.addView(recovery, new FrameLayout.LayoutParams(-1, -1));
        root.addView(screen, new LinearLayout.LayoutParams(-1, 0, 1));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            picture.imeVisibility(insets.isVisible(WindowInsets.Type.ime()));
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            if (systemBars != null) systemBars.updateInsets(); return insets;
        });
        dialog.setContentView(root);
        // Close/back means stop; programmatic dismissal during configuration rebuild is silent.
        dialog.setOnDismissListener(ignored -> { closeBars(); if (!closed) end.run(); });
    }
    public void show() {
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.getDecorView().setForceDarkAllowed(false);
            window.setDecorFitsSystemWindows(false);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            window.setLayout(-1, -1); window.getDecorView().requestApplyInsets();
            systemBars = new PreviewSystemBars(activity, window, activity.findViewById(android.R.id.content), theme -> {
                root.setBackgroundColor(theme.surface); toolbar.applyTheme(theme);
                window.setBackgroundDrawable(new ColorDrawable(theme.surface));
            }, frames);
        }
        resume(); frames.report("LOCAL preview shown");
    }
    public boolean owns(Activity owner) { return activity == owner; }
    public void ready() { if (!closed) { status.setVisibility(View.GONE); picture.invalidate(); } }
    public void pause() { picture.pause(); recovery.pause(); }
    public void resume() { if (!closed) { if (systemBars != null) systemBars.resume(); recovery.resume(); picture.resume(); } }
    public void close() {
        if (closed) return;
        closed = true; pause(); closeBars(); dialog.setOnDismissListener(null); dialog.dismiss();
    }
    private void closeBars() { if (systemBars != null) { systemBars.close(); systemBars = null; } }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
