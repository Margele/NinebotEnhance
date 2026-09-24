package dev.ichinomiya.ninebotenhance.ui;

import android.Manifest;
import android.app.*;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import dev.ichinomiya.ninebotenhance.core.LampSettings;
import dev.ichinomiya.ninebotenhance.core.LampState;
import dev.ichinomiya.ninebotenhance.core.TxLampProtocol;
import dev.ichinomiya.ninebotenhance.lamp.LampController;
import java.util.List;
import java.util.function.Consumer;

/**
 * Module-owned lamp screen, launched by a UID-checked immutable PendingIntent. It runs in the module process, so the Bluetooth
 * permissions it asks for are the module's own and the vehicle's link is untouched. Saving writes the binding and then holds the
 * radio open long enough to authenticate, so the outcome shown is a real handshake with the device.
 */
public final class LampSettingsActivity extends Activity {
    private MirrorUi theme;private LampController lamp;
    private EditText mac,password;private SeekBar steps;private Switch volumeControl,reversed;
    private TextView status,permissionStatus,stepsValue;private Button grant,scan,save;
    private Consumer<LampState> watcher;private boolean saving;
    @Override protected void onCreate(Bundle saved){
        boolean dark=getIntent().getBooleanExtra("dark",true);
        setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(saved);theme=new MirrorUi(dark);lamp=LampController.get(this);
        LampSettings current=lamp.settings();
        int pad=MirrorUi.dp(this,20),gap=MirrorUi.dp(this,12);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.surface);root.setForceDarkAllowed(false);root.setPadding(pad,pad,pad,pad);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
            root.setPadding(pad+bars.left,pad+bars.top,pad+bars.right,pad+bars.bottom);return insets;});
        getWindow().setDecorFitsSystemWindows(false);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        root.addView(label("大灯控制",22));
        permissionStatus=label("",14);root.addView(permissionStatus);
        grant=button("授予蓝牙权限");root.addView(grant);
        grant.setOnClickListener(v->requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN},7));
        LinearLayout addressRow=new LinearLayout(this);addressRow.setGravity(Gravity.BOTTOM);
        LinearLayout addressColumn=new LinearLayout(this);addressColumn.setOrientation(LinearLayout.VERTICAL);
        addressColumn.addView(caption("蓝牙地址"));
        mac=field(current.mac(),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS,17);
        addressColumn.addView(mac,new LinearLayout.LayoutParams(-1,-2));
        addressRow.addView(addressColumn,new LinearLayout.LayoutParams(0,-2,1));
        scan=button("扫描");scan.setOnClickListener(v->startScan());
        LinearLayout.LayoutParams scanParams=new LinearLayout.LayoutParams(MirrorUi.dp(this,92),MirrorUi.dp(this,52));
        scanParams.setMarginStart(gap);addressRow.addView(scan,scanParams);
        LinearLayout.LayoutParams addressParams=new LinearLayout.LayoutParams(-1,-2);addressParams.topMargin=gap;root.addView(addressRow,addressParams);
        root.addView(caption("密码"));
        password=field(current.password(),InputType.TYPE_CLASS_NUMBER,TxLampProtocol.PASSWORD_LENGTH);
        root.addView(password,new LinearLayout.LayoutParams(-1,-2));
        stepsValue=label("",16);steps=slider("档位数",root,stepsValue,LampSettings.MIN_STEPS,LampSettings.MAX_STEPS,current.steps(),v->v+" 档");
        volumeControl=new Switch(this);volumeControl.setText("音量键调节高度");volumeControl.setTextColor(theme.text);volumeControl.setTextSize(16);
        volumeControl.setPadding(0,gap,0,0);volumeControl.setChecked(current.volumeControl());root.addView(volumeControl);
        reversed=new Switch(this);reversed.setText("反转方向");reversed.setTextColor(theme.text);reversed.setTextSize(16);
        reversed.setPadding(0,gap,0,gap);reversed.setChecked(current.reversed());root.addView(reversed);
        reversed.setEnabled(volumeControl.isChecked());
        volumeControl.setOnCheckedChangeListener((b,checked)->reversed.setEnabled(checked));
        status=label("",14);root.addView(status);
        save=button("保存并连接");root.addView(save);save.setOnClickListener(v->commit());
        ScrollView scroll=new ScrollView(this);scroll.addView(root);scroll.setBackgroundColor(theme.surface);
        setContentView(scroll);
        watcher=state->{if(!saving)status.setText(describe(state));};
        lamp.watch(watcher);
    }
    /** The screen holds the link while visible, renewing every few seconds, and lets go when it leaves the screen. */
    private final android.os.Handler ui=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable renew=new Runnable(){@Override public void run(){lamp.hold(LampController.HOLD_SCREEN,LampController.SCREEN_HOLD_MS);ui.postDelayed(this,LampController.SCREEN_RENEW_MS);}};
    @Override protected void onStart(){super.onStart();ui.removeCallbacks(renew);renew.run();}
    @Override protected void onStop(){super.onStop();ui.removeCallbacks(renew);lamp.release(LampController.HOLD_SCREEN);}
    @Override protected void onResume(){super.onResume();refreshPermission();}
    @Override protected void onDestroy(){super.onDestroy();if(watcher!=null)lamp.unwatch(watcher);}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
        super.onRequestPermissionsResult(code,permissions,results);refreshPermission();lamp.hold(LampController.HOLD_SCREEN,LampController.SCREEN_HOLD_MS);
    }
    private void refreshPermission(){
        boolean connect=checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
        boolean scanning=checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED;
        permissionStatus.setText(connect&&scanning?"蓝牙权限已授予":"蓝牙权限未授予");
        grant.setVisibility(connect&&scanning?View.GONE:View.VISIBLE);
        scan.setEnabled(scanning);
    }
    private void startScan(){
        scan.setEnabled(false);status.setText("正在扫描");
        lamp.scan(8000,outcome->{
            scan.setEnabled(true);
            if(isFinishing()||isDestroyed())return;
            if(outcome.failure()!=0){status.setText("扫描失败 "+outcome.failure());return;}
            List<LampController.Found> all=outcome.devices();
            if(all.isEmpty()){status.setText(locationEnabled()?"未搜索到蓝牙设备":"未搜索到蓝牙设备，定位服务未开启");return;}
            java.util.ArrayList<LampController.Found> lamps=new java.util.ArrayList<>();
            for(LampController.Found device:all)if(device.matched())lamps.add(device);
            // Only the lamp controllers are offered; the whole list is a fallback so an unexpected name is still reachable.
            List<LampController.Found> devices=lamps.isEmpty()?all:List.copyOf(lamps);
            status.setText(lamps.isEmpty()?"未找到大灯，全部 "+all.size()+" 个设备":"大灯 "+lamps.size()+" 个");
            String[] items=new String[devices.size()];
            for(int i=0;i<devices.size();i++){
                LampController.Found device=devices.get(i);
                items[i]=(device.name().isEmpty()?"未命名":device.name())+"\n"+device.mac()+"   "+device.rssi()+" dBm";
            }
            new AlertDialog.Builder(this,theme.dark?android.R.style.Theme_Material_Dialog_Alert:android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setTitle("选择大灯").setItems(items,(d,which)->{mac.setText(devices.get(which).mac());status.setText(devices.get(which).name());})
                    .setNegativeButton("取消",null).show();
        });
    }
    /** Some builds return no scan results at all while the system location switch is off, whatever the permission says. */
    private boolean locationEnabled(){
        try{android.location.LocationManager manager=getSystemService(android.location.LocationManager.class);return manager==null||manager.isLocationEnabled();}
        catch(RuntimeException e){return true;}
    }
    private void commit(){
        String address=LampSettings.normalizeMac(mac.getText().toString());
        String secret=password.getText().toString().trim();
        if(address.isEmpty()){status.setText("蓝牙地址无效");return;}
        if(!TxLampProtocol.validPassword(secret)){status.setText("密码必须是 6 位数字");return;}
        mac.setText(address);
        LampSettings next=new LampSettings(address,secret,LampSettings.DEFAULT_SPEED,steps.getProgress()+LampSettings.MIN_STEPS,
                reversed.isChecked(),volumeControl.isChecked());
        lamp.save(next);lamp.hold(LampController.HOLD_SCREEN,LampController.SCREEN_HOLD_MS);
        saving=true;save.setEnabled(false);status.setText("正在认证");
        // The device answers the handshake within a couple of seconds; report whatever the link reached by then.
        status.postDelayed(()->{
            if(isFinishing()||isDestroyed())return;
            saving=false;save.setEnabled(true);
            LampState state=lamp.state();
            status.setText(state.ready()?"认证通过":describe(state));
        },9000);
    }
    /** The live line shows the remapped height once the link is up, so it matches the dashboard card, not the raw device value. */
    private String describe(LampState state){
        return state.ready()&&state.knownPosition()?"已连接 "+lamp.settings().displayPercent(state.position(),state.lowLimit(),state.highLimit())+"%":state.describe();
    }
    // ---------------------------------------------------------------- views
    private TextView label(String text,int size){
        TextView view=new TextView(this);view.setText(text);view.setTextSize(size);view.setTextColor(size>=20?theme.text:theme.secondary);
        view.setPadding(0,MirrorUi.dp(this,6),0,MirrorUi.dp(this,6));return view;
    }
    private TextView caption(String text){
        TextView view=new TextView(this);view.setText(text);view.setTextSize(13);view.setTextColor(theme.secondary);
        view.setPadding(0,MirrorUi.dp(this,12),0,MirrorUi.dp(this,4));return view;
    }
    private Button button(String text){
        Button view=new Button(this);view.setText(text);view.setAllCaps(false);theme.button(view,null);view.setTextSize(14);
        view.setMaxLines(1);view.setMinimumHeight(MirrorUi.dp(this,52));return view;
    }
    private EditText field(String value,int inputType,int length){
        int pad=MirrorUi.dp(this,12);
        EditText edit=new EditText(this);edit.setSingleLine(true);edit.setInputType(inputType);edit.setText(value);
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(length)});
        edit.setTextColor(theme.text);edit.setTextSize(18);edit.setBackgroundTintList(null);
        edit.setBackground(theme.background(this,theme.input,12,false));edit.setPadding(pad,pad,pad,pad);
        edit.setMinimumHeight(MirrorUi.dp(this,52));return edit;
    }
    private interface Describe{String of(int value);}
    private SeekBar slider(String name,LinearLayout parent,TextView value,int min,int max,int current,Describe describe){
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(caption(name),new LinearLayout.LayoutParams(0,-2,1));header.addView(value);
        parent.addView(header,new LinearLayout.LayoutParams(-1,-2));
        SeekBar bar=new SeekBar(this);bar.setMax(max-min);bar.setProgress(Math.max(0,Math.min(max-min,current-min)));
        value.setText(describe.of(bar.getProgress()+min));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar b,int progress,boolean user){value.setText(describe.of(progress+min));}
            @Override public void onStartTrackingTouch(SeekBar b){}
            @Override public void onStopTrackingTouch(SeekBar b){}
        });
        parent.addView(bar,new LinearLayout.LayoutParams(-1,-2));return bar;
    }
}
