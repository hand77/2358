package com.project.ti2358.data.manager

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import com.project.ti2358.MainActivity
import com.project.ti2358.R
import com.project.ti2358.TheApplication
import com.project.ti2358.service.*
import kotlinx.coroutines.*
import org.koin.core.component.KoinApiExtension
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.*
import kotlin.math.abs
import kotlin.random.Random

@KoinApiExtension
class StrategyLimits : KoinComponent {
    private val stockManager: StockManager by inject()
    private val strategySpeaker: StrategySpeaker by inject()
    private val strategyTelegram: StrategyTelegram by inject()

    var stocks: MutableList<Stock> = mutableListOf()
    var upLimitStocks: MutableList<LimitStock> = Collections.synchronizedList(mutableListOf())
    var downLimitStocks: MutableList<LimitStock> = Collections.synchronizedList(mutableListOf())
    private var currentSort: Sorting = Sorting.DESCENDING

    private var started: Boolean = false

    suspend fun process(): MutableList<Stock> = withContext(StockManager.limitsContext) {
        val all = stockManager.getWhiteStocks()

        val min = SettingsManager.getCommonPriceMin()
        val max = SettingsManager.getCommonPriceMax()

        stocks.clear()
        stocks.addAll(all.filter { it.getPriceNow() > min && it.getPriceNow() < max })

        return@withContext stocks
    }

    fun resort(): MutableList<Stock> {
        currentSort = if (currentSort == Sorting.DESCENDING) Sorting.ASCENDING else Sorting.DESCENDING
        val temp = stocks

        // удалить все бумаги, где лимиты неизвестны
        temp.removeAll { it.stockInfo?.limit_up == 0.0 && it.stockInfo?.limit_down == 0.0 }

        temp.sortBy {
            val sign = if (currentSort == Sorting.ASCENDING) 1 else -1
            Utils.getPercentFromTo(it.stockInfo?.limit_up ?: 0.0, it.getPriceRaw()) * sign
        }

        return stocks
    }

    suspend fun restartStrategy() = withContext(StockManager.limitsContext) {
        if (started) stopStrategy()
        delay(500)
        startStrategy()
    }

    suspend fun startStrategy() = withContext(StockManager.limitsContext) {
        upLimitStocks.clear()
        downLimitStocks.clear()

        process()

        started = true
        strategyTelegram.sendLimitsStart(true)
    }

    fun stopStrategy() {
        started = false
        strategyTelegram.sendLimitsStart(false)
    }

