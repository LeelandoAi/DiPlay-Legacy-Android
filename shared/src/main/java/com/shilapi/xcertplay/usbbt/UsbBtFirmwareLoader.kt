package com.shilapi.xcertplay.usbbt

import android.content.res.AssetManager
import android.util.Log
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_BCM_DOWNLOAD_MINIDRIVER
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_RESET
import com.shilapi.xcertplay.usbbt.HciProtocol.OP_RTL_DOWNLOAD_COMMAND

/**
 * Vendor firmware uploader for USB Bluetooth dongles.
 *
 * Many cheap Broadcom/Realtek dongles ship with an empty patchram: they
 * ignore HCI_Reset until a vendor firmware blob is uploaded. The blob
 * formats and upload sequences below are public protocol knowledge:
 *
 * - Broadcom `.hcd`: a flat sequence of HCI command records, each
 *   `[opcode: u16 LE][length: u8][payload]`. After the chip is put in
 *   download mode with 0xFC2E (DOWNLOAD_MINIDRIVER), every record is sent
 *   as one HCI command and its Command Complete is awaited; a final
 *   HCI_Reset activates the patch. (Same flow as the public
 *   brcm_patchram_plus tool and Linux btbcm patchram setup.)
 * - Realtek: opcode 0xFC20 (DOWNLOAD) switches the chip to download mode;
 *   the raw firmware image is then pushed in fragments via 0xFC20 with a
 *   1-byte fragment index header, and the chip signals completion. The
 *   fragment framing here follows the publicly described RTL download
 *   protocol; validate on a real RTL8761/RTL8821 dongle before trusting it.
 *
 * Firmware binaries are NOT bundled with the app: the user copies the file
 * for their dongle (from the vendor driver package or another public
 * source) to `assets/usbbt_fw/<vid>_<pid>.hcd` (Broadcom) or
 * `assets/usbbt_fw/<vid>_<pid>.bin` (Realtek). When no file is present the
 * loader reports [LoadResult.NoFirmware] and the caller keeps the previous
 * graceful-degradation behaviour.
 *
 * Clean-room: protocol facts only, no third-party implementation referenced.
 * Blocking; call from a background thread.
 */
