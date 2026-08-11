package com.rammy.aigun.camera

import android.hardware.usb.UsbConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UvcClassifierTest {
    @Test
    fun acceptsDeviceLevelVideoClass() {
        assertTrue(UvcClassifier.isVideoClass(UsbConstants.USB_CLASS_VIDEO, emptyList()))
    }

    @Test
    fun acceptsCompositeDeviceWithVideoInterface() {
        assertTrue(UvcClassifier.isVideoClass(0, listOf(UsbConstants.USB_CLASS_AUDIO, UsbConstants.USB_CLASS_VIDEO)))
    }

    @Test
    fun rejectsNonVideoUsbDevice() {
        assertFalse(UvcClassifier.isVideoClass(0, listOf(UsbConstants.USB_CLASS_MASS_STORAGE)))
    }
}

