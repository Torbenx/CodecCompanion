package com.tt.autobluetoothcodec

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.util.TreeMap

/** Whether the app has the Nearby devices permission, and whether it may still ask for it. */
internal enum class PermissionState { GRANTED, DENIED, PERMANENTLY_DENIED }

/** A connected device as the main screen shows it. */
internal data class DeviceData(
    val address: String,
    val name: String,
    val androidCodecConfig: AndroidCodecConfig?,
    // null if the codec is unknown to this app or no codec status is available
    val codecConfig: CodecConfig?,
    val configMasks: ArrayList<A2dpProfileUtil.ConfigMask>,
    // false if the Bluetooth stack refused to report the codec status
    val statusReadable: Boolean,
    val associationRequired: Boolean,
    val associated: Boolean,
    val preferredConfig: CodecConfig?,
    // Why the codec status couldn't be read, null if it was read
    val readError: String? = null,
    // The codec being applied, null while no codec change is pending
    val targetConfig: CodecConfig? = null,
) {
    /**
     * What the card shows as the device's codec: the codec being applied, with the options it leaves to the system as
     * reported, otherwise the reported codec. Choosing a value changes this one, so quick taps build on each other.
     */
    val shownConfig: CodecConfig? get() = targetConfig?.withSystemChoices(codecConfig) ?: codecConfig
}

/** A saved device as the Saved devices list shows it, see [SavedDeviceStore]. */
internal data class SavedDeviceData(
    val address: String,
    val name: String,
    val preferredConfig: CodecConfig,
    // The values it offered when it was last read, empty if they never were
    val capabilities: List<A2dpProfileUtil.ConfigMask>,
    // Milliseconds since the epoch, null if unknown
    val lastConnected: Long?,
    val connected: Boolean,
)

/** Everything the main screen shows, provided by [MainActivity]. */
internal data class MainScreenState(
    val permissionState: PermissionState,
    val devices: List<DeviceData>,
    val canAssociate: Boolean,
    // The device a link is being requested for, null if none
    val linkingAddress: String?,
    val showDevelopmentOptions: Boolean,
    val bluetoothEnabled: Boolean,
    // Whether empty devices mean none are connected, right after Bluetooth turned on the profile isn't connected yet
    val devicesKnown: Boolean,
    // The connected ones first, then by when they were last connected
    val savedDevices: List<SavedDeviceData>,
)

/** What the main screen's controls do, implemented by [MainActivity]. */
internal interface MainScreenActions {
    fun requestPermission()
    fun openAppSettings()
    fun enableBluetooth()
    fun openBluetoothSettings()
    fun openLog()
    fun openAbout()
    fun setCodec(address: String, config: CodecConfig)
    fun savePreferred(address: String, config: CodecConfig)
    fun restorePreferred(address: String, config: CodecConfig)
    fun deletePreferred(address: String)
    /** Saves the device again as it was before [deletePreferred], unless it was saved again meanwhile. */
    fun undoDelete(address: String)
    /** Changes the preferred codec of a saved device, connected or not, without applying it. */
    fun setPreferred(address: String, config: CodecConfig)
    fun link(address: String)
    fun unlink(address: String)
}

class MainActivity : AppActivity(), BluetoothProfile.ServiceListener {
    companion object {
        private const val TAG = "MainActivity"
        private const val COMPANION_TAG = "CompanionAssociation"

        // How many polls with Bluetooth on the profile may take to connect before there just are no devices
        private const val PROFILE_WAIT_POLLS = 3

        // Kept per process, so the activity being recreated doesn't log the same state again
        private var loggedPermissionState: PermissionState? = null
        private val readFailures = HashSet<String>()

        /** Forgets the states logged by this process, for tests. */
        @VisibleForTesting
        fun resetForTest() {
            loggedPermissionState = null
            readFailures.clear()
        }

        private fun showFailure(context: Context, resolution: EventType) {
            val text = when (resolution) {
                EventType.SECURITY_EXCEPTION -> "Bluetooth didn't allow changing the codec"
                EventType.ERROR -> "Changing the codec failed"
                else -> return
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    // Development options are only shown in debuggable builds
    private val showDevelopmentOptions by lazy {
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private val log by lazy { EventLog.get(this) }
    private val applier by lazy { CodecApplier.get(this) }

    private var permissionState by mutableStateOf(PermissionState.DENIED)

    private val actions = object : MainScreenActions {
        override fun requestPermission() = requestBluetoothPermission()
        override fun openAppSettings() = this@MainActivity.openAppSettings()
        override fun enableBluetooth() = this@MainActivity.enableBluetooth()
        override fun openBluetoothSettings() = this@MainActivity.openBluetoothSettings()
        override fun openLog() = startActivity(Intent(this@MainActivity, LogActivity::class.java))
        override fun openAbout() = startActivity(Intent(this@MainActivity, AboutActivity::class.java))
        override fun setCodec(address: String, config: CodecConfig) = setCodecConfig(remoteDevice(address), config)
        override fun savePreferred(address: String, config: CodecConfig) = savePreferredConfig(remoteDevice(address), config)
        override fun restorePreferred(address: String, config: CodecConfig) = restorePreferredConfig(remoteDevice(address), config)
        override fun deletePreferred(address: String) = deletePreferredConfig(remoteDevice(address))
        override fun undoDelete(address: String) = undoDeletePreferred(remoteDevice(address))
        override fun setPreferred(address: String, config: CodecConfig) = changePreferredConfig(remoteDevice(address), config)
        override fun link(address: String) = linkDevice(remoteDevice(address))
        override fun unlink(address: String) = unlinkDevice(remoteDevice(address))
    }

    /** The device the main screen shows with [address], only shown devices have one. */
    private fun remoteDevice(address: String): BluetoothDevice =
        getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            updatePermissionState()
        } else if (Build.VERSION.SDK_INT >= 31
            && !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)) {
            // Denied without a rationale means the system won't show the permission dialog again
            permissionState = PermissionState.PERMANENTLY_DENIED
        } else {
            permissionState = PermissionState.DENIED
        }
        logPermissionState()
    }

    // Started for a result, since the system's dialog has to name the app asking
    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pullBluetoothState()
    }

    private val associationLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        Log.d(COMPANION_TAG, "result code for association request: " + result.resultCode + when (result.resultCode) { RESULT_OK -> " (ok)" else -> " (not ok)"})
        // Since API 33 onAssociationCreated finishes a successful request, before that only the result is reported
        if (result.resultCode == RESULT_OK && Build.VERSION.SDK_INT < 33) {
            finishAssociation(success = true, message = null)
        } else if (result.resultCode != RESULT_OK) {
            finishAssociation(success = false, message = null)
        }
        pullBluetoothState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Starts loading the log in the background, merging the events logged before the unlock.
        // The screen only logs on its own once a device is connected
        EventLog.get(this)

        handler = Handler(mainLooper)
        CompanionPresence.syncAll(this)

        updatePermissionState()
        // After a configuration change the result of a pending request is delivered to this instance
        if (permissionState != PermissionState.GRANTED && savedInstanceState == null) {
            requestBluetoothPermission()
        }

