package com.tt.codeccompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LogExportTest {
    private val headphones = LogDevice("AA:BB:CC:DD:EE:01", "Torben's Headphones")
    private val speaker = LogDevice("AA:BB:CC:DD:EE:02", "Kitchen Speaker")

    private fun event(seq: Long, device: LogDevice?, message: String? = null) =
        LogEvent(LogEvent.VERSION, seq, seq, EventType.DEVICE_DISAPPEARED, device = device, message = message)

    @Test
    fun anonymise_replacesAddressesConsistently() {
        val events = listOf(
            event(0, headphones),
            event(1, speaker),
            event(2, headphones.copy(address = "aa:bb:cc:dd:ee:01")),
        )
        val anonymised = LogExport.anonymise(events, Random(0))
        val ids = anonymised.map { it.device!!.address }
        assertEquals(ids[0], ids[2])
        assertNotEquals(ids[0], ids[1])
        for (id in ids) {
            assertTrue(id, Regex("device-[GHJKMNPQRSTUVWXYZ]{4}").matches(id))
        }
        assertTrue(anonymised.all { it.device!!.name == null })
    }

    @Test
    fun anonymise_scrubsMessages() {
        val events = listOf(
            event(0, headphones),
            event(1, null, "Torben's Headphones (AA:BB:CC:DD:EE:01) and 11:22:33:44:55:66 failed"),
        )
        val anonymised = LogExport.anonymise(events, Random(0))
        val message = anonymised[1].message!!
        assertFalse(message, message.contains("Torben"))
        assertFalse(message, message.contains("AA:BB"))
        assertFalse(message, message.contains("11:22"))
        assertTrue(message, message.contains(anonymised[0].device!!.address))
    }

    @Test
    fun anonymise_removesNamesContainingOtherNames() {
        val pro = LogDevice("AA:BB:CC:DD:EE:03", "Kitchen Speaker Pro")
        val events = listOf(
            event(0, speaker),
            event(1, pro),
            event(2, null, "Kitchen Speaker Pro replaced Kitchen Speaker"),
        )
        val message = LogExport.anonymise(events, Random(0))[2].message!!
        assertEquals("[name removed] replaced [name removed]", message)
    }

    @Test
    fun anonymise_givesLowerCaseAddressesInMessagesTheDevicesId() {
        val events = listOf(
            event(0, headphones),
            event(1, null, "lost aa:bb:cc:dd:ee:01"),
        )
        val anonymised = LogExport.anonymise(events, Random(0))
        assertEquals("lost " + anonymised[0].device!!.address, anonymised[1].message)
    }

    @Test
    fun full_keepsEverything() {
        val events = listOf(event(0, headphones, "AA:BB:CC:DD:EE:01"))
        val text = LogExport.toJsonLines(events, LogExport.Mode.FULL)
        assertEquals(events, EventLog.parse(text.lines()))
        assertNull(EventLog.parse(text.lines()).first().opUID)
    }
}
