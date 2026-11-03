package com.ca1nm.droidfileexplorer.presentation.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.ca1nm.droidfileexplorer.MainActivity
import com.ca1nm.droidfileexplorer.R
import com.ca1nm.droidfileexplorer.data.settings.AppSettings
import java.io.File

private const val STORAGE_ROOT = "/storage/emulated/0"
private val DEFAULT_FOLDERS = listOf("Download", "DCIM", "Pictures", "Music", "Movies", "Documents")

/** Every pinned folder: the built-in quick-access ones, then the user's own. */
internal fun pinnedFolders(context: Context): List<String> {
    val prefs = context.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)
    // The list as edited in the app (name<TAB>path per line); older installs fall back to the built-ins plus pinned folders.
    prefs.getString("quick_access_v2", null)?.let { raw ->
        return raw.split('\n').mapNotNull { l -> l.substringAfter('\t', "").takeIf { it.startsWith("/") } }.distinct()
    }
    val custom = prefs.getString("quick_access_custom", "").orEmpty().split('\n').filter { it.isNotBlank() }
    return (listOf(STORAGE_ROOT) + DEFAULT_FOLDERS.map { "$STORAGE_ROOT/$it" } + custom).distinct()
}

/** Home-screen widget: a list of pinned folders; tapping one opens it in the app. */
class PinnedFoldersWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_pinned)
            views.setRemoteAdapter(
                R.id.widget_list,
                Intent(context, PinnedFoldersWidgetService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                }
            )
            val template = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setPendingIntentTemplate(R.id.widget_list, template)
            manager.updateAppWidget(id, views)
            manager.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
        }
    }
}

class PinnedFoldersWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(private val context: Context) : RemoteViewsFactory {
        private var folders: List<String> = emptyList()
        override fun onCreate() { folders = pinnedFolders(context) }
        override fun onDataSetChanged() { folders = pinnedFolders(context) }
        override fun onDestroy() {}
        override fun getCount() = folders.size
        override fun getViewAt(position: Int): RemoteViews {
            val path = folders[position]
            val name = if (path == STORAGE_ROOT) "Internal storage" else File(path).name
            return RemoteViews(context.packageName, R.layout.widget_pinned_item).apply {
                setTextViewText(R.id.widget_item, name)
                setOnClickFillInIntent(R.id.widget_item, Intent().putExtra(MainActivity.EXTRA_OPEN_PATH, path))
            }
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = true
    }
}
