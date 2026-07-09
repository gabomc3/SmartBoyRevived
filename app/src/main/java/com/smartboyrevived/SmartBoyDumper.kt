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
            var romDataStartInAcc = -1   // position in acc right after rb<BANKS> digits

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
                            romDataStartInAcc = bankEnd
                            found = true
                        }
                    }
                    from = nmIdx + 1
                }

                if (!found && s.contains("nr")) { onNoCart() }
                if (!found && acc.length > 512) {
                    acc.delete(0, acc.length - 512)
                    romDataStartInAcc = -1
                }
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

            val finalS = acc.toString()
            val srIdx = finalS.indexOf("startrom")

            val romDataStart = when {
                srIdx >= 0 -> srIdx + "startrom".length
                romDataStartInAcc in 0 until finalS.length -> romDataStartInAcc
                else -> finalS.length
            }

            val preRomBytes = ByteArray(maxOf(0, finalS.length - romDataStart)) { i ->
                (finalS[romDataStart + i].code and 0xFF).toByte()
            }

            Log.d(TAG, "readCartridgeInfo: name=$romName banks=$numBanks " +
                "startromFound=${srIdx >= 0} romDataStart=$romDataStart " +
                "preRomBytes=${preRomBytes.size} accLen=${finalS.length}")

            CartridgeInfo(romName!!, numBanks, preRomBytes)
        }

    suspend fun dumpRom(
        info: CartridgeInfo,
        onProgress: suspend (Int) -> Unit
    ): ByteArray = withContext(Dispatchers.IO) {
        val romSize = info.romSizeBytes
        val out = ByteArrayOutputStream(romSize)

        Log.d(TAG, "dumpRom: romSize=$romSize (${romSize / 1024}KB) preRomBytes=${info.preRomBytes.size}")

        val streamBuf = ByteArrayOutputStream()
        if (info.preRomBytes.isNotEmpty()) {
            streamBuf.write(info.preRomBytes)
            Log.d(TAG, "dumpRom: seeded ${info.preRomBytes.size} preRomBytes")
        }

        port.write("sd".toByteArray(Charsets.US_ASCII), WRITE_TIMEOUT_MS)
        Log.d(TAG, "dumpRom: sent 'sd'")

        val logo = byteArrayOf(
            0xCE.toByte(), 0xED.toByte(), 0x66, 0x66,
            0xCC.toByte(), 0x0D, 0x00, 0x0B
        )
        val LOGO_OFFSET = 0x104

        val tmp = ByteArray(256)
        val deadline = System.currentTimeMillis() + 30_000L
        var romStart = -1
        var scanFrom = 0
        var loggedAt = 0

        while (romStart < 0) {
            if (System.currentTimeMillis() > deadline) {
                val buf = streamBuf.toByteArray()
                val first32 = buf.take(32).joinToString(" ") { "%02X".format(it) }
                val last32  = buf.takeLast(32).joinToString(" ") { "%02X".format(it) }
                Log.e(TAG, "dumpRom TIMEOUT: ${buf.size} bytes received")
                Log.e(TAG, "dumpRom: first 32: $first32")
                Log.e(TAG, "dumpRom: last  32: $last32")

                val sMark = "startrom".toByteArray(Charsets.US_ASCII)
                var srPos = 0; var srCount = 0
                while (srPos <= buf.size - sMark.size) {
                    if (sMark.indices.all { buf[srPos + it] == sMark[it] }) {
                        srCount++
                        val doff = srPos + sMark.size
                        if (buf.size >= doff + 0x108) {
                            Log.e(TAG, "dumpRom: startrom[$srCount] at $srPos -> " +
                                "bytes@0x104: ${"%02X %02X %02X %02X".format(
                                    buf[doff + 0x104].toInt() and 0xFF,
                                    buf[doff + 0x105].toInt() and 0xFF,
                                    buf[doff + 0x106].toInt() and 0xFF,
                                    buf[doff + 0x107].toInt() and 0xFF)} " +
                                "(expected CE ED 66 66)")
                        }
                    }
                    srPos++
                }
                if (srCount == 0) Log.e(TAG, "dumpRom: NO 'startrom' in stream")
                Log.e(TAG, "dumpRom: startrom count=$srCount")

                error("TIMEOUT 30s: ${buf.size} bytes, logo no encontrado.\n" +
                      "startrom en stream: $srCount\n" +
                      "Primeros 32 bytes:\n$first32")
            }

            val n = port.read(tmp, 2000)
            if (n > 0) streamBuf.write(tmp, 0, n)

            val buf = streamBuf.toByteArray()
            if (buf.size - loggedAt >= 4096) {
                Log.d(TAG, "dumpRom: ${buf.size} bytes, scanFrom=$scanFrom")
                loggedAt = buf.size
            }

            val limit = buf.size - logo.size
            while (scanFrom <= limit) {
                if (logo.indices.all { buf[scanFrom + it] == logo[it] }) {
                    val candidate = scanFrom - LOGO_OFFSET
                    if (candidate >= 0) {
                        romStart = candidate
                        Log.d(TAG, "dumpRom: LOGO at buf[$scanFrom] -> romStart=$romStart (streamSize=${buf.size})")
                        break
                    }
                    Log.d(TAG, "dumpRom: logo at buf[$scanFrom] but candidate=$candidate < 0, skipping")
                }
                scanFrom++
            }
        }

        val buf = streamBuf.toByteArray()
        val preloaded = minOf(buf.size - romStart, romSize)
        out.write(buf, romStart, preloaded)
        var totalRead = out.size()
        Log.d(TAG, "dumpRom: preloaded=$preloaded totalRead=$totalRead")

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
        Log.d(TAG, "dumpRom: complete totalRead=$totalRead")
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
