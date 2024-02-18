// Ported from
// https://github.com/dotnet/runtime/blob/4f4af6b5/src/libraries/System.Private.CoreLib/src/System/Globalization/PersianCalendar.cs
// https://github.com/dotnet/runtime/blob/4f4af6b5/src/libraries/System.Private.CoreLib/src/System/Globalization/CalendricalCalculationsHelper.cs#L8
// Which is released under The MIT License (MIT)
package io.github.persiancalendar.calendar.persian

import io.github.persiancalendar.calendar.CivilDate
import kotlin.math.*

object AlgorithmicConverter {
    private const val projectJdnOffset: Long = 1721426 // Offset from Jdn to jdn used in this converter
    private const val persianEpoch: Long = 226895 // new DateTime(622, 3, 22).Ticks / TicksPerDay
    private const val meanTropicalYearInDays = 365.242189
    private const val fullCircleOfArc = 360.0
    private const val meanSpeedOfSun = meanTropicalYearInDays / fullCircleOfArc
    private const val halfCircleOfArc = 180
    private const val twoDegreesAfterSpring = 2.0
    private val daysToMonth = intArrayOf(0, 31, 62, 93, 124, 155, 186, 216, 246, 276, 306, 336, 366)
    private const val noon2000Jan01 = 730120.5
    private const val daysInUniformLengthCentury = 36525
    private val startOf1810: Long = CivilDate(1810, 1, 1).toJdn() - projectJdnOffset
    private val startOf1900Century: Long = CivilDate(1900, 1, 1).toJdn() - projectJdnOffset
    private const val twelveHours = .5 // half a day
    private const val secondsPerDay = 24 * 60 * 60 // 24 hours * 60 minutes * 60 seconds
    private const val secondsPerMinute = 60
    private const val minutesPerDegree = 60
    private val coefficients1900to1987 =
        doubleArrayOf(-0.00002, 0.000297, 0.025184, -0.181133, 0.553040, -0.861938, 0.677066, -0.212591)
    private val coefficients1800to1899 = doubleArrayOf(
        -0.000009,
        0.003844,
        0.083563,
        0.865736,
        4.867575,
        15.845535,
        31.332267,
        38.291999,
        28.316289,
        11.636204,
        2.043794
    )
    private val coefficients1700to1799 = doubleArrayOf(8.118780842, -0.005092142, 0.003336121, -0.0000266484)
    private val coefficients1620to1699 = doubleArrayOf(196.58333, -4.0675, 0.0219167)
    private val lambdaCoefficients = doubleArrayOf(280.46645, 36000.76983, 0.0003032)
    private val anomalyCoefficients = doubleArrayOf(357.52910, 35999.05030, -0.0001559, -0.00000048)
    private val eccentricityCoefficients = doubleArrayOf(0.016708617, -0.000042037, -0.0000001236)
    private val coefficients =
        doubleArrayOf(angle(23, 26, 21.448), angle(0, 0, -46.8150), angle(0, 0, -0.00059), angle(0, 0, 0.001813))
    private val coefficientsA = doubleArrayOf(124.90, -1934.134, 0.002063)
    private val coefficientsB = doubleArrayOf(201.11, 72001.5377, 0.00057)
    private val ephemerisCorrectionTable = arrayOf( // lowest year that starts algorithm, algorithm to use
        EphemerisCorrectionAlgorithmMap(2020, CorrectionAlgorithm.Default),
        EphemerisCorrectionAlgorithmMap(1988, CorrectionAlgorithm.Year1988to2019),
        EphemerisCorrectionAlgorithmMap(1900, CorrectionAlgorithm.Year1900to1987),
        EphemerisCorrectionAlgorithmMap(1800, CorrectionAlgorithm.Year1800to1899),
        EphemerisCorrectionAlgorithmMap(1700, CorrectionAlgorithm.Year1700to1799),
        EphemerisCorrectionAlgorithmMap(1620, CorrectionAlgorithm.Year1620to1699),
        EphemerisCorrectionAlgorithmMap(Int.MIN_VALUE, CorrectionAlgorithm.Default) // default must be last
    )
    private const val longitudeSpring = .0
    fun toJdn(year: Int, month: Int, day: Int): Long {
        val approximateHalfYear = 180
        val ordinalDay =
            daysInPreviousMonths(month) + day - 1 // day is one based, make 0 based since this will be the number of days we add to beginning of year below
        val approximateDaysFromEpochForYearStart = (meanTropicalYearInDays * (year - 1)).toInt()
        var yearStart =
            persianNewYearOnOrBefore(persianEpoch + approximateDaysFromEpochForYearStart + approximateHalfYear)
        yearStart += ordinalDay.toLong()
        return yearStart + projectJdnOffset
    }

