package com.example.autoredialer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * سرویس پیش‌زمینه مدیریت تماس مجدد.
 *
 * دلیل استفاده از Foreground Service:
 * - سیستم عامل اندروید سرویس‌های پس‌زمینه را می‌کشد (خصوصاً روی سامسونگ، شیائومی و هوآوی).
 * - سرویس پیش‌زمینه با نمایش نوتیفیکیشن، به سیستم اعلام می‌کند که فعال است
 *   و نباید کشته شود.
 *
 * دلیل استفاده از WakeLock:
 * - حتی با foreground service، CPU ممکن است در حین تأخیر بین تلاش‌ها به خواب برود.
 * - WakeLock مطمئن می‌شود که Handler های زمان‌بندی‌شده اجرا می‌شوند.
 */
class RedialService : Service() {

    companion object {
        const val ACTION_START = "com.example.autoredialer.START"
        const val ACTION_STOP = "com.example.autoredialer.STOP"

        const val EXTRA_PHONE_NUMBER = "phone_number"
        const val EXTRA_MIN_DURATION = "min_duration"
        const val EXTRA_MAX_RETRIES = "max_retries"
        const val EXTRA_RETRY_DELAY = "retry_delay"

        const val BROADCAST_STATUS = "com.example.autoredialer.STATUS"
        const val EXTRA_LOG_MESSAGE = "log_message"
        const val EXTRA_STATUS_TYPE = "status_type"
        const val EXTRA_RETRY_COUNT = "retry_count"
        const val EXTRA_MAX_RETRIES_BROADCAST = "max_retries_broadcast"

        private const val CHANNEL_ID = "redial_channel"
        private const val NOTIFICATION_ID = 1001

        // وضعیت سرویس - static تا MainActivity هم بتواند بخواند
        @Volatile var isRunning = false
        @Volatile var currentPhoneNumber = ""
    }

    // تنظیمات کاربر
    private var phoneNumber = ""
    private var minDuration = 20L      // ثانیه - حداقل مدت برای تشخیص تماس واقعی
    private var maxRetries = 50
    private var retryDelay = 5L        // ثانیه

    // وضعیت داخلی
    private var retryCount = 0
    private var callStartTime = 0L
    private var callWasOffHook = false // آیا تماس واقعاً برقرار شد؟
    private var isWaitingForRetry = false

