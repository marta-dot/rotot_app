package com.example.robot_app

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * A simple [Fragment] subclass as the second destination in the navigation.
 */
class DeviceListAdapter(
    private val devices: List<BluetoothDevice>,
    private val onDeviceClicked: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.DeviceViewHolder>() {

    class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val deviceName: TextView = view.findViewById(R.id.tvDeviceName)
        val deviceAddress: TextView = view.findViewById(R.id.tvDeviceAddress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]

        // Do odczytania nazwy urządzenia na Android 12+ potrzebne jest uprawnienie BLUETOOTH_CONNECT
        if (ActivityCompat.checkSelfPermission(holder.itemView.context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            holder.deviceName.text = device.name ?: "Nieznana nazwa"
        } else {
            holder.deviceName.text = "Brak uprawnień"
        }

        holder.deviceAddress.text = device.address
        holder.itemView.setOnClickListener {
            onDeviceClicked(device)
        }
    }

    override fun getItemCount() = devices.size
}