package net.kuecker.lodestar.kit.satellites

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// The deep-space (SDP4) part of the SGP4 reference implementation: lunar-solar terms (`dscom`), their periodic
// effect (`dpper`), the resonance setup (`dsinit`) and the resonance integration (`dspace`). A port of
// Vallado's `SGP4.cpp` (version 2020-07-13) with `opsmode` 'i' (improved); the arithmetic and its order are the
// reference's, so the results agree with the published test vectors.

private const val twoPi: Double = 2.0 * PI

/** The lunar-solar quantities `dscom` hands to `dsinit` and to the element record. */
internal class Sgp4DeepSpaceCommon {
    var snodm = 0.0
    var cnodm = 0.0
    var sinim = 0.0
    var cosim = 0.0
    var sinomm = 0.0
    var cosomm = 0.0
    var day = 0.0
    var e3 = 0.0
    var ee2 = 0.0
    var em = 0.0
    var emsq = 0.0
    var gam = 0.0
    var peo = 0.0
    var pgho = 0.0
    var pho = 0.0
    var pinco = 0.0
    var plo = 0.0
    var rtemsq = 0.0
    var se2 = 0.0
    var se3 = 0.0
    var sgh2 = 0.0
    var sgh3 = 0.0
    var sgh4 = 0.0
    var sh2 = 0.0
    var sh3 = 0.0
    var si2 = 0.0
    var si3 = 0.0
    var sl2 = 0.0
    var sl3 = 0.0
    var sl4 = 0.0
    var s1 = 0.0
    var s2 = 0.0
    var s3 = 0.0
    var s4 = 0.0
    var s5 = 0.0
    var s6 = 0.0
    var s7 = 0.0
    var ss1 = 0.0
    var ss2 = 0.0
    var ss3 = 0.0
    var ss4 = 0.0
    var ss5 = 0.0
    var ss6 = 0.0
    var ss7 = 0.0
    var sz1 = 0.0
    var sz2 = 0.0
    var sz3 = 0.0
    var sz11 = 0.0
    var sz12 = 0.0
    var sz13 = 0.0
    var sz21 = 0.0
    var sz22 = 0.0
    var sz23 = 0.0
    var sz31 = 0.0
    var sz32 = 0.0
    var sz33 = 0.0
    var xgh2 = 0.0
    var xgh3 = 0.0
    var xgh4 = 0.0
    var xh2 = 0.0
    var xh3 = 0.0
    var xi2 = 0.0
    var xi3 = 0.0
    var xl2 = 0.0
    var xl3 = 0.0
    var xl4 = 0.0
    var nm = 0.0
    var z1 = 0.0
    var z2 = 0.0
    var z3 = 0.0
    var z11 = 0.0
    var z12 = 0.0
    var z13 = 0.0
    var z21 = 0.0
    var z22 = 0.0
    var z23 = 0.0
    var z31 = 0.0
    var z32 = 0.0
    var z33 = 0.0
    var zmol = 0.0
    var zmos = 0.0

