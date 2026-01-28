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

        handlePermissionsAndConnect()
    }

    private fun handleDestinationSelection(destination: String, clickedButton: View) {
        if (!isDestinationSelected) {
            sendData(destination, destinationCharacteristic)
            this.selectedDestination = destination
            binding.tvSelectedDestination.text = "Wybrano punkt ${destination.uppercase()}"

            updateDestinationButtonsState(clickedButton)
            isDestinationSelected = true

        }
    }



    private fun updateDestinationButtonsState(selectedButton: View) {
        // Stwórz listę wszystkich przycisków docelowych
        val allButtons = listOf(binding.btnSendA, binding.btnSendB, binding.btnSendC, binding.btnSendD)

        for (button in allButtons) {
            button.isEnabled = (button == selectedButton)
        }
    }



    private fun handleReachConfirmation() {
        sendData("1", reachCharacteristic)
        binding.tvSelectedDestination.text = "Wybierz następny punkt"
        binding.tvRobotMessage.visibility = View.VISIBLE
        binding.tvRobotMessage.text = ""
        binding.btnSendReached.isEnabled = false

        binding.btnSendA.isEnabled = true
        binding.btnSendB.isEnabled = true
        binding.btnSendC.isEnabled = true
        binding.btnSendD.isEnabled = true

        isDestinationSelected = false
        selectedDestination = null
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
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return
                }
                gatt.requestMtu(512)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt?.getService(UUID.fromString(SERVICE_UUID))
                if (service != null) {
                    destinationCharacteristic = service.getCharacteristic(UUID.fromString(CHARACTERISTIC_UUID_DESTINATION_MESSAGE))
                    reachCharacteristic = service.getCharacteristic(UUID.fromString(CHARACTERISTIC_UUID_REACH_MESSAGE))
                    robotMessageCharacteristic = service.getCharacteristic(UUID.fromString(CHARACTERISTIC_UUID_ROBOT_MESSAGE))

                    if (destinationCharacteristic != null && reachCharacteristic != null && robotMessageCharacteristic != null) {
                        setCharacteristicNotification(robotMessageCharacteristic!!, true)
                        runOnUiThread {
                            binding.tvStatus.text = "Gotowy do pracy."
                            binding.rvDevices.visibility = View.GONE
                            binding.destinationButtonsLayout.visibility = View.VISIBLE
                            binding.mapImage.visibility = View.VISIBLE
                            binding.tvRobotMessage.text = ""
                            binding.tvRobotMessage.visibility = View.VISIBLE
                            binding.tvSelectedDestination.text = "Wybierz punkt"
                            binding.tvSelectedDestination.visibility = View.VISIBLE
                            binding.reachButtonLayout.visibility = View.VISIBLE
                            binding.btnSendReached.isEnabled = false
                        }
                    } else {
                        runOnUiThread { binding.tvStatus.text = "Błąd: Nie znaleziono wszystkich charakterystyk" }
                    }
                } else {
                    runOnUiThread { binding.tvStatus.text = "Błąd: Nie znaleziono serwisu" }
                }
            }
        }


        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            isWriteInProgress = false
            runOnUiThread {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Toast.makeText(this@MainActivity, "Błąd wysyłania", Toast.LENGTH_SHORT).show()
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val message = value.toString(Charsets.UTF_8)
            runOnUiThread {
                binding.tvRobotMessage.text = message
                binding.tvRobotMessage.visibility = View.VISIBLE
                binding.btnSendReached.isEnabled = true
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
        isWriteInProgress = false
        isDestinationSelected = false
        selectedDestination = null
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
    }
}
