package net.kuecker.lodestar.features.online

import net.kuecker.lodestar.services.OnlineFeed

/** The words of Settings ▸ Online Data. The iOS app uses the same texts. */
internal object OnlineText {
    const val SECTION_TITLE = "Online Data"

    const val INTRO =
        "Lodestar works fully offline. These optional downloads add satellite passes, streak warnings, comets, a " +
            "cloud forecast and terrain horizons. Everything is off until you switch it on, and every screen stays " +
            "usable without the data."

    const val FOOTER =
        "Switching a feed off deletes what it downloaded. A failed download keeps the data you already have. Nothing " +
            "runs in the background: feeds refresh when you open the app, and terrain horizons download only when you " +
            "ask for one in a site's editor. Each request carries only the app's name and version."

    const val FIRST_TIME_TITLE = "Online data is optional"

    const val FIRST_TIME_BODY =
        "Lodestar works fully offline, and every screen stays usable without these downloads. When you switch a feed " +
            "on, Lodestar makes an ordinary HTTPS request to the provider named under it. The provider sees your IP " +
            "address and the app's name and version. Only the cloud forecast also sends your location, rounded to about " +
            "10 km, and terrain horizons ask for the elevation tiles around a site, so the provider also sees which " +
            "1° tiles (about 100 km wide) you request. Switching a feed off deletes what it downloaded."

    const val FIRST_TIME_CONFIRM = "Turn on"
    const val FIRST_TIME_CANCEL = "Not now"
    const val UPDATE_NOW = "Update now"
    const val UPDATING = "Updating…"
    const val NEVER = "Never"

    /** What the feed is for. */
    fun purpose(feed: OnlineFeed): String = when (feed) {
        OnlineFeed.SATELLITE_PASSES ->
            "Shows when the ISS and other bright satellites cross your sky, on the Tonight screen and in Aim."
        OnlineFeed.STARLINK ->
            "Warns in Aim when Starlink satellites are likely to cross your frame during an exposure."
        OnlineFeed.COMETS -> "Adds the current comets to the object list, the sky chart and Aim."
        OnlineFeed.CLOUDS -> "Shows tonight's hourly cloud cover, dew, wind, fog and temperature on the Tonight screen."
        OnlineFeed.TERRAIN ->
            "Works out the skyline of the hills and mountains around a saved site from elevation data, for sites " +
                "where you have not recorded one. Nothing downloads until you ask in a site's editor."
    }

    /** What leaves the phone: the provider, the size and how often. */
    fun leavesPhone(feed: OnlineFeed): String = when (feed) {
        OnlineFeed.SATELLITE_PASSES ->
            "Downloads orbit data for the ISS and other bright satellites from CelesTrak (celestrak.org): about 75 KB, " +
                "at most every 12 to 24 hours while you use the app. CelesTrak sees your IP address."
        OnlineFeed.STARLINK ->
            "Downloads orbit data for the Starlink satellites from CelesTrak (celestrak.org): about 4.7 MB, once a day, " +
                "only on an unmetered network such as Wi-Fi. CelesTrak sees your IP address."
        OnlineFeed.COMETS ->
            "Downloads the comet orbit file from the Minor Planet Center (minorplanetcenter.net): about 164 KB, at most " +
                "once a day. The Minor Planet Center sees your IP address."
        OnlineFeed.CLOUDS ->
            "Requests the forecast from MET Norway (api.met.no) with your location rounded to 0.1° (about 10 km): about " +
                "64 KB, no more often than MET Norway allows. MET Norway sees your IP address and the rounded location."
        OnlineFeed.TERRAIN ->
            "When you ask a site for its terrain horizon, downloads up to four 1° elevation tiles (about 11 MB each) " +
                "from the AWS Open Data Terrain Tiles (s3.amazonaws.com) and deletes them after the calculation. The " +
                "tile server sees your IP address and which 1° tiles you request, which is where the site is to within " +
                "about 100 km."
    }

    /** How long ago the data was confirmed current: "just now", "5 min ago", "2 h ago", "3 days ago", or "Never". */
    fun since(lastMillis: Long?, nowMillis: Long): String {
        if (lastMillis == null) return NEVER
        val minutes = (nowMillis - lastMillis).coerceAtLeast(0) / 60_000
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 48 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }

    /** "Next update possible after 16:30." */
    fun nextUpdate(timeText: String): String = "Next update possible after $timeText."
}
