package com.tharunbirla.librecuts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ErrorDisplayActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val errorCode = intent.getStringExtra("ERROR_CODE") ?: "LC-500"
        val errorLog = intent.getStringExtra("ERROR_LOG") ?: "No log available"

        showCrashDialog(errorCode, errorLog)
    }

    private fun showCrashDialog(code: String, log: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Unexpected Error")
            .setMessage("The app encountered a critical issue ($code). Would you like to report it?")
            .setCancelable(false)
            .setNeutralButton("Copy Log") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Error Log", log))
                Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
                showCrashDialog(code, log) // Re-show to keep the choice alive
            }
            .setPositiveButton("GitHub Report") { _, _ ->
                val url = "https://github.com/tharunbirla/LibreCuts/issues/new?body=$log"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                finish()
            }
            .setNegativeButton("Restart App") { _, _ ->
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            .show()
    }
}