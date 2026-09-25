package dev.ichinomiya.ninebotenhance.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import dev.ichinomiya.ninebotenhance.core.BandColor;
import dev.ichinomiya.ninebotenhance.core.DisplaySettings;

/** Compact colour field. Popup edits are applied to the field only after confirmation. */
final class BandColorButton extends Button {
    private final Activity activity;
    private final MirrorUi theme;
    private final String title;
    private int color;
    private AlertDialog popup;

    BandColorButton(Activity activity, MirrorUi theme, int color) { this(activity, theme, color, "背景颜色"); }

    BandColorButton(Activity activity, MirrorUi theme, int color, String title) {
        super(activity); this.activity = activity; this.theme = theme; this.title = title;
        theme.button(this, null); setTextSize(12); setSingleLine(true);
        setForceDarkAllowed(false);
        setPadding(dp(4), dp(12), dp(4), dp(12)); setMinimumHeight(dp(52));
        setBandColor(color); setOnClickListener(v -> choose());
    }
    int color() { return color; }
    void setBandColor(int value) {
        BandColor.requireOpaque(value); color = value;
        sample(this,color);setContentDescription(title+" "+BandColor.hex(color)+"，点击修改");
    }
    private void sample(TextView view, int value) {
        view.setText(BandColor.hex(value));
        view.setTextColor(Color.luminance(value) > .179f ? Color.BLACK : Color.WHITE);
        view.setBackground(theme.background(activity, value, 12, true));
    }
    private void choose() {
        if (!isEnabled() || !isAttachedToWindow() || activity.isFinishing() || activity.isDestroyed() || popup != null) return;
        LinearLayout content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(20));
        content.setBackground(theme.background(activity, theme.surface, 24, false)); content.setClipToOutline(true);
        content.setForceDarkAllowed(false);
        TextView heading = new TextView(activity); heading.setText(title); heading.setTextColor(theme.text); heading.setTextSize(20);
        heading.setPadding(0, 0, 0, dp(16)); content.addView(heading);
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(activity); scroll.addView(body); content.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1));
        EditText input = new EditText(activity); input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setTextColor(theme.text); input.setHintTextColor(theme.secondary); input.setHint("#RRGGBB");
        input.setTextSize(18); input.setBackgroundTintList(null); input.setBackground(theme.background(activity, theme.input, 12, false));
        input.setPadding(dp(12), dp(12), dp(12), dp(12)); input.setText(BandColor.hex(color));
        input.setSelectAllOnFocus(true); input.setContentDescription("六位颜色值"); body.addView(input, new LinearLayout.LayoutParams(-1, -2));
        TextView preview = new TextView(activity); preview.setGravity(android.view.Gravity.CENTER); preview.setTextSize(14);
        sample(preview, color);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, dp(48)); previewParams.topMargin = dp(12);
        body.addView(preview, previewParams);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                input.setError(null);
                try { sample(preview, BandColor.parse(text.toString())); } catch (IllegalArgumentException ignored) {}
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        int[] colors = {0xff000000, 0xff17191f, DisplaySettings.DEFAULT_BACKGROUND_COLOR, 0xff333333, 0xff606060, 0xffffffff};
        for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
            LinearLayout row = new LinearLayout(activity); row.setBaselineAligned(false);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2); rowParams.topMargin = dp(8); body.addView(row, rowParams);
            for (int i = 0; i < 3; i++) {
                int preset = colors[rowIndex * 3 + i];
                Button button = new Button(activity); theme.button(button, null); button.setTextSize(11); button.setPadding(0, 0, 0, 0);
                sample(button, preset); button.setContentDescription("选择 " + BandColor.hex(preset));
                button.setOnClickListener(v -> input.setText(BandColor.hex(preset)));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1); if (i > 0) params.setMarginStart(dp(8)); row.addView(button, params);
            }
        }
        LinearLayout actions = new LinearLayout(activity);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2); actionsParams.topMargin = dp(16); content.addView(actions, actionsParams);
        AlertDialog dialog = new AlertDialog.Builder(activity).create(); popup = dialog;
        dialog.setView(content, 0, 0, 0, 0);
        action(actions,"默认",()->input.setText(BandColor.hex(DisplaySettings.DEFAULT_BACKGROUND_COLOR)));
        action(actions, "取消", dialog::dismiss);
        action(actions, "确定", () -> {
            if (!isEnabled() || !isAttachedToWindow()) { dialog.dismiss(); return; }
            try { setBandColor(BandColor.parse(input.getText().toString())); dialog.dismiss(); }
            catch (IllegalArgumentException e) { input.setError(e.getMessage()); }
        });
        dialog.setOnDismissListener(v -> { if (popup == dialog) popup = null; }); dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(theme.background(activity, theme.surface, 24, false));
    }
    private void action(LinearLayout row, String text, Runnable click) {
        Button button = new Button(activity); theme.button(button, null); button.setText(text); button.setTextSize(13);
        button.setPadding(dp(4), dp(12), dp(4), dp(12)); button.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        if (row.getChildCount() > 0) params.setMarginStart(dp(8)); row.addView(button, params);
    }
    @Override public void setEnabled(boolean enabled) { super.setEnabled(enabled); setAlpha(enabled ? 1 : .5f); if (!enabled && popup != null) popup.dismiss(); }
    @Override protected void onDetachedFromWindow() { if (popup != null) popup.dismiss(); super.onDetachedFromWindow(); }
    private int dp(int value) { return MirrorUi.dp(activity, value); }
}
