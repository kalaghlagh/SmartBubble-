package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.hypot

/**
 * Checks the launcher icon resources as files (pure JVM, runs from the app module directory):
 * adaptive icon wiring, manifest references, safe-zone placement of the foreground artwork and
 * the legacy PNG sizes. The old raster artwork must be gone.
 */
class LauncherIconResourcesTest {

    private val res = File("src/main/res")
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun xml(path: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(File(res, path)).documentElement
    }

    private fun drawableRef(e: Element, tag: String): String {
        val node = e.getElementsByTagName(tag).item(0) as Element
        return node.getAttributeNS(androidNs, "drawable").removePrefix("@drawable/")
    }

    @Test
    fun adaptiveIcons_referenceExistingLayers() {
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val icon = xml("mipmap-anydpi-v26/$name.xml")
            assertEquals("adaptive-icon", icon.tagName)
            assertEquals("ic_launcher_background", drawableRef(icon, "background"))
            assertEquals("ic_launcher_foreground", drawableRef(icon, "foreground"))
            assertEquals("ic_launcher_monochrome", drawableRef(icon, "monochrome"))
        }
        for (layer in listOf("ic_launcher_background", "ic_launcher_foreground", "ic_launcher_monochrome")) {
            val vector = xml("drawable/$layer.xml")
            assertEquals("vector", vector.tagName)
            assertEquals("108", vector.getAttributeNS(androidNs, "viewportWidth"))
            assertEquals("108dp", vector.getAttributeNS(androidNs, "width"))
        }
    }

    @Test
    fun manifest_usesTheLauncherMipmaps() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue(manifest.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))
    }

    @Test
    fun oldRasterArtwork_isRemoved() {
        assertFalse(File(res, "drawable/smart_bubble_icon.jpg").exists())
        val anyReference = res.walkTopDown().filter { it.isFile && it.extension == "xml" }
            .any { it.readText().contains("smart_bubble_icon") }
        assertFalse(anyReference)
    }

    @Test
    fun legacyIcons_haveTheRightSizePerDensity() {
        val sizes = mapOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192)
        for ((density, px) in sizes) {
            for (name in listOf("ic_launcher", "ic_launcher_round")) {
                val image = ImageIO.read(File(res, "mipmap-$density/$name.png"))
                assertEquals("$density/$name width", px, image.width)
                assertEquals("$density/$name height", px, image.height)
                // Transparent corners: no white or black box around the icon.
                assertEquals("$density/$name corner alpha", 0, image.getRGB(0, 0) ushr 24)
            }
        }
    }

    /**
     * Every shape of the foreground and monochrome layers stays inside the adaptive icon safe
     * zone: a circle of radius 33 around the centre (54, 54) of the 108 x 108 canvas, so no
     * launcher mask (circle, squircle, rounded square, teardrop) can cut it.
     */
    @Test
    fun foreground_staysInsideTheSafeZone() {
        for (layer in listOf("ic_launcher_foreground", "ic_launcher_monochrome")) {
            val paths = xml("drawable/$layer.xml").getElementsByTagName("path")
            for (i in 0 until paths.length) {
                val path = paths.item(i) as Element
                val data = path.getAttributeNS(androidNs, "pathData")
                val stroke = path.getAttributeNS(androidNs, "strokeWidth").toDoubleOrNull() ?: 0.0
                val extent = maxDistanceFromCentre(data) + stroke / 2
                assertTrue("$layer path $i reaches $extent (limit 33): $data", extent <= 33.0 + 1e-6)
            }
        }
    }

    /** Max distance from (54,54) of a path made of absolute M / C / A / H / V / Z commands. */
    private fun maxDistanceFromCentre(data: String): Double {
        val tokens = Regex("""[MCAHVZ]|-?\d+(?:\.\d+)?""").findAll(data).map { it.value }.toList()
        var i = 0
        var x = 0.0
        var y = 0.0
        var max = 0.0
        fun num() = tokens[i++].toDouble()
        fun visit(px: Double, py: Double) {
            max = maxOf(max, hypot(px - 54, py - 54))
        }
        var command = ""
        while (i < tokens.size) {
            if (tokens[i].first().isLetter()) command = tokens[i++]
            when (command) {
                "M" -> { x = num(); y = num(); visit(x, y) }
                "C" -> repeat(3) { x = num(); y = num(); visit(x, y) }
                "H" -> { x = num(); visit(x, y) }
                "V" -> { y = num(); visit(x, y) }
                "A" -> {
                    val r = num(); num(); num()
                    val largeArc = num() != 0.0
                    val sweep = num() != 0.0
                    val nx = num()
                    val ny = num()
                    arcPoints(x, y, r, largeArc, sweep, nx, ny).forEach { (px, py) -> visit(px, py) }
                    x = nx; y = ny
                }
                "Z" -> Unit
            }
        }
        return max
    }

    /** Points along a circular SVG arc (endpoint parameterization, no rotation). */
    private fun arcPoints(x1: Double, y1: Double, radius: Double, largeArc: Boolean, sweep: Boolean, x2: Double, y2: Double): List<Pair<Double, Double>> {
        val hx = (x1 - x2) / 2
        val hy = (y1 - y2) / 2
        val r = maxOf(radius, hypot(hx, hy))
        val num = r * r - hx * hx - hy * hy
        val coef = Math.sqrt(maxOf(0.0, num / (hx * hx + hy * hy))) * (if (largeArc == sweep) -1 else 1)
        val cxp = coef * hy
        val cyp = -coef * hx
        val cx = cxp + (x1 + x2) / 2
        val cy = cyp + (y1 + y2) / 2
        val start = Math.atan2((hy - cyp) / r, (hx - cxp) / r)
        var delta = Math.atan2((-hy - cyp) / r, (-hx - cxp) / r) - start
        if (sweep && delta < 0) delta += 2 * Math.PI
        if (!sweep && delta > 0) delta -= 2 * Math.PI
        return (0..64).map { k ->
            val t = start + delta * k / 64
            (cx + r * Math.cos(t)) to (cy + r * Math.sin(t))
        }
    }
}
