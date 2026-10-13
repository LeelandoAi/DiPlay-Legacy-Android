package com.shilapi.xcertplay.usbbt

/**
 * Bluetooth HCI/USB transport protocol constants, packet encoding and parsing.
 *
 * Pure Kotlin with no Android dependencies, so it is unit-testable on the JVM.
 * Based on the Bluetooth Core Specification:
 * - Vol 4, Part B (USB Transport Layer): HCI commands travel as USB control
 *   transfers on endpoint 0 (bmRequestType=0x20, bRequest=0); HCI events arrive
 *   on the interrupt IN endpoint; ACL data uses bulk IN/OUT endpoints.
 * - Vol 4, Part E (HCI): command/event packet formats and opcodes.
 *
 * Clean-room implementation: derived from the public spec, not from any
 * third-party code.
 */
object HciProtocol {

    // USB transport: HCI commands are sent with a class-specific control transfer.
    const val USB_BT_REQ_TYPE_CLASS_INTERFACE_OUT = 0x20
    const val USB_BT_CONTROL_REQUEST = 0x00

    // USB-IF "Wireless Controller" class codes identifying a Bluetooth dongle.
    const val USB_CLASS_WIRELESS_CONTROLLER = 0xE0
    const val USB_SUBCLASS_RF = 0x01
    const val USB_PROTOCOL_BLUETOOTH = 0x01

    // HCI packet indicators as seen on interrupt/bulk endpoints.
    const val HCI_EVENT_PACKET = 0x04
    const val HCI_ACL_PACKET = 0x02

    // HCI event codes we care about during init.
    const val EVT_COMMAND_COMPLETE = 0x0E
    const val EVT_COMMAND_STATUS = 0x0D

    /** HCI opcode = (OGF << 10) | OCF. */
    fun opcode(ogf: Int, ocf: Int): Int = (ogf shl 10) or ocf

    val OP_RESET = opcode(0x03, 0x0003)
    val OP_READ_LOCAL_VERSION = opcode(0x04, 0x0001)
    val OP_READ_BD_ADDR = opcode(0x04, 0x0009)
    val OP_READ_BUFFER_SIZE = opcode(0x04, 0x0005)
    val OP_WRITE_SCAN_ENABLE = opcode(0x03, 0x001A)
    val OP_WRITE_LOCAL_NAME = opcode(0x03, 0x0013)

    // Write_Scan_Enable values.
    const val SCAN_DISABLED = 0x00
    const val SCAN_INQUIRY_AND_PAGE = 0x03

    /** Max bytes of a single HCI event packet on the wire (indicator + header + 255 params). */
    const val MAX_EVENT_PACKET_SIZE = 260

    /**
     * Encode an HCI command for the USB control transfer payload:
     * opcode (2 bytes LE) + total param length (1 byte) + params.
     * The 0x01 packet indicator is NOT included; the control transfer wraps it.
     */
    fun encodeCommand(opcode: Int, params: ByteArray = byteArrayOf()): ByteArray {
        require(params.size <= 255) { "HCI params too long: ${params.size}" }
        val out = ByteArray(3 + params.size)
        out[0] = (opcode and 0xFF).toByte()
        out[1] = ((opcode ushr 8) and 0xFF).toByte()
        out[2] = params.size.toByte()
        params.copyInto(out, 3)
        return out
    }

    /** One parsed HCI event packet. */
    data class HciEvent(val code: Int, val params: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HciEvent) return false
            return code == other.code && params.contentEquals(other.params)
        }

        override fun hashCode(): Int = 31 * code + params.contentHashCode()
    }

    /** Parsed Command Complete (0x0E) event. */
    data class CommandComplete(
        val numHciCommandPackets: Int,
        val opcode: Int,
        val returnParams: ByteArray,
    )

    /** Parsed Command Status (0x0D) event. */
    data class CommandStatus(
        val status: Int,
        val numHciCommandPackets: Int,
        val opcode: Int,
    )

    /**
     * Parse one HCI event packet: 0x04 | event_code | param_len | params.
     * Returns null when [length] bytes do not hold a well-formed event.
     */
    fun parseEvent(packet: ByteArray, length: Int = packet.size): HciEvent? {
        if (length < 3 || length > packet.size) return null
        if (packet[0].toInt() and 0xFF != HCI_EVENT_PACKET) return null
        val paramLen = packet[2].toInt() and 0xFF
        if (3 + paramLen > length) return null
        return HciEvent(
            code = packet[1].toInt() and 0xFF,
            params = packet.copyOfRange(3, 3 + paramLen),
        )
    }

    /** Parse Command Complete (0x0E) params. Null when malformed. */
    fun parseCommandComplete(event: HciEvent): CommandComplete? {
        if (event.code != EVT_COMMAND_COMPLETE || event.params.size < 3) return null
        val opcode = (event.params[1].toInt() and 0xFF) or
            ((event.params[2].toInt() and 0xFF) shl 8)
        return CommandComplete(
            numHciCommandPackets = event.params[0].toInt() and 0xFF,
            opcode = opcode,
            returnParams = event.params.copyOfRange(3, event.params.size),
        )
    }

    /** Parse Command Status (0x0D) params. Null when malformed. */
    fun parseCommandStatus(event: HciEvent): CommandStatus? {
        if (event.code != EVT_COMMAND_STATUS || event.params.size < 4) return null
        val opcode = (event.params[2].toInt() and 0xFF) or
            ((event.params[3].toInt() and 0xFF) shl 8)
        return CommandStatus(
            status = event.params[0].toInt() and 0xFF,
            numHciCommandPackets = event.params[1].toInt() and 0xFF,
            opcode = opcode,
        )
    }

    /**
     * Parse a Read_BD_ADDR return: status(1) + 6-byte address (LSB first on the wire).
     * Returns "XX:XX:XX:XX:XX:XX" (MSB first) or null when status != 0 / malformed.
     */
    fun parseBdAddr(returnParams: ByteArray): String? {
        if (returnParams.size < 7 || returnParams[0] != 0.toByte()) return null
        return (5 downTo 0).joinToString(":") { i ->
            "%02X".format(returnParams[1 + i])
        }
    }

    /** Parsed Read_Buffer_Size return. */
    data class BufferSize(
        val aclDataPacketLength: Int,
        val scoDataPacketLength: Int,
        val totalNumAclPackets: Int,
        val totalNumScoPackets: Int,
    )

    /** Parse Read_Buffer_Size return: status(1) + ACL len(2) + SCO len(1) + ACL pkts(2) + SCO pkts(2). */
    fun parseBufferSize(returnParams: ByteArray): BufferSize? {
        if (returnParams.size < 8 || returnParams[0] != 0.toByte()) return null
        fun u16(at: Int): Int =
            (returnParams[at].toInt() and 0xFF) or ((returnParams[at + 1].toInt() and 0xFF) shl 8)
        return BufferSize(
            aclDataPacketLength = u16(1),
            scoDataPacketLength = returnParams[3].toInt() and 0xFF,
            totalNumAclPackets = u16(4),
            totalNumScoPackets = u16(6),
        )
    }

    /** Encode a Write_Local_Name payload: up to 248 bytes, NUL-padded. */
    fun encodeLocalName(name: String): ByteArray {
        val raw = name.toByteArray(Charsets.UTF_8)
        val out = ByteArray(248)
        raw.copyInto(out, 0, 0, minOf(raw.size, out.size))
        return out
    }
}
