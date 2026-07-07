package com.smartboyrevived

import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Handles communication with the Hyperkin SmartBoy over USB CDC ACM serial.
 *
 * Protocol (reverse-engineered from the official app and documented at
 * https://github.com/hadess/smartboy-dumper):
 *
 *  Device continuously sends:  nmGAMENAMErb<banks>  (e.g. "nmTETRISrb16")
 *  Host sends "sd" to start dumping.
 *  Device replies: "startrom" + <binary ROM data> + "end"
 *  Optional SRAM follows: "srm" + <SRAM data> + "end"
 *  "nr" means no cartridge inserted.
 */
class SmartBoyDumper(private val port: UsbSerialPort) {

    companion object {
        private const val BANK_SIZE = 16 * 1024   // 16 KB per bank
        private const val READ_TIMEOUT_MS = 10_000
        private const val WRITE_TIMEOUT_MS = 2_000

        // GB magic bytes at offset 260 that identify Game Boy (vs Game Boy Color)
        private val GB_MAGIC = byteArrayOf(
            0xCE.toByte(), 0xED.toByte(), 0x66, 0x66,
            0xCC.toByte(), 0x0D, 0x00, 0x0B, 0x03, 0x73,
            0x00, 0x83.toByte(), 0x00, 0x0C, 0x00, 0x0D,
            0x00, 0x08, 0x11, 0x1F, 0x88.toByte(), 0x89.toByte(),
            0x00, 0x0E
        )
        private const val GB_MAGIC_OFFSET = 260

        val VENDOR_ID = 0x16D0
        val PRODUCT_ID = 0x0557
    }

    data class CartridgeInfo(val name: String, val numBanks: Int) {
        val romSizeBytes: Int get() = numBanks * BANK_SIZE
        val romSizeKb: Int get() = numBanks * 16
    }

    // -------------------------------------------------------------------------
    // Buffered single-byte reader.
    // USB CDC ACM sends data in packets (often 2+ bytes at once).
    // Passing a 1-byte buffer causes arraycopy crashes when the packet has
    // more than 1 byte.  We read in 64-byte chunks (USB full-speed max packet)
    // with a short timeout so we don't block the IO thread for too long, then
    // queue the bytes so callers still get one byte at a time.
    // -------------------------------------------------------------------------
    private val readBuffer = ByteArray(64)
    private val byteQueue = ArrayDeque<Byte>()

    private fun readByte(): Byte {
        while (byteQueue.isEmpty()) {
            val n = port.read(readBuffer, 500)   // 500 ms – short so we retry fast
            if (n > 0) repeat(n) { i -> byteQueue.addLast(readBuffer[i]) }
        }
        return byteQueue.removeFirst()
    }

    // -------------------------------------------------------------------------
    // Read bytes until one of the given tags appears at the end of the buffer.
    // Returns the tag that was found, and leaves 'out' containing everything
    // *before* the tag.
    // -------------------------------------------------------------------------
    private fun readUntilTag(out: StringBuilder, tags: List<String>): String {
        val buf = StringBuilder()
        while (true) {
            val c = (readByte().toInt() and 0xFF).toChar()
            buf.append(c)
            val s = buf.toString()
            for (tag in tags) {
                if (s.endsWith(tag)) {
                    out.append(s.dropLast(tag.length))
                    return tag
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Read cartridge info (ROM name + bank count).
    // The device sends "nm<NAME>rb<BANKS>" in a loop until we respond.
    // -------------------------------------------------------------------------
    suspend fun readCartridgeInfo(onNoCart: () -> Unit): CartridgeInfo =
        withContext(Dispatchers.IO) {
            var romName: String? = null
            var numBanks: Int = -1

            val stateTags = listOf("nm", "rb", "nr")
            val buf = StringBuilder()

            while (romName == null || numBanks < 1) {
                buf.clear()
                val tag = readUntilTag(buf, stateTags)

                when (tag) {
                    "nm" -> {
                        // Text that follows "nm" until next tag is the ROM name
                        val nameContent = StringBuilder()
                        val nextTag = readUntilTag(nameContent, stateTags)
                        romName = nameContent.toString().trim()

                        // Process whatever tag ended the name
                        when (nextTag) {
                            "rb" -> {
                                val bankContent = StringBuilder()
                                readUntilTag(bankContent, stateTags)
                                numBanks = bankContent.toString().trim().toIntOrNull() ?: -1
                            }
                            "nr" -> onNoCart()
                        }
                    }
                    "rb" -> {
                        val bankContent = StringBuilder()
                        readUntilTag(bankContent, stateTags)
                        numBanks = bankContent.toString().trim().toIntOrNull() ?: -1
                    }
                    "nr" -> onNoCart()
                }
            }

            CartridgeInfo(romName!!, numBanks)
        }

    // -------------------------------------------------------------------------
    // Dump the ROM.  Call after readCartridgeInfo().
    // onProgress receives 0..100.
    // Returns the raw ROM bytes.
    // -------------------------------------------------------------------------
    suspend fun dumpRom(
        info: CartridgeInfo,
        onProgress: suspend (Int) -> Unit
    ): ByteArray = withContext(Dispatchers.IO) {

        // Send start-dump command
        port.write("sd".toByteArray(Charsets.US_ASCII), WRITE_TIMEOUT_MS)

        // Wait for "startrom" marker (device may send more nm/rb before responding)
        val markerTags = listOf("startrom", "nr")
        val preBuf = StringBuilder()
        val marker = readUntilTag(preBuf, markerTags)
        if (marker == "nr") error("No cartridge inserted when dump started")

        // Read exactly romSize bytes of binary ROM data
        val romSize = info.romSizeBytes
        val out = ByteArrayOutputStream(romSize)
        var totalRead = 0
        val chunk = ByteArray(4096)

        while (totalRead < romSize) {
            val want = minOf(chunk.size, romSize - totalRead)
            val n = port.read(chunk, READ_TIMEOUT_MS)
            if (n > 0) {
                val actual = minOf(n, want)
                out.write(chunk, 0, actual)
                totalRead += actual
                withContext(Dispatchers.Main) {
                    onProgress(totalRead * 100 / romSize)
                }
            }
        }

        out.toByteArray()
    }

    // -------------------------------------------------------------------------
    // Detect GB vs GBC from ROM header
    // -------------------------------------------------------------------------
    fun isGameBoy(romData: ByteArray): Boolean {
        if (romData.size < GB_MAGIC_OFFSET + GB_MAGIC.size) return false
        return GB_MAGIC.indices.all { romData[GB_MAGIC_OFFSET + it] == GB_MAGIC[it] }
    }

    fun suggestFilename(info: CartridgeInfo, romData: ByteArray): String {
        val ext = if (isGameBoy(romData)) "gb" else "gbc"
        val safeName = info.name.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
        return "$safeName.$ext"
    }
}
