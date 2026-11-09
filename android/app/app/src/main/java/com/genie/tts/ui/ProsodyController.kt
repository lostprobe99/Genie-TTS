package com.genie.tts.ui

import com.genie.tts.R
import com.genie.tts.speech.Prosody
import com.genie.tts.speech.Release
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** 韵律页这一刻要画出来的内容。控制器不自己去读仓库或合成器。 */
internal data class ProsodySnapshot(
    val ready: Boolean,
    val fileExists: Boolean,
    val fileBytes: Long,
    val filePath: String,
    val lastModified: Long,
    val downloading: Boolean,
    val busy: Boolean,
    val downloadDone: Long = 0,
    val downloadTotal: Long = 0,
)

/**
 * 韵律页的状态、进度、路径和下载导入删除按钮。
 * 点下去之后做什么，由外面传进来的回调决定。
 */
internal class ProsodyController(private val activity: AppCompatActivity) {
    private val status: TextView = activity.findViewById(R.id.prosodyStatus)
    private val detail: TextView = activity.findViewById(R.id.prosodyDetail)
    private val progress: ProgressBar = activity.findViewById(R.id.prosodyProgress)
    private val badge: TextView = activity.findViewById(R.id.prosodyBadge)
    private val model: TextView = activity.findViewById(R.id.prosodyModel)
    private val fileName: TextView = activity.findViewById(R.id.prosodyFile)
    private val size: TextView = activity.findViewById(R.id.prosodySize)
    private val path: TextView = activity.findViewById(R.id.prosodyPath)
    private val updated: TextView = activity.findViewById(R.id.prosodyUpdated)
    private val downloadSub: TextView = activity.findViewById(R.id.prosodyDownloadSub)
    private val copy: ImageButton = activity.findViewById(R.id.prosodyCopy)
    private val deleteCard: View = activity.findViewById(R.id.prosodyDeleteCard)
    private val download: TextView = activity.findViewById(R.id.downloadProsody)
    private val importButton: TextView = activity.findViewById(R.id.importProsody)
    private val delete: TextView = activity.findViewById(R.id.deleteProsody)
    private val drawerState: TextView = activity.findViewById(R.id.drawerProsodyState)

    fun bind(
        onDownload: () -> Unit,
        onImport: () -> Unit,
        onDelete: () -> Unit,
        onCopyPath: () -> Unit,
    ) {
        model.text = Prosody.FILE_NAME
        fileName.text = Prosody.FILE_NAME
        downloadSub.text = activity.getString(
            R.string.prosody_download_sub,
            Release.TAG,
            bytesLabel(Prosody.MODEL_BYTES),
        )
        activity.findViewById<TextView>(R.id.prosodyTipWhat).text = html(activity.getString(R.string.prosody_tip_what))
        activity.findViewById<TextView>(R.id.prosodyTipNet).text = html(activity.getString(R.string.prosody_tip_net))
        download.setOnClickListener { onDownload() }
        importButton.setOnClickListener { onImport() }
        delete.setOnClickListener { onDelete() }
        copy.setOnClickListener { onCopyPath() }
    }

    fun render(state: ProsodySnapshot) {
        status.setText(if (state.ready) R.string.prosody_installed else R.string.prosody_missing)
        status.setBackgroundResource(if (state.ready) R.drawable.bg_prosody_ready else R.drawable.bg_prosody_idle)
        status.setTextColor(if (state.ready) color(R.color.green_ink) else color(R.color.text_sub))
        badge.setText(if (state.ready) R.string.prosody_ready else R.string.prosody_missing)
        size.text = when {
            state.ready -> bytesLabel(state.fileBytes)
            state.fileExists -> activity.getString(R.string.prosody_bad_size)
            else -> activity.getString(R.string.prosody_missing)
        }
        path.text = state.filePath
        updated.text = when {
            state.ready -> activity.getString(R.string.prosody_written, writtenAt(state.lastModified))
            state.fileExists -> activity.getString(
                R.string.prosody_wrong_bytes,
                "%,d".format(Locale.US, Prosody.MODEL_BYTES),
            )
            else -> activity.getString(R.string.prosody_absent)
        }
        deleteCard.visibility = if (state.fileExists) View.VISIBLE else View.GONE
        download.setText(
            when {
                state.downloading -> R.string.prosody_download_busy
                state.ready -> R.string.prosody_installed
                else -> R.string.download
            },
        )
        paintProgress(state)
        drawerState.setText(if (state.ready) R.string.prosody_ready else R.string.prosody_missing)
        val enabled = !state.busy
        val alpha = if (enabled) 1f else 0.4f
        download.isEnabled = enabled
        importButton.isEnabled = enabled
        delete.isEnabled = enabled
        download.alpha = alpha
        importButton.alpha = alpha
        delete.alpha = alpha
    }

    private fun paintProgress(state: ProsodySnapshot) {
        if (!state.downloading) {
            detail.visibility = View.GONE
            progress.visibility = View.GONE
            progress.isIndeterminate = false
            progress.progress = 0
            return
        }
        detail.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        val done = state.downloadDone
        val total = state.downloadTotal
        detail.text = when {
            done == 0L && total == 0L -> activity.getString(R.string.prosody_downloading)
            total > 0 -> activity.getString(R.string.prosody_downloaded_of, bytesLabel(done), bytesLabel(total))
            else -> activity.getString(R.string.prosody_downloaded, bytesLabel(done))
        }
        if (total > 0) {
            progress.isIndeterminate = false
            progress.max = 1000
            progress.progress = ((done.coerceIn(0, total) * 1000) / total).toInt()
        } else {
            progress.isIndeterminate = true
        }
    }

    private fun html(source: String): CharSequence =
        HtmlCompat.fromHtml(source, HtmlCompat.FROM_HTML_MODE_COMPACT)

    private fun writtenAt(millis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        return format.format(Date(millis))
    }

    private fun bytesLabel(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return "${mb.roundToInt()} MB"
    }

    private fun color(id: Int) = ContextCompat.getColor(activity, id)
}
