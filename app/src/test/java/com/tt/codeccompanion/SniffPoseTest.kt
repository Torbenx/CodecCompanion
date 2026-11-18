package com.tt.codeccompanion

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** The sniffing dog's motion, which is computed from the time alone. Robolectric provides the art's paths. */
@RunWith(AndroidJUnit4::class)
class SniffPoseTest {
    // When each leg starts in the loop, when Codec stops at its end and when it's done sniffing there
    private val starts = SNIFF_LEGS.runningFold(0f) { start, leg -> start + leg.seconds }.dropLast(1)
    private val stops = SNIFF_LEGS.indices.map { starts[it] + SNIFF_LEGS[it].walkSeconds }
    private val sniffsEnd = SNIFF_LEGS.indices.map { stops[it] + SNIFF_LEGS[it].sniffSeconds }
    private val midWalks = SNIFF_LEGS.indices.map { starts[it] + SNIFF_LEGS[it].walkSeconds / 2 }

    private fun times(from: Float, until: Float, step: Float = 0.01f) =
        generateSequence(from) { it + step }.takeWhile { it < until }

    @Test
    fun theLoopHasNoSeam() {
        val start = sniffPose(0f)
        val end = sniffPose(11.9999f)
        assertEquals(start.x, end.x, 0.01f)
        assertEquals(start.facing, end.facing, 0.01f)
        assertEquals(start.neck, end.neck, 0.01f)
        assertEquals(start.head, end.head, 0.01f)
        assertEquals(start.tail, end.tail, 0.05f)
        start.scent.zip(end.scent).forEach { (first, last) -> assertEquals(first, last, 0.01f) }
        assertEquals(sniffMoment(0f).sinceStop, sniffMoment(11.9999f).sinceStop, 0.001f)
    }

    @Test
    fun nothingJumps() {
        // The most each part may move in a frame at 120 Hz, a little over its fastest motion
        val limits = listOf(0.5f, 0.3f, 0.2f, 2f, 1.5f, 1f, 3f, 3f, 0.2f, 0.2f, 0.2f)
        fun parts(pose: SniffPose) = with(pose) { listOf(x, bounce, facing, diagonalLegs, neck, head, ear, tail) + scent }
        var last = parts(sniffPose(0f))
        for (frame in 1..(LOOP_SECONDS * 120).toInt()) {
            val parts = parts(sniffPose(frame / 120f))
            parts.indices.forEach { assertEquals("Part $it at frame $frame", last[it], parts[it], limits[it]) }
            last = parts
        }
    }

    @Test
    fun theLegsAreARoundTripFillingTheLoop() {
        // Otherwise the loop's end cuts off the last turn, or Codec stands around turned until it ends
        assertEquals(LOOP_SECONDS, SNIFF_LEGS.sumOf { it.seconds.toDouble() }.toFloat(), 0.001f)
        SNIFF_LEGS.forEachIndexed { index, leg ->
            val next = SNIFF_LEGS[(index + 1) % SNIFF_LEGS.size]
            // Codec doesn't jump, and walks back the way the turn at the stop left it facing
            assertEquals("Leg $index ends where the next starts", leg.to, next.from, 0f)
            assertEquals("Leg $index turns to walk the next", -leg.facing, next.facing, 0f)
            assertTrue("Leg $index sniffs", leg.sniffs > 0)
            // Which it wouldn't, if starting and stopping overlapped
            assertEquals(0f, leg.walked(0f), 0f)
            assertEquals(1f, leg.walked(leg.walkSeconds), 0.001f)
            val walked = times(0f, leg.walkSeconds).map { leg.walked(it) }.toList()
            assertTrue("Leg $index walks on", walked.zipWithNext().all { (before, after) -> after > before })
        }
    }

    @Test
    fun theDogFacesTheWayItWalks() {
        val (left, right) = midWalks
        assertTrue(sniffPose(left - 0.2f).x > sniffPose(left + 0.2f).x)
        assertEquals(1f, sniffPose(left).facing, 0f)
        assertTrue(sniffPose(right - 0.2f).x < sniffPose(right + 0.2f).x)
        assertEquals(-1f, sniffPose(right).facing, 0f)
    }

    @Test
    fun itCrossesTheSofaIn1Point7SecondsAtFullSpeed() {
        val (left) = midWalks
        assertEquals(70f / 1.7f, (sniffPose(left - 0.05f).x - sniffPose(left + 0.05f).x) / 0.1f, 0.01f)
    }

    @Test
    fun theNoseStaysAboveTheFloorAtBothEnds() {
        // The nose's tip at rest, and the flip's middle, in the art's units
        fun noseAt(seconds: Float) = sniffPose(seconds).let { it.x + 90 + (14.5f - 90) * it.facing }
        assertEquals(10f, noseAt(stops[0] + 1), 1f)
        assertEquals(231f, noseAt(stops[1] + 1), 1f)
    }

