package com.rammy.aigun.camera

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice

object UvcClassifier {
    fun isUvc(device: UsbDevice): Boolean = isVideoClass(
        deviceClass = device.deviceClass,
        interfaceClasses = List(device.interfaceCount) { index ->
            device.getInterface(index).interfaceClass
        },
    )

    internal fun isVideoClass(deviceClass: Int, interfaceClasses: List<Int>): Boolean =
        deviceClass == UsbConstants.USB_CLASS_VIDEO ||
            interfaceClasses.any { it == UsbConstants.USB_CLASS_VIDEO }
}

