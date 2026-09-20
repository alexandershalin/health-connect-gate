package com.bishop.healthconnectgate

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset

/**
 * Record format 2: every record is written out field by field, in canonical units only (meters, kilocalories, kilograms,
 * m/s, mmHg) - no unit conversions, no null annotations, no repeated id or end offset. The structure is otherwise the same as
 * the reflective format 1, so a server reads both. It only covers the record types the reference receiver models; [encode]
 * returns null for everything else (and for any record that cannot be written), and the caller falls back to the reflective
 * encoder, so no record is ever dropped.
 */
internal object RecordJson {
    const val FORMAT = 2

    fun encode(record: Record): JSONObject? = try {
        when (record) {
            is StepsRecord -> interval(record, "Steps", record.startTime, record.startZoneOffset, record.endTime, record.endZoneOffset) {
                put("count", record.count)
            }
            is DistanceRecord -> interval(record, "Distance", record.startTime, record.startZoneOffset, record.endTime, record.endZoneOffset) {
                put("distance", JSONObject().put("meters", record.distance.inMeters))
            }
            is ActiveCaloriesBurnedRecord -> interval(record, "ActiveCaloriesBurned", record.startTime, record.startZoneOffset, record.endTime, record.endZoneOffset) {
                put("energy", JSONObject().put("kilocalories", record.energy.inKilocalories))
            }
            is TotalCaloriesBurnedRecord -> interval(record, "TotalCaloriesBurned", record.startTime, record.startZoneOffset, record.endTime, record.endZoneOffset) {
                put("energy", JSONObject().put("kilocalories", record.energy.inKilocalories))
            }
            is HeartRateRecord -> interval(record, "HeartRate", record.startTime, record.startZoneOffset, record.endTime, record.endZoneOffset) {
                put("samples", JSONArray().also { list ->
                    record.samples.forEach { list.put(JSONObject().put("time", it.time.toString()).put("beatsPerMinute", it.beatsPerMinute)) }
                })
            }
            is SpeedRecord -> interval(record, "Speed", record.startTime, record.startZoneOffset, record.endTime, record.endZoneOffset) {
                put("samples", JSONArray().also { list ->
                    record.samples.forEach {
                        list.put(JSONObject().put("time", it.time.toString()).put("speed", JSONObject().put("metersPerSecond", it.speed.inMetersPerSecond)))
                    }
                })
            }
            is SleepSessionRecord -> interval(record, "SleepSession", record.startTime, record.startZoneOffset, record.endTime, record.endZoneOffset) {
                put("title", record.title ?: JSONObject.NULL)
                put("notes", record.notes ?: JSONObject.NULL)
                put("stages", JSONArray().also { list ->
                    record.stages.forEach {
                        list.put(JSONObject().put("startTime", it.startTime.toString()).put("endTime", it.endTime.toString()).put("stage", it.stage))
                    }
                })
            }
            is ExerciseSessionRecord -> interval(record, "ExerciseSession", record.startTime, record.startZoneOffset, record.endTime, record.endZoneOffset) {
                put("exerciseType", record.exerciseType)
                put("title", record.title ?: JSONObject.NULL)
                put("notes", record.notes ?: JSONObject.NULL)
                put("plannedExerciseSessionId", record.plannedExerciseSessionId ?: JSONObject.NULL)
                put("segments", JSONArray().also { list ->
                    record.segments.forEach {
                        list.put(JSONObject().put("startTime", it.startTime.toString()).put("endTime", it.endTime.toString())
                            .put("segmentType", it.segmentType).put("repetitions", it.repetitions))
                    }
                })
                put("laps", JSONArray().also { list ->
                    record.laps.forEach {
                        list.put(JSONObject().put("startTime", it.startTime.toString()).put("endTime", it.endTime.toString())
                            .put("length", it.length?.let { l -> JSONObject().put("meters", l.inMeters) } ?: JSONObject.NULL))
                    }
                })
                if (record.exerciseRouteResult !is ExerciseRouteResult.NoData) put("exerciseRouteResult", JSONObject().put("present", true))
            }
            is BasalMetabolicRateRecord -> point(record, "BasalMetabolicRate", record.time, record.zoneOffset) {
                put("basalMetabolicRate", JSONObject().put("kilocaloriesPerDay", record.basalMetabolicRate.inKilocaloriesPerDay))
            }
            is WeightRecord -> point(record, "Weight", record.time, record.zoneOffset) {
                put("weight", JSONObject().put("kilograms", record.weight.inKilograms))
            }
            is HeightRecord -> point(record, "Height", record.time, record.zoneOffset) {
                put("height", JSONObject().put("meters", record.height.inMeters))
            }
            is BloodPressureRecord -> point(record, "BloodPressure", record.time, record.zoneOffset) {
                put("systolic", JSONObject().put("millimetersOfMercury", record.systolic.inMillimetersOfMercury))
                put("diastolic", JSONObject().put("millimetersOfMercury", record.diastolic.inMillimetersOfMercury))
                put("bodyPosition", record.bodyPosition)
                put("measurementLocation", record.measurementLocation)
            }
            is RestingHeartRateRecord -> point(record, "RestingHeartRate", record.time, record.zoneOffset) {
                put("beatsPerMinute", record.beatsPerMinute)
            }
            else -> null
        }
    } catch (_: Exception) {
        null // e.g. a value JSON cannot express (NaN): the reflective encoder handles that record instead
    }

    private fun interval(
        record: Record, kind: String, start: Instant, startZone: ZoneOffset?, end: Instant, endZone: ZoneOffset?, fields: JSONObject.() -> Unit
    ): JSONObject = wrap(record, kind) {
        put("startTime", start.toString())
        put("endTime", end.toString())
        put("startZoneOffset", startZone?.toString() ?: JSONObject.NULL)
        if (endZone != startZone) put("endZoneOffset", endZone?.toString() ?: JSONObject.NULL)
        fields()
    }

    private fun point(record: Record, kind: String, time: Instant, zone: ZoneOffset?, fields: JSONObject.() -> Unit): JSONObject = wrap(record, kind) {
        put("time", time.toString())
        put("zoneOffset", zone?.toString() ?: JSONObject.NULL)
        fields()
    }

    private fun wrap(record: Record, kind: String, fields: JSONObject.() -> Unit): JSONObject {
        val data = JSONObject().apply(fields)
        data.put("metadata", metadata(record.metadata))
        return JSONObject().put("id", record.metadata.id).put("kind", kind).put("data", data)
    }

    private fun metadata(m: Metadata): JSONObject = JSONObject()
        .put("clientRecordId", m.clientRecordId ?: JSONObject.NULL)
        .put("clientRecordVersion", m.clientRecordVersion)
        .put("dataOrigin", JSONObject().put("packageName", m.dataOrigin.packageName))
        .put("device", JSONObject().put("manufacturer", m.device?.manufacturer ?: JSONObject.NULL)
            .put("model", m.device?.model ?: JSONObject.NULL).put("type", m.device?.type ?: 0))
        .put("lastModifiedTime", m.lastModifiedTime.toString())
        .put("recordingMethod", m.recordingMethod)
}