    suspend fun processStrategy(stock: Stock) = withContext(StockManager.limitsContext) {
        if (!started || stock.stockInfo == null) return@withContext
        if (stocks.isEmpty()) runBlocking { process() }
        if (stock !in stocks) return@withContext

        val changeUpLimit = SettingsManager.getLimitsChangeDown()
        val changeDownLimit = SettingsManager.getLimitsChangeUp()
        val allowDown = SettingsManager.getLimitsDown()
        val allowUp = SettingsManager.getLimitsUp()

        if (stock.minuteCandles.isEmpty()) return@withContext

        val lastCandle = stock.minuteCandles.last()

        // минимальный объём
        if (lastCandle.volume < 50) return@withContext

        val fireTime = lastCandle.time.time

        stock.stockInfo?.let {
            var limitStock: LimitStock? = null

            val allDistance = it.limit_up - it.limit_down
            val center = it.limit_down + allDistance / 2.0
            val price = stock.getPriceRaw()

            val upLimitChange = Utils.getPercentFromTo(it.limit_up, price)
            val downLimitChange = Utils.getPercentFromTo(it.limit_down, price)

            if (price == it.limit_up && allowUp) { // на верхних
                limitStock = LimitStock(stock, LimitType.ON_UP, upLimitChange, price, fireTime)
            } else if (price == it.limit_down && allowDown) { // на нижних
                limitStock = LimitStock(stock, LimitType.ON_DOWN, downLimitChange, price, fireTime)
            } else if (price > it.limit_up && allowUp) { // выше верхних
                limitStock = LimitStock(stock, LimitType.ABOVE_UP, upLimitChange, price, fireTime)
            } else if (price > center && allowUp) { // близко к верхним
                val percentUpLeft = 100.0 - 100.0 * (stock.getPriceRaw() - center) / (it.limit_up - center)

                if (percentUpLeft < changeUpLimit) { // если близко к лимиту - сигналим!
                    limitStock = LimitStock(stock, LimitType.NEAR_UP, upLimitChange, price, fireTime)
                } else {

                }

            } else if (price < it.limit_down && allowDown) { // ниже нижних
                limitStock = LimitStock(stock, LimitType.UNDER_DOWN, downLimitChange, price, fireTime)
            } else if (price < center && allowDown) { // ближе к нижнему
                val percentDownLeft = 100.0 - 100.0 * (center - stock.getPriceRaw()) / (center - it.limit_down)
                if (percentDownLeft < changeDownLimit) { // если близко к лимиту - сигналим!
                    limitStock = LimitStock(stock, LimitType.NEAR_DOWN, downLimitChange, price, fireTime)
                } else {

                }
            } else {

            }

            if (limitStock != null) {
                if (limitStock.type in listOf(LimitType.NEAR_UP, LimitType.ABOVE_UP, LimitType.ON_UP)) {
                    val last = upLimitStocks.firstOrNull { stock -> stock.stock.ticker == stock.ticker }
                    if (last != null) {
                        val deltaTime = ((fireTime - last.fireTime) / 60.0 / 1000.0).toInt()
                        if (deltaTime < 5) return@withContext
                    }
                    upLimitStocks.add(0, limitStock)
                } else {
                    val last = downLimitStocks.firstOrNull { stock -> stock.stock.ticker == stock.ticker }
                    if (last != null) {
                        val deltaTime = ((fireTime - last.fireTime) / 60.0 / 1000.0).toInt()
                        if (deltaTime < 5) return@withContext
                    }
                    downLimitStocks.add(0, limitStock)
                }

                GlobalScope.launch(Dispatchers.Main) {
                    strategySpeaker.speakLimit(limitStock)
                    strategyTelegram.sendLimit(limitStock)
                    createLimit(limitStock)
                }
            }
        }

    }

    private fun createLimit(limitStock: LimitStock) {
        val context: Context = TheApplication.application.applicationContext

        val ticker = limitStock.ticker
        val notificationChannelId = ticker

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                notificationChannelId,
                "Limits notifications channel $ticker",
                NotificationManager.IMPORTANCE_HIGH
            ).let {
                it.description = notificationChannelId
                it.lightColor = Color.RED
                it.enableVibration(true)
                it.enableLights(true)
                it
            }
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent: PendingIntent = Intent(context, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(context, 0, notificationIntent, 0)
        }

        val builder: Notification.Builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, notificationChannelId) else Notification.Builder(context)

        val emoji = when (limitStock.type)  {
            LimitType.ON_UP -> "⬆️ на лимите"
            LimitType.ON_DOWN -> "⬇️️ на лимите"

            LimitType.ABOVE_UP -> "⬆️ выше лимита"
            LimitType.UNDER_DOWN -> "⬇️️ ниже лимита"

            LimitType.NEAR_UP -> "⬆️ рядом с лимитом"
            LimitType.NEAR_DOWN -> "⬇️️ рядом с лимитом"
        }

        val title = "$ticker: $emoji"

        val notification = builder
            .setSubText("$$ticker $emoji")
            .setContentTitle(title)
            .setShowWhen(true)
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .build()

        val manager = context.getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager
        val uid = Random.nextInt(0, 100000)
        manager.notify(ticker, uid, notification)

        val alive: Long = SettingsManager.getRocketNotifyAlive().toLong()
        GlobalScope.launch(Dispatchers.Main) {
            delay(1000 * alive)
            manager.cancel(ticker, uid)
        }
    }
}