    @Test
    fun theStopClockStartsWhenTheDogStops() {
        for (stop in stops) {
            assertEquals(0f, sniffMoment(stop).sinceStop, 0.001f)
            assertEquals(0f, sniffMoment(stop).walking, 0.001f)
            assertEquals(sniffMoment(stop).x, sniffMoment(stop + 1).x, 0.01f)
            assertTrue(sniffMoment(stop - 0.1f).sinceStop < 0)
            assertTrue(sniffMoment(stop - 0.1f).walking > 0)
        }
    }

    @Test
    fun itOnlySniffsWhileStanding() {
        midWalks.forEach { assertEquals(listOf(0f, 0f, 0f), sniffPose(it).scent) }
        assertTrue(times(stops[0], stops[0] + 1).any { time -> sniffPose(time).scent.any { it > 0.5f } })
    }

    @Test
    fun thePuffsStartAtTheStop() {
        for (stop in stops) {
            assertEquals(listOf(0f, 0f, 0f), sniffPose(stop - 0.05f).scent)
            assertEquals(0f, sniffPose(stop).scent[0], 0.001f)
            assertTrue(sniffPose(stop + 0.1f).scent[0] > 0.5f)
        }
    }

    @Test
    fun itSniffsAlikeAtBothEnds() {
        val (left, right) = stops
        for (since in times(0f, SNIFF_LEGS.minOf { it.sniffSeconds }, 0.05f)) {
            val atLeft = sniffPose(left + since)
            val atRight = sniffPose(right + since)
            assertEquals(atLeft.neck, atRight.neck, 0.001f)
            assertEquals(atLeft.head, atRight.head, 0.001f)
            atLeft.scent.zip(atRight.scent).forEach { (first, second) -> assertEquals(first, second, 0.001f) }
        }
    }

    @Test
    fun itFinishesSniffingAndPausesBeforeTurning() {
        SNIFF_LEGS.forEachIndexed { index, leg ->
            // Each sniff's puffs are gone before it ends, so the last one's too
            assertTrue(times(sniffsEnd[index] - 0.05f, sniffsEnd[index]).all { time -> sniffPose(time).scent.all { it < 0.01f } })
            val nextStop = stops.getOrElse(index + 1) { stops[0] + LOOP_SECONDS }
            assertTrue("No puffs after leg $index's sniffs",
                times(sniffsEnd[index], nextStop).all { time -> sniffPose(time).scent.all { it < 0.001f } })
            val turnStart = times(sniffsEnd[index], nextStop).first { sniffPose(it).facing != leg.facing }
            val turnEnd = times(turnStart, nextStop).first { sniffPose(it).facing == -leg.facing }
            assertTrue(turnStart - sniffsEnd[index] > 0.1f)
            assertTrue(sniffPose(sniffsEnd[index] - 0.1f).neck < 0)
            // Quickly, like the other motions of the head, and down again by the end of the turn
            assertTrue(times(sniffsEnd[index], sniffsEnd[index] + 0.2f).any { sniffPose(it).neck > 2.5f })
            assertTrue("Nose a little up when leg $index turns", sniffPose(turnStart).neck in 1f..5f)
            assertEquals(0f, sniffPose(turnEnd).neck, 0.5f)
            // Holding, but not frozen, the head bobs on through the turn, between how much it does while walking and
            // sniffing
            for (from in times(sniffsEnd[index], turnEnd - 0.15f, 0.05f)) {
                val heads = times(from, from + 0.15f).map { sniffPose(it).head }.toList()
                assertTrue("Bobbing in leg $index's pause", heads.max() - heads.min() > 0.5f)
            }
            val held = times(sniffsEnd[index] + 0.2f, turnEnd).maxOf { abs(sniffPose(it).head) }
            assertEquals(1.2f, held, 0.05f)
            assertTrue(abs(sniffPose(midWalks[(index + 1) % SNIFF_LEGS.size]).neck) <= 2)
        }
    }

    @Test
    fun theTailWagsMoreWhileWalkingThanWhileSniffing() {
        // Over one wag
        fun wag(around: Float) = times(around - 0.2f, around + 0.2f).maxOf { abs(sniffPose(it).tail) }
        midWalks.forEach { assertTrue(wag(it) > 14) }
        stops.forEach { assertTrue(wag(it + 1) < 6) }
    }

    @Test
    fun theLegsOnlySwingWhileWalking() {
        assertEquals(0f, sniffPose(stops[0] + 1).diagonalLegs, 0f)
        assertTrue(times(midWalks[0] - 0.4f, midWalks[0] + 0.4f).any { abs(sniffPose(it).diagonalLegs) > 10 })
    }
}
