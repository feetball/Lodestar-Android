package net.kuecker.lodestar.features.online

import net.kuecker.lodestar.services.OnlineFeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnlineTextTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    @Test
    fun theLastUpdateIsShownRelativeOrAsNever() {
        assertEquals("Never", OnlineText.since(null, now))
        assertEquals("Just now", OnlineText.since(now - 20_000, now))
        assertEquals("Just now", OnlineText.since(now + 5 * minute, now))
        assertEquals("5 min ago", OnlineText.since(now - 5 * minute, now))
        assertEquals("59 min ago", OnlineText.since(now - 59 * minute, now))
        assertEquals("2 h ago", OnlineText.since(now - 125 * minute, now))
        assertEquals("47 h ago", OnlineText.since(now - 47 * 60 * minute, now))
        assertEquals("3 days ago", OnlineText.since(now - 72 * 60 * minute - 1, now))
    }

    @Test
    fun everyFeedSaysWhatItIsForWhatLeavesThePhoneAndWho() {
        val providers = mapOf(
            OnlineFeed.SATELLITE_PASSES to "CelesTrak", OnlineFeed.STARLINK to "CelesTrak",
            OnlineFeed.COMETS to "Minor Planet Center", OnlineFeed.CLOUDS to "MET Norway",
        )
        for (feed in OnlineFeed.entries) {
            val leaves = OnlineText.leavesPhone(feed)
            assertTrue(leaves.contains(providers.getValue(feed)), leaves)
            assertTrue(leaves.contains("KB") || leaves.contains("MB"), leaves)
            assertTrue(leaves.contains("IP address"), leaves)
            assertTrue(OnlineText.purpose(feed).isNotBlank())
        }
        assertTrue(OnlineText.leavesPhone(OnlineFeed.CLOUDS).contains("rounded to 0.1° (about 10 km)"))
        assertTrue(OnlineText.leavesPhone(OnlineFeed.STARLINK).contains("unmetered"))
        assertFalse(OnlineText.leavesPhone(OnlineFeed.SATELLITE_PASSES).contains("location"))
    }

    @Test
    fun theIntroSaysThatTheAppWorksOffline() {
        assertTrue(OnlineText.INTRO.contains("works fully offline"))
        assertTrue(OnlineText.FIRST_TIME_BODY.contains("works fully offline"))
    }

    @Test
    fun theTextsDoNotMentionAnotherPlatform() {
        val texts = OnlineFeed.entries.flatMap { listOf(OnlineText.purpose(it), OnlineText.leavesPhone(it)) } +
            listOf(OnlineText.INTRO, OnlineText.FOOTER, OnlineText.FIRST_TIME_BODY)
        for (text in texts) assertFalse(text.contains("iPhone") || text.contains("iOS"), text)
    }
}
