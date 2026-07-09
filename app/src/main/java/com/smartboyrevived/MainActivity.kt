package com.smartboyrevived

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StrictMode
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.smartboyrevived.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var usbManager: UsbManager

    private var serialPort: UsbSerialPort? = null
    private var dumper: SmartBoyDumper? = null
    private var cartInfo: SmartBoyDumper.CartridgeInfo? = null
    private var lastRomUri: Uri? = null
    private var lastRomFile: File? = null
    private var lastRomFilePath: String? = null

    companion object {
        private const val ACTION_USB_PERMISSION = "com.smartboyrevived.USB_PERMISSION"
        private const val AUTHORITY = "com.smartboyrevived.fileprovider"
    }

    // -------------------------------------------------------------------------
    // USB permission broadcast receiver
    // -------------------------------------------------------------------------
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        connectToDevice(device)
                    } else {
                        setStatus("⛔ Permiso USB denegado")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    device?.let { checkAndConnect(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (device?.vendorId == SmartBoyDumper.VENDOR_ID) {
                        closeConnection()
                        setStatus("⏳ SmartBoy desconectado. Esperando...")
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Show build version in the badge (top-right corner of the title)
        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        binding.btnDump.setOnClickListener { startDump() }
        binding.btnPlay.setOnClickListener { openInMyOldBoy() }

        handleIntent(intent)
        scanForSmartBoy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        closeConnection()
    }

    // -------------------------------------------------------------------------
    // USB detection
    // -------------------------------------------------------------------------
    private fun handleIntent(intent: Intent) {
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            device?.let { checkAndConnect(it) }
        }
    }

    private fun scanForSmartBoy() {
        val device = usbManager.deviceList.values.find {
            it.vendorId == SmartBoyDumper.VENDOR_ID && it.productId == SmartBoyDumper.PRODUCT_ID
        }
        device?.let { checkAndConnect(it) }
    }

    private fun checkAndConnect(device: UsbDevice) {
        if (device.vendorId != SmartBoyDumper.VENDOR_ID) return

        if (usbManager.hasPermission(device)) {
            connectToDevice(device)
        } else {
            val permIntent = PendingIntent.getBroadcast(
                this, 0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, permIntent)
            setStatus("⏳ Solicitando permiso USB...")
        }
    }

    // -------------------------------------------------------------------------
    // Serial connection
    // -------------------------------------------------------------------------
    private fun connectToDevice(device: UsbDevice) {
        try {
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            val driver = drivers.firstOrNull { it.device == device }
                ?: run {
                    setStatus("⚠️ Driver USB no encontrado (CDC ACM)")
                    return
                }

            val connection = usbManager.openDevice(driver.device)
                ?: run {
                    setStatus("⚠️ No se pudo abrir el dispositivo USB")
                    return
                }

            val port = driver.ports[0]
            port.open(connection)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            port.rts = true

            serialPort = port
            dumper = SmartBoyDumper(port)

            setStatus("✅ SmartBoy conectado")
            readCartridgeInfo()

        } catch (e: Exception) {
            setStatus("❌ Error: ${e.message}")
        }
    }

    private fun closeConnection() {
        try { serialPort?.close() } catch (_: Exception) {}
        serialPort = null
        dumper = null
        cartInfo = null
        binding.btnDump.isEnabled = false
        binding.layoutCartInfo.visibility = View.GONE
        binding.btnPlay.visibility = View.GONE
    }

    // -------------------------------------------------------------------------
    // Read cartridge info in background
    // -------------------------------------------------------------------------
    private fun readCartridgeInfo() {
        val d = dumper ?: return
        lifecycleScope.launch {
            try {
                setStatus("🔍 Leyendo cartucho...")
                val info = d.readCartridgeInfo(
                    onNoCart = {
                        runOnUiThread { setStatus("📭 Inserta un cartucho en el SmartBoy") }
                    }
                )
                cartInfo = info
                showCartInfo(info)
                setStatus("✅ Cartucho listo")
                binding.btnDump.isEnabled = true
            } catch (e: Exception) {
                setStatus("❌ Error leyendo cartucho: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Dump ROM
    // -------------------------------------------------------------------------
    private fun startDump() {
        val d = dumper ?: return
        val info = cartInfo ?: return

        binding.btnDump.isEnabled = false
        binding.btnPlay.visibility = View.GONE
        binding.layoutProgress.visibility = View.VISIBLE
        binding.progressBar.progress = 0

        lifecycleScope.launch {
            try {
                setStatus("📥 Volcando ROM...")

                val romData = d.dumpRom(info) { progress ->
                    binding.progressBar.progress = progress
                    binding.tvProgress.text = "Volcando ROM: $progress%"
                }

                val filename = d.suggestFilename(info, romData)
                val uri = saveRomToDownloads(romData, filename)

                if (uri != null) {
                    lastRomUri = uri

                    val romCacheDir = File(cacheDir, "SmartBoyROMs").also { it.mkdirs() }
                    val cacheRomFile = File(romCacheDir, filename)
                    withContext(Dispatchers.IO) {
                        lastRomFilePath = try {
                            contentResolver.query(
                                uri,
                                arrayOf(MediaStore.MediaColumns.DATA),
                                null, null, null
                            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                        } catch (_: Exception) { null }

                        try {
                            contentResolver.openInputStream(uri)?.use { inp ->
                                cacheRomFile.outputStream().use { out -> inp.copyTo(out) }
                            }
                        } catch (_: Exception) {}
                    }
                    lastRomFile = cacheRomFile

                    binding.layoutProgress.visibility = View.GONE
                    binding.btnPlay.visibility = View.VISIBLE
                    binding.btnDump.isEnabled = true
                    setStatus("✅ ROM guardado: $filename")
                    Toast.makeText(this@MainActivity, "ROM guardado en Descargas", Toast.LENGTH_SHORT).show()
                } else {
                    setStatus("❌ Error guardando ROM")
                    binding.btnDump.isEnabled = true
                }

            } catch (e: Exception) {
                binding.layoutProgress.visibility = View.GONE
                binding.btnDump.isEnabled = true
                val msg = e.message ?: "Error desconocido"
                setStatus("❌ ${msg.lines().firstOrNull() ?: msg}")
                // Show full diagnostic in a dialog so the user can read/copy it
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("❌ Error volcando ROM")
                    .setMessage(msg)
                    .setPositiveButton("Cerrar", null)
                    .show()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Save ROM to Downloads folder (Scoped Storage - Android 10+)
    // -------------------------------------------------------------------------
    private suspend fun saveRomToDownloads(romData: ByteArray, filename: String): Uri? =
        withContext(Dispatchers.IO) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/SmartBoyROMs")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val resolver = contentResolver
            val uri = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: return@withContext null

            try {
                resolver.openOutputStream(uri)?.use { it.write(romData) }
                contentValues.clear()
                contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
                uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                null
            }
        }

    // -------------------------------------------------------------------------
    // Launch My OldBoy! — shows diagnostic dialog first
    // -------------------------------------------------------------------------
    @Suppress("DEPRECATION")
    private fun openInMyOldBoy() {
        val mediaUri = lastRomUri ?: return
        val packages = listOf("com.fastemulator.gbc", "com.fastemulator.gbcfree")

        val filePath = lastRomFilePath ?: lastRomFile?.name?.let {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "SmartBoyROMs/$it"
            ).absolutePath
        }

        lifecycleScope.launch {
            val diagMsg = withContext(Dispatchers.IO) {
                if (filePath == null) return@withContext "⚠️ No se encontró ruta del archivo."

                val file = File(filePath)
                val size = file.length()
                val bytes = try { file.readBytes() } catch (e: Exception) {
                    return@withContext "Ruta: $filePath\n\n❌ No se pudo leer:\n${e.message}"
                }

                val hex0x000 = bytes.take(8).joinToString(" ") { "%02X".format(it) }
                val hex0x100 = if (bytes.size > 0x107)
                    bytes.drop(0x100).take(8).joinToString(" ") { "%02X".format(it) }
                else "N/A"
                val hex0x104 = if (bytes.size > 0x10B)
                    bytes.drop(0x104).take(8).joinToString(" ") { "%02X".format(it) }
                else "N/A"

                val logoSig = byteArrayOf(0xCE.toByte(), 0xED.toByte(), 0x66.toByte(), 0x66.toByte())
                val logoAt = (0 until bytes.size - 4).firstOrNull { i ->
                    logoSig.indices.all { j -> bytes[i + j] == logoSig[j] }
                } ?: -1

                val logoStatus = when {
                    logoAt == 0x104 -> "✅ en 0x104 (correcto)"
                    logoAt > 0x104  -> "en 0x${logoAt.toString(16).uppercase()} (+${logoAt - 0x104} bytes)"
                    logoAt in 1 until 0x104 -> "en 0x${logoAt.toString(16).uppercase()} (faltan ${0x104 - logoAt} bytes)"
                    else -> "❌ no encontrado"
                }

                val isPow2 = size > 0 && (size and (size - 1)) == 0L

                "Ruta: $filePath\n\n" +
                "Tamaño: $size B (${size / 1024} KB)\n" +
                "Potencia de 2: ${if (isPow2) "✅" else "❌"}\n" +
                "Logo Nintendo: $logoStatus\n\n" +
                "0x000: $hex0x000\n" +
                "0x100: $hex0x100\n" +
                "0x104: $hex0x104 ← logo esperado: CE ED 66 66"
            }

            AlertDialog.Builder(this@MainActivity)
                .setTitle("📁 Diagnóstico ROM")
                .setMessage(diagMsg)
                .setPositiveButton("Abrir My OldBoy!") { _, _ ->
                    doLaunchMyOldBoy(filePath, mediaUri, packages)
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    @Suppress("DEPRECATION")
    private fun doLaunchMyOldBoy(filePath: String?, mediaUri: Uri, packages: List<String>) {
        val cacheFile = lastRomFile
        if (cacheFile?.exists() == true) {
            val fpUri: Uri? = try {
                FileProvider.getUriForFile(this, AUTHORITY, cacheFile)
            } catch (_: Exception) { null }

            if (fpUri != null) {
                for (pkg in packages) {
                    for (mime in listOf("application/octet-stream", "*/*")) {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(fpUri, mime)
                                setPackage(pkg)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                            return
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        for (pkg in packages) {
            for (mime in listOf("application/octet-stream", "*/*")) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(mediaUri, mime)
                        setPackage(pkg)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    return
                } catch (_: Exception) {}
            }
        }

        if (filePath != null) {
            val fileUri = Uri.fromFile(File(filePath))
            val savedPolicy = StrictMode.getVmPolicy()
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
            try {
                for (pkg in packages) {
                    for (mime in listOf("application/octet-stream", "*/*")) {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(fileUri, mime)
                                setPackage(pkg)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                            return
                        } catch (_: Exception) {}
                    }
                }
                try {
                    startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(fileUri, "*/*")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }, "Abrir ROM con..."
                    ))
                    return
                } catch (_: Exception) {}
            } finally {
                StrictMode.setVmPolicy(savedPolicy)
            }
        }

        Toast.makeText(this, "My OldBoy! no encontrado", Toast.LENGTH_LONG).show()
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------
    private fun showCartInfo(info: SmartBoyDumper.CartridgeInfo) {
        runOnUiThread {
            binding.tvRomName.text = info.name
            binding.tvRomSize.text = "${info.romSizeKb} KB (${info.numBanks} bancos)"
            binding.layoutCartInfo.visibility = View.VISIBLE
        }
    }

    private fun setStatus(msg: String) {
        runOnUiThread {
            binding.tvStatus.text = msg
        }
    }
}
