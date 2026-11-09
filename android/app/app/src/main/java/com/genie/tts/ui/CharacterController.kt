package com.genie.tts.ui

import com.genie.tts.databinding.ActivityMainBinding
import com.genie.tts.databinding.MenuRolesBinding
import com.genie.tts.R
import com.genie.tts.character.CharacterInfo
import com.genie.tts.character.CharacterPack
import com.genie.tts.databinding.ItemRoleCardBinding
import android.content.res.ColorStateList
import android.graphics.Bitmap
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
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
    private val binding: ActivityMainBinding,
    private val avatarIndex: (id: String, choices: Int) -> Int,
) {
    private val cards: RecyclerView = binding.characterPage.characterCards
    private val avatars = AvatarCache()
    private val roleRows = RoleAdapter(activity, avatars, avatarIndex)
    private val title: TextView = binding.rolesTitle
    private val back: ImageButton = binding.rolesBack
    private val count: TextView = binding.roleCount
    private val query: EditText = binding.roleQuery
    private val more: View = binding.roleMore
    private val empty: View = binding.characterPage.roleEmpty
    private val foot: TextView = binding.characterPage.roleFoot
    private val batchBar: View = binding.characterPage.roleBatchBar
    private val selectAll: TextView = binding.characterPage.roleSelectAll
    private val deletePicked: TextView = binding.characterPage.roleDeletePicked
    private val importButton: View = binding.characterPage.importCharacter
    private var menu: PopupWindow? = null

    init {
        cards.layoutManager = LinearLayoutManager(activity)
        cards.adapter = roleRows
        cards.itemAnimator = null
        cards.isNestedScrollingEnabled = false
    }

    fun bind(
        onQuery: () -> Unit,
        onBack: () -> Unit,
        onMore: (View) -> Unit,
        onSelectAll: () -> Unit,
        onDeletePicked: () -> Unit,
        onImport: () -> Unit,
    ) {
        back.setOnClickListener { onBack() }
        binding.roleSearchButton.setOnClickListener { toggleSearch() }
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
        roleRows.submit(shown, state, onToggle, onEdit, onPreview)
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
        val menuBinding = MenuRolesBinding.inflate(activity.layoutInflater)
        val content = menuBinding.root
        val popup = PopupWindow(content, activity.px(R.dimen.dp_176), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true
        popup.elevation = activity.px(R.dimen.dp_12).toFloat()
        menuBinding.menuImport.setOnClickListener {
            popup.dismiss()
            onImport()
        }
        menuBinding.menuBatch.setOnClickListener {
            popup.dismiss()
            onBatch()
        }
        menuBinding.menuExport.setOnClickListener {
            popup.dismiss()
            onExport()
        }
        menuBinding.menuClear.setOnClickListener {
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

    private fun color(id: Int) = ContextCompat.getColor(activity, id)
}

private data class RoleRow(
    val info: CharacterInfo,
    val batching: Boolean,
    val picked: Boolean,
    val playingThis: Boolean,
    val busy: Boolean,
)

private class RoleAdapter(
    private val activity: AppCompatActivity,
    private val avatars: AvatarCache,
    private val avatarIndex: (id: String, choices: Int) -> Int,
) : RecyclerView.Adapter<RoleAdapter.Holder>() {
    private var rows = emptyList<RoleRow>()
    private var onToggle: (String) -> Unit = {}
    private var onEdit: (CharacterInfo) -> Unit = {}
    private var onPreview: (CharacterInfo) -> Unit = {}

    fun submit(
        shown: List<CharacterInfo>,
        state: CharacterSnapshot,
        onToggle: (String) -> Unit,
        onEdit: (CharacterInfo) -> Unit,
        onPreview: (CharacterInfo) -> Unit,
    ) {
        val next = shown.map { info ->
            RoleRow(
                info = info,
                batching = state.batching,
                picked = info.id in state.picked,
                playingThis = state.previewId == info.id,
                busy = state.busy,
            )
        }
        val old = rows
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(a: Int, b: Int) = old[a].info.id == next[b].info.id
            override fun areContentsTheSame(a: Int, b: Int) = old[a] == next[b]
        })
        rows = next
        this.onToggle = onToggle
        this.onEdit = onEdit
        this.onPreview = onPreview
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemRoleCardBinding.inflate(activity.layoutInflater, parent, false)
        binding.roleAvatar.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.dimen(R.dimen.dp_12))
            }
        }
        binding.roleAvatar.clipToOutline = true
        return Holder(binding, avatars, avatarIndex)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(rows[position], onToggle, onEdit, onPreview)
    }

    class Holder(
        private val binding: ItemRoleCardBinding,
        private val avatars: AvatarCache,
        private val avatarIndex: (id: String, choices: Int) -> Int,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            row: RoleRow,
            onToggle: (String) -> Unit,
            onEdit: (CharacterInfo) -> Unit,
            onPreview: (CharacterInfo) -> Unit,
        ) {
            val info = row.info
            val view = binding.root
            view.background = if (row.batching && row.picked) {
                GradientDrawable().apply {
                    cornerRadius = view.dimen(R.dimen.dp_16)
                    setColor(color(view, R.color.teal_soft))
                }
            } else {
                ContextCompat.getDrawable(view.context, R.drawable.bg_role_card)
            }
            view.setOnClickListener { if (row.batching) onToggle(info.id) }
            view.setOnLongClickListener {
                if (!row.batching) onEdit(info)
                true
            }
            paintAvatar(info)
            binding.roleName.text = info.name
            binding.roleId.text = view.context.getString(R.string.role_id, info.id)
            if (row.batching) {
                binding.rolePlay.visibility = View.GONE
                binding.rolePlay.setOnClickListener(null)
                binding.roleBatchMark.visibility = View.VISIBLE
                binding.roleBatchMark.background = ContextCompat.getDrawable(
                    view.context,
                    if (row.picked) R.drawable.bg_role_fab else R.drawable.bg_role_check_off,
                )
                if (row.picked) {
                    binding.roleBatchMark.setImageResource(R.drawable.ic_role_check)
                    binding.roleBatchMark.imageTintList = ColorStateList.valueOf(Color.WHITE)
                } else {
                    binding.roleBatchMark.setImageDrawable(null)
                    binding.roleBatchMark.imageTintList = null
                }
            } else {
                binding.roleBatchMark.visibility = View.GONE
                binding.rolePlay.visibility = View.VISIBLE
                binding.rolePlay.setBackgroundResource(
                    if (row.playingThis) R.drawable.bg_role_play_on else R.drawable.bg_role_play,
                )
                binding.rolePlay.setImageResource(
                    if (row.playingThis) R.drawable.ic_role_bars else R.drawable.ic_role_play,
                )
                binding.rolePlay.imageTintList = ColorStateList.valueOf(
                    color(view, if (row.playingThis) R.color.header else R.color.primary),
                )
                binding.rolePlay.contentDescription = view.context.getString(
                    if (row.playingThis) R.string.preview_playing else R.string.preview_role,
                    info.name,
                )
                binding.rolePlay.isEnabled = !row.busy || row.playingThis
                binding.rolePlay.alpha = if (binding.rolePlay.isEnabled) 1f else 0.4f
                binding.rolePlay.setOnClickListener { onPreview(info) }
            }
        }

        private fun paintAvatar(info: CharacterInfo) {
            val file = CharacterPack.avatarImage(info.root)
            val bitmap = file?.let { avatars.get(info.id, it) }
            if (bitmap != null) {
                binding.roleAvatar.background = null
                binding.roleAvatarImage.setImageBitmap(bitmap)
                binding.roleAvatarImage.visibility = View.VISIBLE
                binding.roleAvatarIcon.visibility = View.GONE
                return
            }
            val index = avatarIndex(info.id, AVATAR_ICONS.size)
            binding.roleAvatarImage.setImageDrawable(null)
            binding.roleAvatarImage.visibility = View.GONE
            binding.roleAvatar.background = GradientDrawable(
                GradientDrawable.Orientation.BL_TR,
                intArrayOf(color(binding.root, AVATAR_COLORS[index].first), color(binding.root, AVATAR_COLORS[index].second)),
            ).apply { cornerRadius = binding.root.dimen(R.dimen.dp_12) }
            binding.roleAvatarIcon.visibility = View.VISIBLE
            binding.roleAvatarIcon.setImageResource(AVATAR_ICONS[index])
        }

        private fun color(view: View, id: Int) = ContextCompat.getColor(view.context, id)
    }

    private companion object {
        val AVATAR_ICONS = intArrayOf(
            R.drawable.ic_avatar_wave,
            R.drawable.ic_avatar_plus,
            R.drawable.ic_avatar_mic,
            R.drawable.ic_avatar_sparkles,
            R.drawable.ic_avatar_note,
            R.drawable.ic_avatar_microphone,
            R.drawable.ic_avatar_heart,
            R.drawable.ic_avatar_book,
        )
        val AVATAR_COLORS = arrayOf(
            R.color.header to R.color.teal_bright,
            R.color.green_mid to R.color.mint_bright,
            R.color.amber_deep to R.color.teal,
            R.color.teal_bright to R.color.cyan_bright,
            R.color.sky to R.color.indigo,
            R.color.slate_text to R.color.header,
            R.color.rose_soft to R.color.teal_bright,
            R.color.teal_deep to R.color.green,
        )
    }
}

private class AvatarCache {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(id: String, file: File): Bitmap? {
        val key = "$id:${file.lastModified()}:${file.length()}"
        cache.get(key)?.let { return it }
        val bitmap = decode(file) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
