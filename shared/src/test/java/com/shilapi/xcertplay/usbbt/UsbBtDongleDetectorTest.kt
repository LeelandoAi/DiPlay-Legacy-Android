package com.shilapi.xcertplay.usbbt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbBtDongleDetectorTest {

    @Test
    fun identifiesUgreenHaylouVendor() {
        val label = UsbBtDongleDetector.resolveVendorLabel(0x2b89, viaClassCode = true)
        assertEquals("UGREEN / Haylou (0x2b89)", label)

        val fallback = UsbBtDongleDetector.resolveVendorLabel(0x2b89, viaClassCode = false, productId = 0x6275)
        assertEquals("UGREEN / Haylou (0x2b89:0x6275)", fallback)
    }

    @Test
    fun identifiesTpLinkVendor() {
        val label = UsbBtDongleDetector.resolveVendorLabel(0x2357, viaClassCode = true)
        assertEquals("TP-Link (0x2357)", label)

        val fallback = UsbBtDongleDetector.resolveVendorLabel(0x2357, viaClassCode = false, productId = 0x0604)
        assertEquals("TP-Link (0x2357:0x0604)", fallback)
    }

    @Test
    fun identifiesAsusVendor() {
        val label = UsbBtDongleDetector.resolveVendorLabel(0x0b05, viaClassCode = true)
        assertEquals("ASUS (0x0b05)", label)
    }

    @Test
    fun identifiesGenericBluetoothVendor() {
        val label = UsbBtDongleDetector.resolveVendorLabel(0x9999, viaClassCode = true)
        assertEquals("Generic Bluetooth (0x9999)", label)
    }

    @Test
    fun knownVendorsTableContainsMajorManufacturers() {
        val table = UsbBtDongleDetector.KNOWN_BT_VENDORS
        assertTrue(table.containsKey(0x0bda)) // Realtek
        assertTrue(table.containsKey(0x2b89)) // UGREEN / Haylou
        assertTrue(table.containsKey(0x2357)) // TP-Link
        assertTrue(table.containsKey(0x0b05)) // ASUS
        assertTrue(table.containsKey(0x7392)) // Edimax
        assertTrue(table.containsKey(0x2001)) // D-Link
        assertTrue(table.containsKey(0x0a5c)) // Broadcom
        assertTrue(table.containsKey(0x0a12)) // CSR / Qualcomm
        assertTrue(table.containsKey(0x8087)) // Intel
    }
}
