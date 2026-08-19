package com.example.myapplication2

import android.Manifest
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.app.PendingIntent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.graphics.RectF
import android.graphics.YuvImage
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication2.ui.theme.CameraError
import com.example.myapplication2.ui.theme.CameraOnSurfaceMuted
import com.example.myapplication2.ui.theme.CameraPrimary
import com.example.myapplication2.ui.theme.CameraSuccess
import com.example.myapplication2.ui.theme.CameraWarning
import com.example.myapplication2.ui.theme.DetectionObstacle
import com.example.myapplication2.ui.theme.DetectionPerson
import com.example.myapplication2.ui.theme.DetectionTraffic
import com.example.myapplication2.ui.theme.DetectionVehicle
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.myapplication2.yolo.Detection
import com.example.myapplication2.yolo.YoloV8TfliteDetector
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors

/**
 * 主畫面：顯示 USB 攝影機畫面與物件方框；曝光、語音等選項在獨立設定頁。
 *
 * 只用外接 USB 攝影機。較花時間的看圖工作放在背景做，完成後再更新畫面。
 */
@Composable
fun YoloCameraScreen() {
    val context = LocalContext.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val prefs = remember(context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var voiceEnabled by remember {
        mutableStateOf(prefs.getBoolean(PREF_KEY_VOICE_ENABLED, true))
    }
    val ttsRef = remember { mutableStateOf<TextToSpeech?>(null) }
    var isTtsReady by remember { mutableStateOf(false) }
    val voiceDebounce = remember { VoiceDebounceState() }
    val voiceIntervalMsState = remember {
        mutableStateOf(loadSavedVoiceIntervalMs(prefs))
    }
    var uvcExposureLevel by remember { mutableStateOf(0) } // -2..2
    var showDebugInfo by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    var cameraStatus by remember { mutableStateOf("等待外接 UVC 攝像頭…") }
    var hasCameraPermission by remember { mutableStateOf(false) }
    var pendingStartAfterCameraGrant by remember { mutableStateOf(false) }
    var activeCameraLabel by remember { mutableStateOf("未知") }
    var uvcPreferredActive by remember { mutableStateOf(false) }
    var uvcStatus by remember { mutableStateOf("未連接") }
    var usbDebugInfo by remember { mutableStateOf("USB 裝置：未掃描") }
    val usbMonitorState = remember { mutableStateOf<USBMonitor?>(null) }
    val pendingUvcDeviceState = remember { mutableStateOf<UsbDevice?>(null) }
    val triggerUsbStartState = remember { mutableStateOf<(() -> Unit)?>(null) }
    val applyExposureState = remember { mutableStateOf<((Int) -> Unit)?>(null) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
            if (granted) {
                cameraStatus = "相機權限已允許"
                if (pendingStartAfterCameraGrant) {
                    pendingStartAfterCameraGrant = false
                    triggerUsbStartState.value?.invoke()
                }
            } else {
                cameraStatus = "未允許相機權限"
                uvcStatus = "請先開啟系統相機權限"
            }
        }
    )
    LaunchedEffect(Unit) {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val uvcTextureViewRef = remember { mutableStateOf<TextureView?>(null) }

        val detectionsState: MutableState<List<Detection>> = remember { mutableStateOf(emptyList()) }
        val detectorState = remember { mutableStateOf<YoloV8TfliteDetector?>(null) }
        val trafficDetectorState = remember { mutableStateOf<YoloV8TfliteDetector?>(null) }
        val trafficModeState = remember {
            mutableStateOf(loadSavedTrafficMode(prefs))
        }

        // 畫面開著時才載入模型；離開畫面時立刻關掉，避免一直佔用記憶體。
        DisposableEffect(Unit) {
            val tts = TextToSpeech(context) { status ->
                isTtsReady = status == TextToSpeech.SUCCESS
            }
            ttsRef.value = tts
            // Text 模型是繁/簡都用中文字，所以用中文語系優先。
            // 實際語音由裝置安裝的 TTS 引擎決定。
            tts.language = Locale.CHINA
            tts.setSpeechRate(1.0f)
            onDispose {
                // 停止並釋放資源，避免回到背景後仍在播報。
                tts.stop()
                tts.shutdown()
                ttsRef.value = null
                isTtsReady = false
                voiceDebounce.reset()
            }
        }

        DisposableEffect(Unit) {
            val detector = YoloV8TfliteDetector(
                context = context,
                modelAssetName = "yolov8n_float16.tflite",
                modelType = YoloV8TfliteDetector.ModelType.COCO,
                maxCandidatesBeforeNms = 120
            )
            detectorState.value = detector
            val trafficDetector = runCatching {
                YoloV8TfliteDetector(
                    context = context,
                    modelAssetName = "traffic_float16.tflite",
                    modelType = YoloV8TfliteDetector.ModelType.TRAFFIC,
                    confThreshold = 0.18f,
                    maxCandidatesBeforeNms = 120
                )
            }.getOrNull()
            trafficDetectorState.value = trafficDetector
            onDispose {
                detector.close()
                trafficDetector?.close()
                detectorState.value = null
                trafficDetectorState.value = null
                analysisExecutor.shutdown()
            }
        }

        // 要等預覽區準備好，USB 攝影機才有地方可以把影像畫出來。
        DisposableEffect(uvcTextureViewRef.value) {
            val frameCounter = AtomicInteger(0)
            val uvcAnalyzing = AtomicBoolean(false)
            val trafficTracks = mutableListOf<TrafficTrack>()
            val uvcCameraRef = AtomicReference<UVCCamera?>(null)
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val permissionAction = "${context.packageName}.USB_PERMISSION_UVC_DIRECT"
            val permissionReqCode = AtomicInteger(4000)
            val mainHandler = Handler(Looper.getMainLooper())
            val usbMonitor = USBMonitor(
                context,
                object : USBMonitor.OnDeviceConnectListener {
                    override fun onAttach(device: UsbDevice) = Unit
                    override fun onDettach(device: UsbDevice) = Unit
                    override fun onConnect(
                        device: UsbDevice,
                        ctrlBlock: USBMonitor.UsbControlBlock,
                        createNew: Boolean
                    ) = Unit
                    override fun onDisconnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) = Unit
                    override fun onCancel(device: UsbDevice?) = Unit
                }
            )
            usbMonitorState.value = usbMonitor

            fun stopUvc() {
                // 先把目前相機取走再關掉，避免拔除和回呼同時重複關閉它。
                // 畫面上的文字要回到主執行緒再改，畫面才不會出錯。
                runCatching { uvcCameraRef.getAndSet(null)?.destroy() }
                mainExecutor.execute {
                    uvcPreferredActive = false
                    activeCameraLabel = "未啟用（禁止手機鏡頭）"
                    uvcStatus = "UVC 已斷開"
                }
            }

            fun startUvc(device: UsbDevice) {
                // 只處理像攝影機的 USB 裝置，不要把鍵盤或隨身碟當成攝影機開啟。
                if (!isUvcDevice(device)) return
                val texture = uvcTextureViewRef.value?.surfaceTexture
                if (texture == null) {
                    pendingUvcDeviceState.value = device
                    uvcStatus = "等待 UVC 預覽面初始化…"
                    return
                }
                if (!usbManager.hasPermission(device)) {
                    uvcStatus = "等待 USB 授權（請在系統彈窗點允許）"
                    return
                }

                runCatching { uvcCameraRef.getAndSet(null)?.destroy() }
                runCatching {
                    val ctrlBlock = usbMonitor.openDevice(device)
                    val camera = UVCCamera()
                    camera.open(ctrlBlock)
                    camera.setPreviewSize(UVC_FRAME_WIDTH, UVC_FRAME_HEIGHT, UVCCamera.FRAME_FORMAT_MJPEG)
                    runCatching { camera.setBrightness(exposureLevelToBrightnessPercent(uvcExposureLevel)) }
                    camera.setPreviewTexture(texture)
                    camera.setFrameCallback(IFrameCallback { frame ->
                        try {
                            if (!uvcPreferredActive) return@IFrameCallback
                            val frameNo = frameCounter.incrementAndGet()
                            // 不必每一張都辨識；跳過幾張可讓預覽比較順。
                            if (frameNo % UVC_INFERENCE_EVERY_N_FRAMES != 0) return@IFrameCallback
                            // 一次只分析一張。如果前一張還沒看完，就直接跳過這張，
                            // 才不會越積越多，最後看到很久以前的畫面。
                            if (!uvcAnalyzing.compareAndSet(false, true)) return@IFrameCallback
                            // 複製一個可讀取的位置，避免動到相機程式本來正在使用的資料。
                            val safeBuffer = frame.duplicate()
                            safeBuffer.rewind()
                            val remaining = safeBuffer.remaining()
                            if (remaining <= 0) {
                                uvcAnalyzing.set(false)
                                return@IFrameCallback
                            }
                            val bytes = ByteArray(remaining)
                            safeBuffer.get(bytes)
                            analysisExecutor.execute {
                                try {
                                    val detector = detectorState.value ?: return@execute
                                    val bitmap = uvcFrameToBitmap(bytes, UVC_FRAME_WIDTH, UVC_FRAME_HEIGHT)
                                        ?: return@execute
                                    try {
                                        val baseResults = detector.detect(bitmap)
                                            .filterNot { it.className == "traffic light" }
                                        // 一般模型和交通模型都可能看到紅綠燈；交通資訊固定交給專用模型，避免重複。
                                        val trafficRaw = trafficDetectorState.value?.detect(bitmap).orEmpty()
                                        val trafficStable = stabilizeTrafficDetections(trafficRaw, trafficTracks)
                                        val merged = mergeDetections(baseResults, trafficStable)
                                        mainExecutor.execute { detectionsState.value = merged }
                                    } finally {
                                        bitmap.recycle()
                                    }
                                } catch (_: Throwable) {
                                } finally {
                                    uvcAnalyzing.set(false)
                                }
                            }
                        } catch (_: Throwable) {
                            uvcAnalyzing.set(false)
                        }
                    }, UVCCamera.PIXEL_FORMAT_RGBX)
                    camera.startPreview()
                    uvcCameraRef.set(camera)
                    applyExposureState.value = { level ->
                        runCatching {
                            uvcCameraRef.get()?.setBrightness(exposureLevelToBrightnessPercent(level))
                        }
                    }
                    mainExecutor.execute {
                        uvcPreferredActive = true
                        activeCameraLabel = "USB 外接攝像頭(UVC)"
                        uvcStatus = "UVC 已連接"
                        cameraStatus = "UVC 直連中"
                    }
                }.onFailure {
                    mainExecutor.execute {
                        uvcPreferredActive = false
                        uvcStatus = "UVC 打開失敗：${it.javaClass.simpleName} ${it.message.orEmpty()}"
                    }
                }
            }

            fun buildPermissionIntent(): PendingIntent {
                val reqCode = permissionReqCode.incrementAndGet()
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                return PendingIntent.getBroadcast(
                    context,
                    reqCode,
                    Intent(permissionAction).setPackage(context.packageName),
                    flags
                )
            }

            fun requestUsbPermissionOnce(target: UsbDevice?) {
                // 就算是 USB 攝影機，Android 仍要求先取得相機權限。
                if (!hasCameraPermission) {
                    pendingStartAfterCameraGrant = true
                    uvcStatus = "請先允許相機權限，允許後將自動開啟外接鏡頭"
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    return
                }
                if (target == null) {
                    uvcStatus = "未找到 UVC 裝置"
                    return
                }
                pendingUvcDeviceState.value = target
                val allDevices = usbManager.deviceList.values.toList()
                usbDebugInfo = if (allDevices.isEmpty()) {
                    "USB 裝置：0"
                } else {
                    val first = allDevices.first()
                    "USB 裝置：${allDevices.size} / firstVidPid=${first.vendorId}:${first.productId} class=${first.deviceClass}"
                }
                if (usbManager.hasPermission(target)) {
                    uvcStatus = "USB 權限已存在，嘗試連線…"
                    startUvc(target)
                } else {
                    uvcStatus = "已發送 USB 授權請求（僅本次）"
                    usbManager.requestPermission(target, buildPermissionIntent())
                }
            }
            triggerUsbStartState.value = {
                val target = pendingUvcDeviceState.value
                    ?: usbManager.deviceList.values.firstOrNull { isUvcDevice(it) }
                    ?: usbManager.deviceList.values.firstOrNull()
                requestUsbPermissionOnce(target)
            }

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    when (intent.action) {
                        permissionAction -> {
                            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                            }
                            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            val chooseGrantedDevice = {
                                val fromIntent = device
                                if (fromIntent != null && usbManager.hasPermission(fromIntent)) {
                                    fromIntent
                                } else {
                                    val all = usbManager.deviceList.values.toList()
                                    all.firstOrNull { usbManager.hasPermission(it) && isUvcDevice(it) }
                                        ?: all.firstOrNull { usbManager.hasPermission(it) }
                                }
                            }

                            val immediate = chooseGrantedDevice()
                            if (immediate != null) {
                                pendingUvcDeviceState.value = immediate
                                startUvc(immediate)
                                return
                            }

                            // 有些手機會先說還沒拿到權限，但過一下其實就成功了。
                            // 所以短暫再看幾次；真的沒成功才顯示失敗，也不一直跳出授權視窗。
                            val delayMs = listOf(300L, 1000L, 2000L)
                            var consumed = false
                            delayMs.forEach { delay ->
                                mainHandler.postDelayed({
                                    if (consumed) return@postDelayed
                                    val late = chooseGrantedDevice()
                                    if (late != null) {
                                        consumed = true
                                        pendingUvcDeviceState.value = late
                                        startUvc(late)
                                    } else if (delay == delayMs.last()) {
                                        uvcStatus = "USB 權限未通過（不重試）"
                                        usbDebugInfo =
                                            "grant=$granted hasPerm=false device=${device?.vendorId}:${device?.productId} class=${device?.deviceClass}"
                                    }
                                }, delay)
                            }
                        }
                        UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                            }
                            if (device != null && isUvcDevice(device)) {
                                pendingUvcDeviceState.value = device
                                uvcStatus = "偵測到 UVC 裝置，正在請求 USB 授權…"
                                usbDebugInfo = "attach vid=${device.vendorId} pid=${device.productId} class=${device.deviceClass} ifCount=${device.interfaceCount}"
                                requestUsbPermissionOnce(device)
                            }
                        }
                        UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                            }
                            if (device != null && isUvcDevice(device)) {
                                pendingUvcDeviceState.value = null
                                stopUvc()
                            }
                        }
                    }
                }
            }

            val filter = IntentFilter(permissionAction).apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }

            val existing = usbManager.deviceList.values.firstOrNull { isUvcDevice(it) }
            pendingUvcDeviceState.value = existing
            if (existing != null) {
                uvcStatus = "找到 UVC 裝置，正在請求 USB 授權…"
                requestUsbPermissionOnce(existing)
            } else {
                uvcStatus = "未找到 UVC 裝置"
            }

            onDispose {
                // 離開畫面時把監聽和相機關掉，避免 App 還在背景偷用攝影機。
                runCatching { context.unregisterReceiver(receiver) }
                runCatching { uvcCameraRef.getAndSet(null)?.destroy() }
                runCatching { usbMonitor.destroy() }
                usbMonitorState.value = null
                pendingUvcDeviceState.value = null
                triggerUsbStartState.value = null
                applyExposureState.value = null
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            val previewViewportModifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .align(Alignment.Center)

            AndroidView(
                modifier = previewViewportModifier,
                factory = {
                    TextureView(it).also { tv ->
                        uvcTextureViewRef.value = tv
                        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                surface: SurfaceTexture,
                                width: Int,
                                height: Int
                            ) {
                                val pending = pendingUvcDeviceState.value
                                if (pending != null && !uvcPreferredActive) {
                                    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                                    if (usbManager.hasPermission(pending)) {
                                        uvcStatus = "UVC 預覽面就緒，正在開啟外接鏡頭…"
                                        triggerUsbStartState.value?.invoke()
                                    } else {
                                        uvcStatus = "UVC 預覽面就緒，等待 USB 授權…"
                                    }
                                }
                            }

                            override fun onSurfaceTextureSizeChanged(
                                surface: SurfaceTexture,
                                width: Int,
                                height: Int
                            ) = Unit

                            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

                            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                        }
                    }
                },
                update = { tv ->
                    uvcTextureViewRef.value = tv
                    tv.visibility = android.view.View.VISIBLE
                    // 手機直拿時仍維持 16:9 橫向預覽（完整畫面，不裁切）。
                    tv.rotation = 0f
                    tv.scaleX = 1f
                    tv.scaleY = 1f
                }
            )

            DetectionOverlay(
                detections = detectionsState.value,
                modifier = previewViewportModifier
            )

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                StatusHeader(
                    uvcConnected = uvcPreferredActive,
                    detectionCount = detectionsState.value.size,
                    cameraStatus = cameraStatus,
                    activeCameraLabel = activeCameraLabel,
                    uvcStatus = uvcStatus,
                    onSettingsClick = { showSettings = true }
                )
            }

            if (showSettings) {
                SettingsScreen(
                    uvcExposureLevel = uvcExposureLevel,
                    trafficMode = trafficModeState.value,
                    voiceEnabled = voiceEnabled,
                    voiceIntervalMs = voiceIntervalMsState.value,
                    showDebugInfo = showDebugInfo,
                    usbDebugInfo = usbDebugInfo,
                    onExposureDecrease = {
                        uvcExposureLevel = (uvcExposureLevel - 1).coerceAtLeast(-2)
                        applyExposureState.value?.invoke(uvcExposureLevel)
                    },
                    onExposureIncrease = {
                        uvcExposureLevel = (uvcExposureLevel + 1).coerceAtMost(2)
                        applyExposureState.value?.invoke(uvcExposureLevel)
                    },
                    onTrafficModeChange = { mode ->
                        setTrafficMode(trafficModeState, prefs, mode)
                    },
                    onVoiceToggle = { enabled ->
                        voiceEnabled = enabled
                        prefs.edit().putBoolean(PREF_KEY_VOICE_ENABLED, voiceEnabled).apply()
                        if (!voiceEnabled) {
                            ttsRef.value?.stop()
                        }
                        voiceDebounce.reset()
                    },
                    onVoiceIntervalChange = { interval ->
                        setVoiceIntervalMs(voiceIntervalMsState, prefs, interval)
                        voiceDebounce.reset()
                    },
                    onDebugToggle = { showDebugInfo = !showDebugInfo },
                    onClose = { showSettings = false }
                )
            }
        }

        LaunchedEffect(uvcPreferredActive) {
            if (uvcPreferredActive) {
                cameraStatus = "UVC 直連中"
            } else {
                cameraStatus = "等待外接 UVC 攝像頭…"
                activeCameraLabel = "未啟用（禁止手機鏡頭）"
                detectionsState.value = emptyList()
            }
        }

        LaunchedEffect(detectionsState.value, voiceEnabled, isTtsReady, voiceIntervalMsState.value) {
            if (!voiceEnabled || !isTtsReady) return@LaunchedEffect

            val prompts = createVoicePrompts(detectionsState.value)
            val now = SystemClock.uptimeMillis()

            if (prompts.isEmpty()) {
                voiceDebounce.lastCandidateKey = null
                voiceDebounce.sameKeyCount = 0
                return@LaunchedEffect
            }
            val combinedKey = prompts.joinToString(separator = "|") { it.key }
            val combinedMessage = prompts.joinToString(separator = "，") { it.message }

            if (voiceDebounce.lastCandidateKey == combinedKey) {
                voiceDebounce.sameKeyCount += 1
            } else {
                voiceDebounce.lastCandidateKey = combinedKey
                voiceDebounce.sameKeyCount = 1
            }

            val canSpeak =
                voiceDebounce.sameKeyCount >= MIN_SAME_KEY_FRAMES_FOR_SPEAK &&
                    now - voiceDebounce.lastSpokenAtMs >= voiceIntervalMsState.value

            if (canSpeak) {
                voiceDebounce.lastSpokenKey = combinedKey
                voiceDebounce.lastSpokenAtMs = now
                // 第一段先清空舊佇列，再把其餘提示加入佇列，達成「排隊播報」。
                prompts.forEachIndexed { idx, prompt ->
                    val queueMode = if (idx == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    ttsRef.value?.speak(prompt.message, queueMode, null, "${now}_${idx}")
                }
            }
        }
    }
}

