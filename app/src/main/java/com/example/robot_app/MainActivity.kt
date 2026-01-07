package com.example.robot_app

import android.os.Bundle
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import android.view.Menu
import android.view.MenuItem
import com.example.robot_app.databinding.ActivityMainBinding
import android.widget.Toast


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Konfiguracja ViewBinding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. Obsługa przycisku "Połącz"
        binding.btnConnect.setOnClickListener {
            if (!isConnected) {
                isConnected = true
                binding.tvStatus.text = "Connected"
                binding.tvStatus.setBackgroundColor(getColor(android.R.color.holo_green_light))
                binding.btnConnect.text = "Rozłącz"
                Toast.makeText(this, "Połączono z robotem", Toast.LENGTH_SHORT).show()
            } else {
                isConnected = false
                binding.tvStatus.text = "Not connected"
                binding.tvStatus.setBackgroundColor(0xFFE0E0E0.toInt()) // Szary
                binding.btnConnect.text = "Połącz"
            }
        }

        // 2. Obsługa kliknięcia w punkt A na mapie
        binding.btnPointA.setOnClickListener {
            binding.tvSelectedPoint.text = "A"
            Toast.makeText(this, "Wybrano punkt A", Toast.LENGTH_SHORT).show()
            // Tu możesz dodać kod wysyłający komendę do robota
        }
    }
}