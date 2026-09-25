package dev.ichinomiya.ninebotenhance.notification;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.*;
import android.os.*;
import android.telephony.*;
import java.util.*;

/** Reads battery/network indicators only. Never reads numbers, subscriber identifiers, SSID or location. */
public final class PhoneStatus {
    private final Context context; private volatile Bundle cached=new Bundle();private long sampled=-10000;
    private final Handler worker;private volatile boolean pending;
    private TelephonyCallback displayCallback;private TelephonyManager displayManager;private int displaySubscription=SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    private volatile int overrideType=TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE;
    public PhoneStatus(Context c){context=c.getApplicationContext();HandlerThread thread=new HandlerThread("Ninebot-PhoneStatus",android.os.Process.THREAD_PRIORITY_BACKGROUND);thread.start();worker=new Handler(thread.getLooper());cached.putInt("battery",-1);}
    public synchronized Bundle snapshot() {
        long now=SystemClock.elapsedRealtime();
        if(!pending&&now-sampled>=1000){sampled=now;pending=true;worker.post(()->{try{cached=sample();}finally{pending=false;}});}
        return new Bundle(cached);
    }
    private Bundle sample() {
        Bundle b=new Bundle();b.putInt("battery",-1);
        try{Intent battery=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));if(battery!=null){int level=battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=battery.getIntExtra(BatteryManager.EXTRA_SCALE,100);if(level>=0&&scale>0)b.putInt("battery",Math.min(100,level*100/scale));int status=battery.getIntExtra(BatteryManager.EXTRA_STATUS,-1);b.putBoolean("charging",status==BatteryManager.BATTERY_STATUS_CHARGING||status==BatteryManager.BATTERY_STATUS_FULL&&battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0);}}catch(RuntimeException ignored){}
        try{ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);if(cm!=null)for(Network n:cm.getAllNetworks()){NetworkCapabilities cap=cm.getNetworkCapabilities(n);if(cap!=null&&cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)){b.putBoolean("wifi",true);break;}}}catch(RuntimeException ignored){}
        boolean cellular=false;
        try{ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);Network active=cm==null?null:cm.getActiveNetwork();NetworkCapabilities cap=active==null?null:cm.getNetworkCapabilities(active);cellular=cap!=null&&cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)&&!cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);}catch(RuntimeException ignored){}
        boolean granted=context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)==PackageManager.PERMISSION_GRANTED;
        b.putBoolean("phone_permission",granted);
        // Without Wi-Fi the HUD shows the mobile generation in the same slot; it needs the phone-state grant like the signal bars.
        b.putString("network",!b.getBoolean("wifi")&&cellular&&granted?generation():"");
        ArrayList<Integer> slots=new ArrayList<>(),levels=new ArrayList<>();
        if(granted)try{
            SubscriptionManager sm=context.getSystemService(SubscriptionManager.class);TelephonyManager tm=context.getSystemService(TelephonyManager.class);
            List<SubscriptionInfo> subscriptions=sm==null?null:sm.getActiveSubscriptionInfoList();
            if(subscriptions!=null&&tm!=null){subscriptions=new ArrayList<>(subscriptions);subscriptions.sort(Comparator.comparingInt(SubscriptionInfo::getSimSlotIndex));for(SubscriptionInfo info:subscriptions){if(slots.size()==2)break;if(info.getSimSlotIndex()<0)continue;int level=-1;try{SignalStrength strength=tm.createForSubscriptionId(info.getSubscriptionId()).getSignalStrength();if(strength!=null)level=strength.getLevel();}catch(RuntimeException ignored){}slots.add(info.getSimSlotIndex()+1);levels.add(level);}}
        }catch(RuntimeException ignored){b.putBoolean("phone_permission",false);}
        b.putIntegerArrayList("slots",slots);b.putIntegerArrayList("levels",levels);return b;
    }
    /** Generation of the default data SIM: 5G (including NSA via the display-info override), 4G, 3G or 2G; empty when unknown. */
    private String generation(){
        try{
            TelephonyManager tm=context.getSystemService(TelephonyManager.class);if(tm==null)return "";
            int subscription=SubscriptionManager.getDefaultDataSubscriptionId();
            if(subscription!=SubscriptionManager.INVALID_SUBSCRIPTION_ID)tm=tm.createForSubscriptionId(subscription);
            listenDisplayInfo(tm,subscription);
            int override=overrideType;
            if(override==TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA||override==TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED)return "5G";
            switch(tm.getDataNetworkType()){
                case TelephonyManager.NETWORK_TYPE_NR:return "5G";
                case TelephonyManager.NETWORK_TYPE_LTE:case TelephonyManager.NETWORK_TYPE_IWLAN:return "4G";
                case TelephonyManager.NETWORK_TYPE_HSPAP:case TelephonyManager.NETWORK_TYPE_HSPA:case TelephonyManager.NETWORK_TYPE_HSUPA:case TelephonyManager.NETWORK_TYPE_HSDPA:
                case TelephonyManager.NETWORK_TYPE_UMTS:case TelephonyManager.NETWORK_TYPE_EVDO_0:case TelephonyManager.NETWORK_TYPE_EVDO_A:case TelephonyManager.NETWORK_TYPE_EVDO_B:
                case TelephonyManager.NETWORK_TYPE_EHRPD:case TelephonyManager.NETWORK_TYPE_TD_SCDMA:return "3G";
                case TelephonyManager.NETWORK_TYPE_GPRS:case TelephonyManager.NETWORK_TYPE_EDGE:case TelephonyManager.NETWORK_TYPE_CDMA:case TelephonyManager.NETWORK_TYPE_1xRTT:case TelephonyManager.NETWORK_TYPE_GSM:return "2G";
                default:return "";
            }
        }catch(RuntimeException e){return "";}
    }
    /** One display-info listener on the worker thread for the current data SIM; re-registered when that SIM changes. */
    private void listenDisplayInfo(TelephonyManager tm,int subscription){
        if(android.os.Build.VERSION.SDK_INT<31)return; // TelephonyCallback exists from Android 12; the generation label stays empty before it.
        if(displayCallback!=null&&displaySubscription==subscription)return;
        try{
            if(displayCallback!=null&&displayManager!=null)displayManager.unregisterTelephonyCallback(displayCallback);
            displayCallback=null;overrideType=TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE;
            TelephonyCallback callback=new DisplayInfoCallback();
            tm.registerTelephonyCallback(worker::post,callback);
            displayCallback=callback;displayManager=tm;displaySubscription=subscription;
        }catch(RuntimeException ignored){displayCallback=null;}
    }
    private final class DisplayInfoCallback extends TelephonyCallback implements TelephonyCallback.DisplayInfoListener{
        @Override public void onDisplayInfoChanged(TelephonyDisplayInfo info){overrideType=info==null?TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE:info.getOverrideNetworkType();}
    }
}