class UsbBtFirmwareLoader(
    private val hci: UsbBtHci,
    private val assets: AssetManager,
) {

    companion object {
        private const val TAG = "UsbBtFwLoader"

        /** Assets sub-directory holding user-supplied firmware files. */
        const val FW_ASSET_DIR = "usbbt_fw"

        /** Max HCI payload per Realtek download fragment (publicly used 252). */
        const val RTL_FRAGMENT_SIZE = 252

        /** Vendor IDs with a known upload sequence. */
        const val VID_BROADCOM = 0x0a5c
        const val VID_REALTEK = 0x0bda
    }

    sealed class LoadResult {
        /** Firmware uploaded and the chip reset; re-run HCI init afterwards. */
        data class Uploaded(val bytes: Int, val detail: String) : LoadResult()

        /** No firmware file for this VID/PID in assets. */
        data class NoFirmware(val assetName: String) : LoadResult()

        /** Upload attempted but failed; [reason] explains. */
        data class Failed(val reason: String) : LoadResult()
    }

    /**
     * One `.hcd` record: [opcode] with [payload]. Pure data; JVM-testable via
     * [HcdParser].
     */
    data class HcdRecord(val opcode: Int, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HcdRecord) return false
            return opcode == other.opcode && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = 31 * opcode + payload.contentHashCode()
    }

    /**
     * Parser for Broadcom `.hcd` files. Format (public):
     * repeated `[opcode: u16 LE][len: u8][len bytes payload]`.
     * Returns null when the byte stream is truncated or malformed.
     */
    object HcdParser {
        fun parse(bytes: ByteArray): List<HcdRecord>? {
            val out = ArrayList<HcdRecord>()
            var i = 0
            while (i < bytes.size) {
                if (i + 3 > bytes.size) return null // truncated header
                val opcode = (bytes[i].toInt() and 0xFF) or
                    ((bytes[i + 1].toInt() and 0xFF) shl 8)
                val len = bytes[i + 2].toInt() and 0xFF
                if (i + 3 + len > bytes.size) return null // truncated payload
                out.add(HcdRecord(opcode, bytes.copyOfRange(i + 3, i + 3 + len)))
                i += 3 + len
            }
            return out
        }
    }

    /**
     * Upload firmware for the dongle behind [hci]. Returns [LoadResult].
     * On [LoadResult.Uploaded] the caller must re-run HCI init (the chip
     * was reset by the upload).
     */
    fun loadForDevice(vendorId: Int, productId: Int): LoadResult {
        return when (vendorId) {
            VID_BROADCOM -> loadBroadcom(vendorId, productId)
            VID_REALTEK -> loadRealtek(vendorId, productId)
            else -> LoadResult.NoFirmware(assetNameFor(vendorId, productId, ".hcd"))
        }
    }

    private fun assetNameFor(vendorId: Int, productId: Int, ext: String): String =
        "$FW_ASSET_DIR/${"%04x".format(vendorId)}_${"%04x".format(productId)}$ext"

    private fun readAsset(name: String): ByteArray? = try {
        assets.open(name).use { it.readBytes() }
    } catch (t: Throwable) {
        Log.i(TAG, "firmware asset not found: $name")
        null
    }

    // ------------------------------------------------------------------
    // Broadcom
    // ------------------------------------------------------------------

    private fun loadBroadcom(vendorId: Int, productId: Int): LoadResult {
        val assetName = assetNameFor(vendorId, productId, ".hcd")
        val bytes = readAsset(assetName) ?: return LoadResult.NoFirmware(assetName)
        val records = HcdParser.parse(bytes)
            ?: return LoadResult.Failed("malformed .hcd file: $assetName")
        if (records.isEmpty()) return LoadResult.Failed("empty .hcd file: $assetName")
        Log.i(TAG, "Broadcom firmware: ${records.size} hcd records (${bytes.size} bytes)")

        // 1. Put the chip in download mode.
        when (val r = hci.sendCommand(OP_BCM_DOWNLOAD_MINIDRIVER, timeoutMs = 3_000)) {
            is UsbBtHci.HciResult.Failed ->
                return LoadResult.Failed("DOWNLOAD_MINIDRIVER rejected: ${r.reason}")
            else -> { /* acknowledged; proceed */ }
        }

        // 2. Stream every .hcd record as one HCI command.
        for ((index, rec) in records.withIndex()) {
            when (val r = hci.sendCommand(rec.opcode, rec.payload, timeoutMs = 3_000)) {
                is UsbBtHci.HciResult.Failed -> return LoadResult.Failed(
                    "hcd record $index/${records.size} " +
                        "(opcode 0x${rec.opcode.toString(16)}) failed: ${r.reason}",
                )
                else -> { /* ok */ }
            }
        }

        // 3. Reset to activate the patchram.
        when (val r = hci.sendCommand(OP_RESET, timeoutMs = 5_000)) {
            is UsbBtHci.HciResult.Failed ->
                return LoadResult.Failed("post-upload HCI_Reset failed: ${r.reason}")
            else -> { /* ok */ }
        }
        Log.i(TAG, "Broadcom firmware upload complete")
        return LoadResult.Uploaded(bytes.size, "${records.size} hcd records")
    }

    // ------------------------------------------------------------------
    // Realtek
    // ------------------------------------------------------------------

    private fun loadRealtek(vendorId: Int, productId: Int): LoadResult {
        val assetName = assetNameFor(vendorId, productId, ".bin")
        val image = readAsset(assetName) ?: return LoadResult.NoFirmware(assetName)
        if (image.isEmpty()) return LoadResult.Failed("empty firmware file: $assetName")
        Log.i(TAG, "Realtek firmware: ${image.size} bytes")

        // 1. Enter download mode. Public sequence: 0xFC20 acknowledges and the
        // chip then expects firmware fragments on the same opcode.
        when (val r = hci.sendCommand(OP_RTL_DOWNLOAD_COMMAND, timeoutMs = 3_000)) {
            is UsbBtHci.HciResult.Failed ->
                return LoadResult.Failed("RTL DOWNLOAD rejected: ${r.reason}")
            else -> { /* acknowledged */ }
        }

        // 2. Push the image in fragments: [index:1][data]. The index wraps at
        // 0xFF; the final fragment carries the end marker bit (0x80).
        // Framing follows the publicly described RTL download protocol —
        // validate against a real RTL8761/RTL8821 dongle.
        val total = (image.size + RTL_FRAGMENT_SIZE - 1) / RTL_FRAGMENT_SIZE
        var offset = 0
        var index = 0
        while (offset < image.size) {
            val end = minOf(offset + RTL_FRAGMENT_SIZE, image.size)
            val isLast = end >= image.size
            val fragIndex = ((index and 0x7F) or (if (isLast) 0x80 else 0x00)).toByte()
            val frag = byteArrayOf(fragIndex) + image.copyOfRange(offset, end)
            when (val r = hci.sendCommand(OP_RTL_DOWNLOAD_COMMAND, frag, timeoutMs = 3_000)) {
                is UsbBtHci.HciResult.Failed -> return LoadResult.Failed(
                    "RTL fragment ${index + 1}/$total failed: ${r.reason}",
                )
                else -> { /* ok */ }
            }
            offset = end
            index++
        }

        // 3. Reset to boot the new firmware.
        when (val r = hci.sendCommand(OP_RESET, timeoutMs = 5_000)) {
            is UsbBtHci.HciResult.Failed ->
                return LoadResult.Failed("post-upload HCI_Reset failed: ${r.reason}")
            else -> { /* ok */ }
        }
        Log.i(TAG, "Realtek firmware upload complete ($total fragments)")
        return LoadResult.Uploaded(image.size, "$total fragments")
    }
}