        setContent {
            AppTheme {
                MainScreen(
                    MainScreenState(
                        permissionState = permissionState,
                        devices = connectedDevices,
                        canAssociate = PermissionUtil.canAssociate(this),
                        linkingAddress = linkingAddress,
                        showDevelopmentOptions = showDevelopmentOptions,
                        bluetoothEnabled = bluetoothEnabled,
                        devicesKnown = devicesKnown,
                        savedDevices = savedDevices
                    ),
                    actions
                )
            }
        }

        lifecycleScope.launch {
            // Shows the codec as soon as an apply starts or ends, instead of on the next poll
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                applier.pending.collect { pullBluetoothState() }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        a2dpProfile?.let { closeProfileProxy(it) }
        a2dpProfile = null
    }

    private fun updatePermissionState() {
        if (PermissionUtil.testPermission(this)) {
            permissionState = PermissionState.GRANTED
            connectProfile()
        } else if (permissionState == PermissionState.GRANTED) {
            permissionState = PermissionState.DENIED
        }
        logPermissionState()
    }

    /** Logs a change of the permission state, the first state seen by the process isn't a change. */
    private fun logPermissionState() {
        val previous = loggedPermissionState
        loggedPermissionState = permissionState
        if (previous != null && previous != permissionState)
            log.log(EventType.PERMISSION_CHANGED, message = permissionState.name.lowercase().replace('_', ' '))
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun enableBluetooth() {
        try {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "can't ask to turn on Bluetooth", e)
            Toast.makeText(this, "Couldn't turn on Bluetooth", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openBluetoothSettings() {
        try {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "can't open the Bluetooth settings", e)
            Toast.makeText(this, "Couldn't open the Bluetooth settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun connectProfile() {
        if (profileRequested)
            return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) {
            Log.w(TAG, "device has no Bluetooth adapter")
            return
        }
        profileRequested = adapter.getProfileProxy(this, this, BluetoothProfile.A2DP)
    }

    private fun closeProfileProxy(profile: BluetoothA2dp) {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter?.closeProfileProxy(BluetoothProfile.A2DP, profile)
    }

    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
        if (isDestroyed) {
            (proxy as? BluetoothA2dp)?.let { closeProfileProxy(it) }
            return
        }
        a2dpProfile = proxy as BluetoothA2dp
        pullBluetoothState()
    }

    override fun onServiceDisconnected(profile: Int) {
        a2dpProfile = null
        pullBluetoothState()
    }

    private var linkingAddress by mutableStateOf<String?>(null)
    private val associationInProgress get() = linkingAddress != null
    private var onAssociated: (() -> Unit)? = null
    private var onAssociationFailed: ((String?) -> Unit)? = null

    private fun requestAssociation(device: BluetoothDevice, onSuccess: () -> Unit, onFailure: (String?) -> Unit) {
        if (associationInProgress) {
            onFailure("association request already in progress")
            return
        }
        if (!PermissionUtil.canAssociate(this)) {
            onFailure("companion association not supported")
            return
        }

        val companionManager = getSystemService(CompanionDeviceManager::class.java)
        val filter = BluetoothDeviceFilter.Builder().setAddress(device.address).build()
        val request = AssociationRequest.Builder().setSingleDevice(true).addDeviceFilter(filter).build()
        linkingAddress = device.address
        onAssociated = {
            CompanionPresence.update(this, device)
            onSuccess()
        }
        onAssociationFailed = onFailure
        if (Build.VERSION.SDK_INT < 33) {
            // AssociationInfo and the callbacks using it only exist since API 33
            companionManager.associate(request, object: CompanionDeviceManager.Callback() {
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onDeviceFound(intentSender: IntentSender) {
                    Log.d(COMPANION_TAG, "device found, requesting association...")
                    associationLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                }

                override fun onFailure(error: CharSequence?) {
                    Log.d(COMPANION_TAG, "companion association failed: $error")
                    finishAssociation(success = false, message = error)
                }
            }, handler)
            return
        }
        companionManager.associate(request, mainExecutor, object: CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                Log.d(COMPANION_TAG, "device found, requesting association...")
                associationLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }

            override fun onAssociationCreated(info: AssociationInfo) {
                Log.d(COMPANION_TAG, "companion association created")
                finishAssociation(success = true, message = null)
            }

            override fun onFailure(error: CharSequence?) {
                Log.d(COMPANION_TAG, "companion association failed: $error")
                finishAssociation(success = false, message = error)
            }
        })
    }

    private fun finishAssociation(success: Boolean, message: CharSequence?) {
        if (!associationInProgress)
            return
        linkingAddress = null
        val action = onAssociated
        val failureAction = onAssociationFailed
        onAssociated = null
        onAssociationFailed = null
        if (success) {
            action?.invoke()
        } else {
            val text = "Device wasn't linked" + (message?.let { ": $it" } ?: "")
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
            failureAction?.invoke(message?.toString())
        }
    }

    /** Requests an association as part of [operation], which is resolved if it fails. */
    private fun associateFor(device: BluetoothDevice, operation: EventLog.Operation, onSuccess: () -> Unit) {
        operation.log(EventType.ASSOCIATION_REQUESTED)
        requestAssociation(device, onSuccess) { operation.resolve(EventType.ASSOCIATION_FAILED, message = it) }
    }

    private fun linkDevice(device: BluetoothDevice) {
        val operation = log.begin(EventType.USER_LINK_DEVICE, LogDevice.of(device))
        requestAssociation(
            device,
            onSuccess = {
                operation.resolve(EventType.ASSOCIATION_CREATED)
                pullBluetoothState()
            },
            onFailure = { operation.resolve(EventType.ASSOCIATION_FAILED, message = it) }
        )
    }

    private fun setCodecConfig(device: BluetoothDevice, config: CodecConfig) {
        val operation = log.begin(EventType.USER_SET_CODEC, LogDevice.of(device), LogCodecConfig.of(config))
        applyCodecConfig(device, config, operation)
    }

    private fun restorePreferredConfig(device: BluetoothDevice, config: CodecConfig) {
        val operation = log.begin(EventType.USER_RESTORE_PREFERRED, LogDevice.of(device), LogCodecConfig.of(config))
        applyCodecConfig(device, null, operation)
    }

    /** Applies [config], or the preferred codec if it's null, as part of [operation]. */
    private fun applyCodecConfig(device: BluetoothDevice, config: CodecConfig?, operation: EventLog.Operation, mayAssociate: Boolean = true) {
        if (mayAssociate && PermissionUtil.isAssociationRequired(this, device)) {
            associateFor(device, operation) { applyCodecConfig(device, config, operation, mayAssociate = false) }
            return
        }
        // The applier may outlive this activity, so the result is shown with the application context
        val appContext = applicationContext
        val onSecurityException: ((EventLog.Operation, SecurityException) -> Unit)? =
            if (mayAssociate) ({ handedBack, e -> associateAfterRefusal(device, config, handedBack, e) }) else null
        applier.apply(device, operation, config, onDone = { showFailure(appContext, it) }, onSecurityException = onSecurityException)
    }

    /** Continues [operation] after Bluetooth refused the codec change with [e]. */
    private fun associateAfterRefusal(device: BluetoothDevice, config: CodecConfig?, operation: EventLog.Operation, e: SecurityException) {
        // An updated Bluetooth module may require an association on older versions as well
        if (!isDestroyed && PermissionUtil.canAssociate(this) && !PermissionUtil.hasAssociation(this, device)) {
            associateFor(device, operation) { applyCodecConfig(device, config, operation, mayAssociate = false) }
            return
        }
        Log.w(TAG, "Bluetooth refused to change the codec", e)
        operation.resolve(EventType.SECURITY_EXCEPTION, message = e.toString())
        showFailure(applicationContext, EventType.SECURITY_EXCEPTION)
    }

