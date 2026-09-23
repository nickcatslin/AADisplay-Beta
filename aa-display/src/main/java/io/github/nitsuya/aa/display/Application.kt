package io.github.nitsuya.aa.display

import android.content.Context
import android.os.Process
import com.github.kyuubiran.ezxhelper.utils.tryOrNull
import com.google.android.material.color.DynamicColors
import com.topjohnwu.superuser.Shell
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.CoreManager


val IsSystemEnv by lazy {
    Process.myUid() == 1000
}
val CoreApi by lazy {
    if(!IsSystemEnv) CoreManager
    else CoreManagerService.instance!!
}
lateinit var App : Application
class Application: android.app.Application() {
    init {
        App = this
        tryOrNull {
            Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(30))
        }
    }

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        ensureConfigFileExists()
    }

    /**
     * system_server and the Android Auto hooks read our settings through XSharedPreferences,
     * which needs the world-readable file to exist. It is only written when the settings
     * screen saves something, so on a fresh install nothing exists and both sides fall back
     * to defaults. Create it on first start so the file is there as soon as this process
     * runs (settings screen, CarActivityService or ShellManagerService bind).
     */
    @Suppress("DEPRECATION")
    private fun ensureConfigFileExists() {
        tryOrNull {
            val prefs = getSharedPreferences(AADisplayConfig.ConfigName, Context.MODE_WORLD_READABLE)
            if (!prefs.contains(CONFIG_INIT_KEY)) {
                prefs.edit().putBoolean(CONFIG_INIT_KEY, true).commit()
            }
        }
    }

    companion object {
        private const val CONFIG_INIT_KEY = "_initialized"
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
    }
}