    fun fromJdn(jdn: Long): IntArray {
        var jdn = jdn
        jdn++ // TODO: Investigate why this is needed
        val yearStart = persianNewYearOnOrBefore(jdn - projectJdnOffset)
        val y: Int = kotlin.math.floor((yearStart - persianEpoch) / meanTropicalYearInDays + 0.5).toInt() + 1
        val ordinalDay = (jdn - toJdn(y, 1, 1)).toInt()
        val m = monthFromOrdinalDay(ordinalDay)
        val d = ordinalDay - daysInPreviousMonths(m)
        return intArrayOf(y, m, d)
    }

    private fun daysInPreviousMonths(month: Int): Int {
        return daysToMonth[month - 1]
    }

    private fun asSeason(longitude: Double): Double {
        return if (longitude < 0) longitude + fullCircleOfArc else longitude
    }

    private fun initLongitude(longitude: Double): Double {
        return normalizeLongitude(longitude + halfCircleOfArc) - halfCircleOfArc
    }

    private fun normalizeLongitude(longitude: Double): Double {
        var longitude = longitude
        longitude %= fullCircleOfArc
        if (longitude < 0) longitude += fullCircleOfArc
        return longitude
    }

    private fun estimatePrior(longitude: Double, time: Double): Double {
        val timeSunLastAtLongitude = time - meanSpeedOfSun * asSeason(initLongitude(compute(time) - longitude))
        val longitudeErrorDelta = initLongitude(compute(timeSunLastAtLongitude) - longitude)
        return min(time, timeSunLastAtLongitude - meanSpeedOfSun * longitudeErrorDelta)
    }

    private fun compute(time: Double): Double {
        val julianCenturies = julianCenturies(time)
        val lambda =
            282.7771834 + 36000.76953744 * julianCenturies + 0.000005729577951308232 * sumLongSequenceOfPeriodicTerms(
                julianCenturies
            )
        val longitude = lambda + aberration(julianCenturies) + nutation(julianCenturies)
        return initLongitude(longitude)
    }

    private fun polynomialSum(coefficients: DoubleArray, indeterminate: Double): Double {
        var sum = coefficients[0]
        var indeterminateRaised = 1.0
        for (i in 1 until coefficients.size) {
            indeterminateRaised *= indeterminate
            sum += coefficients[i] * indeterminateRaised
        }
        return sum
    }

    private fun nutation(julianCenturies: Double): Double {
        val a = polynomialSum(coefficientsA, julianCenturies)
        val b = polynomialSum(coefficientsB, julianCenturies)
        return -0.004778 * sin(Math.toRadians(a)) - 0.0003667 * sin(Math.toRadians(b))
    }

    private fun aberration(julianCenturies: Double): Double {
        return 0.0000974 * cos(Math.toRadians(177.63 + 35999.01848 * julianCenturies)) - 0.005575
    }

    private fun periodicTerm(julianCenturies: Double, x: Int, y: Double, z: Double): Double {
        return x * sin(Math.toRadians(y + z * julianCenturies))
    }

