package dev.ichinomiya.ninebotenhance.core;

/**
 * Recognizes the system refusing input injection: the daemon's identity lacks INJECT_EVENTS. On Xiaomi / HyperOS the developer
 * option "USB debugging (security settings)" grants it to shell; the module shows that guidance once when this matches.
 */
public final class InputDenial {
    public static boolean matches(String error) {
        if (error == null) return false;
        return error.contains("INJECT_EVENTS") || error.contains("Injecting input events") || error.contains("injectInputEventToTarget");
    }
    private InputDenial() {}
}