/**
 * 建立 Android 內建相機的預覽與辨識流程，當作 USB 攝影機以外的備用做法。
 * 每張畫面都在背景看完，再回到主畫面顯示結果。
 */
private fun bindCameraUseCases(
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    detectorState: MutableState<YoloV8TfliteDetector?>,
    trafficDetectorState: MutableState<YoloV8TfliteDetector?>,
    trafficModeState: MutableState<TrafficPerfMode>,
    detectionsState: MutableState<List<Detection>>,
    mainExecutor: java.util.concurrent.Executor,
    analysisExecutor: java.util.concurrent.Executor,
    onCameraSelected: (String) -> Unit,
) {
    // 這是備用相機流程：依設定輪流跑兩個模型，沒辨識的畫面先沿用上一筆結果。
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener(
        {
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 360))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            val trafficTracks = mutableListOf<TrafficTrack>()
            var frameIndex = 0
            var lastBaseResults: List<Detection> = emptyList()
            var lastStableTraffic: List<Detection> = emptyList()

            imageAnalysis.setAnalyzer(analysisExecutor) { image ->
                val detector = detectorState.value
                if (detector == null) {
                    image.close()
                    return@setAnalyzer
                }

                try {
                    frameIndex += 1
                    val mode = trafficModeState.value
                    val sharedFrame = YoloV8TfliteDetector.imageProxyToRotatedBitmap(image)
                    // 平衡和省電模式不會每張都重新辨識，先沿用上一張結果，畫面仍會有方框。
                    val baseInterval = mode.baseInferEveryNFrames
                    val runBaseThisFrame = frameIndex % baseInterval == 0
                    try {
                        val baseResults = if (runBaseThisFrame) {
                            detector.detect(sharedFrame)
                                .filterNot { it.className == "traffic light" }
                                .also { lastBaseResults = it }
                        } else {
                            lastBaseResults
                        }

                        val interval = mode.inferEveryNFrames
                        val runTrafficThisFrame = (frameIndex + mode.trafficFrameOffset) % interval == 0
                        val trafficStable = if (runTrafficThisFrame) {
                            val trafficRaw = trafficDetectorState.value?.detect(sharedFrame).orEmpty()
                            stabilizeTrafficDetections(trafficRaw, trafficTracks).also {
                                lastStableTraffic = it
                            }
                        } else {
                            lastStableTraffic
                        }
                        val merged = mergeDetections(baseResults, trafficStable)
                        mainExecutor.execute { detectionsState.value = merged }
                    } finally {
                        sharedFrame.recycle()
                    }
                } catch (_: Throwable) {
                    // ignore per-frame failures
                } finally {
                    image.close()
                }
            }

            val selectedCamera = selectPreferredCamera(cameraProvider.availableCameraInfos)
            onCameraSelected(selectedCamera.label)
            val cameraSelector = selectedCamera.selector
            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
        },
        ContextCompat.getMainExecutor(context)
    )
}