    private fun sumLongSequenceOfPeriodicTerms(julianCenturies: Double): Double {
        var sum = .0
        sum += periodicTerm(julianCenturies, 403406, 270.54861, 0.9287892)
        sum += periodicTerm(julianCenturies, 195207, 340.19128, 35999.1376958)
        sum += periodicTerm(julianCenturies, 119433, 63.91854, 35999.4089666)
        sum += periodicTerm(julianCenturies, 112392, 331.2622, 35998.7287385)
        sum += periodicTerm(julianCenturies, 3891, 317.843, 71998.20261)
        sum += periodicTerm(julianCenturies, 2819, 86.631, 71998.4403)
        sum += periodicTerm(julianCenturies, 1721, 240.052, 36000.35726)
        sum += periodicTerm(julianCenturies, 660, 310.26, 71997.4812)
        sum += periodicTerm(julianCenturies, 350, 247.23, 32964.4678)
        sum += periodicTerm(julianCenturies, 334, 260.87, -19.441)
        sum += periodicTerm(julianCenturies, 314, 297.82, 445267.1117)
        sum += periodicTerm(julianCenturies, 268, 343.14, 45036.884)
        sum += periodicTerm(julianCenturies, 242, 166.79, 3.1008)
        sum += periodicTerm(julianCenturies, 234, 81.53, 22518.4434)
        sum += periodicTerm(julianCenturies, 158, 3.5, -19.9739)
        sum += periodicTerm(julianCenturies, 132, 132.75, 65928.9345)
        sum += periodicTerm(julianCenturies, 129, 182.95, 9038.0293)
        sum += periodicTerm(julianCenturies, 114, 162.03, 3034.7684)
        sum += periodicTerm(julianCenturies, 99, 29.8, 33718.148)
        sum += periodicTerm(julianCenturies, 93, 266.4, 3034.448)
        sum += periodicTerm(julianCenturies, 86, 249.2, -2280.773)
        sum += periodicTerm(julianCenturies, 78, 157.6, 29929.992)
        sum += periodicTerm(julianCenturies, 72, 257.8, 31556.493)
        sum += periodicTerm(julianCenturies, 68, 185.1, 149.588)
        sum += periodicTerm(julianCenturies, 64, 69.9, 9037.75)
        sum += periodicTerm(julianCenturies, 46, 8.0, 107997.405)
        sum += periodicTerm(julianCenturies, 38, 197.1, -4444.176)
        sum += periodicTerm(julianCenturies, 37, 250.4, 151.771)
        sum += periodicTerm(julianCenturies, 32, 65.3, 67555.316)
        sum += periodicTerm(julianCenturies, 29, 162.7, 31556.08)
        sum += periodicTerm(julianCenturies, 28, 341.5, -4561.54)
        sum += periodicTerm(julianCenturies, 27, 291.6, 107996.706)
        sum += periodicTerm(julianCenturies, 27, 98.5, 1221.655)
        sum += periodicTerm(julianCenturies, 25, 146.7, 62894.167)
        sum += periodicTerm(julianCenturies, 24, 110.0, 31437.369)
        sum += periodicTerm(julianCenturies, 21, 5.2, 14578.298)
        sum += periodicTerm(julianCenturies, 21, 342.6, -31931.757)
        sum += periodicTerm(julianCenturies, 20, 230.9, 34777.243)
        sum += periodicTerm(julianCenturies, 18, 256.1, 1221.999)
        sum += periodicTerm(julianCenturies, 17, 45.3, 62894.511)
        sum += periodicTerm(julianCenturies, 14, 242.9, -4442.039)
        sum += periodicTerm(julianCenturies, 13, 115.2, 107997.909)
        sum += periodicTerm(julianCenturies, 13, 151.8, 119.066)
        sum += periodicTerm(julianCenturies, 13, 285.3, 16859.071)
        sum += periodicTerm(julianCenturies, 12, 53.3, -4.578)
        sum += periodicTerm(julianCenturies, 10, 126.6, 26895.292)
        sum += periodicTerm(julianCenturies, 10, 205.7, -39.127)
        sum += periodicTerm(julianCenturies, 10, 85.9, 12297.536)
        sum += periodicTerm(julianCenturies, 10, 146.1, 90073.778)
        return sum
    }

    private fun julianCenturies(moment: Double): Double {
        val dynamicalMoment = moment + ephemerisCorrection(moment)
        return (dynamicalMoment - noon2000Jan01) / daysInUniformLengthCentury
    }