    private fun savePreferredConfig(device: BluetoothDevice, config: CodecConfig) {
        val operation = log.begin(EventType.USER_SAVE_PREFERRED, LogDevice.of(device), LogCodecConfig.of(config))
        storePreferredConfig(device, config, operation)
    }

    private fun storePreferredConfig(device: BluetoothDevice, config: CodecConfig, operation: EventLog.Operation, mayAssociate: Boolean = true) {
        // Without an association the preferred codec can't be applied when the device connects
        if (mayAssociate && (PermissionUtil.isAssociationRequired(this, device)
                    || PermissionUtil.isAssociationRequiredForAutoApply(this, device))) {
            associateFor(device, operation) { storePreferredConfig(device, config, operation, mayAssociate = false) }
            return
        }
        SavedDeviceStore.setPreferred(this, device, config)
        // Saving needs a connected device, so it's connected now
        SavedDeviceStore.updateInfo(this, device, name = device.name, lastConnected = System.currentTimeMillis(),
            capabilities = connectedDevices.firstOrNull { it.address.equals(device.address, ignoreCase = true) }
                ?.configMasks?.takeIf { it.isNotEmpty() })
        CompanionPresence.update(this, device)
        operation.resolve(EventType.PREFERRED_SAVED)
        pullBluetoothState()
    }

    /** Changes the preferred codec from the Saved devices list. The device was linked when it was saved. */
    private fun changePreferredConfig(device: BluetoothDevice, config: CodecConfig) {
        if (SavedDeviceStore.getPreferred(this, device) == null)
            return
        val operation = log.begin(EventType.USER_SAVE_PREFERRED, LogDevice.of(device), LogCodecConfig.of(config),
            message = "saved devices")
        SavedDeviceStore.setPreferred(this, device, config)
        operation.resolve(EventType.PREFERRED_SAVED)
        pullBluetoothState()
    }

    // What deleting removed, by upper case address, for undoing it while the activity lives
    private val deletedDevices = HashMap<String, SavedDeviceStore.Entry>()

    private fun deletePreferredConfig(device: BluetoothDevice) {
        val config = SavedDeviceStore.getPreferred(this, device)
        SavedDeviceStore.remove(this, device)?.let { deletedDevices[device.address.uppercase()] = it }
        log.log(EventType.PREFERRED_DELETED, LogDevice.of(device), config = config?.let { LogCodecConfig.of(it) })
        CompanionPresence.update(this, device)
        pullBluetoothState()
    }

    private fun undoDeletePreferred(device: BluetoothDevice) {
        val entry = deletedDevices.remove(device.address.uppercase()) ?: return
        if (SavedDeviceStore.getPreferred(this, device) != null)
            return
        SavedDeviceStore.restore(this, device, entry)
        val config = SavedDeviceStore.getPreferred(this, device)
        log.begin(EventType.USER_SAVE_PREFERRED, LogDevice.of(device), config?.let { LogCodecConfig.of(it) },
            message = "undo delete").resolve(EventType.PREFERRED_SAVED)
        CompanionPresence.update(this, device)
        pullBluetoothState()
    }

    private fun unlinkDevice(device: BluetoothDevice) {
        if (PermissionUtil.removeAssociation(this, device)) {
            log.log(EventType.DEVICE_UNLINKED, LogDevice.of(device))
            Toast.makeText(this, "Device unlinked", Toast.LENGTH_SHORT).show()
        }
        pullBluetoothState()
    }

    private var a2dpProfile: BluetoothA2dp? = null
    private var profileRequested = false
    private var handler: Handler? = null

    private var connectedDevices by mutableStateOf(emptyList<DeviceData>())
    private var bluetoothEnabled by mutableStateOf(true)
    private var devicesKnown by mutableStateOf(false)
    private var savedDevices by mutableStateOf(emptyList<SavedDeviceData>())
    private var profileWaitPolls = 0

    private fun pullBluetoothState() {
        val profile = a2dpProfile
        bluetoothEnabled = getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true
        profileWaitPolls = if (profile != null || !bluetoothEnabled) 0 else profileWaitPolls + 1
        devicesKnown = profile != null || profileWaitPolls > PROFILE_WAIT_POLLS
        if (profile == null) {
            connectedDevices = emptyList()
        } else {
            val devices = try {
                profile.connectedDevices
            } catch (e: SecurityException) {
                Log.w(TAG, "not allowed to list connected devices", e)
                emptyList()
            }
            connectedDevices = devices.map { readDeviceData(profile, it) }
            for ((device, data) in devices.zip(connectedDevices)) {
                logObservedCodec(device, data)
                updateSavedInfo(device, data)
            }
        }
        savedDevices = readSavedDevices()
    }

    /** The saved devices, the connected ones first, then the most recently connected. */
    private fun readSavedDevices(): List<SavedDeviceData> {
        val connected = connectedDevices.mapTo(HashSet()) { it.address.uppercase() }
        return SavedDeviceStore.addresses(this).mapNotNull { address ->
            val preferred = SavedDeviceStore.getPreferred(this, address) ?: return@mapNotNull null
            val info = SavedDeviceStore.getInfo(this, address) ?: return@mapNotNull null
            SavedDeviceData(address, info.name ?: address, preferred, info.capabilities, info.lastConnected,
                connected = address in connected)
        }.sortedWith(
            compareByDescending<SavedDeviceData> { it.connected }
                .thenByDescending { it.lastConnected ?: 0 }
                .thenBy { it.name }
        )
    }

    /** Keeps the name and the offered values of a saved device as they're read, the store only writes changes. */
    private fun updateSavedInfo(device: BluetoothDevice, data: DeviceData) {
        if (data.preferredConfig == null)
            return
        SavedDeviceStore.updateInfo(this, device, name = device.name,
            capabilities = data.configMasks.takeIf { it.isNotEmpty() })
    }

    /**
     * Logs codecs that differ from the last one known for the device in this process, so the first
     * one read in a process as well, and new read errors. While the applier changes the codec of a
     * device, its operation logs the codecs instead.
     */
    private fun logObservedCodec(device: BluetoothDevice, data: DeviceData) {
        val address = device.address.uppercase()
        val error = data.readError
        if (error != null) {
            if (readFailures.add(address))
                log.log(EventType.READ_FAILED, LogDevice.of(device), message = error)
            return
        }
        readFailures.remove(address)
        val config = data.androidCodecConfig ?: return
        if (address !in applier.pending.value && config != applier.knownCodec(address)) {
            applier.noteObservedCodec(address, config)
            log.log(EventType.ACTIVITY_OBSERVED_EXTERNAL_CODEC_CHANGE, LogDevice.of(device), actual = LogCodecConfig.of(config))
        }
    }

