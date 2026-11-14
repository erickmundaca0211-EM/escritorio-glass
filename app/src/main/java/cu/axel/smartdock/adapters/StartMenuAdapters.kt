package cu.axel.smartdock.adapters

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import cu.axel.smartdock.R
import cu.axel.smartdock.models.App
import cu.axel.smartdock.utils.IconPackUtils
import cu.axel.smartdock.utils.StartMenuUtils
import cu.axel.smartdock.utils.Utils

/*
 * Lists of the Escritorio Glass Start menu (docs/DISENO.md, "Menú Inicio"). They always show the apps'
 * own icons (or the icon pack's), never Smart Dock's coloured circles.
 */

private fun appIcon(app: App, iconPackUtils: IconPackUtils?): Drawable =
    iconPackUtils?.getAppThemedIcon(app.packageName) ?: app.icon

/** Click, long press and right click on an app, like [AppAdapter]. */
private fun bindAppClicks(view: View, app: App, listener: AppAdapter.OnAppClickListener) {
    view.setOnClickListener { listener.onAppClicked(app, it) }
    view.setOnLongClickListener {
        listener.onAppLongClicked(app, it)
        true
    }
    view.setOnTouchListener { v, event ->
        if (event.buttonState == MotionEvent.BUTTON_SECONDARY) {
            listener.onAppLongClicked(app, v)
            return@setOnTouchListener true
        }
        false
    }
}

/** "Ancladas": 6-column grid of pinned apps. */
class PinnedAppsAdapter(
    private val context: Context,
    private var apps: List<App>,
    private val listener: AppAdapter.OnAppClickListener,
    private val iconPackUtils: IconPackUtils?
) : RecyclerView.Adapter<PinnedAppsAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val iconIv: ImageView = itemView.findViewById(R.id.app_icon_iv)
        val nameTv: TextView = itemView.findViewById(R.id.app_name_tv)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(context).inflate(R.layout.start_pinned_entry, parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.iconIv.setImageDrawable(appIcon(app, iconPackUtils))
        holder.nameTv.text = app.name
        holder.itemView.contentDescription = app.name
        bindAppClicks(holder.itemView, app, listener)
    }

    override fun getItemCount() = apps.size
}

/**
 * "Todas las apps": A-Z list with letter separators. While searching it shows only the matches (bold
 * match, no separators); a simple sum like "12*3" shows its result, as Smart Dock did.
 */
class AppListAdapter(
    private val context: Context,
    apps: List<App>,
    private val listener: AppAdapter.OnAppClickListener,
    private val iconPackUtils: IconPackUtils?
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var allApps: List<App> = apps
    private var items: List<Any> = emptyList() // String = letter separator, App = row
    private var query = ""

    /** First app shown (search results first), for "Enter opens the first match". */
    val firstApp: App? get() = items.firstOrNull { it is App } as App?
    val isEmpty: Boolean get() = items.isEmpty()

    init {
        rebuild()
    }

    fun updateApps(apps: List<App>) {
        allApps = apps
        rebuild()
    }

    fun filter(text: String) {
        query = text.trim()
        rebuild()
    }

    private fun rebuild() {
        items = if (query.isEmpty()) {
            val list = ArrayList<Any>()
            var current: String? = null
            for (app in allApps) {
                val letter = StartMenuUtils.sectionLetter(app.name)
                if (letter != current) {
                    list.add(letter)
                    current = letter
                }
                list.add(app)
            }
            list
        } else if (query.matches("^[0-9]+(\\.[0-9]+)?[-+/*][0-9]+(\\.[0-9]+)?".toRegex())) {
            listOf(
                App(
                    Utils.solve(query).toString(), context.packageName + ".calc",
                    ResourcesCompat.getDrawable(
                        context.resources, R.drawable.ic_calculator, context.theme
                    )!!
                )
            )
        } else {
            val needle = StartMenuUtils.normalize(query)
            // Names that start with the text first, then the ones that contain it
            val matches = allApps.filter { StartMenuUtils.normalize(it.name).contains(needle) }
            matches.sortedBy { if (StartMenuUtils.normalize(it.name).startsWith(needle)) 0 else 1 }
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = if (items[position] is String) 0 else 1

    class HeaderHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val letterTv: TextView = itemView.findViewById(R.id.letter_tv)
    }

    class RowHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val iconIv: ImageView = itemView.findViewById(R.id.app_icon_iv)
        val nameTv: TextView = itemView.findViewById(R.id.app_name_tv)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(context)
        return if (viewType == 0) HeaderHolder(
            inflater.inflate(R.layout.start_list_header, parent, false)
        ) else RowHolder(inflater.inflate(R.layout.start_list_entry, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is HeaderHolder) {
            holder.letterTv.text = item as String
            return
        }
        val app = item as App
        val row = holder as RowHolder
        row.iconIv.setImageDrawable(appIcon(app, iconPackUtils))
        row.nameTv.text = highlight(app.name)
        row.itemView.contentDescription = app.name
        bindAppClicks(row.itemView, app, listener)
    }

    private fun highlight(name: String): CharSequence {
        if (query.isEmpty()) return name
        val start = StartMenuUtils.normalize(name).indexOf(StartMenuUtils.normalize(query))
        if (start == -1 || start + query.length > name.length) return name
        return SpannableString(name).apply {
            setSpan(
                StyleSpan(Typeface.BOLD), start, start + query.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    override fun getItemCount() = items.size
}

/** "Recomendado": recently used apps as tiles with "when" under the name. */
class RecentAppsAdapter(
    private val context: Context,
    private var apps: List<StartMenuUtils.RecentApp>,
    private val listener: AppAdapter.OnAppClickListener,
    private val iconPackUtils: IconPackUtils?
) : RecyclerView.Adapter<RecentAppsAdapter.ViewHolder>() {

    /** As many apps as before, so the same rows are only bound again (nothing is inflated while Start opens). */
    fun update(sameSizeApps: List<StartMenuUtils.RecentApp>) {
        apps = sameSizeApps
        notifyItemRangeChanged(0, apps.size)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val iconIv: ImageView = itemView.findViewById(R.id.app_icon_iv)
        val nameTv: TextView = itemView.findViewById(R.id.app_name_tv)
        val timeTv: TextView = itemView.findViewById(R.id.app_time_tv)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(context).inflate(R.layout.start_recent_entry, parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val recent = apps[position]
        holder.iconIv.setImageDrawable(appIcon(recent.app, iconPackUtils))
        holder.nameTv.text = recent.app.name
        holder.timeTv.text = StartMenuUtils.relativeTime(context, recent.lastUsed)
        holder.itemView.contentDescription = recent.app.name
        bindAppClicks(holder.itemView, recent.app, listener)
    }

    override fun getItemCount() = apps.size
}
