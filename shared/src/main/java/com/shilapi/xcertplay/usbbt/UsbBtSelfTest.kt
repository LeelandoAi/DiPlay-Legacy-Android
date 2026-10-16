package com.shilapi.xcertplay.usbbt

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * Diagnostic and self-test utility for external USB Bluetooth dongles.
 *
 * Runs clean-room HCI verification:
 * 1. Enumeration & permission check via Android USB Host API.
 * 2. HCI endpoint discovery (Interrupt IN, Bulk IN, Bulk OUT).
 * 3. Control transfer: HCI_RESET (0x0C03) and awaits Command Complete event.
 * 4. Control transfer: HCI_READ_BD_ADDR (0x1009) to read local Bluetooth MAC.
 * 5. Control transfer: HCI_READ_BUFFER_SIZE (0x1005) to read ACL buffer capacities.
 */
object UsbBtSelfTest {
    private const val TAG = "UsbBtSelfTest"
    private const val TIMEOUT_MS = 2000

    data class Report(
        val ok: Boolean,
        val status: String,
        val message: String,
        val device: UsbDevice? = null,
        val bdAddr: String? = null,
        val vendorLabel: String? = null,
        val needsPermission: Boolean = false,
        val details: List<String> = emptyList(),
    )

    fun run(context: Context, targetDevice: UsbDevice? = null): Report {
        val usbManager = runCatching {
            context.getSystemService(Context.USB_SERVICE) as? UsbManager
        }.getOrNull() ?: return Report(
            ok = false,
            status = "NoUsbManager",
            message = "USB Host service unavailable on this system",
        )
        return runWithManager(context, usbManager, targetDevice)
    }