    private fun readDeviceData(profile: BluetoothA2dp, device: BluetoothDevice): DeviceData {
        val name = device.name ?: device.address
        val associationRequired = PermissionUtil.isAssociationRequired(this, device)
                || PermissionUtil.isAssociationRequiredForAutoApply(this, device)
        val associated = PermissionUtil.hasAssociation(this, device)
        val preferredConfig = SavedDeviceStore.getPreferred(this, device)
        val targetConfig = applier.pending.value[device.address.uppercase()]
        try {
            val androidConfig = A2dpProfileUtil.getCurrentCodecConfig(profile, device)
            Log.d(TAG, androidConfig.toString())
            return DeviceData(
                address = device.address,
                name = name,
                androidCodecConfig = androidConfig,
                codecConfig = androidConfig?.let { CodecConfig.fromAndroid(it) },
                configMasks = A2dpProfileUtil.getSelectableCodecConfigs(profile, device),
                statusReadable = true,
                associationRequired = associationRequired,
                associated = associated,
                preferredConfig = preferredConfig,
                targetConfig = targetConfig
            )
        } catch (e: SecurityException) {
            // Since API 36 reading the codec status requires an association as well
            Log.d(TAG, "Bluetooth refused to report the codec status of $name")
            return DeviceData(device.address, name, null, null, ArrayList(), statusReadable = false, associationRequired, associated, preferredConfig, readError = e.toString(), targetConfig = targetConfig)
        } catch (e: Exception) {
            Log.e(TAG, "reading the codec status of $name failed", e)
            return DeviceData(device.address, name, null, null, ArrayList(), statusReadable = true, associationRequired, associated, preferredConfig, readError = e.toString(), targetConfig = targetConfig)
        }
    }

    private val myTimer = Runnable {
        pullBluetoothState()
        handler!!.postDelayed(timer(), 1000)
    }
    private fun timer(): Runnable { return myTimer }

    override fun onStart() {
        super.onStart()

        // The permission may have been granted in the app settings meanwhile
        updatePermissionState()
        timer().run()
    }

    override fun onStop() {
        super.onStop()

        handler!!.removeCallbacks(timer())
    }
}

// Not const, a constant false condition would prevent the smart casts in the guarded blocks
private val SHOW_UNCONFIGURABLE_OPTIONS = false

/**
 * A chip of the codec's values. The selected one is filled in the inverse primary color, the theme's color at tone 80
 * in light mode and 40 in dark mode: a step stronger than Save's container, without outshouting the buttons. The
 * others are faintly outlined. While the device doesn't report the selected value yet, since a codec change is pending,
 * it's filled in the secondary color instead. The collapsed card's chips look like the selected ones, but can't be
 * chosen.
 */
@Composable
private fun ConfigChip(text: String, selected: Boolean, pending: Boolean, enabled: Boolean, clickable: Boolean,
                       onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = when {
                !selected -> Color.Transparent
                pending -> scheme.secondary
                else -> scheme.inversePrimary
            },
            contentColor = when {
                !selected -> scheme.onSurfaceVariant
                pending -> scheme.onSecondary
                else -> scheme.onPrimaryContainer
            },
            border = if (selected) null else BorderStroke(1.dp, scheme.outlineVariant),
            enabled = clickable,
            onClick = onClick,
            modifier = Modifier.graphicsLayer(alpha = if (enabled) 1f else 0.38f)
                .semantics { if (selected) stateDescription = if (pending) "Pending" else "Selected" }
        ) {
            Text(text, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ActiveConfigChip(text: String, pending: Boolean = false) {
    ConfigChip(text = text, selected = true, pending = pending, enabled = true, onClick = {}, clickable = false)
}

/**
 * Which values of the shown codec the device doesn't report yet, each row's chips on its own: the codec, the sample
 * rate, the sample size, and the codec specific options, which are pending until the device reports that codec.
 */
private class PendingRows(shown: CodecConfig?, reported: CodecConfig?) {
    val codec = shown != null && shown.getCodecType() != reported?.getCodecType()
    val sampleRate = shown != null && shown.sampleRate != reported?.sampleRate
    val bitsPerSample = shown != null && shown.bitsPerSample != reported?.bitsPerSample
    val options = shown != null && (codec || shown.toAndroid().options() != reported?.toAndroid()?.options())

    private fun AndroidCodecConfig.options() = listOf(codecSpecific1, codecSpecific2, codecSpecific3, codecSpecific4)
}

private val DeviceData.pendingRows get() = PendingRows(shownConfig, codecConfig)

/**
 * The chip of the collapsed card that says the preferred codec is active, after the codec's chips, in the green of the
 * expanded card's banner, which holds its own among the filled codec chips.
 */
@Composable
private fun PreferredChip() {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = AppColors.status.preferredContainer,
        contentColor = AppColors.status.onPreferredContainer,
        modifier = Modifier.testTag("preferred_chip")
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Preferred Codec", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The size of the icons in the preferred codec's buttons, 18 dp at the normal font size, growing with the labels. */
@Composable
private fun preferredIconSize(): Dp =
    with(LocalDensity.current) { (MaterialTheme.typography.labelLarge.fontSize * (18f / 14f)).toDp() }

/** The icon and the label of a button, at least 40 dp tall. */
@Composable
private fun PreferredContent(label: String, icon: ImageVector) {
    Row(
        Modifier.heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(preferredIconSize()))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/**
 * What a banner offers. The preferred codec's: Restore while it differs from the codec, Delete while it's active. A
 * saved device's: View device while it's connected, Delete otherwise.
 */
private enum class BannerAction(val label: String, val icon: ImageVector, val tag: String) {
    RESTORE("Restore", Icons.Default.Refresh, "restore_preferred"),
    DELETE("Delete", Icons.Default.Delete, "clear_preferred"),
    VIEW_DEVICE("View device", Icons.AutoMirrored.Filled.ArrowForward, "view_device"),
    DELETE_SAVED("Delete", Icons.Default.Delete, "delete_saved"),
}

/**
 * Tops the expanded card with the preferred codec as text: in the collapsed card's Preferred chip green with Delete
 * while it's active, otherwise a step darker than the card, with Restore while it differs, or what Save below the
 * codec does while nothing is saved. A codec this app doesn't know can't be saved, so the card leaves the banner out
 * when there's no preferred one either.
 */
@Composable
private fun PreferredBanner(device: DeviceData, actions: MainScreenActions) {
    val preferred = device.preferredConfig
    // Compared with the codec being applied, like the chips
    val active = preferred != null && preferred == device.shownConfig
    val action = when {
        preferred == null -> null
        active -> BannerAction.DELETE
        else -> BannerAction.RESTORE
    }
    val scheme = MaterialTheme.colorScheme
    ActionBanner(
        icon = if (active) Icons.Default.Check else Icons.Default.Star,
        title = when {
            preferred == null -> "No preferred codec"
            active -> "Preferred codec is active"
            else -> "Preferred codec"
        },
        description = preferred?.describe() ?: "Save the codec below to switch to it whenever this device connects.",
        action = action,
        onAction = {
            when (action) {
                BannerAction.RESTORE -> preferred?.let { actions.restorePreferred(device.address, it) }
                else -> actions.deletePreferred(device.address)
            }
        },
        color = if (active) AppColors.status.preferredContainer else scheme.surfaceContainerHighest,
        contentColor = if (active) AppColors.status.onPreferredContainer else scheme.onSurface,
        tag = "preferred_banner"
    )
}

/**
 * A banner with an icon, a title, a description below and an [action]. The button is outlined and uncolored, so it
 * doesn't compete with the chips or Save. It sits beside the title and the description, centered on both, if the title
 * fits next to it, where a long description wraps instead of moving the button. Otherwise it's below the description,
 * so the title never wraps mid-word.
 */
@Composable
private fun ActionBanner(
    icon: ImageVector,
    title: String,
    description: String,
    action: BannerAction?,
    onAction: () -> Unit,
    color: Color,
    contentColor: Color,
    tag: String,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color,
        contentColor = contentColor,
        modifier = Modifier.fillMaxWidth().testTag(tag)
    ) {
        val iconSize = preferredIconSize()
        // With the button's 48 dp touch target around its 40 dp, this puts both the button and the text, with its own
        // 4 dp, 12 dp from the banner's edge
        BoxWithConstraints(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            fun width(text: String, style: TextStyle) =
                with(density) { measurer.measure(text, style, softWrap = false).size.width.toDp() }
            // The button's icon, its gap and the padding on both sides
            val button = action?.let { width(it.label, MaterialTheme.typography.labelLarge) + iconSize + 8.dp + 32.dp }
            val beside = button != null &&
                iconSize + 12.dp + width(title, MaterialTheme.typography.titleSmall) + 8.dp + button <= maxWidth
            if (beside) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BannerText(icon, title, description, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    BannerButton(action, onAction)
                }
            } else {
                Column {
                    BannerText(icon, title, description)
                    if (action != null) {
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            BannerButton(action, onAction)
                        }
                    }
                }
            }
        }
    }
}

/** The banner's icon and title, with its description below the title. */
@Composable
private fun BannerText(icon: ImageVector, title: String, description: String, modifier: Modifier = Modifier) {
    val iconSize = preferredIconSize()
    Column(modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize))
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(2.dp))
        Text(description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = iconSize + 12.dp))
    }
}

