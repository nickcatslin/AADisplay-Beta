package io.github.nitsuya.aa.display.xposed.hook

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.IPackageManager
import android.content.res.Configuration
import android.os.Build
import android.view.Display
import com.github.kyuubiran.ezxhelper.utils.*
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.nitsuya.aa.display.IsSystemEnv
import io.github.nitsuya.aa.display.xposed.BridgeService
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.log
import io.github.qauxv.util.Initiator

object AndroidHook : BaseHook() {
    override val tagName: String = "AAD_AndroidHook"
    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        Initiator.init(lpparam.classLoader)
        log(tagName, "xposed init")
        var serviceManagerHook: XC_MethodHook.Unhook? = null
        serviceManagerHook = findMethod("android.os.ServiceManager") {
            name == "addService"
        }.hookBefore { param ->
            if (param.args[0] == "package") {
                serviceManagerHook?.unhook()
                val pms = param.args[1] as IPackageManager
                log(tagName, "Got pms: $pms")
                runCatching {
                    BridgeService.register(pms)
                    log(tagName, "Bridge service injected")
                }.onFailure {
                    log(tagName, "System service crashed", it)
                }
            }
        }

        var activityManagerServiceConstructorHook: List<XC_MethodHook.Unhook> = emptyList()
        activityManagerServiceConstructorHook = findAllConstructors("com.android.server.am.ActivityManagerService") {
            parameterTypes[0] == Context::class.java
        }.hookAfter {
            activityManagerServiceConstructorHook.forEach { hook -> hook.unhook() }
            CoreManagerService.systemContext = it.thisObject.getObjectAs("mUiContext")
            log(tagName, "get systemUiContext")
        }.also {
            if (it.isEmpty())
                log(tagName, "no constructor with parameterTypes[0] == Context found")
        }