/** 看看 USB 裝置是不是攝影機；有些裝置會把這個資訊寫在裡面的介面。 */
private fun isUvcDevice(device: UsbDevice): Boolean {
    // 有些 USB 攝影機沒有直接標明自己是攝影機，要再檢查裡面的每個介面。
    if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
    if (device.deviceClass == UsbConstants.USB_CLASS_MISC) return true
    if (device.deviceClass == UsbConstants.USB_CLASS_PER_INTERFACE) {
        // keep checking interfaces below
    }
    for (i in 0 until device.interfaceCount) {
        val intf = device.getInterface(i)
        if (intf.interfaceClass == UsbConstants.USB_CLASS_VIDEO) {
            return true
        }
        if (intf.interfaceClass == UsbConstants.USB_CLASS_MISC) {
            return true
        }
    }
    return false
}

/** 把 NV21 格式的原始畫面轉成 Android 可辨識的圖片；失敗時回傳空值。 */
private fun nv21ToBitmap(data: ByteArray, width: Int, height: Int): Bitmap? {
    return try {
        // Android 沒有直接轉 NV21 圖片的簡單做法，所以先轉成 JPEG；較慢但相容性好。
        val yuvImage = YuvImage(data, ImageFormat.NV21, width, height, null)
        val output = ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 80, output)
        val jpeg = output.toByteArray()
        output.close()
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    } catch (_: Throwable) {
        null
    }
}

