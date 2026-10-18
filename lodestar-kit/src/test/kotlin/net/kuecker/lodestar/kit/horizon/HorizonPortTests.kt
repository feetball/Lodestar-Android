package net.kuecker.lodestar.kit.horizon

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.horizon.HorizonTestSupport as Support
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Tests that have no Swift counterpart: allocation, the JSON forms inside a settings document, and class semantics. */
class HorizonPortTests {
    /** What an app stores for an observing site: the skyline is optional and absent for a flat horizon. */
    @Serializable
    private data class StoredSite(val name: String, val horizon: HorizonProfile? = null)

    @Test
    fun testTheMaskDoesNotAllocate() {
        val bean = try {
            ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        } catch (_: LinkageError) {
            null
        }
        if (bean == null || !bean.isThreadAllocatedMemorySupported || !bean.isThreadAllocatedMemoryEnabled) return
        val mask = HorizonMask(Support.truthProfile)
        val generator = Support.SeededGenerator(3uL)
        val vectors = DoubleArray(3 * 5_000)
        for (index in 0 until 5_000) {
            val direction = HorizontalCoordinate(360 * generator.nextDouble(), 40 * generator.nextDouble()).unitVector
            vectors[3 * index] = direction.x
            vectors[3 * index + 1] = direction.y
            vectors[3 * index + 2] = direction.z
        }
        var hidden = 0
        fun frame() {
            for (index in 0 until 5_000) {
                if (mask.isBelow(vectors[3 * index], vectors[3 * index + 1], vectors[3 * index + 2])) hidden += 1
            }
        }
        repeat(10) { frame() }
        val before = bean.currentThreadAllocatedBytes
        repeat(10) { frame() }
        val allocated = bean.currentThreadAllocatedBytes - before
        // 50 000 tests; one object per call would be over a megabyte. What is left is the JVM's own bookkeeping.
        assertTrue(allocated < 8 * 1024, "$allocated bytes allocated by the mask")
        assertTrue(hidden > 0)
    }

    @Test
    fun testAProfileInsideAStoredSiteRoundTripsAndAFlatOneIsLeftOut() {
        val json = Json
        val site = StoredSite("Dark ridge", Support.truthProfile)
        val text = json.encodeToString(StoredSite.serializer(), site)
        assertTrue(text.startsWith("""{"name":"Dark ridge","horizon":{"version":1,"altitudeTenths":[20,"""), text.take(80))
        assertEquals(site, json.decodeFromString(StoredSite.serializer(), text))
        // Nothing is stored for a site without a skyline, and a site stored before the feature existed still loads.
        val plain = StoredSite("Backyard")
        assertEquals("""{"name":"Backyard"}""", json.encodeToString(StoredSite.serializer(), plain))
        assertEquals(plain, json.decodeFromString(StoredSite.serializer(), """{"name":"Backyard"}"""))
        assertNull(json.decodeFromString(StoredSite.serializer(), """{"name":"Backyard","horizon":null}""").horizon)
        // A corrupt skyline fails the decode of the site, so the app can drop or repair it.
        assertFailsWith<SerializationException> {
            json.decodeFromString(StoredSite.serializer(), """{"name":"x","horizon":{"version":1,"altitudeTenths":[1,2,3]}}""")
        }
    }

    @Test
    fun testStrictJsonStillIgnoresUnknownKeysOfTheProfile() {
        val strict = Json { ignoreUnknownKeys = false }
        val text = strict.encodeToString(HorizonProfile.serializer(), HorizonProfile.constant(5.0))
        val extended = text.dropLast(1) + ""","note":"added later"}"""
        assertEquals(HorizonProfile.constant(5.0), strict.decodeFromString(HorizonProfile.serializer(), extended))
    }

    @Test
    fun testTheRecorderIsAClassWhoseReferencesShareState() {
        val recorder = HorizonRecorder()
        val alias = recorder
        recorder.add(10.0, 5.0, 0.0)
        assertSame(recorder, alias)
        assertEquals(1, alias.recordedBinCount)
        alias.reset()
        assertEquals(0, recorder.recordedBinCount)
    }

    @Test
    fun testVisibilityObjectsAreValuesThatCompareByProfile() {
        assertEquals(HorizonVisibility(Support.truthProfile), HorizonVisibility(Support.truthProfile))
        assertEquals(HorizonVisibility(Support.truthProfile).hashCode(), HorizonVisibility(Support.truthProfile).hashCode())
        assertTrue(HorizonVisibility(Support.truthProfile) != HorizonVisibility(HorizonProfile.flat))
    }
}
