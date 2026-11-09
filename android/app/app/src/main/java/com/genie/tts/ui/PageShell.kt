package com.genie.tts.ui

import com.genie.tts.databinding.ActivityMainBinding
import com.genie.tts.R
import android.os.Build
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import kotlin.math.max

internal enum class AppPage { Speak, Characters, Service, Prosody, About, Logs, Help }

/**
 * 抽屉、顶栏和各页的显隐。点哪一页由外面决定。
 */
internal class PageShell(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val typing: () -> Boolean,
) {
    private val drawer: DrawerLayout = binding.drawer
    private val drawerPanel: View = binding.drawerPanel
    private val headerSlot: View = binding.headerSlot
    private val pageFrame: View = binding.pageFrame
    private val homeBar: View = binding.homeBar
    private val rolesBar: View = binding.rolesBar
    private val prosodyBar: View = binding.prosodyBar
    private val aboutBar: View = binding.aboutBar
    private val serviceBar: View = binding.serviceBar
    private val logsBar: View = binding.logsBar
    private val helpBar: View = binding.helpBar
    private val otherBar: View = binding.otherBar
    private val menuButton: ImageButton = binding.menuButton
    private val pageTitle: TextView = binding.pageTitle
    val banner: TextView = binding.banner
    private val roleCount: TextView = binding.drawerRoleCount
    private val speakPage: View = binding.speakPage.speakPage
    private val speakChrome: View = binding.speakPage.speakChrome
    private val characterPage: View = binding.characterPage.characterPage
    private val servicePage: View = binding.servicePage.root
    private val prosodyPage: View = binding.prosodyPage.root
    private val aboutPage: View = binding.aboutPage.root
    private val logsPage: View = binding.logsPage.logsPage
    private val helpPage: View = binding.helpPage.helpPage
    private var imeBottom = 0

    fun padForScreen() {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        if (Build.VERSION.SDK_INT >= 29) {
            activity.window.isStatusBarContrastEnforced = false
            activity.window.isNavigationBarContrastEnforced = false
        }
        activity.window.statusBarColor = android.graphics.Color.TRANSPARENT
        activity.window.navigationBarColor = android.graphics.Color.TRANSPARENT
        val content = binding.contentRoot
        val drawerHeader = binding.drawerHeader
        val drawerNav = binding.drawerNav
        val contentLeft = content.paddingLeft
        val contentRight = content.paddingRight
        val headerTop = headerSlot.paddingTop
        val pageBottom = pageFrame.paddingBottom
        val drawerLeft = drawerPanel.paddingLeft
        val drawerHeaderTop = drawerHeader.paddingTop
        val drawerNavBottom = drawerNav.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(drawer) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            imeBottom = ime
            applyTyping()
            content.updatePadding(
                left = contentLeft + bars.left,
                right = contentRight + bars.right,
            )
            headerSlot.updatePadding(top = headerTop + bars.top)
            pageFrame.updatePadding(bottom = pageBottom + max(bars.bottom, ime))
            drawerPanel.updatePadding(left = drawerLeft + bars.left)
            drawerHeader.updatePadding(top = drawerHeaderTop + bars.top)
            drawerNav.updatePadding(bottom = drawerNavBottom + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    fun applyTyping() {
        val focused = imeBottom > 0 && typing()
        speakChrome.visibility = if (focused) View.GONE else View.VISIBLE
    }

    fun show(page: AppPage) {
        speakPage.visibility = if (page == AppPage.Speak) View.VISIBLE else View.GONE
        characterPage.visibility = if (page == AppPage.Characters) View.VISIBLE else View.GONE
        servicePage.visibility = if (page == AppPage.Service) View.VISIBLE else View.GONE
        prosodyPage.visibility = if (page == AppPage.Prosody) View.VISIBLE else View.GONE
        aboutPage.visibility = if (page == AppPage.About) View.VISIBLE else View.GONE
        logsPage.visibility = if (page == AppPage.Logs) View.VISIBLE else View.GONE
        helpPage.visibility = if (page == AppPage.Help) View.VISIBLE else View.GONE
        applyTyping()
        val onSpeak = page == AppPage.Speak
        val onRoles = page == AppPage.Characters
        val onProsody = page == AppPage.Prosody
        val onAbout = page == AppPage.About
        val onService = page == AppPage.Service
        val onLogs = page == AppPage.Logs
        val onHelp = page == AppPage.Help
        homeBar.visibility = if (onSpeak) View.VISIBLE else View.GONE
        rolesBar.visibility = if (onRoles) View.VISIBLE else View.GONE
        prosodyBar.visibility = if (onProsody) View.VISIBLE else View.GONE
        aboutBar.visibility = if (onAbout) View.VISIBLE else View.GONE
        serviceBar.visibility = if (onService) View.VISIBLE else View.GONE
        logsBar.visibility = if (onLogs) View.VISIBLE else View.GONE
        helpBar.visibility = if (onHelp) View.VISIBLE else View.GONE
        val light = onSpeak || onRoles || onProsody || onAbout || onService || onLogs || onHelp
        otherBar.visibility = if (light) View.GONE else View.VISIBLE
        val pageColor = color(if (light) R.color.bg_light else R.color.ink)
        val headerColor = color(if (light) R.color.header else R.color.ink)
        headerSlot.setBackgroundColor(headerColor)
        pageFrame.setBackgroundColor(pageColor)
        binding.contentRoot.setBackgroundColor(pageColor)
        banner.setTextColor(color(if (light) R.color.primary else R.color.amber))
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = light
        pageTitle.setText(
            when (page) {
                AppPage.Speak -> R.string.app_name
                AppPage.Characters -> R.string.characters
                AppPage.Service -> R.string.service
                AppPage.Prosody -> R.string.prosody
                AppPage.About -> R.string.about
                AppPage.Logs -> R.string.view_log
                AppPage.Help -> R.string.help
            },
        )
        menuButton.setImageResource(if (onSpeak) R.drawable.ic_menu else R.drawable.ic_back)
        menuButton.contentDescription = activity.getString(if (onSpeak) R.string.menu else R.string.back)
        val menuPages = onSpeak || onRoles || onService || onProsody
        drawer.setDrawerLockMode(
            if (menuPages) DrawerLayout.LOCK_MODE_UNLOCKED else DrawerLayout.LOCK_MODE_LOCKED_CLOSED,
            GravityCompat.START,
        )
    }

    fun bindDrawer(onPage: (AppPage) -> Unit, onSettings: () -> Unit) {
        binding.drawerClose.setOnClickListener { closeDrawer() }
        binding.openHome.setOnClickListener {
            closeDrawer()
            onPage(AppPage.Speak)
        }
        binding.openSettings.setOnClickListener {
            closeDrawer()
            onSettings()
        }
        binding.openHelp.setOnClickListener {
            closeDrawer()
            onPage(AppPage.Help)
        }
        binding.openLogs.setOnClickListener {
            closeDrawer()
            onPage(AppPage.Logs)
        }
        binding.openCharacters.setOnClickListener {
            closeDrawer()
            onPage(AppPage.Characters)
        }
        binding.openService.setOnClickListener {
            closeDrawer()
            onPage(AppPage.Service)
        }
        binding.openProsody.setOnClickListener {
            closeDrawer()
            onPage(AppPage.Prosody)
        }
        binding.openAbout.setOnClickListener {
            closeDrawer()
            onPage(AppPage.About)
        }
    }

    fun bindMenu(onClick: () -> Unit) {
        menuButton.setOnClickListener { onClick() }
    }

    fun bindRoleRow(onClick: () -> Unit) {
        binding.roleRow.setOnClickListener { onClick() }
    }

    fun setRoleCount(count: Int) {
        roleCount.text = activity.getString(R.string.role_count, count)
    }

    fun openDrawer() {
        drawer.openDrawer(drawerPanel)
    }

    fun closeDrawer() {
        drawer.closeDrawer(drawerPanel)
    }

    fun isDrawerOpen(): Boolean = drawer.isDrawerOpen(GravityCompat.START)

    private fun color(id: Int) = ContextCompat.getColor(activity, id)
}