    /** The reference's `dscom`: common items for the secular and periodic lunar-solar terms. */
    fun compute(epoch: Double, ep: Double, argpp: Double, tc: Double, inclp: Double, nodep: Double, np: Double) {
        val zes = 0.01675
        val zel = 0.05490
        val c1ss = 2.9864797e-6
        val c1l = 4.7968065e-7
        val zsinis = 0.39785416
        val zcosis = 0.91744867
        val zcosgs = 0.1945905
        val zsings = -0.98088458

        nm = np
        em = ep
        snodm = sin(nodep)
        cnodm = cos(nodep)
        sinomm = sin(argpp)
        cosomm = cos(argpp)
        sinim = sin(inclp)
        cosim = cos(inclp)
        emsq = em * em
        val betasq = 1.0 - emsq
        rtemsq = sqrt(betasq)

        // initialize lunar solar terms
        peo = 0.0
        pinco = 0.0
        plo = 0.0
        pgho = 0.0
        pho = 0.0
        day = epoch + 18261.5 + tc / 1440.0
        val xnodce = (4.5236020 - 9.2422029e-4 * day) % twoPi
        val stem = sin(xnodce)
        val ctem = cos(xnodce)
        val zcosil = 0.91375164 - 0.03568096 * ctem
        val zsinil = sqrt(1.0 - zcosil * zcosil)
        val zsinhl = 0.089683511 * stem / zsinil
        val zcoshl = sqrt(1.0 - zsinhl * zsinhl)
        gam = 5.8351514 + 0.0019443680 * day
        var zx = 0.39785416 * stem / zsinil
        val zy = zcoshl * ctem + 0.91744867 * zsinhl * stem
        zx = atan2(zx, zy)
        zx = gam + zx - xnodce
        val zcosgl = cos(zx)
        val zsingl = sin(zx)

        // do solar terms
        var zcosg = zcosgs
        var zsing = zsings
        var zcosi = zcosis
        var zsini = zsinis
        var zcosh = cnodm
        var zsinh = snodm
        var cc = c1ss
        val xnoi = 1.0 / nm

        for (lsflg in 1..2) {
            val a1 = zcosg * zcosh + zsing * zcosi * zsinh
            val a3 = -zsing * zcosh + zcosg * zcosi * zsinh
            val a7 = -zcosg * zsinh + zsing * zcosi * zcosh
            val a8 = zsing * zsini
            val a9 = zsing * zsinh + zcosg * zcosi * zcosh
            val a10 = zcosg * zsini
            val a2 = cosim * a7 + sinim * a8
            val a4 = cosim * a9 + sinim * a10
            val a5 = -sinim * a7 + cosim * a8
            val a6 = -sinim * a9 + cosim * a10

            val x1 = a1 * cosomm + a2 * sinomm
            val x2 = a3 * cosomm + a4 * sinomm
            val x3 = -a1 * sinomm + a2 * cosomm
            val x4 = -a3 * sinomm + a4 * cosomm
            val x5 = a5 * sinomm
            val x6 = a6 * sinomm
            val x7 = a5 * cosomm
            val x8 = a6 * cosomm

            z31 = 12.0 * x1 * x1 - 3.0 * x3 * x3
            z32 = 24.0 * x1 * x2 - 6.0 * x3 * x4
            z33 = 12.0 * x2 * x2 - 3.0 * x4 * x4
            z1 = 3.0 * (a1 * a1 + a2 * a2) + z31 * emsq
            z2 = 6.0 * (a1 * a3 + a2 * a4) + z32 * emsq
            z3 = 3.0 * (a3 * a3 + a4 * a4) + z33 * emsq
            z11 = -6.0 * a1 * a5 + emsq * (-24.0 * x1 * x7 - 6.0 * x3 * x5)
            z12 = -6.0 * (a1 * a6 + a3 * a5) + emsq * (-24.0 * (x2 * x7 + x1 * x8) - 6.0 * (x3 * x6 + x4 * x5))
            z13 = -6.0 * a3 * a6 + emsq * (-24.0 * x2 * x8 - 6.0 * x4 * x6)
            z21 = 6.0 * a2 * a5 + emsq * (24.0 * x1 * x5 - 6.0 * x3 * x7)
            z22 = 6.0 * (a4 * a5 + a2 * a6) + emsq * (24.0 * (x2 * x5 + x1 * x6) - 6.0 * (x4 * x7 + x3 * x8))
            z23 = 6.0 * a4 * a6 + emsq * (24.0 * x2 * x6 - 6.0 * x4 * x8)
            z1 = z1 + z1 + betasq * z31
            z2 = z2 + z2 + betasq * z32
            z3 = z3 + z3 + betasq * z33
            s3 = cc * xnoi
            s2 = -0.5 * s3 / rtemsq
            s4 = s3 * rtemsq
            s1 = -15.0 * em * s4
            s5 = x1 * x3 + x2 * x4
            s6 = x2 * x3 + x1 * x4
            s7 = x2 * x4 - x1 * x3

            // do lunar terms
            if (lsflg == 1) {
                ss1 = s1
                ss2 = s2
                ss3 = s3
                ss4 = s4
                ss5 = s5
                ss6 = s6
                ss7 = s7
                sz1 = z1
                sz2 = z2
                sz3 = z3
                sz11 = z11
                sz12 = z12
                sz13 = z13
                sz21 = z21
                sz22 = z22
                sz23 = z23
                sz31 = z31
                sz32 = z32
                sz33 = z33
                zcosg = zcosgl
                zsing = zsingl
                zcosi = zcosil
                zsini = zsinil
                zcosh = zcoshl * cnodm + zsinhl * snodm
                zsinh = snodm * zcoshl - cnodm * zsinhl
                cc = c1l
            }
        }

        zmol = (4.7199672 + 0.22997150 * day - gam) % twoPi
        zmos = (6.2565837 + 0.017201977 * day) % twoPi

        // do solar terms
        se2 = 2.0 * ss1 * ss6
        se3 = 2.0 * ss1 * ss7
        si2 = 2.0 * ss2 * sz12
        si3 = 2.0 * ss2 * (sz13 - sz11)
        sl2 = -2.0 * ss3 * sz2
        sl3 = -2.0 * ss3 * (sz3 - sz1)
        sl4 = -2.0 * ss3 * (-21.0 - 9.0 * emsq) * zes
        sgh2 = 2.0 * ss4 * sz32
        sgh3 = 2.0 * ss4 * (sz33 - sz31)
        sgh4 = -18.0 * ss4 * zes
        sh2 = -2.0 * ss2 * sz22
        sh3 = -2.0 * ss2 * (sz23 - sz21)

        // do lunar terms
        ee2 = 2.0 * s1 * s6
        e3 = 2.0 * s1 * s7
        xi2 = 2.0 * s2 * z12
        xi3 = 2.0 * s2 * (z13 - z11)
        xl2 = -2.0 * s3 * z2
        xl3 = -2.0 * s3 * (z3 - z1)
        xl4 = -2.0 * s3 * (-21.0 - 9.0 * emsq) * zel
        xgh2 = 2.0 * s4 * z32
        xgh3 = 2.0 * s4 * (z33 - z31)
        xgh4 = -18.0 * s4 * zel
        xh2 = -2.0 * s2 * z22
        xh3 = -2.0 * s2 * (z23 - z21)
    }
}

