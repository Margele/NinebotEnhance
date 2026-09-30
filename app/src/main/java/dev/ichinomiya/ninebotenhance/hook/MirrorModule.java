package dev.ichinomiya.ninebotenhance.hook;

import dev.ichinomiya.ninebotenhance.ipc.Flavor;
import dev.ichinomiya.ninebotenhance.ipc.Protocol;
import dev.ichinomiya.ninebotenhance.navi.NaviApps;
import dev.ichinomiya.ninebotenhance.platform.ModuleResources;

import io.github.libxposed.api.XposedModule;

/**
 * API 101 entry, compatible with subsequent API versions. No legacy Xposed API is mixed in. The work is in {@link NinebotHooks}
 * and {@link NaviAppHooks}; this class only hands them LSPosed as their {@link HookHost}.
 */
public final class MirrorModule extends XposedModule {
    private final HookHost host = new XposedHost(this);
    private NinebotHooks ninebot;
    private NaviAppHooks naviApps;
    private String process;

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        process = param.getProcessName(); ModuleResources.initialize(getModuleApplicationInfo().sourceDir);
    }
    /**
     * Ninebot runs helper processes (":pushcore" and others); only the main process shows the vehicle page and needs the module.
     * A patched Ninebot APK carries its hooks in its own code, so there LSPosed only serves the navigation apps.
     */
    private boolean hooksNinebot() { return !Flavor.EMBEDDED && (process == null || process.equals(Protocol.TARGET)); }
    @Override public void onPackageLoaded(PackageLoadedParam param) {
        if (Protocol.TARGET.equals(param.getPackageName())) { if (hooksNinebot()) ninebot().install(param.getDefaultClassLoader()); }
        else if (NaviApps.supported(param.getPackageName())) naviApps(param.getPackageName()).install(param.getDefaultClassLoader());
    }
    @Override public void onPackageReady(PackageReadyParam param) {
        if (NaviApps.supported(param.getPackageName())) { naviApps(param.getPackageName()).ready(param.getClassLoader()); return; }
        if (!Protocol.TARGET.equals(param.getPackageName()) || !hooksNinebot()) return;
        ninebot().install(param.getClassLoader());
    }
    private synchronized NinebotHooks ninebot() {
        if (ninebot == null) ninebot = new NinebotHooks(host, process == null ? Protocol.TARGET : process);
        return ninebot;
    }
    /** Navigation apps get their own observe-only probe; the Ninebot hooks are never installed there. */
    private synchronized NaviAppHooks naviApps(String pkg) {
        if (naviApps == null) naviApps = new NaviAppHooks(host, pkg, process);
        return naviApps;
    }
}
