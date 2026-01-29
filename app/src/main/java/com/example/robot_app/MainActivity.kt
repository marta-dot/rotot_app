package com.example.robot_app

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.robot_app.databinding.ActivityMainBinding
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isConnected = false
    private var isScanning = false
    private var isWriteInProgress = false
    private var isDestinationSelected = false
    private var selectedDestination: String? = null

    private val deviceList = mutableListOf<BluetoothDevice>()
    private val deviceAddressSet = mutableSetOf<String>()

    private lateinit var deviceListAdapter: DeviceListAdapter

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val bluetoothLeScanner: BluetoothLeScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var destinationCharacteristic: BluetoothGattCharacteristic? = null
    private var reachCharacteristic: BluetoothGattCharacteristic? = null
    private var robotMessageCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private val scanHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = systemBars.bottom)
            insets
        }

        setupRecyclerView()
        binding.btnConnect.text = "Skanuj ponownie"
        binding.btnConnect.setOnClickListener {
            if (!isConnected) {
                clearDeviceList()
                handlePermissionsAndConnect()
            } else {
                disconnectFromDevice()
            }
        }

        binding.btnSendA.setOnClickListener { handleDestinationSelection("a", it) }
        binding.btnSendB.setOnClickListener { handleDestinationSelection("b", it) }
        binding.btnSendC.setOnClickListener { handleDestinationSelection("c", it) }
        binding.btnSendD.setOnClickListener { handleDestinationSelection("d", it) }

        binding.btnSendReached.setOnClickListener { handleReachConfirmation() }
        binding.btnSendNotReached.setOnClickListener { handleNotReachedConfirmation() }

        handlePermissionsAndConnect()
    }

    private fun handleDestinationSelection(destination: String, clickedButton: View) {
        if (!isDestinationSelected) {
            sendData(destination, destinationCharacteristic)
            this.selectedDestination = destination
            isDestinationSelected = true
            setupUIForRobotState("BUSY")
        }
    }

    private fun handleReachConfirmation() {
        sendData("1", reachCharacteristic)
        setupUIForRobotState("IDLE")
    }

    private fun handleNotReachedConfirmation() {
        sendData("0", reachCharacteristic)
        setupUIForRobotState("IDLE")
    }

    private fun setupRecyclerView() {
        deviceListAdapter = DeviceListAdapter(deviceList) { device ->
            stopBleScan()
            connectToDevice(device)
        }
        binding.rvDevices.apply {
            adapter = deviceListAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }
    }

    private fun handlePermissionsAndConnect() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val allPermissionsGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allPermissionsGranted) {
            startBleScan()
        } else {
            ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startBleScan()
            } else {
                Toast.makeText(this, "Uprawnienia są wymagane.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startBleScan() {
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Włącz Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        isScanning = true
        binding.tvStatus.text = "Skanowanie..."
        binding.rvDevices.visibility = View.VISIBLE

        scanHandler.postDelayed({ if (isScanning) stopBleScan() }, SCAN_PERIOD)
        bluetoothLeScanner.startScan(scanCallback())
    }

    private fun stopBleScan() {
        if (!isScanning) return
        isScanning = false

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothLeScanner.stopScan(scanCallback())
        }

        if (!isConnected) {
            binding.tvStatus.text = "Zakończono skanowanie"
        }
    }

    private fun scanCallback() = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device?.address != null && !deviceAddressSet.contains(device.address)) {
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return

                if (device.name?.startsWith("ESP32_ROBOT") == true) {
                    deviceAddressSet.add(device.address)
                    deviceList.add(device)
                    runOnUiThread {
                        deviceListAdapter.notifyItemInserted(deviceList.size - 1)
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BLE_SCAN", "Błąd skanowania: $errorCode")
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        binding.tvStatus.text = "Łączenie..."
        binding.rvDevices.visibility = View.GONE
        runOnUiThread {
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    runOnUiThread {
                        isConnected = true
                        binding.btnConnect.text = "Rozłącz"
                        binding.tvStatus.text = "Połączono. Szukam usług..."
                    }
                    // KROK 1: Odkryj serwisy (nie bawimy się w MTU na start)
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    runOnUiThread { disconnectFromDevice() }
                }
            } else {
                runOnUiThread { disconnectFromDevice() }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(UUID.fromString(SERVICE_UUID))
                if (service != null) {
                    // KROK 2: Przypisz charakterystyki
                    destinationCharacteristic = service.getCharacteristic(UUID.fromString(CHARACTERISTIC_UUID_DESTINATION_MESSAGE))
                    reachCharacteristic = service.getCharacteristic(UUID.fromString(CHARACTERISTIC_UUID_REACH_MESSAGE))
                    robotMessageCharacteristic = service.getCharacteristic(UUID.fromString(CHARACTERISTIC_UUID_ROBOT_MESSAGE))
                    statusCharacteristic = service.getCharacteristic(UUID.fromString(CHARACTERISTIC_UUID_STATUS))

                    if (robotMessageCharacteristic != null && statusCharacteristic != null) {
                        // KROK 3: Najpierw włącz powiadomienia.
                        // Odczyt statusu zrobimy DOPIERO gdy przyjdzie potwierdzenie zapisu deskryptora (onDescriptorWrite)
                        enableNotification(gatt, robotMessageCharacteristic!!)
                    } else {
                        runOnUiThread { binding.tvStatus.text = "Błąd: Brak wymaganych charakterystyk" }
                    }
                } else {
                    runOnUiThread { binding.tvStatus.text = "Błąd: Nie znaleziono serwisu" }
                }
            }
        }

        // KROK 4: Callback po włączeniu powiadomień
        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE", "Powiadomienia włączone. Teraz czytam status.")

                // KROK 5: Teraz bezpiecznie czytamy status (IDLE/BUSY)
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    statusCharacteristic?.let { char ->
                        gatt?.readCharacteristic(char)
                    }
                }
            }
        }

        // KROK 6: Otrzymano status
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            super.onCharacteristicRead(gatt, characteristic, value, status) // Dla nowszych API
            handleRead(characteristic, value)
        }

        // Obsługa legacy (dla starszych Androidów)
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            super.onCharacteristicRead(gatt, characteristic, status)
            handleRead(characteristic, characteristic.value)
        }

        private fun handleRead(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == statusCharacteristic?.uuid) {
                val robotState = value.toString(Charsets.UTF_8)
                Log.d("BLE", "Odczytano status: $robotState")
                runOnUiThread {
                    setupUIForRobotState(robotState)
                }
            }
        }

        // KROK 7: Otrzymano powiadomienie (Notify)
        // Wersja dla Android 13+ (Tiramisu)
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            super.onCharacteristicChanged(gatt, characteristic, value)
            handleNotification(characteristic, value)
        }

        // Wersja dla starszych Androidów (Bardzo ważne!)
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicChanged(gatt, characteristic)
            handleNotification(characteristic, characteristic.value)
        }

        private fun handleNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == robotMessageCharacteristic?.uuid) {
                val message = value.toString(Charsets.UTF_8)
                Log.d("BLE", "Przyszło powiadomienie: $message")
                runOnUiThread {
                    binding.tvRobotMessage.text = message
                    binding.tvRobotMessage.visibility = View.VISIBLE
                    // Jeśli dostaliśmy jakąkolwiek wiadomość (np. "Pojazd dotarł"), odblokuj potwierdzenie
                    // ALE TYLKO jeśli cel został wybrany (nie jesteśmy w stanie IDLE)
                    if (message.startsWith("Pojazd dotarł")) {
                        binding.btnSendReached.isEnabled = true
                        binding.btnSendNotReached.isEnabled = true
                    }
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            isWriteInProgress = false
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE", "Wysłano pomyślnie")
            } else {
                runOnUiThread { Toast.makeText(this@MainActivity, "Błąd zapisu BLE", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return

        gatt.setCharacteristicNotification(characteristic, true)

        // Standardowy UUID deskryptora Client Characteristic Configuration
        val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
        if (descriptor != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    private fun setupUIForRobotState(state: String) {
        binding.rvDevices.visibility = View.GONE
        binding.destinationButtonsLayout.visibility = View.VISIBLE
        binding.mapImage.visibility = View.VISIBLE
        binding.tvRobotMessage.visibility = View.VISIBLE
        binding.tvSelectedDestination.visibility = View.VISIBLE
        binding.reachButtonLayout.visibility = View.VISIBLE

        when (state.trim()) {
            "IDLE" -> {
                binding.tvStatus.text = "Gotowy do pracy"
                binding.tvSelectedDestination.text = "Wybierz punkt"
                binding.tvRobotMessage.text = ""
                binding.btnSendReached.isEnabled = false
                binding.btnSendNotReached.isEnabled = false
                binding.btnSendA.isEnabled = true
                binding.btnSendB.isEnabled = true
                binding.btnSendC.isEnabled = true
                binding.btnSendD.isEnabled = true
                isDestinationSelected = false
                selectedDestination = null
            }
            "BUSY" -> {
                binding.tvStatus.text = "Robot w trakcie misji..."
                if (selectedDestination != null) {
                    binding.tvSelectedDestination.text = "W drodze do punktu ${selectedDestination?.uppercase()}"
                } else {
                    binding.tvSelectedDestination.text = "Misja w toku"
                }
                binding.tvRobotMessage.text = ""
                binding.btnSendReached.isEnabled = false
                binding.btnSendNotReached.isEnabled = false
                binding.btnSendA.isEnabled = false
                binding.btnSendB.isEnabled = false
                binding.btnSendC.isEnabled = false
                binding.btnSendD.isEnabled = false
                isDestinationSelected = true
            }
        }
    }

    private fun setCharacteristicNotification(characteristic: BluetoothGattCharacteristic, enabled: Boolean) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        bluetoothGatt?.setCharacteristicNotification(characteristic, enabled)
        val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
        descriptor.value = if (enabled) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        bluetoothGatt?.writeDescriptor(descriptor)
    }

    private fun sendData(data: String, characteristic: BluetoothGattCharacteristic?) {
        if (isWriteInProgress) {
            Toast.makeText(this, "Poczekaj na zakończenie poprzedniej operacji", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isConnected || bluetoothGatt == null || characteristic == null) {
            Toast.makeText(this, "Urządzenie niegotowe", Toast.LENGTH_SHORT).show()
            return
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return

        isWriteInProgress = true
        characteristic.let { char ->
            char.value = data.toByteArray()
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            bluetoothGatt?.writeCharacteristic(char)
        }
    }

    private fun disconnectFromDevice() {
        if (isScanning) stopBleScan()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        destinationCharacteristic = null
        reachCharacteristic = null
        robotMessageCharacteristic = null
        statusCharacteristic = null
        isWriteInProgress = false
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        isConnected = false
        runOnUiThread {
            binding.tvStatus.text = "Nie połączono"
            binding.btnConnect.text = "Skanuj ponownie"
            binding.rvDevices.visibility = View.VISIBLE
            binding.destinationButtonsLayout.visibility = View.GONE
            binding.reachButtonLayout.visibility = View.GONE
            binding.mapImage.visibility = View.GONE
            binding.tvSelectedDestination.visibility = View.GONE
            binding.tvRobotMessage.visibility = View.GONE
        }
    }

    private fun clearDeviceList() {
        deviceList.clear()
        deviceAddressSet.clear()
        deviceListAdapter.notifyDataSetChanged()
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectFromDevice()
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 101
        private const val SCAN_PERIOD: Long = 20000
        private const val SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
        private const val CHARACTERISTIC_UUID_DESTINATION_MESSAGE = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
        private const val CHARACTERISTIC_UUID_REACH_MESSAGE = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
        private const val CHARACTERISTIC_UUID_ROBOT_MESSAGE = "6E400004-B5A3-F393-E0A9-E50E24DCCA9E"
        private const val CHARACTERISTIC_UUID_STATUS = "6E400005-B5A3-F393-E0A9-E50E24DCCA9E"
    }
}
