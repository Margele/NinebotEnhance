package dev.ichinomiya.ninebotenhance.hook;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Executable;
import java.util.List;

/** LSPosed as a {@link HookHost}: every call goes straight to the libxposed API. */
final class XposedHost implements HookHost {
    private final XposedModule module;
    XposedHost(XposedModule module) { this.module = module; }
    @Override public Builder hook(Executable executable) {
        XposedInterface.HookBuilder builder = module.hook(executable);
        return hooker -> builder.intercept(chain -> hooker.intercept(new Chain() {
            @Override public Object getThisObject() { return chain.getThisObject(); }
            @Override public List<Object> getArgs() { return chain.getArgs(); }
            @Override public Object getArg(int index) { return chain.getArg(index); }
            @Override public Object proceed() throws Throwable { return chain.proceed(); }
            @Override public Object proceed(Object[] args) throws Throwable { return chain.proceed(args); }
        }));
    }
    @Override public void log(int priority, String tag, String message, Throwable error) { module.log(priority, tag, message, error); }
    @Override public boolean framework() { return true; }
    @Override public String describe() { return "API=" + module.getApiVersion(); }
}
