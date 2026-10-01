package com.arionacc.snipbox

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils

fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density + 0.5f).toInt()

object Ui {
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    val BG = 0xFF0E0E0E.toInt()
    val SURFACE = 0xFF161616.toInt()
    val CARD = 0xFF1E1E1E.toInt()
    val FIELD = 0xFF262626.toInt()
    val STROKE = 0xFF333333.toInt()
    val ACCENT = 0xFFE8E8E8.toInt()
    val TEXT = 0xFFEDEDED.toInt()
    val TEXT_DIM = 0xFF8A8A8A.toInt()

    fun roundRect(
        color: Int,
        radius: Float,
        strokeColor: Int = 0,
        strokeWidth: Int = 0
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

    fun pressable(
        color: Int,
        radius: Float,
        strokeColor: Int = 0,
        strokeWidth: Int = 0
    ): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(0x33FFFFFF),
        roundRect(color, radius, strokeColor, strokeWidth),
        roundRect(Color.BLACK, radius)
    )

    fun styleChip(
        ctx: Context,
        tv: TextView,
        selected: Boolean,
        vertical: Int = 3
    ) {
        tv.setTextColor(if (selected) BG else TEXT_DIM)
        tv.background = roundRect(
            if (selected) ACCENT else FIELD,
            ctx.dp(20).toFloat()
        )
        tv.setPadding(ctx.dp(10), ctx.dp(vertical), ctx.dp(10), ctx.dp(vertical))
    }

    fun chip(
        ctx: Context,
        label: String,
        selected: Boolean = false,
        vertical: Int = 3
    ): TextView {
        val tv = TextView(ctx)
        tv.text = label
        tv.textSize = 11f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.gravity = Gravity.CENTER
        styleChip(ctx, tv, selected, vertical)
        return tv
    }

    fun pill(
        ctx: Context,
        label: String,
        bg: Int,
        fg: Int,
        onClick: () -> Unit
    ): TextView {
        val tv = TextView(ctx)
        tv.text = label
        tv.textSize = 14f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.setTextColor(fg)
        tv.gravity = Gravity.CENTER
        tv.includeFontPadding = false
        tv.background = pressable(bg, ctx.dp(24).toFloat(), STROKE, ctx.dp(1))
        tv.setPadding(ctx.dp(18), 0, ctx.dp(18), 0)
        tv.isClickable = true
        tv.setOnClickListener { onClick() }
        return tv
    }

    fun label(ctx: Context, text: String): TextView {
        val tv = TextView(ctx)
        tv.text = text
        tv.textSize = 11f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.letterSpacing = 0.08f
        tv.setTextColor(TEXT_DIM)
        tv.setPadding(0, ctx.dp(16), 0, ctx.dp(6))
        return tv
    }

    fun field(ctx: Context, hint: String, multiline: Boolean): EditText {
        val et = EditText(ctx)
        et.hint = hint
        et.textSize = 15f
        et.setTextColor(TEXT)
        et.setHintTextColor(TEXT_DIM)
        et.background = roundRect(FIELD, ctx.dp(14).toFloat())
        et.setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
        if (multiline) {
            et.typeface = Typeface.MONOSPACE
            et.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            et.minLines = 6
            et.gravity = Gravity.TOP or Gravity.START
        } else {
            et.isSingleLine = true
        }
        return et
    }

    /** Getar halus + kedipan singkat sebagai tanda snippet tersalin. */
    fun feedback(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        v.animate().cancel()
        v.alpha = 0.55f
        v.animate().alpha(1f).setDuration(240).start()
    }
}

/** Baris chip kategori yang bisa digeser: Semua + kategori + tombol tambah (opsional). */
class CategoryBar(private val ctx: Context) {
    val view = android.widget.HorizontalScrollView(ctx)
    private val row = LinearLayout(ctx)

    init {
        view.isHorizontalScrollBarEnabled = false
        view.overScrollMode = View.OVER_SCROLL_NEVER
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        view.addView(row, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
    }

    fun set(
        categories: List<String>,
        selected: String?,
        onSelect: (String?) -> Unit,
        onLongPress: ((String) -> Unit)? = null,
        onAdd: (() -> Unit)? = null,
        showAll: Boolean = true
    ) {
        row.removeAllViews()
        fun add(label: String, isSel: Boolean, click: () -> Unit, long: (() -> Unit)? = null) {
            val c = Ui.chip(ctx, label, isSel, 7)
            c.setPadding(ctx.dp(14), ctx.dp(7), ctx.dp(14), ctx.dp(7))
            c.textSize = 12f
            c.setOnClickListener { click() }
            if (long != null) c.setOnLongClickListener { long(); true }
            row.addView(c, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply {
                marginEnd = ctx.dp(8)
            })
        }
        if (showAll) add(ctx.getString(R.string.chip_all), selected == null, { onSelect(null) })
        categories.forEach { cat ->
            add(cat, cat.equals(selected, true), { onSelect(cat) }, onLongPress?.let { f -> { f(cat) } })
        }
        if (onAdd != null) add("+", false, { onAdd() })
    }
}

/** Adapter kartu snippet, dipakai oleh layar utama dan overlay. */
class SnippetAdapter(
    private val ctx: Context,
    private val items: () -> List<Snippet>
) : BaseAdapter() {

    private class Holder(
        val chip: TextView,
        val title: TextView,
        val preview: TextView
    )

    override fun getCount(): Int = items().size

    override fun getItem(position: Int): Any = items()[position]

    override fun getItemId(position: Int): Long = items()[position].id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val root: View
        val holder: Holder
        if (convertView == null) {
            val chip = Ui.chip(ctx, "")

            val title = TextView(ctx)
            title.textSize = 15f
            title.typeface = Typeface.DEFAULT_BOLD
            title.setTextColor(Ui.TEXT)
            title.maxLines = 1
            title.ellipsize = TextUtils.TruncateAt.END
            title.layoutParams = LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply {
                marginStart = ctx.dp(10)
            }

            val top = LinearLayout(ctx)
            top.orientation = LinearLayout.HORIZONTAL
            top.gravity = Gravity.CENTER_VERTICAL
            top.addView(chip)
            top.addView(title)

            val preview = TextView(ctx)
            preview.textSize = 12f
            preview.typeface = Typeface.MONOSPACE
            preview.setTextColor(Ui.TEXT_DIM)
            preview.maxLines = 2
            preview.ellipsize = TextUtils.TruncateAt.END
            preview.setPadding(0, ctx.dp(8), 0, 0)

            val card = LinearLayout(ctx)
            card.orientation = LinearLayout.VERTICAL
            card.background = Ui.pressable(
                Ui.CARD, ctx.dp(16).toFloat(), Ui.STROKE, ctx.dp(1)
            )
            card.setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
            card.isDuplicateParentStateEnabled = true
            card.addView(top)
            card.addView(preview)

            val frame = FrameLayout(ctx)
            frame.setPadding(0, ctx.dp(4), 0, ctx.dp(4))
            frame.addView(card, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

            holder = Holder(chip, title, preview)
            frame.tag = holder
            root = frame
        } else {
            root = convertView
            holder = convertView.tag as Holder
        }

        val s = items()[position]
        holder.chip.text = s.type
        Ui.styleChip(ctx, holder.chip, false)
        holder.title.text = s.title
        val body = s.content.trim()
        holder.preview.text = body
        holder.preview.visibility = if (body.isEmpty()) View.GONE else View.VISIBLE
        return root
    }
}
