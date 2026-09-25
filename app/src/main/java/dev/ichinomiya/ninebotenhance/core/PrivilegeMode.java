package dev.ichinomiya.ninebotenhance.core;

/** The privileged backend that launches the virtual display daemon; the other picture sources need neither. */
public enum PrivilegeMode {
    ROOT, SHIZUKU;
    public static PrivilegeMode parse(String name) {
        try { return valueOf(name); } catch (Exception e) { throw new IllegalArgumentException("无效的授权方式"); }
    }
    /** A saved value; the old AUTO and NONE settings and anything unknown become the fallback. */
    public static PrivilegeMode read(String name, PrivilegeMode fallback) {
        try { return name == null ? fallback : valueOf(name); } catch (RuntimeException e) { return fallback; }
    }
    public boolean useShizuku(boolean authorized) {
        if (this == SHIZUKU && !authorized) throw new IllegalStateException("请先启动并授权 Shizuku / Sui");
        return this == SHIZUKU;
    }
}
