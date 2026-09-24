package dev.ichinomiya.ninebotenhance.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Shared phone preview controls, with a second row when the labelled actions cannot fit beside the title. */
public final class PreviewToolbar extends LinearLayout {
    private static final long REFRESH_MS = 1000;
    private final TextView title;
    private final LinearLayout actions;
    private MirrorUi theme;
    private final PreviewPicture picture;
    private final Button back, turn, keyboard, calibrate, close;
    private Button dashboard;
    private final BooleanSupplier landscape;
    private final Supplier<String> calibration;
    private boolean configured, wasCompact, wasCalibrate;
    private final Runnable refresher = new Runnable() { @Override public void run() { if (isAttachedToWindow()) { refresh(); postDelayed(this, REFRESH_MS); } } };
    /**
     * {@code landscape} / {@code toggleLandscape} back the "横屏" button: the preview window's orientation, or the picture's own
     * quarter turn where the window cannot turn. {@code calibration} names the touch panel action ("校准" / "取消校准") or returns
     * null while no panel is present; the button follows it once a second. {@code closeLabel} names the last button.
     */
    public PreviewToolbar(Context context, MirrorUi theme, String label, PreviewPicture picture, Runnable diagnostics,
                          BooleanSupplier landscape, Runnable toggleLandscape, BooleanSupplier dashboardDark, Runnable toggleDashboardTheme,
                          Supplier<String> calibration, Runnable calibrateAction, String closeLabel, Runnable closeAction) {
        super(context); this.theme = theme; this.picture = picture; this.landscape = landscape; this.calibration = calibration;
        setGravity(Gravity.CENTER_VERTICAL); setBackgroundColor(theme.surface);
        setPadding(dp(10), dp(4), dp(10), dp(4));
        title = new TextView(context); title.setText(label); title.setTextSize(14); title.setTextColor(theme.text);
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER_VERTICAL); title.setOnLongClickListener(v -> { diagnostics.run(); return true; });
        addView(title);
        actions = new LinearLayout(context); actions.setGravity(Gravity.CENTER_VERTICAL); addView(actions);
        back = action("返回", picture::back);
        turn = action("横屏", () -> { toggleLandscape.run(); refresh(); });
        keyboard = action("输入法", picture::toggleKeyboard);
        dashboard = action(dashboardDark.getAsBoolean() ? "深色" : "浅色", () -> {
            toggleDashboardTheme.run(); dashboard.setText(dashboardDark.getAsBoolean() ? "深色" : "浅色"); dashboard.setContentDescription(dashboard.getText());
        });
        calibrate = action("校准", () -> { calibrateAction.run(); refresh(); }); calibrate.setVisibility(GONE);
        close = action(closeLabel, closeAction);
        picture.onControlsChanged = () -> {
            keyboard.setText(picture.keyboardActive() ? "收起" : "输入法"); style(keyboard, picture.keyboardActive()); refresh();
        };
        picture.onControlsChanged.run();
    }
    /** Follows the orientation choice and the touch panel: "校准" appears while a panel is present, "取消校准" while tapping targets. */
    public void refresh() {
        boolean turned = landscape.getAsBoolean(); String turnLabel = turned ? "还原" : "横屏";
        if (!turnLabel.contentEquals(turn.getText())) { turn.setText(turnLabel); turn.setContentDescription(turnLabel); }
        if (turn.isSelected() != turned) style(turn, turned);
        String label = calibration.get();
        boolean shown = label != null;
        if (shown && !label.contentEquals(calibrate.getText())) { calibrate.setText(label); calibrate.setContentDescription(label); }
        boolean active = shown && label.startsWith("取消");
        if (calibrate.isSelected() != active) style(calibrate, active);
        if ((calibrate.getVisibility() == VISIBLE) != shown) { calibrate.setVisibility(shown ? VISIBLE : GONE); requestLayout(); }
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); removeCallbacks(refresher); postDelayed(refresher, REFRESH_MS); }
    @Override protected void onDetachedFromWindow() { removeCallbacks(refresher); super.onDetachedFromWindow(); }
    public void applyTheme(MirrorUi next) {
        if (theme.dark == next.dark) return;
        theme = next; setBackgroundColor(theme.surface); title.setTextColor(theme.text);
        style(back, false); style(turn, landscape.getAsBoolean()); style(keyboard, picture.keyboardActive());
        style(dashboard, false); style(calibrate, calibrate.isSelected()); style(close, false);
    }
    private Button action(String label, Runnable action) {
        Button button = new Button(getContext()); button.setText(label); button.setContentDescription(label);
        style(button, false); button.setOnClickListener(v -> action.run());
        LayoutParams params = new LayoutParams(0, dp(44), 1); if (actions.getChildCount() > 0) params.leftMargin = dp(6);
        actions.addView(button, params); return button;
    }
    /** Text only, sized to fit; a chosen state shows in the accent colour. */
    private void style(Button button, boolean active) {
        theme.button(button, null); button.setTextSize(12); button.setPadding(dp(4), 0, dp(4), 0); button.setMaxLines(1);
        button.setAutoSizeTextTypeUniformWithConfiguration(9, 12, 1, TypedValue.COMPLEX_UNIT_SP);
        button.setTextColor(active ? theme.accent : theme.text);
        button.setCompoundDrawablesRelative(null, null, null, null); button.setSelected(active);
        button.setContentDescription(button.getText());
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        boolean compact = MeasureSpec.getSize(widthSpec) < dp(640);
        boolean calibrateShown = calibrate.getVisibility() == VISIBLE;
        if (!configured || wasCompact != compact || wasCalibrate != calibrateShown) {
            configured = true; wasCompact = compact; wasCalibrate = calibrateShown; setOrientation(compact ? VERTICAL : HORIZONTAL);
            title.setLayoutParams(compact ? new LayoutParams(-1, dp(28)) : new LayoutParams(0, dp(48), 1));
            actions.setLayoutParams(new LayoutParams(compact ? -1 : dp(440 + (calibrateShown ? 60 : 0)), dp(48)));
        }
        super.onMeasure(widthSpec, heightSpec);
    }
    private int dp(int value) { return MirrorUi.dp(getContext(), value); }
}