    fun runWithManager(context: Context, usbManager: UsbManager, targetDevice: UsbDevice? = null): Report {
        val detector = UsbBtDongleDetector(context.applicationContext)
        val dongles = detector.findDongles(usbManager)
        val candidate = targetDevice?.let { dev ->
            dongles.firstOrNull { it.device == dev } ?: detector.identify(dev)
        } ?: dongles.firstOrNull()

        if (candidate == null) {
            return Report(
                ok = false,
                status = "NoDongle",
                message = "未检测到外置 USB 蓝牙适配器",
            )
        }

        val device = candidate.device
        val vendorLabel = candidate.vendorLabel
        val logDetails = mutableListOf<String>()
        logDetails.add("设备: $vendorLabel (VID=0x${device.vendorId.toString(16)} PID=0x${device.productId.toString(16)})")

        if (!usbManager.hasPermission(device)) {
            return Report(
                ok = false,
                status = "NeedsPermission",
                message = "未获取 USB 权限，请点击授权",
                device = device,
                vendorLabel = vendorLabel,
                needsPermission = true,
                details = logDetails,
            )
        }

        // Open device
        val connection: UsbDeviceConnection = usbManager.openDevice(device) ?: return Report(
            ok = false,
            status = "OpenFailed",
            message = "打开 USB 设备连接失败 (openDevice returned null)",
            device = device,
            vendorLabel = vendorLabel,
            details = logDetails,
        )

        try {
            val btIface = findBluetoothInterface(device) ?: return Report(
                ok = false,
                status = "NoInterface",
                message = "未找到可用的蓝牙 HCI 接口",
                device = device,
                vendorLabel = vendorLabel,
                details = logDetails,
            )
            logDetails.add("HCI 接口 ID: ${btIface.id}")

            if (!connection.claimInterface(btIface, true)) {
                return Report(
                    ok = false,
                    status = "ClaimFailed",
                    message = "占用接口失败 (claimInterface returned false)",
                    device = device,
                    vendorLabel = vendorLabel,
                    details = logDetails,
                )
            }

            val endpoints = findEndpoints(btIface) ?: return Report(
                ok = false,
                status = "NoEndpoints",
                message = "未找到完整 HCI 端点 (需要中断输入及批量收发端点)",
                device = device,
                vendorLabel = vendorLabel,
                details = logDetails,
            )
            logDetails.add("端点: Event=0x${endpoints.event.address.toString(16)} ACL_IN=0x${endpoints.aclIn.address.toString(16)} ACL_OUT=0x${endpoints.aclOut.address.toString(16)}")

            // Step 1: Send HCI_RESET
            val resetPayload = HciProtocol.encodeCommand(HciProtocol.OP_RESET)
            val resetSent = connection.controlTransfer(
                HciProtocol.USB_BT_REQ_TYPE_CLASS_INTERFACE_OUT,
                HciProtocol.USB_BT_CONTROL_REQUEST,
                0,
                btIface.id,
                resetPayload,
                resetPayload.size,
                TIMEOUT_MS,
            )
            if (resetSent != resetPayload.size) {
                return Report(
                    ok = false,
                    status = "ResetFailed",
                    message = "HCI Reset 控制传输失败 (sent $resetSent/${resetPayload.size})",
                    device = device,
                    vendorLabel = vendorLabel,
                    details = logDetails,
                )
            }
            logDetails.add("HCI Reset 命令发送成功")

            // Wait for Reset Command Complete
            val resetEvent = pollEvent(connection, endpoints.event, HciProtocol.OP_RESET)
            if (resetEvent == null) {
                return Report(
                    ok = false,
                    status = "ResetTimeout",
                    message = "HCI Reset 响应超时",
                    device = device,
                    vendorLabel = vendorLabel,
                    details = logDetails,
                )
            }
            logDetails.add("HCI Reset 响应正常 (Command Complete)")

            // Step 2: Read BD_ADDR
            val bdAddrPayload = HciProtocol.encodeCommand(HciProtocol.OP_READ_BD_ADDR)
            connection.controlTransfer(
                HciProtocol.USB_BT_REQ_TYPE_CLASS_INTERFACE_OUT,
                HciProtocol.USB_BT_CONTROL_REQUEST,
                0,
                btIface.id,
                bdAddrPayload,
                bdAddrPayload.size,
                TIMEOUT_MS,
            )
            val bdAddrEvent = pollEvent(connection, endpoints.event, HciProtocol.OP_READ_BD_ADDR)
            val bdAddr = bdAddrEvent?.let { HciProtocol.parseBdAddr(it.returnParams) }
            if (bdAddr != null) {
                logDetails.add("读取本地 BD_ADDR: $bdAddr")
            } else {
                logDetails.add("读取本地 BD_ADDR 失败或格式不符")
            }

            // Step 3: Read Buffer Size
            val bufPayload = HciProtocol.encodeCommand(HciProtocol.OP_READ_BUFFER_SIZE)
            connection.controlTransfer(
                HciProtocol.USB_BT_REQ_TYPE_CLASS_INTERFACE_OUT,
                HciProtocol.USB_BT_CONTROL_REQUEST,
                0,
                btIface.id,
                bufPayload,
                bufPayload.size,
                TIMEOUT_MS,
            )
            val bufEvent = pollEvent(connection, endpoints.event, HciProtocol.OP_READ_BUFFER_SIZE)
            val bufferSize = bufEvent?.let { HciProtocol.parseBufferSize(it.returnParams) }
            if (bufferSize != null) {
                logDetails.add("ACL 数据包限制: ${bufferSize.aclDataPacketLength} 字节, 容量: ${bufferSize.totalNumAclPackets}")
            }

            runCatching { connection.releaseInterface(btIface) }

            return Report(
                ok = true,
                status = "Success",
                message = "自检通过: 芯片通讯正常" + (if (bdAddr != null) " (MAC: $bdAddr)" else ""),
                device = device,
                bdAddr = bdAddr,
                vendorLabel = vendorLabel,
                details = logDetails,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Self-test error", t)
            return Report(
                ok = false,
                status = "Exception",
                message = "自检异常: ${t.message ?: t.javaClass.simpleName}",
                device = device,
                vendorLabel = vendorLabel,
                details = logDetails,
            )
        } finally {
            runCatching { connection.close() }
        }
    }

    private data class Endpoints(
        val event: UsbEndpoint,
        val aclIn: UsbEndpoint,
        val aclOut: UsbEndpoint,
    )

    private fun findBluetoothInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == HciProtocol.USB_CLASS_WIRELESS_CONTROLLER &&
                intf.interfaceSubclass == HciProtocol.USB_SUBCLASS_RF &&
                intf.interfaceProtocol == HciProtocol.USB_PROTOCOL_BLUETOOTH
            ) {
                return intf
            }
        }
        return if (device.interfaceCount > 0) device.getInterface(0) else null
    }

    private fun findEndpoints(intf: UsbInterface): Endpoints? {
        var event: UsbEndpoint? = null
        var aclIn: UsbEndpoint? = null
        var aclOut: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            val isIn = ep.direction == UsbConstants.USB_DIR_IN
            when (ep.type) {
                UsbConstants.USB_ENDPOINT_XFER_INT -> if (isIn && event == null) event = ep
                UsbConstants.USB_ENDPOINT_XFER_BULK -> {
                    if (isIn && aclIn == null) aclIn = ep
                    if (!isIn && aclOut == null) aclOut = ep
                }
                else -> Unit
            }
        }
        val e = event ?: return null
        val inEp = aclIn ?: return null
        val outEp = aclOut ?: return null
        return Endpoints(e, inEp, outEp)
    }

    private fun pollEvent(
        connection: UsbDeviceConnection,
        eventEndpoint: UsbEndpoint,
        expectedOpcode: Int,
        timeoutMs: Int = TIMEOUT_MS,
    ): HciProtocol.CommandComplete? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(HciProtocol.MAX_EVENT_PACKET_SIZE)
        while (System.currentTimeMillis() < deadline) {
            val read = connection.bulkTransfer(eventEndpoint, buf, buf.size, 500)
            if (read > 0) {
                val evt = HciProtocol.parseEvent(buf, read) ?: continue
                if (evt.code == HciProtocol.EVT_COMMAND_COMPLETE) {
                    val cc = HciProtocol.parseCommandComplete(evt)
                    if (cc != null && cc.opcode == expectedOpcode) {
                        return cc
                    }
                }
            }
        }
        return null
    }
}