/** 把 RGBX 格式的原始畫面直接放進 Android 圖片物件。 */
private fun rgbxToBitmap(data: ByteArray, width: Int, height: Int): Bitmap? {
    return try {
        val expected = width * height * 4
        if (data.size < expected) return null
        val buffer = java.nio.ByteBuffer.wrap(data, 0, expected)
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(buffer)
        }
    } catch (_: Throwable) {
        null
    }
}

/** 依序以 UVC 最常見的 RGBX 與相容用的 NV21 格式解讀影格。 */
private fun uvcFrameToBitmap(data: ByteArray, width: Int, height: Int): Bitmap? {
    // 預設 callback 為 RGBX；NV21 是相容部分相機韌體的備援格式。
    return rgbxToBitmap(data, width, height) ?: nv21ToBitmap(data, width, height)
}

/** 把畫面上的 -2 到 2 曝光按鈕，換成攝影機要的 0 到 100 亮度數字。 */
private fun exposureLevelToBrightnessPercent(level: Int): Int {
    // 使用者只需要選五段亮度；攝影機函式庫需要實際的百分比。
    return when (level.coerceIn(-2, 2)) {
        -2 -> 35
        -1 -> 45
        0 -> 55
        1 -> 65
        else -> 75
    }
}

/** 記住要用哪一台內建相機，以及畫面上要顯示的名稱。 */
private data class SelectedCamera(
    val selector: CameraSelector,
    val label: String
)

