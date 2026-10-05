package io.github.nitsuya.aa.display.xposed.hook

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.IPackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.SparseBooleanArray
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
            // Record display interactivity from boot on, so a virtual display created while the
            // phone sleeps can be made interactive immediately (see VirtualDisplayKeepOn).
            runCatching { VirtualDisplayKeepOn.installInputRecorder() }
                .onFailure { log(tagName, "VirtualDisplayKeepOn: input recorder install failed", it) }
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
        private const val CLASS_DISPLAY_POWER_REQUEST = "android.hardware.display.DisplayManagerInternal\$DisplayPowerRequest"
        // Android 14 shipped both (flag-selected); 15+ only has DisplayPowerController.
        private val CLASSES_DISPLAY_POWER_CONTROLLER = arrayOf(
            "com.android.server.display.DisplayPowerController",
            "com.android.server.display.DisplayPowerController2",
        )
        private const val POLICY_BRIGHT = 3 // DisplayPowerRequest.POLICY_BRIGHT

        /**
         * Upstream choke point. Field test on a Pixel / Android 17 showed that forcing the device
         * state alone is not enough: the car still goes black although every STATE_OFF request was
         * rewritten. The reason is the per-display DisplayPowerController: on an OFF policy it
         * first drops the ColorFade level to 0 (a black surface on the display's layer stack) and
         * only then asks for STATE_OFF. So the request itself must never reach our display: when
         * PowerManager hands the controller for our displayId a non-BRIGHT policy, substitute a
         * copy of the request whose policy is BRIGHT. The request object is shared with every
         * other display of the group, hence the copy.
         */
        private val dpcRequestPowerState by lazy {
            if(!IsSystemEnv) return@lazy null
            CLASSES_DISPLAY_POWER_CONTROLLER.flatMap { cls ->
                try {
                    findAllMethods(loadClass(cls)) {
                        name == "requestPowerState"
                            && parameterCount == 2
                            && parameterTypes[0].name == CLASS_DISPLAY_POWER_REQUEST
                            && parameterTypes[1] == Boolean::class.javaPrimitiveType
                    }.toList()
                } catch (e: Throwable) {
                    log(tagName, "VirtualDisplayKeepOn: $cls.requestPowerState unavailable (${e.javaClass.simpleName})")
                    emptyList()
                }
            }.also {
                if (it.isEmpty()) log(tagName, "VirtualDisplayKeepOn: no DisplayPowerController.requestPowerState found")
            }
        }

        /**
         * Input side. Android 17 (verified on the phone's own services.jar) assigns every virtual
         * display to the default display group when DisplayManagerFlags.separateTimeouts is on:
         * DisplayGroupAllocator only grants a separate group ("secondary_mode") to desktop-capable
         * EXTERNAL / OVERLAY displays, so FLAG_OWN_DISPLAY_GROUP is ignored for us and the display
         * is reported non-interactive together with the phone screen. The dispatcher then routes
         * every injected touch / key through the non-interactive policy and drops it. PowerManager
         * publishes that state through InputManagerInternal.setDisplayInteractivities(); mark our
         * display interactive in a copy of the map before it reaches InputManagerService.
         */
        private val imsSetDisplayInteractivities by lazy {
            if(!IsSystemEnv) return@lazy null
            try {
                findAllMethods(loadClass("com.android.server.input.InputManagerService\$LocalService")) {
                    name == "setDisplayInteractivities"
                        && parameterCount == 1
                        && parameterTypes[0] == SparseBooleanArray::class.java
                }.also {
                    if (it.isEmpty()) log(tagName, "VirtualDisplayKeepOn: InputManagerService.setDisplayInteractivities not found (pre-Android 15?)")
                }
            } catch (e: Throwable) {
                log(tagName, "VirtualDisplayKeepOn: InputManagerService\$LocalService unavailable (${e.javaClass.simpleName})")
                null
            }
        }

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
        @Volatile private var displayId: Int = -1
        private var hooks: List<XC_MethodHook.Unhook> = emptyList()

        // Input side is installed once, at system ready, and stays installed. PowerManager only
        // publishes display interactivity on a wakefulness / group change, so when AADisplay is
        // started while the phone is already asleep the "non-interactive" map that includes our
        // new display was sent before [hook] ran, and nothing re-sent it until the phone woke up
        // (field report: first start with the screen off -> no touch until one wake cycle).
        // Recording the last map lets [hook] replay it immediately through the patched method.
        private val inputLock = Any()
        private var inputRecorder: List<XC_MethodHook.Unhook>? = null
        private var lastImsService: Any? = null
        private var lastInteractivities: SparseBooleanArray? = null

        fun installInputRecorder() {
            if (inputRecorder != null) return
            inputRecorder = imsSetDisplayInteractivities?.hookBefore { param ->
                try {
                    val map = param.args[0] as? SparseBooleanArray ?: return@hookBefore
                    synchronized(inputLock) {
                        lastImsService = param.thisObject
                        lastInteractivities = map.clone()
                    }
                    val target = displayId
                    if (target < 0) return@hookBefore
                    // Absent = interactive as far as InputManagerService is concerned; only flip false.
                    if (map.indexOfKey(target) < 0 || map.get(target)) return@hookBefore
                    param.args[0] = map.clone().apply { put(target, true) }
                    log(tagName, "VirtualDisplayKeepOn: display $target reported non-interactive -> kept interactive")
                } catch (e: Throwable) {
                    log(tagName, "VirtualDisplayKeepOn setDisplayInteractivities hook error", e)
                }
            } ?: emptyList()
            log(tagName, "VirtualDisplayKeepOn: input interactivity recorder installed (${inputRecorder?.size ?: 0} method)")
        }

        /** Re-sends the last interactivity map; the recorder hook applies the current override. */
        private fun replayInteractivities() {
            val (service, map) = synchronized(inputLock) { lastImsService to lastInteractivities?.clone() }
            if (service == null || map == null) {
                log(tagName, "VirtualDisplayKeepOn: no interactivity map recorded yet, nothing to replay")
                return
            }
            val method = imsSetDisplayInteractivities?.firstOrNull() ?: return
            runCatching { method.invoke(service, map) }
                .onSuccess { log(tagName, "VirtualDisplayKeepOn: replayed display interactivities ($map)") }
                .onFailure { log(tagName, "VirtualDisplayKeepOn: replay of display interactivities failed", it) }
        }

        /**
         * @param name the VirtualDisplay name passed to DisplayManager.createVirtualDisplay().
         * @param id   its logical display id.
         */
        fun hook(name: String, id: Int) {
            unHook()
            displayName = name
            displayId = id
            val dpcHooks = dpcRequestPowerState?.hookBefore { param ->
                try {
                    val target = displayId
                    if (target < 0 || param.thisObject.getObjectOrNull("mDisplayId") != target) return@hookBefore
                    val request = param.args[0] ?: return@hookBefore
                    val policyField = request.javaClass.getField("policy")
                    val policy = policyField.getInt(request)
                    if (policy == POLICY_BRIGHT) return@hookBefore
                    val copy = request.javaClass.getConstructor(request.javaClass).newInstance(request)
                    policyField.setInt(copy, POLICY_BRIGHT)
                    param.args[0] = copy
                    log(tagName, "VirtualDisplayKeepOn: display $target power policy $policy -> BRIGHT")
                } catch (e: Throwable) {
                    log(tagName, "VirtualDisplayKeepOn requestPowerState hook error", e)
                }
            } ?: emptyList()
            installInputRecorder() // normally already done at system ready
            val deviceHooks = requestDisplayStateLocked?.hookBefore { param ->
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
            hooks = dpcHooks + deviceHooks
            log(tagName, "VirtualDisplayKeepOn: hooked ${dpcHooks.size} requestPowerState + ${inputRecorder?.size ?: 0} setDisplayInteractivities + ${deviceHooks.size} requestDisplayStateLocked for '$name' (display $id)")
            // The display may already be marked non-interactive (created while the phone sleeps).
            replayInteractivities()
        }

        fun unHook() {
            hooks.forEach { it.unhook() }
            hooks = emptyList()
            val wasActive = displayId >= 0
            displayName = null
            displayId = -1
            if (wasActive) replayInteractivities() // hand the honest state back to input
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