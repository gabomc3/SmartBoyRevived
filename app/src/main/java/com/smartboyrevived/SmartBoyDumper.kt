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

                // Only truncate while still searching — once nm/rb is found we
                // must NOT truncate: those accumulated bytes may include "startrom"
                // and the first ROM bytes, which we need to hand off to dumpRom().
                if (!found && acc.length > 512) acc.delete(0, acc.length - 512)
            }

            // Many SmartBoy units stream nm/rb + "startrom" + ROM all at once without
            // waiting for "sd".  "startrom" may arrive in the same USB packet as nm/rb
            // (already in `acc`) or a few milliseconds later in a separate packet.
            // Try for up to 500 ms so that dumpRom() can use Scenario A (no drain,
            // no "sd"), which guarantees ROM bytes starting at offset 0.
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

        if (info.preRomBytes.isNotEmpty()) {
            // ── Scenario A: device already sent "startrom" + ROM alongside nm/rb ──
            // The device does not use a "sd" handshake; it streams everything at once.
            // Write what we already have and read the remainder from USB.
            out.write(info.preRomBytes)
        } else {
            // ── Scenario B: device needs "sd" before it sends "startrom" + ROM ──
            // (Fallback — Scenario A should fire for most devices.)
            // Send "sd" immediately; do NOT drain first.  Draining would discard the
            // very "startrom" response we are about to wait for (the device answers
            // in well under 300 ms).
            port.write("sd".toByteArray(Charsets.US_ASCII), WRITE_TIMEOUT_MS)

            // Wait for the fresh "startrom" marker.
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

            // Any bytes that arrived in the same read as "startrom" but after it
            // are the first ROM bytes — write them before the main read loop.
            val startromEnd = preamble.indexOf("startrom") + "startrom".length
            for (i in startromEnd until preamble.length) {
                if (out.size() >= romSize) break
                out.write(preamble[i].code and 0xFF)
            }
        }

        // Read the rest of the ROM from USB.
        var totalRead = out.size()
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