/**
 * 找相機時優先選外接，再來是後鏡頭、前鏡頭，最後才隨便選一台可用的。
 */
private fun selectPreferredCamera(availableCameraInfos: List<CameraInfo>): SelectedCamera {
    val externalCamera = availableCameraInfos.firstOrNull { info -> isExternalCamera(info) }
    if (externalCamera != null) {
        return SelectedCamera(
            selector = CameraSelector.Builder()
                .addCameraFilter { infos -> infos.filter { it == externalCamera } }
                .build(),
            label = "USB 外接攝像頭"
        )
    }

    val hasBack = availableCameraInfos.any { info ->
        Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_BACK
    }
    if (hasBack) {
        return SelectedCamera(CameraSelector.DEFAULT_BACK_CAMERA, "手機後鏡頭")
    }

    val hasFront = availableCameraInfos.any { info ->
        Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
    }
    if (hasFront) {
        return SelectedCamera(CameraSelector.DEFAULT_FRONT_CAMERA, "手機前鏡頭")
    }

    val fallback = availableCameraInfos.firstOrNull()
    if (fallback != null) {
        return SelectedCamera(
            selector = CameraSelector.Builder()
                .addCameraFilter { infos -> infos.filter { it == fallback } }
                .build(),
            label = "其他鏡頭"
        )
    }

    return SelectedCamera(CameraSelector.DEFAULT_BACK_CAMERA, "未找到可用鏡頭")
}

/** 判斷這台相機是不是外接的，也兼顧有些手機沒有正確標示的情況。 */
private fun isExternalCamera(info: CameraInfo): Boolean {
    val camera2Info = Camera2CameraInfo.from(info)
    val lensFacing = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
    if (lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
        return true
    }

    // 某些 ROM 會把外接相機標成一般鏡頭，cameraId 常帶有 usb/external 關鍵字。
    val cameraId = camera2Info.cameraId.lowercase(Locale.ROOT)
    return cameraId.contains("usb") || cameraId.contains("uvc") || cameraId.contains("external")
}

/** 把一般模型和交通模型的結果放在一起，順便移除明顯重複的框。 */
private fun mergeDetections(
    baseResults: List<Detection>,
    trafficResults: List<Detection>
): List<Detection> {
    // 行人和人行道有時會框到同一區域。重疊太多時不顯示人行道，避免語音講兩次。
    val personLike = baseResults.filter { it.group == Detection.Group.PERSON }
    val filteredTraffic = trafficResults.filter { traffic ->
        personLike.none { person -> iou(person.box, traffic.box) > 0.45f }
    }
    return baseResults + filteredTraffic
}

/** 算兩個框重疊多少，用來判斷它們是不是在說同一個東西。 */
private fun iou(a: android.graphics.RectF, b: android.graphics.RectF): Float {
    val interLeft = maxOf(a.left, b.left)
    val interTop = maxOf(a.top, b.top)
    val interRight = minOf(a.right, b.right)
    val interBottom = minOf(a.bottom, b.bottom)
    val interW = maxOf(0f, interRight - interLeft)
    val interH = maxOf(0f, interBottom - interTop)
    val interArea = interW * interH
    val areaA = maxOf(0f, a.width()) * maxOf(0f, a.height())
    val areaB = maxOf(0f, b.width()) * maxOf(0f, b.height())
    val union = areaA + areaB - interArea
    return if (union <= 0f) 0f else interArea / union
}

