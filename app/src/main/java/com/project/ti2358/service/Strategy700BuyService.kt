package com.project.ti2358.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast
import com.project.ti2358.data.manager.Strategy1000Buy
import com.project.ti2358.data.manager.SettingsManager
import kotlinx.coroutines.*
import okhttp3.internal.notify
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinApiExtension
import java.util.*

@KoinApiExtension
class Strategy700BuyService : Service() {

    private val NOTIFICATION_ACTION = "event.700buy"
    private val NOTIFICATION_CANCEL_ACTION = "event.700buy.cancel"
    private val NOTIFICATION_CHANNEL_ID = "700 BUY CHANNEL NOTIFICATION"
    private val NOTIFICATION_ID = 17001

    private val strategy1000Buy: Strategy1000Buy by inject()

    private var wakeLock: PowerManager.WakeLock? = null
    private var isServiceRunning = false
    private lateinit var schedulePurchaseTime: Calendar
    private var notificationButtonReceiver: BroadcastReceiver? = null

    var title: String = ""
    var updateTitle: Boolean = true
    var job: Job? = null

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val intentFilter = IntentFilter(NOTIFICATION_ACTION)
        notificationButtonReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val type = intent.getStringExtra("type")
                if (type == NOTIFICATION_CANCEL_ACTION) {
                    if (notificationButtonReceiver != null) unregisterReceiver(
                        notificationButtonReceiver
                    )
                    notificationButtonReceiver = null
                    context.stopService(Intent(context, Strategy700BuyService::class.java))
                }
            }
        }
        registerReceiver(notificationButtonReceiver, intentFilter)

        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        val notification = Utils.createNotification(this, NOTIFICATION_CHANNEL_ID, "700 buy","",  "", "")
        startForeground(NOTIFICATION_ID, notification)

        strategy1000Buy.prepareBuy700()
        schedulePurchase()
    }

    override fun onDestroy() {
        Toast.makeText(this, "Покупка 700 Buy отменена", Toast.LENGTH_LONG).show()
        if (notificationButtonReceiver != null) unregisterReceiver(notificationButtonReceiver)
        notificationButtonReceiver = null
        isServiceRunning = false
        job?.cancel()
        strategy1000Buy.stopStrategy700()
        super.onDestroy()
    }

    private fun schedulePurchase() {
        Toast.makeText(this, "Запущен таймер на покупку 700", Toast.LENGTH_LONG).show()
        isServiceRunning = true

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EndlessService::lock").apply {
                acquire(10*10*1000L /*10 minutes*/)
            }
        }

        val differenceHours: Int = Utils.getTimeDiffBetweenMSK()

        // 07:00:00.100
        val hours = 7
        val minutes = 0
        val seconds = 0
        val milliseconds = 100

        schedulePurchaseTime = Calendar.getInstance(TimeZone.getDefault())
        schedulePurchaseTime.add(Calendar.HOUR_OF_DAY, -differenceHours)
        schedulePurchaseTime.set(Calendar.HOUR_OF_DAY, hours)
        schedulePurchaseTime.set(Calendar.MINUTE, minutes)
        schedulePurchaseTime.set(Calendar.SECOND, seconds)
        schedulePurchaseTime.set(Calendar.MILLISECOND, milliseconds)
        schedulePurchaseTime.add(Calendar.HOUR_OF_DAY, differenceHours)

        val now = Calendar.getInstance(TimeZone.getDefault())
        var scheduleDelay = schedulePurchaseTime.timeInMillis - now.timeInMillis
        if (scheduleDelay < 0) {
            schedulePurchaseTime.add(Calendar.DAY_OF_MONTH, 1)
            scheduleDelay = schedulePurchaseTime.timeInMillis - now.timeInMillis
        }

        if (scheduleDelay < 0) {
            stopService()
            return
        }

        job?.cancel()
        job = GlobalScope.launch(Dispatchers.Main) {
            while (isServiceRunning) {
                val delaySeconds = updateNotification()
                delay(1 * 100 * delaySeconds)
            }
        }
    }

    private fun stopService() {
        Toast.makeText(this, "700 Buy остановлена", Toast.LENGTH_SHORT).show()
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            stopForeground(true)
            stopSelf()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        isServiceRunning = false
    }

    private fun updateNotification(): Long {
        val now = Calendar.getInstance(TimeZone.getDefault())
        val scheduleDelay = schedulePurchaseTime.timeInMillis - now.timeInMillis

        val allSeconds = scheduleDelay / 1000
        val hours = allSeconds / 3600
        val minutes = (allSeconds - hours * 3600) / 60
        val seconds = allSeconds % 60

        if (updateTitle) {
            if (hours + minutes + seconds <= 0) {
                strategy1000Buy.startStrategy700Buy()
                updateTitle = false
                title = "Покупка!"
            } else {
                title = "Покупка через %02d:%02d:%02d".format(hours, minutes, seconds)
            }
        }

        val shortText: String = strategy1000Buy.getNotificationTextShort(strategy1000Buy.stocksToBuy700)
        val longText: String = strategy1000Buy.getNotificationTextLong(strategy1000Buy.stocksToBuy700)
        val longTitleText: String = "~" + strategy1000Buy.getTotalPurchaseString(strategy1000Buy.stocksToBuy700) + " ="

        val cancelIntent = Intent(NOTIFICATION_ACTION).apply { putExtra("type", NOTIFICATION_CANCEL_ACTION) }
        val pendingCancelIntent = PendingIntent.getBroadcast(this, 1, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT)
        val actionCancel: Notification.Action = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            Notification.Action.Builder(null, "СТОП", pendingCancelIntent).build()
        } else {
            Notification.Action.Builder(0, "СТОП", pendingCancelIntent).build()
        }

        val notification = Utils.createNotification(this, NOTIFICATION_CHANNEL_ID, title, shortText, longText, longTitleText, actionCancel)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)

        when {
            hours > 1 -> {
                return 100
            }
            minutes > 10 -> {
                return 50
            }
            minutes > 1 -> {
                return 20
            }
            minutes < 1 -> {
                return 1
            }
        }

        return 50
    }
}
