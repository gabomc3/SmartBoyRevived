package com.smartboyrevived

import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class SmartBoyDumper(private val port: UsbSerialPort) {

    companion object {
        private const val TAG = "SmartBoy"
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

                if (!found && s.contains("nr")) { onNoCart() }
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
            Log.d(TAG, "readCartridgeInfo: name=$romName banks=$numBanks " +
                "startromFound=${srIdx >= 0} accLen=${acc.length}")

            val preRomBytes = if (srIdx >= 0) {
                val dataStart = srIdx + "startrom".length
                ByteArray(maxOf(0, s.length - dataStart)) { i ->
                    (s[dataStart + i].code and 0xFF).toByte()
                }
            } else ByteArray(0)

            Log.d(TAG, "readCartridgeInfo: preRomBytes=${preRomBytes.size}")
            CartridgeInfo(romName!!, numBanks, preRomBytes)
        }

    suspend fun dumpRom(
        info: CartridgeInfo,
        onProgress: suspend (Int) -> Unit
    ): ByteArray = withContext(Dispatchers.IO) {
        val romSize = info.romSizeBytes
        val out = ByteArrayOutputStream(romSize)

        Log.d(TAG, "dumpRom: start romSize=$romSize (${romSize / 1024}KB)")

        // Send "sd" to signal readiness.
        port.write("sd".toByteArray(Charsets.US_ASCII), WRITE_TIMEOUT_MS)
        Log.d(TAG, "dumpRom: sent 'sd'")

        // ── Strategy: locate ROM byte 0 via the Nintendo logo in the raw byte stream ──
        //
        // The 8-byte logo (CE ED 66 66 CC 0D 00 0B) is at ROM offset 0x104.
        // Therefore: romStart = logoPositionInStream - 0x104.
        //
        // This does NOT depend on a "startrom" text marker. It handles:
        //   • Device sends ROM directly after "sd" (no prefix) → logo at stream[0x104]
        //   • Device prefixes with nm/rb or other text (N bytes) → logo at stream[N+0x104]
        //   • Device is mid-cycle when "sd" arrives → we wait for the next full cycle
        //
        // Timeout 120s covers one full 1MB cycle at 115200 baud (~91s) + margin.
        val logo = byteArrayOf(
            0xCE.toByte(), 0xED.toByte(), 0x66, 0x66,
            0xCC.toByte(), 0x0D, 0x00, 0x0B
        )
        val LOGO_OFFSET = 0x104

        val streamBuf = ByteArrayOutputStream()
        val tmp = ByteArray(256)
        val deadline = System.currentTimeMillis() + 120_000L
        var romStart = -1
        var scanFrom = LOGO_OFFSET   // earliest pos in buf where logo can sit
        var loggedAt = 0             // last size we printed a progress log

        while (romStart < 0) {
            if (System.currentTimeMillis() > deadline) {
                val buf = streamBuf.toByteArray()
                // ── TIMEOUT DIAGNOSTICS ──
                Log.e(TAG, "dumpRom TIMEOUT: received ${buf.size} bytes total")
                Log.e(TAG, "dumpRom: first 32 bytes: ${buf.take(32).joinToString(" ") { "%02X".format(it) }}")
                Log.e(TAG, "dumpRom: last  32 bytes: ${buf.takeLast(32).joinToString(" ") { "%02X".format(it) }}")

                // Scan full buffer for "startrom" text and log bytes at 0x104
                val sMark = "startrom".toByteArray(Charsets.US_ASCII)
                var srPos = 0; var srCount = 0
                while (srPos <= buf.size - sMark.size) {
                    if (sMark.indices.all { buf[srPos + it] == sMark[it] }) {
                        srCount++
                        val doff = srPos + sMark.size
                        if (buf.size >= doff + 0x108) {
                            Log.e(TAG, "dumpRom: startrom[$srCount] at buf[$srPos] " +
                                "→ bytes@0x104: ${"%02X %02X %02X %02X".format(
                                    buf[doff + 0x104].toInt() and 0xFF,
                                    buf[doff + 0x105].toInt() and 0xFF,
                                    buf[doff + 0x106].toInt() and 0xFF,
                                    buf[doff + 0x107].toInt() and 0xFF
                                )} (expected CE ED 66 66)")
                        } else {
                            Log.e(TAG, "dumpRom: startrom[$srCount] at buf[$srPos] " +
                                "— not enough bytes after it to read 0x104")
                        }
                    }
                    srPos++
                }
                if (srCount == 0) Log.e(TAG, "dumpRom: NO 'startrom' found in stream at all")
                Log.e(TAG, "dumpRom: 'startrom' count=$srCount")

                error("Timeout: ${buf.size} bytes, logo not found, startrom=$srCount. See logcat tag=SmartBoy")
            }

            val n = port.read(tmp, 2000)
            if (n > 0) streamBuf.write(tmp, 0, n)

            val buf = streamBuf.toByteArray()

            // Periodic progress log
            if (buf.size - loggedAt >= 4096) {
                Log.d(TAG, "dumpRom: ${buf.size} bytes received, scanFrom=$scanFrom")
                loggedAt = buf.size
            }

            // Scan new portion of buf for the logo
            val limit = buf.size - logo.size
            while (scanFrom <= limit) {
                if (logo.indices.all { buf[scanFrom + it] == logo[it] }) {
                    romStart = scanFrom - LOGO_OFFSET
                    Log.d(TAG, "dumpRom: LOGO FOUND at buf[$scanFrom] → romStart=$romStart streamSize=${buf.size}")
                    break
                }
                scanFrom++
            }
        }

        // ── Write ROM bytes already in buffer, then stream the rest ──
        val buf = streamBuf.toByteArray()
        val preloaded = minOf(buf.size - romStart, romSize)
        out.write(buf, romStart, preloaded)
        var totalRead = out.size()
        Log.d(TAG, "dumpRom: preloaded $preloaded bytes, totalRead=$totalRead")

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
        Log.d(TAG, "dumpRom: complete. totalRead=$totalRead")
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
