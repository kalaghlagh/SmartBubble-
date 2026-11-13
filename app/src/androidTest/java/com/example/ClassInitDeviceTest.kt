package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Loads and initializes every class of the app on the Android runtime (ART). The section editor
 * crash came from a static field (a regex ICU rejects) that the desktop JVM, and so every
 * Robolectric test, accepted: a failing static initializer, a method missing on this Android
 * version or a class ART refuses to verify shows up here, for every class of the app at once.
 */
@RunWith(AndroidJUnit4::class)
class ClassInitDeviceTest {

    @Test
    fun everyAppClassInitializesOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val names = dalvik.system.DexFile(context.packageCodePath).entries().toList()
            .filter { it.startsWith("com.example.") }
        val failures = mutableListOf<String>()
        var loaded = 0
        for (name in names) {
            try {
                Class.forName(name, true, context.classLoader)
                loaded++
            } catch (e: LinkageError) {
                // ExceptionInInitializerError, NoClassDefFoundError, VerifyError, ...
                failures += "$name: ${e.javaClass.simpleName}: ${generateSequence<Throwable>(e) { it.cause }.last().let { "${it.javaClass.name}: ${it.message}" }}"
            } catch (e: ClassNotFoundException) {
                failures += "$name: not found"
            }
        }
        assertTrue("only $loaded app classes found", loaded > 500)
        assertTrue("${failures.size} classes fail on Android:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
