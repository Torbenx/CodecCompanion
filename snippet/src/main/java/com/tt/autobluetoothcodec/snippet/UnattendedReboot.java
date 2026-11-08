package com.tt.autobluetoothcodec.snippet;

import android.os.Build;
import android.os.IBinder;

/**
 * Prepares the SIM cards for an unattended reboot, so they unlock without their PIN after it. Runs
 * as the shell with app_process, not in the app's process, see lib/sim_helper.py:
 *
 *   CLASSPATH=<snippet apk> app_process / com.tt.autobluetoothcodec.snippet.UnattendedReboot
 *
 * Prints the result of ITelephony.prepareForUnattendedReboot: 0 success, 1 a SIM's PIN isn't
 * stored, 2 error. The reboot must follow within 20 s. It's Java because the snippet APK has no
 * Kotlin runtime, the app's is left out of it and app_process doesn't load the app.
 */
public final class UnattendedReboot {
    private UnattendedReboot() {
    }

    public static void main(String[] args) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            throw new UnsupportedOperationException("unattended reboots need API 31, this is " + Build.VERSION.SDK_INT);
        }
        // Hidden APIs, which app_process may use
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class)
                .invoke(null, "phone");
        if (binder == null) {
            throw new IllegalStateException("no phone service");
        }
        Object telephony = Class.forName("com.android.internal.telephony.ITelephony$Stub")
                .getMethod("asInterface", IBinder.class)
                .invoke(null, binder);
        // From the interface: its proxy class is private
        int result = (int) Class.forName("com.android.internal.telephony.ITelephony")
                .getMethod("prepareForUnattendedReboot")
                .invoke(telephony);
        System.out.println(result);
    }
}
