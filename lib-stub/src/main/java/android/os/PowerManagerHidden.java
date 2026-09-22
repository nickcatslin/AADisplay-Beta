package android.os;

import dev.rikka.tools.refine.RefineAs;

@RefineAs(PowerManager.class)
public class PowerManagerHidden {

    /** Go to sleep reason: requested by an application (PowerManager.GO_TO_SLEEP_REASON_APPLICATION). */
    public static final int GO_TO_SLEEP_REASON_APPLICATION = 2;

    /** Wake reason: requested by an application (PowerManager.WAKE_REASON_APPLICATION). */
    public static final int WAKE_REASON_APPLICATION = 2;

    public PowerManager.WakeLock newWakeLock(int levelAndFlags, String tag, int displayId) {
        throw new RuntimeException("Stub!");
    }

    /**
     * Puts the default display group to sleep. Other display groups (e.g. a virtual display
     * created with VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP) are not affected.
     */
    public void goToSleep(long time, int reason, int flags) {
        throw new RuntimeException("Stub!");
    }

    /** Wakes up the default display group. */
    public void wakeUp(long time, int reason, String details) {
        throw new RuntimeException("Stub!");
    }

}