/** The banner's uncolored outlined button. */
@Composable
private fun BannerButton(action: BannerAction, onAction: () -> Unit) {
    Surface(
        onClick = onAction,
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.semantics { role = Role.Button }.testTag(action.tag)
    ) {
        PreferredContent(action.label, action.icon)
    }
}

/**
 * Ends the expanded card when there's something to save: nothing is saved yet, or the codec differs from the
 * preferred one, which it then replaces. A tonal button as wide as the row, up to 420 dp. Never for a codec this app
 * doesn't know. While a codec change is pending it saves the codec being applied, the one the chips show.
 */
@Composable
private fun SavePreferredButton(device: DeviceData, actions: MainScreenActions) {
    val current = device.shownConfig
    if (current == null || current == device.preferredConfig)
        return
    val scheme = MaterialTheme.colorScheme
    Column {
        Spacer(Modifier.height(16.dp))
        Surface(
            onClick = { actions.savePreferred(device.address, current) },
            shape = CircleShape,
            color = scheme.primaryContainer,
            contentColor = scheme.onPrimaryContainer,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().semantics { role = Role.Button }
                .testTag("save_preferred")
        ) {
            PreferredContent("Save as preferred", Icons.Default.Star)
        }
    }
}

private data class ConfigRowItem(
    val text: String,
    val value: Int,
    val enabled: Boolean
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfigSelectRow(title: String, items: List<ConfigRowItem>, selectedValue: Int, pending: Boolean,
                            onSelected: (Int) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (item in items) {
                ConfigChip(
                    text = item.text,
                    selected = item.value == selectedValue,
                    pending = pending,
                    enabled = item.enabled,
                    clickable = item.enabled,
                    onClick = { onSelected(item.value) },
                )
            }
        }
    }
}

private fun isCodecTypeSupported(masks: List<A2dpProfileUtil.ConfigMask>, @CodecConfig.CodecType newCodecType: Int): Boolean {
    return masks.any { it.codecType == newCodecType }
}

/**
 * The configuration of [newCodecType] to switch to, keeping [curSampleRate] and [curBitsPerSample] where the codec
 * supports them, otherwise the first supported values.
 */
private fun findSelectableConfig(
    masks: List<A2dpProfileUtil.ConfigMask>,
    @CodecConfig.CodecType newCodecType: Int,
    @CodecConfig.SampleRate curSampleRate: Int,
    @CodecConfig.BitsPerSample curBitsPerSample: Int
): CodecConfig {
    val validConfigs = TreeMap<Int, CodecConfig>()
    for (configMask in masks) {
        if (configMask.codecType != newCodecType)
            continue

        val newConfig = CodecConfig.makeCodecConfig(newCodecType)!!
        var score = 0

        if (configMask.isSampleRateSupported(curSampleRate)) {
            score += 2
            newConfig.sampleRate = curSampleRate
        } else {
            for (sampleRate in CodecConfig.SAMPLE_RATES) {
                if (configMask.isSampleRateSupported(sampleRate)) {
                    newConfig.sampleRate = sampleRate
                    break
                }
            }
        }
        if (configMask.isSampleSizeSupported(curBitsPerSample)) {
            score += 1
            newConfig.bitsPerSample = curBitsPerSample
        } else {
            for (bitsPerSample in CodecConfig.SAMPLE_SIZES) {
                if (configMask.isSampleSizeSupported(bitsPerSample)) {
                    newConfig.bitsPerSample = bitsPerSample
                    break
                }
            }
        }

        validConfigs.putIfAbsent(score, newConfig)
    }
    return validConfigs.lastEntry()!!.value
}

private fun isConfigSelectable(
    masks: List<A2dpProfileUtil.ConfigMask>,
    @CodecConfig.CodecType codecType: Int,
    @CodecConfig.SampleRate sampleRate: Int,
    @CodecConfig.BitsPerSample bitsPerSample: Int
): Boolean {
    for (configMask in masks) {
        if (configMask.codecType == codecType &&
            configMask.isSampleRateSupported(sampleRate) &&
            configMask.isSampleSizeSupported(bitsPerSample)) {
            return true
        }
    }
    return false
}

private fun isSampleRateSelectable(masks: List<A2dpProfileUtil.ConfigMask>, config: CodecConfig, @CodecConfig.SampleRate sampleRate: Int): Boolean {
    return isConfigSelectable(masks, config.getCodecType(), sampleRate, config.bitsPerSample)
}

private fun isSampleSizeSelectable(masks: List<A2dpProfileUtil.ConfigMask>, config: CodecConfig, @CodecConfig.BitsPerSample bitsPerSample: Int): Boolean {
    return isConfigSelectable(masks, config.getCodecType(), config.sampleRate, bitsPerSample)
}

/**
 * The rows of chips to choose the codec and its values, with [config] selected, null if it's unknown to this app, and
 * [masks] the values that can be chosen. Each row is colored as pending on its own, see [PendingRows]. Choosing a
 * codec keeps the sample rate and size of [config], or else of [android], where the new codec supports them.
 */
