@file:Suppress("DEPRECATION")

package com.example.voiceanswer

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Свой экран контакта: создать, изменить, позвонить, написать, в избранное, удалить. */
class ContactEditActivity : BaseActivity() {

    companion object {
        const val EXTRA_ID = "contact_id"
        const val EXTRA_NUMBER = "prefill_number"
        const val EXTRA_NAME = "prefill_name"
    }

    private var contact: Contact? = null
    private lateinit var nameInput: EditText
    private lateinit var numberInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id >= 0) contact = Repo.contact(this, id)
        build()
        if (!Perms.has(this, Manifest.permission.WRITE_CONTACTS)) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_CONTACTS), 1)
        }
    }

    private fun input(hint: String, value: String, phone: Boolean): EditText {
        val a = this
        val e = EditText(a)
        e.hint = hint
        e.setHintTextColor(Theme.sub)
        e.setTextColor(Theme.text)
        e.textSize = 18f
        e.isSingleLine = true
        e.inputType = if (phone) InputType.TYPE_CLASS_PHONE else (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        e.setText(value)
        e.background = Ui.rounded(Theme.card, Ui.dpf(a, 18f))
        e.setPadding(Ui.dp(a, 18), Ui.dp(a, 15), Ui.dp(a, 18), Ui.dp(a, 15))
        return e
    }

    private fun build() {
        val a = this
        val c = contact
        val scroll = ScrollView(a)
        scroll.isVerticalScrollBarEnabled = false
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(a, 20), 0, Ui.dp(a, 20), Ui.dp(a, 32))
        scroll.addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        val top = LinearLayout(a)
        top.gravity = Gravity.CENTER_VERTICAL
        val back = Ui.roundButton(a, 46, ColorDrawable(Color.TRANSPARENT), R.drawable.ic_arrow_back, Theme.text, 24)
        back.setOnClickListener { finish() }
        top.addView(back)
        top.addView(
            Ui.light(a, if (c == null) "Новый контакт" else "Контакт", 28f, Theme.text),
            Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), Ui.dp(a, 6), 0, 0, 0)
        )
        col.addView(top, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), -Ui.dp(a, 8), Ui.dp(a, 8), 0, Ui.dp(a, 8)))

        val avatar = AvatarView(a, Ui.dp(a, 110), 40f)
        avatar.bind(c?.name, c?.photo)
        col.addView(avatar, Ui.margins(Ui.lp(Ui.dp(a, 110), Ui.dp(a, 110)), 0, Ui.dp(a, 8), 0, Ui.dp(a, 20)))
        (avatar.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL

        nameInput = input("Имя", c?.name ?: intent.getStringExtra(EXTRA_NAME).orEmpty(), false)
        numberInput = input("Телефон", c?.number ?: intent.getStringExtra(EXTRA_NUMBER).orEmpty(), true)
        col.addView(nameInput, Ui.lp(Ui.MATCH, Ui.WRAP))
        col.addView(numberInput, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 12), 0, 0))

        col.addView(pill("Сохранить", true) { save() }, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 20), 0, 0))

        if (c != null) {
            col.addView(pill("Позвонить", false) { call(c.number) }, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 10), 0, 0))
            col.addView(pill("Написать SMS", false) {
                Perms.open(a, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.number))))
            }, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 10), 0, 0))
            col.addView(pill(if (c.starred) "Убрать из избранного" else "В избранное ★", false) {
                if (Repo.setStarred(a, c.id, !c.starred)) {
                    finish()
                } else {
                    Toast.makeText(a, "Нужно разрешение на изменение контактов", Toast.LENGTH_LONG).show()
                }
            }, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 10), 0, 0))
            val del = pill("Удалить контакт", false) { confirmDelete(c) }
            (del as TextView).setTextColor(Theme.RED)
            col.addView(del, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(a, 10), 0, 0))
        }
        setContentView(scroll)
    }

    private fun pill(label: String, primary: Boolean, onClick: () -> Unit): View {
        val a = this
        val b = Ui.text(a, label, 16f, if (primary) Color.WHITE else Theme.text, primary)
        b.gravity = Gravity.CENTER
        b.setPadding(Ui.dp(a, 18), Ui.dp(a, 14), Ui.dp(a, 18), Ui.dp(a, 14))
        val fill = if (primary) Ui.accentFill(Ui.dpf(a, 24f)) else Ui.rounded(Theme.card, Ui.dpf(a, 24f))
        b.background = Ui.ripple(fill, Ui.dpf(a, 24f))
        b.isClickable = true
        b.setOnClickListener { onClick() }
        return b
    }

    private fun call(number: String) {
        if (!Perms.has(this, Manifest.permission.CALL_PHONE)) {
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 2)
            return
        }
        try {
            getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle())
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось позвонить", Toast.LENGTH_SHORT).show()
        }
    }

    private fun save() {
        val name = nameInput.text.toString().trim()
        val number = numberInput.text.toString().trim()
        if (name.isEmpty() || number.isEmpty()) {
            Toast.makeText(this, "Введите имя и номер", Toast.LENGTH_SHORT).show()
            return
        }
        if (!Perms.has(this, Manifest.permission.WRITE_CONTACTS)) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_CONTACTS), 1)
            Toast.makeText(this, "Разрешите изменение контактов и нажмите «Сохранить» ещё раз", Toast.LENGTH_LONG).show()
            return
        }
        val c = contact
        val ok = if (c == null) Repo.insertContact(this, name, number) else Repo.updateContact(this, c.id, c.dataId, name, number)
        if (ok) {
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            Toast.makeText(this, "Не удалось сохранить контакт", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete(c: Contact) {
        Ui.sheet(
            this, "Удалить «${c.name}»?", "Контакт исчезнет с телефона",
            listOf(Triple(R.drawable.ic_delete, "Да, удалить") {
                if (Repo.deleteContact(this, c.id)) {
                    finish()
                } else {
                    Toast.makeText(this, "Не удалось удалить", Toast.LENGTH_LONG).show()
                }
            })
        )
    }
}