/** The outputs of `dsinit` that the element record keeps. */
internal class Sgp4DeepSpaceResonance {
    var irez = 0
    var d2201 = 0.0
    var d2211 = 0.0
    var d3210 = 0.0
    var d3222 = 0.0
    var d4410 = 0.0
    var d4422 = 0.0
    var d5220 = 0.0
    var d5232 = 0.0
    var d5421 = 0.0
    var d5433 = 0.0
    var dedt = 0.0
    var didt = 0.0
    var dmdt = 0.0
    var dnodt = 0.0
    var domdt = 0.0
    var del1 = 0.0
    var del2 = 0.0
    var del3 = 0.0
    var xfact = 0.0
    var xlamo = 0.0

    /**
     * The reference's `dsinit`: deep-space contributions to the mean motion from the resonance with half-day and
     * one-day orbits, and the secular rates of the lunar-solar terms. (The reference also updates local copies
     * of the elements at the epoch; they are not used afterwards and are not kept here.)
     */
    fun compute(
        xke: Double,
        common: Sgp4DeepSpaceCommon,
        argpo: Double,
        t: Double,
        tc: Double,
        gsto: Double,
        mo: Double,
        mdot: Double,
        no: Double,
        nodeo: Double,
        nodedot: Double,
        xpidot: Double,
        ecco: Double,
        eccsq: Double,
        inclo: Double,
    ) {
        val cosim = common.cosim
        val sinim = common.sinim
        val s1 = common.s1
        val s2 = common.s2
        val s3 = common.s3
        val s4 = common.s4
        val s5 = common.s5
        val z1 = common.z1
        val z3 = common.z3
        val z11 = common.z11
        val z13 = common.z13
        val z21 = common.z21
        val z23 = common.z23
        val z31 = common.z31
        val z33 = common.z33
        var em = common.em
        var emsq = common.emsq
        val nm = common.nm
        val inclm = inclo

        val q22 = 1.7891679e-6
        val q31 = 2.1460748e-6
        val q33 = 2.2123015e-7
        val root22 = 1.7891679e-6
        val root44 = 7.3636953e-9
        val root54 = 2.1765803e-9
        val rptim = 4.37526908801129966e-3
        val root32 = 3.7393792e-7
        val root52 = 1.1428639e-7
        val x2o3 = 2.0 / 3.0
        val znl = 1.5835218e-4
        val zns = 1.19459e-5

        // deep space initialization
        irez = 0
        if (nm < 0.0052359877 && nm > 0.0034906585) irez = 1
        if (nm >= 8.26e-3 && nm <= 9.24e-3 && em >= 0.5) irez = 2

        // do solar terms
        val ses = common.ss1 * zns * common.ss5
        val sis = common.ss2 * zns * (common.sz11 + common.sz13)
        val sls = -zns * common.ss3 * (common.sz1 + common.sz3 - 14.0 - 6.0 * emsq)
        val sghs = common.ss4 * zns * (common.sz31 + common.sz33 - 6.0)
        var shs = -zns * common.ss2 * (common.sz21 + common.sz23)
        // sgp4fix for 180 deg incl
        if (inclm < 5.2359877e-2 || inclm > PI - 5.2359877e-2) shs = 0.0
        if (sinim != 0.0) shs = shs / sinim
        val sgs = sghs - cosim * shs

        // do lunar terms
        dedt = ses + s1 * znl * s5
        didt = sis + s2 * znl * (z11 + z13)
        dmdt = sls - znl * s3 * (z1 + z3 - 14.0 - 6.0 * emsq)
        val sghl = s4 * znl * (z31 + z33 - 6.0)
        var shll = -znl * s2 * (z21 + z23)
        // sgp4fix for 180 deg incl
        if (inclm < 5.2359877e-2 || inclm > PI - 5.2359877e-2) shll = 0.0
        domdt = sgs + sghl
        dnodt = shs
        if (sinim != 0.0) {
            domdt = domdt - cosim / sinim * shll
            dnodt = dnodt + shll / sinim
        }

        // calculate deep space resonance effects
        val theta = (gsto + tc * rptim) % twoPi
        em = em + dedt * t

        // initialize the resonance terms
        if (irez != 0) {
            val aonv = (nm / xke).pow(x2o3)

            // geopotential resonance for 12 hour orbits
            if (irez == 2) {
                val cosisq = cosim * cosim
                val emo = em
                em = ecco
                val emsqo = emsq
                emsq = eccsq
                val eoc = em * emsq
                val g201 = -0.306 - (em - 0.64) * 0.440
                val g211: Double
                val g310: Double
                val g322: Double
                val g410: Double
                val g422: Double
                val g520: Double

                if (em <= 0.65) {
                    g211 = 3.616 - 13.2470 * em + 16.2900 * emsq
                    g310 = -19.302 + 117.3900 * em - 228.4190 * emsq + 156.5910 * eoc
                    g322 = -18.9068 + 109.7927 * em - 214.6334 * emsq + 146.5816 * eoc
                    g410 = -41.122 + 242.6940 * em - 471.0940 * emsq + 313.9530 * eoc
                    g422 = -146.407 + 841.8800 * em - 1629.014 * emsq + 1083.4350 * eoc
                    g520 = -532.114 + 3017.977 * em - 5740.032 * emsq + 3708.2760 * eoc
                } else {
                    g211 = -72.099 + 331.819 * em - 508.738 * emsq + 266.724 * eoc
                    g310 = -346.844 + 1582.851 * em - 2415.925 * emsq + 1246.113 * eoc
                    g322 = -342.585 + 1554.908 * em - 2366.899 * emsq + 1215.972 * eoc
                    g410 = -1052.797 + 4758.686 * em - 7193.992 * emsq + 3651.957 * eoc
                    g422 = -3581.690 + 16178.110 * em - 24462.770 * emsq + 12422.520 * eoc
                    g520 = if (em > 0.715) {
                        -5149.66 + 29936.92 * em - 54087.36 * emsq + 31324.56 * eoc
                    } else {
                        1464.74 - 4664.75 * em + 3763.64 * emsq
                    }
                }
                val g533: Double
                val g521: Double
                val g532: Double
                if (em < 0.7) {
                    g533 = -919.22770 + 4988.6100 * em - 9064.7700 * emsq + 5542.21 * eoc
                    g521 = -822.71072 + 4568.6173 * em - 8491.4146 * emsq + 5337.524 * eoc
                    g532 = -853.66600 + 4690.2500 * em - 8624.7700 * emsq + 5341.4 * eoc
                } else {
                    g533 = -37995.780 + 161616.52 * em - 229838.20 * emsq + 109377.94 * eoc
                    g521 = -51752.104 + 218913.95 * em - 309468.16 * emsq + 146349.42 * eoc
                    g532 = -40023.880 + 170470.89 * em - 242699.48 * emsq + 115605.82 * eoc
                }

                val sini2 = sinim * sinim
                val f220 = 0.75 * (1.0 + 2.0 * cosim + cosisq)
                val f221 = 1.5 * sini2
                val f321 = 1.875 * sinim * (1.0 - 2.0 * cosim - 3.0 * cosisq)
                val f322 = -1.875 * sinim * (1.0 + 2.0 * cosim - 3.0 * cosisq)
                val f441 = 35.0 * sini2 * f220
                val f442 = 39.3750 * sini2 * sini2
                val f522 = 9.84375 * sinim * (
                    sini2 * (1.0 - 2.0 * cosim - 5.0 * cosisq) +
                        0.33333333 * (-2.0 + 4.0 * cosim + 6.0 * cosisq)
                    )
                val f523 = sinim * (
                    4.92187512 * sini2 * (-2.0 - 4.0 * cosim + 10.0 * cosisq) +
                        6.56250012 * (1.0 + 2.0 * cosim - 3.0 * cosisq)
                    )
                val f542 = 29.53125 * sinim * (2.0 - 8.0 * cosim + cosisq * (-12.0 + 8.0 * cosim + 10.0 * cosisq))
                val f543 = 29.53125 * sinim * (-2.0 - 8.0 * cosim + cosisq * (12.0 + 8.0 * cosim - 10.0 * cosisq))
                val xno2 = nm * nm
                val ainv2 = aonv * aonv
                var temp1 = 3.0 * xno2 * ainv2
                var temp = temp1 * root22
                d2201 = temp * f220 * g201
                d2211 = temp * f221 * g211
                temp1 = temp1 * aonv
                temp = temp1 * root32
                d3210 = temp * f321 * g310
                d3222 = temp * f322 * g322
                temp1 = temp1 * aonv
                temp = 2.0 * temp1 * root44
                d4410 = temp * f441 * g410
                d4422 = temp * f442 * g422
                temp1 = temp1 * aonv
                temp = temp1 * root52
                d5220 = temp * f522 * g520
                d5232 = temp * f523 * g532
                temp = 2.0 * temp1 * root54
                d5421 = temp * f542 * g521
                d5433 = temp * f543 * g533
                xlamo = (mo + nodeo + nodeo - theta - theta) % twoPi
                xfact = mdot + dmdt + 2.0 * (nodedot + dnodt - rptim) - no
                em = emo
                emsq = emsqo
            }

            // synchronous resonance terms
            if (irez == 1) {
                val g200 = 1.0 + emsq * (-2.5 + 0.8125 * emsq)
                val g310 = 1.0 + 2.0 * emsq
                val g300 = 1.0 + emsq * (-6.0 + 6.60937 * emsq)
                val f220 = 0.75 * (1.0 + cosim) * (1.0 + cosim)
                val f311 = 0.9375 * sinim * sinim * (1.0 + 3.0 * cosim) - 0.75 * (1.0 + cosim)
                var f330 = 1.0 + cosim
                f330 = 1.875 * f330 * f330 * f330
                del1 = 3.0 * nm * nm * aonv * aonv
                del2 = 2.0 * del1 * f220 * g200 * q22
                del3 = 3.0 * del1 * f330 * g300 * q33 * aonv
                del1 = del1 * f311 * g310 * q31 * aonv
                xlamo = (mo + nodeo + argpo - theta) % twoPi
                xfact = mdot + xpidot - rptim + dmdt + domdt + dnodt - no
            }
        }
    }
}

