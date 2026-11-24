package fr.jbrunet.yama.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import fr.jbrunet.yama.MainActivity
import fr.jbrunet.yama.R
import fr.jbrunet.yama.domain.Place
import fr.jbrunet.yama.domain.WeatherAlertMatch
import fr.jbrunet.yama.domain.WeatherAlertRule
import fr.jbrunet.yama.ui.amountRes
import fr.jbrunet.yama.ui.labelRes
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object Notifications {
    private const val CHANNEL_RAIN = "rain"
    private const val CHANNEL_UPDATE = "update"
    private const val ID_RAIN = 1
    private const val ID_UPDATE = 2

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RAIN, context.getString(R.string.channel_rain), NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATE, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun canPostWeather(context: Context): Boolean = canPost(context) &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_RAIN)
            ?.importance != NotificationManager.IMPORTANCE_NONE

    internal fun weather(context: Context, rule: WeatherAlertRule, place: Place, hit: WeatherAlertMatch, zone: ZoneId) {
        val locale = context.resources.configuration.locales[0]
        val value = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }.format(hit.value)
        val amount = context.getString(rule.type.amountRes, value)
        val time = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(zone)
            .format(Instant.ofEpochSecond(hit.time))
        post(
            context, CHANNEL_RAIN, ID_RAIN,
            context.getString(R.string.alert_notification_title, context.getString(rule.type.labelRes), place.name),
            context.getString(R.string.alert_notification_text, amount, time),
            tag = rule.id,
        )
    }

    fun update(context: Context, version: String) =
        post(
            context, CHANNEL_UPDATE, ID_UPDATE,
            context.getString(R.string.notification_update_title),
            context.getString(R.string.notification_update_text, version),
        )

    private fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun post(context: Context, channel: String, id: Int, title: String, text: String, tag: String? = null) {
        if (!canPost(context)) return
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(tag, id, notification)
    }
}
