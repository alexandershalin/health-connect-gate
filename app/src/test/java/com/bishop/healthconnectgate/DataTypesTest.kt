package com.bishop.healthconnectgate

import androidx.health.connect.client.permission.HealthPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DataTypesTest {
    private val all = DataCategory.entries.flatMap { it.types }

    @Test fun everyRecordTypeBelongsToExactlyOneCategory() {
        assertEquals("a type appears in two categories", all.size, all.toSet().size)
        assertEquals("the app requests 41 Health Connect record types", 41, all.size)
        assertEquals(all, RecordCatalog.types)
    }

    @Test fun sensitiveCategoriesAreOffByDefault() {
        val sensitive = DataCategory.entries.filter { it.sensitive }.map { it.id }.toSet()
        assertEquals(setOf("cycle", "sexual"), sensitive)
        assertTrue(DataSelection.defaultIds.intersect(sensitive).isEmpty())
        assertEquals(DataCategory.entries.size - 2, DataSelection.defaultIds.size)
    }

    @Test fun anInstallThatNeverChoseGetsTheDefaults() {
        assertEquals(DataSelection.defaultIds, DataSelection.effectiveIds(null))
        val types = DataSelection.typesFor(null)
        assertFalse(types.any { it in DataCategory.CYCLE.types || it in DataCategory.SEXUAL.types })
        assertTrue(types.containsAll(DataCategory.ACTIVITY.types))
    }

    @Test fun unknownIdsInTheSavedChoiceAreIgnored() {
        assertEquals(setOf("sleep"), DataSelection.effectiveIds(setOf("sleep", "no-such-category")))
        assertEquals(DataCategory.SLEEP.types, DataSelection.typesFor(setOf("sleep")))
        assertTrue(DataSelection.typesFor(emptySet()).isEmpty())
    }

    @Test fun permissionsFollowTheChoiceAndAlwaysIncludeHistoryAndBackground() {
        val chosen = DataSelection.permissionsFor(setOf("sleep"))
        assertEquals(setOf("android.permission.health.READ_SLEEP", "android.permission.health.READ_MINDFULNESS",
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY, HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND), chosen)
        val everything = DataSelection.permissionsFor(DataCategory.entries.map { it.id }.toSet())
        assertTrue("android.permission.health.READ_SEXUAL_ACTIVITY" in everything)
        assertFalse("android.permission.health.READ_SEXUAL_ACTIVITY" in DataSelection.permissionsFor(null))
    }

    @Test fun everyPermissionTheAppCanRequestIsDeclaredInTheManifest() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val declared = Regex("""<uses-permission android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()
        val missing = DataSelection.permissionsFor(DataCategory.entries.map { it.id }.toSet()) - declared
        assertTrue("requested but not declared: $missing", missing.isEmpty())
    }
}