/** The mean elements `dspace` and `dpper` return. */
internal class Sgp4DeepSpaceElements(
    val em: Double,
    val argpm: Double,
    val inclm: Double,
    val mm: Double,
    val nodem: Double,
    val nm: Double,
)

/** The five elements `dpper` perturbs. */
internal class Sgp4PerturbedElements(
    val ep: Double,
    val inclp: Double,
    val nodep: Double,
    val argpp: Double,
    val mp: Double,
)

/** The functions of the deep-space model that run at every propagation. */
internal object Sgp4DeepSpace {

    /** The reference's `dspace`: lunar-solar secular terms and the resonance integration at time [t] (minutes). */
    fun secular(
        record: Sgp4Record,
        t: Double,
        emIn: Double,
        argpmIn: Double,
        inclmIn: Double,
        mmIn: Double,
        nodemIn: Double,
        nmIn: Double,
    ): Sgp4DeepSpaceElements {
        val fasx2 = 0.13130908
        val fasx4 = 2.8843198
        val fasx6 = 0.37448087
        val g22 = 5.7686396
        val g32 = 0.95240898
        val g44 = 1.8014998
        val g52 = 1.0508330
        val g54 = 4.4108898
        val rptim = 4.37526908801129966e-3
        val stepp = 720.0
        val stepn = -720.0
        val step2 = 259200.0

        val tc = t
        var nm = nmIn
        var em = emIn
        var argpm = argpmIn
        var inclm = inclmIn
        var mm = mmIn
        var nodem = nodemIn
        var dndt = 0.0
        val theta = (record.gsto + tc * rptim) % twoPi
        em = em + record.dedt * t
        inclm = inclm + record.didt * t
        argpm = argpm + record.domdt * t
        nodem = nodem + record.dnodt * t
        mm = mm + record.dmdt * t

        // The reference resumes the integration from the state of the previous call; restarting it at the epoch
        // (what it does for a first call, a change of direction or a smaller |t|) gives identical numbers.
        var ft = 0.0
        if (record.irez != 0) {
            var atime = 0.0
            var xni = record.noUnkozai
            var xli = record.xlamo
            val delt = if (t > 0.0) stepp else stepn

            var xndt: Double
            var xldot: Double
            var xnddt: Double
            while (true) {
                // dot terms calculated
                if (record.irez != 2) {
                    // near-synchronous resonance terms
                    xndt = record.del1 * sin(xli - fasx2) + record.del2 * sin(2.0 * (xli - fasx4)) +
                        record.del3 * sin(3.0 * (xli - fasx6))
                    xldot = xni + record.xfact
                    xnddt = record.del1 * cos(xli - fasx2) +
                        2.0 * record.del2 * cos(2.0 * (xli - fasx4)) +
                        3.0 * record.del3 * cos(3.0 * (xli - fasx6))
                    xnddt = xnddt * xldot
                } else {
                    // near-half-day resonance terms
                    val xomi = record.argpo + record.argpdot * atime
                    val x2omi = xomi + xomi
                    val x2li = xli + xli
                    xndt = record.d2201 * sin(x2omi + xli - g22) + record.d2211 * sin(xli - g22) +
                        record.d3210 * sin(xomi + xli - g32) + record.d3222 * sin(-xomi + xli - g32) +
                        record.d4410 * sin(x2omi + x2li - g44) + record.d4422 * sin(x2li - g44) +
                        record.d5220 * sin(xomi + xli - g52) + record.d5232 * sin(-xomi + xli - g52) +
                        record.d5421 * sin(xomi + x2li - g54) + record.d5433 * sin(-xomi + x2li - g54)
                    xldot = xni + record.xfact
                    xnddt = record.d2201 * cos(x2omi + xli - g22) + record.d2211 * cos(xli - g22) +
                        record.d3210 * cos(xomi + xli - g32) + record.d3222 * cos(-xomi + xli - g32) +
                        record.d5220 * cos(xomi + xli - g52) + record.d5232 * cos(-xomi + xli - g52) +
                        2.0 * (
                            record.d4410 * cos(x2omi + x2li - g44) +
                                record.d4422 * cos(x2li - g44) + record.d5421 * cos(xomi + x2li - g54) +
                                record.d5433 * cos(-xomi + x2li - g54)
                            )
                    xnddt = xnddt * xldot
                }

                // integrator
                val iterate = abs(t - atime) >= stepp
                if (!iterate) ft = t - atime

                if (iterate) {
                    xli = xli + xldot * delt + xndt * step2
                    xni = xni + xndt * delt + xnddt * step2
                    atime = atime + delt
                } else {
                    nm = xni + xndt * ft + xnddt * ft * ft * 0.5
                    val xl = xli + xldot * ft + xndt * ft * ft * 0.5
                    if (record.irez != 1) {
                        mm = xl - 2.0 * nodem + 2.0 * theta
                        dndt = nm - record.noUnkozai
                    } else {
                        mm = xl - nodem - argpm + theta
                        dndt = nm - record.noUnkozai
                    }
                    nm = record.noUnkozai + dndt
                    break
                }
            }
        }
        return Sgp4DeepSpaceElements(em, argpm, inclm, mm, nodem, nm)
    }

