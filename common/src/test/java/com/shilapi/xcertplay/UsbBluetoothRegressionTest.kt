package com.shilapi.xcertplay

import android.hardware.usb.*
import com.shilapi.xcertplay.usbbt.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UsbBluetoothRegressionTest {
    private class UsbFixture {
        val context = RuntimeEnvironment.getApplication()
        val manager = mock(UsbManager::class.java)
        val device = mock(UsbDevice::class.java)
        val connection = mock(UsbDeviceConnection::class.java)
        val intf = mock(UsbInterface::class.java)
        val incoming = LinkedBlockingQueue<ByteArray>()
        val commands = mutableListOf<Int>()
        var transferResult: Int? = null
        var reply: (Int) -> ByteArray? = { opcode -> complete(opcode, when (opcode) {
            HciProtocol.OP_READ_BD_ADDR -> byteArrayOf(0, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11)
            HciProtocol.OP_READ_BUFFER_SIZE -> byteArrayOf(0, 0xFD.toByte(), 3, 0, 8, 0, 0, 0)
            else -> byteArrayOf(0)
        }) }
        init {
            `when`(device.vendorId).thenReturn(0x10D7)
            `when`(device.productId).thenReturn(0xB012)
            `when`(device.deviceClass).thenReturn(0xE0)
            `when`(device.deviceSubclass).thenReturn(1)
            `when`(device.deviceProtocol).thenReturn(1)
            `when`(device.interfaceCount).thenReturn(1)
            `when`(device.getInterface(0)).thenReturn(intf)
            `when`(intf.interfaceClass).thenReturn(0xE0)
            `when`(intf.interfaceSubclass).thenReturn(1)
            `when`(intf.interfaceProtocol).thenReturn(1)
            `when`(intf.endpointCount).thenReturn(3)
            for (index in 0..2) {
                val endpoint = mock(UsbEndpoint::class.java)
                `when`(endpoint.type).thenReturn(if (index == 0) UsbConstants.USB_ENDPOINT_XFER_INT else UsbConstants.USB_ENDPOINT_XFER_BULK)
                `when`(endpoint.direction).thenReturn(if (index < 2) UsbConstants.USB_DIR_IN else UsbConstants.USB_DIR_OUT)
                `when`(intf.getEndpoint(index)).thenReturn(endpoint)
            }
            `when`(manager.deviceList).thenReturn(hashMapOf("review-dongle" to device))
            `when`(manager.hasPermission(device)).thenReturn(true)
            `when`(manager.openDevice(device)).thenReturn(connection)
            `when`(connection.claimInterface(intf, true)).thenReturn(true)
            `when`(connection.controlTransfer(anyInt(), anyInt(), anyInt(), anyInt(), any(ByteArray::class.java), anyInt(), anyInt()))
                .thenAnswer { call ->
                    val packet = call.getArgument<ByteArray>(4)
                    val opcode = (packet[0].toInt() and 255) or ((packet[1].toInt() and 255) shl 8)
                    commands.add(opcode)
                    reply(opcode)?.let { incoming.offer(it) }
                    transferResult ?: packet.size
                }
            `when`(connection.bulkTransfer(any(UsbEndpoint::class.java), any(ByteArray::class.java), anyInt(), anyInt()))
                .thenAnswer { call ->
                    val response = incoming.poll(call.getArgument<Int>(3).toLong(), TimeUnit.MILLISECONDS)
                    if (response == null) -1 else { response.copyInto(call.getArgument(1)); response.size }
                }
        }
        fun run() = UsbBtSelfTest.runWithManager(context, manager)
        companion object {
            fun complete(opcode: Int, params: ByteArray) =
                byteArrayOf(0x0E, (3 + params.size).toByte(), 1, opcode.toByte(), (opcode ushr 8).toByte()) + params
        }
    }

    @Test fun selfTestAcceptsUsbRepliesAndClosesItsClaim() {
        val f = UsbFixture()
        val report = f.run()
        assertTrue(report.message, report.ok)
        assertEquals("11:22:33:44:55:66", report.bdAddr)
        verify(f.connection).releaseInterface(f.intf)
        verify(f.connection).close()
    }
    @Test fun rejectedResetStopsBeforeLaterChecks() {
        val f = UsbFixture()
        f.reply = { UsbFixture.complete(it, byteArrayOf(0x0C)) }
        assertEquals("ResetFailed", f.run().status)
        assertEquals(listOf(HciProtocol.OP_RESET), f.commands)
        verify(f.connection).releaseInterface(f.intf)
        verify(f.connection).close()
    }
    @Test fun missingResetStatusFails() {
        val f = UsbFixture()
        f.reply = { UsbFixture.complete(it, byteArrayOf()) }
        assertEquals("ResetFailed", f.run().status)
    }
    @Test fun failedOrMalformedReadsCannotPass() {
        for (opcode in listOf(HciProtocol.OP_READ_BD_ADDR, HciProtocol.OP_READ_BUFFER_SIZE)) {
            for (params in listOf(byteArrayOf(1), byteArrayOf(0))) {
                val f = UsbFixture()
                val normal = f.reply
                f.reply = { if (it == opcode) UsbFixture.complete(it, params) else normal(it) }
                val report = f.run()
                assertFalse(report.ok)
                assertEquals(if (opcode == HciProtocol.OP_READ_BD_ADDR) "ReadBdAddrFailed" else "ReadBufferSizeFailed", report.status)
            }
        }
    }
    @Test fun shortControlTransferFailsEvenWithAReply() {
        val f = UsbFixture()
        f.transferResult = 0
        assertEquals("ResetFailed", f.run().status)
    }
    @Test fun missingAddressReplyFails() {
        val f = UsbFixture()
        val normal = f.reply
        f.reply = { if (it == HciProtocol.OP_READ_BD_ADDR) null else normal(it) }
        assertEquals("ReadBdAddrFailed", f.run().status)
    }
    @Test fun hciRoutesUsbStatusAndConnectionEvents() {
        val f = UsbFixture()
        f.reply = { byteArrayOf(0x0F, 4, 0, 1, it.toByte(), (it ushr 8).toByte()) }
        UsbBtHci.open(f.manager, f.device)!!.use { hci ->
            assertTrue(hci.sendCommand(HciProtocol.OP_CREATE_CONNECTION) is UsbBtHci.HciResult.Ok)
            f.incoming.offer(byteArrayOf(0x03, 0))
            assertEquals(0x03, hci.pollEvent(1_000)?.code)
        }
        verify(f.connection).close()
    }
    @Test fun statusDoesNotWaitForUsbInitialization() {
        val f = UsbFixture()
        val initializing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val normal = f.reply
        f.reply = {
            if (it == HciProtocol.OP_RESET) { initializing.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            normal(it)
        }
        val adapter = UsbBtBluetoothAdapter(f.context)
        ReflectionHelpers.setField(adapter, "usbManager", f.manager)
        val enable = FutureTask { adapter.enable() }
        Thread(enable).start()
        try {
            assertTrue(initializing.await(2, TimeUnit.SECONDS))
            val status = FutureTask { adapter.getStatus() }
            Thread(status).start()
            assertFalse(status.get(1, TimeUnit.SECONDS).isEnabled)
            release.countDown()
            assertTrue(enable.get(3, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            enable.get(5, TimeUnit.SECONDS)
            adapter.shutdown()
        }
    }
}
