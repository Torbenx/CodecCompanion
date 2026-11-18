package com.tt.codeccompanion

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi

object PermissionUtil {
    /**
     * Below API 31 the legacy BLUETOOTH permission is an install time permission declared in the
     * manifest, so only BLUETOOTH_CONNECT has to be requested at runtime.
     */
    fun testPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < 31
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Companion device associations are only used since API 31, where they allow observing the
     * presence of a device. Before API 31 the Bluetooth stack doesn't check for an association,
     * and it isn't an updatable module there, so there is never a need to request one.
     */
    fun canAssociate(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= 31
                && context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)
    }

    fun hasAssociation(context: Context, device: BluetoothDevice): Boolean {
        return findAssociatedAddresses(context, device).isNotEmpty()
    }

    /** Removes all associations with [device], returns false if there were none. */
    fun removeAssociation(context: Context, device: BluetoothDevice): Boolean {
        if (!canAssociate(context))
            return false
        CompanionPresence.stopObserving(context, device)
        val companionManager = context.getSystemService(CompanionDeviceManager::class.java)
        if (Build.VERSION.SDK_INT >= 33) {
            val associations = findAssociations(context, device)
            for (association in associations) {
                companionManager.disassociate(association.id)
            }
            return associations.isNotEmpty()
        }
        val addresses = findAssociatedAddresses(context, device)
        for (address in addresses) {
            @Suppress("DEPRECATION")
            companionManager.disassociate(address)
        }
        return addresses.isNotEmpty()
    }

    /**
     * Returns the associated addresses as the companion device manager stores them. Before API 33
     * it matches them by exact string comparison, so they have to be passed back unchanged.
     */
    fun findAssociatedAddresses(context: Context, device: BluetoothDevice): List<String> {
        return getAssociatedAddresses(context).filter { matches(it, device) }
    }

    @RequiresApi(33)
    fun findAssociations(context: Context, device: BluetoothDevice): List<AssociationInfo> {
        if (!canAssociate(context))
            return emptyList()
        val companionManager = context.getSystemService(CompanionDeviceManager::class.java)
        return companionManager.myAssociations.filter { matches(it.deviceMacAddress?.toString(), device) }
    }

    fun getAssociatedAddresses(context: Context): List<String> {
        if (!canAssociate(context))
            return emptyList()
        val companionManager = context.getSystemService(CompanionDeviceManager::class.java)
        if (Build.VERSION.SDK_INT >= 33) {
            // Self managed associations have no MAC address
            return companionManager.myAssociations.mapNotNull { it.deviceMacAddress?.toString() }
        }
        @Suppress("DEPRECATION")
        return companionManager.associations
    }

    private fun matches(address: String?, device: BluetoothDevice): Boolean {
        return address.equals(device.address, ignoreCase = true)
    }

    /**
     * Since API 34 setCodecConfigPreference and since API 36 also getCodecStatus require a
     * companion device association (or BLUETOOTH_PRIVILEGED). The Bluetooth stack is an updatable
     * module, so callers must still handle a SecurityException on older versions.
     */
    fun isAssociationRequired(context: Context, device: BluetoothDevice): Boolean {
        return Build.VERSION.SDK_INT >= 34 && !hasAssociation(context, device)
    }

    /**
     * Whether [device] has to be linked so its preferred codec keeps being applied when it connects.
     * [A2dpConnectReceiver] applies it with or without an association.
     *
     * Since API 33 [CodecCompanionService] applies it as well while the device is present, which the
     * system only reports for associated devices. On API 31 and 32 the service is disabled, but the
     * system revokes the Bluetooth permission of an app that isn't used for a few months, which stops
     * the receiver. Any association exempts the app from that, and unlinking keeps the exemption, so a
     * link is only needed while the app isn't exempt and no device is linked. With a device linked, the
     * user turned the exemption off in the app's settings, and linking again wouldn't change it.
     */
    fun isAssociationRequiredForAutoApply(context: Context, device: BluetoothDevice): Boolean {
        if (Build.VERSION.SDK_INT < 31 || !canAssociate(context))
            return false
        if (Build.VERSION.SDK_INT >= 33)
            return !hasAssociation(context, device)
        return !context.packageManager.isAutoRevokeWhitelisted && getAssociatedAddresses(context).isEmpty()
    }
}