        var activityManagerServiceSystemReadyHook: XC_MethodHook.Unhook? = null
        activityManagerServiceSystemReadyHook = findMethod("com.android.server.am.ActivityManagerService") {
            name == "systemReady"
        }.hookAfter {
            activityManagerServiceSystemReadyHook?.unhook()
            CoreManagerService.systemReady()
            log(tagName, "system ready")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {//10+
            var className = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)  //12+
                "com.android.server.wm.ActivityTaskSupervisor"
            else  //10+
                "com.android.server.wm.ActivityStackSupervisor"
            findMethod(className){
                name == "isCallerAllowedToLaunchOnDisplay"
                && parameterCount == 4
                && parameterTypes[0] == Int::class.javaPrimitiveType //callingPid
                && parameterTypes[1] == Int::class.javaPrimitiveType //callingUid
                && parameterTypes[2] == Int::class.javaPrimitiveType //launchDisplayId
                && parameterTypes[3] == ActivityInfo::class.java
            }.hookAfter { param ->
                if((param.result as Boolean).not() && param.args[2] == CoreManagerService.getDisplayId()){
                    param.result = true
                    log(tagName,"hook isCallerAllowedToLaunchOnDisplay success")
                }
            }
        }

    }

    /**
     * "Screen Off Only" (AADisplayConfig.ScreenOffReplaceLockScreen).
     *
     * When the phone goes to sleep (power key / timeout) DisplayManager asks every display of the
     * sleeping power group to switch to STATE_OFF via
     * VirtualDisplayAdapter$VirtualDisplayDevice.requestDisplayStateLocked(). For our public
     * PRESENTATION display that has two visible effects: the display is moved to the blank layer
     * stack (black stream on the car) and WindowManager acquires a "Display-off" sleep token for
     * it, which pauses every activity on it. In theory the display-bound SCREEN_BRIGHT wake lock
     * held by DisplayWindow keeps our own display group awake, but on some builds (observed on a
     * Pixel running Android 17) the projection still stops the moment the phone screen goes off.
     *
     * This hook makes the AADisplay virtual display immune: any requested state other than
     * STATE_ON is rewritten to STATE_ON for the device whose name matches ours. Only that device
     * is touched; Android Auto's own virtual displays and everything else are left alone.
     * Signature-agnostic (the method gained a 4th parameter in Android 15), matched by name.
     */
    object VirtualDisplayKeepOn {
        private const val CLASS_VIRTUAL_DISPLAY_DEVICE = "com.android.server.display.VirtualDisplayAdapter\$VirtualDisplayDevice"

        private val requestDisplayStateLocked by lazy {
            if(!IsSystemEnv) return@lazy null
            try {
                findAllMethods(loadClass(CLASS_VIRTUAL_DISPLAY_DEVICE)) {
                    name == "requestDisplayStateLocked"
                        && parameterCount >= 1
                        && parameterTypes[0] == Int::class.javaPrimitiveType // state
                }.also {
                    if (it.isEmpty()) log(tagName, "VirtualDisplayKeepOn: requestDisplayStateLocked not found")
                }
            } catch (e: Throwable) {
                log(tagName, "VirtualDisplayKeepOn: $CLASS_VIRTUAL_DISPLAY_DEVICE.requestDisplayStateLocked", e)
                null
            }
        }

        @Volatile private var displayName: String? = null
        private var hooks: List<XC_MethodHook.Unhook> = emptyList()

        /** @param name the VirtualDisplay name passed to DisplayManager.createVirtualDisplay(). */
        fun hook(name: String) {
            unHook()
            displayName = name
            hooks = requestDisplayStateLocked?.hookBefore { param ->
                try {
                    val target = displayName ?: return@hookBefore
                    if (param.thisObject.getObjectOrNull("mName") != target) return@hookBefore
                    val state = param.args[0] as Int
                    if (state != Display.STATE_ON) {
                        log(tagName, "VirtualDisplayKeepOn: '$target' requested state $state -> forced STATE_ON")
                        param.args[0] = Display.STATE_ON
                    }
                } catch (e: Throwable) {
                    log(tagName, "VirtualDisplayKeepOn hook error", e)
                }
            } ?: emptyList()
            log(tagName, "VirtualDisplayKeepOn: hooked ${hooks.size} method(s) for '$name'")
        }

        fun unHook() {
            hooks.forEach { it.unhook() }
            hooks = emptyList()
            displayName = null
        }
    }

    object FuckAppUseApplicationContext {
        private val appInitUseDisplay: HashMap<String, Int> = hashMapOf()
        private val activityTaskManagerService_startProcessAsync by lazy {
            if(!IsSystemEnv) return@lazy null
            try{
                findMethod("com.android.server.wm.ActivityTaskManagerService"){
                    name == "startProcessAsync"
                }
            } catch (e: Throwable){
                log(tagName,  "FuckAppUseAppContext ActivityTaskManagerService.startProcessAsync method", e)
                null
            }
        }
        private val applicationThread_bindApplication by lazy {
            if(!IsSystemEnv) return@lazy null
            try{
                findMethod("android.app.IApplicationThread\$Stub\$Proxy"){
                    name == "bindApplication"
                }
            } catch (e: Throwable){
                log(tagName,  "FuckAppUseAppContext IApplicationThread.bindApplication method", e)
                null
            }
        }

        private var activityTaskManagerService_startProcessAsync_hook : XC_MethodHook.Unhook? = null
        private var applicationThread_bindApplication_hook : XC_MethodHook.Unhook? = null
        fun hook(){
            unHook()
            activityTaskManagerService_startProcessAsync_hook  = activityTaskManagerService_startProcessAsync?.hookBefore { param ->
                try {
                    val activityRecord = param.args[0]
                    val displayId = activityRecord.invokeMethod("getDisplayId") as Int
                    val packageName = activityRecord.getObject("packageName") as String
                    if(displayId == 0){
                        if(appInitUseDisplay.containsKey(packageName)){
                            appInitUseDisplay.remove(packageName)
                        }
                        return@hookBefore
                    }
                    appInitUseDisplay[packageName] = displayId
                } catch (e: Exception) {
                    log(tagName, "activityTaskManagerService_startProcessAsync Hook Exception", e)
                }
            }
            applicationThread_bindApplication_hook = applicationThread_bindApplication?.hookBefore { param ->
                try {
                    val configuration = param.args[15]
                    if(configuration !is Configuration){
                        return@hookBefore
                    }
                    val packageName = (param.args[0] as String).run {
                        this.substringBeforeLast(":")
                    }
                    if(appInitUseDisplay.containsKey(packageName)){
                        val densityDpi = CoreManagerService.getDensityDpi()
                        if(densityDpi != 0){
                            configuration.densityDpi = densityDpi
                        }
                    }
                } catch (e: Exception) {
                    log(tagName, "applicationThread_bindApplication Hook Exception", e)
                }
            }
        }

        fun unHook(){
            appInitUseDisplay.clear()
            activityTaskManagerService_startProcessAsync_hook?.apply { unhook() }
            activityTaskManagerService_startProcessAsync_hook = null

            applicationThread_bindApplication_hook?.apply { unhook() }
            applicationThread_bindApplication_hook = null
        }

    }
}