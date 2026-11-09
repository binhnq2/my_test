package com.ts8.launcher.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The list of apps this launcher may open.
 *
 * **Read once, on a background thread.** `queryIntentActivities` on a firmware
 * with 40 installed packages takes a few tens of milliseconds and touches
 * PackageManager, which holds a lock other apps contend for. Doing it in
 * `onCreate` on the main thread is how a launcher gets a slow first frame.
 *
 * **Icons are decoded lazily, per tile.** Loading 40 drawables up front costs
 * several megabytes on a device that may have 1 GB, and the grid shows only one
 * screenful. [iconFor] is called from the adapter when a tile is bound.
 *
 * **The launcher itself is filtered out.** Tapping our own tile would push a
 * second copy of the app drawer on top of the first. Every other packaged app
 * with a MAIN/LAUNCHER activity is shown, including vendor apps the design
 * names (Radio, EQ, Car Info) when the firmware ships them.
 */
class AppCatalog(private val context: Context) {

    /** One launchable app. */
    data class AppEntry(
        val packageName: String,
        val activityName: String,
        /** Label as the firmware declares it, in the current locale. */
        val label: String,
        /** Sort key: the label without diacritics, lowercased. */
        val sortKey: String
    ) {
        /** Snapshot of the activity in [packageName], for a stable compare. */
        val key: String get() = "$packageName/$activityName"
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ts8-apps").apply { isDaemon = true }
    }

    private val pm: PackageManager get() = appContext.packageManager

    /** Cached result, so returning to the drawer does not re-query. */
    @Volatile
    private var cached: List<AppEntry>? = null

    /**
     * Loads the list, calling back on the main thread.
     *
     * @param force re-read from PackageManager instead of the cache; used after
     *        an install event, which this build does not observe yet.
     */
    fun load(force: Boolean = false, onLoaded: (List<AppEntry>) -> Unit) {
        cached?.let { if (!force) { onLoaded(it); return } }
        loader.execute {
            val list = query()
            cached = list
            main.post { onLoaded(list) }
        }
    }

    private fun query(): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = runCatching {
            pm.queryIntentActivities(intent, 0)
        }.getOrDefault(emptyList())

        val collator = Collator.getInstance(Locale("vi", "VN")).apply {
            // A Vietnamese collator sorts "Đ" next to "D" and puts diacritics
            // where a driver expects them; plain code-point order scatters it.
            strength = Collator.PRIMARY
        }

        val entries = resolved.mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            val pkg = activity.packageName ?: return@mapNotNull null
            val name = activity.name ?: return@mapNotNull null
            // Our own drawer is not an app the driver should open from itself.
            if (pkg == appContext.packageName) return@mapNotNull null
            val label = runCatching {
                info.loadLabel(pm)?.toString()
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
            AppEntry(
                packageName = pkg,
                activityName = name,
                label = label,
                sortKey = VietnameseText.fold(label)
            )
        }.distinctBy { it.key }

