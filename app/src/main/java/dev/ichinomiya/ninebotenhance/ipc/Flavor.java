package dev.ichinomiya.ninebotenhance.ipc;

/**
 * What differs between the LSPosed module and the build patched into Ninebot's own APK. The patched build compiles the same sources
 * with its own copy of this one file.
 */
public final class Flavor {
    /** False here: the module is its own package and LSPosed installs the hooks. */
    public static final boolean EMBEDDED = false;
    /** The package that declares the module's service, providers and activities. */
    public static final String MODULE = "dev.ichinomiya.ninebotenhance";
    /** Prefix of the provider authorities; the two builds never share one, so both can be installed. */
    public static final String AUTHORITY = MODULE;
    public static final String RELEASES_URL = "https://github.com/Margele/NinebotEnhance/releases";
    public static final String LATEST_API = "https://api.github.com/repos/Margele/NinebotEnhance/releases/latest";
    /** Where the licence texts and the notice sit inside the APK that carries the module. */
    public static final String RESOURCES = "";
    /** Small icon of the screen-capture notification. */
    public static int notificationIcon() { return dev.ichinomiya.ninebotenhance.R.drawable.ic_mirror; }
    private Flavor() {}
}