@Composable
private fun CodecConfigSelection(
    config: CodecConfig?,
    android: AndroidCodecConfig?,
    masks: List<A2dpProfileUtil.ConfigMask>,
    pending: PendingRows,
    onConfigChange: (CodecConfig) -> Unit
) {
    Column {
        val currentCodecType = config?.getCodecType() ?: CodecConfig.CODEC_TYPE_INVALID
        ConfigSelectRow(
            "Codec",
            CodecConfig.CODEC_TYPES.map {
                ConfigRowItem(CodecConfig.getCodecName(it)!!, it, isCodecTypeSupported(masks, it))
            },
            currentCodecType,
            pending.codec,
            onSelected = {
                if (it != currentCodecType) {
                    onConfigChange(findSelectableConfig(
                        masks,
                        it,
                        config?.sampleRate ?: android?.sampleRate ?: CodecConfig.SAMPLE_RATE_NONE,
                        config?.bitsPerSample ?: android?.bitsPerSample ?: CodecConfig.BITS_PER_SAMPLE_NONE
                    ))
                }
            }
        )

        // The current codec is unknown to this app, so only offer switching to a known one
        if (config == null)
            return@Column

        Spacer(Modifier.height(16.dp))
        ConfigSelectRow(
            "Sample Rate",
            CodecConfig.SAMPLE_RATES.map {
                ConfigRowItem("${CodecConfig.getSampleRate(it)} Hz", it, isSampleRateSelectable(masks, config, it))
            },
            config.sampleRate,
            pending.sampleRate,
            onSelected = {
                val newConfig = config.clone() as CodecConfig
                newConfig.sampleRate = it
                onConfigChange(newConfig)
            }
        )

        Spacer(Modifier.height(16.dp))
        ConfigSelectRow(
            "Sample Size",
            CodecConfig.SAMPLE_SIZES.map { ConfigRowItem("${CodecConfig.getBitsPerSample(it)}-bit", it, isSampleSizeSelectable(masks, config, it)) },
            config.bitsPerSample,
            pending.bitsPerSample,
            onSelected = {
                val newConfig = config.clone() as CodecConfig
                newConfig.bitsPerSample = it
                onConfigChange(newConfig)
            }
        )

        if (config is LDACCodecConfig) {
            Spacer(Modifier.height(16.dp))
            ConfigSelectRow(
                "Bitrate",
                LDACCodecConfig.BITRATES.map {
                    ConfigRowItem(LDACCodecConfig.getFixedBitrate(it, config.sampleRate)?.toString()?.plus(" kbps") ?: "Adaptive", it, true)
                },
                if (config.bitrate == LDACCodecConfig.BITRATE_SYSTEM_DEFAULT) LDACCodecConfig.BITRATE_ADAPTIVE else config.bitrate,
                pending.options,
                onSelected = {
                    val newConfig = config.clone() as LDACCodecConfig
                    newConfig.bitrate = it
                    onConfigChange(newConfig)
                }
            )
        }

        // This is in the AOSP bluetooth source but appears to not be configurable
        if (SHOW_UNCONFIGURABLE_OPTIONS && config is AACCodecConfig) {
            Spacer(Modifier.height(16.dp))
            ConfigSelectRow(
                "Variable Bitrate",
                arrayListOf(
                    ConfigRowItem("Disabled", AACCodecConfig.VARIABLE_BITRATE_DISABLED, true),
                    ConfigRowItem("Very Low", AACCodecConfig.VARIABLE_BITRATE_VERY_LOW, true),
                    ConfigRowItem("Low", AACCodecConfig.VARIABLE_BITRATE_LOW, true),
                    ConfigRowItem("Medium", AACCodecConfig.VARIABLE_BITRATE_MEDIUM, true),
                    ConfigRowItem("High", AACCodecConfig.VARIABLE_BITRATE_HIGH, true),
                    ConfigRowItem("Very High", AACCodecConfig.VARIABLE_BITRATE_VERY_HIGH, true),
                ),
                config.variableBitrate,
                pending.options,
                onSelected = {
                    val newConfig = config.clone() as AACCodecConfig
                    newConfig.variableBitrate = it
                    onConfigChange(newConfig)
                }
            )
        }

        // This is in the AOSP bluetooth source but appears to not be configurable
        if (SHOW_UNCONFIGURABLE_OPTIONS && config is OpusCodecConfig) {
            Spacer(Modifier.height(16.dp))
            ConfigSelectRow(
                "Encode Complexity",
                listOf(ConfigRowItem("Default", OpusCodecConfig.ENCODE_COMPLEXITY_SYSTEM_DEFAULT, true)) +
                (0..9).map { ConfigRowItem(it.toString(), it, true) },
                config.encodeComplexity,
                pending.options,
                onSelected = {
                    val newConfig = config.clone() as OpusCodecConfig
                    newConfig.encodeComplexity = it
                    onConfigChange(newConfig)
                }
            )
        }
    }
}

/**
 * The collapsed card's chips: the codec, then the preferred codec's chip while it's active, then the link's while
 * the device isn't linked. Last, so the codec's chips stay where they are when they come and go.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodecChips(device: DeviceData, state: MainScreenState) {
    val extra = @Composable {
        if (device.associationRequired)
            LinkChip(linking = state.linkingAddress == device.address, canLink = state.canAssociate)
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val config = device.shownConfig
        if (config == null) {
            ActiveConfigChip(if (device.androidCodecConfig != null) "Unsupported codec" else "No codec information")
            extra()
            return@FlowRow
        }
        ConfigValueChips(config, device.pendingRows)
        if (device.preferredConfig == config) {
            PreferredChip()
        }
        extra()
    }
}

/**
 * The chips of [config]'s values, each colored as pending as its row in [pending]. The system default LDAC bitrate has
 * none. They're placed in the caller's row.
 */
@Composable
private fun ConfigValueChips(config: CodecConfig, pending: PendingRows?) {
    CodecConfig.getCodecName(config.getCodecType())?.let {
        ActiveConfigChip(it, pending?.codec == true)
    }
    CodecConfig.getSampleRate(config.sampleRate)?.let {
        ActiveConfigChip("$it Hz", pending?.sampleRate == true)
    }
    CodecConfig.getBitsPerSample(config.bitsPerSample)?.let {
        ActiveConfigChip("$it-bit", pending?.bitsPerSample == true)
    }
    if (config is LDACCodecConfig) {
        val pendingBitrate = pending?.options == true
        if (config.bitrate == LDACCodecConfig.BITRATE_ADAPTIVE) {
            ActiveConfigChip("Adaptive", pendingBitrate)
        } else {
            LDACCodecConfig.getFixedBitrate(config.bitrate, config.sampleRate)
                ?.let { ActiveConfigChip("$it kbps", pendingBitrate) }
                ?: LDACCodecConfig.getFixedBitrate(config.bitrate)
                    ?.let { ActiveConfigChip("${it.first}/${it.second} kbps", pendingBitrate) }
        }
    }
}

