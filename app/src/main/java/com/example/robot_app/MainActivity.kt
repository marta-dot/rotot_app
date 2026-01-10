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
    private var bleCharacteristic: BluetoothGattCharacteristic? = null
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
        binding.btnConnect.setOnClickListener {
            if (!isConnected) {
                clearDeviceList()
                handlePermissionsAndConnect()
            } else {
                disconnectFromDevice()
            }
        }

        binding.btnSend1.setOnClickListener { sendBleData("1") }
        binding.btnSend0.setOnClickListener { sendBleData("0") }
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
        Log.d("BLE_SCAN", "Uprawnienia OK. Startuję skaner.")

        isScanning = true
        binding.tvStatus.text = "Skanowanie..."
        binding.rvDevices.visibility = View.VISIBLE

        scanHandler.postDelayed({
            if (isScanning) {
                stopBleScan()
            }
        }, SCAN_PERIOD)

        bluetoothLeScanner.startScan(scanCallback)
    }

    private fun stopBleScan() {
        if (!isScanning) return
        isScanning = false

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothLeScanner.stopScan(scanCallback)
            Log.d("BLE_SCAN", "Zatrzymano skanowanie.")
        }

        if (!isConnected) {
            binding.tvStatus.text = "Zakończono skanowanie"
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device?.address != null && !deviceAddressSet.contains(device.address)) {
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return
                }
                deviceAddressSet.add(device.address)
                deviceList.add(device)
                runOnUiThread {
                    deviceListAdapter.notifyItemInserted(deviceList.size - 1)
                }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            Log.e("BLE_SCAN", "Błąd skanowania: $errorCode")
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        binding.tvStatus.text = "Łączenie..."
        binding.rvDevices.visibility = View.GONE
        runOnUiThread {
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d("BLE_GATT", "Połączono. Odkrywam usługi...")
                    gatt.discoverServices()
                    runOnUiThread {
                        isConnected = true
                        binding.btnConnect.text = "Rozłącz"
                        binding.tvStatus.text = "Połączono. Wyszukuję usługi..."
                    }
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
                    bleCharacteristic = service.getCharacteristic(UUID.fromString(CHARACTERISTIC_UUID_RX))
                    if (bleCharacteristic != null) {
                        Log.d("BLE_GATT", "Znaleziono charakterystykę, można wysyłać dane.")
                        runOnUiThread {
                            binding.tvStatus.text = "Gotowy do pracy."
                            binding.buttonsLayout.visibility = View.VISIBLE
                        }
                    } else {
                        Log.e("BLE_GATT", "Nie znaleziono charakterystyki")
                        runOnUiThread { binding.tvStatus.text = "Błąd: Nie znaleziono charakterystyki" }
                    }
                } else {
                    Log.e("BLE_GATT", "Nie znaleziono serwisu")
                    runOnUiThread { binding.tvStatus.text = "Błąd: Nie znaleziono serwisu" }
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            val data = characteristic?.value?.toString(Charsets.UTF_8) ?: ""
            runOnUiThread {
                binding.btnSend1.isEnabled = true
                binding.btnSend0.isEnabled = true
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d("BLE_WRITE", "Wysłano pomyślnie: $data")
                    Toast.makeText(this@MainActivity, "Wysłano: $data", Toast.LENGTH_SHORT).show()
                } else {
                    Log.e("BLE_WRITE", "Błąd zapisu: $status")
                    Toast.makeText(this@MainActivity, "Błąd wysyłania", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun sendBleData(data: String) {
        if (!isConnected || bluetoothGatt == null || bleCharacteristic == null) {
            Toast.makeText(this, "Brak połączenia lub urządzenie niegotowe", Toast.LENGTH_SHORT).show()
            return
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        bleCharacteristic?.let { char ->
            char.value = data.toByteArray()
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            
            runOnUiThread { 
                binding.btnSend1.isEnabled = false
                binding.btnSend0.isEnabled = false
            }
            bluetoothGatt?.writeCharacteristic(char)
        }
    }


    private fun disconnectFromDevice() {
        Log.d("DISCONNECT", "Rozłączanie z urządzeniem.")
        if (isScanning) {
            stopBleScan()
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        bleCharacteristic = null
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        isConnected = false
        runOnUiThread {
            binding.tvStatus.text = "Not connected"
            binding.btnConnect.text = "Połącz"
            binding.rvDevices.visibility = View.GONE
            binding.buttonsLayout.visibility = View.GONE
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
        private const val CHARACTERISTIC_UUID_RX = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
    }
}