/** 記住交通物件上一張的位置，以及它連續出現或消失了幾次。 */
private data class TrafficTrack(
    val className: String,
    var box: RectF,
    var streak: Int,
    var missed: Int
)

/**
 * 把這一張看到的交通物件，和前幾張看到的對起來。
 * 這樣方框比較不會亂跳；連續四次以上沒看到就把它忘掉。
 */
private fun stabilizeTrafficDetections(
    trafficRaw: List<Detection>,
    tracks: MutableList<TrafficTrack>
): List<Detection> {
    // 只把同一種類、位置相近的物件當成同一個，並讓框的位置慢慢移動而不是突然跳動。
    val matchedTrackIndexes = mutableSetOf<Int>()
    val stable = mutableListOf<Detection>()

    for (det in trafficRaw) {
        var bestTrackIndex = -1
        var bestIou = 0f
        for (i in tracks.indices) {
            val t = tracks[i]
            if (t.className != det.className) continue
            val overlap = iou(t.box, det.box)
            if (overlap > bestIou) {
                bestIou = overlap
                bestTrackIndex = i
            }
        }

        if (bestTrackIndex >= 0 && bestIou >= 0.25f) {
            val track = tracks[bestTrackIndex]
            track.streak += 1
            track.missed = 0
            track.box = blendRect(track.box, det.box, 0.65f)
            matchedTrackIndexes.add(bestTrackIndex)
            if (track.streak >= 1) {
                stable.add(det.copy(box = track.box))
            }
        } else {
            tracks.add(
                TrafficTrack(
                    className = det.className,
                    box = RectF(det.box),
                    streak = 1,
                    missed = 0
                )
            )
        }
    }

    for (i in tracks.indices) {
        if (i !in matchedTrackIndexes) {
            tracks[i].missed += 1
        }
    }
    tracks.removeAll { it.missed > 4 }

    return stable
}

/** 新舊位置混在一起，讓方框移動看起來比較平順。 */
private fun blendRect(oldRect: RectF, newRect: RectF, oldWeight: Float): RectF {
    // 多保留一點舊位置，方框就不會因為模型小誤差一直抖動。
    val nw = 1f - oldWeight
    return RectF(
        oldRect.left * oldWeight + newRect.left * nw,
        oldRect.top * oldWeight + newRect.top * nw,
        oldRect.right * oldWeight + newRect.right * nw,
        oldRect.bottom * oldWeight + newRect.bottom * nw
    )
}

/** 決定多久辨識一次；越常辨識越即時，但也越耗電。 */
private enum class TrafficPerfMode(
    val label: String,
    val inferEveryNFrames: Int,
    val baseInferEveryNFrames: Int,
    val trafficFrameOffset: Int
) {
    REALTIME("即時", inferEveryNFrames = 1, baseInferEveryNFrames = 1, trafficFrameOffset = 0),
    BALANCED("平衡", inferEveryNFrames = 2, baseInferEveryNFrames = 2, trafficFrameOffset = 1),
    SMOOTH("省電", inferEveryNFrames = 3, baseInferEveryNFrames = 3, trafficFrameOffset = 1)
}

/** 把使用者選的模式立刻套用，也存起來讓下次打開還能記得。 */
private fun setTrafficMode(
    state: MutableState<TrafficPerfMode>,
    prefs: SharedPreferences,
    mode: TrafficPerfMode
) {
    // 同時改畫面和儲存設定，這次與下次開 App 都會是同一個模式。
    state.value = mode
    prefs.edit().putString(PREF_KEY_TRAFFIC_MODE, mode.name).apply()
}

/** 讀取上次選的模式；如果沒有資料或資料不對，就用省電模式。 */
private fun loadSavedTrafficMode(prefs: SharedPreferences): TrafficPerfMode {
    // 對舊版或無效值回退到省電模式，避免偏好資料異常導致 App 無法啟動。
    val raw = prefs.getString(PREF_KEY_TRAFFIC_MODE, null)
    return TrafficPerfMode.entries.firstOrNull { it.name == raw } ?: TrafficPerfMode.SMOOTH
}

/** 顯示 USB 攝影機有沒有連上、正在用哪台相機，以及目前找到幾個物件。 */
@Composable
private fun StatusHeader(
    uvcConnected: Boolean,
    detectionCount: Int,
    cameraStatus: String,
    activeCameraLabel: String,
    uvcStatus: String,
    onSettingsClick: () -> Unit
) {
    val statusColor = when {
        uvcConnected -> CameraSuccess
        uvcStatus.contains("等待") || uvcStatus.contains("請求") -> CameraWarning
        else -> CameraError
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.72f),
                        Color.Transparent
                    )
                )
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Column {
                    Text(
                        text = "視覺導航助手",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (uvcConnected) activeCameraLabel else uvcStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = CameraOnSurfaceMuted
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = "$detectionCount 個目標",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                }

                SettingsEntryButton(onClick = onSettingsClick)
            }
        }

        if (!uvcConnected) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = cameraStatus,
                style = MaterialTheme.typography.labelSmall,
                color = CameraOnSurfaceMuted
            )
        }
    }
}

