package net.kuecker.lodestar.features.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalUriHandler
import net.kuecker.lodestar.shared.InfoRow
import net.kuecker.lodestar.shared.SettingsCaption
import net.kuecker.lodestar.shared.SettingsDivider
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.SettingsSection
import net.kuecker.lodestar.theme.LocalPalette

/** A third-party data set, model or library that ships inside (or is used by) Lodestar. */
internal data class DataCredit(
    val id: String,
    /** What it is called, e.g. "HYG Database v4.1". */
    val name: String,
    /** What Lodestar uses it for. */
    val usedFor: String,
    val author: String,
    /** The license's short name, e.g. "CC BY-SA 4.0". */
    val license: String,
    val licenseURL: String?,
    val sourceURL: String?,
    /** Further acknowledgements and the changes Lodestar made to the data. */
    val notice: String,
) {
    companion object {
        val all = listOf(
            DataCredit(
                id = "hyg", name = "HYG Database v4.1", usedFor = "Stars",
                author = "astronexus, maintainer of the HYG Database",
                license = "CC BY-SA 4.0", licenseURL = "https://creativecommons.org/licenses/by-sa/4.0/",
                sourceURL = "https://www.astronexus.com/hyg",
                notice = "Compiled by its maintainer from the Hipparcos catalogue (ESA), the Yale Bright Star " +
                    "Catalog and the Gliese–Jahreiss Catalogue of Nearby Stars. Changes for Lodestar: converted to " +
                    "compact JSON; kept every star brighter than magnitude 6.5 and every named star; right " +
                    "ascension in degrees; Bayer designations in Greek letters; Hipparcos right ascensions " +
                    "corrected for proper motion; sorted by magnitude.",
            ),
            DataCredit(
                id = "openngc", name = "OpenNGC", usedFor = "Deep-sky objects",
                author = "Mattia Verga and contributors",
                license = "CC BY-SA 4.0", licenseURL = "https://creativecommons.org/licenses/by-sa/4.0/",
                sourceURL = "https://github.com/mattiaverga/OpenNGC",
                notice = "Compiled from the NASA/IPAC Extragalactic Database (NED), HyperLEDA, SIMBAD (CDS, " +
                    "Strasbourg), HEASARC and Harold Corwin's NGC/IC notes. Changes for Lodestar: converted to " +
                    "compact JSON; selected the Messier objects, all named objects, the catalog objects brighter " +
                    "than magnitude 11.5 and the larger nebulae and clusters; merged duplicates; coordinates in " +
                    "degrees; a few common names added. The Caldwell numbers come from OpenNGC's identifiers.",
            ),
            DataCredit(
                id = "sharpless", name = "Sharpless 2 catalogue", usedFor = "Emission nebulae (H II regions)",
                author = "S. Sharpless, via CDS (VII/20)",
                license = "CDS terms of use", licenseURL = CDS_TERMS_URL,
                sourceURL = "https://cdsarc.cds.unistra.fr/viz-bin/cat/VII/20",
                notice = "S. Sharpless, A Catalogue of H II Regions, Astrophysical Journal Supplement 4, 257 " +
                    "(1959). A factual compilation (positions, sizes, classes) distributed by CDS, Strasbourg, " +
                    "through VizieR.",
            ),
            DataCredit(
                id = "barnard", name = "Barnard dark nebulae", usedFor = "Dark nebulae",
                author = "E. E. Barnard, via CDS (VII/220A)",
                license = "CDS terms of use", licenseURL = CDS_TERMS_URL,
                sourceURL = "https://cdsarc.cds.unistra.fr/viz-bin/cat/VII/220A",
                notice = "E. E. Barnard, A Photographic Atlas of Selected Regions of the Milky Way (1927). The " +
                    "machine-readable version is by J. Takahashi and M. Ohishi (NAOJ), with diameters and notes by " +
                    "Bill Gray and corrections by Mati Morel, distributed by CDS, Strasbourg, through VizieR.",
            ),
            DataCredit(
                id = "lynds", name = "Lynds dark nebulae", usedFor = "Dark nebulae and dark clouds",
                author = "B. T. Lynds, via CDS (VII/7A)",
                license = "CDS terms of use", licenseURL = CDS_TERMS_URL,
                sourceURL = "https://cdsarc.cds.unistra.fr/viz-bin/cat/VII/7A",
                notice = "B. T. Lynds, Catalogue of Dark Nebulae, Astrophysical Journal Supplement 7, 1 (1962), " +
                    "prepared by J. Marcout and F. Ochsenbein from T. Nagy's version at the Astronomical Data " +
                    "Center. Lodestar uses the clouds of opacity 6 or larger than one square degree, and the named " +
                    "ones. Distributed by CDS, Strasbourg, through VizieR.",
            ),
            DataCredit(
                id = "dias", name = "Optically visible open clusters and candidates", usedFor = "Open clusters",
                author = "W. S. Dias and co-authors, via CDS (B/ocl)",
                license = "CDS terms of use", licenseURL = CDS_TERMS_URL,
                sourceURL = "https://cdsarc.cds.unistra.fr/viz-bin/cat/B/ocl",
                notice = "W. S. Dias, B. S. Alessi, A. Moitinho and J. R. D. Lépine, Astronomy & Astrophysics 389, " +
                    "871 (2002), version 3.5. Lodestar uses a selection of Collinder, Melotte, Trumpler and Stock " +
                    "clusters. Distributed by CDS, Strasbourg, through VizieR.",
            ),
            DataCredit(
                id = "iau-boundaries", name = "Constellation boundaries",
                usedFor = "The constellation of a deep-sky object",
                author = "N. G. Roman, after E. Delporte, via CDS (VI/42)",
                license = "CDS terms of use", licenseURL = CDS_TERMS_URL,
                sourceURL = "https://cdsarc.cds.unistra.fr/viz-bin/cat/VI/42",
                notice = "N. G. Roman, Identification of a constellation from a position, Publications of the " +
                    "Astronomical Society of the Pacific 99, 695 (1987), after the boundaries of the IAU adopted " +
                    "from E. Delporte (1930). Used for the objects that OpenNGC does not place in a constellation. " +
                    "Distributed by CDS, Strasbourg, through VizieR.",
            ),
            DataCredit(
                id = "vizier", name = "VizieR catalogue access tool",
                usedFor = "Distribution of the five catalogues above",
                author = "CDS, Strasbourg, France",
                license = "Acknowledgement requested", licenseURL = CDS_TERMS_URL,
                sourceURL = "https://vizier.cds.unistra.fr/",
                notice = "This research has made use of the VizieR catalogue access tool, CDS, Strasbourg, France " +
                    "(DOI: 10.26093/cds/vizier). The original description of the VizieR service was published in " +
                    "2000, A&AS 143, 23.",
            ),
            DataCredit(
                id = "d3-celestial", name = "d3-celestial", usedFor = "Constellation figures and Milky Way outlines",
                author = "Olaf Frohn",
                license = "BSD 3-Clause", licenseURL = "https://opensource.org/license/bsd-3-clause",
                sourceURL = "https://github.com/ofrohn/d3-celestial",
                notice = "Constellation names and figures follow the IAU constellation page. The Milky Way outlines " +
                    "are after Jose R. Vieira's Milky Way Outline Catalog. Lodestar simplified the outlines and " +
                    "corrected a few names; the license text is below.",
            ),
            DataCredit(
                id = "wmm", name = "World Magnetic Model 2025", usedFor = "True north and magnetic interference",
                author = "NOAA NCEI and the British Geological Survey",
                license = "Public domain", licenseURL = null,
                sourceURL = "https://www.ncei.noaa.gov/products/world-magnetic-model",
                notice = "A work of the US Government. Valid from 2025 to 2030.",
            ),
            DataCredit(
                id = "ephemeris", name = "VSOP87 and lunar theory", usedFor = "Planets and Moon",
                author = "Bretagnon & Francou (IMCCE); Jean Meeus",
                license = "Published scientific theories", licenseURL = null, sourceURL = null,
                notice = "VSOP87 planetary theory (Astronomy & Astrophysics 202, 309, 1988) and the lunar theory of " +
                    "Meeus, Astronomical Algorithms, 2nd ed. Planetary magnitudes follow Mallama & Hilton (2018).",
            ),
            DataCredit(
                id = "imo", name = "IMO Meteor Shower Calendar 2026", usedFor = "Meteor showers",
                author = "International Meteor Organization, edited by Jürgen Rendtel",
                license = "Published data, cited with credit", licenseURL = null,
                sourceURL = "https://www.imo.net",
                notice = "The activity periods, peak times, rates, radiant positions and drift, and entry speeds of " +
                    "the meteor showers are entered by hand from the 2026 Meteor Shower Calendar (IMO INFO 3-25, " +
                    "© International Meteor Organization). The radiant drift rates are least-squares fits to the " +
                    "calendar's radiant tables.",
            ),
            DataCredit(
                id = "maplibre", name = "MapLibre Native", usedFor = "Drawing the site map",
                author = "The MapLibre project and contributors",
                license = "BSD 2-Clause", licenseURL = "https://opensource.org/license/bsd-2-clause",
                sourceURL = "https://github.com/maplibre/maplibre-native",
                notice = "An open-source library for drawing vector maps, used unchanged. Its license text is at " +
                    "the link below.",
            ),
            DataCredit(
                id = "openfreemap", name = "OpenFreeMap, OpenMapTiles and OpenStreetMap", usedFor = "Map tiles and styles",
                author = "OpenFreeMap; © OpenMapTiles; © OpenStreetMap contributors",
                license = "ODbL 1.0 (OpenStreetMap data)", licenseURL = "https://opendatacommons.org/licenses/odbl/1-0/",
                sourceURL = "https://openfreemap.org",
                notice = "The site map loads its tiles and styles from OpenFreeMap, and only while the map is open. " +
                    "The tiles follow the OpenMapTiles schema (openmaptiles.org) and contain data © OpenStreetMap " +
                    "contributors (openstreetmap.org/copyright). The map shows this attribution itself, behind its " +
                    "information button.",
            ),
            DataCredit(
                id = "android-libraries", name = "AndroidX and Kotlin", usedFor = "App framework",
                author = "Google and JetBrains",
                license = "Apache 2.0", licenseURL = "https://www.apache.org/licenses/LICENSE-2.0",
                sourceURL = "https://developer.android.com/jetpack/androidx",
                notice = "Android Jetpack (Compose, Activity, Lifecycle, CameraX, Core), the Kotlin standard library, " +
                    "kotlinx.coroutines, kotlinx.serialization and the Material icons for Compose.",
            ),
        )
    }
}