    // the following formulas defines a polynomial function which gives us the amount that the earth is slowing down for specific year ranges
    private fun defaultEphemerisCorrection(gregorianYear: Int): Double {
        // Contract.Assert(gregorianYear < 1620 || 2020 <= gregorianYear);
        val january1stOfYear: Long = CivilDate(gregorianYear, 1, 1).toJdn() - projectJdnOffset
        val daysSinceStartOf1810 = (january1stOfYear - startOf1810).toDouble()
        val x = twelveHours + daysSinceStartOf1810
        return (x.pow(2.0) / 41048480 - 15) / secondsPerDay
    }

    private fun ephemerisCorrection1988to2019(gregorianYear: Int): Double {
        // Contract.Assert(1988 <= gregorianYear && gregorianYear <= 2019);
        return (gregorianYear - 1933).toDouble() / secondsPerDay
    }

    private fun centuriesFrom1900(gregorianYear: Int): Double {
        val july1stOfYear: Long = CivilDate(gregorianYear, 7, 1).toJdn() - projectJdnOffset
        return (july1stOfYear - startOf1900Century).toDouble() / daysInUniformLengthCentury
    }

    private fun angle(degrees: Int, minutes: Int, seconds: Double): Double {
        return (seconds / secondsPerMinute + minutes) / minutesPerDegree + degrees
    }

    private fun ephemerisCorrection1900to1987(gregorianYear: Int): Double {
        // Contract.Assert(1900 <= gregorianYear && gregorianYear <= 1987);
        val centuriesFrom1900 = centuriesFrom1900(gregorianYear)
        return polynomialSum(coefficients1900to1987, centuriesFrom1900)
    }

    private fun ephemerisCorrection1800to1899(gregorianYear: Int): Double {
        // Contract.Assert(1800 <= gregorianYear && gregorianYear <= 1899);
        val centuriesFrom1900 = centuriesFrom1900(gregorianYear)
        return polynomialSum(coefficients1800to1899, centuriesFrom1900)
    }

    private fun ephemerisCorrection1700to1799(gregorianYear: Int): Double {
        // Contract.Assert(1700 <= gregorianYear && gregorianYear <= 1799);
        val yearsSince1700 = (gregorianYear - 1700).toDouble()
        return polynomialSum(coefficients1700to1799, yearsSince1700) / secondsPerDay
    }

    private fun ephemerisCorrection1620to1699(gregorianYear: Int): Double {
        // Contract.Assert(1620 <= gregorianYear && gregorianYear <= 1699);
        val yearsSince1600 = (gregorianYear - 1600).toDouble()
        return polynomialSum(coefficients1620to1699, yearsSince1600) / secondsPerDay
    }

    private fun getGregorianYear(numberOfDays: Double): Int {
        return CivilDate(kotlin.math.floor(numberOfDays).toLong() + projectJdnOffset).year
    }

    // ephemeris-correction: correction to account for the slowing down of the rotation of the earth
    private fun ephemerisCorrection(time: Double): Double {
        val year = getGregorianYear(time)
        for (map in ephemerisCorrectionTable) {
            if (map.lowestYear <= year) {
                return when (map.algorithm) {
                    CorrectionAlgorithm.Default -> defaultEphemerisCorrection(year)
                    CorrectionAlgorithm.Year1988to2019 -> ephemerisCorrection1988to2019(year)
                    CorrectionAlgorithm.Year1900to1987 -> ephemerisCorrection1900to1987(year)
                    CorrectionAlgorithm.Year1800to1899 -> ephemerisCorrection1800to1899(year)
                    CorrectionAlgorithm.Year1700to1799 -> ephemerisCorrection1700to1799(year)
                    CorrectionAlgorithm.Year1620to1699 -> ephemerisCorrection1620to1699(year)
                }
                break // break the loop and assert eventually
            }
        }

        // Contract.Assert(false, "Not expected to come here");
        return defaultEphemerisCorrection(year)
    }

    private fun asDayFraction(longitude: Double): Double {
        return longitude / fullCircleOfArc
    }

