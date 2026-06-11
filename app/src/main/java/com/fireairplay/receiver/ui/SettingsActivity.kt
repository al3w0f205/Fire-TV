package com.fireairplay.receiver.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fireairplay.receiver.R

/**
 * Settings screen for FireAirPlay.
 * Optimised for Android TV navigation using D-pad controllers.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var etDeviceName: EditText
    private lateinit var btnSave: Button
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        sharedPreferences = getSharedPreferences("fire_airplay_prefs", Context.MODE_PRIVATE)

        etDeviceName = findViewById(R.id.etDeviceName)
        btnSave = findViewById(R.id.btnSave)

        // Load currently saved receiver name (defaulting to the original name)
        val currentName = sharedPreferences.getString("device_name", "FireTV AirPlay")
        etDeviceName.setText(currentName)

        // Focus the input text field initially for TV remote convenience
        etDeviceName.requestFocus()

        btnSave.setOnClickListener {
            val newName = etDeviceName.text.toString().trim()
            if (newName.isNotEmpty()) {
                sharedPreferences.edit().putString("device_name", newName).apply()
                Toast.makeText(this, "Nombre guardado. El servicio usará este nombre al reconectarse.", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "El nombre del dispositivo no puede estar vacío.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
