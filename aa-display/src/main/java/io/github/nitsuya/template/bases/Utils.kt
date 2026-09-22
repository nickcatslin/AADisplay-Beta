package io.github.nitsuya.template.bases

import android.content.res.Configuration
import android.content.res.Resources
import android.util.Log
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.core.view.WindowInsetsCompat
import io.github.nitsuya.aa.display.App
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

val WindowInsetsCompat.maxSystemBarsDisplayCutout
    get() = getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())

val WindowInsetsCompat.maxSystemBarsDisplayCutoutIme
    get() = getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())

private const val COROUTINE_TAG = "AADisplay_Coroutine"

/**
 * Logs an uncaught coroutine exception instead of letting it reach the thread's
 * UncaughtExceptionHandler. runMain/runIO are used inside system_server (via the
 * Xposed hook); an uncaught exception there kills system_server and reboots the phone.
 */
private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
    Log.e(COROUTINE_TAG, "Uncaught exception in coroutine", throwable)
    // XposedBridge only exists in hooked processes (system_server / Android Auto);
    // in the module's own app process the class is absent, so this is best-effort.
    runCatching { de.robv.android.xposed.XposedBridge.log(throwable) }
}

/**
 * Shared application-wide scope. SupervisorJob so one failed child never cancels
 * the others; the handler above swallows and logs anything a child throws.
 */
val appCoroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + coroutineExceptionHandler)

fun runMain(block: suspend CoroutineScope.() -> Unit) =
    appCoroutineScope.launch(Dispatchers.Main, block = block)

fun runIO(block: suspend CoroutineScope.() -> Unit) =
    appCoroutineScope.launch(Dispatchers.IO, block = block)

fun runNewThread(name: String? = null, block: () -> Unit) =
    (if (name != null) Thread(block, name) else Thread(block)).start()

val isSystemNightMode
    get() = when (App.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
        Configuration.UI_MODE_NIGHT_YES -> true
        Configuration.UI_MODE_NIGHT_NO -> false
        else -> null
    }

fun Resources.Theme.getAttr(@AttrRes id: Int) =
    TypedValue().apply { resolveAttribute(id, this, true) }

fun Number.dpToPx() =
    TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), App.resources.displayMetrics
    ).toInt()