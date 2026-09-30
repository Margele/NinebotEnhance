package dev.ichinomiya.ninebotenhance.hook;

import java.lang.reflect.Executable;
import java.util.List;

/**
 * The hooking calls the observers need, so the same code runs under LSPosed ({@link XposedHost}) and inside a Ninebot APK that was
 * patched ahead of time, where a hook is a trampoline written into the method or a redirected call site.
 */
public interface HookHost {
    interface Chain {
        Object getThisObject();
        List<Object> getArgs();
        Object getArg(int index);
        Object proceed() throws Throwable;
        Object proceed(Object[] args) throws Throwable;
    }
    interface Hooker { Object intercept(Chain chain) throws Throwable; }
    interface Builder { void intercept(Hooker hooker); }
    /** Throws when this host cannot hook the executable. */
    Builder hook(Executable executable);
    void log(int priority, String tag, String message, Throwable error);
    /** Whether Application.attach and ClassLoader.loadClass can be hooked; a host that cannot reports attachment and its classes itself. */
    boolean framework();
    /** Names the host in the load line of the module log. */
    String describe();
}
