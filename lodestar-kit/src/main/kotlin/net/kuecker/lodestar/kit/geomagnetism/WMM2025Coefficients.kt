// WMM2025Coefficients.kt
//
// World Magnetic Model 2025 (WMM-2025), Gauss coefficients to degree and order 12.
// Produced by NOAA National Centers for Environmental Information and the British Geological
// Survey. A work of the U.S. Government, in the public domain.
//
// Source archive : https://www.ncei.noaa.gov/sites/default/files/2024-12/WMM2025COF.zip
// Archive SHA-256: 2e76569370d081f2cd7919490218bd094ca9afde347b198eff5621e0af460d03
// Member file    : WMM2025COF/WMM.COF
// Member SHA-256 : dfa8597825af4e0b87ff4198a5b4fb661b3c49f4cd090cd0164e0259b075582f
// Header line    : 2025.0 WMM-2025 11/13/2024
//
// One row per line of WMM.COF: degree n, order m, g and h in nT at the epoch, and their secular
// variation gDot and hDot in nT/yr. A literal -0.0 in the source is written as 0.0.
//
// This table is a mechanical translation of the generated Swift file WMM2025Coefficients.swift:
// every number is copied digit for digit, in the same order. Do not edit it by hand.

package net.kuecker.lodestar.kit.geomagnetism

import net.kuecker.lodestar.kit.geomagnetism.WorldMagneticModel.CoefficientRow

/** The WMM2025 coefficient set exactly as published by NOAA. */
internal object WMM2025Coefficients {
    /** Reference epoch t0 of the main-field coefficients, in decimal years. */
    const val epoch: Double = 2025.0

