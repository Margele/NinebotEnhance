package dev.ichinomiya.ninebotenhance.hook;

import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import dev.ichinomiya.ninebotenhance.core.AmapNavi;
import dev.ichinomiya.ninebotenhance.core.EventCensus;
import dev.ichinomiya.ninebotenhance.core.NaviDestination;
import dev.ichinomiya.ninebotenhance.core.NaviUpdate;
import dev.ichinomiya.ninebotenhance.navi.NaviAppClient;
import dev.ichinomiya.ninebotenhance.navi.NaviApps;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Observe-only probes inside the navigation apps, one process at a time; nothing in the apps is changed and nothing is sent
 * to the vehicle from here. Each app exposes its turn-by-turn data at a stable, non-obfuscated seam:
 * <ul>
 * <li>AMap: the native guidance engine broadcasts JSON events to every registered {@code NaviEventReceiver}. The module
 * instantiates AMap's own no-argument receiver class ({@code HiCarXbusEmitter$a}), registers it with {@code NaviManager} and
 * intercepts its two callbacks, so it sees every event without adding classes to the app. {@code notifyOngoingCard} (the
 * JS-to-Java HiCar card feed) is logged as well. {@code createAndInitScene} is observed for the travel mode (NaviSceneType)
 * and event 59 for the route end, which together let the module re-plan the same route after the system relaunched the
 * map activity on the virtual display.</li>
 * <li>Baidu: the guidance JNI wrapper {@code JNIGuidanceControl} fills Bundles with the simple guide info, remaining route,
 * current road and assist distance; the Bundles are logged after each call.</li>
 * <li>Tencent: implementations of {@code TNaviCarCallback} receive road signs, turn icon, segment distance and remaining
 * time / distance; their {@code onUpdate*} methods are logged.</li>
 * </ul>
 * Logging is bounded by {@link EventCensus}; a census line per minute lists what was seen.
 */
