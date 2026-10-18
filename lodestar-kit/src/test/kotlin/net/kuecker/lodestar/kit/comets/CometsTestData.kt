package net.kuecker.lodestar.kit.comets

/**
 * Real records used as test fixtures (small extracts, not whole files):
 * - comet lines: `CometEls.txt` of the Minor Planet Center, https://www.minorplanetcenter.net/iau/MPCORB/CometEls.txt,
 *   downloaded 2026-10-18 (Last-Modified 08:15 GMT), all elements osculating at 2026-10-17;
 * - asteroid lines: `MPCORB.DAT` (Ceres, Vesta) and `NEA.txt`, https://www.minorplanetcenter.net/iau/MPCORB/, downloaded 2026-10-18;
 * - JSON: answers of https://ssd-api.jpl.nasa.gov/sbdb.api?des=...&phys-par=1&full-prec=1 (2026-10-18), reduced to the
 *   members the parser reads plus a few neighbours;
 * - Horizons rows for the comets above: https://ssd.jpl.nasa.gov/api/horizons.api, OBSERVER table, CENTER=500@399,
 *   TLIST (UT) 2461331.5 and 2461361.5 (2026-10-18 and 2026-11-17), QUANTITIES 1,9,19,20,23,24,27.
 */
internal object CometsTestData {
    /** MPC comet line of C/1995 O1 (Hale-Bopp). */
    val cometHaleBopp: String =
        """    CJ95O010  1997 03 29.0284  0.925995  0.994895  130.7313  281.8097   89.7335  20261017  -2.0  4.0  C/1995 O1 (Hale-Bopp)                                    MPC194091"""
    /** MPC comet line of C/2023 A3 (Tsuchinshan-ATLAS). */
    val cometTsuchinshanAtlas: String =
        """    CK23A030  2024 09 27.8337  0.391312  1.000180  308.5842   21.6759  139.0991  20261017   6.5  3.2  C/2023 A3 (Tsuchinshan-ATLAS)                            MPEC 2026-O98"""
    /** MPC comet line of 12P/Pons-Brooks. */
    val cometPonsBrooks: String =
        """0012P         2024 04 21.2066  0.781920  0.954748  199.0355  255.8678   74.1469  20261017   5.0  6.0  12P/Pons-Brooks                                          MPEC 2026-TI5"""
    /** MPC comet line of 1P/Halley. */
    val cometHalley: String =
        """0001P         2061 08  1.5218  0.570945  0.968030  112.1616   59.2610  162.1917  20261017   5.5  3.2  1P/Halley                                                MPC191592"""
    /** MPC comet line of 2P/Encke. */
    val cometEncke: String =
        """0002P         2027 02 10.2276  0.338603  0.847319  187.2873  334.0197   11.3480  20261017  14.4  4.0  2P/Encke                                                 MPC xxxxx"""
    /** MPC comet line of 3D-A/Biela. */
    val cometBielaA: String =
        """0003D      a  2024 12  2.1425  0.827609  0.767040  192.1566  276.6852    7.2141  20261017  11.0  6.0  3D-A/Biela                                                76, 1135"""
    /** MPC comet line of 18D/Perrine-Mrkos. */
    val cometPerrineMrkos: String =
        """0018D         2024 08 28.912   1.59239   0.58937   238.150   158.452    17.664   20261017  16.0  4.0  18D/Perrine-Mrkos                                         19,   80"""
    /** MPC comet line of 73P-BT/Schwassmann-Wachmann. */
    val cometSchwassmannWachmann3Bt: String =
        """0073P     bt  2027 12 25.1036  0.915911  0.700909  214.4932   52.3435    6.1794  20261017  11.5  6.0  73P-BT/Schwassmann-Wachmann                              MPC106348"""
    /** MPC comet line of 332P-B/Ikeya-Murakami. */
    val cometIkeyaMurakamiB: String =
        """0332P      b  2016 03 17.1650  1.572891  0.489707  152.3711    3.8028    9.3805            19.0  4.0  332P-B/Ikeya-Murakami                                    MPEC 2016-EI8"""
    /** MPC comet line of 1I/`Oumuamua. */
    val cometOumuamua: String =
        """0001I         2017 09  9.4886  0.255240  1.199252  241.6845   24.5997  122.6778  20170904  23.0  2.0  1I/`Oumuamua                                             MPC107687"""
    /** MPC comet line of 2I/Borisov. */
    val cometBorisov: String =
        """0002I         2019 12  9.0572  1.997724  3.345952  209.2911  307.8024   44.2624  20251121  11.0  4.0  2I/Borisov                                               MPEC 2025-R69"""
    /** MPC comet line of 3I/ATLAS. */
    val cometAtlas3i: String =
        """0003I         2025 10 29.4825  1.356507  6.139884  128.0055  322.1535  175.1129  20251121   8.0  4.0  3I/ATLAS                                                 MPEC 2026-G41"""
    /** MPC comet line of C/2013 A1 (SidingSpring). */
    val cometSidingSpring: String =
        """    CK13A010  2014 10 26.8227  1.391249  1.000073    2.5353  301.1848  128.6857  20261017   8.2  2.4  C/2013 A1 (SidingSpring)                                 MPC194130"""
    /** MPC comet line of A/2018 W3. */
    val cometAsteroidLike: String =
        """    AK18W030  2021 04 13.1790  4.274560  0.993519  313.7724  251.6525  104.8220  20261017  10.5  2.0  A/2018 W3                                                MPC194158"""
    /** MPC comet line of P/2026 N2. */
    val cometUnnamedPeriodic: String =
        """    PK26N020  2026 09 25.7701  2.422069  0.212174  218.8645   96.3812    2.2496  20261017  17.4  4.0  P/2026 N2                                                MPEC 2026-Q95"""
    /** MPC comet line of 161P/Hartley-IRAS. */
    val cometHartleyIras: String =
        """0161P         2026 11 27.1783  1.265109  0.836180   47.0652    1.4736   95.7919  20261017   8.5  6.0  161P/Hartley-IRAS                                        MPEC 2026-TP0"""
    /** MPC comet line of 78P/Gehrels. */
    val cometGehrels: String =
        """0078P         2026 06 25.1355  2.004570  0.462933  192.8100  210.4913    6.2574  20261017   5.5  8.0  78P/Gehrels                                              MPEC 2026-TP0"""
    /** MPC comet line of 29P/Schwassmann-Wachmann. */
    val cometSchwassmannWachmann: String =
        """0029P         2034 03 15.1855  5.797924  0.042915   51.2459  312.3998    9.3549  20261017   4.0  4.0  29P/Schwassmann-Wachmann                                 MPEC 2026-RA1"""
    /** MPC comet line of C/2014 UN271 (Bernardinelli-Bernstein). */
    val cometBernardinelliBernstein: String =
        """    CK14UR1N  2031 01 16.6692 10.959316  1.004054  326.0949  190.0073   95.4457  20261017   2.5  3.2  C/2014 UN271 (Bernardinelli-Bernstein)                   MPEC 2026-TP0"""
    /** MPC comet line of C/2024 E1 (Wierzchos). */
    val cometWierzchos: String =
        """    CK24E010  2026 01 20.8145  0.565779  1.000381  243.6172  108.0685   75.1999  20261017   7.0  4.0  C/2024 E1 (Wierzchos)                                    MPEC 2026-O98"""
    /** MPC comet line of P/2026 R2 (Leonard). */
    val cometLeonard: String =
        """    PK26R020  2026 10 25.2362  0.789531  0.898740  205.5263  179.2306   78.0993  20261017  16.0  4.0  P/2026 R2 (Leonard)                                      MPC xxxxx"""
    /** MPC minor-planet line of (1) Ceres. */
    val asteroidCeres: String =
        """00001    3.34  0.15 K2669 274.41935   73.29420   80.24863   10.58803  0.0796923  0.21430445   2.7655526  0 E2026-SD4  7376 127 1801-2026 0.82 M-v 30k MPCORBFIT  4000      (1) Ceres              20260922"""
    /** MPC minor-planet line of (4) Vesta. */
    val asteroidVesta: String =
        """00004    3.25  0.15 K2669  81.19019  151.46856  103.70135    7.14393  0.0902037  0.27161836   2.3613660  0 E2026-T23  7610 112 1821-2025 0.84 M-p 18k Pan        4000      (4) Vesta              20250624"""
    /** MPC minor-planet line of (433) Eros. */
    val asteroidEros: String =
        """00433   10.39  0.15 K2669  62.51145  178.91814  304.26797   10.82855  0.2228780  0.55970463   1.4582437  0 E2026-TG8 18065  59 1893-2026 0.61 M-v 3Ek MPCORBFIT  1804    (433) Eros               20260919"""
    /** MPC minor-planet line of (99942) Apophis. */
    val asteroidApophis: String =
        """99942   19.00  0.15 K2669 175.33041  126.67957  203.89365    3.34100  0.1911492  1.11263811   0.9223592  0 E2026-T09  9417  13 2004-2021 0.50 M-v 3Ek Pan        8802  (99942) Apophis            20210520"""
    /** MPC minor-planet line of (101955) Bennu. */
    val asteroidBennu: String =
        """A1955   20.68  0.15 K2669  72.45164   66.41057    1.96656    6.03295  0.2036822  0.82494496   1.1259508  0 E2026-T23   590   7 1999-2025 0.74 M-v 3Ek Pan        8803 (101955) Bennu              20250428"""
    /** MPC minor-planet line of 2024 YR4. */
    val asteroidYr42024: String =
        """K24Y04R 23.93  0.15 K2669 139.21182  134.36645  271.36696    3.40806  0.6613330  0.24696810   2.5159894  1 E2026-TG8   509   2 2024-2026 0.46 M-v 3Ek Pan        0803          2024 YR4           20260226"""
    /** MPC minor-planet line of 1991 TT. */
    val asteroidUnknownH: String =
        """J91T00T 99.99  0.15 K2669 176.22907  218.04073  192.31551   14.79902  0.1614954  0.75483638   1.1946321  7 E2026-TC5     6   1    3 days 0.86 M-v 3Ek Pan        2804          1991 TT            19911009"""
    /** MPC minor-planet line of 2006 WO130. */
    val asteroidAssumedEccentricity: String =
        """K06WD0O 26.1   0.15 K06BL 337.88753  230.31956  246.43012   12.34392  0.4559844  0.45908031   1.6642182  E MPO111441    12   1    0 days 0.35         MPCK       2803          2006 WO130         20061127"""
    /** MPC minor-planet line of (202411) 2005 RC. */
    val asteroidNumberedUnnamed: String =
        """K2411   17.77  0.15 K2669 232.88403   40.60291  191.93219   16.25005  0.7520839  0.31220785   2.1519896  0 E2026-TK4  2351   9 1979-2024 0.45 M-v 3Ek Pan        1803 (202411) 2005 RC            20241106"""
    /** MPC minor-planet line of 2024 AA. */
    val asteroidYearEndEpochAa: String =
        """K24A00A 27.41  0.15 K2669 269.98272   93.09215   95.94992    2.13080  0.7220269  0.32014689   2.1162641  7 E2026-T09    28   1    1 days 1.08 M-v 3Ek Pan        2803          2024 AA            20240102"""
    /** Small-Body Database answer for vesta. */
    val jsonVesta: String = """
{
 "object": {"des": "4", "prefix": null, "orbit_id": "36", "pha": false, "neo": false, "shortname": "4 Vesta", "orbit_class": {"name": "Main-belt Asteroid", "code": "MBA"}, "kind": "an", "spkid": "20000004", "fullname": "4 Vesta (A807 FA)"},
 "signature": {"version": "1.3", "source": "NASA/JPL Small-Body Database (SBDB) API"},
 "orbit": {"epoch": "2461200.5", "equinox": "J2000", "orbit_id": "36", "soln_date": "2021-04-13 11:15:57",
  "elements": [
   {"label": "e", "value": ".09020374382834395", "units": null, "title": "eccentricity", "sigma": "2.2909E-10", "name": "e"},
   {"sigma": "1.5328E-9", "name": "a", "title": "semi-major axis", "value": "2.361365965127599", "units": "au", "label": "a"},
   {"sigma": "1.9335E-9", "name": "q", "label": "q", "value": "2.148361914524259", "units": "au", "title": "perihelion distance"},
   {"name": "i", "sigma": "2.1704E-7", "units": "deg", "value": "7.143925545058711", "label": "i", "title": "inclination; angle with respect to x-y ecliptic plane"},
   {"name": "om", "sigma": "3.8827E-7", "label": "node", "units": "deg", "value": "103.701293265032", "title": "longitude of the ascending node"},
   {"name": "w", "sigma": "1.8814E-7", "label": "peri", "units": "deg", "value": "151.4686478221564", "title": "argument of perihelion"},
   {"label": "M", "units": "deg", "value": "81.19015607686903", "title": "mean anomaly", "name": "ma", "sigma": "1.9021E-6"},
   {"label": "tp", "units": "TDB", "value": "2460901.587379842988", "title": "time of perihelion passage", "name": "tp", "sigma": "6.7119E-6"},
   {"sigma": "1.2905E-6", "name": "per", "title": "sidereal orbital period", "label": "period", "value": "1325.389042911101", "units": "d"},
   {"sigma": "2.6447E-10", "name": "n", "value": ".2716183613599909", "units": "deg/d", "label": "n", "title": "mean motion"},
   {"title": "aphelion distance", "value": "2.574370015730939", "units": "au", "label": "Q", "sigma": "1.6711E-9", "name": "ad"}
  ],
  "model_pars": []},
 "phys_par": [
  {"sigma": null, "name": "H", "ref": "E2024D82", "value": "3.25", "units": null, "notes": "IRAS observations used: 2", "desc": "absolute magnitude (magnitude at 1 au from Sun and observer)", "title": "absolute magnitude"},
  {"notes": "Fit", "units": null, "value": "0.32", "title": "magnitude slope", "desc": "magnitude slope parameter used in the standard asteroid H/G magnitude law", "name": "G", "sigma": null, "ref": "PDS3 (MPC 17257)"},
  {"ref": "Park, R.S. et al. 2025, Nat Astron, DOI: 10.1038/s41550-025-02533-7", "sigma": "0.1", "name": "diameter", "desc": "effective body diameter", "title": "diameter", "value": "522.77", "units": "km", "notes": null}
 ]
}""".trimStart()
    /** Small-Body Database answer for ceres. */
    val jsonCeres: String = """
{
 "object": {"des": "1", "pha": false, "fullname": "1 Ceres (A801 AA)", "shortname": "1 Ceres", "orbit_class": {"name": "Main-belt Asteroid", "code": "MBA"}, "orbit_id": "48", "spkid": "20000001", "kind": "an", "neo": false, "prefix": null},
 "signature": {"version": "1.3", "source": "NASA/JPL Small-Body Database (SBDB) API"},
 "orbit": {"epoch": "2461200.5", "equinox": "J2000", "orbit_id": "48", "soln_date": "2021-04-13 11:04:44",
  "elements": [
   {"label": "e", "title": "eccentricity", "name": "e", "units": null, "value": ".07969229514816586", "sigma": "4.7755E-12"},
   {"units": "au", "name": "a", "value": "2.765552595034094", "title": "semi-major axis", "label": "a", "sigma": "1.0134E-11"},
   {"sigma": "1.9113E-11", "value": "2.545159361382861", "units": "au", "name": "q", "title": "perihelion distance", "label": "q"},
   {"sigma": "4.6124E-9", "units": "deg", "name": "i", "value": "10.58802780183462", "title": "inclination; angle with respect to x-y ecliptic plane", "label": "i"},
   {"label": "node", "value": "80.24862682043221", "units": "deg", "name": "om", "title": "longitude of the ascending node", "sigma": "6.1807E-8"},
   {"sigma": "6.6076E-8", "label": "peri", "name": "w", "units": "deg", "value": "73.29421453021587", "title": "argument of perihelion"},
   {"sigma": "6.1728E-9", "title": "mean anomaly", "value": "274.4193463761342", "name": "ma", "units": "deg", "label": "M"},
   {"label": "tp", "title": "time of perihelion passage", "units": "TDB", "name": "tp", "value": "2461599.841466614066", "sigma": "2.8446E-8"},
   {"sigma": "9.2333E-9", "label": "period", "title": "sidereal orbital period", "value": "1679.853119758983", "units": "d", "name": "per"},
   {"sigma": "1.1779E-12", "title": "mean motion", "value": ".21430445064843", "units": "deg/d", "name": "n", "label": "n"},
   {"title": "aphelion distance", "units": "au", "name": "ad", "value": "2.985945828685327", "label": "Q", "sigma": "1.0942E-11"}
  ],
  "model_pars": []},
 "phys_par": [
  {"notes": "IRAS observations used: 15", "sigma": null, "ref": "MPO980521", "title": "absolute magnitude", "units": null, "name": "H", "value": "3.34", "desc": "absolute magnitude (magnitude at 1 au from Sun and observer)"},
  {"notes": "Fit", "sigma": null, "ref": "PDS3 (MPC 17257)", "title": "magnitude slope", "units": null, "name": "G", "value": "0.12", "desc": "magnitude slope parameter used in the standard asteroid H/G magnitude law"},
  {"title": "diameter", "value": "939.4", "units": "km", "name": "diameter", "desc": "effective body diameter", "sigma": "0.2", "notes": null, "ref": "Nature vol. 537, pp515-517 (22 September 2016)"}
 ]
}""".trimStart()
    /** Small-Body Database answer for pons_brooks. */
    val jsonPonsBrooks: String = """
{
 "object": {"des": "12P", "orbit_id": "K242/85", "prefix": "P", "spkid": "1000068", "pha": false, "orbit_class": {"name": "Halley-type Comet*", "code": "HTC"}, "neo": true, "fullname": "12P/Pons-Brooks", "kind": "cn"},
 "signature": {"version": "1.3", "source": "NASA/JPL Small-Body Database (SBDB) API"},
 "orbit": {"epoch": "2460211.5", "equinox": "J2000", "orbit_id": "K242/85", "soln_date": "2026-10-09 14:40:06",
  "elements": [
   {"sigma": "1.0709E-8", "title": "eccentricity", "name": "e", "value": ".954561244241766", "units": null, "label": "e"},
   {"label": "a", "units": "au", "value": "17.18491452391846", "title": "semi-major axis", "name": "a", "sigma": "2.3117E-7"},
   {"sigma": "1.8411E-7", "name": "q", "title": "perihelion distance", "value": ".7808611337784586", "units": "au", "label": "q"},
   {"title": "inclination; angle with respect to x-y ecliptic plane", "name": "i", "value": "74.19091025149504", "sigma": "4.7547E-6", "units": "deg", "label": "i"},
   {"units": "deg", "label": "node", "sigma": "1.5432E-5", "title": "longitude of the ascending node", "name": "om", "value": "255.8553515784109"},
   {"label": "peri", "units": "deg", "sigma": "1.0395E-5", "value": "198.9879991135792", "title": "argument of perihelion", "name": "w"},
   {"value": "357.0928096847352", "name": "ma", "title": "mean anomaly", "sigma": "2.1953E-7", "label": "M", "units": "deg"},
   {"name": "tp", "title": "time of perihelion passage", "value": "2460421.631157980603", "sigma": "1.5145E-5", "units": "TDB", "label": "tp"},
   {"sigma": ".00052504", "title": "sidereal orbital period", "name": "per", "value": "26020.73090152191", "units": "d", "label": "period"},
   {"units": "deg/d", "label": "n", "sigma": "2.7916E-10", "title": "mean motion", "name": "n", "value": ".01383512251682923"},
   {"label": "Q", "units": "au", "sigma": "4.5183E-7", "value": "33.58896791405846", "title": "aphelion distance", "name": "ad"}
  ],
  "model_pars": []},
 "phys_par": [
  {"ref": "MPC data tape", "desc": "absolute magnitude of comet and coma (i.e. total)", "value": "5.", "notes": null, "units": null, "title": "comet total magnitude", "name": "M1", "sigma": null},
  {"units": null, "title": "comet total magnitude slope", "name": "K1", "sigma": null, "ref": "MPC data tape", "desc": "comet total magnitude slope parameter", "notes": null, "value": "15.0"},
  {"desc": "absolute magnitude of comet nucleus", "ref": "MPC data tape", "notes": null, "value": "11.", "units": null, "sigma": null, "name": "M2", "title": "comet nuclear magnitude"},
  {"desc": "comet nuclear magnitude slope parameter", "ref": "MPC data tape", "notes": null, "value": "10.0", "units": null, "sigma": null, "name": "K2", "title": "comet nuclear magnitude slope"}
 ]
}""".trimStart()
    /** Small-Body Database answer for tsuchinshan_atlas. */
    val jsonTsuchinshanAtlas: String = """
{
 "object": {"neo": false, "orbit_class": {"code": "HYP", "name": "Hyperbolic Comet"}, "kind": "cu", "fullname": "C/2023 A3 (Tsuchinshan-ATLAS)", "des": "2023 A3", "pha": false, "orbit_id": "113", "prefix": "C", "spkid": "1003913"},
 "signature": {"version": "1.3", "source": "NASA/JPL Small-Body Database (SBDB) API"},
 "orbit": {"epoch": "2460448.5", "equinox": "J2000", "orbit_id": "113", "soln_date": "2026-07-28 14:12:17",
  "elements": [
   {"sigma": "2.2442E-7", "name": "e", "title": "eccentricity", "value": "1.000095368540586", "units": null, "label": "e"},
   {"name": "a", "title": "semi-major axis", "value": "-4104.394095058612", "sigma": "9.6552", "units": "au", "label": "a"},
   {"label": "q", "units": "au", "sigma": "3.883E-7", "value": ".3914300748355564", "title": "perihelion distance", "name": "q"},
   {"label": "i", "units": "deg", "value": "139.112109080566", "name": "i", "title": "inclination; angle with respect to x-y ecliptic plane", "sigma": "6.0595E-6"},
   {"units": "deg", "label": "node", "sigma": "5.8117E-6", "title": "longitude of the ascending node", "name": "om", "value": "21.55947897244586"},
   {"units": "deg", "label": "peri", "sigma": "3.3149E-5", "title": "argument of perihelion", "name": "w", "value": "308.4917649633916"},
   {"title": "mean anomaly", "name": "ma", "value": "-.0004975480989684438", "sigma": "1.7557E-6", "units": "deg", "label": "M"},
   {"units": "TDB", "label": "tp", "sigma": "2.4798E-5", "title": "time of perihelion passage", "name": "tp", "value": "2460581.240845175775"},
   {"sigma": null, "value": null, "name": "per", "title": "sidereal orbital period", "label": "period", "units": "d"},
   {"name": "n", "title": "mean motion", "value": "3.748266769806928E-6", "sigma": "1.3226E-8", "units": "deg/d", "label": "n"},
   {"units": "au", "label": "Q", "sigma": null, "title": "aphelion distance", "name": "ad", "value": null}
  ],
  "model_pars": [
   {"n": 1, "desc": "non-gravitational radial acceleration parameter", "value": "1.418905010750004E-8", "units": "au/d^2", "sigma": "1.789E-10", "title": "non-grav. radial accel.", "kind": "EST", "name": "A1"},
   {"name": "A2", "title": "non-grav. transverse accel.", "kind": "EST", "sigma": "1.991E-11", "units": "au/d^2", "value": "1.347781281066188E-9", "desc": "non-gravitational transverse acceleration parameter", "n": 2}
  ]},
 "phys_par": [
  {"ref": "113", "desc": "absolute magnitude of comet and coma (i.e. total)", "value": "8.9", "notes": "2 parameter fit from 6314 observations, autocmod 3.0g", "units": null, "name": "M1", "title": "comet total magnitude", "sigma": "1."},
  {"desc": "comet total magnitude slope parameter", "ref": "113", "value": "5.5", "notes": "autocmod 3.0g", "units": null, "sigma": null, "title": "comet total magnitude slope", "name": "K1"}
 ]
}""".trimStart()
    /** Small-Body Database answer for halley. */
    val jsonHalley: String = """
{
 "object": {"kind": "cn", "spkid": "1000036", "orbit_id": "75", "neo": true, "prefix": "P", "pha": false, "des": "1P", "orbit_class": {"name": "Halley-type Comet*", "code": "HTC"}, "fullname": "1P/Halley"},
 "signature": {"source": "NASA/JPL Small-Body Database (SBDB) API", "version": "1.3"},
 "orbit": {"epoch": "2439875.5", "equinox": "J2000", "orbit_id": "75", "soln_date": "2025-11-21 15:57:34",
  "elements": [
   {"units": null, "name": "e", "value": ".9679359956953211", "title": "eccentricity", "label": "e", "sigma": "7.5527E-9"},
   {"value": "17.92863504856923", "units": "au", "name": "a", "title": "semi-major axis", "label": "a", "sigma": "9.9587E-9"},
   {"label": "q", "value": ".5748638313743413", "name": "q", "units": "au", "title": "perihelion distance", "sigma": "1.3552E-7"},
   {"label": "i", "title": "inclination; angle with respect to x-y ecliptic plane", "value": "162.1905300439129", "units": "deg", "name": "i", "sigma": "1.5093E-5"},
   {"label": "node", "value": "59.09894720612437", "name": "om", "units": "deg", "title": "longitude of the ascending node", "sigma": "1.9861E-5"},
   {"sigma": "2.0534E-5", "title": "argument of perihelion", "units": "deg", "name": "w", "value": "112.2414314637764", "label": "peri"},
   {"title": "mean anomaly", "name": "ma", "units": "deg", "value": "274.3823371366792", "label": "M", "sigma": "1.2457E-7"},
   {"sigma": "1.2976E-5", "value": "2446469.973616146677", "units": "TDB", "name": "tp", "title": "time of perihelion passage", "label": "tp"},
   {"title": "sidereal orbital period", "value": "27728.04608790421", "name": "per", "units": "d", "label": "period", "sigma": "2.3103E-5"},
   {"sigma": "1.0818E-11", "label": "n", "name": "n", "units": "deg/d", "value": ".01298324443268444", "title": "mean motion"},
   {"sigma": "1.9598E-8", "label": "Q", "title": "aphelion distance", "value": "35.28240626576412", "name": "ad", "units": "au"}
  ],
  "model_pars": [
   {"n": 1, "sigma": "4.E-11", "desc": "non-gravitational radial acceleration parameter", "kind": "EST", "title": "non-grav. radial accel.", "value": "4.887055280193953E-10", "units": "au/d^2", "name": "A1"},
   {"title": "non-grav. transverse accel.", "units": "au/d^2", "name": "A2", "value": "1.554720285182461E-10", "desc": "non-gravitational transverse acceleration parameter", "kind": "EST", "sigma": "4.575E-15", "n": 2},
   {"n": 3, "sigma": "27.33", "desc": "offset between the center-of-light and center-of-mass", "kind": "EST", "title": "center-of-light/mass offset", "value": "577.878759926215", "name": "S0", "units": "km"}
  ]},
 "phys_par": [
  {"desc": "absolute magnitude of comet and coma (i.e. total)", "value": "5.5", "name": "M1", "units": null, "title": "comet total magnitude", "ref": "ICQ 2005 Handbook", "sigma": null, "notes": null},
  {"value": "8.0", "units": null, "name": "K1", "title": "comet total magnitude slope", "desc": "comet total magnitude slope parameter", "sigma": null, "notes": null, "ref": "ICQ 2005 Handbook"},
  {"notes": null, "sigma": null, "ref": "ICQ 2005 Handbook", "title": "comet nuclear magnitude", "units": null, "name": "M2", "value": "13.6", "desc": "absolute magnitude of comet nucleus"},
  {"desc": "comet nuclear magnitude slope parameter", "title": "comet nuclear magnitude slope", "name": "K2", "units": null, "value": "5.0", "ref": "ICQ 2005 Handbook", "notes": null, "sigma": null},
  {"desc": "comet nuclear magnitude law phase coefficient", "title": "comet nuclear phase coefficient", "value": "0.03", "name": "PC", "units": null, "ref": "ICQ 2005 Handbook", "sigma": null, "notes": null},
  {"ref": "Lamy,P.L.;Toth,I.;Fernandez,Y.R.;Weaver,H.A. (2004) Comets II, pp. 223-264", "sigma": null, "notes": null, "desc": "effective body diameter", "value": "11.0", "units": "km", "name": "diameter", "title": "diameter"}
 ]
}""".trimStart()
    /** Small-Body Database answer for atlas_3i. */
    val jsonAtlas3i: String = """
{
 "object": {"neo": false, "orbit_class": {"name": "Hyperbolic Comet", "code": "HYP"}, "kind": "cu", "fullname": "C/2025 N1 (ATLAS)", "des": "2025 N1", "pha": false, "orbit_id": "54", "prefix": "C", "spkid": "1004083"},
 "signature": {"version": "1.3", "source": "NASA/JPL Small-Body Database (SBDB) API"},
 "orbit": {"epoch": "2461090.5", "equinox": "J2000", "orbit_id": "54", "soln_date": "2026-02-19 09:10:47",
  "elements": [
   {"label": "e", "units": null, "sigma": "2.469E-5", "value": "6.141351449317625", "title": "eccentricity", "name": "e"},
   {"value": "-.2638374502507929", "title": "semi-major axis", "name": "a", "sigma": "6.9616E-7", "label": "a", "units": "au"},
   {"sigma": "3.7743E-6", "title": "perihelion distance", "name": "q", "value": "1.356481057231181", "units": "au", "label": "q"},
   {"units": "deg", "label": "i", "sigma": "1.9023E-5", "name": "i", "title": "inclination; angle with respect to x-y ecliptic plane", "value": "175.1164570850441"},
   {"label": "node", "units": "deg", "sigma": "7.0591E-5", "value": "322.1696089290778", "title": "longitude of the ascending node", "name": "om"},
   {"units": "deg", "label": "peri", "name": "w", "title": "argument of perihelion", "value": "128.0228697185194", "sigma": ".00010119"},
   {"sigma": ".002878", "value": "818.22024580252", "name": "ma", "title": "mean anomaly", "label": "M", "units": "deg"},
   {"value": "2460977.995262847653", "name": "tp", "title": "time of perihelion passage", "sigma": "5.0807E-5", "label": "tp", "units": "TDB"},
   {"units": "d", "label": "period", "name": "per", "title": "sidereal orbital period", "value": null, "sigma": null},
   {"units": "deg/d", "label": "n", "name": "n", "title": "mean motion", "value": "7.27276260993825", "sigma": "2.8785E-5"},
   {"name": "ad", "title": "aphelion distance", "value": null, "sigma": null, "units": "au", "label": "Q"}
  ],
  "model_pars": [
   {"units": "au/d^2", "sigma": "1.22E-9", "name": "A1", "title": "non-grav. radial accel.", "kind": "EST", "n": 1, "desc": "non-gravitational radial acceleration parameter", "value": "5.320206310958365E-8"},
   {"name": "A2", "kind": "EST", "title": "non-grav. transverse accel.", "sigma": "2.139E-9", "units": "au/d^2", "value": "1.148166024648843E-8", "n": 2, "desc": "non-gravitational transverse acceleration parameter"},
   {"n": 3, "desc": "non-gravitational normal acceleration parameter", "value": "-6.854491622221589E-9", "units": "au/d^2", "sigma": "1.776E-10", "title": "non-grav. normal accel.", "name": "A3", "kind": "EST"},
   {"title": "normalizing factor", "name": "ALN", "kind": "SET", "sigma": null, "units": null, "value": "1.", "n": 0, "desc": "normalizing factor [default 0.1112620426]"},
   {"value": "9.478815477761261", "n": 4, "desc": "non-gravitational perihelion-maximum offset", "sigma": "1.426", "name": "DT", "title": "non-grav. perihelion offset", "kind": "EST", "units": "d"},
   {"desc": "non-grav. model constant, exponent k [default 4.6142]", "n": 0, "value": "0.", "units": null, "sigma": null, "title": "non-grav. model exp. k", "name": "NK", "kind": "SET"},
   {"n": 0, "desc": "non-grav. model constant, exponent m [default 2.15]", "value": "2.", "units": null, "name": "NM", "kind": "SET", "title": "non-grav. model exp. m", "sigma": null},
   {"name": "NN", "title": "non-grav. model exp. n", "kind": "SET", "sigma": null, "units": null, "value": "0.", "n": 0, "desc": "non-grav. model constant, exponent n [default 5.093]"},
   {"value": "1.", "n": 0, "desc": "normalizing distance, au [default 2.808 au]", "sigma": null, "title": "normalizing distance", "name": "R0", "kind": "SET", "units": "au"}
  ]},
 "phys_par": [
  {"units": null, "sigma": "0.9", "title": "comet total magnitude", "name": "M1", "desc": "absolute magnitude of comet and coma (i.e. total)", "ref": "54", "notes": "2 parameter fit from 733 observations, autocmod 3.0g", "value": "12.5"},
  {"units": null, "sigma": null, "name": "K1", "title": "comet total magnitude slope", "desc": "comet total magnitude slope parameter", "ref": "54", "notes": "autocmod 3.0g", "value": "4.5"}
 ]
}""".trimStart()
    /** Small-Body Database answer for yr4_2024. */
    val jsonYr42024: String = """
{
 "object": {"pha": false, "neo": true, "des": "2024 YR4", "prefix": null, "orbit_id": "80", "spkid": "54509621", "fullname": "(2024 YR4)", "orbit_class": {"name": "Apollo", "code": "APO"}, "kind": "au"},
 "signature": {"version": "1.3", "source": "NASA/JPL Small-Body Database (SBDB) API"},
 "orbit": {"epoch": "2461200.5", "equinox": "J2000", "orbit_id": "80", "soln_date": "2026-07-28 09:44:17",
  "elements": [
   {"sigma": "3.5167E-8", "name": "e", "value": ".6613330031901985", "units": null, "label": "e", "title": "eccentricity"},
   {"label": "a", "value": "2.515989042462611", "units": "au", "title": "semi-major axis", "sigma": "2.6541E-7", "name": "a"},
   {"sigma": "2.6777E-8", "name": "q", "value": ".8520824530171806", "units": "au", "label": "q", "title": "perihelion distance"},
   {"title": "inclination; angle with respect to x-y ecliptic plane", "label": "i", "units": "deg", "value": "3.408056116054586", "name": "i", "sigma": "9.9471E-7"},
   {"name": "om", "sigma": "8.1145E-6", "title": "longitude of the ascending node", "label": "node", "units": "deg", "value": "271.3669515560151"},
   {"title": "argument of perihelion", "value": "134.3664504369954", "units": "deg", "label": "peri", "sigma": "8.1366E-6", "name": "w"},
   {"name": "ma", "sigma": "2.245E-5", "title": "mean anomaly", "label": "M", "units": "deg", "value": "139.2118467324958"},
   {"name": "tp", "sigma": "3.1684E-6", "label": "tp", "units": "TDB", "value": "2460636.816608526811", "title": "time of perihelion passage"},
   {"name": "per", "sigma": ".00023066", "title": "sidereal orbital period", "units": "d", "value": "1457.677817609034", "label": "period"},
   {"name": "n", "sigma": "3.9079E-8", "label": "n", "units": "deg/d", "value": ".2469681541772326", "title": "mean motion"},
   {"sigma": "4.4094E-7", "name": "ad", "title": "aphelion distance", "label": "Q", "value": "4.179895631908042", "units": "au"}
  ],
  "model_pars": []},
 "phys_par": [
  {"value": "23.93", "notes": "autocmod 3.0f", "units": null, "desc": "absolute magnitude (magnitude at 1 au from Sun and observer)", "title": "absolute magnitude", "sigma": ".27787", "name": "H", "ref": "E2026E32"}
 ]
}""".trimStart()

