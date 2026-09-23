package dev.ichinomiya.ninebotenhance.ui;

import android.app.Activity;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.hardware.input.InputManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import dev.ichinomiya.ninebotenhance.core.TouchPanel;
import dev.ichinomiya.ninebotenhance.service.TouchPanelUsb;
import java.util.ArrayList;
import java.util.List;

/** Module-owned touch panel screen: pick an external touchscreen, choose its mounting rotation and bind it to the virtual display. */
public final class TouchSettingsActivity extends Activity implements InputManager.InputDeviceListener {
    public static final String PREFERENCES="touch_panel";
    private MirrorUi theme;private InputManager input;
    private RadioGroup devices,rotation;private CheckBox marks;private TextView status,usbStatus,empty;private Button grant;
    private final List<TouchPanel> found=new ArrayList<>();private TouchPanel selected=TouchPanel.NONE,saved=TouchPanel.NONE;
    private BroadcastReceiver usbAnswer;
    @Override protected void onCreate(Bundle state){
        boolean dark=getIntent().getBooleanExtra("dark",true);
        setTheme(dark?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);theme=new MirrorUi(dark);input=getSystemService(InputManager.class);
        saved=read(this);selected=saved;
        int pad=MirrorUi.dp(this,20),gap=MirrorUi.dp(this,12);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.surface);root.setForceDarkAllowed(false);root.setPadding(pad,pad,pad,pad);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            root.setPadding(pad+bars.left,pad+bars.top,pad+bars.right,pad+bars.bottom);return insets;});
        getWindow().setDecorFitsSystemWindows(false);
        root.addView(label("触摸屏管理",22));
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(caption("触摸屏"),new LinearLayout.LayoutParams(0,-2,1));
        Button refresh=button("刷新");refresh.setOnClickListener(v->refresh());
        header.addView(refresh,new LinearLayout.LayoutParams(MirrorUi.dp(this,92),MirrorUi.dp(this,44)));
        root.addView(header,new LinearLayout.LayoutParams(-1,-2));
        devices=new RadioGroup(this);devices.setOrientation(RadioGroup.VERTICAL);root.addView(devices,new LinearLayout.LayoutParams(-1,-2));
        empty=label("未发现外接触摸屏",14);root.addView(empty);
        devices.setOnCheckedChangeListener((g,id)->{
            int index=devices.indexOfChild(devices.findViewById(id));
            if(index>=0&&index<found.size()){selected=found.get(index).withRotation(selected.rotation());usbRefresh();}
        });
        root.addView(caption("旋转"));
        rotation=new RadioGroup(this);rotation.setOrientation(RadioGroup.HORIZONTAL);
        for(int i=0;i<TouchPanel.ROTATIONS;i++){
            RadioButton turn=radio(i*90+"°");rotation.addView(turn,new RadioGroup.LayoutParams(0,-2,1));
            if(i==saved.rotation())turn.setChecked(true);
        }
        root.addView(rotation,new LinearLayout.LayoutParams(-1,-2));
        marks=new CheckBox(this);marks.setText("显示触点");marks.setTextColor(theme.text);marks.setTextSize(15);
        marks.setButtonTintList(ColorStateList.valueOf(theme.accent));marks.setPadding(0,MirrorUi.dp(this,8),0,MirrorUi.dp(this,8));marks.setChecked(saved.marks());
        root.addView(marks,new LinearLayout.LayoutParams(-1,-2));
        usbStatus=label("",14);root.addView(usbStatus);
        grant=button("授予 USB 权限");root.addView(grant);grant.setVisibility(View.GONE);
        grant.setOnClickListener(v->{UsbDevice device=TouchPanelUsb.find(this,selected);if(device!=null)TouchPanelUsb.request(this,device);});
        status=label("",14);root.addView(status);
        LinearLayout actions=new LinearLayout(this);
        Button unbind=button("解除绑定"),save=button("保存");
        LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-2,1);left.setMarginEnd(gap);
        actions.addView(unbind,left);actions.addView(save,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams actionParams=new LinearLayout.LayoutParams(-1,-2);actionParams.topMargin=gap;root.addView(actions,actionParams);
        unbind.setOnClickListener(v->unbind());save.setOnClickListener(v->commit());
        ScrollView scroll=new ScrollView(this);scroll.addView(root);scroll.setBackgroundColor(theme.surface);
        setContentView(scroll);
        refresh();showSaved();
    }
    @Override protected void onResume(){
        super.onResume();input.registerInputDeviceListener(this,null);
        usbAnswer=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){usbRefresh();}};
        registerReceiver(usbAnswer,new IntentFilter(TouchPanelUsb.ACTION),Context.RECEIVER_NOT_EXPORTED);
        refresh();
    }
    @Override protected void onPause(){
        super.onPause();input.unregisterInputDeviceListener(this);
        if(usbAnswer!=null){try{unregisterReceiver(usbAnswer);}catch(RuntimeException ignored){}usbAnswer=null;}
    }
    @Override public void onInputDeviceAdded(int id){refresh();}
    @Override public void onInputDeviceRemoved(int id){refresh();}
    @Override public void onInputDeviceChanged(int id){refresh();}
    public static TouchPanel read(Context context){
        SharedPreferences p=context.getSharedPreferences(PREFERENCES,0);return TouchPanel.read(p::getInt,p::getString);
    }
    private void refresh(){
        found.clear();
        for(int id:input.getInputDeviceIds()){
            InputDevice device=input.getInputDevice(id);
            if(device==null||device.isVirtual()||!device.isExternal()||!device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN))continue;
            TouchPanel panel=new TouchPanel(device.getVendorId(),device.getProductId(),device.getName(),0,true,null);
            boolean duplicate=false;for(TouchPanel known:found)if(known.sameDevice(panel))duplicate=true;
            if(!duplicate)found.add(panel);
        }
        boolean savedPresent=false;for(TouchPanel panel:found)if(panel.sameDevice(saved))savedPresent=true;
        if(saved.bound()&&!savedPresent)found.add(saved);
        devices.setOnCheckedChangeListener(null);devices.removeAllViews();
        for(TouchPanel panel:found){
            RadioButton choice=radio(panel.label()+(panel.sameDevice(saved)&&!savedPresent?"（未连接）":""));
            devices.addView(choice,new RadioGroup.LayoutParams(-1,-2));
            if(panel.sameDevice(selected))choice.setChecked(true);
        }
        devices.setOnCheckedChangeListener((g,id)->{
            int index=devices.indexOfChild(devices.findViewById(id));
            if(index>=0&&index<found.size()){selected=found.get(index).withRotation(selected.rotation());usbRefresh();}
        });
        empty.setVisibility(found.isEmpty()?View.VISIBLE:View.GONE);
        usbRefresh();
    }
    private void usbRefresh(){
        UsbDevice device=TouchPanelUsb.find(this,selected);
        if(device==null){usbStatus.setText("");usbStatus.setVisibility(View.GONE);grant.setVisibility(View.GONE);return;}
        boolean permitted=TouchPanelUsb.permitted(this,device);
        usbStatus.setVisibility(View.VISIBLE);usbStatus.setText(permitted?"USB 权限已授予":"USB 权限未授予");
        grant.setVisibility(permitted?View.GONE:View.VISIBLE);
    }
    private int chosenRotation(){
        int checked=rotation.indexOfChild(rotation.findViewById(rotation.getCheckedRadioButtonId()));
        return checked<0?0:checked;
    }
    private void showSaved(){status.setText(saved.bound()?"已绑定 "+saved.label()+(saved.calibrated()?" · 已校准":""):"未绑定");}
    private void commit(){
        if(!selected.bound()){status.setText("请选择触摸屏");return;}
        // The calibration belongs to the device: it survives a rotation or marks change and goes with a different panel.
        TouchPanel next=selected.withRotation(chosenRotation()).withMarks(marks.isChecked()).withCalibration(selected.sameDevice(saved)?saved.calibration():null);
        SharedPreferences.Editor editor=getSharedPreferences(PREFERENCES,0).edit();
        next.write(editor::putInt,editor::putString);editor.apply();
        saved=next;selected=next;showSaved();
        UsbDevice device=TouchPanelUsb.find(this,next);
        if(device!=null&&!TouchPanelUsb.permitted(this,device))TouchPanelUsb.request(this,device);
        refresh();
    }
    private void unbind(){
        getSharedPreferences(PREFERENCES,0).edit().clear().apply();
        saved=TouchPanel.NONE;selected=TouchPanel.NONE;devices.clearCheck();showSaved();refresh();
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
}
