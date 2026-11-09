package com.genie.tts.ui

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
    private val typing: () -> Boolean,
) {
    private val drawer: DrawerLayout = activity.findViewById(R.id.drawer)
    private val drawerPanel: View = activity.findViewById(R.id.drawerPanel)
    private val headerSlot: View = activity.findViewById(R.id.headerSlot)
    private val pageFrame: View = activity.findViewById(R.id.pageFrame)
    private val homeBar: View = activity.findViewById(R.id.homeBar)
    private val rolesBar: View = activity.findViewById(R.id.rolesBar)
    private val prosodyBar: View = activity.findViewById(R.id.prosodyBar)
    private val aboutBar: View = activity.findViewById(R.id.aboutBar)
    private val serviceBar: View = activity.findViewById(R.id.serviceBar)
    private val logsBar: View = activity.findViewById(R.id.logsBar)
    private val helpBar: View = activity.findViewById(R.id.helpBar)
    private val otherBar: View = activity.findViewById(R.id.otherBar)
    private val menuButton: ImageButton = activity.findViewById(R.id.menuButton)
    private val pageTitle: TextView = activity.findViewById(R.id.pageTitle)
    val banner: TextView = activity.findViewById(R.id.banner)
    private val roleCount: TextView = activity.findViewById(R.id.drawerRoleCount)
    private val speakPage: View = activity.findViewById(R.id.speakPage)
    private val speakChrome: View = activity.findViewById(R.id.speakChrome)
    private val characterPage: View = activity.findViewById(R.id.characterPage)
    private val servicePage: View = activity.findViewById(R.id.servicePage)
    private val prosodyPage: View = activity.findViewById(R.id.prosodyPage)
    private val aboutPage: View = activity.findViewById(R.id.aboutPage)
    private val logsPage: View = activity.findViewById(R.id.logsPage)
    private val helpPage: View = activity.findViewById(R.id.helpPage)
    private var imeBottom = 0

    fun padForScreen() {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        if (Build.VERSION.SDK_INT >= 29) {
            activity.window.isStatusBarContrastEnforced = false
            activity.window.isNavigationBarContrastEnforced = false
        }
        activity.window.statusBarColor = android.graphics.Color.TRANSPARENT
        activity.window.navigationBarColor = android.graphics.Color.TRANSPARENT
        val content = activity.findViewById<View>(R.id.contentRoot)
        val drawerHeader = activity.findViewById<View>(R.id.drawerHeader)
        val drawerNav = activity.findViewById<View>(R.id.drawerNav)
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
        activity.findViewById<View>(R.id.contentRoot).setBackgroundColor(pageColor)
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
        activity.findViewById<View>(R.id.drawerClose).setOnClickListener { closeDrawer() }
        activity.findViewById<View>(R.id.openHome).setOnClickListener {
            closeDrawer()
            onPage(AppPage.Speak)
        }
        activity.findViewById<View>(R.id.openSettings).setOnClickListener {
            closeDrawer()
            onSettings()
        }
        activity.findViewById<View>(R.id.openHelp).setOnClickListener {
            closeDrawer()
            onPage(AppPage.Help)
        }
        activity.findViewById<View>(R.id.openLogs).setOnClickListener {
            closeDrawer()
            onPage(AppPage.Logs)
        }
        activity.findViewById<View>(R.id.openCharacters).setOnClickListener {
            closeDrawer()
            onPage(AppPage.Characters)
        }
        activity.findViewById<View>(R.id.openService).setOnClickListener {
            closeDrawer()
            onPage(AppPage.Service)
        }
        activity.findViewById<View>(R.id.openProsody).setOnClickListener {
            closeDrawer()
            onPage(AppPage.Prosody)
        }
        activity.findViewById<View>(R.id.openAbout).setOnClickListener {
            closeDrawer()
            onPage(AppPage.About)
        }
    }

    fun bindMenu(onClick: () -> Unit) {
        menuButton.setOnClickListener { onClick() }
    }

    fun bindRoleRow(onClick: () -> Unit) {
        activity.findViewById<View>(R.id.roleRow).setOnClickListener { onClick() }
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
