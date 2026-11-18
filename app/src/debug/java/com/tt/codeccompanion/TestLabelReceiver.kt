package com.tt.codeccompanion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Lets tests mark their steps in the event log. Only part of debug builds, send it explicitly:
 *
 *     adb shell am broadcast -n com.tt.codeccompanion/.TestLabelReceiver --es label "step 1"
 *
 * A freshly installed app that was never started is stopped and doesn't receive broadcasts, add
 * `-f 0x20` (FLAG_INCLUDE_STOPPED_PACKAGES) in that case.
 */
class TestLabelReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_LABEL = "label"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra(EXTRA_LABEL) ?: return
        val log = EventLog.get(context)
        log.log(EventType.TEST_LABEL, message = label)
        // A test may read the file right after the broadcast completes
        val result = goAsync()
        log.flush { result.finish() }
    }
}
