package cu.axel.smartdock.components

import android.annotation.SuppressLint
import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cu.axel.smartdock.R
import cu.axel.smartdock.utils.ColorUtils
import cu.axel.smartdock.utils.StartMenuUtils
import cu.axel.smartdock.utils.SwipeDismissTouchListener
import cu.axel.smartdock.utils.Utils

/**
 * Notifications flyout of the taskbar bell (docs/DISENO.md, "Notificaciones y calendario").
 *
 * Notifications are grouped by app and only shown or dismissed: nothing is stored or sent anywhere
 * (CLAUDE.md). Their own action buttons work like in the system shade; a reply typed in "Responder"
 * goes only to the app that asked for it and is kept in memory just while it is being written.
 * The calendar lives in its own flyout ([CalendarPanel]), opened from the clock.
 */
class NotificationCenter(
    private val context: Context,
    sharedPreferences: SharedPreferences,
    private val host: Host
) {
    interface Host {
        fun openNotification(sbn: StatusBarNotification, view: View)
        fun dismissNotification(sbn: StatusBarNotification)
        fun clearAllNotifications()

        /** Runs one of the notification's buttons, with the typed reply in [fillIn] if any. */
        fun sendAction(sbn: StatusBarNotification, action: Notification.Action, fillIn: Intent?)

        /** The reply field needs the keyboard: the window must take focus while [typing]. */
        fun setTyping(typing: Boolean)
    }

    private class Group(val packageName: String, val notifications: List<StatusBarNotification>)

    val view: LinearLayout =
        LayoutInflater.from(context).inflate(R.layout.notification_center, null) as LinearLayout
    val notificationsCard: View = view.findViewById(R.id.nc_notifications_card)

    private val list: RecyclerView = view.findViewById(R.id.nc_list)
    private val empty: TextView = view.findViewById(R.id.nc_empty)
    private val clearAll: TextView = view.findViewById(R.id.nc_clear_all)
    private val adapter = GroupAdapter()
    private val accent = ColorUtils.getAccentColor(sharedPreferences)
    private val inputMethodManager = context.getSystemService(InputMethodManager::class.java)

    // Reply being written (memory only, never stored); list updates wait until it is sent or closed
    private var replyKey: String? = null
    private var replyAction: Notification.Action? = null
    private var replyInput: RemoteInput? = null
    private var replyDraft: CharSequence = ""
    private var replyRow: View? = null
    private var pendingUpdate: List<StatusBarNotification>? = null

    init {
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        clearAll.setOnClickListener { host.clearAllNotifications() }
    }

    /** Shows the current notifications, newest app first (group summaries are skipped). */
    fun update(notifications: List<StatusBarNotification>) {
        // Redrawing now would wipe the reply being typed
        if (replyKey != null) {
            pendingUpdate = notifications
            return
        }
        val groups = notifications
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .sortedByDescending { it.postTime }
            .groupBy { it.packageName }
            .map { (pkg, items) -> Group(pkg, items) }
        adapter.groups = groups
        adapter.notifyDataSetChanged()
        list.isVisible = groups.isNotEmpty()
        empty.isVisible = groups.isEmpty()
        clearAll.isVisible = groups.any { group -> group.notifications.any { it.isClearable } }
    }

    // ---- notifications

    private inner class GroupAdapter : RecyclerView.Adapter<GroupAdapter.Holder>() {
        var groups: List<Group> = emptyList()

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val icon: ImageView = itemView.findViewById(R.id.group_icon)
            val app: TextView = itemView.findViewById(R.id.group_app)
            val time: TextView = itemView.findViewById(R.id.group_time)
            val items: LinearLayout = itemView.findViewById(R.id.group_items)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(context).inflate(R.layout.notification_group_entry, parent, false)
        )

        override fun getItemCount() = groups.size

        // Taps still reach the row: the swipe listener only takes over sideways moves
        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val group = groups[position]
            val pm = context.packageManager
            try {
                val info = pm.getApplicationInfo(group.packageName, 0)
                holder.icon.setImageDrawable(pm.getApplicationIcon(info))
                holder.app.text = pm.getApplicationLabel(info)
            } catch (_: PackageManager.NameNotFoundException) {
                holder.icon.setImageDrawable(null)
                holder.app.text = group.packageName
            }
            holder.time.text =
                StartMenuUtils.relativeTime(context, group.notifications.first().postTime)
            holder.items.removeAllViews()
            for (sbn in group.notifications) {
                val row = LayoutInflater.from(context)
                    .inflate(R.layout.notification_item_entry, holder.items, false)
                val extras = sbn.notification.extras
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)
                val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                row.findViewById<TextView>(R.id.item_title).apply {
                    this.text = title ?: holder.app.text
                }
                row.findViewById<TextView>(R.id.item_text).apply {
                    this.text = text
                    isVisible = !text.isNullOrBlank()
                }
                row.setOnClickListener { host.openNotification(sbn, it) }
                // Swiping sideways closes it, like the system shade (ongoing ones spring back)
                row.setOnTouchListener(
                    SwipeDismissTouchListener(canDismiss = sbn.isClearable, collapse = true) {
                        host.dismissNotification(sbn)
                    }
                )
                row.findViewById<View>(R.id.item_dismiss).apply {
                    isVisible = sbn.isClearable
                    setOnClickListener { host.dismissNotification(sbn) }
                }
                bindActions(row, sbn)
                holder.items.addView(row)
            }
        }
    }

    // ---- action buttons and reply

    /** The notification's own buttons, at most three like the system; "Responder" opens a field. */
    private fun bindActions(row: View, sbn: StatusBarNotification) {
        val bar = row.findViewById<LinearLayout>(R.id.item_actions)
        val actions = sbn.notification.actions.orEmpty()
            .filter { it.actionIntent != null && !it.isContextual }
            .take(MAX_ACTIONS)
        bar.removeAllViews()
        bar.isVisible = actions.isNotEmpty()
        val gap = Utils.dpToPx(context, 4)
        val padH = Utils.dpToPx(context, 8)
        val padV = Utils.dpToPx(context, 5)
        for (action in actions) {
            val input = action.remoteInputs?.firstOrNull { it.allowFreeFormInput }
            bar.addView(TextView(context).apply {
                text = action.title
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                textSize = 12f
                typeface = Typeface.create(typeface, 600, false)
                setTextColor(accent)
                background = ContextCompat.getDrawable(context, R.drawable.taskbar_button_bg)
                setPadding(padH, padV, padH, padV)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = gap }
                setOnClickListener {
                    if (input != null) openReply(row, sbn, action, input)
                    else host.sendAction(sbn, action, null)
                }
            })
        }
        // Scrolled away and back while replying: show the field again with what was typed
        if (sbn.key == replyKey) showReply(row, sbn)
        else row.findViewById<View>(R.id.item_reply).isVisible = false
    }

    private fun openReply(
        row: View, sbn: StatusBarNotification, action: Notification.Action, input: RemoteInput
    ) {
        if (replyKey != sbn.key) replyDraft = ""
        replyRow?.findViewById<View>(R.id.item_reply)?.isVisible = false
        replyKey = sbn.key
        replyAction = action
        replyInput = input
        host.setTyping(true)
        val field = showReply(row, sbn)
        field.requestFocus()
        // The window only takes focus after the change above reaches the system
        field.postDelayed({ inputMethodManager.showSoftInput(field, 0) }, KEYBOARD_DELAY_MS)
    }

    private fun showReply(row: View, sbn: StatusBarNotification): EditText {
        replyRow = row
        row.findViewById<View>(R.id.item_reply).isVisible = true
        val field = row.findViewById<EditText>(R.id.item_reply_text)
        field.hint = replyInput?.label?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.nc_reply_hint)
        field.setText(replyDraft)
        field.setSelection(field.length())
        field.doAfterTextChanged { replyDraft = it ?: "" }
        field.setOnEditorActionListener { _, actionId, event ->
            // Send key of the on-screen keyboard, or Enter on a physical one
            val enter = actionId == EditorInfo.IME_NULL && event?.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                sendReply(sbn)
                true
            } else false
        }
        field.setOnKeyListener { _, keyCode, event ->
            val close = keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BACK
            if (close && event.action == KeyEvent.ACTION_UP) closeReply()
            close
        }
        row.findViewById<View>(R.id.item_reply_send).setOnClickListener { sendReply(sbn) }
        return field
    }

    private fun sendReply(sbn: StatusBarNotification) {
        val text = replyDraft.toString().trim()
        val action = replyAction
        val input = replyInput
        if (text.isEmpty() || action == null || input == null) return
        // The same answer the system shade gives the app: its RemoteInput key with the text
        val fillIn = Intent()
        RemoteInput.addResultsToIntent(
            arrayOf(input), fillIn, Bundle().apply { putCharSequence(input.resultKey, text) }
        )
        RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)
        closeReply()
        host.sendAction(sbn, action, fillIn)
    }

    /** Hides the reply field, forgets the draft and applies the updates that waited. */
    fun closeReply() {
        if (replyKey == null) return
        replyRow?.let { row ->
            row.findViewById<View>(R.id.item_reply).isVisible = false
            inputMethodManager.hideSoftInputFromWindow(row.windowToken, 0)
        }
        replyRow = null
        replyKey = null
        replyAction = null
        replyInput = null
        replyDraft = ""
        host.setTyping(false)
        pendingUpdate?.let {
            pendingUpdate = null
            update(it)
        }
    }

    private companion object {
        /** The system shade shows at most three buttons per notification. */
        const val MAX_ACTIONS = 3
        const val KEYBOARD_DELAY_MS = 150L
    }
}
