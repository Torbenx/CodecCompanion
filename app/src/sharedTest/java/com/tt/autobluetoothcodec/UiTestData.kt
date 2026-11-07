package com.tt.autobluetoothcodec

/** Recorded actions for the UI tests, which run on the JVM and on the phones. The example data is in [SampleData]. */
internal object UiTestData {
    /** Records what the main screen asked for. */
    class RecordingActions : MainScreenActions {
        val calls = ArrayList<String>()

        override fun requestPermission() { calls.add("requestPermission") }
        override fun openAppSettings() { calls.add("openAppSettings") }
        override fun enableBluetooth() { calls.add("enableBluetooth") }
        override fun openBluetoothSettings() { calls.add("openBluetoothSettings") }
        override fun openLog() { calls.add("openLog") }
        override fun openAbout() { calls.add("openAbout") }
        override fun setCodec(address: String, config: CodecConfig) { calls.add("setCodec $address ${config.describe()}") }
        override fun savePreferred(address: String, config: CodecConfig) { calls.add("savePreferred $address ${config.describe()}") }
        override fun restorePreferred(address: String, config: CodecConfig) { calls.add("restorePreferred $address ${config.describe()}") }
        override fun deletePreferred(address: String) { calls.add("deletePreferred $address") }
        override fun undoDelete(address: String) { calls.add("undoDelete $address") }
        override fun setPreferred(address: String, config: CodecConfig) { calls.add("setPreferred $address ${config.describe()}") }
        override fun link(address: String) { calls.add("link $address") }
        override fun unlink(address: String) { calls.add("unlink $address") }
    }
}
