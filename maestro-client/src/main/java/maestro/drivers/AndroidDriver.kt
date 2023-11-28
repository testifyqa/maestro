/*
 *
 *  Copyright (c) 2022 mobile.dev inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package maestro.drivers

import com.google.protobuf.ByteString
import dadb.AdbShellPacket
import dadb.AdbShellResponse
import dadb.AdbShellStream
import dadb.Dadb
import io.grpc.ManagedChannelBuilder
import maestro.*
import maestro.UiElement.Companion.toUiElementOrNull
import maestro.android.AndroidAppFiles
import maestro.android.AndroidLaunchArguments.toAndroidLaunchArguments
import maestro.utils.BlockingStreamObserver
import maestro.utils.MaestroTimer
import maestro.utils.ScreenshotUtils
import maestro.utils.StringUtils.toRegexSafe
import maestro_android.*
import net.dongliu.apk.parser.ApkFile
import okio.*
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.*
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.use

class AndroidDriver(
    private val dadb: Dadb,
    private val hostPort: Int = 7001,
) : Driver {

    private val channel = ManagedChannelBuilder.forAddress("localhost", hostPort)
        .usePlaintext()
        .build()
    private val blockingStub = MaestroDriverGrpc.newBlockingStub(channel)
    private val asyncStub = MaestroDriverGrpc.newStub(channel)
    private val documentBuilderFactory = DocumentBuilderFactory.newInstance()

    private var instrumentationSession: AdbShellStream? = null
    private var proxySet = false

    override fun name(): String {
        return "Android Device ($dadb)"
    }

    override fun open() {
        uninstallMaestroApks()
        installMaestroApks()
        startInstrumentationSession()

        try {
            awaitLaunch()
        } catch (ignored: InterruptedException) {
            instrumentationSession?.close()
            return
        }

        allocateForwarder()
    }

    private fun startInstrumentationSession() {
        val startTime = System.currentTimeMillis()
        val apiLevel = getDeviceApiLevel()

        val instrumentationCommand = buildString {
            append("am instrument -w ")
            if (apiLevel >= 26) append("-m ")
            append("-e debug false ")
            append("-e class 'dev.mobile.maestro.MaestroDriverService#grpcServer' ")
            append("dev.mobile.maestro.test/androidx.test.runner.AndroidJUnitRunner &\n")
        }

        while (System.currentTimeMillis() - startTime < getStartupTimeout()) {
            instrumentationSession = dadb.openShell(instrumentationCommand)

            if (instrumentationSession.successfullyStarted()) {
                return
            }

            instrumentationSession?.close()
            Thread.sleep(100)
        }
        throw TimeoutException("Maestro instrumentation could not be initialized")
    }

    private fun getDeviceApiLevel(): Int {
        val response = dadb.openShell("getprop ro.build.version.sdk").readAll()
        if (response.exitCode != 0) {
            throw IOException("Failed to get device API level: ${response.errorOutput}")
        }
        return response.output.trim().toIntOrNull() ?: throw IOException("Invalid API level: ${response.output}")
    }

    private fun allocateForwarder() {
        PORT_TO_FORWARDER[hostPort]?.close()
        PORT_TO_ALLOCATION_POINT[hostPort]?.let {
            LOGGER.warn("Port $hostPort was already allocated. Allocation point: $it")
        }

        PORT_TO_FORWARDER[hostPort] = dadb.tcpForward(
            hostPort,
            7001
        )
        PORT_TO_ALLOCATION_POINT[hostPort] = Exception().stackTraceToString()
    }

    private fun awaitLaunch() {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < getStartupTimeout()) {
            runCatching {
                dadb.open("tcp:7001").close()
                return
            }
            Thread.sleep(100)
        }

        throw TimeoutException("Maestro Android driver did not start up in time")
    }

    override fun close() {
        if (proxySet) {
            resetProxy()
        }

        PORT_TO_FORWARDER[hostPort]?.close()
        PORT_TO_FORWARDER.remove(hostPort)
        PORT_TO_ALLOCATION_POINT.remove(hostPort)
        uninstallMaestroApks()
        instrumentationSession?.close()
        instrumentationSession = null
        channel.shutdown()

        if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
            throw TimeoutException("Couldn't close Maestro Android driver due to gRPC timeout")
        }
    }

    override fun deviceInfo(): DeviceInfo {
        val response = blockingStub.deviceInfo(deviceInfoRequest {})

        return DeviceInfo(
            platform = Platform.ANDROID,
            widthPixels = response.widthPixels,
            heightPixels = response.heightPixels,
            widthGrid = response.widthPixels,
            heightGrid = response.heightPixels,
        )
    }

    override fun launchApp(
        appId: String,
        launchArguments: Map<String, Any>,
        sessionId: UUID?,
    ) {
        if (!isPackageInstalled(appId)) {
            throw IllegalArgumentException("Package $appId is not installed")
        }

        val arguments = launchArguments.toAndroidLaunchArguments()
        val sessionUUID = sessionId ?: UUID.randomUUID()
        dadb.shell("setprop debug.maestro.sessionId $sessionUUID")
        blockingStub.launchApp(
            launchAppRequest {
                this.packageName = appId
                this.arguments.addAll(arguments)
            }
        ) ?: throw IllegalStateException("Maestro driver failed to launch app")
    }

    override fun stopApp(appId: String) {
        // Note: If the package does not exist, this call does *not* throw an exception
        shell("am force-stop $appId")
    }

    override fun clearAppState(appId: String) {
        if (!isPackageInstalled(appId)) {
            return
        }

        shell("pm clear $appId")
    }

    override fun clearKeychain() {
        // No op
    }

    override fun tap(point: Point) {
        blockingStub.tap(
            tapRequest {
                x = point.x
                y = point.y
            }
        ) ?: throw IllegalStateException("Response can't be null")
    }

    override fun longPress(point: Point) {
        dadb.shell("input swipe ${point.x} ${point.y} ${point.x} ${point.y} 3000")
    }

    override fun pressKey(code: KeyCode) {
        val intCode: Int = when (code) {
            KeyCode.ENTER -> 66
            KeyCode.BACKSPACE -> 67
            KeyCode.BACK -> 4
            KeyCode.VOLUME_UP -> 24
            KeyCode.VOLUME_DOWN -> 25
            KeyCode.HOME -> 3
            KeyCode.LOCK -> 276
            KeyCode.REMOTE_UP -> 19
            KeyCode.REMOTE_DOWN -> 20
            KeyCode.REMOTE_LEFT -> 21
            KeyCode.REMOTE_RIGHT -> 22
            KeyCode.REMOTE_CENTER -> 23
            KeyCode.REMOTE_PLAY_PAUSE -> 85
            KeyCode.REMOTE_STOP -> 86
            KeyCode.REMOTE_NEXT -> 87
            KeyCode.REMOTE_PREVIOUS -> 88
            KeyCode.REMOTE_REWIND -> 89
            KeyCode.REMOTE_FAST_FORWARD -> 90
            KeyCode.POWER -> 26
        }

        dadb.shell("input keyevent $intCode")
        Thread.sleep(300)
    }

    override fun contentDescriptor(): TreeNode {
        val response = try {
            blockingStub.viewHierarchy(viewHierarchyRequest {})
        } catch (ignored: Exception) {
            // There is a bug in Android UiAutomator that rarely throws an NPE while dumping a view hierarchy.
            // Trying to recover once by giving it a bit of time to settle.

            LOGGER.error("Failed to get view hierarchy", ignored)

            MaestroTimer.sleep(MaestroTimer.Reason.BUFFER, 1000L)

            // If the connection is broken, getting the view hierarchy will wait indefinitely
            getViewHierarchyWithTimeout() ?: throw ignored
        }

        val document = documentBuilderFactory
            .newDocumentBuilder()
            .parse(response.hierarchy.byteInputStream())

        return mapHierarchy(document)
    }

    private fun getViewHierarchyWithTimeout(): MaestroAndroid.ViewHierarchyResponse? {
        val future: Future<MaestroAndroid.ViewHierarchyResponse> = Executors.newSingleThreadExecutor().submit<MaestroAndroid.ViewHierarchyResponse> {
            blockingStub.viewHierarchy(viewHierarchyRequest {})
        }
        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            LOGGER.error("Timeout while fetching view hierarchy", e)
            null
        }
    }

    override fun scrollVertical() {
        swipe(SwipeDirection.UP, 400)
    }

    override fun swipe(start: Point, end: Point, durationMs: Long) {
        dadb.shell("input swipe ${start.x} ${start.y} ${end.x} ${end.y} $durationMs")
    }

    override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) {
        val deviceInfo = deviceInfo()
        when (swipeDirection) {
            SwipeDirection.UP -> {
                val startX = (deviceInfo.widthGrid * 0.5f).toInt()
                val startY = (deviceInfo.heightGrid * 0.5f).toInt()
                val endX = (deviceInfo.widthGrid * 0.5f).toInt()
                val endY = (deviceInfo.heightGrid * 0.1f).toInt()
                directionalSwipe(
                    durationMs,
                    Point(startX, startY),
                    Point(endX, endY)
                )
            }
            SwipeDirection.DOWN -> {
                val startX = (deviceInfo.widthGrid * 0.5f).toInt()
                val startY = (deviceInfo.heightGrid * 0.2f).toInt()
                val endX = (deviceInfo.widthGrid * 0.5f).toInt()
                val endY = (deviceInfo.heightGrid * 0.9f).toInt()
                directionalSwipe(
                    durationMs,
                    Point(startX, startY),
                    Point(endX, endY)
                )
            }
            SwipeDirection.RIGHT -> {
                val startX = (deviceInfo.widthGrid * 0.1f).toInt()
                val startY = (deviceInfo.heightGrid * 0.5f).toInt()
                val endX = (deviceInfo.widthGrid * 0.9f).toInt()
                val endY = (deviceInfo.heightGrid * 0.5f).toInt()
                directionalSwipe(
                    durationMs,
                    Point(startX, startY),
                    Point(endX, endY)
                )
            }
            SwipeDirection.LEFT -> {
                val startX = (deviceInfo.widthGrid * 0.9f).toInt()
                val startY = (deviceInfo.heightGrid * 0.5f).toInt()
                val endX = (deviceInfo.widthGrid * 0.1f).toInt()
                val endY = (deviceInfo.heightGrid * 0.5f).toInt()
                directionalSwipe(
                    durationMs,
                    Point(startX, startY),
                    Point(endX, endY)
                )
            }
        }
    }

    override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) {
        val deviceInfo = deviceInfo()
        when (direction) {
            SwipeDirection.UP -> {
                val endY = (deviceInfo.heightGrid * 0.1f).toInt()
                directionalSwipe(durationMs, elementPoint, Point(elementPoint.x, endY))
            }
            SwipeDirection.DOWN -> {
                val endY = (deviceInfo.heightGrid * 0.9f).toInt()
                directionalSwipe(durationMs, elementPoint, Point(elementPoint.x, endY))
            }
            SwipeDirection.RIGHT -> {
                val endX = (deviceInfo.widthGrid * 0.9f).toInt()
                directionalSwipe(durationMs, elementPoint, Point(endX, elementPoint.y))
            }
            SwipeDirection.LEFT -> {
                val endX = (deviceInfo.widthGrid * 0.1f).toInt()
                directionalSwipe(durationMs, elementPoint, Point(endX, elementPoint.y))
            }
        }
    }

    private fun directionalSwipe(durationMs: Long, start: Point, end: Point) {
        dadb.shell("input swipe ${start.x} ${start.y} ${end.x} ${end.y} $durationMs")
    }

    override fun backPress() {
        dadb.shell("input keyevent 4")
        Thread.sleep(300)
    }

    override fun hideKeyboard() {
        dadb.shell("input keyevent 4") // 'Back', which dismisses the keyboard before handing over to navigation
        Thread.sleep(300)
        waitForAppToSettle(null, null)
    }

    override fun takeScreenshot(out: Sink, compressed: Boolean) {
        val response = blockingStub.screenshot(screenshotRequest {})
        out.buffer().use {
            it.write(response.bytes.toByteArray())
        }
    }

    override fun startScreenRecording(out: Sink): ScreenRecording {
        val deviceScreenRecordingPath = "/sdcard/maestro-screenrecording.mp4"

        val future = CompletableFuture.runAsync({
            try {
                shell("screenrecord --bit-rate '100000' $deviceScreenRecordingPath")
            } catch (e: IOException) {
                throw IOException(
                    "Failed to capture screen recording on the device. Note that some Android emulators do not support screen recording. " +
                        "Try using a different Android emulator (eg. Pixel 5 / API 30)",
                    e,
                )
            }
        }, Executors.newSingleThreadExecutor())

        return object : ScreenRecording {
            override fun close() {
                dadb.shell("killall -INT screenrecord") // Ignore exit code
                future.get()
                Thread.sleep(3000)
                dadb.pull(out, deviceScreenRecordingPath)
            }
        }
    }

    override fun inputText(text: String) {
        blockingStub.inputText(inputTextRequest {
            this.text = text
        }) ?: throw IllegalStateException("Input Response can't be null")
    }

    override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) {
        if (browser) {
            openBrowser(link)
        } else {
            dadb.shell("am start -a android.intent.action.VIEW -d \"$link\"")
        }

        if (autoVerify) {
            autoVerifyApp(appId)
        }
    }

    private fun autoVerifyApp(appId: String?) {
        if (appId != null) {
            autoVerifyWithAppName(appId)
        }
        autoVerifyChromeAgreement()
    }

    private fun autoVerifyWithAppName(appId: String) {
        val appNameResult = runCatching {
            val apkFile = AndroidAppFiles.getApkFile(dadb, appId)
            val appName = ApkFile(apkFile).apkMeta.name
            appName
        }
        if (appNameResult.isSuccess) {
            val appName = appNameResult.getOrThrow()
            waitUntilScreenIsStatic(3000)
            val appNameElement = filterByText(appName)
            if (appNameElement != null) {
                tap(appNameElement.bounds.center())
                filterById("android:id/button_once")?.let {
                    tap(it.bounds.center())
                }
            } else {
                val openWithAppElement = filterByText(".*$appName.*")
                if (openWithAppElement != null) {
                    filterById("android:id/button_once")?.let {
                        tap(it.bounds.center())
                    }
                }
            }
        }
    }

    private fun autoVerifyChromeAgreement() {
        filterById("com.android.chrome:id/terms_accept")?.let { tap(it.bounds.center()) }
        waitForAppToSettle(null, null)
        filterById("com.android.chrome:id/negative_button")?.let { tap(it.bounds.center()) }
    }

    private fun filterByText(textRegex: String): UiElement? {
        val textMatcher = Filters.textMatches(textRegex.toRegexSafe(REGEX_OPTIONS))
        val filterFunc = Filters.deepestMatchingElement(textMatcher)
        return filterFunc(contentDescriptor().aggregate()).firstOrNull()?.toUiElementOrNull()
    }

    private fun filterById(idRegex: String): UiElement? {
        val idMatcher = Filters.idMatches(idRegex.toRegexSafe(REGEX_OPTIONS))
        val filterFunc = Filters.deepestMatchingElement(idMatcher)
        return filterFunc(contentDescriptor().aggregate()).firstOrNull()?.toUiElementOrNull()
    }

    private fun openBrowser(link: String) {
        val installedPackages = installedPackages()
        when {
            installedPackages.contains("com.android.chrome") -> {
                dadb.shell("am start -a android.intent.action.VIEW -d \"$link\" com.android.chrome")
            }
            installedPackages.contains("org.mozilla.firefox") -> {
                dadb.shell("am start -a android.intent.action.VIEW -d \"$link\" org.mozilla.firefox")
            }
            else -> {
                dadb.shell("am start -a android.intent.action.VIEW -d \"$link\"")
            }
        }
    }

    private fun installedPackages() = shell("pm list packages").split("\n")
        .map { line: String -> line.split(":".toRegex()).toTypedArray() }
        .filter { parts: Array<String> -> parts.size == 2 }
        .map { parts: Array<String> -> parts[1] }

    override fun setLocation(latitude: Double, longitude: Double) {
        shell("appops set dev.mobile.maestro android:mock_location allow")

        blockingStub.setLocation(
            setLocationRequest {
                this.latitude = latitude
                this.longitude = longitude
            }
        ) ?: error("Set Location Response can't be null")
    }

    override fun eraseText(charactersToErase: Int) {
        blockingStub.eraseAllText(
            eraseAllTextRequest {
                this.charactersToErase = charactersToErase
            }
        ) ?: throw IllegalStateException("Erase Response can't be null")
    }

    override fun setProxy(host: String, port: Int) {
        shell("""settings put global http_proxy "${host}:${port}"""")
        proxySet = true
    }

    override fun resetProxy() {
        shell("settings put global http_proxy :0")
    }

    override fun isShutdown(): Boolean {
        return channel.isShutdown
    }

    override fun isUnicodeInputSupported(): Boolean {
        return false
    }

    override fun waitForAppToSettle(initialHierarchy: ViewHierarchy?, appId: String?): ViewHierarchy? {
        return if (appId != null) {
            waitForWindowToSettle(appId, initialHierarchy)
        } else {
            ScreenshotUtils.waitForAppToSettle(initialHierarchy, this)
        }
    }

    private fun waitForWindowToSettle(appId: String, initialHierarchy: ViewHierarchy?): ViewHierarchy {
        val endTime = System.currentTimeMillis() + WINDOW_UPDATE_TIMEOUT_MS

        do {
            if (blockingStub.isWindowUpdating(checkWindowUpdatingRequest { this.appId = appId }).isWindowUpdating) {
                ScreenshotUtils.waitForAppToSettle(initialHierarchy, this)
            }
        } while (System.currentTimeMillis() < endTime)

        return ScreenshotUtils.waitForAppToSettle(initialHierarchy, this)
    }

    override fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean {
        return ScreenshotUtils.waitUntilScreenIsStatic(timeoutMs, SCREENSHOT_DIFF_THRESHOLD, this)
    }

    override fun capabilities(): List<Capability> {
        return listOf(
            Capability.FAST_HIERARCHY
        )
    }

    override fun setPermissions(appId: String, permissions: Map<String, String>) {
        val mutable = permissions.toMutableMap()
        mutable.remove("all")?.let { value ->
            setAllPermissions(appId, value)
        }

        mutable.forEach { permission ->
            val permissionValue = translatePermissionValue(permission.value)
            translatePermissionName(permission.key).forEach { permissionName ->
                setPermissionInternal(appId, permissionName, permissionValue)
            }
        }
    }

    override fun addMedia(mediaFiles: List<File>) {
        LOGGER.info("[Start] Adding media files")
        mediaFiles.forEach { addMediaToDevice(it) }
        LOGGER.info("[Done] Adding media files")
    }

    private fun addMediaToDevice(mediaFile: File) {
        val namedSource = NamedSource(
            mediaFile.name,
            mediaFile.source(),
            mediaFile.extension,
            mediaFile.path
        )
        val responseObserver = BlockingStreamObserver<MaestroAndroid.AddMediaResponse>()
        val requestStream = asyncStub.addMedia(responseObserver)
        val ext =
            MediaExt.values().firstOrNull { it.extName == namedSource.extension } ?: throw IllegalArgumentException(
                "Extension .${namedSource.extension} is not yet supported for add media"
            )

        val buffer = Buffer()
        val source = namedSource.source
        while (source.read(buffer, CHUNK_SIZE) != -1L) {
            requestStream.onNext(
                addMediaRequest {
                    this.payload = payload {
                        data = ByteString.copyFrom(buffer.readByteArray())
                    }
                    this.mediaName = namedSource.name
                    this.mediaExt = ext.extName
                }
            )
            buffer.clear()
        }
        source.close()
        requestStream.onCompleted()
        responseObserver.awaitResult()
    }

    private fun setAllPermissions(appId: String, permissionValue: String) {
        val permissionsResult = runCatching {
            val apkFile = AndroidAppFiles.getApkFile(dadb, appId)
            ApkFile(apkFile).apkMeta.usesPermissions
        }
        if (permissionsResult.isSuccess) {
            permissionsResult.getOrNull()?.let {
                it.forEach { permission ->
                    setPermissionInternal(appId, permission, translatePermissionValue(permissionValue))
                }
            }
        }
    }

    private fun setPermissionInternal(appId: String, permission: String, permissionValue: String) {
        try {
            dadb.shell("pm $permissionValue $appId $permission")
        } catch (exception: Exception) {
            /* no-op */
        }
    }

    private fun translatePermissionName(name: String): List<String> {
        return when (name) {
            "location" -> listOf(
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
            )
            "camera" -> listOf("android.permission.CAMERA")
            "contacts" -> listOf(
                "android.permission.READ_CONTACTS",
                "android.permission.WRITE_CONTACTS"
            )
            "phone" -> listOf(
                "android.permission.CALL_PHONE",
                "android.permission.ANSWER_PHONE_CALLS",
            )
            "microphone" -> listOf(
                "android.permission.RECORD_AUDIO"
            )
            "bluetooth" -> listOf(
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH_SCAN",
            )
            "storage" -> listOf(
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.READ_EXTERNAL_STORAGE"
            )
            "notifications" -> listOf(
                "android.permission.POST_NOTIFICATIONS"
            )
            "medialibrary" -> listOf(
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.READ_MEDIA_AUDIO",
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VIDEO"
            )
            "calendar" -> listOf(
                "android.permission.WRITE_CALENDAR",
                "android.permission.READ_CALENDAR"
            )
            "sms" -> listOf(
                "android.permission.READ_SMS",
                "android.permission.RECEIVE_SMS",
                "android.permission.SEND_SMS"
            )
            else -> listOf(name.replace("[^A-Za-z0-9._]+".toRegex(), ""))
        }
    }

    private fun translatePermissionValue(value: String): String {
        return when (value) {
            "allow" -> "grant"
            "deny" -> "revoke"
            "unset" -> "revoke"
            else -> "revoke"
        }
    }

    private fun mapHierarchy(node: Node): TreeNode {
        val attributes = if (node is Element) {
            val attributesBuilder = mutableMapOf<String, String>()

            if (node.hasAttribute("text")) {
                val text = node.getAttribute("text")
                attributesBuilder["text"] = text
            }

            if (node.hasAttribute("content-desc")) {
                attributesBuilder["accessibilityText"] = node.getAttribute("content-desc")
            }

            if (node.hasAttribute("hintText")) {
                attributesBuilder["hintText"] = node.getAttribute("hintText")
            }

            if (node.hasAttribute("class") && node.getAttribute("class") == TOAST_CLASS_NAME) {
                attributesBuilder["ignoreBoundsFiltering"] = true.toString()
            } else {
                attributesBuilder["ignoreBoundsFiltering"] = false.toString()
            }

            if (node.hasAttribute("resource-id")) {
                attributesBuilder["resource-id"] = node.getAttribute("resource-id")
            }

            if (node.hasAttribute("clickable")) {
                attributesBuilder["clickable"] = node.getAttribute("clickable")
            }

            if (node.hasAttribute("bounds")) {
                attributesBuilder["bounds"] = node.getAttribute("bounds")
            }

            if (node.hasAttribute("enabled")) {
                attributesBuilder["enabled"] = node.getAttribute("enabled")
            }

            if (node.hasAttribute("focused")) {
                attributesBuilder["focused"] = node.getAttribute("focused")
            }

            if (node.hasAttribute("checked")) {
                attributesBuilder["checked"] = node.getAttribute("checked")
            }

            if (node.hasAttribute("selected")) {
                attributesBuilder["selected"] = node.getAttribute("selected")
            }

            attributesBuilder
        } else {
            emptyMap()
        }

        val children = mutableListOf<TreeNode>()
        val childNodes = node.childNodes
        (0 until childNodes.length).forEach { i ->
            children += mapHierarchy(childNodes.item(i))
        }

        return TreeNode(
            attributes = attributes.toMutableMap(),
            children = children,
            clickable = node.getBoolean("clickable"),
            enabled = node.getBoolean("enabled"),
            focused = node.getBoolean("focused"),
            checked = node.getBoolean("checked"),
            selected = node.getBoolean("selected"),
        )
    }

    private fun Node.getBoolean(name: String): Boolean? {
        return (this as? Element)
            ?.getAttribute(name)
            ?.let { it == "true" }
    }

    private fun installMaestroApks() {
        val maestroAppApk = File.createTempFile("maestro-app", ".apk")
        val maestroServerApk = File.createTempFile("maestro-server", ".apk")
        Maestro::class.java.getResourceAsStream("/maestro-app.apk")?.let {
            val bufferedSink = maestroAppApk.sink().buffer()
            bufferedSink.writeAll(it.source())
            bufferedSink.flush()
        }
        Maestro::class.java.getResourceAsStream("/maestro-server.apk")?.let {
            val bufferedSink = maestroServerApk.sink().buffer()
            bufferedSink.writeAll(it.source())
            bufferedSink.flush()
        }
        install(maestroAppApk)
        if (!isPackageInstalled("dev.mobile.maestro")) {
            throw IllegalStateException("dev.mobile.maestro was not installed")
        }
        install(maestroServerApk)
        if (!isPackageInstalled("dev.mobile.maestro.test")) {
            throw IllegalStateException("dev.mobile.maestro.test was not installed")
        }
    }

    private fun uninstallMaestroApks() {
        if (isPackageInstalled("dev.mobile.maestro.test")) {
            uninstall("dev.mobile.maestro.test")
        }
        if (isPackageInstalled("dev.mobile.maestro")) {
            uninstall("dev.mobile.maestro")
        }
    }

    private fun install(apkFile: File) {
        try {
            dadb.install(apkFile)
        } catch (installError: IOException) {
            throw IOException("Failed to install apk " + apkFile + ": " + installError.message, installError)
        }
    }

    private fun uninstall(packageName: String) {
        try {
            dadb.uninstall(packageName)
        } catch (error: IOException) {
            throw IOException("Failed to uninstall package " + packageName + ": " + error.message, error)
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        val output: String = shell("pm list packages --user 0 $packageName")
        return output.split("\n".toRegex())
            .map { line -> line.split(":".toRegex()) }
            .filter { parts -> parts.size == 2 }
            .map { parts -> parts[1] }
            .any { linePackageName -> linePackageName == packageName }
    }

    private fun shell(command: String): String {
        val response: AdbShellResponse = try {
            dadb.shell(command)
        } catch (e: IOException) {
            throw IOException(command, e)
        }

        if (response.exitCode != 0) {
            throw IOException("$command: ${response.allOutput}")
        }
        return response.output
    }

    private fun getStartupTimeout(): Long = runCatching {
        System.getenv(MAESTRO_DRIVER_STARTUP_TIMEOUT).toLong()
    }.getOrDefault(SERVER_LAUNCH_TIMEOUT_MS)

    private fun AdbShellStream?.successfullyStarted(): Boolean {
        val output = this?.read()
        return when {
            output is AdbShellPacket.StdError -> false
            output.toString().contains("FAILED", true) -> false
            output.toString().contains("UNABLE", true) -> false
            else -> true
        }
    }

    companion object {

        private const val SERVER_LAUNCH_TIMEOUT_MS = 15000L
        private const val MAESTRO_DRIVER_STARTUP_TIMEOUT = "MAESTRO_DRIVER_STARTUP_TIMEOUT"
        private const val WINDOW_UPDATE_TIMEOUT_MS = 750

        private val REGEX_OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)

        private val LOGGER = LoggerFactory.getLogger(AndroidDriver::class.java)

        private const val TOAST_CLASS_NAME = "android.widget.Toast"
        private val PORT_TO_FORWARDER = mutableMapOf<Int, AutoCloseable>()
        private val PORT_TO_ALLOCATION_POINT = mutableMapOf<Int, String>()
        private const val SCREENSHOT_DIFF_THRESHOLD = 0.005
        private const val CHUNK_SIZE = 1024L * 1024L * 3L
    }
}