/** CDS's terms for the catalogues it distributes. */
private const val CDS_TERMS_URL = "https://cds.unistra.fr/vizier-org/licences_vizier.html"

/** The credit line from ATTRIBUTION.md, shown first, extended by the Android app's map and library credits. */
private const val CREDIT_LINE =
    "Star data: HYG Database v4.1 (astronexus, CC BY-SA 4.0). Deep-sky data: OpenNGC by Mattia Verga (CC BY-SA " +
        "4.0), compiled from NED, HyperLEDA, SIMBAD, HEASARC and H. Corwin's NGC/IC notes, with the Sharpless 2, " +
        "Barnard and Lynds nebula catalogues, the open clusters of Dias et al. and the constellation boundaries of " +
        "Roman and Delporte, distributed by CDS through VizieR. This research has made use of the VizieR " +
        "catalogue access tool, CDS, Strasbourg, France (DOI: 10.26093/cds/vizier; original description: A&AS " +
        "143, 23, 2000). Constellation figures " +
        "and Milky Way outlines: d3-celestial by Olaf Frohn (BSD-3-Clause), the latter after Jose R. Vieira's " +
        "Milky Way Outline Catalog. Geomagnetic model: WMM2025 by NOAA NCEI and the British Geological Survey " +
        "(public domain). Planetary theory: VSOP87 (Bretagnon & Francou, IMCCE); lunar theory after J. Meeus. " +
        "Maps: tiles and styles by OpenFreeMap, © OpenMapTiles, data © OpenStreetMap contributors (ODbL), drawn " +
        "with MapLibre Native (BSD-2-Clause). Software: AndroidX and Kotlin (Apache-2.0)."

