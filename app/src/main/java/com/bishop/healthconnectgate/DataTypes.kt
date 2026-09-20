@file:OptIn(androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi::class)

package com.bishop.healthconnectgate

import androidx.annotation.StringRes
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalBodyTemperatureRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.CervicalMucusRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.IntermenstrualBleedingRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OvulationTestRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PlannedExerciseSessionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SexualActivityRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import kotlin.reflect.KClass

/**
 * Every Health Connect record type the app can read, grouped the way the user chooses them. Sensitive categories are
 * off until the user turns them on. A record type belongs to exactly one category (a unit test enforces it).
 */
internal enum class DataCategory(
    val id: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val summaryRes: Int,
    val sensitive: Boolean,
    val types: List<KClass<out Record>>
) {
    ACTIVITY("activity", R.string.cat_activity, R.string.cat_activity_summary, false, listOf(
        StepsRecord::class, DistanceRecord::class, ActiveCaloriesBurnedRecord::class, TotalCaloriesBurnedRecord::class,
        SpeedRecord::class, ExerciseSessionRecord::class, PlannedExerciseSessionRecord::class, FloorsClimbedRecord::class,
        ElevationGainedRecord::class, PowerRecord::class, CyclingPedalingCadenceRecord::class, StepsCadenceRecord::class,
        WheelchairPushesRecord::class)),
    VITALS("vitals", R.string.cat_vitals, R.string.cat_vitals_summary, false, listOf(
        HeartRateRecord::class, RestingHeartRateRecord::class, HeartRateVariabilityRmssdRecord::class, BloodPressureRecord::class,
        OxygenSaturationRecord::class, RespiratoryRateRecord::class, BodyTemperatureRecord::class, BasalBodyTemperatureRecord::class,
        SkinTemperatureRecord::class, BloodGlucoseRecord::class, Vo2MaxRecord::class)),
    BODY("body", R.string.cat_body, R.string.cat_body_summary, false, listOf(
        WeightRecord::class, HeightRecord::class, BodyFatRecord::class, LeanBodyMassRecord::class, BoneMassRecord::class,
        BodyWaterMassRecord::class, BasalMetabolicRateRecord::class)),
    SLEEP("sleep", R.string.cat_sleep, R.string.cat_sleep_summary, false, listOf(
        SleepSessionRecord::class, MindfulnessSessionRecord::class)),
    NUTRITION("nutrition", R.string.cat_nutrition, R.string.cat_nutrition_summary, false, listOf(
        NutritionRecord::class, HydrationRecord::class)),
    CYCLE("cycle", R.string.cat_cycle, R.string.cat_cycle_summary, true, listOf(
        MenstruationFlowRecord::class, MenstruationPeriodRecord::class, OvulationTestRecord::class, CervicalMucusRecord::class,
        IntermenstrualBleedingRecord::class)),
    SEXUAL("sexual", R.string.cat_sexual, R.string.cat_sexual_summary, true, listOf(SexualActivityRecord::class));
}

internal object DataSelection {
    private val allIds: Set<String> = DataCategory.entries.map { it.id }.toSet()

    /** What an install gets before the user chooses: everything except the sensitive categories. */
    val defaultIds: Set<String> = DataCategory.entries.filter { !it.sensitive }.map { it.id }.toSet()

    fun effectiveIds(saved: Set<String>?): Set<String> = (saved ?: defaultIds).intersect(allIds)

    fun typesFor(saved: Set<String>?): List<KClass<out Record>> =
        effectiveIds(saved).let { ids -> DataCategory.entries.filter { it.id in ids }.flatMap { it.types } }

    /** Read permissions to request: the chosen types, plus history older than 30 days and background reads. */
    fun permissionsFor(saved: Set<String>?): Set<String> =
        typesFor(saved).map { HealthPermission.getReadPermission(it) }.toSet() +
            setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY, HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
}