    /** Gauss coefficients and secular variation, ordered by degree and then by order. */
    val rows: List<CoefficientRow> = listOf(
        CoefficientRow(degree = 1, order = 0, g = -29351.8, h = 0.0, gDot = 12.0, hDot = 0.0),
        CoefficientRow(degree = 1, order = 1, g = -1410.8, h = 4545.4, gDot = 9.7, hDot = -21.5),
        CoefficientRow(degree = 2, order = 0, g = -2556.6, h = 0.0, gDot = -11.6, hDot = 0.0),
        CoefficientRow(degree = 2, order = 1, g = 2951.1, h = -3133.6, gDot = -5.2, hDot = -27.7),
        CoefficientRow(degree = 2, order = 2, g = 1649.3, h = -815.1, gDot = -8.0, hDot = -12.1),
        CoefficientRow(degree = 3, order = 0, g = 1361.0, h = 0.0, gDot = -1.3, hDot = 0.0),
        CoefficientRow(degree = 3, order = 1, g = -2404.1, h = -56.6, gDot = -4.2, hDot = 4.0),
        CoefficientRow(degree = 3, order = 2, g = 1243.8, h = 237.5, gDot = 0.4, hDot = -0.3),
        CoefficientRow(degree = 3, order = 3, g = 453.6, h = -549.5, gDot = -15.6, hDot = -4.1),
        CoefficientRow(degree = 4, order = 0, g = 895.0, h = 0.0, gDot = -1.6, hDot = 0.0),
        CoefficientRow(degree = 4, order = 1, g = 799.5, h = 278.6, gDot = -2.4, hDot = -1.1),
        CoefficientRow(degree = 4, order = 2, g = 55.7, h = -133.9, gDot = -6.0, hDot = 4.1),
        CoefficientRow(degree = 4, order = 3, g = -281.1, h = 212.0, gDot = 5.6, hDot = 1.6),
        CoefficientRow(degree = 4, order = 4, g = 12.1, h = -375.6, gDot = -7.0, hDot = -4.4),
        CoefficientRow(degree = 5, order = 0, g = -233.2, h = 0.0, gDot = 0.6, hDot = 0.0),
        CoefficientRow(degree = 5, order = 1, g = 368.9, h = 45.4, gDot = 1.4, hDot = -0.5),
        CoefficientRow(degree = 5, order = 2, g = 187.2, h = 220.2, gDot = 0.0, hDot = 2.2),
        CoefficientRow(degree = 5, order = 3, g = -138.7, h = -122.9, gDot = 0.6, hDot = 0.4),
        CoefficientRow(degree = 5, order = 4, g = -142.0, h = 43.0, gDot = 2.2, hDot = 1.7),
        CoefficientRow(degree = 5, order = 5, g = 20.9, h = 106.1, gDot = 0.9, hDot = 1.9),
        CoefficientRow(degree = 6, order = 0, g = 64.4, h = 0.0, gDot = -0.2, hDot = 0.0),
        CoefficientRow(degree = 6, order = 1, g = 63.8, h = -18.4, gDot = -0.4, hDot = 0.3),
        CoefficientRow(degree = 6, order = 2, g = 76.9, h = 16.8, gDot = 0.9, hDot = -1.6),
        CoefficientRow(degree = 6, order = 3, g = -115.7, h = 48.8, gDot = 1.2, hDot = -0.4),
        CoefficientRow(degree = 6, order = 4, g = -40.9, h = -59.8, gDot = -0.9, hDot = 0.9),
        CoefficientRow(degree = 6, order = 5, g = 14.9, h = 10.9, gDot = 0.3, hDot = 0.7),
        CoefficientRow(degree = 6, order = 6, g = -60.7, h = 72.7, gDot = 0.9, hDot = 0.9),
        CoefficientRow(degree = 7, order = 0, g = 79.5, h = 0.0, gDot = 0.0, hDot = 0.0),
        CoefficientRow(degree = 7, order = 1, g = -77.0, h = -48.9, gDot = -0.1, hDot = 0.6),
        CoefficientRow(degree = 7, order = 2, g = -8.8, h = -14.4, gDot = -0.1, hDot = 0.5),
        CoefficientRow(degree = 7, order = 3, g = 59.3, h = -1.0, gDot = 0.5, hDot = -0.8),
        CoefficientRow(degree = 7, order = 4, g = 15.8, h = 23.4, gDot = -0.1, hDot = 0.0),
        CoefficientRow(degree = 7, order = 5, g = 2.5, h = -7.4, gDot = -0.8, hDot = -1.0),
        CoefficientRow(degree = 7, order = 6, g = -11.1, h = -25.1, gDot = -0.8, hDot = 0.6),
        CoefficientRow(degree = 7, order = 7, g = 14.2, h = -2.3, gDot = 0.8, hDot = -0.2),
        CoefficientRow(degree = 8, order = 0, g = 23.2, h = 0.0, gDot = -0.1, hDot = 0.0),
        CoefficientRow(degree = 8, order = 1, g = 10.8, h = 7.1, gDot = 0.2, hDot = -0.2),
        CoefficientRow(degree = 8, order = 2, g = -17.5, h = -12.6, gDot = 0.0, hDot = 0.5),
        CoefficientRow(degree = 8, order = 3, g = 2.0, h = 11.4, gDot = 0.5, hDot = -0.4),
        CoefficientRow(degree = 8, order = 4, g = -21.7, h = -9.7, gDot = -0.1, hDot = 0.4),
        CoefficientRow(degree = 8, order = 5, g = 16.9, h = 12.7, gDot = 0.3, hDot = -0.5),
        CoefficientRow(degree = 8, order = 6, g = 15.0, h = 0.7, gDot = 0.2, hDot = -0.6),
        CoefficientRow(degree = 8, order = 7, g = -16.8, h = -5.2, gDot = 0.0, hDot = 0.3),
        CoefficientRow(degree = 8, order = 8, g = 0.9, h = 3.9, gDot = 0.2, hDot = 0.2),
        CoefficientRow(degree = 9, order = 0, g = 4.6, h = 0.0, gDot = 0.0, hDot = 0.0),
        CoefficientRow(degree = 9, order = 1, g = 7.8, h = -24.8, gDot = -0.1, hDot = -0.3),
        CoefficientRow(degree = 9, order = 2, g = 3.0, h = 12.2, gDot = 0.1, hDot = 0.3),
        CoefficientRow(degree = 9, order = 3, g = -0.2, h = 8.3, gDot = 0.3, hDot = -0.3),
        CoefficientRow(degree = 9, order = 4, g = -2.5, h = -3.3, gDot = -0.3, hDot = 0.3),
        CoefficientRow(degree = 9, order = 5, g = -13.1, h = -5.2, gDot = 0.0, hDot = 0.2),
        CoefficientRow(degree = 9, order = 6, g = 2.4, h = 7.2, gDot = 0.3, hDot = -0.1),
        CoefficientRow(degree = 9, order = 7, g = 8.6, h = -0.6, gDot = -0.1, hDot = -0.2),
        CoefficientRow(degree = 9, order = 8, g = -8.7, h = 0.8, gDot = 0.1, hDot = 0.4),
        CoefficientRow(degree = 9, order = 9, g = -12.9, h = 10.0, gDot = -0.1, hDot = 0.1),
        CoefficientRow(degree = 10, order = 0, g = -1.3, h = 0.0, gDot = 0.1, hDot = 0.0),
        CoefficientRow(degree = 10, order = 1, g = -6.4, h = 3.3, gDot = 0.0, hDot = 0.0),
        CoefficientRow(degree = 10, order = 2, g = 0.2, h = 0.0, gDot = 0.1, hDot = 0.0),
        CoefficientRow(degree = 10, order = 3, g = 2.0, h = 2.4, gDot = 0.1, hDot = -0.2),
        CoefficientRow(degree = 10, order = 4, g = -1.0, h = 5.3, gDot = 0.0, hDot = 0.1),
        CoefficientRow(degree = 10, order = 5, g = -0.6, h = -9.1, gDot = -0.3, hDot = -0.1),
        CoefficientRow(degree = 10, order = 6, g = -0.9, h = 0.4, gDot = 0.0, hDot = 0.1),
        CoefficientRow(degree = 10, order = 7, g = 1.5, h = -4.2, gDot = -0.1, hDot = 0.0),
        CoefficientRow(degree = 10, order = 8, g = 0.9, h = -3.8, gDot = -0.1, hDot = -0.1),
        CoefficientRow(degree = 10, order = 9, g = -2.7, h = 0.9, gDot = 0.0, hDot = 0.2),
        CoefficientRow(degree = 10, order = 10, g = -3.9, h = -9.1, gDot = 0.0, hDot = 0.0),
        CoefficientRow(degree = 11, order = 0, g = 2.9, h = 0.0, gDot = 0.0, hDot = 0.0),
        CoefficientRow(degree = 11, order = 1, g = -1.5, h = 0.0, gDot = 0.0, hDot = 0.0),
        CoefficientRow(degree = 11, order = 2, g = -2.5, h = 2.9, gDot = 0.0, hDot = 0.1),
        CoefficientRow(degree = 11, order = 3, g = 2.4, h = -0.6, gDot = 0.0, hDot = 0.0),
        CoefficientRow(degree = 11, order = 4, g = -0.6, h = 0.2, gDot = 0.0, hDot = 0.1),
        CoefficientRow(degree = 11, order = 5, g = -0.1, h = 0.5, gDot = -0.1, hDot = 0.0),
        CoefficientRow(degree = 11, order = 6, g = -0.6, h = -0.3, gDot = 0.0, hDot = 0.0),
        CoefficientRow(degree = 11, order = 7, g = -0.1, h = -1.2, gDot = 0.0, hDot = 0.1),
        CoefficientRow(degree = 11, order = 8, g = 1.1, h = -1.7, gDot = -0.1, hDot = 0.0),
        CoefficientRow(degree = 11, order = 9, g = -1.0, h = -2.9, gDot = -0.1, hDot = 0.0),
        CoefficientRow(degree = 11, order = 10, g = -0.2, h = -1.8, gDot = -0.1, hDot = 0.0),
        CoefficientRow(degree = 11, order = 11, g = 2.6, h = -2.3, gDot = -0.1, hDot = 0.0),
        CoefficientRow(degree = 12, order = 0, g = -2.0, h = 0.0, gDot = 0.0, hDot = 0.0),
        CoefficientRow(degree = 12, order = 1, g = -0.2, h = -1.3, gDot = 0.0, hDot = 0.0),
        CoefficientRow(degree = 12, order = 2, g = 0.3, h = 0.7, gDot = 0.0, hDot = 0.0),
        CoefficientRow(degree = 12, order = 3, g = 1.2, h = 1.0, gDot = 0.0, hDot = -0.1),
        CoefficientRow(degree = 12, order = 4, g = -1.3, h = -1.4, gDot = 0.0, hDot = 0.1),
        CoefficientRow(degree = 12, order = 5, g = 0.6, h = 0.0, gDot = 0.0, hDot = 0.0),
        CoefficientRow(degree = 12, order = 6, g = 0.6, h = 0.6, gDot = 0.1, hDot = 0.0),
        CoefficientRow(degree = 12, order = 7, g = 0.5, h = -0.1, gDot = 0.0, hDot = 0.0),
        CoefficientRow(degree = 12, order = 8, g = -0.1, h = 0.8, gDot = 0.0, hDot = 0.0),
        CoefficientRow(degree = 12, order = 9, g = -0.4, h = 0.1, gDot = 0.0, hDot = 0.0),
        CoefficientRow(degree = 12, order = 10, g = -0.2, h = -1.0, gDot = -0.1, hDot = 0.0),
        CoefficientRow(degree = 12, order = 11, g = -1.3, h = 0.1, gDot = 0.0, hDot = 0.0),
        CoefficientRow(degree = 12, order = 12, g = -0.7, h = 0.2, gDot = -0.1, hDot = -0.1),
    )
}