private val bsdLicense = listOf(
    "Copyright (c) 2015, Olaf Frohn\nAll rights reserved.",
    "Redistribution and use in source and binary forms, with or without modification, are permitted provided " +
        "that the following conditions are met:",
    "1. Redistributions of source code must retain the above copyright notice, this list of conditions and the " +
        "following disclaimer.",
    "2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and " +
        "the following disclaimer in the documentation and/or other materials provided with the distribution.",
    "3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or " +
        "promote products derived from this software without specific prior written permission.",
    "THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS \"AS IS\" AND ANY EXPRESS OR IMPLIED " +
        "WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A " +
        "PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR " +
        "ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT " +
        "LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS " +
        "INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR " +
        "TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF " +
        "ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.",
).joinToString("\n\n")

/** The credits and licences of the data and software inside Lodestar, with links to the sources. */
@Composable
internal fun DataCreditsView(onBack: () -> Unit) {
    val palette = LocalPalette.current
    val uriHandler = LocalUriHandler.current
    val open: (String) -> Unit = { url ->
        try {
            uriHandler.openUri(url)
        } catch (_: IllegalArgumentException) {
            // No app on the phone can open web links; there is nothing more to offer.
        }
    }
    SettingsSubScreen("Data Credits", onBack) {
        SettingsSection(title = "Credits", footer = "Links open in your browser.") {
            SettingsRow { Text(CREDIT_LINE, color = palette.primaryText, style = MaterialTheme.typography.bodyMedium) }
        }
        for (credit in DataCredit.all) {
            key(credit.id) {
                SettingsSection(title = credit.name) {
                    SettingsRow { InfoRow("Used for", credit.usedFor) }
                    SettingsDivider()
                    SettingsRow { InfoRow("By", credit.author) }
                    SettingsDivider()
                    SettingsRow { InfoRow("License", credit.license) }
                    SettingsDivider()
                    SettingsRow { SettingsCaption(credit.notice) }
                    credit.licenseURL?.let { url ->
                        SettingsDivider()
                        SettingsActionRow("Read the license", onClick = { open(url) }, icon = Icons.Outlined.Description)
                    }
                    credit.sourceURL?.let { url ->
                        SettingsDivider()
                        SettingsActionRow("Open the source", onClick = { open(url) }, icon = Icons.AutoMirrored.Outlined.OpenInNew)
                    }
                }
            }
        }
        SettingsSection(
            title = "BSD 3-Clause License (d3-celestial)",
            footer = "The CC BY-SA 4.0 data files stay under that license: anyone who passes them on must keep " +
                "these credits, say what they changed, and license them the same way.",
        ) {
            SettingsRow { Text(bsdLicense, color = palette.secondaryText, style = MaterialTheme.typography.labelMedium) }
        }
    }
}
