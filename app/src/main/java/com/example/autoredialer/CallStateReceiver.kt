package com.example.autoredialer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager

/**
 * دریافت‌کننده تغییرات وضعیت تماس.
 *
 * چرا در مانیفست ثبت می‌شود (و نه در کد):
 * - اگر سیستم سرویس را بکشد، این گیرنده همچنان فعال است.
 * - با دریافت اولین تغییر وضعیت، می‌تواند سرویس را دوباره راه‌اندازی کند.
 *
 * نکته سازگاری با اندروید ۱۲+:
 * - در اندروید ۱۲+، broadcast receiver های مانیفست برای برخی intent ها محدود شدند،
 *   اما PHONE_STATE همچنان کار می‌کند چون جزء دسترسی‌های خاص است.
 */
class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // فقط اگر سرویس فعال باشد، پردازش کن
        if (!RedialService.isRunning) return

        when (intent.action) {
            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
                notifyService(context, state)
            }

            // این intent در اندروید ۱۰+ deprecated شده،
            // اما هنوز روی اکثر دستگاه‌ها کار می‌کند
            Intent.ACTION_NEW_OUTGOING_CALL -> {
                // برای اطمینان، وضعیت را به OFFHOOK تغییر می‌دهیم
                // چون NEW_OUTGOING_CALL قبل از PHONE_STATE می‌آید
                notifyService(context, TelephonyManager.EXTRA_STATE_OFFHOOK)
            }
        }
    }

    private fun notifyService(context: Context, state: String) {
        // وضعیت را به سرویس منتقل کن
        val serviceIntent = Intent(context, RedialService::class.java).apply {
            action = "PHONE_STATE_CHANGED"
            putExtra("state", state)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: Exception) {
            // اگر سرویس کشته شده، نادیده بگیر
        }

        // مستقیماً به RedialService اطلاع بده (اگر هنوز در حافظه باشد)
        // این روش مطمئن‌تر است چون از startService صبر نمی‌کند
        RedialServiceBridge.instance?.onPhoneStateChanged(state)
    }
}

/**
 * پل ارتباطی بین CallStateReceiver و RedialService.
 * از آنجا که BroadcastReceiver نمی‌تواند مستقیماً به instance سرویس دسترسی داشته باشد،
 * از این singleton استفاده می‌کنیم.
 */
object RedialServiceBridge {
    var instance: RedialService? = null
}
