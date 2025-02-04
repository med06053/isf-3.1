package app.aaps.implementation.iob

import app.aaps.annotations.OpenForTesting
import app.aaps.core.interfaces.iob.GlucoseStatus
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.main.iob.asRounded
import app.aaps.core.main.iob.log
import dagger.Reusable
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import app.aaps.implementation.R
import app.aaps.core.interfaces.sharedPreferences.SP


@Reusable
@OpenForTesting
class GlucoseStatusProviderImpl @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val iobCobCalculator: IobCobCalculator,
    private val dateUtil: DateUtil,
    private val decimalFormatter: DecimalFormatter
) : GlucoseStatusProvider {
    @Inject lateinit var sp: SP

    override val glucoseStatusData: GlucoseStatus?
        get() = getGlucoseStatusData()

    override fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatus? {
        val data = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return null
        val orig = iobCobCalculator.ads.getBgReadingsDataTableCopy() // ?: return null

        var sizeRecords = data.size
        if (sizeRecords == 0) {
            aapsLogger.debug(LTag.GLUCOSE, "sizeRecords==0")
            return null
        }
        if (data[0].timestamp < dateUtil.now() - 7 * 60 * 1000L && !allowOldData) {
            aapsLogger.debug(LTag.GLUCOSE, "oldData")
            return null
        }

        val now = data[0]
        val nowDate = now.timestamp
        val nowValue = now.value
        val recalc = now.recalculated
        val smooth = now.smoothed
        val filled = now.filledGap
        val cgm = now.sourceSensor
        val fsl = orig[0]
        val fslDate = fsl.timestamp
        val fslValue = fsl.value
        val fslRaw = fsl.raw
        val fslReally = fsl.sourceSensor.isLibre()
        //val fslReally = true    // "RANDOM" while testing with virtual phone in AS
        var fslMinDur = 15  // default for 5m CGM
        //val fslFitSrc = sp.getInt(R.string.key_parabolaSourceDataType, 1)
        aapsLogger.debug(LTag.GLUCOSE, "BgReadings stamp=$fslDate; raw=$fslRaw; value=$fslValue; " +
            "BgBucketed value=$nowValue; recalc=$recalc; smooth=$smooth; filled=$filled; CGM=$cgm; Libre=$fslReally; fitDura=$fslMinDur; fitSrc=1mRaw")
        var change: Double
        if (sizeRecords == 1) {
            aapsLogger.debug(LTag.GLUCOSE, "sizeRecords==1")
            return GlucoseStatus(
                glucose = now.recalculated,
                noise = 0.0,
                delta = 0.0,
                shortAvgDelta = 0.0,
                longAvgDelta = 0.0,
                date = nowDate,
                duraISFminutes  = 0.0,
                duraISFaverage = now.value,
                useFSL1minuteRaw = false,
                parabolaMinutes = 0.0,
                deltaPl = 0.0,
                deltaPn = 0.0,
                bgAcceleration = 0.0,
                a0 = now.value,
                a1 = 0.0,
                a2 = 0.0,
                corrSqu = 0.0
            ).asRounded()
        }
        val lastDeltas = ArrayList<Double>()
        val shortDeltas = ArrayList<Double>()
        val longDeltas = ArrayList<Double>()

        // Use the latest sgv value in the now calculations
        for (i in 1 until sizeRecords) {
            if (data[i].value > 39 && !data[i].filledGap) {
                val then = data[i]
                val thenDate = then.timestamp
                val valueAgo = then.value
                val bgAgo = then.recalculated
                val smoothAgo = then.smoothed
                val filledAgo = then.filledGap

                val minutesAgo = ((nowDate - thenDate) / (1000.0 * 60)).roundToLong()
                // multiply by 5 to get the same units as delta, i.e. mg/dL/5m
                change = now.recalculated - then.recalculated
                val avgDel = change / minutesAgo * 5
                aapsLogger.debug(LTag.GLUCOSE, "$then Bucketed=$minutesAgo valueAgo=$valueAgo recalcAgo=$bgAgo smooth=$smoothAgo filled=$filledAgo avgDelta=$avgDel")

                // use the average of all data points in the last 2.5m for all further "now" calculations
                // if (0 < minutesAgo && minutesAgo < 2.5) {
                //     // Keep and average all values within the last 2.5 minutes
                //     nowValueList.add(then.recalculated)
                //     now.value = average(nowValueList)
                //     // short_deltas are calculated from everything ~5-15 minutes ago
                // } else
                if (2.5 < minutesAgo && minutesAgo < 17.5) {
                    //console.error(minutesAgo, avgDelta);
                    shortDeltas.add(avgDel)
                    // last_deltas are calculated from everything ~5 minutes ago
                    if (2.5 < minutesAgo && minutesAgo < 7.5) {
                        lastDeltas.add(avgDel)
                    }
                    // long_deltas are calculated from everything ~20-40 minutes ago
                } else if (17.5 < minutesAgo && minutesAgo < 42.5) {
                    longDeltas.add(avgDel)
                } else {
                    // Do not process any more records after >= 42.5 minutes
                    break
                }
            }
        }
        val shortAverageDelta = average(shortDeltas)
        val delta = if (lastDeltas.isEmpty()) {
            shortAverageDelta
        } else {
            average(lastDeltas)
        }

        // calculate 2 variables for 5% range; still using 5 minute data
        val bw = 0.05
        var sumBG: Double = now.recalculated
        var oldavg: Double = sumBG
        var minutesdur = 0L
        var n = 1
        for (i in 1 until sizeRecords) {
            if (data[i].value>39 && !data[i].filledGap) {
                n += 1
                val then = data[i]
                val thenDate: Long = then.timestamp
                //  stop the series if there was a CGM gap greater than 13 minutes, i.e. 2 regular readings
                //  needs shorter gap for Libre?
                if (((nowDate - thenDate) / (1000.0 * 60)).roundToInt() - minutesdur > 13) {
                    break
                }
                if (then.recalculated > oldavg * (1 - bw) && then.recalculated < oldavg * (1 + bw)) {
                    sumBG += then.recalculated
                    oldavg = sumBG / n  // was: (i + 1)
                    minutesdur = ((nowDate - thenDate) / (1000.0 * 60)).roundToInt().toLong()
                } else {
                    break
                }
            }
        }

        // calculate best parabola and determine delta by extending it 5 minutes into the future
        // after https://www.codeproject.com/Articles/63170/Least-Squares-Regression-for-Quadratic-Curve-Fitti
        //
        //  y = a2*x^2 + a1*x + a0      or
        //  y = a*x^2  + b*x  + c       respectively
        var duraP = 0.0
        var deltaPl = 0.0
        var deltaPn = 0.0
        var bgAcceleration = 0.0
        var corrMax = 0.0
        var a0 = 0.0
        var a1 = 0.0
        var a2 = 0.0
        //var b = 0.0
        var use1MinuteRaw = false
        if ( fslReally ) {
            // original FSL 1-minute cgm data from Juggluco direct to AAPS although not smoothed
            if ( orig.size>2 ) {
                if ( orig[0].timestamp - orig[2].timestamp < 3 * 60000 ) {
                    use1MinuteRaw = true
                    sizeRecords = orig.size
                    fslMinDur = 20  //sp.getInt(R.string.key_fslMinFitMinutes, 20)
                }
            }
        }
        //if ( abs(fslFitSrc) == 1 ) {
        //    sizeRecords = orig.size
        //}

        if (sizeRecords > 3) {
            //double corrMin = 0.90;              // go backwards until the correlation coefficient goes below
            var sy   = 0.0 // y
            var sx   = 0.0 // x
            var sx2  = 0.0 // x^2
            var sx3  = 0.0 // x^3
            var sx4  = 0.0 // x^4
            var sxy  = 0.0 // x*y
            var sx2y = 0.0 // x^2*y
            val time0 = if (use1MinuteRaw) orig[0].timestamp else data[0].timestamp
            var tiLast = 0.0
            //# for best numerical accuracy time and bg must be of same order of magnitude
            val scaleTime = 300.0 // in 5m; values are  0, -1, -2, -3, -4, ...
            val scaleBg   =  50.0 // TIR range is now 1.4 - 3.6

            // if (data[i].recalculated > 38) {  } // not checked in past 1.5 years
            n= 0
            for (i in 0 until sizeRecords) {
                val noGap = if (fslReally) true else !data[i].filledGap
                if (orig[i].value>39 && noGap) {
                    n += 1
                    val thenDate: Long
                    var bg: Double
                    //if (fslReally && fslFitSrc == 1) {
                    //    use1MinuteSmooth = true
                    //    val then = orig[i]
                    //    thenDate = then.timestamp
                    //    bg = then.raw ?: then.value
                    //    if (bg == 0.0) {            // happended sometimes, use unsmoothed instead
                    //        bg = then.value
                    //    }
                    //    bg = bg / scaleBg
                    //} else
                    if (use1MinuteRaw) {
                        val then = orig[i]
                        thenDate = then.timestamp
                        bg = then.value / scaleBg

                    //} else if (fslFitSrc < -1) {
                    //    val then = data[i]
                    //    thenDate = then.timestamp
                    //    bg = then.value / scaleBg
                    } else {    // all other including standard 5m CGM smoothed
                        val then = data[i]
                        thenDate = then.timestamp
                        bg = then.recalculated / scaleBg
                    }

                    val ti = (thenDate - time0) / 1000.0 / scaleTime
                    if (-ti * scaleTime > 47 * 60) {                       // skip records older than 47.5 minutes
                        break
                    } else if (ti < tiLast - 7.5 * 60 / scaleTime) {       // stop scan if a CGM gap > 7.5 minutes is detected
                        if (i < 3 || -ti * scaleTime < fslMinDur * 60) {   // history too short for fit
                            duraP = -tiLast * scaleTime / 60.0
                            deltaPl = 0.0
                            deltaPn = 0.0
                            bgAcceleration = 0.0
                            corrMax = 0.0
                            a0 = 0.0
                            a1 = 0.0
                            a2 = 0.0
                        }
                        break
                    }
                    tiLast = ti
                    sx += ti
                    sx2 += ti.pow(2.0)
                    sx3 += ti.pow(3.0)
                    sx4 += ti.pow(4.0)
                    sy += bg
                    sxy += ti * bg
                    sx2y += ti.pow(2.0) * bg
                    //val n = i + 1
                    var detH = 0.0
                    var detA = 0.0
                    var detB = 0.0
                    var detC = 0.0
                    if (n > 3 && -ti * scaleTime > fslMinDur * 60) {
                        detH = sx4 * (sx2 * n - sx * sx) - sx3 * (sx3 * n - sx * sx2) + sx2 * (sx3 * sx - sx2 * sx2)
                        detA = sx2y * (sx2 * n - sx * sx) - sxy * (sx3 * n - sx * sx2) + sy * (sx3 * sx - sx2 * sx2)
                        detB = sx4 * (sxy * n - sy * sx) - sx3 * (sx2y * n - sy * sx2) + sx2 * (sx2y * sx - sxy * sx2)
                        detC = sx4 * (sx2 * sy - sx * sxy) - sx3 * (sx3 * sy - sx * sx2y) + sx2 * (sx3 * sxy - sx2 * sx2y)
                    }
                    if (detH != 0.0) {
                        val a: Double = detA / detH
                        val b = detB / detH
                        val c: Double = detC / detH
                        val yMean = sy / n
                        var sSquares = 0.0
                        var sResidualSquares = 0.0
                        //var smoothBg: Double
                        var rawBg: Double
                        for (j in 0..i) {
                            //if (fslReally && fslFitSrc==1) {
                            //    val before = orig[j]
                            //    smoothBg = before.raw ?: before.value
                            //    if (smoothBg==0.0) {
                            //        smoothBg = before.value     // fall back to raw if smooth not available
                            //    }
                            //    sSquares += (smoothBg / scaleBg - yMean).pow(2.0)
                            //    val deltaT: Double = (before.timestamp - time0) / 1000.0 / scaleTime
                            //    val bgj: Double = a * deltaT.pow(2.0) + b * deltaT + c
                            //    sResidualSquares += (smoothBg / scaleBg - bgj).pow(2.0)
                            //} else
                            if (use1MinuteRaw) {
                                val before = orig[j]
                                rawBg = before.value
                                sSquares += (rawBg / scaleBg - yMean).pow(2.0)
                                val deltaT: Double = (before.timestamp - time0) / 1000.0 / scaleTime
                                val bgj: Double = a * deltaT.pow(2.0) + b * deltaT + c
                                sResidualSquares += (rawBg / scaleBg - bgj).pow(2.0)
                            //} else if (fslFitSrc<-1) {
                            //    val before = data[j]
                            //    sSquares += (before.value / scaleBg - yMean).pow(2.0)
                            //    val deltaT: Double = (before.timestamp - time0) / 1000.0 / scaleTime
                            //    val bgj: Double = a * deltaT.pow(2.0) + b * deltaT + c
                            //    sResidualSquares += (before.value / scaleBg - bgj).pow(2.0)
                            } else {                            // default case anyway
                                val before = data[j]
                                sSquares += (before.recalculated / scaleBg - yMean).pow(2.0)
                                val deltaT: Double = (before.timestamp - time0) / 1000.0 / scaleTime
                                val bgj: Double = a * deltaT.pow(2.0) + b * deltaT + c
                                sResidualSquares += (before.recalculated / scaleBg - bgj).pow(2.0)
                            }
                            var rSqu = 0.64
                            if (sSquares != 0.0) {
                                rSqu = 1 - sResidualSquares / sSquares
                            }
                            if (rSqu >= corrMax) {
                                corrMax = rSqu
                                // double delta_t = (then_date - time_0) / 1000;
                                duraP = -ti * scaleTime / 60.0 // remember we are going backwards in time
                                val delta5Min = 5 * 60 / scaleTime
                                deltaPl = -scaleBg * (a * (-delta5Min).pow(2.0) - b * delta5Min)    // 5 minute slope from last fitted bg ending at this bg, i.e. t=0
                                deltaPn =  scaleBg * (a *   delta5Min.pow(2.0)  + b * delta5Min)    // 5 minute slope to next fitted bg starting from this bg, i.e. t=0
                                bgAcceleration = 2 * a * scaleBg
                                a0 = c * scaleBg
                                a1 = b * scaleBg
                                a2 = a * scaleBg
                            }
                        }
                    }
                }
            }
        }
        // End parabola fit



        return GlucoseStatus(
            glucose = now.recalculated,
            date = nowDate,
            noise = 0.0, //for now set to nothing as not all CGMs report noise
            shortAvgDelta = shortAverageDelta,
            delta = delta,
            longAvgDelta = average(longDeltas),
            duraISFminutes  = minutesdur.toDouble(),
            duraISFaverage = oldavg,
            useFSL1minuteRaw = use1MinuteRaw,
            parabolaMinutes = duraP,
            deltaPl = deltaPl,
            deltaPn = deltaPn,
            corrSqu = corrMax,
            bgAcceleration = bgAcceleration,
            a0 = a0,
            a1 = a1,
            a2 = a2,
        ).also { aapsLogger.debug(LTag.GLUCOSE, it.log(decimalFormatter)) }.asRounded()
    }

    /* Real BG (previous) version
           override fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatus? {
            val data = iobCobCalculator.ads.getBgReadingsDataTableCopy()
            val sizeRecords = data.size
            if (sizeRecords == 0) {
                aapsLogger.debug(LTag.GLUCOSE, "sizeRecords==0")
                return null
            }
            if (data[0].timestamp < dateUtil.now() - 7 * 60 * 1000L && !allowOldData) {
                aapsLogger.debug(LTag.GLUCOSE, "oldData")
                return null
            }
            val now = data[0]
            val nowDate = now.timestamp
            var change: Double
            if (sizeRecords == 1) {
                aapsLogger.debug(LTag.GLUCOSE, "sizeRecords==1")
                return GlucoseStatus(
                    glucose = now.value,
                    noise = 0.0,
                    delta = 0.0,
                    shortAvgDelta = 0.0,
                    longAvgDelta = 0.0,
                    date = nowDate
                ).asRounded()
            }
            val nowValueList = ArrayList<Double>()
            val lastDeltas = ArrayList<Double>()
            val shortDeltas = ArrayList<Double>()
            val longDeltas = ArrayList<Double>()

            // Use the latest sgv value in the now calculations
            nowValueList.add(now.value)
            for (i in 1 until sizeRecords) {
                if (data[i].value > 38) {
                    val then = data[i]
                    val thenDate = then.timestamp

                    val minutesAgo = ((nowDate - thenDate) / (1000.0 * 60)).roundToLong()
                    // multiply by 5 to get the same units as delta, i.e. mg/dL/5m
                    change = now.value - then.value
                    val avgDel = change / minutesAgo * 5
                    aapsLogger.debug(LTag.GLUCOSE, "$then minutesAgo=$minutesAgo avgDelta=$avgDel")

                    // use the average of all data points in the last 2.5m for all further "now" calculations
                    if (0 < minutesAgo && minutesAgo < 2.5) {
                        // Keep and average all values within the last 2.5 minutes
                        nowValueList.add(then.value)
                        now.value = average(nowValueList)
                        // short_deltas are calculated from everything ~5-15 minutes ago
                    } else if (2.5 < minutesAgo && minutesAgo < 17.5) {
                        //console.error(minutesAgo, avgDelta);
                        shortDeltas.add(avgDel)
                        // last_deltas are calculated from everything ~5 minutes ago
                        if (2.5 < minutesAgo && minutesAgo < 7.5) {
                            lastDeltas.add(avgDel)
                        }
                        // long_deltas are calculated from everything ~20-40 minutes ago
                    } else if (17.5 < minutesAgo && minutesAgo < 42.5) {
                        longDeltas.add(avgDel)
                    } else {
                        // Do not process any more records after >= 42.5 minutes
                        break
                    }
                }
            }
            val shortAverageDelta = average(shortDeltas)
            val delta = if (lastDeltas.isEmpty()) {
                shortAverageDelta
            } else {
                average(lastDeltas)
            }
            return GlucoseStatus(
                glucose = now.value,
                date = nowDate,
                noise = 0.0, //for now set to nothing as not all CGMs report noise
                shortAvgDelta = shortAverageDelta,
                delta = delta,
                longAvgDelta = average(longDeltas),
            ).also { aapsLogger.debug(LTag.GLUCOSE, it.log()) }.asRounded()
        }

    */
    companion object {

        fun average(array: ArrayList<Double>): Double {
            var sum = 0.0
            if (array.isEmpty()) return 0.0
            for (value in array) {
                sum += value
            }
            return sum / array.size
        }
    }
}
