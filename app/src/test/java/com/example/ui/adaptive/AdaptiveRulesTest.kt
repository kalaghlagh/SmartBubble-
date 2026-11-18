package com.example.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Layout rules from the available space and font scale (no device names). */
class AdaptiveRulesTest {

    @Test
    fun windowClasses_followMaterialBreakpoints() {
        assertEquals(WindowClass.COMPACT, AdaptiveRules.widthClass(320f))
        assertEquals(WindowClass.COMPACT, AdaptiveRules.widthClass(599f))
        assertEquals(WindowClass.MEDIUM, AdaptiveRules.widthClass(600f))
        assertEquals(WindowClass.EXPANDED, AdaptiveRules.widthClass(840f))
        assertEquals(WindowClass.COMPACT, AdaptiveRules.heightClass(412f))
        assertEquals(WindowClass.MEDIUM, AdaptiveRules.heightClass(640f))
        assertEquals(WindowClass.EXPANDED, AdaptiveRules.heightClass(915f))
    }

    @Test
    fun actionColumns_growWithWidth_shrinkWithLargerText_neverZeroOrMoreThanActions() {
        // Usable widths of the review's action area (window less 2 x 10 dp padding).
        assertEquals(3, AdaptiveRules.actionColumns(300f, 1f, 7))
        assertEquals(4, AdaptiveRules.actionColumns(392f, 1f, 7))
        assertEquals(6, AdaptiveRules.actionColumns(580f, 1f, 7))
        assertEquals(7, AdaptiveRules.actionColumns(820f, 1f, 7))
        assertTrue(AdaptiveRules.actionColumns(300f, 1.5f, 7) < AdaptiveRules.actionColumns(300f, 1f, 7))
        assertEquals(1, AdaptiveRules.actionColumns(150f, 2f, 7))
        assertEquals(1, AdaptiveRules.actionColumns(10f, 1f, 7))
        assertEquals(2, AdaptiveRules.actionColumns(2000f, 1f, 2))
        // Font scale above 2 does not shrink the cells further below one per row.
        assertEquals(AdaptiveRules.actionColumns(600f, 2f, 7), AdaptiveRules.actionColumns(600f, 3f, 7))
    }

    @Test
    fun actionsBeside_onlyWhenShortAndWide() {
        assertTrue(AdaptiveRules.actionsBeside(640f, 360f))
        assertTrue(AdaptiveRules.actionsBeside(915f, 412f))
        assertFalse(AdaptiveRules.actionsBeside(412f, 915f))
        assertFalse(AdaptiveRules.actionsBeside(480f, 400f)) // short but narrow: under the content
        assertFalse(AdaptiveRules.actionsBeside(840f, 1200f))
    }

    @Test
    fun contentKeepsAThird_atLeast120dp_neverMoreThanTheSpace() {
        assertEquals(120f, AdaptiveRules.minContentHeightDp(300f), 0.01f)
        assertEquals(315f, AdaptiveRules.minContentHeightDp(900f), 0.01f)
        assertEquals(100f, AdaptiveRules.minContentHeightDp(100f), 0.01f)
    }
}