    /** A row of the Horizons table for an MPC comet: astrometric position and the distances. */
    class MpcRow(val dayOffset: Double, val ra: Double, val dec: Double, val sunDistance: Double, val earthDistance: Double,
        val elongation: Double, val phaseAngle: Double, val tailPositionAngle: Double, val magnitude: Double)

    /** Horizons (JPL magnitude parameters M1=15.2, K1=4.5) for hartley_iras. */
    val horizonsHartleyIras: List<MpcRow> = listOf(
        MpcRow(0.0, 312.961067275, 8.051394991, 1.3821965916, 0.66091729588438, 111.4082, 42.1643, 83.002, 14.933),
        MpcRow(30.0, 291.66722518, 17.3998239, 1.273098692265, 1.23088471413244, 69.0089, 46.4884, 61.14, 16.123),
    )
    /** Horizons (JPL magnitude parameters M1=9.1, K1=13.25) for gehrels. */
    val horizonsGehrels: List<MpcRow> = listOf(
        MpcRow(0.0, 116.025240017, 15.302927074, 2.21045523598, 1.98904051763612, 89.0811, 26.7965, 280.009, 15.158),
        MpcRow(30.0, 121.994801467, 12.333106536, 2.317417716185, 1.75037379311169, 112.5639, 23.2114, 285.488, 15.152),
    )
    /** Horizons (JPL magnitude parameters M1=10.1, K1=4.5) for schwassmann_wachmann. */
    val horizonsSchwassmannWachmann: List<MpcRow> = listOf(
        MpcRow(0.0, 183.907719942, -8.702469572, 6.317833568061, 7.2559387949822, 18.3385, 2.8415, 273.65, 18.006),
        MpcRow(30.0, 188.561858106, -11.111363119, 6.31751693406, 7.01153855312121, 42.4694, 6.0649, 285.829, 17.932),
    )
    /** Horizons (JPL magnitude parameters M1=5.6, K1=4.25) for bernardinelli_bernstein. */
    val horizonsBernardinelliBernstein: List<MpcRow> = listOf(
        MpcRow(0.0, 115.547831605, -72.838099694, 13.52275614108, 13.6582672370925, 80.1082, 4.1651, 269.857, 16.084),
        MpcRow(30.0, 116.12407887, -74.72968087, 13.4366086025, 13.6024704000525, 78.2842, 4.1339, 299.499, 16.063),
    )
    /** Horizons (JPL magnitude parameters M1=11.7, K1=4.5) for wierzchos. */
    val horizonsWierzchos: List<MpcRow> = listOf(
        MpcRow(0.0, 136.181702088, 36.391236052, 4.108745579785, 4.21390341803606, 77.1427, 13.6767, 292.11, 17.585),
        MpcRow(30.0, 136.941453434, 40.60090349, 4.437886281161, 4.06541584467437, 105.7945, 12.3826, 281.501, 17.658),
    )
    /** Horizons (JPL magnitude parameters M1=18.9, K1=7.75) for leonard. */
    val horizonsLeonard: List<MpcRow> = listOf(
        MpcRow(0.0, 246.984540319, -43.300792101, 0.800597652659, 0.43576050727477, 51.3917, 103.4446, 117.723, 16.348),
        MpcRow(30.0, 231.569932511, -70.730997723, 0.890896133222, 1.04409584690919, 51.8952, 60.8616, 180.404, 18.605),
    )
}
