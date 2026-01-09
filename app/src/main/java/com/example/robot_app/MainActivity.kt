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
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.robot_app.databinding.ActivityMainBinding

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
    private val scanHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupRecyclerView()
        binding.btnConnect.setOnClickListener {
            if (!isConnected) {
                clearDeviceList()
                handlePermissionsAndConnect()
            } else {
                disconnectFromDevice()
            }
        }
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

    // --- SEKCJA SKANOWANIA ---

    private fun startBleScan() {
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Włącz Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, requiredPermission) != PackageManager.PERMISSION_GRANTED) {
            Log.e("BLE_SCAN", "Próba skanowania bez uprawnienia: $requiredPermission")
            return
        }
        Log.d("BLE_SCAN", "Uprawnienia OK. Startuję skaner.")

        isScanning = true
        binding.tvStatus.text = "Skanowanie..."
        binding.rvDevices.visibility = View.VISIBLE

        scanHandler.postDelayed({
            if (isScanning) {
                Log.d("BLE_SCAN", "Zatrzymuję skanowanie po upływie czasu.")
                stopBleScan()
            }
        }, SCAN_PERIOD)

        bluetoothLeScanner.startScan(scanCallback)
    }

    private fun stopBleScan() {
        if (!isScanning) return
        isScanning = false

        val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, requiredPermission) == PackageManager.PERMISSION_GRANTED) {
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
                // BLUETOOTH_CONNECT is required to get device name.
                // The app does not display it, so we can proceed without the name.
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Log.d("BLE_RESULT", "Found device without CONNECT permission: ${device.address}")
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
            Toast.makeText(this@MainActivity, "Błąd skanowania: $errorCode", Toast.LENGTH_SHORT).show()
        }
    }


    // --- KONIEC SEKCJI SKANOWANIA ---

    private fun connectToDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
            val deviceAddress = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    runOnUiThread {
                        isConnected = true
                        binding.tvStatus.text = "Połączono z: $deviceAddress"
                        binding.tvStatus.setBackgroundColor(getColor(android.R.color.holo_green_light))
                        binding.btnConnect.text = "Rozłącz"
                        Toast.makeText(this@MainActivity, "Połączono z robotem", Toast.LENGTH_SHORT).show()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    runOnUiThread {
                        disconnectFromDevice()
                    }
                }
            } else {
                runOnUiThread {
                    disconnectFromDevice()
                    binding.tvStatus.text = "Błąd połączenia"
                }
            }
        }
    }

    private fun disconnectFromDevice() {
        if (isScanning) {
            stopBleScan()
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return
        }
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        isConnected = false
        binding.tvStatus.text = "Not connected"
        binding.tvStatus.setBackgroundColor(0xFFE0E0E0.toInt())
        binding.btnConnect.text = "Połącz"
        binding.rvDevices.visibility = View.GONE
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
    }
}
