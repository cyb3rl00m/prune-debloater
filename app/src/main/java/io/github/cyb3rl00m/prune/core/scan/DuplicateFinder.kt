package io.github.cyb3rl00m.prune.core.scan

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.inputmethod.InputMethodManager

/**
 * Finds app types installed more than once (two browsers, two galleries, three keyboards...), a common
 * source of manufacturer bloat. Uses Android's standard app categories, so it works on every brand.
 * It only reports: which duplicate to keep is the user's choice.
 */
class DuplicateFinder(private val context: Context) {

    data class DuplicateGroup(val name: String, val packages: List<String>, val defaultPackage: String?)

    private val pm = context.packageManager

    fun find(): List<DuplicateGroup> {
        val groups = mutableListOf<DuplicateGroup>()
        fun add(name: String, pkgs: Collection<String>, default: String? = null) {
            val list = pkgs.filter { it != context.packageName }.distinct()
            if (list.size >= 2) groups += DuplicateGroup(name, list.sorted(), default?.takeIf { it in list })
        }
        val categories = listOf(
            Intent.CATEGORY_APP_BROWSER to "Browsers",
            Intent.CATEGORY_APP_GALLERY to "Galleries",
            Intent.CATEGORY_APP_MUSIC to "Music players",
            Intent.CATEGORY_APP_CALCULATOR to "Calculators",
            Intent.CATEGORY_APP_CALENDAR to "Calendars",
            Intent.CATEGORY_APP_EMAIL to "Email apps",
            Intent.CATEGORY_APP_MESSAGING to "Messaging (SMS) apps",
            Intent.CATEGORY_APP_CONTACTS to "Contacts apps",
            Intent.CATEGORY_APP_MAPS to "Maps",
        ) + (if (Build.VERSION.SDK_INT >= 29) listOf(Intent.CATEGORY_APP_FILES to "File managers") else emptyList())
        for ((category, name) in categories) {
            val intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category)
            add(name, query(intent), defaultFor(intent))
        }
        add("App stores", query(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.example"))))
        add("Video players", query(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://media/video"), "video/*")))
        add("Cameras", query(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)))
        val imm = context.getSystemService(InputMethodManager::class.java)
        add("Keyboards", runCatching { imm?.inputMethodList?.map { it.packageName } }.getOrNull().orEmpty())
        return groups
    }

    @Suppress("DEPRECATION")
    private fun query(intent: Intent): List<String> = runCatching {
        val list = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        list.mapNotNull { it.activityInfo?.packageName }.filter { it != "android" }
    }.getOrDefault(emptyList())

    @Suppress("DEPRECATION")
    private fun defaultFor(intent: Intent): String? = runCatching {
        val ri = if (Build.VERSION.SDK_INT >= 33) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        ri?.activityInfo?.packageName?.takeIf { it != "android" }
    }.getOrNull()
}
