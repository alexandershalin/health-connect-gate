package com.bishop.healthconnectgate

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseLap
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Pressure
import androidx.health.connect.client.units.Velocity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream

/**
 * The explicit encoder must say exactly what the reflective one says, minus the derived unit conversions. Both are run on real Health Connect
 * objects, so a forgotten field fails here instead of silently missing from the server.
 */
class RecordJsonTest {
    private val t0 = Instant.parse("2026-09-19T10:07:12.234Z")
    private val t1 = Instant.parse("2026-09-19T10:37:12Z")
    private val plus3 = ZoneOffset.ofHours(3)
    private val watch = Device(manufacturer = "acme", model = "w-1", type = Device.TYPE_WATCH)
    private fun meta(withId: Boolean = true): Metadata =
        if (withId) Metadata.manualEntry(clientRecordId = "c-1", clientRecordVersion = 7L, device = watch) else Metadata.manualEntry()

    private val derived = mapOf(
        "distance" to listOf("feet", "inches", "kilometers", "miles"), "length" to listOf("feet", "inches", "kilometers", "miles"),
        "height" to listOf("feet", "inches", "kilometers", "miles"), "energy" to listOf("calories", "joules", "kilojoules"),
        "basalMetabolicRate" to listOf("watts"), "weight" to listOf("grams", "micrograms", "milligrams", "ounces", "pounds"),
        "speed" to listOf("kilometersPerHour", "milesPerHour"))

    /** The rules of record format 2 applied to the reflective output. */
    private fun canonicalize(o: Any?, key: String? = null): Any? = when (o) {
        is JSONObject -> {
            val out = JSONObject()
            for (k in o.keys().asSequence().toList()) {
                if (k.endsWith("\$annotations") && o.isNull(k)) continue
                if (key != null && k in (derived[key] ?: emptyList())) continue
                out.put(k, canonicalize(o.get(k), k))
            }
            out
        }
        is JSONArray -> JSONArray().also { list -> for (i in 0 until o.length()) list.put(canonicalize(o.get(i), key)) }
        else -> o
    }

    private fun expected(record: Record): JSONObject {
        val json = canonicalize(JSONObject(RecordCatalog.recordJson(record))) as JSONObject
        val data = json.getJSONObject("data")
        data.getJSONObject("metadata").remove("id")
        if (data.opt("endZoneOffset") == data.opt("startZoneOffset")) data.remove("endZoneOffset")
        if (data.has("exerciseRouteResult") && data.getJSONObject("exerciseRouteResult").length() == 0) data.remove("exerciseRouteResult")
        val meta = data.getJSONObject("metadata")
        if (meta.isNull("device")) meta.put("device", JSONObject().put("manufacturer", JSONObject.NULL).put("model", JSONObject.NULL).put("type", 0))
        return json
    }

    /** Structural equality of two JSON trees (numbers compared as doubles, JSON null equal to JSON null). */
    private fun deepEquals(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject ->
            a.keys().asSequence().toSet() == b.keys().asSequence().toSet() && a.keys().asSequence().all { deepEquals(a.get(it), b.get(it)) }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { deepEquals(a.get(it), b.get(it)) }
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        a == null || a == JSONObject.NULL -> b == null || b == JSONObject.NULL
        else -> a == b
    }

    private fun assertSame(record: Record) {
        val actual = RecordJson.encode(record) ?: error("${record.javaClass.simpleName} was not encoded explicitly")
        val want = expected(record)
        assertTrue("explicit and reflective output differ for ${record.javaClass.simpleName}\nexpected: $want\nactual:   $actual", deepEquals(want, actual))
        assertTrue("canonical format must be smaller", actual.toString().length < RecordCatalog.recordJson(record).length)
    }