/** 主畫面右上角的設定入口按鈕。 */
@Composable
private fun SettingsEntryButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(18.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.18f),
            contentColor = Color.White
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
    ) {
        Text(
            text = "設定",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 獨立設定頁面，包含曝光、偵測模式、語音與除錯等選項。 */
@Composable
private fun SettingsScreen(
    uvcExposureLevel: Int,
    trafficMode: TrafficPerfMode,
    voiceEnabled: Boolean,
    voiceIntervalMs: Long,
    showDebugInfo: Boolean,
    usbDebugInfo: String,
    onExposureDecrease: () -> Unit,
    onExposureIncrease: () -> Unit,
    onTrafficModeChange: (TrafficPerfMode) -> Unit,
    onVoiceToggle: (Boolean) -> Unit,
    onVoiceIntervalChange: (Long) -> Unit,
    onDebugToggle: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = CameraPrimary
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)
                ) {
                    Text(
                        text = "← 返回",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = "設定",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

            SettingsContent(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                uvcExposureLevel = uvcExposureLevel,
                trafficMode = trafficMode,
                voiceEnabled = voiceEnabled,
                voiceIntervalMs = voiceIntervalMs,
                showDebugInfo = showDebugInfo,
                usbDebugInfo = usbDebugInfo,
                onExposureDecrease = onExposureDecrease,
                onExposureIncrease = onExposureIncrease,
                onTrafficModeChange = onTrafficModeChange,
                onVoiceToggle = onVoiceToggle,
                onVoiceIntervalChange = onVoiceIntervalChange,
                onDebugToggle = onDebugToggle
            )
        }
    }
}

/** 設定頁內容：曝光、偵測模式、語音間隔與除錯資訊。 */
@Composable
private fun SettingsContent(
    modifier: Modifier = Modifier,
    uvcExposureLevel: Int,
    trafficMode: TrafficPerfMode,
    voiceEnabled: Boolean,
    voiceIntervalMs: Long,
    showDebugInfo: Boolean,
    usbDebugInfo: String,
    onExposureDecrease: () -> Unit,
    onExposureIncrease: () -> Unit,
    onTrafficModeChange: (TrafficPerfMode) -> Unit,
    onVoiceToggle: (Boolean) -> Unit,
    onVoiceIntervalChange: (Long) -> Unit,
    onDebugToggle: () -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingSection(title = "曝光調整") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SettingChip(text = "−", selected = false, onClick = onExposureDecrease)
                SettingChip(
                    text = "曝光 $uvcExposureLevel",
                    selected = true,
                    onClick = {},
                    modifier = Modifier.weight(1f)
                )
                SettingChip(text = "+", selected = false, onClick = onExposureIncrease)
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

        SettingSection(title = "偵測模式") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TrafficPerfMode.entries.forEach { mode ->
                    SettingChip(
                        text = mode.label,
                        selected = trafficMode == mode,
                        onClick = { onTrafficModeChange(mode) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

        SettingSection(title = "語音提醒") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (voiceEnabled) "已開啟" else "已關閉",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Switch(
                    checked = voiceEnabled,
                    onCheckedChange = onVoiceToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = CameraPrimary,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = MaterialTheme.colorScheme.outline
                    )
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(2000L to "2 秒", 3000L to "3 秒", 5000L to "5 秒").forEach { (ms, label) ->
                    SettingChip(
                        text = label,
                        selected = voiceIntervalMs == ms,
                        onClick = { onVoiceIntervalChange(ms) },
                        modifier = Modifier.weight(1f),
                        enabled = voiceEnabled
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

        SettingSection(title = "偵測項目") {
            Text(
                text = "人 · 車 · 障礙物 · 交通號誌",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onDebugToggle)
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "USB 除錯資訊",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (showDebugInfo) "收起 ▲" else "展開 ▼",
                style = MaterialTheme.typography.labelSmall,
                color = CameraPrimary
            )
        }

        if (showDebugInfo) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ) {
                Text(
                    text = usbDebugInfo,
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = CameraOnSurfaceMuted
                )
            }
        }
    }
}

/** 畫出設定區的一個小標題和它底下的選項。 */
@Composable
private fun SettingSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
        content()
    }
}

/** 可按的圓角選項按鈕；被選到時會用不同顏色顯示。 */
@Composable
private fun SettingChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) CameraPrimary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            disabledContentColor = CameraOnSurfaceMuted
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/** UVC 預覽與分析使用的固定影格尺寸。 */
private const val UVC_FRAME_WIDTH = 640
private const val UVC_FRAME_HEIGHT = 480
private const val UVC_INFERENCE_EVERY_N_FRAMES = 3

/** 使用者偏好設定的儲存鍵值。 */
private const val PREFS_NAME = "yolo_camera_prefs"
private const val PREF_KEY_TRAFFIC_MODE = "traffic_perf_mode"
private const val PREF_KEY_VOICE_ENABLED = "voice_enabled"
private const val PREF_KEY_VOICE_INTERVAL_MS = "voice_interval_ms"

private const val MIN_VOICE_SCORE = 0.45f
private const val MIN_DETECTION_BOTTOM = 0.50f
private const val MIN_SAME_KEY_FRAMES_FOR_SPEAK = 3
private const val DEFAULT_VOICE_INTERVAL_MS = 3000L

/**
 * 記住最近講過什麼，避免同一件事一直重複講。
 * 同一個提示要連續出現幾次，而且距離上次說話夠久，才會再播一次。
 */
private class VoiceDebounceState {
    // lastCandidateKey / sameKeyCount 用來確認連續影格；lastSpokenAtMs 則限制最短播報間隔。
    var lastCandidateKey: String? = null
    var sameKeyCount: Int = 0
    var lastSpokenKey: String? = null
    var lastSpokenAtMs: Long = 0L

    fun reset() {
        lastCandidateKey = null
        sameKeyCount = 0
        lastSpokenKey = null
        lastSpokenAtMs = 0L
    }
}

/** 一句準備要說的話，以及用來判斷是不是重複提示的名稱。 */
private data class VoicePrompt(
    val key: String,
    val message: String,
)

/** 改變兩次語音之間至少要等多久，並把選擇存起來。 */
private fun setVoiceIntervalMs(
    state: MutableState<Long>,
    prefs: SharedPreferences,
    intervalMs: Long
) {
    state.value = intervalMs
    prefs.edit().putLong(PREF_KEY_VOICE_INTERVAL_MS, intervalMs).apply()
}

