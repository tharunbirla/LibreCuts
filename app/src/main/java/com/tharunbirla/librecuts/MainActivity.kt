package com.tharunbirla.librecuts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.Parcelable
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tharunbirla.librecuts.databinding.ActivityMainBinding
import com.tharunbirla.librecuts.utils.ErrorCode
import com.tharunbirla.librecuts.utils.setBounceClickListener
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val selectVideoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                Log.d("VideoSelection", "Video selected: $uri")
                navigateToEditingScreen(uri)
            } else {
                Log.e("VideoSelectionError", "No video selected")
            }
        }

    private val selectFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                try {
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)

                    val prefs = getSharedPreferences("librecuts_prefs", MODE_PRIVATE)
                    prefs.edit().putString("export_directory_uri", uri.toString()).apply()
                    updateExportFolderUI(uri)
                } catch (e: Exception) {
                    Log.e("FolderSelectionError", "Error securing permission for URI", e)
                    showToast(getString(R.string.toast_failed_to_set_export_folder))
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupGlobalCrashHandler()

        binding.btnImport.setBounceClickListener {
            Log.d("ButtonClick", "Launching video selection.")
            selectVideo()
        }

        // Initialize bottom navigation tab backgrounds
        val attrs = intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
        val ta = obtainStyledAttributes(attrs)
        val inactiveBg = ta.getDrawable(0)
        ta.recycle()
        binding.tabSettings.background = inactiveBg
        binding.tabAbout.background = inactiveBg

        // Setup bottom navigation tab switching
        binding.tabHome.setBounceClickListener {
            switchTab(0)
        }
        
        binding.tabSettings.setBounceClickListener {
            switchTab(1)
        }

        binding.tabAbout.setBounceClickListener {
            switchTab(2)
        }
        
        // Setup Settings Actions
        binding.btnChangeExportFolder.setBounceClickListener {
            selectFolderLauncher.launch(null)
        }
        
        binding.btnCheckForUpdates.setBounceClickListener {
            checkForUpdates()
        }
        
        binding.btnOpenSourceLicenses.setBounceClickListener {
            com.mikepenz.aboutlibraries.LibsBuilder()
                .withActivityTitle(getString(R.string.str_open_source_licenses))
                .withSearchEnabled(true)
                .start(this)
        }
        
        // Initialize Settings UI
        val prefs = getSharedPreferences("librecuts_prefs", MODE_PRIVATE)
        val savedUriString = prefs.getString("export_directory_uri", null)
        if (savedUriString != null) {
            updateExportFolderUI(Uri.parse(savedUriString))
        } else {
            updateExportFolderUI(null)
        }

        // Setup GitHub button listeners
        binding.btnStarGithub.setBounceClickListener {
            openUrl("https://github.com/tharunbirla/LibreCuts")
        }

        binding.btnReportBug.setBounceClickListener {
            openUrl("https://github.com/tharunbirla/LibreCuts/issues")
        }
        binding.btnSponsor.setBounceClickListener {
            openUrl("https://github.com/sponsors/tharunbirla")
        }

        // Onboarding / Welcome Dialog
        val isFirstLaunch = prefs.getBoolean("first_launch_v1", true)
        if (isFirstLaunch) {
            showOnboardingDialog(prefs)
        }

        // Handle shared/intent videos
        handleIntent(intent)
    }

    private fun updateExportFolderUI(uri: Uri?) {
        if (uri == null) {
            binding.tvCurrentExportFolder.text = getString(R.string.str_default_movies_librecuts)
        } else {
            try {
                val path = uri.lastPathSegment?.split(":")?.lastOrNull()
                if (!path.isNullOrEmpty()) {
                    binding.tvCurrentExportFolder.text = path
                } else {
                    binding.tvCurrentExportFolder.text = getString(R.string.str_custom_directory)
                }
            } catch (e: Exception) {
                binding.tvCurrentExportFolder.text = getString(R.string.str_custom_directory)
            }
        }
    }

    private fun switchTab(tabIndex: Int) {
        val activeBg = ContextCompat.getDrawable(this, R.drawable.bg_nav_active)
        val attrs = intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
        val ta = obtainStyledAttributes(attrs)
        val inactiveBg = ta.getDrawable(0)
        ta.recycle()

        // Reset all tabs to inactive
        binding.layoutHomeContent.visibility = View.GONE
        binding.layoutSettingsContent.visibility = View.GONE
        binding.layoutAboutContent.visibility = View.GONE

        binding.tabHome.background = inactiveBg
        binding.ivHome.setColorFilter(ContextCompat.getColor(this, R.color.inactiveTool))
        binding.tabSettings.background = inactiveBg
        binding.ivSettings.setColorFilter(ContextCompat.getColor(this, R.color.inactiveTool))
        binding.tabAbout.background = inactiveBg
        binding.ivAbout.setColorFilter(ContextCompat.getColor(this, R.color.inactiveTool))

        when (tabIndex) {
            0 -> {
                binding.layoutHomeContent.visibility = View.VISIBLE
                binding.tabHome.background = activeBg
                binding.ivHome.setColorFilter(ContextCompat.getColor(this, R.color.colorPrimary))
            }
            1 -> {
                binding.layoutSettingsContent.visibility = View.VISIBLE
                binding.tabSettings.background = activeBg
                binding.ivSettings.setColorFilter(ContextCompat.getColor(this, R.color.colorPrimary))
            }
            2 -> {
                binding.layoutAboutContent.visibility = View.VISIBLE
                binding.tabAbout.background = activeBg
                binding.ivAbout.setColorFilter(ContextCompat.getColor(this, R.color.colorPrimary))
            }
        }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            showToast("Unable to open link")
        }
    }

    private fun setupGlobalCrashHandler() {
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("LibreCutsCrash", "CRITICAL CRASH", throwable)

            // Start a dedicated Error Activity to show the dialog
            val intent = Intent(this, ErrorDisplayActivity::class.java).apply {
                putExtra("ERROR_CODE", ErrorCode.UNEXPECTED_CRASH.code)
                putExtra("ERROR_LOG", Log.getStackTraceString(throwable))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(intent)

            // Terminate the crashed process
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }
    }



    private fun selectVideo() {
        Log.d("VideoSelection", "Launching video selector.")
        selectVideoLauncher.launch("video/*")
    }

    private fun navigateToEditingScreen(videoUri: Uri) {
        Log.d("Navigation", "Navigating to editing screen with URI: $videoUri")
        val intent = Intent(this, VideoEditingActivity::class.java).apply {
            putExtra("VIDEO_URI", videoUri)
            data = videoUri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null) {
            if (type.startsWith("video/")) {
                (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let { uri ->
                    Log.d("SharedVideo", "Received SEND intent with video URI: $uri")
                    navigateToEditingScreen(uri)
                }
            }
        } else if ((Intent.ACTION_VIEW == action || Intent.ACTION_EDIT == action) && type != null) {
            if (type.startsWith("video/")) {
                intent.data?.let { uri ->
                    Log.d("SharedVideo", "Received VIEW/EDIT intent with video URI: $uri")
                    navigateToEditingScreen(uri)
                }
            }
        }
    }

    private fun showOnboardingDialog(prefs: android.content.SharedPreferences) {
        val dialog = android.app.Dialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_welcome_onboarding, null)
        dialog.setContentView(view)
        dialog.setCancelable(false)

        dialog.window?.let { window ->
            // Make dialog window background transparent so our custom layout's background card and shape render perfectly
            window.setBackgroundDrawableResource(android.R.color.transparent)
            
            // Set size parameters
            val lp = window.attributes
            lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            window.attributes = lp
        }

        // Set version dynamically
        val tvVersion = view.findViewById<TextView>(R.id.tvOnboardingVersion)
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            tvVersion.text = "Version ${pInfo.versionName}"
        } catch (e: Exception) {
            tvVersion.text = "Version 1.0-beta4"
        }

        view.findViewById<View>(R.id.layoutStarGithub).setBounceClickListener {
            openUrl("https://github.com/tharunbirla/LibreCuts")
        }

        view.findViewById<View>(R.id.layoutSponsorGithub).setBounceClickListener {
            openUrl("https://github.com/sponsors/tharunbirla")
        }

        view.findViewById<View>(R.id.layoutDiscord).setBounceClickListener {
            openUrl("https://discord.gg/gwr3nE7YW")
        }

        view.findViewById<View>(R.id.layoutTroubleshooting)?.setBounceClickListener {
            openUrl("https://github.com/tharunbirla/LibreCuts/wiki/Error-Codes-&-Troubleshooting")
        }

        view.findViewById<View>(R.id.btnOnboardingGetStarted).setBounceClickListener {
            prefs.edit().putBoolean("first_launch_v1", false).apply()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showToast(message: String) {
        Log.d("ToastMessage", "Showing toast: $message")
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun checkForUpdates() {
        showToast("Checking for updates in browser...")
        openUrl("https://github.com/tharunbirla/LibreCuts/releases/latest")
    }
}