package com.tt.autobluetoothcodec.snippet;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Turns Bluetooth on or off for the tests on phones where the adb shell isn't allowed to and there's
 * no su, like the Galaxy A50 on Android 11. Runs in the snippet's own process with its
 * BLUETOOTH_ADMIN permission, which lets apps switch Bluetooth up to API 30, see
 * lib/device_state.py:
 *
 *   am broadcast -n com.tt.autobluetoothcodec.snippet/.BluetoothSwitch --ez on true
 *
 * The result code is 1 if the adapter accepted the request and 2 if it didn't, with the reason as
 * the result data. It stays 0 if nothing received the broadcast. Only senders with DUMP, like the
 * shell, may send it. It's Java because the snippet APK has no Kotlin runtime.
 */
public final class BluetoothSwitch extends BroadcastReceiver {
    public static final String EXTRA_ON = "on";
    public static final int RESULT_ACCEPTED = 1;
    public static final int RESULT_FAILED = 2;

    @Override
    @SuppressWarnings("deprecation") // enable() and disable() are what the shell's svc uses too
    public void onReceive(Context context, Intent intent) {
        if (!intent.hasExtra(EXTRA_ON)) {
            setResult(RESULT_FAILED, "missing --ez " + EXTRA_ON, null);
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            setResult(RESULT_FAILED, "no Bluetooth adapter", null);
            return;
        }
        boolean on = intent.getBooleanExtra(EXTRA_ON, false);
        boolean accepted = on ? adapter.enable() : adapter.disable();
        setResult(accepted ? RESULT_ACCEPTED : RESULT_FAILED, accepted ? null : "the adapter refused", null);
    }
}