        return entries.sortedWith { a, b ->
            val byName = collator.compare(a.sortKey, b.sortKey)
            if (byName != 0) byName else a.key.compareTo(b.key)
        }
    }

    /** The app's own icon, or null when the package will not give one. */
    fun iconFor(entry: AppEntry): Drawable? = runCatching {
        pm.getActivityIcon(
            android.content.ComponentName(entry.packageName, entry.activityName)
        )
    }.getOrNull()

    /**
     * The installed app whose label folds to [spoken], or null.
     *
     * **Why synchronous here while [load] is not.** A voice command has already
     * spoken the driver's words and cannot return a "pending" outcome — the
     * router has no such state — so the answer has to be available when this
     * returns. The work is the same `queryIntentActivities` call [load] makes,
     * it is cached afterwards, and the caller runs it on the main thread inside
     * the executor's budget. Being synchronous is what makes "không tìm thấy
     * ứng dụng" true when it is said.
     *
     * **Exact folded match only.** A prefix match would resolve "nhạc" to
     * whichever of several music apps sorts first, which is a wrong app launched
     * confidently. Exact-only means a label the driver mispronounced produces a
     * failure they can hear and correct.
     */
    fun findByLabel(spoken: String): AppEntry? {
        val wanted = normalize(spoken)
        if (wanted.isEmpty()) return null
        val list = cached ?: query().also { cached = it }
        return list.firstOrNull { normalize(it.label) == wanted }
    }

    /**
     * Folding plus whitespace collapsing, for the comparison only.
     *
     * [VietnameseText.fold] is used unchanged for sorting, where keeping the
     * label's own spacing is harmless; matching needs the spoken form and the
     * label to agree on spacing, because the parser joins tokens with single
     * spaces while a label may carry a double space or a trailing one.
     */
    private fun normalize(text: String): String =
        VietnameseText.fold(text).replace(WHITESPACE, " ").trim()

    /**
     * Builds the intent that opens [entry].
     *
     * An explicit component first: it is the one that the launcher's own
     * resolution just verified, so it cannot open a chooser. The exception is a
     * package that shares a process alias — the explicit form still works
     * there, because the component is what the system resolved.
     */
    fun intentFor(entry: AppEntry): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(entry.packageName, entry.activityName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    fun shutdown() {
        loader.shutdownNow()
    }

    private companion object {
        private val WHITESPACE = Regex("\\s+")
    }
}

/**
 * Lowercase, diacritic-free folding for sorting and filtering.
 *
 * Deliberately a small local helper rather than a dependency on the
 * `place-search` module's `VietnameseText`: the app layer should not reach into
 * a core module for a six-line function, and the two have different jobs —
 * that one folds for FTS matching, this one only for `Collator` ordering.
 */
internal object VietnameseText {

    private val folds = mapOf(
        'à' to 'a', 'á' to 'a', 'ạ' to 'a', 'ả' to 'a', 'ã' to 'a',
        'â' to 'a', 'ầ' to 'a', 'ấ' to 'a', 'ậ' to 'a', 'ẩ' to 'a', 'ẫ' to 'a',
        'ă' to 'a', 'ằ' to 'a', 'ắ' to 'a', 'ặ' to 'a', 'ẳ' to 'a', 'ẵ' to 'a',
        'è' to 'e', 'é' to 'e', 'ẹ' to 'e', 'ẻ' to 'e', 'ẽ' to 'e',
        'ê' to 'e', 'ề' to 'e', 'ế' to 'e', 'ệ' to 'e', 'ể' to 'e', 'ễ' to 'e',
        'ì' to 'i', 'í' to 'i', 'ị' to 'i', 'ỉ' to 'i', 'ĩ' to 'i',
        'ò' to 'o', 'ó' to 'o', 'ọ' to 'o', 'ỏ' to 'o', 'õ' to 'o',
        'ô' to 'o', 'ồ' to 'o', 'ố' to 'o', 'ộ' to 'o', 'ổ' to 'o', 'ỗ' to 'o',
        'ơ' to 'o', 'ờ' to 'o', 'ớ' to 'o', 'ợ' to 'o', 'ở' to 'o', 'ỡ' to 'o',
        'ù' to 'u', 'ú' to 'u', 'ụ' to 'u', 'ủ' to 'u', 'ũ' to 'u',
        'ư' to 'u', 'ừ' to 'u', 'ứ' to 'u', 'ự' to 'u', 'ử' to 'u', 'ữ' to 'u',
        'ỳ' to 'y', 'ý' to 'y', 'ỵ' to 'y', 'ỷ' to 'y', 'ỹ' to 'y',
        'đ' to 'd'
    )

    fun fold(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        val out = StringBuilder(lower.length)
        for (ch in lower) {
            out.append(folds[ch] ?: ch)
        }
        return out.toString()
    }
}
