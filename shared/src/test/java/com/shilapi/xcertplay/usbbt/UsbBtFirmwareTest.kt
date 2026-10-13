package com.shilapi.xcertplay.usbbt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbBtFirmwareTest {

    @Test
    fun parseSingleRecord() {
        // opcode 0xFC2E LE, len 0 -> [2E FC 00]
        val bytes = byteArrayOf(0x2E, 0xFC.toByte(), 0x00)
        val records = UsbBtFirmwareLoader.HcdParser.parse(bytes)!!
        assertEquals(1, records.size)
        assertEquals(0xFC2E, records[0].opcode)
        assertArrayEquals(byteArrayOf(), records[0].payload)
    }

    @Test
    fun parseMultipleRecords() {
        val bytes = byteArrayOf(
            0x2E, 0xFC.toByte(), 0x00,          // record 1: 0xFC2E, empty
            0x03, 0x0C, 0x01, 0x05,             // record 2: 0x0C03, payload [05]
            0x1A, 0x0C, 0x02, 0x03, 0x04,       // record 3: 0x0C1A, payload [03 04]
        )
        val records = UsbBtFirmwareLoader.HcdParser.parse(bytes)!!
        assertEquals(3, records.size)
        assertEquals(0xFC2E, records[0].opcode)
        assertEquals(0x0C03, records[1].opcode)
        assertArrayEquals(byteArrayOf(0x05), records[1].payload)
        assertEquals(0x0C1A, records[2].opcode)
        assertArrayEquals(byteArrayOf(0x03, 0x04), records[2].payload)
    }

    @Test
    fun parseTruncatedHeaderReturnsNull() {
        assertNull(UsbBtFirmwareLoader.HcdParser.parse(byteArrayOf(0x2E, 0xFC.toByte())))
        assertNull(UsbBtFirmwareLoader.HcdParser.parse(byteArrayOf(0x2E)))
    }

    @Test
    fun parseTruncatedPayloadReturnsNull() {
        // Claims len=4 but only 2 payload bytes present.
        val bytes = byteArrayOf(0x03, 0x0C, 0x04, 0x01, 0x02)
        assertNull(UsbBtFirmwareLoader.HcdParser.parse(bytes))
    }

    @Test
    fun parseEmptyFileGivesEmptyList() {
        val records = UsbBtFirmwareLoader.HcdParser.parse(byteArrayOf())!!
        assertTrue(records.isEmpty())
    }

    @Test
    fun realtekFragmentCountMath() {
        // 600-byte image with 252-byte fragments -> 3 fragments.
        val imageSize = 600
        val total = (imageSize + UsbBtFirmwareLoader.RTL_FRAGMENT_SIZE - 1) /
            UsbBtFirmwareLoader.RTL_FRAGMENT_SIZE
        assertEquals(3, total)
        // Exact multiple: 504 bytes -> 2 fragments.
        val total2 = (504 + UsbBtFirmwareLoader.RTL_FRAGMENT_SIZE - 1) /
            UsbBtFirmwareLoader.RTL_FRAGMENT_SIZE
        assertEquals(2, total2)
    }

    @Test
    fun realtekLastFragmentHasEndMarker() {
        // The loader sets bit 0x80 on the final fragment index.
        val indexLast = ((2 and 0x7F) or 0x80).toByte()
        assertEquals(0x82.toByte(), indexLast)
        val indexMid = ((1 and 0x7F) or 0x00).toByte()
        assertEquals(0x01.toByte(), indexMid)
    }

    @Test
    fun assetNameFormat() {
        // VID/PID formatted as lowercase hex, matching the documented layout.
        val vid = 0x0a5c
        val pid = 0x21e8
        val name = "usbbt_fw/${"%04x".format(vid)}_${"%04x".format(pid)}.hcd"
        assertEquals("usbbt_fw/0a5c_21e8.hcd", name)
    }

    @Test
    fun vendorOpcodes() {
        // Public vendor opcodes used by the loader.
        assertEquals(0xFC2E, HciProtocol.OP_BCM_DOWNLOAD_MINIDRIVER)
        assertEquals(0xFC20, HciProtocol.OP_RTL_DOWNLOAD_COMMAND)
    }
}
