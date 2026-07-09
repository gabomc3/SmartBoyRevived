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

    /**
     * @param preRomBytes ROM bytes already received during readCartridgeInfo()
     *   (non-empty when the device sends nm/rb/startrom/ROM all in one burst,
     *    without waiting for the "sd" command).
     */
    data class CartridgeInfo(
        val name: String,
        val numBanks: Int,
        val preRomBytes: ByteArray = ByteArray(0)
    ) {
        val romSizeBytes: Int get() = numBanks * BANK_SIZE
        val romSizeKb: Int get() = numBanks * 16
        override fun equals(other: Any?) =
            other is CartridgeInfo && name == other.name && numBanks == other.numBanks
        override fun hashCode() = 31 * name.hashCode() + numBanks
    }

    suspend fun readCartridgeInfo(onNoCart: () -> Unit): CartridgeInfo =
        withContext(Dispatchers.IO) {
            val acc = StringBuilder()
            val tmp = ByteArray(256)
            var romName: String? = null
            var numBanks: Int = -1
            var found = false

            while (romName == null || numBanks < 1) {
                val n = port.read(tmp, 2000)
                if (n > 0) {
                    for (i in 0 until n) acc.append((tmp[i].toInt() and 0xFF).toChar())
                }

                val s = acc.toString()

                var from = 0
                found = false
                while (!found) {
                    val nmIdx = s.indexOf("nm", from)
                    if (nmIdx < 0) break
                    val rbIdx = s.indexOf("rb", nmIdx + 2)
                    if (rbIdx < 0) break

                    val candidateName = s.substring(nmIdx + 2, rbIdx)
                    if (candidateName.isEmpty()) { from = nmIdx + 1; continue }

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

                if (!found && s.contains("nr")) {
                    onNoCart()
                }

                if (!found && acc.length > 512) acc.delete(0, acc.length - 512)
            }

            if (!acc.contains("startrom")) {
                val captureEnd = System.currentTimeMillis() + 500L
                while (System.currentTimeMillis() < captureEnd) {
                    val n = port.read(tmp, 100)
                    if (n > 0) {
                        for (i in 0 until n) acc.append((tmp[i].toInt() and 0xFF).toChar())
                        if (acc.contains("startrom")) break
                    }
                }
            }

            val s = acc.toString()
            val srIdx = s.indexOf("startrom")
            val preRomBytes = if (srIdx >= 0) {
                val dataStart = srIdx + "startrom".length
                ByteArray(maxOf(0, s.length - dataStart)) { i ->
                    (s[dataStart + i].code and 0xFF).toByte()
                }
            } else ByteArray(0)

            CartridgeInfo(romName!!, numBanks, preRomBytes)
        }

    suspend fun dumpRom(
        info: CartridgeInfo,
        onProgress: suspend (Int) -> Unit
    ): ByteArray = withContext(Dispatchers.IO) {
        val romSize = info.romSizeBytes
        val out = ByteArrayOutputStream(romSize)

        // Send "sd" to trigger/re-trigger the dump cycle.
        port.write("sd".toByteArray(Charsets.US_ASCII), WRITE_TIMEOUT_MS)

        // Read the USB stream looking for a "startrom" marker whose ROM data
        // passes the Nintendo logo check (CE ED 66 66 at offset 0x104).
        //
        // WHY: the device may be mid-cycle when we start reading, so the first
        // bytes could be ROM data from some arbitrary offset, followed eventually
        // by "startrom" at the start of the next (or current) cycle.  We must
        // not trust a "startrom" marker blindly — we validate by checking the
        // logo bytes before committing.  Without this, every dump starts at a
        // random byte, producing corrupt output each time.
        //
        // Timeout: 120 s covers a full 1 MB cycle at 115 200 baud (~91 s) plus margin.
        val stream = StringBuilder()
        val tmp = ByteArray(256)
        val deadline = System.currentTimeMillis() + 120_000L
        var romStart = -1   // index in `stream` where ROM byte 0 begins
        var searchFrom = 0  // avoid re-scanning the same positions

        while (romStart < 0) {
            if (System.currentTimeMillis() > deadline) error("Timeout: valid ROM start not found")

            val n = port.read(tmp, 2000)
            if (n > 0) {
                for (i in 0 until n) stream.append((tmp[i].toInt() and 0xFF).toChar())
            }

            if (stream.contains("nr")) error("No cartridge detected")

            // Scan for every "startrom" occurrence since last check.
            while (true) {
                val srIdx = stream.indexOf("startrom", searchFrom)
                if (srIdx < 0) break

                val dataStart = srIdx + "startrom".length
                // Need at least 0x108 ROM bytes to read the logo at 0x104-0x107.
                if (stream.length < dataStart + 0x108) break

                val b104 = stream[dataStart + 0x104].code and 0xFF
                val b105 = stream[dataStart + 0x105].code and 0xFF
                val b106 = stream[dataStart + 0x106].code and 0xFF
                val b107 = stream[dataStart + 0x107].code and 0xFF

                if (b104 == 0xCE && b105 == 0xED && b106 == 0x66 && b107 == 0x66) {
                    romStart = dataStart  // confirmed: ROM byte 0 is here
                    break
                }

                // Logo mismatch - this "startrom" is mid-cycle; skip it.
                searchFrom = srIdx + 1
            }
            if (romStart < 0) searchFrom = maxOf(searchFrom, stream.length - 8)
        }

        // Write the ROM bytes already in the stream buffer.
        for (i in romStart until stream.length) {
            if (out.size() >= romSize) break
            out.write(stream[i].code and 0xFF)
        }

        // Read the remainder directly from USB.
        var totalRead = out.size()
        val chunk = ByteArray(4096)
        while (totalRead < romSize) {
            val n = port.read(chunk, READ_TIMEOUT_MS)
            if (n > 0) {
                val actual = minOf(n, romSize - totalRead)
                out.write(chunk, 0, actual)
                totalRead += actual
                withContext(Dispatchers.Main) { onProgress(totalRead * 100 / romSize) }
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