/** A device's card, expanded or collapsed as the list keeps it, so it stays that way while the other list shows. */
@Composable
private fun DeviceCard(device: DeviceData, extended: Boolean, onToggle: () -> Unit, state: MainScreenState,
                       actions: MainScreenActions, modifier: Modifier = Modifier) {
    val cardModifier = modifier.fillMaxWidth().padding(16.dp).testTag(deviceTag(device))
    val content = @Composable {
        Column(modifier = Modifier.padding(10.dp)) {
            // The name has the header to itself, the preferred codec is below the codec
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(
                    device.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (state.showDevelopmentOptions && device.associated) {
                    DeviceMenu(device, actions)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (!device.statusReadable) {
                LinkPrompt(device, state, actions)
                return@Column
            }
            AnimatedVisibility(!extended) {
                CodecChips(device, state)
            }
            AnimatedVisibility(extended) {
                Column {
                    if (device.associationRequired) {
                        LinkBanner(device, state.canAssociate)
                        Spacer(Modifier.height(16.dp))
                    }
                    if (device.preferredConfig != null || device.shownConfig != null) {
                        PreferredBanner(device, actions)
                        Spacer(Modifier.height(16.dp))
                    }
                    CodecConfigSelection(
                        device.shownConfig,
                        device.androidCodecConfig,
                        device.configMasks,
                        device.pendingRows,
                        onConfigChange = { actions.setCodec(device.address, it) }
                    )
                    SavePreferredButton(device, actions)
                }
            }
        }
    }
    // Material's elevated card color, the shadow alone doesn't show in dark mode
    val color = MaterialTheme.colorScheme.surfaceContainerLow
    // Without a codec there's nothing to expand, the card always shows how to link the device
    if (device.statusReadable) {
        Surface(
            onClick = onToggle,
            modifier = cardModifier,
            color = color,
            shadowElevation = 6.dp,
            shape = CardDefaults.elevatedShape,
            content = content
        )
    } else {
        Surface(modifier = cardModifier, color = color, shadowElevation = 6.dp, shape = CardDefaults.elevatedShape,
            content = content)
    }
}

/**
 * A saved device's card. Collapsed it shows the preferred codec, and a Connected chip while the device is connected.
 * Expanded, a banner says whether it's connected, offering to view it in the Connected devices list or to delete it,
 * above the chips that change the preferred codec right away, from the values the device offered when it was last read.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedDeviceCard(
    device: SavedDeviceData,
    extended: Boolean,
    onToggle: () -> Unit,
    onView: () -> Unit,
    onDelete: () -> Unit,
    actions: MainScreenActions,
) {
    Surface(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag(savedTag(device)),
        // Material's elevated card color, like the connected devices' cards
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 6.dp,
        shape = CardDefaults.elevatedShape
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(
                    device.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            AnimatedVisibility(!extended) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ConfigValueChips(device.preferredConfig, pending = null)
                    if (device.connected)
                        ConnectedChip()
                }
            }
            AnimatedVisibility(extended) {
                Column {
                    SavedBanner(device, onView, onDelete)
                    Spacer(Modifier.height(16.dp))
                    CodecConfigSelection(
                        device.preferredConfig,
                        android = null,
                        device.capabilities,
                        PendingRows(null, null),
                        onConfigChange = {
                            if (it != device.preferredConfig)
                                actions.setPreferred(device.address, it)
                        }
                    )
                }
            }
        }
    }
}

/**
 * The chip after a saved device's codec while it's connected. Neutral, since being connected is neither the preferred
 * codec's green nor needs attention like the amber of a missing link.
 */
@Composable
private fun ConnectedChip() {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("connected_chip")
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(ImageVector.vectorResource(R.drawable.ic_headset), contentDescription = null,
                modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Connected", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Tops a saved device's expanded card, a step darker than the card: while it's connected with View device, which
 * shows its card in the Connected devices list, otherwise with when it was last connected and Delete.
 */
@Composable
private fun SavedBanner(device: SavedDeviceData, onView: () -> Unit, onDelete: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    if (device.connected) {
        ActionBanner(
            icon = ImageVector.vectorResource(R.drawable.ic_headset),
            title = "Connected",
            description = "Changes here apply the next time it connects. View it to change the codec now.",
            action = BannerAction.VIEW_DEVICE,
            onAction = onView,
            color = scheme.surfaceContainerHighest,
            contentColor = scheme.onSurface,
            tag = "saved_banner"
        )
    } else {
        val context = LocalContext.current
        ActionBanner(
            icon = ImageVector.vectorResource(R.drawable.ic_schedule),
            title = "Not connected",
            description = device.lastConnected?.let { "Last connected: " + lastConnectedText(context, it) }
                ?: "Not connected since it was saved",
            action = BannerAction.DELETE_SAVED,
            onAction = onDelete,
            color = scheme.surfaceContainerHighest,
            contentColor = scheme.onSurface,
            tag = "saved_banner"
        )
    }
}

/** For example "Yesterday, 18:42", "3 days ago, 14:32" or "Nov 3, 14:32", in the app's locale, see [AppLocale]. */
internal fun lastConnectedText(context: Context, millis: Long): String =
    DateUtils.getRelativeDateTimeString(context, millis, DateUtils.DAY_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0).toString()

/** The device's menu, only in debug builds, for unlinking a linked device. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DeviceMenu(device: DeviceData, actions: MainScreenActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("device_menu")) {
            Icon(Icons.Default.MoreVert, contentDescription = "More options")
        }
        // A menu is a popup, outside the Scaffold that exposes the test tags to UiAutomator
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.semantics { testTagsAsResourceId = true }) {
            DropdownMenuItem(
                text = { Text("Unlink Device (Debug)") },
                modifier = Modifier.testTag("unlink_device"),
                onClick = {
                    expanded = false
                    actions.unlink(device.address)
                }
            )
        }
    }
}

/** The link's chip after the codec's: "Not linked" or "Linking…" in amber, "Can't link" in grey. */
@Composable
private fun LinkChip(linking: Boolean, canLink: Boolean) {
    val (container, content) =
        if (canLink) AppColors.status.attentionContainer to AppColors.status.onAttentionContainer
        else MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = container,
        contentColor = content,
        modifier = Modifier.testTag("link_chip")
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (linking) {
                CircularProgressIndicator(Modifier.size(14.dp), color = content, strokeWidth = 2.dp)
            } else {
                Icon(ImageVector.vectorResource(R.drawable.ic_link_off), contentDescription = null,
                    modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    linking -> "Linking…"
                    canLink -> "Not linked"
                    else -> "Can't link"
                },
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

/**
 * The card of a device whose codec can only be read once it's linked: why, and "Link Device" where Save would be,
 * as wide as the row up to 420 dp. While linking it spins instead, while another device links it waits. Without the
 * companion feature there's only the text.
 */
@Composable
private fun LinkPrompt(device: DeviceData, state: MainScreenState, actions: MainScreenActions) {
    Column {
        if (!state.canAssociate) {
            Text(
                "This phone can't link devices, so the codec can't be read or changed.",
                style = MaterialTheme.typography.bodyMedium
            )
            return@Column
        }
        Text("Link this device to see and change its codec.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        val linking = state.linkingAddress == device.address
        val waiting = state.linkingAddress != null && !linking
        val scheme = MaterialTheme.colorScheme
        Surface(
            onClick = { actions.link(device.address) },
            enabled = state.linkingAddress == null,
            shape = CircleShape,
            color = scheme.primaryContainer,
            contentColor = scheme.onPrimaryContainer,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
                .graphicsLayer(alpha = if (waiting) 0.38f else 1f)
                .semantics { role = Role.Button }.testTag("link_device")
        ) {
            if (linking) {
                Row(
                    Modifier.heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(preferredIconSize()), color = scheme.onPrimaryContainer,
                        strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Linking…", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            } else {
                PreferredContent("Link Device", ImageVector.vectorResource(R.drawable.ic_link))
            }
        }
    }
}

/**
 * Tops the expanded card of a readable device that isn't linked, in the "Not linked" chip's amber. Choosing a codec
 * or saving it asks to link first, so it has no button. In grey if the phone can't link.
 */
@Composable
private fun LinkBanner(device: DeviceData, canLink: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (canLink) AppColors.status.attentionContainer else scheme.surfaceVariant,
        contentColor = if (canLink) AppColors.status.onAttentionContainer else scheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag("link_banner")
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(ImageVector.vectorResource(R.drawable.ic_link_off), contentDescription = null,
                modifier = Modifier.size(preferredIconSize()))
            Spacer(Modifier.width(12.dp))
            Text(
                when {
                    !canLink -> "This phone can't link devices, so the codec can't be changed."
                    // Only if the link was removed after saving, since saving asks to link
                    device.preferredConfig != null ->
                        "The preferred codec is only applied when this device connects once it's linked. " +
                            "Changing or restoring the codec asks to link it."
                    else -> "Changing the codec or saving it as preferred asks to link this device first."
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun PermissionRequired(contentPadding: PaddingValues, permissionState: PermissionState, actions: MainScreenActions) {
    val title = "Codec needs your permission"
    val reason = "Auto Bluetooth Codec needs the Nearby devices permission to access the codec of your Bluetooth devices."
    if (permissionState == PermissionState.PERMANENTLY_DENIED) {
        EmptyState(DogScene.AT_DOOR, title, "Open settings", actions::openAppSettings, contentPadding,
            listOf(reason, "Allow it in the app settings under Permissions."))
    } else {
        EmptyState(DogScene.AT_DOOR, title, "Grant permission", actions::requestPermission, contentPadding, listOf(reason))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AppMenu(onOpenLog: () -> Unit, onOpenAbout: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("app_menu")) {
            Icon(Icons.Default.MoreVert, contentDescription = "More options")
        }
        // A menu is a popup, outside the Scaffold that exposes the test tags to UiAutomator
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.semantics { testTagsAsResourceId = true }) {
            DropdownMenuItem(
                text = { Text("Log") },
                modifier = Modifier.testTag("open_log"),
                onClick = {
                    expanded = false
                    onOpenLog()
                }
            )
            DropdownMenuItem(
                text = { Text("About") },
                modifier = Modifier.testTag("open_about"),
                onClick = {
                    expanded = false
                    onOpenAbout()
                }
            )
        }
    }
}

private fun Set<String>.toggled(address: String) = if (address in this) this - address else this + address

/** The test tag of a device's card. */
internal fun deviceTag(device: DeviceData) = "device_" + device.address.uppercase()

/** The test tag of a saved device's card. */
internal fun savedTag(device: SavedDeviceData) = "saved_" + device.address.uppercase()

/** Keeps a set of addresses across the activity being recreated. */
private val AddressSetSaver = listSaver<MutableState<Set<String>>, String>(
    save = { it.value.toList() },
    restore = { mutableStateOf(it.toSet()) }
)

/** How long a card takes to expand, after which View device brings it into view once more. */
private const val EXPAND_MILLIS = 400L

/**
 * The main screen: the connected devices with their codecs, or the saved devices with their preferred codecs, or an
 * empty state with a way out. The cards start collapsed, except those of the devices in [initiallyExpanded], by
 * address, for the previews, which can start with [initialList] as well. Each list keeps which cards are expanded and
 * how far it's scrolled while the other one shows.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun MainScreen(
    state: MainScreenState,
    actions: MainScreenActions,
    initiallyExpanded: Set<String> = emptySet(),
    initialList: DeviceList = DeviceList.CONNECTED,
) {
    var list by rememberSaveable { mutableStateOf(initialList) }
    var connectedExpanded by rememberSaveable(saver = AddressSetSaver) { mutableStateOf(initiallyExpanded) }
    var savedExpanded by rememberSaveable(saver = AddressSetSaver) { mutableStateOf(initiallyExpanded) }
    val connectedScroll = rememberScrollState()
    val savedScroll = rememberScrollState()
    // The connected device whose card View device asked to show, until it's in view
    var revealAddress by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        // Lets UiAutomator find the elements by their test tags
        modifier = Modifier.semantics { testTagsAsResourceId = true }.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MainTopBar(list, {
                list = it
                revealAddress = null
            }, scrollBehavior, actions)
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // Also keeps the content clear of display cutouts in landscape
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        when {
            list == DeviceList.SAVED && state.savedDevices.isEmpty() ->
                EmptyState(DogScene.TOY_BOX, "Codec has no saved devices yet", "Show connected devices",
                    { list = DeviceList.CONNECTED }, innerPadding)
            // Without it the saved devices' connections are unknown
            state.permissionState != PermissionState.GRANTED ->
                PermissionRequired(innerPadding, state.permissionState, actions)
            list == DeviceList.SAVED ->
                DeviceColumn(savedScroll, innerPadding) {
                    for (device in state.savedDevices) key(device.address) {
                        SavedDeviceCard(
                            device,
                            extended = device.address in savedExpanded,
                            onToggle = { savedExpanded = savedExpanded.toggled(device.address) },
                            onView = {
                                val address = state.devices.firstOrNull { it.address.equals(device.address, ignoreCase = true) }
                                    ?.address ?: device.address
                                connectedExpanded = connectedExpanded + address
                                revealAddress = address
                                list = DeviceList.CONNECTED
                            },
                            onDelete = {
                                actions.deletePreferred(device.address)
                                scope.launch {
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    val result = snackbarHostState.showSnackbar("${device.name} deleted", "Undo",
                                        duration = SnackbarDuration.Short)
                                    if (result == SnackbarResult.ActionPerformed)
                                        actions.undoDelete(device.address)
                                }
                            },
                            actions
                        )
                    }
                }
            !state.bluetoothEnabled ->
                EmptyState(DogScene.ASLEEP, "Codec is asleep", "Turn on Bluetooth", actions::enableBluetooth, innerPadding,
                    listOf("Bluetooth is off"))
            state.devices.isEmpty() -> if (state.devicesKnown) {
                // Only A2DP devices are listed, a connected watch or keyboard doesn't count
                EmptyState(DogScene.SNIFFING, "Codec hasn't found any audio devices", "Open Bluetooth settings",
                    actions::openBluetoothSettings, innerPadding)
            }
            else ->
                DeviceColumn(connectedScroll, innerPadding) {
                    for (device in state.devices) key(device.address) {
                        val requester = remember { BringIntoViewRequester() }
                        if (revealAddress == device.address) {
                            LaunchedEffect(Unit) {
                                // Once it's laid out, and again once it's expanded
                                withFrameNanos {}
                                requester.bringIntoView()
                                delay(EXPAND_MILLIS)
                                requester.bringIntoView()
                                revealAddress = null
                            }
                        }
                        DeviceCard(
                            device,
                            extended = device.address in connectedExpanded,
                            onToggle = { connectedExpanded = connectedExpanded.toggled(device.address) },
                            state,
                            actions,
                            Modifier.bringIntoViewRequester(requester)
                        )
                    }
                }
        }
    }
}

/** A list's cards, as wide as the screen up to a cap, scrolled by [scroll]. */
@Composable
private fun DeviceColumn(scroll: ScrollState, innerPadding: PaddingValues, content: @Composable () -> Unit) {
    CappedContent(NARROW_CONTENT, tintSides = false) { side ->
        // Padding inside the scroll container, so the cards scroll behind the navigation bar
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(innerPadding).padding(horizontal = side)
        ) {
            content()
        }
    }
}
