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
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.smartboyrevived.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var usbManager: UsbManager

    private var serialPort: UsbSerialPort? = null
    private var dumper: SmartBoyDumper? = null
    private var cartInfo: SmartBoyDumper.CartridgeInfo? = null
    private var lastRomUri: Uri? = null

    companion object {
        private const val ACTION_USB_PERMISSION = "com.smartboyrevived.USB_PERMISSION"
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

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        // Register USB events
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

        // If launched from USB attach intent, handle it
        handleIntent(intent)

        // Also scan already-connected devices
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
            // Assert DTR + RTS so the SmartBoy (MattairTech CDC ACM) starts transmitting
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
                setStatus("❌ Error volcando ROM: ${e.message}")
                binding.layoutProgress.visibility = View.GONE
                binding.btnDump.isEnabled = true
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
    // Launch My OldBoy! with the ROM
    // -------------------------------------------------------------------------
    private fun openInMyOldBoy() {
        val uri = lastRomUri ?: return
        // Correct package names: paid = gbc, free = gbcfree (NOT gbcfull)
        val packages = listOf("com.fastemulator.gbc", "com.fastemulator.gbcfree")
        for (pkg in packages) {
            for (mime in listOf("application/octet-stream", "*/*")) {
                try {
                    val i = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mime)
                        setPackage(pkg)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(i)
                    return
                } catch (_: Exception) {}
            }
        }
        // Fallback chooser
        try {
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(i, "Abrir ROM con..."))
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------
    private fun setStatus(text: String) {
        runOnUiThread { binding.tvStatus.text = text }
    }

    private fun showCartInfo(info: SmartBoyDumper.CartridgeInfo) {
        runOnUiThread {
            binding.layoutCartInfo.visibility = Vie