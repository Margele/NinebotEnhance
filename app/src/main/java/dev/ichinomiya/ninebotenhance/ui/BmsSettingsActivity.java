package dev.ichinomiya.ninebotenhance.ui;

import android.Manifest;
import android.app.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import dev.ichinomiya.ninebotenhance.bms.BmsController;
import dev.ichinomiya.ninebotenhance.core.BmsSettings;
import dev.ichinomiya.ninebotenhance.core.BmsState;
import dev.ichinomiya.ninebotenhance.core.LampSettings;
import java.util.List;
import java.util.function.Consumer;

/** Module-owned BMS screen: scan for a board, bind it, choose which protocol it speaks and how often it is polled; the link runs in the module process. */
public final class BmsSettingsActivity extends Activity {
    private MirrorUi theme;private BmsController bms;
    private EditText mac;private SeekBar poll;private TextView status,permissionStatus,pollValue;private Button grant,scan,save;
    private RadioGroup protocols;private Consumer<BmsState> watcher;private boolean saving;
    @Override protected void onCreate(Bundle saved){
        boolean dark=getIntent().getBooleanExtra("dark",true);
        setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(saved);theme=new MirrorUi(dark);bms=BmsController.get(this);
        BmsSettings current=bms.settings();
        int pad=MirrorUi.dp(this,20),gap=MirrorUi.dp(this,12);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.surface);root.setForceDarkAllowed(false);root.setPadding(pad,pad,pad,pad);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());
            root.setPadding(pad+bars.left,pad+bars.top,pad+bars.right,pad+bars.bottom);return insets;});
        getWindow().setDecorFitsSystemWindows(false);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        root.addView(label("BMS 管理",22));
        permissionStatus=label("",14);root.addView(permissionStatus);
        grant=button("授予蓝牙权限");root.addView(grant);
        grant.setOnClickListener(v->requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN},8));
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
        root.addView(caption("协议"));
        protocols=new RadioGroup(this);protocols.setOrientation(RadioGroup.VERTICAL);
        for(int id=0;id<BmsSettings.PROTOCOL_NAMES.length;id++){
            RadioButton choice=radio(BmsSettings.PROTOCOL_NAMES[id]);protocols.addView(choice,new RadioGroup.LayoutParams(-1,-2));
            if(id==current.protocol())choice.setChecked(true);
        }
        root.addView(protocols,new LinearLayout.LayoutParams(-1,-2));
        pollValue=label("",16);
        int step=BmsSettings.POLL_STEP_MS;
        poll=slider("轮询间隔",root,pollValue,BmsSettings.MIN_POLL_MS/step,BmsSettings.MAX_POLL_MS/step,current.pollMs()/step,v->(v%2==0?String.valueOf(v/2):v/2+".5")+" 秒");
        status=label("",14);root.addView(status);
        save=button("保存并连接");root.addView(save);save.setOnClickListener(v->commit());
        ScrollView scroll=new ScrollView(this);scroll.addView(root);scroll.setBackgroundColor(theme.surface);
        setContentView(scroll);
        watcher=state->{if(!saving)status.setText(state.describe());};
        bms.watch(watcher);
    }
    /** The screen holds the link while visible, renewing every few seconds, and lets go when it leaves the screen. */
    private final android.os.Handler ui=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable renew=new Runnable(){@Override public void run(){bms.hold(BmsController.HOLD_SCREEN,BmsController.SCREEN_HOLD_MS);bms.poll();ui.postDelayed(this,BmsController.SCREEN_RENEW_MS);}};
    @Override protected void onStart(){super.onStart();ui.removeCallbacks(renew);renew.run();}
    @Override protected void onStop(){super.onStop();ui.removeCallbacks(renew);bms.release(BmsController.HOLD_SCREEN);}
    @Override protected void onResume(){super.onResume();refreshPermission();}
    @Override protected void onDestroy(){super.onDestroy();if(watcher!=null)bms.unwatch(watcher);}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
        super.onRequestPermissionsResult(code,permissions,results);refreshPermission();bms.hold(BmsController.HOLD_SCREEN,BmsController.SCREEN_HOLD_MS);
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
        bms.scan(8000,outcome->{
            scan.setEnabled(true);
            if(isFinishing()||isDestroyed())return;
            if(outcome.failure()!=0){status.setText("扫描失败 "+outcome.failure());return;}
            List<BmsController.Found> all=outcome.devices();
            if(all.isEmpty()){status.setText("未搜索到蓝牙设备");return;}
            java.util.ArrayList<BmsController.Found> boards=new java.util.ArrayList<>();
            for(BmsController.Found device:all)if(device.matched())boards.add(device);
            List<BmsController.Found> devices=boards.isEmpty()?all:List.copyOf(boards);
            status.setText(boards.isEmpty()?"未找到 BMS，全部 "+all.size()+" 个设备":"BMS "+boards.size()+" 个");
            String[] items=new String[devices.size()];
            for(int i=0;i<devices.size();i++){
                BmsController.Found device=devices.get(i);
                items[i]=(device.name().isEmpty()?"未命名":device.name())+"\n"+device.mac()+"   "+device.rssi()+" dBm";
            }
            new AlertDialog.Builder(this,theme.dark?android.R.style.Theme_Material_Dialog_Alert:android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setTitle("选择 BMS").setItems(items,(d,which)->{mac.setText(devices.get(which).mac());status.setText(devices.get(which).name());})
                    .setNegativeButton("取消",null).show();
        });
    }
    private void commit(){
        String address=LampSettings.normalizeMac(mac.getText().toString());
        if(address.isEmpty()){status.setText("蓝牙地址无效");return;}
        mac.setText(address);
        BmsSettings next=new BmsSettings(address,poll.getProgress()*BmsSettings.POLL_STEP_MS,protocols.getCheckedRadioButtonId()<0?BmsSettings.PROTOCOL_AUTO:protocols.indexOfChild(protocols.findViewById(protocols.getCheckedRadioButtonId())));
        bms.save(next);bms.hold(BmsController.HOLD_SCREEN,BmsController.SCREEN_HOLD_MS);
        saving=true;save.setEnabled(false);status.setText("正在连接");
        status.postDelayed(()->{
            if(isFinishing()||isDestroyed())return;
            saving=false;save.setEnabled(true);status.setText(bms.state().describe());
        },10000);
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
    private RadioButton radio(String text){
        RadioButton button=new RadioButton(this);button.setId(View.generateViewId());button.setText(text);button.setTextColor(theme.text);button.setTextSize(15);
        button.setButtonTintList(ColorStateList.valueOf(theme.accent));button.setPadding(0,MirrorUi.dp(this,6),0,MirrorUi.dp(this,6));return button;
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
        SeekBar bar=new SeekBar(this);bar.setMin(min);bar.setMax(max);bar.setProgress(Math.max(min,Math.min(max,current)));
        value.setText(describe.of(bar.getProgress()));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar b,int progress,boolean user){value.setText(describe.of(progress));}
            @Override public void onStartTrackingTouch(SeekBar b){}
            @Override public void onStopTrackingTouch(SeekBar b){}
        });
        parent.addView(bar,new LinearLayout.LayoutParams(-1,-2));return bar;
    }
}
