package dev.ichinomiya.ninebotenhance.ui;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Insets;
import android.os.*;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.view.*;
import android.widget.*;
import dev.ichinomiya.ninebotenhance.notification.*;
import dev.ichinomiya.ninebotenhance.core.NotificationTimeline;
import java.text.Collator;
import java.util.*;

/** Module-owned permission/settings screen, launched by a UID-checked immutable PendingIntent. */
public final class NotificationSettingsActivity extends Activity {
    private NotificationPreferences prefs;private MirrorUi theme;private Switch enabled;private SeekBar duration,widthBar,limitBar;private EditText search;
    private TextView status,phoneStatus;private Button grant,phoneGrant;private ListView list;private AppAdapter adapter;
    private final Set<String> selected=new HashSet<>();private boolean loading,loaded,updating,lastGranted;
    private int catalogGeneration;
    private record App(String pkg,String label,ApplicationInfo info){}
    @Override protected void onCreate(Bundle saved){
        // Resources may already be accessed before onCreate (including on ColorOS).
        // Use a theme and explicit palette instead of a late configuration override.
        boolean dark=getIntent().getBooleanExtra("dark",true);
        setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(saved);prefs=new NotificationPreferences(this);theme=new MirrorUi(dark);
        selected.addAll(saved!=null?Optional.ofNullable(saved.getStringArrayList("selected")).orElse(new ArrayList<>()):prefs.packages());
        int pad=MirrorUi.dp(this,20),gap=MirrorUi.dp(this,12);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(theme.surface);root.setForceDarkAllowed(false);
        root.setPadding(pad,pad,pad,pad);
        root.setOnApplyWindowInsetsListener((v,insets)->{Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());root.setPadding(pad+bars.left,pad+bars.top,pad+bars.right,pad+bars.bottom);return insets;});
        getWindow().setDecorFitsSystemWindows(false);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.VERTICAL);
        TextView title=label("通知设置",22);header.addView(title);
        enabled=new Switch(this);enabled.setText("显示通知");enabled.setTextColor(theme.text);enabled.setTextSize(16);enabled.setPadding(0,gap,0,gap);enabled.setChecked(saved!=null?saved.getBoolean("enabled"):prefs.enabled());header.addView(enabled);
        status=label("",14);header.addView(status);
        grant=button("授予通知使用权");header.addView(grant);grant.setOnClickListener(v->openNotificationAccess());
        LinearLayout timing=new LinearLayout(this);timing.setGravity(Gravity.CENTER_VERTICAL);TextView timingTitle=label("显示时长",14);timing.addView(timingTitle,new LinearLayout.LayoutParams(0,-2,1));
        TextView durationValue=label("",16);timing.addView(durationValue);
        LinearLayout.LayoutParams timingParams=new LinearLayout.LayoutParams(-1,-2);timingParams.topMargin=gap;header.addView(timing,timingParams);
        duration=new SeekBar(this);duration.setMin(NotificationTimeline.MIN_SECONDS);duration.setMax(NotificationTimeline.MAX_SECONDS);
        duration.setProgress(NotificationTimeline.clampSeconds(saved!=null?saved.getInt("duration_seconds",prefs.seconds()):prefs.seconds()));
        duration.setProgressTintList(android.content.res.ColorStateList.valueOf(theme.accent));duration.setThumbTintList(android.content.res.ColorStateList.valueOf(theme.accent));
        duration.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(theme.input));duration.setContentDescription("显示时长");
        durationValue.setText(duration.getProgress()+" 秒");header.addView(duration,new LinearLayout.LayoutParams(-1,MirrorUi.dp(this,48)));
        duration.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar bar,int value,boolean fromUser){durationValue.setText(value+" 秒");}public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}});
        widthBar=slider(header,timingParams,"通知宽度",NotificationTimeline.MIN_WIDTH,NotificationTimeline.MAX_WIDTH,saved!=null?saved.getInt("notification_width",prefs.width()):prefs.width(),value->value+"（848 宽画面）");
        limitBar=slider(header,timingParams,"最多同时显示",NotificationTimeline.MIN_LIMIT,NotificationTimeline.MAX_LIMIT,saved!=null?saved.getInt("notification_limit",prefs.limit()):prefs.limit(),value->value+" 条");
        phoneStatus=label("",13);header.addView(phoneStatus);phoneGrant=button("授权手机信号");header.addView(phoneGrant);
        phoneGrant.setOnClickListener(v->requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE},701));
        search=new EditText(this);search.setSingleLine();search.setHint("搜索应用名或包名");search.setTextColor(theme.text);search.setHintTextColor(theme.secondary);search.setPadding(gap,gap,gap,gap);search.setBackground(theme.background(this,theme.input,12,false));header.addView(search,timingParams);
        list=new ListView(this);list.setDivider(null);list.setCacheColorHint(android.graphics.Color.TRANSPARENT);list.addHeaderView(header,null,false);adapter=new AppAdapter();list.setAdapter(adapter);
        root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=new LinearLayout(this);Button close=button("关闭"),save=button("保存");LinearLayout.LayoutParams closeParams=new LinearLayout.LayoutParams(0,-2,1);closeParams.rightMargin=gap;footer.addView(close,closeParams);footer.addView(save,new LinearLayout.LayoutParams(0,-2,1));root.addView(footer);close.setOnClickListener(v->finish());save.setOnClickListener(v->save());setContentView(root);
        enabled.setOnCheckedChangeListener((button,checked)->{if(updating)return;if(checked&&!prefs.granted()){updating=true;enabled.setChecked(false);updating=false;new AlertDialog.Builder(this).setMessage("请先授予 Ninebot Enhance 通知使用权，再选择显示通知的 App。").setPositiveButton("去授权",(d,w)->openNotificationAccess()).setNegativeButton("取消",null).show();}});
        search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){adapter.filter(s.toString());}public void afterTextChanged(android.text.Editable s){}});
    }
    @Override public void onAttachedToWindow(){super.onAttachedToWindow();applySystemBars();}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)applySystemBars();}
    private void applySystemBars(){
        // The decor is available here; PhoneWindow.getInsetsController() can crash before setContentView.
        if(theme==null)return;
        WindowInsetsController controller=getWindow().getDecorView().getWindowInsetsController();
        if(controller==null)return;
        int lightBars=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        controller.setSystemBarsAppearance(theme.dark?0:lightBars,lightBars);
        getWindow().setStatusBarContrastEnforced(false);getWindow().setNavigationBarContrastEnforced(false);
    }
    @Override protected void onResume(){super.onResume();if(prefs==null)return;refresh();}
    private void refresh(){
        boolean granted=prefs.granted();status.setText(granted?"通知使用权已授权，请勾选允许显示通知的 App。":"未授予通知使用权，授权后显示全部已安装 App。");grant.setVisibility(granted?View.GONE:View.VISIBLE);search.setVisibility(granted?View.VISIBLE:View.GONE);
        boolean phone=checkSelfPermission(Manifest.permission.READ_PHONE_STATE)==PackageManager.PERMISSION_GRANTED;phoneStatus.setText(phone?"手机信号权限已授权":"手机信号需要电话状态权限，仅用于读取 SIM 卡数量和信号强度。");phoneGrant.setVisibility(phone?View.GONE:View.VISIBLE);
        if(!granted){catalogGeneration++;loading=loaded=false;adapter.all.clear();adapter.filter("");updating=true;enabled.setChecked(false);updating=false;}
        else {if(!lastGranted)try{NotificationListenerService.requestRebind(NotificationPreferences.listener(this));}catch(RuntimeException ignored){}if(!loaded&&!loading)loadApps();}
        lastGranted=granted;
    }
    private void loadApps(){loading=true;int generation=++catalogGeneration;status.setText("正在读取全部已安装 App…");
        new Thread(()->{ArrayList<App> apps=new ArrayList<>();boolean ok=true;try{PackageManager pm=getPackageManager();for(ApplicationInfo info:pm.getInstalledApplications(0)){String name;try{name=info.loadLabel(pm).toString();}catch(RuntimeException e){name=info.packageName;}apps.add(new App(info.packageName,name,info));}Collator collator=Collator.getInstance();apps.sort((a,b)->{int order=collator.compare(a.label,b.label);return order!=0?order:a.pkg.compareTo(b.pkg);});}catch(RuntimeException e){ok=false;}boolean success=ok;
            runOnUiThread(()->{if(isFinishing()||isDestroyed()||generation!=catalogGeneration)return;loading=false;if(!prefs.granted()){refresh();return;}loaded=success;adapter.all.clear();adapter.all.addAll(apps);adapter.filter(search.getText().toString());status.setText(success?"通知使用权已授权，共 "+apps.size()+" 个 App。":"应用列表读取失败，请关闭后重试。");});},"Ninebot-NotificationApps").start();
    }
    private void openNotificationAccess(){
        try{startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,NotificationPreferences.listener(this).flattenToString()));}
        catch(RuntimeException e){try{startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));}catch(RuntimeException second){ErrorDialog.show(this,null,"无法打开通知使用权页面","请在系统设置中搜索通知使用权。\n"+dev.ichinomiya.ninebotenhance.ipc.Ipc.error(second));}}
    }
    private void save(){try{prefs.save(enabled.isChecked(),duration.getProgress(),widthBar.getProgress(),limitBar.getProgress(),selected);Toast.makeText(this,"通知设置已保存",Toast.LENGTH_SHORT).show();finish();}catch(RuntimeException e){ErrorDialog.show(this,null,"保存失败",String.valueOf(e.getMessage()));}}
    private interface Describe{String of(int value);}
    private SeekBar slider(LinearLayout parent,LinearLayout.LayoutParams params,String caption,int min,int max,int value,Describe describe){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.addView(label(caption,14),new LinearLayout.LayoutParams(0,-2,1));
        TextView shown=label("",16);row.addView(shown);parent.addView(row,params);
        SeekBar bar=new SeekBar(this);bar.setMin(min);bar.setMax(max);bar.setProgress(Math.max(min,Math.min(max,value)));bar.setContentDescription(caption);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(theme.accent));bar.setThumbTintList(android.content.res.ColorStateList.valueOf(theme.accent));bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(theme.input));
        shown.setText(describe.of(bar.getProgress()));parent.addView(bar,new LinearLayout.LayoutParams(-1,MirrorUi.dp(this,48)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int progress,boolean fromUser){shown.setText(describe.of(progress));}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        return bar;
    }
    @Override public void onRequestPermissionsResult(int code,String[] names,int[] grants){super.onRequestPermissionsResult(code,names,grants);if(code==701){refresh();if(checkSelfPermission(Manifest.permission.READ_PHONE_STATE)!=PackageManager.PERMISSION_GRANTED)new AlertDialog.Builder(this).setMessage("手机信号权限未授权，可在系统应用权限中为 Ninebot Enhance 开启电话权限。").setPositiveButton("应用权限",(d,w)->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName())))).setNegativeButton("关闭",null).show();}}
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putBoolean("enabled",enabled.isChecked());b.putInt("duration_seconds",duration.getProgress());b.putInt("notification_width",widthBar.getProgress());b.putInt("notification_limit",limitBar.getProgress());b.putStringArrayList("selected",new ArrayList<>(selected));}
    private TextView label(String text,int size){TextView v=new TextView(this);v.setText(text);v.setTextColor(size>=16?theme.text:theme.secondary);v.setTextSize(size);v.setPadding(0,MirrorUi.dp(this,6),0,MirrorUi.dp(this,6));return v;}
    private Button button(String text){Button b=new Button(this);b.setText(text);theme.button(b,null);return b;}
    private final class AppAdapter extends BaseAdapter {
        final ArrayList<App> all=new ArrayList<>(),visible=new ArrayList<>();final android.util.LruCache<String,android.graphics.drawable.Drawable> icons=new android.util.LruCache<>(48);
        void filter(String query){String q=query.trim().toLowerCase(Locale.ROOT);visible.clear();for(boolean checked:new boolean[]{true,false})for(App a:all)if(selected.contains(a.pkg)==checked&&(a.label.toLowerCase(Locale.ROOT).contains(q)||a.pkg.toLowerCase(Locale.ROOT).contains(q)))visible.add(a);notifyDataSetChanged();}
        public int getCount(){return visible.size();}public Object getItem(int p){return visible.get(p);}public long getItemId(int p){return p;}
        public View getView(int p,View recycled,ViewGroup parent){App app=visible.get(p);LinearLayout row=new LinearLayout(NotificationSettingsActivity.this);row.setGravity(Gravity.CENTER_VERTICAL);int gap=MirrorUi.dp(NotificationSettingsActivity.this,12);row.setPadding(0,gap,0,gap);
            ImageView icon=new ImageView(NotificationSettingsActivity.this);android.graphics.drawable.Drawable d=icons.get(app.pkg);if(d==null)try{d=app.info.loadIcon(getPackageManager());icons.put(app.pkg,d);}catch(RuntimeException ignored){}icon.setImageDrawable(d);row.addView(icon,new LinearLayout.LayoutParams(gap*3,gap*3));
            LinearLayout words=new LinearLayout(NotificationSettingsActivity.this);words.setOrientation(LinearLayout.VERTICAL);TextView name=label(app.label,16),pkg=label(app.pkg,12);name.setPadding(gap,0,gap,0);pkg.setPadding(gap,0,gap,0);name.setSingleLine();pkg.setSingleLine();name.setEllipsize(android.text.TextUtils.TruncateAt.END);pkg.setEllipsize(android.text.TextUtils.TruncateAt.END);words.addView(name);words.addView(pkg);row.addView(words,new LinearLayout.LayoutParams(0,-2,1));CheckBox check=new CheckBox(NotificationSettingsActivity.this);check.setChecked(selected.contains(app.pkg));check.setContentDescription(app.label);row.addView(check);check.setOnCheckedChangeListener((b,value)->{if(value)selected.add(app.pkg);else selected.remove(app.pkg);filter(search.getText().toString());});row.setOnClickListener(v->check.setChecked(!check.isChecked()));return row;
        }
    }
}