    /**
     * The reference's `dpper` for `init` = 'n' and `opsmode` 'i': deep-space long-period periodic contributions to
     * the mean elements at time [t] (minutes). (With `init` = 'y' the reference changes nothing; the initialization
     * does not call this function.)
     */
    fun periodics(
        record: Sgp4Record,
        t: Double,
        epIn: Double,
        inclpIn: Double,
        nodepIn: Double,
        argppIn: Double,
        mpIn: Double,
    ): Sgp4PerturbedElements {
        val zns = 1.19459e-5
        val zes = 0.01675
        val znl = 1.5835218e-4
        val zel = 0.05490

        var ep = epIn
        var inclp = inclpIn
        var nodep = nodepIn
        var argpp = argppIn
        var mp = mpIn

        // calculate time varying periodics
        var zm = record.zmos + zns * t
        var zf = zm + 2.0 * zes * sin(zm)
        var sinzf = sin(zf)
        var f2 = 0.5 * sinzf * sinzf - 0.25
        var f3 = -0.5 * sinzf * cos(zf)
        val ses = record.se2 * f2 + record.se3 * f3
        val sis = record.si2 * f2 + record.si3 * f3
        val sls = record.sl2 * f2 + record.sl3 * f3 + record.sl4 * sinzf
        val sghs = record.sgh2 * f2 + record.sgh3 * f3 + record.sgh4 * sinzf
        val shs = record.sh2 * f2 + record.sh3 * f3
        zm = record.zmol + znl * t
        zf = zm + 2.0 * zel * sin(zm)
        sinzf = sin(zf)
        f2 = 0.5 * sinzf * sinzf - 0.25
        f3 = -0.5 * sinzf * cos(zf)
        val sel = record.ee2 * f2 + record.e3 * f3
        val sil = record.xi2 * f2 + record.xi3 * f3
        val sll = record.xl2 * f2 + record.xl3 * f3 + record.xl4 * sinzf
        val sghl = record.xgh2 * f2 + record.xgh3 * f3 + record.xgh4 * sinzf
        val shll = record.xh2 * f2 + record.xh3 * f3
        var pe = ses + sel
        var pinc = sis + sil
        var pl = sls + sll
        var pgh = sghs + sghl
        var ph = shs + shll

        pe = pe - record.peo
        pinc = pinc - record.pinco
        pl = pl - record.plo
        pgh = pgh - record.pgho
        ph = ph - record.pho
        inclp = inclp + pinc
        ep = ep + pe
        val sinip = sin(inclp)
        val cosip = cos(inclp)

        // apply periodics directly
        // sgp4fix for lyddane choice: the perturbed inclination decides (the "gsfc" choice of the reference)
        if (inclp >= 0.2) {
            ph = ph / sinip
            pgh = pgh - cosip * ph
            argpp = argpp + pgh
            nodep = nodep + ph
            mp = mp + pl
        } else {
            // apply periodics with lyddane modification
            val sinop = sin(nodep)
            val cosop = cos(nodep)
            var alfdp = sinip * sinop
            var betdp = sinip * cosop
            val dalf = ph * cosop + pinc * cosip * sinop
            val dbet = -ph * sinop + pinc * cosip * cosop
            alfdp = alfdp + dalf
            betdp = betdp + dbet
            nodep = nodep % twoPi
            var xls = mp + argpp + cosip * nodep
            val dls = pl + pgh - pinc * nodep * sinip
            xls = xls + dls
            val xnoh = nodep
            nodep = atan2(alfdp, betdp)
            if (abs(xnoh - nodep) > PI) {
                if (nodep < xnoh) {
                    nodep = nodep + twoPi
                } else {
                    nodep = nodep - twoPi
                }
            }
            mp = mp + pl
            argpp = xls - mp - cosip * nodep
        }
        return Sgp4PerturbedElements(ep, inclp, nodep, argpp, mp)
    }
}
