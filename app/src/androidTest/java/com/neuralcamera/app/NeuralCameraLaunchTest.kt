package com.neuralcamera.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Basic instrumentation launch and package verification test (Section 23).
 */
@RunWith(AndroidJUnit4::class)
class NeuralCameraLaunchTest {

    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull(appContext)
        assertEquals("com.neuralcamera.app.debug", appContext.packageName)
    }
}
