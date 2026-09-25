package dev.ichinomiya.ninebotenhance.ui;

import dev.ichinomiya.ninebotenhance.ipc.Ipc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.drawable.Drawable;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.widget.*;
import dev.ichinomiya.ninebotenhance.platform.AppCatalog;
import java.util.*;

/** Reads the launcher catalog under the module's foreground identity, including OEM app-list consent. */
public final class LaunchAppPickerActivity extends Activity {
    public static final String RESULT = "picker_result";
    private MirrorUi theme;
    private ResultReceiver receiver;
    private TextView status;
    private EditText search;
    private Button refresh;
    private Apps adapter;
    private String selected;
    private boolean loading, reloadOnFocus, delivered, retryAfterLoad, resumed, returningFromSettings;
    private int focusRetries;
    private Button expand;
    private boolean expanded, catalogOk;
    private String selectedPackage = "";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private static final Set<String> MAPS = new HashSet<>(Arrays.asList("com.autonavi.minimap", "com.baidu.BaiduMap", "com.tencent.map"));

    @Override protected void onCreate(Bundle saved) {
        boolean dark = getIntent().getBooleanExtra("dark", true);
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(saved); theme = new MirrorUi(dark);
        receiver = Ipc.parcelableExtra(getIntent(), RESULT, ResultReceiver.class);
        selected = getIntent().getStringExtra(AppCatalog.SELECTED);
        ComponentName selectedComponent = selected == null ? null : ComponentName.unflattenFromString(selected);
        selectedPackage = selectedComponent == null ? "" : selectedComponent.getPackageName();
        int pad = MirrorUi.dp(this, 20), gap = MirrorUi.dp(this, 12);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.surface); root.setForceDarkAllowed(false); root.setPadding(pad,pad,pad,pad);
        root.setOnApplyWindowInsetsListener((v,insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            root.setPadding(pad+bars.left,pad+bars.top,pad+bars.right,pad+bars.bottom); return insets;
        });
        getWindow().setDecorFitsSystemWindows(false); getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        root.addView(label("选择启动应用",22));
        status = label("正在读取应用列表…",13); root.addView(status);
        search = new EditText(this); search.setSingleLine(); search.setHint("搜索应用名或包名");
        search.setTextColor(theme.text); search.setHintTextColor(theme.secondary); search.setPadding(gap,gap,gap,gap);
        search.setBackground(theme.background(this,theme.input,12,false)); root.addView(search,new LinearLayout.LayoutParams(-1,-2));
        ListView list = new ListView(this); list.setDivider(null); list.setCacheColorHint(android.graphics.Color.TRANSPARENT);
        adapter = new Apps(); list.setAdapter(adapter); root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        list.setOnItemClickListener((parent,view,position,id) -> { sendResult(RESULT_OK,adapter.visible.get(position)); finish(); });
        LinearLayout actions = new LinearLayout(this);
        Button permission = button("应用列表权限"); refresh = button("刷新列表");
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0,-2,1); first.setMarginEnd(gap);
        actions.addView(permission,first); actions.addView(refresh,new LinearLayout.LayoutParams(0,-2,1)); root.addView(actions);
        permission.setOnClickListener(v -> {
            try { returningFromSettings=true; startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName()))); }
            catch (RuntimeException e) { returningFromSettings=false; ErrorDialog.show(this,null,"无法打开应用信息页","请在系统应用权限中允许 Ninebot Enhance 读取应用列表。\n"+dev.ichinomiya.ninebotenhance.ipc.Ipc.error(e)); }
        });
        refresh.setOnClickListener(v -> { focusRetries=1; loadApps(); });
        expand = button("显示全部应用"); LinearLayout.LayoutParams expandParams = new LinearLayout.LayoutParams(-1,-2); expandParams.topMargin=gap;
        root.addView(expand, expandParams);
        expand.setOnClickListener(v -> { if (expanded) { expanded=false; expand.setText("显示全部应用"); adapter.filter(); updateStatus(); } else promptExpand(); });
        Button cancel = button("取消"); LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(-1,-2); cancelParams.topMargin=gap;
        root.addView(cancel,cancelParams); cancel.setOnClickListener(v -> finish()); setContentView(root);
        if (saved!=null) search.setText(saved.getString("query",""));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) { adapter.filter(); }
            public void afterTextChanged(Editable s) {}
        });
    }
    @Override protected void onResume() {
        super.onResume();
        if(!resumed||returningFromSettings) { resumed=true; returningFromSettings=false; reloadOnFocus=true; focusRetries=1; }
        if(hasWindowFocus()&&reloadOnFocus)reload();
    }
    @Override public void onAttachedToWindow() { super.onAttachedToWindow(); applyBars(); }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) { applyBars(); if(reloadOnFocus) reload(); }
        else if (focusRetries>0) { focusRetries--; reloadOnFocus=true; }
    }
    private void reload() { reloadOnFocus=false; if(loading) retryAfterLoad=true; else loadApps(); }
    private void loadApps() {
        if(loading||isFinishing())return;
        loading=true; refresh.setEnabled(false); status.setText("正在读取应用列表…");
        new Thread(() -> {
            ArrayList<Bundle> apps = new ArrayList<>(); boolean success = true;
            try {
                // ColorOS can intercept installed-app queries. Issue the same request as notification
                // settings while this module Activity is visible, before querying launcher entries.
                // QUERY_ALL_PACKAGES is a manifest permission, not an Android runtime permission.
                getPackageManager().getInstalledApplications(0);
                apps = AppCatalog.choices(getPackageManager());
            } catch (RuntimeException e) { success=false; }
            ArrayList<Bundle> result=apps; boolean ok=success;
            runOnUiThread(() -> {
                if(isFinishing()||isDestroyed())return;
                loading=false; catalogOk=ok; refresh.setEnabled(true); adapter.all.clear(); adapter.all.addAll(result); adapter.filter();
                updateStatus();
                // Some OEM permission dialogs return an initial filtered list before consent.
                // Retry once on focus return, never loop requests after a denial.
                if(retryAfterLoad) { retryAfterLoad=false; if(hasWindowFocus())loadApps(); else reloadOnFocus=true; }
            });
        },"Ninebot-LauncherApps").start();
    }
    private void updateStatus() {
        if (loading) { status.setText("正在读取应用列表…"); return; }
        if (!catalogOk) { status.setText("应用列表读取失败，请检查应用列表权限后刷新。"); return; }
        if (expanded) { status.setText("共 "+adapter.all.size()+" 个可启动应用"); return; }
        int maps=0; for (Bundle app : adapter.all) if (MAPS.contains(app.getString("package"))) maps++;
        status.setText("地图应用 "+maps+" 个");
    }
    /** The gate before every launcher app becomes selectable: a fixed-wait safety notice, then the full list. */
    private void promptExpand() {
        int pad = MirrorUi.dp(this, 20);
        TextView title = new TextView(this); title.setText("显示全部应用"); title.setTextSize(20); title.setTextColor(theme.text);
        title.setPadding(pad, pad, pad, pad / 2);
        TextView body = new TextView(this); body.setText("禁止投屏非地图导航应用，本功能仅用于投屏其他地图应用。投屏非地图导航应用导致的任何后果由自己承担。"); body.setTextSize(15); body.setTextColor(theme.secondary);
        body.setPadding(pad, pad / 2, pad, pad); body.setLineSpacing(MirrorUi.dp(this, 3), 1);
        AlertDialog dialog = new AlertDialog.Builder(this).setCustomTitle(title).setView(body).setCancelable(false)
                .setNegativeButton("取消", null).setPositiveButton("同意", null).create();
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(theme.background(this, theme.surface, 24, false));
        Button agree = dialog.getButton(AlertDialog.BUTTON_POSITIVE), cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        agree.setTextColor(theme.accent); cancel.setTextColor(theme.accent);
        int[] left = {10}; agree.setEnabled(false); agree.setText("同意（"+left[0]+"）");
        Runnable tick = new Runnable() { @Override public void run() {
            if (!dialog.isShowing() || isFinishing() || isDestroyed()) return;
            left[0]--;
            if (left[0] <= 0) { agree.setEnabled(true); agree.setText("同意"); }
            else { agree.setText("同意（"+left[0]+"）"); ui.postDelayed(this, 1000); }
        }};
        ui.postDelayed(tick, 1000);
        agree.setOnClickListener(v -> { if (left[0] > 0) return; expanded=true; expand.setText("只显示地图"); adapter.filter(); updateStatus(); dialog.dismiss(); });
    }
    private void applyBars() {
        if(theme==null)return;
        WindowInsetsController controller=getWindow().getDecorView().getWindowInsetsController(); if(controller==null)return;
        int light=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        controller.setSystemBarsAppearance(theme.dark?0:light,light);
        getWindow().setStatusBarContrastEnforced(false); getWindow().setNavigationBarContrastEnforced(false);
    }
    private void sendResult(int code,Bundle app) { if(delivered)return;delivered=true;if(receiver!=null)receiver.send(code,app); }
    @Override protected void onDestroy() { ui.removeCallbacksAndMessages(null); if(isFinishing())sendResult(RESULT_CANCELED,null); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putString("query",search.getText().toString()); }
    private TextView label(String text,int size) {
        TextView v=new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(size>=16?theme.text:theme.secondary);
        int gap=MirrorUi.dp(this,6); v.setPadding(0,gap,0,gap); return v;
    }
    private Button button(String text) { Button b=new Button(this); b.setText(text); theme.button(b,null); return b; }
    private final class Apps extends BaseAdapter {
        final ArrayList<Bundle> all=new ArrayList<>(),visible=new ArrayList<>();
        final android.util.LruCache<String,Drawable> icons=new android.util.LruCache<>(48);
        void filter() {
            String query=search.getText().toString().trim().toLowerCase(Locale.ROOT); visible.clear();
            for(Bundle app:all){
                String pkg=app.getString("package","");
                if(!expanded && !MAPS.contains(pkg) && !pkg.equals(selectedPackage)) continue;
                if(app.getString("label","").toLowerCase(Locale.ROOT).contains(query)||pkg.toLowerCase(Locale.ROOT).contains(query))visible.add(app);
            }
            notifyDataSetChanged();
        }
        public int getCount(){return visible.size();} public Object getItem(int p){return visible.get(p);} public long getItemId(int p){return p;}
        public View getView(int p,View recycled,ViewGroup parent) {
            Bundle app=visible.get(p); String pkg=app.getString("package"); int gap=MirrorUi.dp(LaunchAppPickerActivity.this,12);
            LinearLayout row=new LinearLayout(LaunchAppPickerActivity.this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(gap,gap,gap,gap);
            boolean checked=Objects.equals(selected,app.getString("component"));
            row.setBackground(theme.background(LaunchAppPickerActivity.this,checked?theme.input:theme.surface,14,false));
            ImageView icon=new ImageView(LaunchAppPickerActivity.this); Drawable drawable=icons.get(pkg);
            if(drawable==null)try{drawable=getPackageManager().getApplicationIcon(pkg);icons.put(pkg,drawable);}catch(Exception ignored){}
            icon.setImageDrawable(drawable!=null?drawable:new MirrorUi.Glyph("app",theme.secondary)); row.addView(icon,new LinearLayout.LayoutParams(gap*3,gap*3));
            LinearLayout words=new LinearLayout(LaunchAppPickerActivity.this); words.setOrientation(LinearLayout.VERTICAL); words.setPadding(gap,0,gap,0);
            TextView name=label(app.getString("label"),16),packageName=label(pkg,12);
            name.setSingleLine(); packageName.setSingleLine(); name.setEllipsize(TextUtils.TruncateAt.END); packageName.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            name.setPadding(0,0,0,0); packageName.setPadding(0,MirrorUi.dp(LaunchAppPickerActivity.this,3),0,0);
            words.addView(name); words.addView(packageName); row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
            ImageView mark=new ImageView(LaunchAppPickerActivity.this);mark.setImageDrawable(new MirrorUi.Glyph(checked?"chosen":"choice",checked?theme.accent:theme.secondary));
            mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(mark,new LinearLayout.LayoutParams(gap*2,gap*2));return row;
        }
    }
}