public final class NaviAppHooks {
    public static final String AMAP_MANAGER="com.autonavi.jni.eyrie.amap.tbt.NaviManager",AMAP_RECEIVER="com.autonavi.jni.eyrie.amap.tbt.NaviEventReceiver";
    public static final String AMAP_TAP="com.amap.bundle.drive.carprojection.protocol.hicar.app.xbus.HiCarXbusEmitter$a";
    public static final String AMAP_CARD="com.amap.bundle.drive.carprojection.module.AjxModuleCarProjection";
    public static final String BAIDU_GUIDANCE="com.baidu.navisdk.jni.nativeif.JNIGuidanceControl";
    public static final String[] BAIDU_GETTERS={"getSimpleMapInfo","getRemainRouteInfo","getCurRoadName","getAssistRemainDist"};
    /** Car callbacks (turn icon, road signs, segment distance, remaining time / distance) and the base callback they extend. */
    public static final String TENCENT_CALLBACK="com.tencent.map.navisdk.api.adapt.TNaviCarCallback",TENCENT_BASE_CALLBACK="com.tencent.map.navisdk.api.adapt.TNaviCallback";
    public static final long CENSUS_INTERVAL_MS=60000,REGISTER_RETRY_MS=3000;public static final int REGISTER_ATTEMPTS=60;
    private final HookHost module;private final String pkg;private final NaviAppClient client;
    private final Set<Executable> hooked=ConcurrentHashMap.newKeySet();private final Set<Class<?>> seen=ConcurrentHashMap.newKeySet();
    private final Set<String> logged=ConcurrentHashMap.newKeySet();
    private final EventCensus census=new EventCensus();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadLocal<Boolean> examining=ThreadLocal.withInitial(()->false);
    private final Set<ClassLoader> loaders=ConcurrentHashMap.newKeySet();
    private volatile Class<?> managerClass,receiverClass,tapClass;
    private final Set<Class<?>> tencentCallbacks=ConcurrentHashMap.newKeySet();
    private volatile Object tap;private volatile boolean registered;private int registerAttempts,seedRounds;
    /** Last NaviSceneType passed to createAndInitScene (9 motorbike, 2 drive, 1 ride, 4 walk); 0 until seen. */
    private volatile int scene;
    private volatile long lastCensus;
    private final boolean mainProcess;
    public NaviAppHooks(HookHost module,String pkg,String process){this.module=module;this.pkg=pkg;client=new NaviAppClient(process==null?pkg:process);mainProcess=process==null||process.equals(pkg);}
    public String label(){return NaviApps.label(pkg);}
    public void install(ClassLoader loader){
        if(loader!=null)loaders.add(loader);
        // The guidance engine only lives in the main process; helper processes (location, crash dump, widgets) are left alone.
        if(!mainProcess||!logged.add("install"))return;
        client.report("NAVI hooks installing for "+label());
        try{
            module.hook(Application.class.getDeclaredMethod("attach",Context.class)).intercept(chain->{
                Object result=chain.proceed();
                try{
                    Context context=(Context)chain.getArg(0);
                    if(pkg.equals(context.getPackageName())){
                        client.attach(context);loaders.add(context.getClassLoader());
                        client.report("NAVI attached "+label()+" process="+client.process());
                        main.post(this::seedScan);
                    }
                }catch(Throwable e){client.report("NAVI attach failed "+e.getClass().getSimpleName());}
                return result;
            });
        }catch(Throwable e){client.report("NAVI attach hook unavailable "+e.getClass().getSimpleName());}
        try{
            module.hook(ClassLoader.class.getDeclaredMethod("loadClass",String.class,boolean.class)).intercept(chain->{
                Object result=chain.proceed();
                if(!examining.get()&&result instanceof Class<?>&&interesting((String)chain.getArg(0)))inspect((Class<?>)result);
                return result;
            });
        }catch(Throwable e){client.report("NAVI class observer unavailable "+e.getClass().getSimpleName());}
    }
    public void ready(ClassLoader loader){if(!mainProcess)return;if(loader!=null)loaders.add(loader);main.post(this::seedScan);}
    private String[] seeds(){
        switch(pkg){
            case NaviApps.AMAP:return new String[]{AMAP_MANAGER,AMAP_RECEIVER,AMAP_TAP,AMAP_CARD};
            case NaviApps.BAIDU:return new String[]{BAIDU_GUIDANCE};
            case NaviApps.TENCENT:return new String[]{TENCENT_CALLBACK,TENCENT_BASE_CALLBACK};
            default:return new String[0];
        }
    }
    private boolean interesting(String name){
        switch(pkg){
            case NaviApps.AMAP:return name.equals(AMAP_MANAGER)||name.equals(AMAP_RECEIVER)||name.equals(AMAP_TAP)||name.equals(AMAP_CARD);
            case NaviApps.BAIDU:return name.equals(BAIDU_GUIDANCE);
            case NaviApps.TENCENT:return name.startsWith("com.tencent.map.");
            default:return false;
        }
    }
    /** Named seams are resolved eagerly a few times after attach; lazily loaded ones are caught by the class observer. */
    private void seedScan(){
        examining.set(true);
        try{for(ClassLoader loader:new ArrayList<>(loaders))for(String name:seeds()){try{inspectUnchecked(Class.forName(name,false,loader));}catch(Throwable ignored){}}}
        finally{examining.set(false);}
        if(++seedRounds<10)main.postDelayed(this::seedScan,3000);
    }
    private void inspect(Class<?> type){examining.set(true);try{inspectUnchecked(type);}catch(Throwable e){client.report("NAVI inspect "+type.getName()+" "+e.getClass().getSimpleName());}finally{examining.set(false);}}
    private void inspectUnchecked(Class<?> type){
        String name=type.getName();
        switch(pkg){
            case NaviApps.AMAP:
                if(!seen.add(type))return;
                if(name.equals(AMAP_MANAGER)){managerClass=type;hookManager(type);registerTap();}
                else if(name.equals(AMAP_RECEIVER))receiverClass=type;
                else if(name.equals(AMAP_TAP)){tapClass=type;hookTap(type);registerTap();}
                else if(name.equals(AMAP_CARD))hookCard(type);
                break;
            case NaviApps.BAIDU:
                if(!seen.add(type))return;
                if(name.equals(BAIDU_GUIDANCE))hookBaidu(type);
                break;
            case NaviApps.TENCENT:
                if(name.equals(TENCENT_CALLBACK)||name.equals(TENCENT_BASE_CALLBACK)){if(seen.add(type)){tencentCallbacks.add(type);client.report("NAVI tencent callback interface seen: "+describe(type));}return;}
                if(type.isInterface()||tencentCallbacks.isEmpty())return;
                boolean implementation=false;for(Class<?> callback:tencentCallbacks)implementation|=callback.isAssignableFrom(type);
                if(!implementation||!seen.add(type))return;
                hookTencent(type);
                break;
            default:break;
        }
    }
    private static String describe(Class<?> type){
        StringBuilder b=new StringBuilder();for(Method m:type.getDeclaredMethods()){if(b.length()>0)b.append(' ');b.append(m.getName()).append('/').append(m.getParameterCount());}
        return EventCensus.truncate(b.toString(),600);
    }
    // ---- AMap ----
    private void hookManager(Class<?> type){
        for(Method method:type.getDeclaredMethods()){
            String name=method.getName();
            if(name.equals("createAndInitScene")&&method.getParameterCount()==5&&method.getParameterTypes()[0]==int.class&&hooked.add(method)){
                try{module.hook(method).intercept(chain->{
                    try{int value=(Integer)chain.getArg(0);if(value!=scene){scene=value;client.report("NAVI amap scene "+value);}}catch(Throwable ignored){}
                    return chain.proceed();
                });client.report("NAVI amap observer NaviManager.createAndInitScene");}
                catch(Throwable e){hooked.remove(method);client.report("NAVI amap hook createAndInitScene unavailable "+e.getClass().getSimpleName());}
                continue;
            }
            if(!(name.equals("registerEventReceiver")||name.equals("unregisterEventReceiver"))||method.getParameterCount()!=1||!hooked.add(method))continue;
            try{module.hook(method).intercept(chain->{
                Object result=chain.proceed();
                try{
                    Object receiver=chain.getArg(0);
                    if(receiver!=tap){client.report("NAVI amap "+name+" "+(receiver==null?"null":receiver.getClass().getName()));if(name.startsWith("register"))main.post(this::registerTap);}
                }catch(Throwable ignored){}
                return result;
            });client.report("NAVI amap observer NaviManager."+name);}
            catch(Throwable e){hooked.remove(method);client.report("NAVI amap hook "+name+" unavailable "+e.getClass().getSimpleName());}
        }
    }
    private void hookTap(Class<?> type){
        for(Method method:type.getDeclaredMethods()){
            String name=method.getName();
            boolean serial=name.equals("onNaviEvent")&&method.getParameterCount()==1,raw=name.equals("onNaviNonSerialEvent")&&method.getParameterCount()==2;
            if(!serial&&!raw||!hooked.add(method))continue;
            try{module.hook(method).intercept(chain->{
                if(chain.getThisObject()!=tap)return chain.proceed();
                try{if(serial)onEvent((String)chain.getArg(0));else onRawEvent((String)chain.getArg(0),(byte[])chain.getArg(1));}
                catch(Throwable e){if(logged.add("event "+e.getClass().getSimpleName()))client.report("NAVI amap event handler "+e.getClass().getSimpleName()+": "+e.getMessage());}
                return null;
            });client.report("NAVI amap tap hook "+name);}
            catch(Throwable e){hooked.remove(method);client.report("NAVI amap tap hook "+name+" unavailable "+e.getClass().getSimpleName());}
        }
    }
    private void hookCard(Class<?> type){
        for(Method method:type.getDeclaredMethods()){
            if(!method.getName().equals("notifyOngoingCard")||method.getParameterCount()!=1||!hooked.add(method))continue;
            try{module.hook(method).intercept(chain->{
                try{String value=String.valueOf(chain.getArg(0));if(census.shouldLog("amap:card"))client.report("NAVI amap card "+EventCensus.truncate(value,700));}catch(Throwable ignored){}
                return chain.proceed();
            });client.report("NAVI amap observer notifyOngoingCard");}
            catch(Throwable e){hooked.remove(method);client.report("NAVI amap card hook unavailable "+e.getClass().getSimpleName());}
        }
    }
    /** Register the module's receiver instance once the engine classes are present; retried while the native side is not ready. */
    private synchronized void registerTap(){
        if(registered)return;
        Class<?> manager=managerClass,receiver=receiverClass,tapType=tapClass;
        if(manager==null||tapType==null)return;
        if(receiver==null){try{receiver=Class.forName(AMAP_RECEIVER,false,manager.getClassLoader());receiverClass=receiver;}catch(Throwable e){return;}}
        try{
            if(tap==null)tap=tapType.getConstructor().newInstance();
            Object instance=manager.getMethod("getInstance").invoke(null);
            if(instance==null)throw new IllegalStateException("NaviManager.getInstance() null");
            manager.getMethod("registerEventReceiver",receiver).invoke(instance,tap);
            registered=true;client.report("NAVI amap tap registered after "+registerAttempts+" retries");
        }catch(Throwable e){
            Throwable cause=e instanceof InvocationTargetException&&e.getCause()!=null?e.getCause():e;
            if(++registerAttempts<=REGISTER_ATTEMPTS){if(registerAttempts<=2||registerAttempts%10==0)client.report("NAVI amap tap register attempt "+registerAttempts+" "+cause.getClass().getSimpleName()+": "+cause.getMessage());main.postDelayed(this::registerTap,REGISTER_RETRY_MS);}
            else if(logged.add("register gave up"))client.report("NAVI amap tap registration gave up: "+cause.getClass().getSimpleName());
        }
    }
    private void onEvent(String json){
        String type="?";
        if(json!=null){int at=json.indexOf("\"eventType\"");if(at>=0){int colon=json.indexOf(':',at);if(colon>0){int end=colon+1;while(end<json.length()&&(json.charAt(end)==' '||json.charAt(end)=='-'||Character.isDigit(json.charAt(end))))end++;type=json.substring(colon+1,end).trim();}}}
        if(census.shouldLog("amap:"+type))client.report("NAVI amap event "+type+" "+EventCensus.truncate(json,700));
        if("1".equals(type)){NaviUpdate update=AmapNavi.parse(json);if(update!=null)client.publish(update);}
        else if("59".equals(type)){NaviDestination destination=AmapNavi.parseDestination(json,scene);if(destination!=null)client.publishDestination(destination);}
        censusTick();
    }
    private void onRawEvent(String json,byte[] data){
        if(census.shouldLog("amap:raw"))client.report("NAVI amap raw "+EventCensus.truncate(json,300)+" bytes="+(data==null?-1:data.length));
        censusTick();
    }
    // ---- Baidu ----
    private void hookBaidu(Class<?> type){
        Set<String> wanted=new HashSet<>(Arrays.asList(BAIDU_GETTERS));
        for(Method method:type.getDeclaredMethods()){
            String name=method.getName();
            if(!wanted.contains(name)||method.getParameterCount()!=1||method.getParameterTypes()[0]!=Bundle.class||!hooked.add(method))continue;
            try{module.hook(method).intercept(chain->{
                Object result=chain.proceed();
                try{if(Boolean.TRUE.equals(result)&&census.shouldLog("baidu:"+name))client.report("NAVI baidu "+name+" "+bundle((Bundle)chain.getArg(0)));}catch(Throwable ignored){}
                censusTick();return result;
            });client.report("NAVI baidu observer JNIGuidanceControl."+name);}
            catch(Throwable e){hooked.remove(method);client.report("NAVI baidu hook "+name+" unavailable "+e.getClass().getSimpleName());}
        }
    }
    private static String bundle(Bundle bundle){
        if(bundle==null)return "null";List<String> keys=new ArrayList<>(bundle.keySet());Collections.sort(keys);StringBuilder b=new StringBuilder("{");
        for(String key:keys){Object value=bundle.get(key);if(b.length()>1)b.append(", ");b.append(key).append('=').append(EventCensus.truncate(value instanceof int[]?Arrays.toString((int[])value):String.valueOf(value),80));}
        return EventCensus.truncate(b.append('}').toString(),900);
    }
    // ---- Tencent ----
    private void hookTencent(Class<?> type){
        int count=0;
        for(Method method:type.getDeclaredMethods()){
            String name=method.getName();
            if(!name.startsWith("onUpdate")&&!name.startsWith("onShow")&&!name.startsWith("onHide")&&!name.startsWith("onArrive")&&!name.startsWith("onRecompute")||Modifier.isAbstract(method.getModifiers())||!hooked.add(method))continue;
            try{module.hook(method).intercept(chain->{
                try{if(census.shouldLog("tencent:"+name))client.report("NAVI tencent "+name+" "+args(chain.getArgs().toArray()));}catch(Throwable ignored){}
                censusTick();return chain.proceed();
            });count++;}
            catch(Throwable e){hooked.remove(method);}
        }
        client.report("NAVI tencent callback "+type.getName()+" hooked "+count+" methods");
    }
    private static String args(Object[] values){
        StringBuilder b=new StringBuilder();
        for(Object value:values){if(b.length()>0)b.append(" | ");b.append(value==null?"null":value.getClass().getSimpleName()+":"+EventCensus.truncate(String.valueOf(value),200));}
        return EventCensus.truncate(b.toString(),700);
    }
    private void censusTick(){
        long now=SystemClock.elapsedRealtime();
        if(now-lastCensus<CENSUS_INTERVAL_MS)return;lastCensus=now;
        String summary=census.census();if(!summary.isEmpty())client.report("NAVI "+label()+" census "+CENSUS_INTERVAL_MS/1000+"s "+summary);
    }
}
