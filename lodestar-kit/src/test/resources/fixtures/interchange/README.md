# Interchange fixtures

Small files in the shapes of the formats `ObservingListCodec` and `SessionLogCodec` read and write. The Kotlin twin's tests
use the same files.

| File | Source |
| --- | --- |
| `telescopius_export.csv` | Header and the first row (M 45) are quoted from a real Telescopius export, as printed in the NINA import error log of https://forum.telescopius.com/t/csv-file-download-issues/1865 (August 2025, Telescopius 250819): 20 columns from "Catalogue Entry" to "Label", J2000 and jNow coordinates, arcseconds written as `""`. The other rows have the same shape and were written by hand. |
| `telescopius_legacy.csv` | The same format as exported before the epoch columns (coordinate columns without "(j2000)" and no jNow columns); written by hand. |
| `generic_degrees.csv`, `generic_sexagesimal.csv` | Hand-written tables: decimal degrees; semicolon separated with a byte order mark, sexagesimal text and bad rows. |
| `skysafari_list.skylist` | The shape of SkySafari 6/7 observing lists: the 3.0 header, `SkyObject=BeginObject` blocks with `ObjectID`, `CommonName`, `CatalogNumber` and `DefaultIndex` keys indented with tabs, `EndObject=SkyObject`. Samples: https://github.com/Stellarium/stellarium/discussions/2308 and the RASC list https://rasc.ca/sites/default/files/RASC%20Finest%20NGC%20-%20Summer%20%28v2%29.skylist.txt. The last two objects (no name, never closed) are hand-made. |
| `session_log_oal.xml` | The expected OpenAstronomyLog 2.1 document of `SessionLogCodecTests`. Written by hand and validated with libxml2 (lxml) against `src/oal21.xsd` of https://github.com/openastronomylog/openastronomylog (schema files `oal21.xsd` sha256 `281b48c2…`, `oal_Base.xsd` `8f97e88c…`; the schema is Apache-2.0). |
