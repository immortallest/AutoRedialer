package com.example.autoredialer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.autoredialer.databinding.ActivityMainBinding
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // لیست تمام دسترسی‌های لازم
    private val requiredPermissions = mutableListOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
    ).apply {
        // در اندروید 13 و بالاتر، دسترسی نمایش نوتیفیکیشن لازم است
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val PERMISSION_REQUEST_CODE = 100

    // گیرنده پیام‌های وضعیت از سرویس
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val message = intent.getStringExtra(RedialService.EXTRA_LOG_MESSAGE) ?: return
            val type = intent.getStringExtra(RedialService.EXTRA_STATUS_TYPE) ?: "info"
            val retryCount = intent.getIntExtra(RedialService.EXTRA_RETRY_COUNT, 0)
            val maxRetries = intent.getIntExtra(RedialService.EXTRA_MAX_RETRIES, 0)

            appendLog(message, type)
            updateStatusUI(type, retryCount, maxRetries)

            // اگر سرویس متوقف شد، دکمه را به حالت اولیه برگردان
            if (type == "success" || type == "stopped" || type == "error") {
                setRunningState(false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkAndRequestPermissions()
        checkBatteryOptimization()
    }

    override fun onResume() {
        super.onResume()
        // ثبت گیرنده پیام‌های سرویس
        val filter = IntentFilter(RedialService.BROADCAST_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }

        // اگر سرویس در حال اجراست، UI را به‌روز کن
        if (RedialService.isRunning) {
            setRunningState(true)
            binding.etPhoneNumber.setText(RedialService.currentPhoneNumber)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: IllegalArgumentException) {}
    }

    private fun setupUI() {
        // مقادیر پیش‌فرض تنظیمات
        binding.etMinDuration.setText("20")
        binding.etMaxRetries.setText("50")
        binding.etRetryDelay.setText("5")

        // دکمه شروع/توقف
        binding.btnStartStop.setOnClickListener {
            if (RedialService.isRunning) {
                stopRedialing()
            } else {
                startRedialing()
            }
        }

        // دکمه پاک‌کردن لاگ
        binding.btnClearLog.setOnClickListener {
            binding.tvLog.text = ""
        }

        // نمایش/پنهان‌کردن تنظیمات پیشرفته
        binding.tvSettingsToggle.setOnClickListener {
            if (binding.layoutAdvancedSettings.visibility == View.VISIBLE) {
                binding.layoutAdvancedSettings.visibility = View.GONE
                binding.tvSettingsToggle.text = "▼ تنظیمات پیشرفته"
            } else {
                binding.layoutAdvancedSettings.visibility = View.VISIBLE
                binding.tvSettingsToggle.text = "▲ تنظیمات پیشرفته"
            }
        }
    }

    private fun startRedialing() {
        val phoneNumber = binding.etPhoneNumber.text.toString().trim()
        if (phoneNumber.isEmpty()) {
            Toast.makeText(this, "لطفاً شماره تلفن را وارد کنید", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isValidIranianNumber(phoneNumber)) {
            AlertDialog.Builder(this)
                .setTitle("شماره نامعتبر")
                .setMessage("شماره وارد‌شده معتبر به نظر نمی‌رسد. آیا می‌خواهید ادامه دهید؟")
                .setPositiveButton("بله") { _, _ -> doStart(phoneNumber) }
                .setNegativeButton("خیر", null)
                .show()
        } else {
            doStart(phoneNumber)
        }
    }

    private fun doStart(phoneNumber: String) {
        if (!hasAllPermissions()) {
            checkAndRequestPermissions()
            return
        }

        val minDuration = binding.etMinDuration.text.toString().toLongOrNull() ?: 20L
        val maxRetries = binding.etMaxRetries.text.toString().toIntOrNull() ?: 50
        val retryDelay = binding.etRetryDelay.text.toString().toLongOrNull() ?: 5L

        val intent = Intent(this, RedialService::class.java).apply {
            action = RedialService.ACTION_START
            putExtra(RedialService.EXTRA_PHONE_NUMBER, phoneNumber)
            putExtra(RedialService.EXTRA_MIN_DURATION, minDuration)
            putExtra(RedialService.EXTRA_MAX_RETRIES, maxRetries)
            putExtra(RedialService.EXTRA_RETRY_DELAY, retryDelay)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        setRunningState(true)
        appendLog("▶ شروع تماس مجدد خودکار با $phoneNumber", "start")
        appendLog("  حداقل مدت تماس واقعی: $minDuration ثانیه", "info")
        appendLog("  حداکثر تعداد تلاش: $maxRetries", "info")
        appendLog("  تأخیر بین تلاش‌ها: $retryDelay ثانیه", "info")
    }

    private fun stopRedialing() {
        val intent = Intent(this, RedialService::class.java).apply {
            action = RedialService.ACTION_STOP
        }
        startService(intent)
        setRunningState(false)
        appendLog("■ توقف توسط کاربر", "stopped")
    }

    private fun setRunningState(running: Boolean) {
        if (running) {
            binding.btnStartStop.text = "■ توقف"
            binding.btnStartStop.setBackgroundColor(ContextCompat.getColor(this, R.color.error))
            binding.statusIndicator.setColorFilter(ContextCompat.getColor(this, R.color.success))
            binding.tvCurrentStatus.text = "در حال اجرا..."
            binding.etPhoneNumber.isEnabled = false
            binding.layoutAdvancedSettings.visibility = View.GONE
            binding.tvSettingsToggle.visibility = View.GONE
        } else {
            binding.btnStartStop.text = "▶ شروع"
            binding.btnStartStop.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
            binding.statusIndicator.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
            binding.tvCurrentStatus.text = "آماده"
            binding.etPhoneNumber.isEnabled = true
            binding.tvSettingsToggle.visibility = View.VISIBLE
        }
    }

    private fun updateStatusUI(type: String, retryCount: Int, maxRetries: Int) {
        when (type) {
            "dialing" -> {
                binding.statusIndicator.setColorFilter(ContextCompat.getColor(this, R.color.warning))
                binding.tvCurrentStatus.text = "در حال شماره‌گیری..."
            }
            "retrying" -> {
                binding.statusIndicator.setColorFilter(ContextCompat.getColor(this, R.color.warning))
                binding.tvCurrentStatus.text = "تلاش $retryCount از $maxRetries"
                binding.progressRetry.max = maxRetries
                binding.progressRetry.progress = retryCount
                binding.progressRetry.visibility = View.VISIBLE
            }
            "success" -> {
                binding.statusIndicator.setColorFilter(ContextCompat.getColor(this, R.color.success))
                binding.tvCurrentStatus.text = "تماس برقرار شد ✓"
                binding.progressRetry.visibility = View.GONE
            }
            "error", "stopped" -> {
                binding.statusIndicator.setColorFilter(ContextCompat.getColor(this, R.color.error))
                binding.tvCurrentStatus.text = if (type == "error") "خطا" else "متوقف شد"
                binding.progressRetry.visibility = View.GONE
            }
        }
    }

    private fun appendLog(message: String, type: String = "info") {
        val time = DateFormat.format("HH:mm:ss", Date()).toString()
        val prefix = when (type) {
            "success" -> "✓"
            "error" -> "✗"
            "retrying" -> "↺"
            "dialing" -> "↗"
            "start" -> "▶"
            "stopped" -> "■"
            else -> "·"
        }
        val currentLog = binding.tvLog.text.toString()
        binding.tvLog.text = "[$time] $prefix $message\n$currentLog"
    }

    // ── دسترسی‌ها ──────────────────────────────────────────────────────────────

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val denied = permissions.zip(grantResults.toList()).filter {
                it.second != PackageManager.PERMISSION_GRANTED
            }.map { it.first }

            if (denied.isNotEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("دسترسی لازم است")
                    .setMessage(
                        "این دسترسی‌ها برای عملکرد اپ ضروری هستند:\n\n" +
                                "• مجوز تماس تلفنی\n• مجوز خواندن وضعیت تلفن\n\n" +
                                "لطفاً در تنظیمات دستگاه، دسترسی‌ها را فعال کنید."
                    )
                    .setPositiveButton("رفتن به تنظیمات") { _, _ ->
                        openAppSettings()
                    }
                    .setNegativeButton("انصراف", null)
                    .show()
            }
        }
    }

    // ── بهینه‌سازی باتری ──────────────────────────────────────────────────────

    /**
     * بسیاری از گوشی‌ها (خصوصاً سامسونگ، شیائومی، هوآوی) در حالت بهینه‌سازی باتری،
     * سرویس‌های پس‌زمینه را می‌کشند. این تابع کاربر را ترغیب می‌کند که اپ را
     * از بهینه‌سازی باتری استثنا کند تا روی همه دستگاه‌ها کار کند.
     */
    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle("بهینه‌سازی باتری")
                    .setMessage(
                        "برای عملکرد صحیح در همه دستگاه‌ها (سامسونگ، شیائومی و...)،\n" +
                                "لطفاً اپ را از بهینه‌سازی باتری استثنا کنید.\n\n" +
                                "در صفحه بعد، «مجاز» یا «Allow» را انتخاب کنید."
                    )
                    .setPositiveButton("تنظیم") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        } catch (_: Exception) {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }
                    .setNegativeButton("بعداً", null)
                    .show()
            }
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun isValidIranianNumber(number: String): Boolean {
        // شماره‌های ایرانی: 09xxxxxxxxx یا +989xxxxxxxxx یا 009
        val cleaned = number.replace(" ", "").replace("-", "")
        return cleaned.matches(Regex("^(0|\\+98|0098)?9[0-9]{9}$")) ||
                cleaned.matches(Regex("^[0-9]{8,15}$")) // سایر فرمت‌ها
    }
}
