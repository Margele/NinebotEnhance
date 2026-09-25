package dev.ichinomiya.ninebotenhance.hook;

import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * One list of the classes, methods and resources the module depends on, checked one by one against the builds in
 * {@link #VERSIONS}. Verified once the target application is attached, so an app update reports exactly which target went
 * missing instead of failing silently.
 * Method parameter patterns use canonical type names; "*" matches any type and "*.Name" any package with that simple name;
 * a null parameter list matches any overload of the name.
 */
public final class HookCatalog {
    public record Target(String kind,String owner,String member,String[] params,String purpose){
        public String label(){
            switch(kind){
                case "method":return simple(owner)+"#"+member+(params==null?"(…)":"("+String.join(",",Arrays.stream(params).map(HookCatalog::simple).toArray(String[]::new))+")");
                case "class":return simple(owner);
                default:return kind+":"+member;
            }
        }
    }
    public record Report(int total,List<String> missing){
        public boolean ok(){return missing.isEmpty();}
        public String text(){return ok()?"Hook 目标 "+total+"/"+total+" 可用":"Hook 目标 "+(total-missing.size())+"/"+total+" 可用，缺失："+String.join("、",missing);}
    }
    public static Target type(String owner,String purpose){return new Target("class",owner,"",null,purpose);}
    public static Target method(String owner,String member,String[] params,String purpose){return new Target("method",owner,member,params,purpose);}
    public static Target resource(String kind,String name,String purpose){return new Target(kind,"",name,null,purpose);}
    /**
     * Builds whose targets were verified individually; any other build keeps the frame replacement disabled. 6.10.11 ships the
     * business dex unpacked and its class set, member signatures, dependent layouts and ids are identical to 6.10.10. 6.10.12 ships the business dex under NetEase reinforcement, so its cn.ninebot.* class and member targets are confirmed only at runtime by verify(); its resources, layouts and ids match 6.10.11.
     */
    public static final Map<String,Long> VERSIONS=Map.of("6.10.10",610104038L,"6.10.11",610114116L,"6.10.12",610124146L);
    public static boolean compatible(String versionName,long versionCode){
        if(versionName==null)return false;Long code=VERSIONS.get(versionName);return code!=null&&code==versionCode;
    }
    public static String versions(){return String.join(" / ",new TreeSet<>(VERSIONS.keySet()));}

    public static final String DEVICE="cn.ninebot.library.bluetooth.dynamic.DynamicDevice",CLIENT="cn.ninebot.library.nbbluetooth.NbBluetoothClient",FUNCTION1="kotlin.jvm.functions.Function1";
    public static final String TYRE_PARSER="cn.ninebot.device.motor.thirdparts.TirePressureStateParser",DEVICE_MANAGER="cn.ninebot.device.DeviceManager";
    public static final String NAVI_MESSENGER="cn.ninebot.device.motor.navi.DashNaviDataMessenger",CRUISE_ACTIVITY="cn.ninebot.device.motor.navi.CruiseModeActivity";
    public static final String CAST_MANAGER="cn.ninebot.mapcapture.DeviceScreenCastManager",RTP_SENDER="cn.ninebot.mapcapture.NBBluetoothRtpSender";
    public static final String NAVIGATION_CARD="layout_detail_navigation_card",LOCATION_CARD="layout_detail_location_card";
    public static final List<Target> ALL=List.of(
        type(DEVICE,"蓝牙读取回复与指令分发"),
        method(DEVICE,"onResponse",new String[]{"*.NbFrame"},"所有蓝牙读取回复的必经点"),
        method(DEVICE,"intercept",new String[]{"*.Command"},"总线统计"),
        method(DEVICE,"sendCommand",new String[]{"java.lang.String","byte[]","boolean","java.lang.Integer",FUNCTION1},"胎压/电压主动读取、导航测试写入"),
        method(DEVICE,"hasCommand",new String[]{"java.lang.String"},"按车型配置过滤指令"),
        method(CLIENT,"getConnectedDevice",new String[0],"当前连接的车辆"),
        type(TYRE_PARSER,"胎压解析器"),
        method(TYRE_PARSER,"parseExtraFloat",null,"胎压字段"),
        method(TYRE_PARSER,"init",null,"绑定车辆身份"),
        type(DEVICE_MANAGER,"车辆管理器"),
        method(NAVI_MESSENGER+"$Companion","isPowerOn",null,"投屏前的开机检查"),
        method(NAVI_MESSENGER+"$Companion","setDashNaviTheme",null,"仪表昼夜主题"),
        method(CRUISE_ACTIVITY+"$Companion","open",null,"巡航页入口"),
        type(CAST_MANAGER,"原投屏管理器"),
        type(RTP_SENDER,"原蓝牙发送器"),
        type(StatisticsHooks.WIFI_SENDER,"原 Wi-Fi 发送器"),
        type(StatisticsHooks.UDP_SESSION,"原 RTP 会话"),
        type(StatisticsHooks.BLE_WRITER,"原蓝牙 RTP 写入"),
        type(StatisticsHooks.SEND_QUEUE,"原发送队列"),
        type(StatisticsHooks.ENCODE_SINKS[0],"原编码回调"),
        type(FeatureHooks.VIEW_HOLDER,"原设置项基类"),
        method(FeatureHooks.VIEW_HOLDER,"updateVisible",new String[]{"java.lang.Object"},"设置项可见性"),
        type(FeatureHooks.VIEW_MODELS,"原设置视图工厂"),
        type(FeatureHooks.VISIBILITY_STORE,"原按键卡片开关"),
        method(FeatureHooks.NAVIGATION_CARD,"updateCruiseViewState",new String[]{"boolean"},"原巡航按钮显示"),
        method(FeatureHooks.VISIBILITY_STORE,"isVisible",new String[]{"java.lang.String"},"按键卡片显示状态"),
        method(FeatureHooks.VIEW_MODELS,"generateView",new String[]{"android.view.ViewGroup","java.lang.String","java.lang.String","int","java.lang.String"},"车辆页卡片构建"),
        type("cn.ninebot.capture.VideoConfig","编码参数"),
        type("cn.ninebot.capture.encoder.LoopBitmapEncoder","编码循环"),
        type("cn.ninebot.capture.mpeg2.NbFFmpegFrameRecorder","FFmpeg 编码参数"),
        type("cn.ninebot.capture.CaptureClient","原采集入口"),
        type("cn.ninebot.capture.codec.BitmapToH264Encoder","原编码器"),
        resource("layout",NAVIGATION_CARD,"巡航入口所在卡片"),
        resource("layout",LOCATION_CARD,"页面扫描触发点"),
        resource("id","vMainContainer","卡片容器"),
        resource("id","ivCruise","原巡航按钮"),
        resource("id","layoutHistory","按钮行插入锚点"),
        resource("id","layoutNavigation","卡片结构校验"));

    /** Resolve every target; the class resolver may search several class loaders, the resource resolver returns 0 for unknown names. */
    public static Report verify(List<Target> targets,Function<String,Class<?>> classes,ToIntFunction<Target> resources){
        List<String> missing=new ArrayList<>();
        for(Target t:targets){
            boolean present;
            try{
                switch(t.kind()){
                    case "class":present=classes.apply(t.owner())!=null;break;
                    case "method":{Class<?> owner=classes.apply(t.owner());present=owner!=null&&hasMethod(owner,t.member(),t.params());break;}
                    default:present=resources.applyAsInt(t)!=0;
                }
            }catch(RuntimeException|LinkageError e){present=false;}
            if(!present)missing.add(t.label());
        }
        return new Report(targets.size(),missing);
    }
    private static boolean hasMethod(Class<?> owner,String name,String[] params){
        List<Method> candidates=new ArrayList<>();
        try{candidates.addAll(Arrays.asList(owner.getDeclaredMethods()));}catch(LinkageError ignored){}
        try{candidates.addAll(Arrays.asList(owner.getMethods()));}catch(LinkageError ignored){}
        for(Method m:candidates){
            if(!m.getName().equals(name))continue;
            if(params==null)return true;
            Class<?>[] actual=m.getParameterTypes();if(actual.length!=params.length)continue;
            boolean all=true;
            for(int i=0;i<actual.length&&all;i++)all=matches(actual[i],params[i]);
            if(all)return true;
        }
        return false;
    }
    private static boolean matches(Class<?> type,String pattern){
        if(pattern.equals("*"))return true;
        String canonical=type.getCanonicalName()==null?type.getName():type.getCanonicalName();
        return pattern.startsWith("*.")?canonical.endsWith(pattern.substring(1)):canonical.equals(pattern);
    }
    private static String simple(String name){int dot=name.lastIndexOf('.');return dot<0?name:name.substring(dot+1);}
    private HookCatalog(){}
}