/** 讀取語音間隔；如果資料不在可選範圍內，就用預設值。 */
private fun loadSavedVoiceIntervalMs(prefs: SharedPreferences): Long {
    // 僅接受 UI 提供的三個選項，避免被舊版或手動修改的偏好值影響播報節奏。
    val saved = prefs.getLong(PREF_KEY_VOICE_INTERVAL_MS, DEFAULT_VOICE_INTERVAL_MS)
    return when (saved) {
        2000L, 3000L, 5000L -> saved
        else -> DEFAULT_VOICE_INTERVAL_MS
    }
}

/**
 * 從目前看到的物件中挑出比較近、比較可信的目標，整理成要說的中文句子。
 * 這裡只決定要說什麼，真正播放和控制多久說一次會在別處處理。
 */
private fun createVoicePrompts(detections: List<Detection>): List<VoicePrompt> {
    fun isNear(det: Detection): Boolean {
        // 使用 box 的底部位置和大小粗略判斷「前方更近」。
        val boxArea = (det.box.width().coerceAtLeast(0f)) * (det.box.height().coerceAtLeast(0f))
        return det.box.bottom >= MIN_DETECTION_BOTTOM && boxArea >= 0.02f
    }

    // 分數高、而且越靠近畫面下方的物件，通常越接近使用者，所以優先提醒。
    fun voiceScore(det: Detection): Float = det.score * (0.6f + det.box.bottom)
    val prompts = mutableListOf<VoicePrompt>()

    val traffic = detections
        .filter { it.group == Detection.Group.TRAFFIC && it.score >= MIN_VOICE_SCORE && isNear(it) }

    if (traffic.isNotEmpty()) {
        // 先提醒交通資訊，並固定順序，使用起來比較不會每次講法都不同。
        val orderedLabels = listOf("人行道", "紅燈", "黃燈", "綠燈", "上樓梯", "下樓梯")
        for (label in orderedLabels) {
            val candidates = traffic.filter { it.labelZh == label }
            if (candidates.isNotEmpty()) {
                val best = candidates.maxByOrNull { voiceScore(it) } ?: candidates.first()
                val key = "traffic:${best.labelZh}"
                val msg = when (best.labelZh) {
                    "人行道" -> "前方有人行道"
                    "紅燈" -> "前方紅燈"
                    "黃燈" -> "前方黃燈"
                    "綠燈" -> "前方綠燈"
                    "上樓梯" -> "前方上樓梯"
                    "下樓梯" -> "前方下樓梯"
                    else -> "前方 ${best.labelZh}"
                }
                prompts.add(VoicePrompt(key = key, message = msg))
            }
        }

        // 兜底：如果 traffic 裡出現但 label 不在清單，仍播一次較高分的。
        if (prompts.isEmpty()) {
            val best = traffic.maxByOrNull { voiceScore(it) } ?: traffic.first()
            prompts.add(
                VoicePrompt(
                    key = "traffic:${best.labelZh}",
                    message = "前方 ${best.labelZh}"
                )
            )
        }
    }

    val obstacles = detections
        .filter { it.group == Detection.Group.OBSTACLE && it.score >= MIN_VOICE_SCORE && isNear(it) }
    if (obstacles.isNotEmpty()) {
        val best = obstacles.maxByOrNull { voiceScore(it) } ?: obstacles.first()
        prompts.add(
            VoicePrompt(
                key = "obstacle",
                message = "前方有障礙物"
            )
        )
    }

    val people = detections
        .filter { it.group == Detection.Group.PERSON && it.score >= MIN_VOICE_SCORE && isNear(it) }
    if (people.isNotEmpty()) {
        val best = people.maxByOrNull { voiceScore(it) } ?: people.first()
        prompts.add(
            VoicePrompt(
                key = "person",
                message = "前方有行人"
            )
        )
    }

    val vehicles = detections
        .filter { it.group == Detection.Group.VEHICLE && it.score >= MIN_VOICE_SCORE && isNear(it) }
    if (vehicles.isNotEmpty()) {
        val best = vehicles.maxByOrNull { voiceScore(it) } ?: vehicles.first()
        prompts.add(
            VoicePrompt(
                key = "vehicle",
                message = "前方有車輛"
            )
        )
    }

    return prompts
}

/** 把模型找到的方框和中文名稱畫在攝影機畫面上。 */
@Composable
private fun DetectionOverlay(
    detections: List<Detection>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        detections.forEach { det ->
            // 模型給的是比例；這裡換成實際螢幕位置，所以不同大小的螢幕都能正常顯示。
            val r = det.box
            val left = (r.left * w).coerceIn(0f, w)
            val top = (r.top * h).coerceIn(0f, h)
            val right = (r.right * w).coerceIn(0f, w)
            val bottom = (r.bottom * h).coerceIn(0f, h)

            val color = when (det.group) {
                Detection.Group.PERSON -> DetectionPerson
                Detection.Group.VEHICLE -> DetectionVehicle
                Detection.Group.OBSTACLE -> DetectionObstacle
                Detection.Group.TRAFFIC -> DetectionTraffic
            }

            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = ComposeSize(right - left, bottom - top),
                style = Stroke(width = 3f)
            )

            drawContext.canvas.nativeCanvas.apply {
                val label = "${det.labelZh}  ${(det.score * 100f).toInt()}%"
                val paint = android.graphics.Paint().apply {
                    this.color = android.graphics.Color.WHITE
                    this.textSize = 32f
                    this.isAntiAlias = true
                    this.isFakeBoldText = true
                }
                val bgPaint = android.graphics.Paint().apply {
                    this.color = android.graphics.Color.argb(200, 0, 0, 0)
                    this.isAntiAlias = true
                }
                val textX = left + 4f
                val textY = (top - 8f).coerceAtLeast(36f)
                val textWidth = paint.measureText(label)
                val fm = paint.fontMetrics
                val bgTop = textY + fm.ascent - 4f
                val bgBottom = textY + fm.descent + 4f
                drawRoundRect(
                    textX - 6f,
                    bgTop,
                    textX + textWidth + 6f,
                    bgBottom,
                    6f,
                    6f,
                    bgPaint
                )
                drawText(label, textX, textY, paint)
            }
        }
    }
}