    private fun obliquity(julianCenturies: Double): Double {
        return polynomialSum(coefficients, julianCenturies)
    }

    // equation-of-time; approximate the difference between apparent solar time and mean solar time
    // formal definition is EOT = GHA - GMHA
    // GHA is the Greenwich Hour Angle of the apparent (actual) Sun
    // GMHA is the Greenwich Mean Hour Angle of the mean (fictitious) Sun
    // http://www.esrl.noaa.gov/gmd/grad/solcalc/
    // http://en.wikipedia.org/wiki/Equation_of_time
    private fun equationOfTime(time: Double): Double {
        val julianCenturies = julianCenturies(time)
        val lambda = polynomialSum(lambdaCoefficients, julianCenturies)
        val anomaly = polynomialSum(anomalyCoefficients, julianCenturies)
        val eccentricity = polynomialSum(eccentricityCoefficients, julianCenturies)
        val epsilon = obliquity(julianCenturies)
        val tanHalfEpsilon: Double = tan(Math.toRadians(epsilon / 2))
        val y = tanHalfEpsilon * tanHalfEpsilon
        val dividend: Double = y * sin(Math.toRadians(2 * lambda)) -
                2 * eccentricity * sin(Math.toRadians(anomaly)) +
                4 * eccentricity * y * sin(Math.toRadians(anomaly)) * cos(Math.toRadians(2 * lambda)) -
                .5 * y.pow(2.0) * sin(Math.toRadians(4 * lambda)) -
                1.25 * eccentricity.pow(2.0) * sin(Math.toRadians(2 * anomaly))
        val divisor: Double = 2 * kotlin.math.PI
        val equation = dividend / divisor

        // approximation of equation of time is not valid for dates that are many millennia in the past or future
        // thus limited to a half day
        return min(abs(equation).withSign(twelveHours), equation)
    }

    private fun asLocalTime(apparentMidday: Double, longitude: Double): Double {
        // slightly inaccurate since equation of time takes mean time not apparent time as its argument, but the difference is negligible
        val universalTime = apparentMidday - asDayFraction(longitude)
        return apparentMidday - equationOfTime(universalTime)
    }

    // midday
    private fun midday(date: Double, longitude: Double): Double {
        return asLocalTime(date + twelveHours, longitude) - asDayFraction(longitude)
    }

    // midday-in-tehran
    private fun middayAtPersianObservationSite(date: Double): Double {
        return midday(
            date,
            initLongitude(52.5)
        ) // 52.5 degrees east - longitude of UTC+3:30 which defines Iranian Standard Time
    }

    // persian-new-year-on-or-before
    //  number of days is the absolute date. The absolute date is the number of days from January 1st, 1 A.D.
    //  1/1/0001 is absolute date 1.
    private fun persianNewYearOnOrBefore(numberOfDays: Long): Long {
        val date = numberOfDays.toDouble()
        val approx = estimatePrior(longitudeSpring, middayAtPersianObservationSite(date))
        val lowerBoundNewYearDay: Long = floor(approx).toLong() - 1
        val upperBoundNewYearDay =
            lowerBoundNewYearDay + 3 // estimate is generally within a day of the actual occurrance (at the limits, the error expands, since the calculations rely on the mean tropical year which changes...)
        var day = lowerBoundNewYearDay
        while (day != upperBoundNewYearDay) {
            val midday = middayAtPersianObservationSite(day.toDouble())
            val l = compute(midday)
            if (l in longitudeSpring..twoDegreesAfterSpring) break
            ++day
        }
        // Contract.Assert(day != upperBoundNewYearDay);
        return day - 1
    }

    private fun monthFromOrdinalDay(ordinalDay: Int): Int {
        var index = 0
        while (ordinalDay > daysToMonth[index]) index++
        return index
    }

    private enum class CorrectionAlgorithm {
        Default,
        Year1988to2019,
        Year1900to1987,
        Year1800to1899,
        Year1700to1799,
        Year1620to1699
    }

    private class EphemerisCorrectionAlgorithmMap internal constructor(
        val lowestYear: Int,
        val algorithm: CorrectionAlgorithm
    )
}