    @Test fun steps() = assertSame(StepsRecord(t0, plus3, t1, plus3, 4321L, meta()))
    @Test fun stepsWithoutOffsetsAndDevice() = assertSame(StepsRecord(t0, null, t1, null, 5L, meta(withId = false)))
    @Test fun stepsWithDifferentEndOffsetKeepBoth() {
        val record = StepsRecord(t0, plus3, t1, ZoneOffset.ofHours(4), 5L, meta())
        assertSame(record)
        assertEquals("+04:00", RecordJson.encode(record)!!.getJSONObject("data").getString("endZoneOffset"))
    }
    @Test fun distance() = assertSame(DistanceRecord(t0, plus3, t1, plus3, Length.meters(1234.5), meta()))
    @Test fun activeCalories() = assertSame(ActiveCaloriesBurnedRecord(t0, plus3, t1, plus3, Energy.kilocalories(88.25), meta()))
    @Test fun totalCalories() = assertSame(TotalCaloriesBurnedRecord(t0, plus3, t1, plus3, Energy.kilocalories(2100.0), meta()))
    @Test fun heartRate() = assertSame(HeartRateRecord(t0, plus3, t1, plus3, listOf(HeartRateRecord.Sample(t0, 62L), HeartRateRecord.Sample(t1, 71L)), meta()))
    @Test fun speed() = assertSame(SpeedRecord(t0, plus3, t1, plus3, listOf(SpeedRecord.Sample(t0, Velocity.metersPerSecond(1.25)), SpeedRecord.Sample(t1, Velocity.metersPerSecond(2.5))), meta()))
    @Test fun sleep() = assertSame(SleepSessionRecord(startTime = t0, startZoneOffset = plus3, endTime = t1, endZoneOffset = plus3, metadata = meta(),
        title = "nap", notes = null, stages = listOf(SleepSessionRecord.Stage(t0, t1, SleepSessionRecord.STAGE_TYPE_DEEP))))
    @Test fun exerciseWithSegmentsAndLaps() = assertSame(ExerciseSessionRecord(
        startTime = t0, startZoneOffset = plus3, endTime = t1, endZoneOffset = plus3, metadata = meta(),
        exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, title = "run", notes = "notes",
        segments = listOf(ExerciseSegment(t0, t1, ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING, 0)),
        laps = listOf(ExerciseLap(t0, t1, Length.meters(400.0)))))
    @Test fun basalMetabolicRate() = assertSame(BasalMetabolicRateRecord(t0, plus3, Power.kilocaloriesPerDay(1650.0), meta()))
    @Test fun weight() = assertSame(WeightRecord(t0, plus3, Mass.kilograms(70.5), meta()))
    @Test fun height() = assertSame(HeightRecord(t0, plus3, Length.meters(1.83), meta()))
    @Test fun bloodPressure() = assertSame(BloodPressureRecord(time = t0, zoneOffset = plus3, metadata = meta(),
        systolic = Pressure.millimetersOfMercury(121.0), diastolic = Pressure.millimetersOfMercury(79.0),
        bodyPosition = BloodPressureRecord.BODY_POSITION_SITTING_DOWN, measurementLocation = BloodPressureRecord.MEASUREMENT_LOCATION_LEFT_UPPER_ARM))
    @Test fun restingHeartRate() = assertSame(RestingHeartRateRecord(t0, plus3, 58L, meta()))

    @Test fun otherKindsAreLeftToTheReflectiveEncoder() {
        assertNull(RecordJson.encode(OxygenSaturationRecord(t0, plus3, Percentage(97.0), meta())))
    }

    @Test fun theIdIsTheHealthConnectRecordIdAndKindIsTheClassName() {
        val json = RecordJson.encode(StepsRecord(t0, plus3, t1, plus3, 1L, meta()))!!
        assertEquals("Steps", json.getString("kind"))
        assertEquals("", json.getString("id"))            // Metadata.manualEntry has no id yet; real records carry the store's id
        assertEquals(setOf("id", "kind", "data"), json.keys().asSequence().toSet())
    }

    @Test fun gzipRoundTripsAndShrinksRepetitiveJson() {
        val text = (1..400).joinToString(",") { "{\"kind\":\"Steps\",\"count\":$it}" }.toByteArray()
        val packed = gzip(text)
        assertTrue(packed.size < text.size / 4)
        assertEquals(String(text), GZIPInputStream(ByteArrayInputStream(packed)).readBytes().decodeToString())
    }
}