    // مقادیر وضعیت تلفن به‌صورت String (از EXTRA_STATE)
    private var lastPhoneState = "IDLE"

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    // ── چرخه حیات سرویس ──────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        // ثبت در bridge تا CallStateReceiver بتواند مستقیماً وضعیت را بفرستد
        RedialServiceBridge.instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // دریافت وضعیت از CallStateReceiver (از طریق startService)
            "PHONE_STATE_CHANGED" -> {
                val state = intent.getStringExtra("state") ?: return START_STICKY
                // فقط اگر bridge مستقیم کار نکرده باشد (جلوگیری از دوبار پردازش)
                if (RedialServiceBridge.instance == null) {
                    onPhoneStateChanged(state)
                }
                return START_STICKY
            }
            ACTION_START -> {
                phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: ""
                minDuration = intent.getLongExtra(EXTRA_MIN_DURATION, 20L)
                maxRetries = intent.getIntExtra(EXTRA_MAX_RETRIES, 50)
                retryDelay = intent.getLongExtra(EXTRA_RETRY_DELAY, 5L)

                currentPhoneNumber = phoneNumber
                isRunning = true
                retryCount = 0
                callWasOffHook = false
                isWaitingForRetry = false

                startForeground(NOTIFICATION_ID, buildNotification("در حال آماده‌سازی..."))
                makeCall()
            }
            ACTION_STOP -> {
                sendStatus("سرویس توسط کاربر متوقف شد", "stopped")
                stopSelf()
            }
        }
        // START_STICKY: اگر سیستم سرویس را کشت، مجدداً راه‌اندازی کند
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        currentPhoneNumber = ""
        RedialServiceBridge.instance = null
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── منطق اصلی تماس مجدد ──────────────────────────────────────────────────

    /**
     * این تابع توسط CallStateReceiver فراخوانی می‌شود.
     * منطق ماشین حالت وضعیت تماس اینجا پیاده‌سازی شده است.
     *
     * حالت‌های ممکن برای تماس خروجی:
     *   IDLE → OFFHOOK (شماره‌گیری آغاز شد)
     *   OFFHOOK → IDLE  (تماس قطع شد - موفق یا ناموفق)
     *
     * تشخیص ربات اپراتورهای ایرانی:
     *   وقتی خط مشغول است، بعضی اپراتورها (ایرانسل، همراه اول) به جای بوق اشغال
     *   یک تماس با ربات برقرار می‌کنند که 3 تا 10 ثانیه طول می‌کشد و پیام می‌دهد.
     *   اگر مدت OFFHOOK کمتر از minDuration باشد، تماس واقعی نبوده و باید مجدداً تلاش کنیم.
     */
    fun onPhoneStateChanged(state: String) {
        if (!isRunning) return

        when (state) {
            "OFFHOOK" -> {
                if (lastPhoneState == "IDLE") {
                    // تماس شروع شد (شماره‌گیری یا برقراری)
                    callStartTime = System.currentTimeMillis()
                    callWasOffHook = true
                    sendStatus(
                        "تماس در حال برقراری... (تلاش ${retryCount + 1} از $maxRetries)",
                        "dialing"
                    )
                    updateNotification("📞 در حال تماس... تلاش ${retryCount + 1}")
                }
                lastPhoneState = "OFFHOOK"
            }

            "IDLE" -> {
                if (lastPhoneState == "OFFHOOK" && callWasOffHook) {
                    val durationSec = (System.currentTimeMillis() - callStartTime) / 1000

                    if (durationSec >= minDuration) {
                        // ✓ تماس به اندازه کافی طول کشید → احتمالاً واقعی بود
                        sendStatus(
                            "✓ تماس با موفقیت برقرار شد ($durationSec ثانیه)",
                            "success"
                        )
                        stopSelf()
                    } else {
                        // ✗ تماس خیلی کوتاه بود → مشغول یا ربات
                        val reason = when {
                            durationSec <= 3 -> "خط مشغول"
                            durationSec <= 12 -> "احتمالاً ربات اپراتور (${durationSec}ث)"
                            else -> "تماس کوتاه (${durationSec}ث)"
                        }
                        scheduleRetry("↺ $reason - تلاش مجدد...")
                    }
                    callWasOffHook = false
                } else if (lastPhoneState == "RINGING") {
                    // تماس ورودی بود که پاسخ داده نشد - نادیده می‌گیریم
                }
                lastPhoneState = "IDLE"
            }

            "RINGING" -> {
                // تماس ورودی - نادیده بگیر، کار ما نیست
                lastPhoneState = "RINGING"
            }
        }
    }

    private fun scheduleRetry(reason: String) {
        retryCount++
        if (retryCount >= maxRetries) {
            sendStatus("✗ حداکثر تعداد تلاش ($maxRetries) به پایان رسید", "error")
            stopSelf()
            return
        }

        sendStatus(
            "$reason در $retryDelay ثانیه دیگر... ($retryCount/$maxRetries)",
            "retrying",
            retryCount,
            maxRetries
        )
        updateNotification("⏳ انتظار برای تلاش مجدد... ($retryCount/$maxRetries)")
        isWaitingForRetry = true

        handler.postDelayed({
            if (isRunning) {
                isWaitingForRetry = false
                makeCall()
            }
        }, retryDelay * 1000L)
    }

    private fun makeCall() {
        if (!isRunning) return

        try {
            lastPhoneState = "IDLE"
            callWasOffHook = false

            sendStatus("📞 شماره‌گیری $phoneNumber...", "dialing")
            updateNotification("📞 در حال شماره‌گیری...")

            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                // FLAG_ACTIVITY_NEW_TASK لازم است چون از سرویس Activity باز می‌کنیم
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(callIntent)

        } catch (e: SecurityException) {
            sendStatus("✗ دسترسی تماس رد شد. لطفاً مجوز را بررسی کنید.", "error")
            stopSelf()
        } catch (e: Exception) {
            // اگر خطایی رخ داد، بعد از تأخیر دوباره تلاش کن
            sendStatus("خطا در شماره‌گیری: ${e.message}", "error")
            scheduleRetry("↺ خطا - تلاش مجدد")
        }
    }

    // ── نوتیفیکیشن (لازم برای Foreground Service) ────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "تماس مجدد خودکار",
                NotificationManager.IMPORTANCE_LOW // LOW = بدون صدا
            ).apply {
                description = "نمایش وضعیت سرویس تماس مجدد"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RedialService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("تماس مجدد خودکار - $phoneNumber")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "توقف", stopIntent)
            .setOngoing(true) // کاربر نمی‌تواند نوتیفیکیشن را ببندد
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ── WakeLock (جلوگیری از خواب CPU) ───────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AutoRedialer::RedialWakeLock"
        ).apply {
            // حداکثر 2 ساعت - پس از آن به‌صورت خودکار آزاد می‌شود
            acquire(2 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
    }

    // ── ارسال وضعیت به MainActivity ──────────────────────────────────────────

    private fun sendStatus(
        message: String,
        type: String,
        retryCount: Int = this.retryCount,
        maxRetries: Int = this.maxRetries
    ) {
        val intent = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_LOG_MESSAGE, message)
            putExtra(EXTRA_STATUS_TYPE, type)
            putExtra(EXTRA_RETRY_COUNT, retryCount)
            putExtra(EXTRA_MAX_RETRIES_BROADCAST, maxRetries)
        }
        sendBroadcast(intent)
    }
}
