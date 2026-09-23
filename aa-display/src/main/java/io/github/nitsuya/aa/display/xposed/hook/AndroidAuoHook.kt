package io.github.nitsuya.aa.display.xposed.hook

import android.app.Application
import android.app.Instrumentation
import android.content.SharedPreferences
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.xposed.hook.aa.AaBasicsHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaBtnEventHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaDpiHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaPropsHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaSignatureHook
import io.github.nitsuya.aa.display.xposed.hook.aa.AaUiHook
import io.github.nitsuya.aa.display.xposed.log
import org.luckypray.dexkit.DexKitBridge
import kotlin.system.measureTimeMillis


abstract class AaHook {
    companion object {
        const val processMain =       "com.google.android.projection.gearhead"
        const val processProjection = "com.google.android.projection.gearhead:projection"
        const val processCar =        "com.google.android.projection.gearhead:car"
    }
    abstract val tagName: String
    abstract fun isSupportProcess(processName: String) : Boolean
    open fun loadDexClass(bridge: DexKitBridge, lpparam: XC_LoadPackage.LoadPackageParam) {}
    abstract fun hook(config: SharedPreferences?, lpparam: XC_LoadPackage.LoadPackageParam)
}

object AndroidAuoHook : BaseHook() {
    override val tagName: String = "AAD_AndroidAuoHook"
    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        val processName = lpparam.processName
        val hooks = listOf(AaBasicsHook, AaSignatureHook, AaDpiHook, AaBtnEventHook, AaUiHook, AaPropsHook).filter { i -> i.isSupportProcess(processName) }
        if(hooks.isEmpty()) return

        // The config file only exists once the module app has run at least once. Do not
        // give up when it is missing: every AADisplayConfig getter falls back to its default
        // for a null config, and the signature / UI hooks are needed regardless of settings.
        // Note: LSPosed redirects world-readable prefs to /data/misc/<uuid>/prefs/<module>/,
        // so log the resolved path; it is the first thing to check when settings "don't apply".
        val xPrefs = XSharedPreferences(BuildConfig.APPLICATION_ID, AADisplayConfig.ConfigName)
        val configPreferences: SharedPreferences? = xPrefs.takeIf { it.file.canRead() }
        if (configPreferences == null) {
            log(tagName, "config not readable at ${xPrefs.file.path} (module app never run?), using defaults")
        } else {
            log(tagName, "config loaded from ${xPrefs.file.path}")
        }

        var onCreateApplication: XC_MethodHook.Unhook? = null
        onCreateApplication = findMethod(Instrumentation::class.java) {
            name == "callApplicationOnCreate"
            && parameterCount == 1
            && parameterTypes[0] == Application::class.java
        }.hookBefore {
            onCreateApplication?.unhook()
            EzXHelperInit.initAppContext()
            System.loadLibrary("dexkit")
            DexKitBridge.create(lpparam.appInfo.sourceDir).use { bridge ->
                if(bridge == null){
                    log(tagName,"DexKitBridge.create() failed")
                    return@hookBefore
                }
                val measureTimeMillis = measureTimeMillis {
                    hooks.forEach { h ->
                        // 单个子 Hook 的 loadDexClass 抛异常不得中断其余子 Hook
                        // （否则一个 AA 升级击穿点会连累整条 hook 链，例：AaDpiHook
                        //  在 AaUiHook 之前，它抛 NoSuchMethodException 会让竖排栏也失效）。
                        runCatching { h.loadDexClass(bridge, lpparam) }
                            .onFailure { e -> log(tagName, "${h.tagName} loadDexClass failed", e) }
                    }
                }
                log(tagName,"${lpparam.processName} load class measure ${measureTimeMillis}ms")
            }
            hooks.forEach { h ->
                runCatching { h.hook(configPreferences, lpparam) }
                    .onFailure { e -> log(tagName, "${h.tagName} hook failed", e) }
            }
        }
    }
}


