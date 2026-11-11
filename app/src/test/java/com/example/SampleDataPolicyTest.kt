package com.example

import com.example.core.storage.DefaultSlot
import com.example.core.storage.SampleDataPolicy
import com.example.data.model.TemplateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the sample-data decision rules. */
class SampleDataPolicyTest {

    private fun template(id: String, lastModified: Long, ppt: Boolean = false) = TemplateEntity(
        id = id, name = id, fileType = "PPTX", originalFilePath = "/t/$id.pptx", profileJson = "{}",
        isDefaultPpt = ppt, lastModified = lastModified
    )

    @Test
    fun samplesInsertedOnlyOnGenuinelyFreshInstall() {
        assertTrue(SampleDataPolicy.shouldInsertSamples(alreadySeeded = false, templatesDirExists = false, existingRowCount = 0))
    }

    @Test
    fun samplesNeverInsertedOnceFlagIsSet() {
        // Even if the user deleted every record, samples must not come back.
        assertFalse(SampleDataPolicy.shouldInsertSamples(alreadySeeded = true, templatesDirExists = false, existingRowCount = 0))
        assertFalse(SampleDataPolicy.shouldInsertSamples(alreadySeeded = true, templatesDirExists = true, existingRowCount = 5))
    }

    @Test
    fun samplesNotInsertedOnExistingInstallWithoutFlag() {
        // Upgrade from a version that predates the flag: the templates dir or any row proves the
        // samples were offered before (and may have been deleted on purpose).
        assertFalse(SampleDataPolicy.shouldInsertSamples(alreadySeeded = false, templatesDirExists = true, existingRowCount = 0))
        assertFalse(SampleDataPolicy.shouldInsertSamples(alreadySeeded = false, templatesDirExists = false, existingRowCount = 1))
    }

    @Test
    fun duplicateDefaultResolutionPrefersUserTemplateOverSample() {
        val sample = template(SampleDataPolicy.SAMPLE_PPT_TEMPLATE_ID, lastModified = 9_000, ppt = true)
        val user = template("user-deck", lastModified = 1_000, ppt = true)
        assertEquals("user-deck", SampleDataPolicy.chooseDefaultToKeep(listOf(sample, user))?.id)
    }

    @Test
    fun duplicateDefaultResolutionPrefersMostRecentAmongUserTemplates() {
        val older = template("user-a", lastModified = 1_000, ppt = true)
        val newer = template("user-b", lastModified = 2_000, ppt = true)
        assertEquals("user-b", SampleDataPolicy.chooseDefaultToKeep(listOf(older, newer))?.id)
    }

    @Test
    fun duplicateDefaultResolutionIsDeterministicForTies() {
        val a = template("user-a", lastModified = 5, ppt = true)
        val b = template("user-b", lastModified = 5, ppt = true)
        assertEquals("user-a", SampleDataPolicy.chooseDefaultToKeep(listOf(b, a))?.id)
        assertNull(SampleDataPolicy.chooseDefaultToKeep(emptyList()))
    }

    @Test
    fun slotMappingMatchesEntityFlags() {
        val t = TemplateEntity(
            id = "x", name = "x", fileType = "DOCX", originalFilePath = "", profileJson = "{}",
            isDefaultWord = true, isDefaultPpt = false, isDefaultExcel = true
        )
        assertTrue(SampleDataPolicy.isDefault(t, DefaultSlot.WORD))
        assertFalse(SampleDataPolicy.isDefault(t, DefaultSlot.PPT))
        assertTrue(SampleDataPolicy.isDefault(t, DefaultSlot.EXCEL))
    }
}
