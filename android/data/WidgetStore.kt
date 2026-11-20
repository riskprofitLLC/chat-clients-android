package chat.client.data

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import chat.client.R
import chat.client.domain.WidgetFeed
import chat.client.ui.widget.ChatListWidgetProvider

// GE1: снапшот списка чатов для виджета главного экрана. Лежит локально
// (как и расшифрованная история), обновляется при каждой пересборке списка
// чатов и чистится при выходе из аккаунта.
class WidgetStore(private val context: Context) {

    private fun prefs() = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)

    fun save(items: List<WidgetFeed.Item>) {
        prefs().edit().putString(KEY_FEED, WidgetFeed.toJson(items)).apply()
        notifyChanged()
    }

    fun load(): List<WidgetFeed.Item> =
        prefs().getString(KEY_FEED, null)?.let(WidgetFeed::fromJson) ?: emptyList()

    fun clear() {
        prefs().edit().remove(KEY_FEED).apply()
        notifyChanged()
    }

    // дергаем RemoteViewsService виджета — он перечитает снапшот
    private fun notifyChanged() {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, ChatListWidgetProvider::class.java))
        if (ids.isNotEmpty()) mgr.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
    }

    companion object {
        const val KEY_FEED = "feed"

        // из сводок репозитория — вход виджета
        fun feedOf(summaries: List<ChatSummary>): List<WidgetFeed.Item> =
            WidgetFeed.build(
                summaries.map {
                    WidgetFeed.Input(
                        chatId = it.id,
                        title = it.title,
                        preview = it.lastPreview,
                        unread = it.unread,
                        ts = it.lastTs,
                        hidden = it.hidden,
                    )
                },
            )
    }
}
