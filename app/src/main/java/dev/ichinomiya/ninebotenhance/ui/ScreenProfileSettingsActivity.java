package dev.ichinomiya.ninebotenhance.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.*;

/**
 * The vehicle screen's own page: the five-inch dashboard or the 2.4 inch 240 x 320 panel, whether the module's dashboard text
 * keeps the system font instead of the phone's, and on the small panel which board the voltage and charge come from and what
 * colour the three rows are drawn in. The choices are stored in the module process and reach the renderer and the session start
 * with the settings bundle, so no Ninebot view or preference is involved.
 */
public final class ScreenProfileSettingsActivity extends Activity {
    public static final String PREFERENCES = "screen_profile", MODE = "screen_mode", FONT = "default_font";
    /** The small panel's three row colours and where it reads voltage and charge from; 0 keeps the theme and the vehicle. */
    public static final String BG_COLOR = "small_bg_color", SPEED_COLOR = "small_speed_color", ROW1_COLOR = "small_row1_color",
            ROW2_COLOR = "small_row2_color", SOURCE = "small_source";
    public static final int FIVE_INCH = 0, SMALL_240 = 1, SOURCE_VEHICLE = 0, SOURCE_BMS = 1;
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFERENCES, 0); }
    /** Which dashboard shape the HUD draws: the sidebar cards, or the small screen's speed and battery blocks. */
    public static int mode(Context context) { return prefs(context).getInt(MODE, FIVE_INCH); }
    /** Whether the module's own dashboard text is drawn with the bundled system font. */
    public static boolean defaultFont(Context context) { return prefs(context).getInt(FONT, 1) != 0; }
    public static int smallBackgroundColor(Context context) { return prefs(context).getInt(BG_COLOR, 0xff000000); }
    public static int speedColor(Context context) { return prefs(context).getInt(SPEED_COLOR, 0); }
    public static int row1Color(Context context) { return prefs(context).getInt(ROW1_COLOR, 0); }
    public static int row2Color(Context context) { return prefs(context).getInt(ROW2_COLOR, 0); }
    public static int smallSource(Context context) { return prefs(context).getInt(SOURCE, SOURCE_VEHICLE); }
    private MirrorUi theme;
    @Override protected void onCreate(Bundle saved) {
        boolean dark = getIntent().getBooleanExtra("dark", true);
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(saved);
        theme = new MirrorUi(dark);
        int pad = MirrorUi.dp(this, 20), gap = MirrorUi.dp(this, 12);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.surface); root.setForceDarkAllowed(false); root.setPadding(pad, pad, pad, pad);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            root.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom);
            return insets;
        });
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        root.addView(label("屏幕规格", 22));
        SharedPreferences prefs = getSharedPreferences(PREFERENCES, 0);
        RadioGroup shapes = new RadioGroup(this); shapes.setOrientation(RadioGroup.VERTICAL);
        RadioButton five = radio("五寸仪表 848 × 480"), small = radio("2.4 寸小屏 240 × 320");
        shapes.addView(five); shapes.addView(small);
        (prefs.getInt(MODE, FIVE_INCH) == SMALL_240 ? small : five).setChecked(true);
        root.addView(shapes, new LinearLayout.LayoutParams(-1, -2));
        CheckBox font = new CheckBox(this); font.setText("默认字体"); font.setTextColor(theme.text); font.setTextSize(15);
        font.setButtonTintList(android.content.res.ColorStateList.valueOf(theme.accent));
        font.setChecked(prefs.getInt(FONT, 1) != 0);
        font.setGravity(Gravity.CENTER_VERTICAL | Gravity.START); font.setIncludeFontPadding(false);
        font.setPadding(0, gap, 0, gap);
        root.addView(font, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout smallBlock = new LinearLayout(this); smallBlock.setOrientation(LinearLayout.VERTICAL);
        smallBlock.addView(label("小屏数据来源", 14));
        RadioGroup sources = new RadioGroup(this); sources.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton vehicle = radio("九号数据"), board = radio("BMS 数据");
        sources.addView(vehicle, new RadioGroup.LayoutParams(0, -2, 1));
        sources.addView(board, new RadioGroup.LayoutParams(0, -2, 1));
        (smallSource(this) == SOURCE_BMS ? board : vehicle).setChecked(true);
        smallBlock.addView(sources, new LinearLayout.LayoutParams(-1, -2));
        String[] colourNames = {"底色颜色", "速度颜色", "第一行颜色", "第二行颜色"};
        String[] colourKeys = {BG_COLOR, SPEED_COLOR, ROW1_COLOR, ROW2_COLOR};
        int[] stored = {smallBackgroundColor(this), speedColor(this), row1Color(this), row2Color(this)};
        CheckBox[] automatic = new CheckBox[colourNames.length]; BandColorButton[] swatches = new BandColorButton[colourNames.length];
        for (int i = 0; i < colourNames.length; i++) smallBlock.addView(colourRow(colourNames[i], stored[i], automatic, swatches, i));
        LinearLayout.LayoutParams smallParams = new LinearLayout.LayoutParams(-1, -2); smallParams.topMargin = gap;
        root.addView(smallBlock, smallParams);
        shapes.setOnCheckedChangeListener((group, id) -> smallBlock.setVisibility(id == small.getId() ? View.VISIBLE : View.GONE));
        smallBlock.setVisibility(small.isChecked() ? View.VISIBLE : View.GONE);
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.CENTER_VERTICAL);
        Button close = button("关闭"), save = button("保存");
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(0, -2, 1);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, -2, 1);
        saveParams.setMarginStart(gap);
        actions.addView(close, closeParams); actions.addView(save, saveParams);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.topMargin = gap;
        root.addView(actions, actionsParams);
        save.setOnClickListener(v -> {
            SharedPreferences.Editor edit = prefs.edit().putInt(MODE, small.isChecked() ? SMALL_240 : FIVE_INCH)
                    .putInt(FONT, font.isChecked() ? 1 : 0).putInt(SOURCE, board.isChecked() ? SOURCE_BMS : SOURCE_VEHICLE);
            for (int i = 0; i < colourKeys.length; i++) edit.putInt(colourKeys[i], automatic[i].isChecked() ? 0 : swatches[i].color());
            edit.apply();
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
            finish();
        });
        close.setOnClickListener(v -> finish());
        ScrollView scroll = new ScrollView(this); scroll.addView(root); scroll.setBackgroundColor(theme.surface);
        setContentView(scroll);
    }
    private TextView label(String text, int size) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size);
        view.setTextColor(size >= 20 ? theme.text : theme.secondary);
        view.setPadding(0, MirrorUi.dp(this, 6), 0, MirrorUi.dp(this, 6));
        return view;
    }
    /** One colour line: a swatch that edits an opaque value, with the theme's own colour taking the field over. */
    private LinearLayout colourRow(String text, int stored, CheckBox[] automatic, BandColorButton[] swatches, int index) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this); name.setText(text); name.setTextColor(theme.text); name.setTextSize(15);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        boolean themed = stored == 0;
        CheckBox follow = new CheckBox(this); follow.setText("跟随主题"); follow.setTextColor(theme.text); follow.setTextSize(13);
        follow.setButtonTintList(ColorStateList.valueOf(theme.accent)); follow.setIncludeFontPadding(false);
        follow.setChecked(themed); row.addView(follow);
        BandColorButton swatch = new BandColorButton(this, theme, themed ? 0xffffffff : stored, text);
        swatch.setEnabled(!themed);
        LinearLayout.LayoutParams swatchParams = new LinearLayout.LayoutParams(MirrorUi.dp(this, 110), -2);
        swatchParams.setMarginStart(MirrorUi.dp(this, 8));
        row.addView(swatch, swatchParams);
        follow.setOnCheckedChangeListener((button, checked) -> swatch.setEnabled(!checked));
        automatic[index] = follow; swatches[index] = swatch;
        return row;
    }
    private RadioButton radio(String text) {
        RadioButton button = new RadioButton(this); button.setId(View.generateViewId()); button.setText(text);
        button.setTextColor(theme.text); button.setTextSize(15);
        button.setButtonTintList(android.content.res.ColorStateList.valueOf(theme.accent));
        button.setPadding(0, MirrorUi.dp(this, 6), 0, MirrorUi.dp(this, 6));
        return button;
    }
    private Button button(String text) {
        Button view = new Button(this); view.setText(text); view.setAllCaps(false); theme.button(view, null);
        view.setTextSize(14); view.setMaxLines(1); view.setMinimumHeight(MirrorUi.dp(this, 52));
        return view;
    }
}
