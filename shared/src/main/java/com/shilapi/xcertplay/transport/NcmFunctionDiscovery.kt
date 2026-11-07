package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/**
 * Finds the NCM control/data interface pair inside an active iPhone configuration.
 *
 * LIVI claims the control interface, claims the data interface, and selects data alternate
 * setting 1, the setting that carries the bulk endpoints. This class only reads descriptors.
 */
object NcmFunctionDiscovery {
    const val CONTROL_CLASS = 0x02
    const val CONTROL_SUBCLASS = 0x0d
    const val DATA_CLASS = 0x0a
    const val APPLE_ETHERNET_CLASS = 0xff
    const val APPLE_ETHERNET_SUBCLASS = 0xfd
    const val APPLE_ETHERNET_PROTOCOL = 0x01
    const val DATA_ALTERNATE_SETTING = 1

    data class NcmFunction(
        val control: UsbInterface,
        /** Data alternate setting that exposes the NCM bulk endpoints (normally alt=1). */
        val data: UsbInterface,
        /** Same data interface at alt=0 when exposed; legacy Android should claim this first. */
        val dataClaim: UsbInterface,
        val statusIn: UsbEndpoint?,
        val bulkIn: UsbEndpoint,
        val bulkOut: UsbEndpoint,
    )

    fun find(configuration: CarPlayUsbConfiguration): NcmFunction? {
        return findCdcNcm(configuration)
    }

    private fun findCdcNcm(configuration: CarPlayUsbConfiguration): NcmFunction? {
        val all = interfaces(configuration)
        val controls = all.filter {
            it.interfaceClass == CONTROL_CLASS && it.interfaceSubclass == CONTROL_SUBCLASS
        }
        // An iPhone exposes two NCM functions. The standard one carries the interrupt status
        // endpoint; the Aux one (`anpi`, RemoteXPC) does not — its endpoint is disabled through
        // the `ncm-interrupt-ep-disabled` device-tree key. Only the standard pair carries
        // CarPlay's Ethernet traffic, so prefer the control interface that has the status endpoint.
        val control = controls.firstOrNull { statusEndpoint(it) != null }
            ?: controls.firstOrNull()
            ?: return null
        val candidates = all.filter {
            it.interfaceClass == DATA_CLASS && bulkEndpoints(it) != null
        }
        // The CDC Union descriptor is authoritative; id order only stands in when it is missing.
        val declared = configuration.unions[control.id]
        val data = declared?.let { slave -> candidates.firstOrNull { it.id == slave } }
            ?: candidates.firstOrNull { it.id == control.id + 1 }
            ?: candidates.minByOrNull {
                if (IphoneCarPlayConfiguration.alternateSetting(it) == DATA_ALTERNATE_SETTING) 0 else 1
            }
            ?: return null
        val endpoints = bulkEndpoints(data) ?: return null
        val dataClaim = interfaces(configuration).firstOrNull {
            it.id == data.id && IphoneCarPlayConfiguration.alternateSetting(it) == 0
        } ?: data
        val statusIn = statusEndpoint(control)
        return NcmFunction(control, data, dataClaim, statusIn, endpoints.first, endpoints.second)
    }

    private fun statusEndpoint(control: UsbInterface): UsbEndpoint? =
        (0 until control.endpointCount)
            .map(control::getEndpoint)
            .singleOrNull {
                it.direction == UsbConstants.USB_DIR_IN &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_INT
            }

    private fun interfaces(configuration: CarPlayUsbConfiguration): List<UsbInterface> = configuration.interfaces

    private fun bulkEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        val endpoints = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
        val input = endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        val output = endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        return if (input != null && output != null) input to output else null
    }
}
