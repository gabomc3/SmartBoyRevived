package com.smartboyrevived

import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class SmartBoyDumper(private val port: UsbSerialPort) {

    companion object {
        private const val BANK_SIZE = 16 * 1024
        private const val READ_TIMEOUT_MS = 10_000
        private const val WRITE_TIMEOUT_MS = 2_000
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

    /**
     * Read cartridge info by accumulating raw bytes and searching for the
     * nm<NAME>rb<BANKS> pattern using simple string indexOf — much more
     * robust than the character-by-character tag-matching approach that
     * could get stuck when starting mid-stream.
     */
    suspend fun readCartridgeInfo(onNoCart: () -> Unit): CartridgeInfo =
        withContext(Dispatchers.IO) {
            val acc = StringBuilder()
            val tmp = ByteArray(256)
            var romName: String? = null
            var numBanks: Int = -1

            while (romName == null || numBanks < 1) {
                val n = port.read(tmp, 2000)
                if (n > 0) {
                    for (i in 0 until n) acc.append((tmp[i].toInt() and 0xFF).toChar())
                }

                val s = acc.toString()

                // Search for nm<NAME>rb<DIGITS> pattern
                var from = 0
                var found = false
                while (!found) {
                    val nmIdx = s.indexOf("nm", from)
                    if (nmIdx < 0) break
                    val rbIdx = s.indexOf("rb", nmIdx + 2)
                    if (rbIdx < 0) break

                    val candidateName = s.substring(nmIdx + 2, rbIdx)
                    if (candidateName.isEmpty()) { from = nmIdx + 1; continue }

                    // Read digits after "rb"
                    var bankEnd = rbIdx + 2
                    while (bankEnd < s.length && s[bankEnd].isDigit()) bankEnd++

                    if (bankEnd > rbIdx + 2) {
                        val banks = s.substring(rbIdx + 2, bankEnd).toIntOrNull() ?: -1
                        if (banks > 0) {
                            romName = candidateName.trim()
                            numBanks = banks
                            found = true
                        }
                    }
                    from = nmIdx + 1
                }

                // If no name found yet, check for no-cartridge indicator
                if (!found && s.contains("nr")) {
                    onNoCart()
                }

                // Trim accumulator to last 512 chars so it doesn't grow forever
                if (acc.length > 512) acc.delete(0, acc.length - 512)
            }

            CartridgeInfo(romName!!, numBanks)
        }

    suspend fun dumpRom(
        info: CartridgeInfo,
        onProgress: suspend (Int) -> Unit
    ): ByteArray = withContext(Dispatchers.IO) {
        port.write("sd".toByteArray(Charsets.US_ASCII), WRITE_TIMEOUT_MS)

        // Wait for "startrom" marker
        val preamble = StringBuilder()
        val tmp = ByteArray(256)
        val deadline = System.currentTimeMillis() + 10_000L
        var startromFound = false
        while (!startromFound && System.currentTimeMillis() < deadline) {
            val n = port.read(tmp, 1000)
            if (n > 0) {
                for (i in 0 until n) preamble.append((tmp[i].toInt() and 0xFF).toChar())
                when {
                    preamble.contains("startrom") -> startromFound = true
                    preamble.contains("nr") -> error("No cartridge when dump started")
                }
            }
        }
        if (!startromFound) error("Timeout waiting for startrom marker")

        val romSize = info.romSizeBytes
        val out = ByteArrayOutputStream(romSize)
        var totalRead = 0
        val chunk = ByteArray(4096)

        while (totalRead < romSize) {
            val n = port.read(chunk, READ_TIMEOUT_MS)
            if (n > 0) {
                val actual = minOf(n, romSize - totalRead)
                out.write(chunk, 0, actual)
                totalRead += actual
                withContext(Dispatchers.Main) {
                    onProgress(totalRead * 100 / romSize)
                }
            }
        }
        out.toByteArray()
    }

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
