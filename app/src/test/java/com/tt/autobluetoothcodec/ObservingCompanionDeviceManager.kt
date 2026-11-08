package com.tt.autobluetoothcodec

import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowCompanionDeviceManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * Robolectric's companion device manager, which also tracks what is observed. Its own shadow
 * records starting to observe but not stopping. Addresses are kept as passed, ids as "id 5".
 */
@Implements(CompanionDeviceManager::class)
class ObservingCompanionDeviceManager : ShadowCompanionDeviceManager() {
    val observed = LinkedHashSet<String>()

    /**
     * Like addAssociation(String), with the name the device had when it was linked. Robolectric only takes one in an
     * AssociationInfo, which apps can't build, so its own record is added like addAssociation(String) adds it.
     */
    fun addAssociation(deviceAddress: String, displayName: String) {
        val recordClass = Class.forName("${ShadowCompanionDeviceManager::class.java.name}\$RoboAssociationInfo")
        val builder = ReflectionHelpers.callStaticMethod<Any>(recordClass, "builder")
        ReflectionHelpers.callInstanceMethod<Any>(builder, "setDeviceMacAddress",
            ClassParameter.from(String::class.java, deviceAddress))
        ReflectionHelpers.callInstanceMethod<Any>(builder, "setDisplayName",
            ClassParameter.from(CharSequence::class.java, displayName))
        val record = ReflectionHelpers.callInstanceMethod<Any>(builder, "build")
        ReflectionHelpers.getField<MutableSet<Any>>(this, "associations").add(record)
    }

    @Implementation(minSdk = 31)
    override fun startObservingDevicePresence(deviceAddress: String) {
        super.startObservingDevicePresence(deviceAddress)
        observed.add(deviceAddress)
    }

    @Implementation(minSdk = 31)
    protected fun stopObservingDevicePresence(deviceAddress: String) {
        observed.remove(deviceAddress)
    }

    @Implementation(minSdk = 36)
    override fun startObservingDevicePresence(request: ObservingDevicePresenceRequest) {
        super.startObservingDevicePresence(request)
        observed.add("id ${request.associationId}")
    }

    @Implementation(minSdk = 36)
    protected fun stopObservingDevicePresence(request: ObservingDevicePresenceRequest) {
        observed.remove("id ${request.associationId}")
    }
}
