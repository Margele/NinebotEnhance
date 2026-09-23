package dev.ichinomiya.ninebotenhance.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import java.util.function.Supplier;

/** Shared phone preview controls, with a second row when the labelled actions cannot fit beside the title. */
public final class PreviewToolbar extends LinearLayout {
    private static final long REFRESH_MS = 1000;
    private final TextView title;
    private final LinearLayout actions;
    private MirrorUi theme;
    private final PreviewPicture picture;
    private final Button back, rotate, keyboard, simulate, calibrate, stop;
    private Button dashboard;
    private final Supplier<String> calibration;
    private boolean configured, wasCompact, wasCalibrate;
    private final Runnable refresher = new Runnable() { @Override public void run() { if (isAttachedToWindow()) { refresh(); postDelayed(this, REFRESH_MS); } } };
    /**
     * {@code calibration} names the touch panel calibration action ("校准" / "取消校准") or returns null while no panel is bound;
     * the button follows it and is left out entirely when the supplier is null.
     */
    public PreviewToolbar(Context context, MirrorUi theme, String label, PreviewPicture picture, Runnable end, Runnable diagnostics, Runnable simulateNotification,
                          java.util.function.BooleanSupplier dashboardDark, Runnable toggleDashboardTheme, Supplier<String> calibration, Runnable calibrateAction) {
        super(context); this.theme = theme; this.picture = picture; this.calibration = calibration;
        setGravity(Gravity.CENTER_VERTICAL); setBackgroundColor(theme.surface);
        setPadding(dp(10), dp(4), dp(10), dp(4));
        title = new TextView(context); title.setText(label); title.setTextSize(14); title.setTextColor(theme.text);
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER_VERTICAL); title.setOnLongClickListener(v -> { diagnostics.run(); return true; });
        addView(title);
        actions = new LinearLayout(context); actions.setGravity(Gravity.CENTER_VERTICAL); addView(actions);
        back = action("返回", "back", picture::back);
        rotate = action("横屏", "rotate", picture::toggleRotation);
        keyboard = action("输入法", "keyboard", picture::toggleKeyboard);
        dashboard = action(dashboardDark.getAsBoolean() ? "深色" : "浅色", null, () -> {
            toggleDashboardTheme.run(); dashboard.setText(dashboardDark.getAsBoolean() ? "深色" : "浅色"); dashboard.setContentDescription(dashboard.getText());
        });
        // Only the local simulation offers the synthetic notification card; a null action leaves the button out.
        simulate = simulateNotification == null ? null : action("通知", null, simulateNotification);
        calibrate = calibration == null ? null : action("校准", null, () -> { calibrateAction.run(); refresh(); });
        if (calibrate != null) calibrate.setVisibility(GONE);
        stop = action("结束", "stop", end);
        picture.onControlsChanged = () -> {
            rotate.setText(picture.rotated() ? "还原" : "横屏");
            keyboard.setText(picture.keyboardActive() ? "收起" : "输入法");
            style(rotate, "rotate", picture.rotated()); style(keyboard, "keyboard", picture.keyboardActive());
        };
        picture.onControlsChanged.run();
        refresh();
    }
    /** Follows the module's touch panel state: the button appears once a panel is bound and reads "取消校准" while tapping targets. */
    public void refresh() {
        if (calibrate == null) return;
        String label = calibration.get();
        boolean shown = label != null;
        if (shown && !label.contentEquals(calibrate.getText())) { calibrate.setText(label); calibrate.setContentDescription(label); }
        boolean active = shown && label.startsWith("取消");
        if (calibrate.isSelected() != active) style(calibrate, null, active);
        if ((calibrate.getVisibility() == VISIBLE) != shown) { calibrate.setVisibility(shown ? VISIBLE : GONE); requestLayout(); }
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); removeCallbacks(refresher); postDelayed(refresher, REFRESH_MS); }
    @Override protected void onDetachedFromWindow() { removeCallbacks(refresher); super.onDetachedFromWindow(); }
    public void applyTheme(MirrorUi next) {
        if (theme.dark == next.dark) return;
        theme = next; setBackgroundColor(theme.surface); title.setTextColor(theme.text);
        style(back, "back", false); style(rotate, "rotate", picture.rotated());
        style(keyboard, "keyboard", picture.keyboardActive()); style(dashboard, null, false); if (simulate != null) style(simulate, null, false);
        if (calibrate != null) style(calibrate, null, calibrate.isSelected()); style(stop, "stop", false);
    }
    private Button action(String label, String icon, Runnable action) {
        Button button = new Button(getContext()); button.setText(label); button.setContentDescription(label);
        style(button, icon, false); button.setOnClickListener(v -> action.run());
        LayoutParams params = new LayoutParams(0, dp(44), 1); if (actions.getChildCount() > 0) params.leftMargin = dp(6);
        actions.addView(button, params); return button;
    }
    private void style(Button button, String icon, boolean active) {
        theme.button(button, null); button.setTextSize(12); button.setPadding(dp(4), 0, dp(4), 0); button.setMaxLines(1);
        button.setAutoSizeTextTypeUniformWithConfiguration(9, 12, 1, TypedValue.COMPLEX_UNIT_SP);
        button.setTextColor(active ? theme.accent : theme.text);
        // Text only: with this many labelled actions there is no room for glyphs.
        button.setCompoundDrawablesRelative(null, null, null, null); button.setSelected(active);
        button.setContentDescription(button.getText());
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        boolean compact = MeasureSpec.getSize(widthSpec) < dp(640);
        boolean calibrateShown = calibrate != null && calibrate.getVisibility() == VISIBLE;
        if (!configured || wasCompact != compact || wasCalibrate != calibrateShown) {
            configured = true; wasCompact = compact; wasCalibrate = calibrateShown; setOrientation(compact ? VERTICAL : HORIZONTAL);
            title.setLayoutParams(compact ? new LayoutParams(-1, dp(28)) : new LayoutParams(0, dp(48), 1));
            actions.setLayoutParams(new LayoutParams(compact ? -1 : dp((simulate == null ? 440 : 500) + (calibrateShown ? 60 : 0)), dp(48)));
        }
        super.onMeasure(widthSpec, heightSpec);
    }
    private int dp(int value) { return MirrorUi.dp(getContext(), value); }
}
