package com.genie.tts.ui

import com.genie.tts.R
import com.genie.tts.character.CharacterInfo
import com.genie.tts.character.CharacterPack
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

/** 角色页这一刻要画出来的内容。控制器不自己去读仓库或合成器。 */
internal data class CharacterSnapshot(
    val roster: List<CharacterInfo>,
    val selectedId: String?,
    val batching: Boolean,
    val onPage: Boolean,
    val picked: Set<String>,
    val busy: Boolean,
    val playing: Boolean,
    val previewId: String?,
)

/**
 * 角色页的卡片、搜索、批量条和导入按钮。
 * 导入、删除、改名和试听仍由外面发起。
 */
internal class CharacterController(
    private val activity: AppCompatActivity,
    private val avatarIndex: (id: String, choices: Int) -> Int,
) {
    private val cards: LinearLayout = activity.findViewById(R.id.characterCards)
    private val title: TextView = activity.findViewById(R.id.rolesTitle)
    private val back: ImageButton = activity.findViewById(R.id.rolesBack)
    private val count: TextView = activity.findViewById(R.id.roleCount)
    private val query: EditText = activity.findViewById(R.id.roleQuery)
    private val more: View = activity.findViewById(R.id.roleMore)
    private val empty: View = activity.findViewById(R.id.roleEmpty)
    private val foot: TextView = activity.findViewById(R.id.roleFoot)
    private val batchBar: View = activity.findViewById(R.id.roleBatchBar)
    private val selectAll: TextView = activity.findViewById(R.id.roleSelectAll)
    private val deletePicked: TextView = activity.findViewById(R.id.roleDeletePicked)
    private val importButton: View = activity.findViewById(R.id.importCharacter)
    private var menu: PopupWindow? = null

    private val avatarIcons = intArrayOf(
        R.drawable.ic_avatar_wave,
        R.drawable.ic_avatar_plus,
        R.drawable.ic_avatar_mic,
        R.drawable.ic_avatar_sparkles,
        R.drawable.ic_avatar_note,
        R.drawable.ic_avatar_microphone,
        R.drawable.ic_avatar_heart,
        R.drawable.ic_avatar_book,
    )
    private val avatarColors = arrayOf(
        intArrayOf(color(R.color.header), color(R.color.teal_bright)),
        intArrayOf(color(R.color.green_mid), color(R.color.mint_bright)),
        intArrayOf(color(R.color.amber_deep), color(R.color.teal)),
        intArrayOf(color(R.color.teal_bright), color(R.color.cyan_bright)),
        intArrayOf(color(R.color.sky), color(R.color.indigo)),
        intArrayOf(color(R.color.slate_text), color(R.color.header)),
        intArrayOf(color(R.color.rose_soft), color(R.color.teal_bright)),
        intArrayOf(color(R.color.teal_deep), color(R.color.green)),
    )

    fun bind(
        onQuery: () -> Unit,
        onBack: () -> Unit,
        onMore: (View) -> Unit,
        onSelectAll: () -> Unit,
        onDeletePicked: () -> Unit,
        onImport: () -> Unit,
    ) {
        back.setOnClickListener { onBack() }
        activity.findViewById<View>(R.id.roleSearchButton).setOnClickListener { toggleSearch() }
        more.setOnClickListener { onMore(it) }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onQuery()
        })
        selectAll.setOnClickListener { onSelectAll() }
        deletePicked.setOnClickListener { onDeletePicked() }
        importButton.setOnClickListener { onImport() }
    }

    fun render(
        state: CharacterSnapshot,
        onToggle: (String) -> Unit,
        onEdit: (CharacterInfo) -> Unit,
        onPreview: (CharacterInfo) -> Unit,
    ) {
        val typed = query.text?.toString()?.trim().orEmpty()
        val shown = state.roster.filter { info ->
            typed.isEmpty() ||
                info.name.contains(typed, ignoreCase = true) ||
                info.id.contains(typed, ignoreCase = true)
        }
        foot.text = footText(state)
        empty.visibility = if (typed.isNotEmpty() && shown.isEmpty()) View.VISIBLE else View.GONE
        val shownIds = shown.map { it.id }
        val allShown = shownIds.isNotEmpty() && shownIds.all { it in state.picked }
        selectAll.setText(if (allShown) R.string.select_none else R.string.select_all)
        selectAll.tag = shownIds
        cards.removeAllViews()
        shown.forEach { info -> cards.addView(roleCard(info, state, onToggle, onEdit, onPreview)) }
        paintChrome(state)
    }

    fun shownIds(): List<String> {
        @Suppress("UNCHECKED_CAST")
        return selectAll.tag as? List<String> ?: emptyList()
    }

    fun clickImport() {
        importButton.performClick()
    }

    fun setImportEnabled(enabled: Boolean) {
        importButton.isEnabled = enabled
        importButton.alpha = if (enabled) 1f else 0.4f
    }

    fun showMenu(
        anchor: View,
        onImport: () -> Unit,
        onBatch: () -> Unit,
        onExport: () -> Unit,
        onClear: () -> Unit,
    ) {
        menu?.dismiss()
        val content = activity.layoutInflater.inflate(R.layout.menu_roles, null)
        val popup = PopupWindow(content, dp(176), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true
        popup.elevation = dp(12).toFloat()
        content.findViewById<View>(R.id.menuImport).setOnClickListener {
            popup.dismiss()
            onImport()
        }
        content.findViewById<View>(R.id.menuBatch).setOnClickListener {
            popup.dismiss()
            onBatch()
        }
        content.findViewById<View>(R.id.menuExport).setOnClickListener {
            popup.dismiss()
            onExport()
        }
        content.findViewById<View>(R.id.menuClear).setOnClickListener {
            popup.dismiss()
            onClear()
        }
        popup.showAsDropDown(anchor, 0, 0, Gravity.END)
        menu = popup
    }

    fun dismissMenu() {
        menu?.dismiss()
    }

    private fun toggleSearch() {
        val open = query.visibility != View.VISIBLE
        query.visibility = if (open) View.VISIBLE else View.GONE
        if (open) query.requestFocus() else query.setText("")
    }

    private fun footText(state: CharacterSnapshot): CharSequence {
        val total = activity.getString(R.string.role_model_count, state.roster.size)
        val current = state.roster.find { it.id == state.selectedId } ?: return total
        val text = activity.getString(R.string.role_model_current, total, current.name)
        val start = text.length - current.name.length
        return SpannableString(text).apply {
            setSpan(ForegroundColorSpan(color(R.color.header)), start, text.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), start, text.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun roleCard(
        info: CharacterInfo,
        state: CharacterSnapshot,
        onToggle: (String) -> Unit,
        onEdit: (CharacterInfo) -> Unit,
        onPreview: (CharacterInfo) -> Unit,
    ): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(activity, R.drawable.bg_role_card)
            elevation = dp(1).toFloat()
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { if (state.batching) onToggle(info.id) }
            setOnLongClickListener {
                if (!state.batching) onEdit(info)
                true
            }
        }
        if (state.batching && info.id in state.picked) {
            card.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(color(R.color.teal_soft))
            }
        }
        card.addView(avatarView(info))
        val text = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        }
        text.addView(TextView(activity).apply {
            this.text = info.name
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text_main))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        text.addView(TextView(activity).apply {
            this.text = activity.getString(R.string.role_id, info.id)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(color(R.color.slate))
            setPadding(0, dp(2), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        card.addView(text)
        if (state.batching) {
            card.addView(batchMark(info.id in state.picked))
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.topMargin = dp(10)
            params.marginStart = dp(12)
            params.marginEnd = dp(12)
            card.layoutParams = params
            return card
        }
        val playingThis = state.previewId == info.id
        card.addView(ImageButton(activity).apply {
            val box = dp(40)
            layoutParams = LinearLayout.LayoutParams(box, box).apply { marginStart = dp(8) }
            background = ContextCompat.getDrawable(
                activity,
                if (playingThis) R.drawable.bg_role_play_on else R.drawable.bg_role_play,
            )
            setImageResource(if (playingThis) R.drawable.ic_role_bars else R.drawable.ic_role_play)
            imageTintList = ColorStateList.valueOf(color(if (playingThis) R.color.header else R.color.primary))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = activity.getString(
                if (playingThis) R.string.preview_playing else R.string.preview_role,
                info.name,
            )
            isEnabled = !state.busy || playingThis
            alpha = if (isEnabled) 1f else 0.4f
            setOnClickListener { onPreview(info) }
        })
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        params.topMargin = dp(10)
        params.marginStart = dp(12)
        params.marginEnd = dp(12)
        card.layoutParams = params
        return card
    }

    private fun avatarView(info: CharacterInfo): View {
        val size = dp(44)
        val frame = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, size, size, dp(12).toFloat())
                }
            }
            clipToOutline = true
        }
        val image = CharacterPack.avatarImage(info.root)
        val bitmap = image?.let { decodeAvatar(it) }
        if (bitmap != null) {
            frame.addView(ImageView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(size, size)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(bitmap)
            })
        } else {
            val index = avatarIndex(info.id, avatarIcons.size)
            frame.background = GradientDrawable(GradientDrawable.Orientation.BL_TR, avatarColors[index]).apply {
                cornerRadius = dp(12).toFloat()
            }
            frame.addView(ImageView(activity).apply {
                val icon = dp(24)
                layoutParams = FrameLayout.LayoutParams(icon, icon, Gravity.CENTER)
                setImageResource(avatarIcons[index])
                imageTintList = ColorStateList.valueOf(Color.WHITE)
            })
        }
        return frame
    }

    private fun decodeAvatar(file: File): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun batchMark(on: Boolean): View {
        return ImageView(activity).apply {
            val box = dp(24)
            layoutParams = LinearLayout.LayoutParams(box, box).apply { marginStart = dp(8) }
            background = ContextCompat.getDrawable(
                activity,
                if (on) R.drawable.bg_role_fab else R.drawable.bg_role_check_off,
            )
            if (on) {
                setImageResource(R.drawable.ic_role_check)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
            }
            scaleType = ImageView.ScaleType.CENTER
            isClickable = false
        }
    }

    private fun paintChrome(state: CharacterSnapshot) {
        val batch = state.batching && state.onPage
        title.setText(if (batch) R.string.batch_manage else R.string.characters)
        back.setImageResource(if (batch) R.drawable.ic_close else R.drawable.ic_menu)
        back.contentDescription = activity.getString(if (batch) R.string.cancel else R.string.menu)
        count.text = if (batch) {
            activity.getString(R.string.role_picked, state.picked.size)
        } else {
            activity.getString(R.string.role_count, state.roster.size)
        }
        more.visibility = if (batch) View.GONE else View.VISIBLE
        foot.visibility = if (batch) View.GONE else View.VISIBLE
        importButton.visibility = if (batch) View.GONE else View.VISIBLE
        batchBar.visibility = if (batch) View.VISIBLE else View.GONE
        deletePicked.alpha = if (state.picked.isEmpty()) 0.4f else 1f
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private fun color(id: Int) = ContextCompat.getColor(activity, id)
}
