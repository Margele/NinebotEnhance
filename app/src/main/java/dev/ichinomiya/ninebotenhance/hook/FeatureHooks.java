package dev.ichinomiya.ninebotenhance.hook;

import android.view.ViewGroup;
import dev.ichinomiya.ninebotenhance.client.FrameClient;
import dev.ichinomiya.ninebotenhance.core.HiddenFeatures;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forces selected Ninebot features on.
 * <p>
 * Settings rows: every dynamic settings row receives its visibility, default or evaluated from the vehicle's feature bits,
 * through {@code DynamicViewHolder.updateVisible(Object)}; for rows whose configuration title is on the unlock list the argument
 * becomes {@code true}.
 * <p>
 * Hard-key card: the vehicle page builds each card through {@code DynamicViewModels.Companion.generateView(parent, type, json,
 * deviceId, deviceTag)}. Observing that call yields the page's factory, device id and tag, which the card injector reuses to
 * build Ninebot's own {@code ext_meter_virtual_key} card. Nothing is written to the vehicle by the module: taps on that card and
 * toggles on unlocked rows are Ninebot's own writes, and the firmware may ignore a feature it never advertised.
 */
public final class FeatureHooks {
    public static final String VIEW_HOLDER="cn.ninebot.library.bluetooth.dynamic.viewHolder.DynamicViewHolder";
    public static final String VIEW_MODELS="cn.ninebot.device.dynamic.DynamicViewModels$Companion";
    /** Ninebot's per-vehicle switch for the hard-key card (SharedPreferences, default off); the card hides itself while it is off. */
    public static final String VISIBILITY_STORE="cn.ninebot.device.motor.manager.HardkeyRemoteControlVisibilityStore";
    /** The vehicle-page navigation card; updateCruiseViewState(boolean) shows or hides Ninebot's own cruise button. */
    public static final String NAVIGATION_CARD="cn.ninebot.device.motor.viewHolder.NavigationCardViewHolder";
    /** setDashNaviTheme(boolean isNight, NbBluetoothDevice) lives here; Ninebot calls it with the phone's dark mode. */
    public static final String NAVI_MESSENGER_COMPANION=HookCatalog.NAVI_MESSENGER+"$Companion";
    private final HookHost module;
    private final FrameClient frames;
    private final Set<Method> hooked=ConcurrentHashMap.newKeySet();
    private final Set<String> reported=ConcurrentHashMap.newKeySet();
    public FeatureHooks(HookHost module,FrameClient frames){this.module=module;this.frames=frames;}
    public static boolean interesting(String name){return name.equals(VIEW_HOLDER)||name.equals(VIEW_MODELS)||name.equals(VISIBILITY_STORE)||name.equals(NAVIGATION_CARD);}
    public int hookCount(){return hooked.size();}
    public void inspect(Class<?> type) {
        if(type.getName().equals(VIEW_HOLDER))installVisibility(type);
        else if(type.getName().equals(VIEW_MODELS))installFactory(type);
        else if(type.getName().equals(VISIBILITY_STORE))installStore(type);
        else if(type.getName().equals(NAVIGATION_CARD))installCruise(type);
    }
    private void installVisibility(Class<?> type) {
        Method getConfig=null;
        try { getConfig=type.getMethod("getConfig"); } catch(Throwable ignored) {}
        if(getConfig==null){frames.report("FEATURE getConfig unavailable");return;}
        final Method config=getConfig;
        for(Method method:type.getDeclaredMethods()) {
            if(!method.getName().equals("updateVisible")||method.getParameterCount()!=1||method.getParameterTypes()[0]!=Object.class||!hooked.add(method))continue;
            try { module.hook(method).intercept(chain->{
                try {
                    HiddenFeatures unlock=frames.hiddenFeatures();
                    if(unlock.throttle()) {
                        String title=title(config,chain.getThisObject());
                        if(unlock.forces(title)&&!Boolean.TRUE.equals(chain.getArg(0))) {
                            if(reported.add(title))frames.report("FEATURE forced visible "+title);
                            Object[] args=chain.getArgs().toArray();args[0]=Boolean.TRUE;return chain.proceed(args);
                        }
                    }
                } catch(RuntimeException ignored) {}
                return chain.proceed();
            }); frames.report("FEATURE observer "+method.toGenericString()); }
            catch(Throwable e){hooked.remove(method);frames.report("FEATURE observer unavailable "+e.getClass().getSimpleName());}
        }
    }
    /** Records the page's view factory and device identity; the original call is untouched. */
    private void installFactory(Class<?> type) {
        for(Method method:type.getDeclaredMethods()) {
            Class<?>[] types=method.getParameterTypes();
            if(!method.getName().equals("generateView")||types.length!=5||types[0]!=ViewGroup.class||types[1]!=String.class||types[2]!=String.class||types[3]!=int.class||types[4]!=String.class||!hooked.add(method))continue;
            try { module.hook(method).intercept(chain->{
                Object result=chain.proceed();
                try {
                    Object tag=chain.getArg(4);Object id=chain.getArg(3);
                    if(tag instanceof String&&!((String)tag).isEmpty()&&id instanceof Integer)
                        frames.dynamicViewFactory(new FrameClient.DynamicViewFactory(chain.getThisObject(),method,(Integer)id,(String)tag));
                } catch(RuntimeException ignored) {}
                return result;
            }); frames.report("FEATURE observer "+method.toGenericString()); }
            catch(Throwable e){hooked.remove(method);frames.report("FEATURE observer unavailable "+e.getClass().getSimpleName());}
        }
    }
    /** The card's own switch reads as on while the unlock is on; Ninebot's stored preference is not modified. */
    private void installStore(Class<?> type) {
        for(Method method:type.getDeclaredMethods()) {
            if(!method.getName().equals("isVisible")||method.getParameterCount()!=1||method.getReturnType()!=boolean.class||!hooked.add(method))continue;
            try { module.hook(method).intercept(chain->{
                Object result=chain.proceed();
                try { if(frames.hiddenFeatures().hardkey())return Boolean.TRUE; } catch(RuntimeException ignored) {}
                return result;
            }); frames.report("FEATURE observer "+method.toGenericString()); }
            catch(Throwable e){hooked.remove(method);frames.report("FEATURE observer unavailable "+e.getClass().getSimpleName());}
        }
    }
    /** The original cruise button stays visible while the unlock is on; its own click listener and checks are untouched. */
    private void installCruise(Class<?> type) {
        for(Method method:type.getDeclaredMethods()) {
            if(!method.getName().equals("updateCruiseViewState")||method.getParameterCount()!=1||method.getParameterTypes()[0]!=boolean.class||!hooked.add(method))continue;
            try { module.hook(method).intercept(chain->{
                try {
                    if(frames.hiddenFeatures().cruise()&&!Boolean.TRUE.equals(chain.getArg(0))) {
                        if(reported.add("cruise"))frames.report("FEATURE forced visible cruise button");
                        Object[] args=chain.getArgs().toArray();args[0]=Boolean.TRUE;return chain.proceed(args);
                    }
                } catch(RuntimeException ignored) {}
                return chain.proceed();
            }); frames.report("FEATURE observer "+method.toGenericString()); }
            catch(Throwable e){hooked.remove(method);frames.report("FEATURE observer unavailable "+e.getClass().getSimpleName());}
        }
    }
    /** Ninebot's day/night flag for the dashboard carries the module's chosen theme instead of the phone's dark mode; command, device and timing stay Ninebot's. */
    public void installTheme(Class<?> type) {
        for(Method method:type.getDeclaredMethods()) {
            if(!method.getName().equals("setDashNaviTheme")||method.getParameterCount()!=2||method.getParameterTypes()[0]!=boolean.class||!hooked.add(method))continue;
            try { module.hook(method).intercept(chain->{
                try {
                    boolean dark=frames.dashboardDark();
                    if(!Boolean.valueOf(dark).equals(chain.getArg(0))) {
                        frames.report("THEME Ninebot setDashNaviTheme "+chain.getArg(0)+" -> "+dark);
                        Object[] args=chain.getArgs().toArray();args[0]=dark;return chain.proceed(args);
                    }
                    frames.report("THEME Ninebot setDashNaviTheme "+dark);
                } catch(RuntimeException ignored) {}
                return chain.proceed();
            }); frames.report("FEATURE observer "+method.toGenericString()); }
            catch(Throwable e){hooked.remove(method);frames.report("FEATURE observer unavailable "+e.getClass().getSimpleName());}
        }
    }
    /** The raw title key of the row's configuration ("string.xxx"); reflection only, no target toString. */
    private static String title(Method getConfig,Object holder) {
        try {
            Object config=getConfig.invoke(holder);if(config==null)return null;
            Method getTitle=config.getClass().getMethod("getTitle");Object value=getTitle.invoke(config);
            return value instanceof String?(String)value:null;
        } catch(ReflectiveOperationException|RuntimeException e) { return null; }
    }
}
