@file:Suppress("DEPRECATION")

package com.example.voiceanswer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** Главный экран: «Недавние», «Контакты» и набор номера — как в обычном телефоне. */
class MainActivity : BaseActivity() {

    companion object {
        const val REQ_PERMS = 10
        const val REQ_ROLE = 11
        const val REQ_CALL = 12
    }

    private class Row(val header: String?, val contact: Contact?)

    private inner class Banner {
        val box = LinearLayout(this@MainActivity)
        val title: TextView
        val body: TextView
        val button: TextView

        init {
            val a = this@MainActivity
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(Ui.dp(a, 18), Ui.dp(a, 16), Ui.dp(a, 18), Ui.dp(a, 16))
            box.background = Ui.rounded(Theme.card, Ui.dpf(a, 22f))
            title = Ui.text(a, "", 16f, Theme.text, true)
            body = Ui.text(a, "", 14f, Theme.sub)
            button = Ui.text(a, "", 15f, Color.WHITE, true)
            button.gravity = Gravity.CENTER
            button.setPadding(Ui.dp(a, 20), Ui.dp(a, 11), Ui.dp(a, 20), Ui.dp(a, 11))
            button.background = Ui.ripple(Ui.accentFill(Ui.dpf(a, 22f)), Ui.dpf(a, 22f))
            button.isClickable = true
            box.addView(title, Ui.lp(Ui.MATCH, Ui.WRAP))
            box.addView(body, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 4), 0, Ui.dp(a, 12)))
            box.addView(button, Ui.lp(Ui.WRAP, Ui.WRAP))
        }

        fun update() {
            val a = this@MainActivity
            val missing = Perms.missing(a)
            when {
                missing.isNotEmpty() -> {
                    box.visibility = View.VISIBLE
                    title.text = "Нужны разрешения"
                    body.text = "Журнал звонков, контакты, микрофон и телефон — без них приложение не сможет работать."
                    button.text = "Разрешить"
                    button.setOnClickListener { requestPermissions(missing.toTypedArray(), REQ_PERMS) }
                }
                !Perms.isDefaultDialer(a) -> {
                    box.visibility = View.VISIBLE
                    title.text = "Сделайте «Голосовой ответ» основным телефоном"
                    body.text = "Тогда этот экран звонка и голосовое управление заработают при каждом вызове."
                    button.text = "Сделать основным"
                    button.setOnClickListener { Perms.requestDefaultDialer(a, REQ_ROLE) }
                }
                else -> box.visibility = View.GONE
            }
        }
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var recentsPage: LinearLayout
    private lateinit var contactsPage: LinearLayout
    private lateinit var recentsEmpty: TextView
    private lateinit var tabAll: TextView
    private lateinit var tabMissed: TextView
    private lateinit var searchBox: EditText
    private lateinit var contactsSub: TextView
    private lateinit var fabDial: View
    private lateinit var fabAdd: View
    private lateinit var navIconRecents: FrameLayout
    private lateinit var navIconContacts: FrameLayout
    private lateinit var navTextRecents: TextView
    private lateinit var navTextContacts: TextView
    private lateinit var dialPanel: LinearLayout
    private lateinit var dialNumber: TextView
    private lateinit var dialHint: TextView
    private lateinit var contactsEmpty: TextView

    private val banners = ArrayList<Banner>()
    private val dialed = StringBuilder()
    private var page = 0
    private var dialOpen = false
    private var log: List<LogEntry> = emptyList()
    private var contacts: List<Contact> = emptyList()
    private var missedOnly = false
    private var logQuery = ""
    private var contactQuery = ""
    private var pendingCall: String? = null
    private var photoByNumber: Map<String, String> = emptyMap()

    private val logAdapter = LogAdapter()
    private val contactAdapter = ContactAdapter()

    // ------------------------------------------------------------------ жизненный цикл

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val missing = Perms.missing(this)
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)
        Calls.warmUp(this)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        when (i.action) {
            Intent.ACTION_DIAL, Intent.ACTION_VIEW -> {
                val d = i.data
                if (d != null && d.scheme == "tel") openDial(Uri.decode(d.schemeSpecificPart ?: "")) else if (i.action == Intent.ACTION_DIAL) openDial("")
            }
            "android.intent.action.CALL_BUTTON" -> openDial("")
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        for (b in banners) b.update()
        reload()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        for (b in banners) b.update()
        reload()
        if (requestCode == REQ_CALL) {
            val n = pendingCall
            pendingCall = null
            if (n != null && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) placeCall(n)
        }
        Calls.warmUp(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        for (b in banners) b.update()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            dialOpen -> closeDial()
            searchBox.visibility == View.VISIBLE -> toggleSearch()
            page != 0 -> showPage(0)
            else -> super.onBackPressed()
        }
    }

    private fun reload() {
        io.execute {
            val c = Repo.contacts(this)
            val photos = HashMap<String, String>()
            val names = HashMap<String, String>()
            for (ct in c) {
                val k = Repo.numKey(ct.number)
                if (k.isEmpty()) continue
                names[k] = ct.name
                if (ct.photo != null) photos[k] = ct.photo
            }
            // имя из контактов важнее старой записи в журнале
            val l = Repo.callLog(this).map { e ->
                val nm = names[Repo.numKey(e.number)]
                if (nm != null && nm != e.name) LogEntry(e.id, e.number, nm, e.type, e.date, e.label) else e
            }
            main.post {
                if (isDestroyed) return@post
                photoByNumber = photos
                log = l
                contacts = c
                applyLog()
                applyContacts()
            }
        }
    }

    // ------------------------------------------------------------------ построение интерфейса

    private fun iconButton(res: Int, onClick: () -> Unit): FrameLayout {
        val b = Ui.roundButton(this, 46, ColorDrawable(Color.TRANSPARENT), res, Theme.text, 24)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun buildUi() {
        val a = this
        val root = FrameLayout(a)
        root.setBackgroundColor(Theme.bg)

        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        val pages = FrameLayout(a)
        recentsPage = buildRecents()
        contactsPage = buildContacts()
        pages.addView(recentsPage, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        pages.addView(contactsPage, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        col.addView(pages, Ui.lp(Ui.MATCH, 0, 1f))
        col.addView(buildNav(), Ui.lp(Ui.MATCH, Ui.WRAP))
        root.addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        val fabLp = { FrameLayout.LayoutParams(Ui.dp(a, 62), Ui.dp(a, 62), Gravity.BOTTOM or Gravity.END).also {
            it.setMargins(0, 0, Ui.dp(a, 20), Ui.dp(a, 84))
        } }
        val dial = Ui.roundButton(a, 62, Ui.oval(Theme.card2), R.drawable.ic_dialpad, Theme.accent, 26)
        dial.setOnClickListener { openDial("") }
        val dialIcon = dial.getChildAt(0) as ImageView
        onAccent { c -> dialIcon.setColorFilter(c) }
        fabDial = dial
        root.addView(dial, fabLp())

        val add = Ui.roundButton(a, 62, Ui.oval(Theme.card2), R.drawable.ic_person_add, Theme.accent, 26)
        add.setOnClickListener {
            startActivity(Intent(a, ContactEditActivity::class.java))
        }
        val addIcon = add.getChildAt(0) as ImageView
        onAccent { c -> addIcon.setColorFilter(c) }
        fabAdd = add
        root.addView(add, fabLp())

        dialPanel = buildDial()
        dialPanel.visibility = View.GONE
        root.addView(dialPanel, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        setContentView(root)
        showPage(0)
    }

    private fun header(title: String, withSearch: Boolean): LinearLayout {
        val a = this
        val h = LinearLayout(a)
        h.orientation = LinearLayout.VERTICAL
        val top = LinearLayout(a)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        if (withSearch) top.addView(iconButton(R.drawable.ic_search) { toggleSearch() })
        top.addView(iconButton(R.drawable.ic_settings) { startActivity(Intent(a, SettingsActivity::class.java)) })
        h.addView(top, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 8), Ui.dp(a, 10), 0))
        val t = Ui.light(a, title, 38f, Theme.text)
        h.addView(t, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), Ui.dp(a, 24), Ui.dp(a, 4), 0, 0))
        return h
    }

    private fun searchField(hint: String, onChange: (String) -> Unit): EditText {
        val a = this
        val e = EditText(a)
        e.hint = hint
        e.setHintTextColor(Theme.sub)
        e.setTextColor(Theme.text)
        e.textSize = 16f
        e.isSingleLine = true
        e.background = Ui.rounded(Theme.card, Ui.dpf(a, 18f))
        e.setPadding(Ui.dp(a, 18), Ui.dp(a, 13), Ui.dp(a, 18), Ui.dp(a, 13))
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onChange(s?.toString() ?: "")
            }
        })
        return e
    }

    private fun footer(l: ListView) {
        val f = View(this)
        f.layoutParams = android.widget.AbsListView.LayoutParams(Ui.MATCH, Ui.dp(this, 150))
        l.addFooterView(f, null, false)
    }

    private fun buildRecents(): LinearLayout {
        val a = this
        val page = LinearLayout(a)
        page.orientation = LinearLayout.VERTICAL
        page.addView(header("Недавние", true), Ui.lp(Ui.MATCH, Ui.WRAP))

        searchBox = searchField("Поиск по журналу") {
            logQuery = it
            applyLog()
        }
        searchBox.visibility = View.GONE
        page.addView(searchBox, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), Ui.dp(a, 4)))

        val tabs = LinearLayout(a)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabAll = Ui.text(a, "Все", 18f, Theme.text, true)
        tabMissed = Ui.text(a, "Пропущенные", 18f, Theme.sub, true)
        for ((i, t) in listOf(tabAll, tabMissed).withIndex()) {
            t.setPadding(Ui.dp(a, 4), Ui.dp(a, 10), Ui.dp(a, 4), Ui.dp(a, 10))
            t.isClickable = true
            t.setOnClickListener {
                missedOnly = i == 1
                styleTabs()
                applyLog()
            }
        }
        tabs.addView(tabAll, Ui.lp(0, Ui.WRAP, 1f))
        tabs.addView(tabMissed, Ui.lp(0, Ui.WRAP, 1f))
        tabAll.gravity = Gravity.CENTER
        tabMissed.gravity = Gravity.CENTER
        page.addView(tabs, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), Ui.dp(a, 20), Ui.dp(a, 14), Ui.dp(a, 20), Ui.dp(a, 4)))
        styleTabs()

        val banner = Banner()
        banners.add(banner)
        page.addView(banner.box, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), Ui.dp(a, 20), Ui.dp(a, 10), Ui.dp(a, 20), Ui.dp(a, 4)))

        val holder = FrameLayout(a)
        val list = ListView(a)
        footer(list)
        list.divider = null
        list.dividerHeight = 0
        list.selector = ColorDrawable(Color.TRANSPARENT)
        list.isVerticalScrollBarEnabled = false
        list.adapter = logAdapter
        holder.addView(list, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        recentsEmpty = Ui.text(a, "", 16f, Theme.sub)
        recentsEmpty.gravity = Gravity.CENTER
        recentsEmpty.setPadding(Ui.dp(a, 32), 0, Ui.dp(a, 32), Ui.dp(a, 80))
        holder.addView(recentsEmpty, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        page.addView(holder, Ui.lp(Ui.MATCH, 0, 1f))
        return page
    }

    private fun styleTabs() {
        tabAll.setTextColor(if (!missedOnly) Theme.accent else Theme.sub)
        tabMissed.setTextColor(if (missedOnly) Theme.accent else Theme.sub)
        // Для радужной темы цвет подпишется заново на каждом кадре
    }

    private fun buildContacts(): LinearLayout {
        val a = this
        val page = LinearLayout(a)
        page.orientation = LinearLayout.VERTICAL
        page.addView(header("Контакты", false), Ui.lp(Ui.MATCH, Ui.WRAP))
        contactsSub = Ui.text(a, "", 15f, Theme.sub)
        page.addView(contactsSub, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), Ui.dp(a, 25), 0, 0, 0))

        val search = searchField("Поиск") {
            contactQuery = it
            applyContacts()
        }
        page.addView(search, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), Ui.dp(a, 20), Ui.dp(a, 14), Ui.dp(a, 20), Ui.dp(a, 6)))

        val banner = Banner()
        banners.add(banner)
        page.addView(banner.box, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), Ui.dp(a, 4)))

        val holder = FrameLayout(a)
        val list = ListView(a)
        footer(list)
        list.divider = null
        list.dividerHeight = 0
        list.selector = ColorDrawable(Color.TRANSPARENT)
        list.isVerticalScrollBarEnabled = false
        list.adapter = contactAdapter
        holder.addView(list, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        contactsEmpty = Ui.text(a, "", 16f, Theme.sub)
        contactsEmpty.gravity = Gravity.CENTER
        contactsEmpty.setPadding(Ui.dp(a, 32), 0, Ui.dp(a, 32), Ui.dp(a, 80))
        holder.addView(contactsEmpty, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        page.addView(holder, Ui.lp(Ui.MATCH, 0, 1f))
        return page
    }

    private fun navItem(res: Int, label: String, onClick: () -> Unit, out: (FrameLayout, TextView) -> Unit): View {
        val a = this
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 8))
        val circle = FrameLayout(a)
        val icon = Ui.icon(a, res, Color.WHITE)
        circle.addView(icon, FrameLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24), Gravity.CENTER))
        box.addView(circle, Ui.lp(Ui.dp(a, 52), Ui.dp(a, 34)))
        val t = Ui.text(a, label, 12f, Theme.sub)
        box.addView(t, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, Ui.dp(a, 2), 0, 0))
        box.isClickable = true
        box.setOnClickListener { onClick() }
        out(circle, t)
        return box
    }

    private fun buildNav(): View {
        val a = this
        val wrap = LinearLayout(a)
        wrap.orientation = LinearLayout.VERTICAL
        val line = View(a)
        line.setBackgroundColor(Theme.line)
        wrap.addView(line, Ui.lp(Ui.MATCH, 1))
        val nav = LinearLayout(a)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.setBackgroundColor(Theme.bg)
        nav.addView(navItem(R.drawable.ic_history, "Недавние", { showPage(0) }) { c, t ->
            navIconRecents = c
            navTextRecents = t
        }, Ui.lp(0, Ui.WRAP, 1f))
        nav.addView(navItem(R.drawable.ic_person, "Контакты", { showPage(1) }) { c, t ->
            navIconContacts = c
            navTextContacts = t
        }, Ui.lp(0, Ui.WRAP, 1f))
        wrap.addView(nav, Ui.lp(Ui.MATCH, Ui.WRAP))
        onAccent { styleNav() }
        return wrap
    }

    private fun styleNav() {
        val a = this
        fun apply(circle: FrameLayout, text: TextView, selected: Boolean) {
            val icon = circle.getChildAt(0) as ImageView
            if (selected) {
                circle.background = Ui.accentFill(Ui.dpf(a, 17f))
                icon.setColorFilter(Color.WHITE)
                text.setTextColor(Theme.accent)
            } else {
                circle.background = null
                icon.setColorFilter(Theme.sub)
                text.setTextColor(Theme.sub)
            }
        }
        if (!::navIconRecents.isInitialized || !::navIconContacts.isInitialized) return
        apply(navIconRecents, navTextRecents, page == 0)
        apply(navIconContacts, navTextContacts, page == 1)
        if (::tabAll.isInitialized) styleTabs()
    }

    private fun showPage(i: Int) {
        page = i
        recentsPage.visibility = if (i == 0) View.VISIBLE else View.GONE
        contactsPage.visibility = if (i == 1) View.VISIBLE else View.GONE
        fabDial.visibility = if (i == 0 && !dialOpen) View.VISIBLE else View.GONE
        fabAdd.visibility = if (i == 1 && !dialOpen) View.VISIBLE else View.GONE
        styleNav()
    }

    private fun toggleSearch() {
        val show = searchBox.visibility != View.VISIBLE
        searchBox.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            searchBox.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(searchBox, 0)
        } else {
            searchBox.setText("")
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(searchBox.windowToken, 0)
        }
    }

    // ------------------------------------------------------------------ данные → списки

    private fun applyLog() {
        val q = logQuery.trim().lowercase()
        val list = log.filter {
            (!missedOnly || it.type == CallLog.Calls.MISSED_TYPE) &&
                (q.isEmpty() || it.title.lowercase().contains(q) || it.number.contains(q))
        }
        logAdapter.items = list
        logAdapter.notifyDataSetChanged()
        recentsEmpty.text = when {
            !Perms.has(this, Manifest.permission.READ_CALL_LOG) -> "Нет доступа к журналу звонков.\nНажмите «Разрешить» выше."
            missedOnly -> "Пропущенных звонков нет"
            q.isNotEmpty() -> "Ничего не найдено"
            else -> "Звонков пока нет"
        }
        recentsEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun applyContacts() {
        val q = contactQuery.trim().lowercase()
        val rows = ArrayList<Row>()
        if (q.isNotEmpty()) {
            val qd = q.filter { it.isDigit() }
            for (c in contacts) {
                if (c.name.lowercase().contains(q) || (qd.isNotEmpty() && c.number.filter { it.isDigit() }.contains(qd))) {
                    rows.add(Row(null, c))
                }
            }
        } else {
            val fav = contacts.filter { it.starred }
            if (fav.isNotEmpty()) {
                rows.add(Row("★", null))
                for (c in fav) rows.add(Row(null, c))
            }
            var last = ""
            for (c in contacts) {
                val ch = c.name.firstOrNull()
                val letter = if (ch != null && ch.isLetter()) ch.uppercaseChar().toString() else "#"
                if (letter != last) {
                    rows.add(Row(letter, null))
                    last = letter
                }
                rows.add(Row(null, c))
            }
        }
        contactAdapter.items = rows
        contactAdapter.notifyDataSetChanged()
        val n = contacts.size
        contactsSub.text = "$n " + Logic.plural(n, "контакт", "контакта", "контактов")
        contactsEmpty.text = if (!Perms.has(this, Manifest.permission.READ_CONTACTS)) "Нет доступа к контактам.\nНажмите «Разрешить» выше." else "Ничего не найдено"
        contactsEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ адаптеры

    private inner class LogAdapter : BaseAdapter() {
        var items: List<LogEntry> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val h = (convertView?.tag as? LogHolder) ?: LogHolder().also { it.root.tag = it }
            h.bind(items[position])
            return h.root
        }
    }

    private inner class LogHolder {
        val a = this@MainActivity
        val root = LinearLayout(a)
        val avatar = AvatarView(a, Ui.dp(a, 46), 17f)
        val typeIcon = ImageView(a)
        val title = Ui.text(a, "", 18f, Theme.text)
        val sub = Ui.text(a, "", 14f, Theme.sub)
        val time = Ui.text(a, "", 14f, Theme.sub)
        val info = FrameLayout(a)

        init {
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            root.setPadding(Ui.dp(a, 20), Ui.dp(a, 10), Ui.dp(a, 12), Ui.dp(a, 10))
            root.background = Ui.ripple(ColorDrawable(Color.TRANSPARENT), 0f)
            root.layoutParams = android.widget.AbsListView.LayoutParams(Ui.MATCH, Ui.WRAP)
            root.addView(avatar, Ui.lp(Ui.dp(a, 46), Ui.dp(a, 46)))
            val col = LinearLayout(a)
            col.orientation = LinearLayout.VERTICAL
            title.maxLines = 1
            title.ellipsize = TextUtils.TruncateAt.END
            sub.maxLines = 1
            sub.ellipsize = TextUtils.TruncateAt.END
            col.addView(title, Ui.lp(Ui.MATCH, Ui.WRAP))
            val line2 = LinearLayout(a)
            line2.orientation = LinearLayout.HORIZONTAL
            line2.gravity = Gravity.CENTER_VERTICAL
            line2.addView(typeIcon, Ui.margins(Ui.lp(Ui.dp(a, 16), Ui.dp(a, 16)), 0, 0, Ui.dp(a, 6), 0))
            line2.addView(sub, Ui.lp(Ui.WRAP, Ui.WRAP))
            col.addView(line2, Ui.lp(Ui.MATCH, Ui.WRAP))
            root.addView(col, Ui.margins(Ui.lp(0, Ui.WRAP, 1f), Ui.dp(a, 14), 0, Ui.dp(a, 8), 0))
            root.addView(time, Ui.lp(Ui.WRAP, Ui.WRAP))
            val i = Ui.icon(a, R.drawable.ic_info, Theme.sub)
            info.addView(i, FrameLayout.LayoutParams(Ui.dp(a, 26), Ui.dp(a, 26), Gravity.CENTER))
            info.background = Ui.ripple(ColorDrawable(Color.TRANSPARENT), 0f, true)
            info.isClickable = true
            root.addView(info, Ui.margins(Ui.lp(Ui.dp(a, 46), Ui.dp(a, 46)), Ui.dp(a, 6), 0, 0, 0))
        }

        fun bind(e: LogEntry) {
            typeIcon.setImageResource(
                when {
                    e.missed -> R.drawable.ic_call_missed
                    e.outgoing -> R.drawable.ic_call_made
                    else -> R.drawable.ic_call_received
                }
            )
            typeIcon.setColorFilter(if (e.missed) Theme.RED else Theme.sub)
            avatar.bind(e.name, photoByNumber[Repo.numKey(e.number)])
            title.text = e.title
            title.setTextColor(if (e.missed) Theme.RED else Theme.text)
            val kind = when {
                e.missed -> "Пропущенный"
                e.outgoing -> "Исходящий"
                else -> "Входящий"
            }
            sub.text = if (e.label.isEmpty()) kind else kind + " · " + e.label
            time.text = Repo.when_(e.date)
            root.setOnClickListener { placeCall(e.number) }
            info.setOnClickListener { logSheet(e) }
        }
    }

    private inner class ContactAdapter : BaseAdapter() {
        var items: List<Row> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (items[position].header != null) 0 else 1
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = items[position]
            if (row.header != null) {
                val t = (convertView as? TextView) ?: Ui.text(this@MainActivity, "", 16f, Theme.sub, true).also {
                    it.setPadding(Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 14), 0, Ui.dp(this@MainActivity, 4))
                    it.layoutParams = android.widget.AbsListView.LayoutParams(Ui.MATCH, Ui.WRAP)
                }
                t.text = row.header
                return t
            }
            val h = (convertView?.tag as? ContactHolder) ?: ContactHolder().also { it.root.tag = it }
            h.bind(row.contact!!)
            return h.root
        }
    }

    private inner class ContactHolder {
        val a = this@MainActivity
        val root = LinearLayout(a)
        val avatar = AvatarView(a, Ui.dp(a, 46), 17f)
        val name = Ui.text(a, "", 18f, Theme.text)

        init {
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            root.setPadding(Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), Ui.dp(a, 8))
            root.background = Ui.ripple(ColorDrawable(Color.TRANSPARENT), 0f)
            root.layoutParams = android.widget.AbsListView.LayoutParams(Ui.MATCH, Ui.WRAP)
            root.addView(avatar, Ui.lp(Ui.dp(a, 46), Ui.dp(a, 46)))
            name.maxLines = 1
            name.ellipsize = TextUtils.TruncateAt.END
            root.addView(name, Ui.margins(Ui.lp(0, Ui.WRAP, 1f), Ui.dp(a, 16), 0, 0, 0))
        }

        fun bind(c: Contact) {
            avatar.bind(c.name, c.photo)
            name.text = c.name
            root.setOnClickListener { contactSheet(c) }
        }
    }

    // ------------------------------------------------------------------ шторки с действиями

    private fun smsIntent(number: String) = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))

    private fun copy(number: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Номер", number))
        Toast.makeText(this, "Номер скопирован", Toast.LENGTH_SHORT).show()
    }

    private fun logSheet(e: LogEntry) {
        if (e.number.isBlank()) return
        val actions = ArrayList<Triple<Int, String, () -> Unit>>()
        actions.add(Triple(R.drawable.ic_call, "Позвонить") { placeCall(e.number) })
        actions.add(Triple(R.drawable.ic_message, "Написать SMS") { Perms.open(this, smsIntent(e.number)) })
        actions.add(Triple(R.drawable.ic_content_copy, "Скопировать номер") { copy(e.number) })
        if (e.name.isNullOrBlank()) {
            actions.add(Triple(R.drawable.ic_person_add, "Добавить в контакты") {
                val i = Intent(this, ContactEditActivity::class.java)
                i.putExtra(ContactEditActivity.EXTRA_NUMBER, e.number)
                startActivity(i)
            })
        }
        actions.add(Triple(R.drawable.ic_delete, "Удалить из журнала") {
            io.execute {
                Repo.deleteLog(this, e.id)
                reload()
            }
        })
        Ui.sheet(this, e.title, Calls.formatNumber(e.number), actions)
    }

    private fun contactSheet(c: Contact) {
        val actions = ArrayList<Triple<Int, String, () -> Unit>>()
        actions.add(Triple(R.drawable.ic_call, "Позвонить") { placeCall(c.number) })
        actions.add(Triple(R.drawable.ic_message, "Написать SMS") { Perms.open(this, smsIntent(c.number)) })
        actions.add(Triple(R.drawable.ic_content_copy, "Скопировать номер") { copy(c.number) })
        actions.add(Triple(R.drawable.ic_person, "Изменить контакт") {
            val i = Intent(this, ContactEditActivity::class.java)
            i.putExtra(ContactEditActivity.EXTRA_ID, c.id)
            startActivity(i)
        })
        Ui.sheet(this, c.name, Calls.formatNumber(c.number), actions)
    }

    // ------------------------------------------------------------------ звонок и набор номера

    private fun placeCall(number: String) {
        if (number.isBlank()) return
        if (!Perms.has(this, Manifest.permission.CALL_PHONE)) {
            pendingCall = number
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), REQ_CALL)
            return
        }
        try {
            getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle())
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось позвонить", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildDial(): LinearLayout {
        val a = this
        val p = LinearLayout(a)
        p.orientation = LinearLayout.VERTICAL
        p.gravity = Gravity.CENTER_HORIZONTAL
        p.setBackgroundColor(Theme.bg)
        p.isClickable = true // не пропускать нажатия на списки под панелью

        val top = LinearLayout(a)
        top.gravity = Gravity.START
        top.addView(iconButton(R.drawable.ic_close) { closeDial() })
        p.addView(top, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), Ui.dp(a, 8), Ui.dp(a, 8), 0, 0))

        dialNumber = Ui.light(a, "", 38f, Theme.text)
        dialNumber.gravity = Gravity.CENTER
        dialNumber.maxLines = 1
        dialNumber.ellipsize = TextUtils.TruncateAt.START
        p.addView(dialNumber, Ui.margins(Ui.lp(Ui.MATCH, Ui.dp(a, 64)), Ui.dp(a, 16), Ui.dp(a, 12), Ui.dp(a, 16), 0))
        dialHint = Ui.text(a, "", 16f, Theme.sub)
        dialHint.gravity = Gravity.CENTER
        p.addView(dialHint, Ui.lp(Ui.MATCH, Ui.dp(a, 26)))

        p.addView(View(a), Ui.lp(Ui.MATCH, 0, 1f))
        p.addView(
            KeypadView(a, 74, Theme.text, Theme.card,
                onDown = { ch -> dialed.append(ch); updateDial() },
                onUp = {},
                onLong = { _ ->
                    if (dialed.isNotEmpty() && dialed.last() == '0') dialed.setLength(dialed.length - 1)
                    dialed.append('+')
                    updateDial()
                }),
            Ui.lp(Ui.WRAP, Ui.WRAP)
        )

        val bottom = LinearLayout(a)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.gravity = Gravity.CENTER
        bottom.addView(View(a), Ui.lp(Ui.dp(a, 74), Ui.dp(a, 74)))
        val call = Ui.roundButton(a, 74, Ui.oval(Theme.GREEN), R.drawable.ic_call, Color.WHITE, 32)
        call.setOnClickListener {
            val n = dialed.toString()
            if (n.isNotEmpty()) {
                placeCall(n)
                closeDial()
            }
        }
        bottom.addView(call, Ui.margins(Ui.lp(Ui.dp(a, 74), Ui.dp(a, 74)), Ui.dp(a, 16), 0, Ui.dp(a, 16), 0))
        val back = Ui.roundButton(a, 74, ColorDrawable(Color.TRANSPARENT), R.drawable.ic_backspace, Theme.sub, 28)
        back.setOnClickListener {
            if (dialed.isNotEmpty()) dialed.setLength(dialed.length - 1)
            updateDial()
        }
        back.setOnLongClickListener {
            dialed.setLength(0)
            updateDial()
            true
        }
        bottom.addView(back)
        p.addView(bottom, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, Ui.dp(a, 8), 0, Ui.dp(a, 20)))
        return p
    }

    private fun updateDial() {
        dialNumber.text = dialed.toString()
        val digits = dialed.filter { it.isDigit() }.toString()
        var hint = ""
        if (digits.length >= 3) {
            val c = contacts.firstOrNull { it.number.filter { ch -> ch.isDigit() }.endsWith(digits) }
            if (c != null) hint = c.name
        }
        dialHint.text = hint
    }

    private fun openDial(prefill: String) {
        dialed.setLength(0)
        dialed.append(prefill)
        updateDial()
        dialOpen = true
        dialPanel.visibility = View.VISIBLE
        dialPanel.translationY = resources.displayMetrics.heightPixels.toFloat()
        dialPanel.animate().translationY(0f).setDuration(220).start()
        showPage(page)
    }

    private fun closeDial() {
        dialOpen = false
        dialPanel.animate().translationY(resources.displayMetrics.heightPixels.toFloat()).setDuration(200).withEndAction {
            if (!dialOpen) dialPanel.visibility = View.GONE
        }.start()
        showPage(page)
    }
}
