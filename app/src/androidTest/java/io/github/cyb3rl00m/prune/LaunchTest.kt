package io.github.cyb3rl00m.prune

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchTest {
    @Test fun packageName() {
        assertEquals("io.github.cyb3rl00m.prune.debug", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
    }
}
