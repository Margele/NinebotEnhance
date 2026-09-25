package dev.ichinomiya.ninebotenhance.core;

/** Exact selected backend only. A lost Root connection must be checked before deciding it is denied. */
public final class StartPermission {
    public enum Backend { NONE, ROOT, SHIZUKU, MEDIA_PROJECTION, DRAW }
    public static Backend select(PictureSource source, PrivilegeMode mode, boolean shizukuReady, boolean rootReady) {
        if (source == null || mode == null) throw new IllegalArgumentException("Missing picture source or privilege mode");
        if (source == PictureSource.CAST) return Backend.MEDIA_PROJECTION;
        if (source == PictureSource.DRAW) return Backend.DRAW;
        if (mode == PrivilegeMode.SHIZUKU) return shizukuReady ? Backend.SHIZUKU : Backend.NONE;
        return rootReady ? Backend.ROOT : Backend.NONE;
    }
    public static boolean needsRootCheck(PictureSource source, PrivilegeMode mode, boolean shizukuReady, boolean rootReady) {
        return select(source, mode, shizukuReady, rootReady) == Backend.NONE && mode == PrivilegeMode.ROOT;
    }
    /** One user-initiated preflight, including asynchronous connection recovery and cancellation. */
    public static final class Check {
        public enum Result { STALE, WAIT, START, SETTINGS, TIMEOUT }
        private long generation, deadline;
        private boolean active;
        public long begin(long now) { active = true; deadline = now + 50000; return ++generation; }
        public boolean active() { return active; }
        public boolean owns(long value) { return active && generation == value; }
        public void cancel() { active = false; generation++; }
        public Result accept(long value, boolean allowed, boolean pending, long now) {
            if (!owns(value)) return Result.STALE;
            Result result = now >= deadline ? Result.TIMEOUT : allowed ? Result.START : pending ? Result.WAIT : Result.SETTINGS;
            if (result != Result.WAIT) active = false;
            return result;
        }
    }
    private StartPermission() {}
}
