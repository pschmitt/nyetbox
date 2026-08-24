@file:Suppress("UnsafeOptInUsageError")

package dev.pschmitt.nyetbox.ui.scanner

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.util.Size as AndroidSize
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pschmitt.nyetbox.data.repository.ScannerLens
import dev.pschmitt.nyetbox.data.repository.ScannerRearLens
import dev.pschmitt.nyetbox.data.repository.ScannerResolution
import dev.pschmitt.nyetbox.scanner.BarcodeAnalyzer
import dev.pschmitt.nyetbox.scanner.NetBoxTarget
import dev.pschmitt.nyetbox.ui.common.NetBoxBottomBar
import dev.pschmitt.nyetbox.ui.common.NetBoxResponsiveScaffold
import dev.pschmitt.nyetbox.ui.navigation.Route
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    onTargetFound: (NetBoxTarget) -> Unit,
    onBack: () -> Unit,
    onNavigate: (Route) -> Unit,
    showBottomBar: Boolean = true,
    viewModel: ScannerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scannerLens by viewModel.scannerLens.collectAsStateWithLifecycle()
    val scannerRearLens by viewModel.scannerRearLens.collectAsStateWithLifecycle()
    val scannerResolution by viewModel.scannerResolution.collectAsStateWithLifecycle()
    var camera by remember { mutableStateOf<Camera?>(null) }
    var availableCameras by remember { mutableStateOf<List<ScannerCameraOption>>(emptyList()) }
    var selectedRearCameraId by remember { mutableStateOf<String?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }

    val rearCameras = availableCameras.filter { it.lens == ScannerLens.Back }
    val canSwitchFacing =
        availableCameras.any { it.lens == ScannerLens.Back } &&
            availableCameras.any { it.lens == ScannerLens.Front }
    val selectedRearCamera =
        rearCameras.firstOrNull { it.id == selectedRearCameraId } ?: rearCameras.firstOrNull()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasCameraPermission = granted
        }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(state) {
        val found = state as? ScanResultState.Found ?: return@LaunchedEffect
        onTargetFound(found.target)
    }

    NetBoxResponsiveScaffold(
        // The camera preview is an immersive surface. On tablets it must cover the area normally
        // occupied by the navigation rail, otherwise the rail remains visible behind the preview.
        fullScreenOnRail = true,
        bottomBar = {
            if (showBottomBar) {
                NetBoxBottomBar(onNavigate = onNavigate)
            }
        },
        topBar = {
            TopAppBar(
                title = { Text("Scan device sticker") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (hasCameraPermission) {
                CameraPreview(
                    desiredLens = scannerLens,
                    selectedCameraId = selectedRearCameraId,
                    resolution = scannerResolution,
                    onCodeScanned = viewModel::onCodeScanned,
                    onAvailableCameras = { options ->
                        availableCameras = options
                        if (
                            selectedRearCameraId !in
                                options.filter { it.lens == ScannerLens.Back }.map { it.id }
                        ) {
                            selectedRearCameraId =
                                defaultRearCamera(
                                        options.filter { it.lens == ScannerLens.Back },
                                        scannerRearLens,
                                    )
                                    ?.id
                        }
                    },
                    onCameraReady = { camera = it },
                    zoomRatio = zoomRatio,
                    onZoomRatioChanged = { zoomRatio = it },
                )
            } else {
                Text(
                    "Camera permission is required to scan device stickers",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                if (scannerLens == ScannerLens.Back && rearCameras.size > 1) {
                    RearLensSelector(
                        cameras = rearCameras,
                        selectedCameraId = selectedRearCamera?.id,
                        onCameraSelected = { option ->
                            selectedRearCameraId = option.id
                            viewModel.setScannerLens(ScannerLens.Back)
                            camera?.cameraControl?.enableTorch(false)
                            torchOn = false
                        },
                    )
                }
                if (zoomRatio > 1.01f) {
                    Surface(
                        modifier = Modifier.zIndex(1f),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                        contentColor = Color.White,
                    ) {
                        Text(
                            text = formatZoomLabel(zoomRatio),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                }
                ScannerControls(
                    modifier = Modifier.zIndex(1f),
                    showTorch = camera?.cameraInfo?.hasFlashUnit() == true,
                    torchOn = torchOn,
                    onTorchClick = {
                        torchOn = !torchOn
                        camera?.cameraControl?.enableTorch(torchOn)
                    },
                    showFacingSwitch = canSwitchFacing,
                    showingFront = scannerLens == ScannerLens.Front,
                    onFacingSwitchClick = {
                        val nextLens =
                            if (scannerLens == ScannerLens.Front) ScannerLens.Back
                            else ScannerLens.Front
                        viewModel.setScannerLens(nextLens)
                        camera?.cameraControl?.enableTorch(false)
                        torchOn = false
                    },
                )
            }

            when (val current = state) {
                is ScanResultState.Resolving -> ScanOverlay { CircularProgressIndicator() }
                is ScanResultState.NotRecognized -> {
                    ScanOverlay {
                        Text(
                            "That doesn't look like a NetBox device link",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    LaunchedEffect(current) {
                        delay(1500)
                        viewModel.reset()
                    }
                }
                is ScanResultState.NotFound -> {
                    ScanOverlay {
                        Text(
                            "No device found for asset tag ${current.assetTag}",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    LaunchedEffect(current) {
                        delay(1500)
                        viewModel.reset()
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun RearLensSelector(
    cameras: List<ScannerCameraOption>,
    selectedCameraId: String?,
    onCameraSelected: (ScannerCameraOption) -> Unit,
) {
    Surface(
        modifier = Modifier.zIndex(1f),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
        contentColor = Color.White,
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(6.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            cameras.forEach { camera ->
                val selected = camera.id == selectedCameraId
                LensButton(
                    label = if (selected) camera.label else camera.label.removeSuffix("×"),
                    selected = selected,
                    onClick = { onCameraSelected(camera) },
                )
            }
        }
    }
}

/** A round lens button, like the Pixel Camera app's zoom selector. */
@Composable
private fun LensButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor =
            if (selected) MaterialTheme.colorScheme.onPrimary else Color.White.copy(alpha = 0.75f),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun ScannerControls(
    modifier: Modifier = Modifier,
    showTorch: Boolean,
    torchOn: Boolean,
    onTorchClick: () -> Unit,
    showFacingSwitch: Boolean,
    showingFront: Boolean,
    onFacingSwitchClick: () -> Unit,
) {
    Surface(
        modifier = modifier.zIndex(1f),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
        contentColor = Color.White,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showTorch) {
                IconButton(onClick = onTorchClick) {
                    Icon(
                        if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription =
                            if (torchOn) "Turn flashlight off" else "Turn flashlight on",
                    )
                }
            }
            if (showFacingSwitch) {
                IconButton(onClick = onFacingSwitchClick) {
                    Icon(
                        Icons.Default.Cameraswitch,
                        contentDescription =
                            if (showingFront) "Use rear camera" else "Use front camera",
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanOverlay(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun CameraPreview(
    desiredLens: ScannerLens,
    selectedCameraId: String?,
    resolution: ScannerResolution,
    onCodeScanned: (String) -> Unit,
    onAvailableCameras: (List<ScannerCameraOption>) -> Unit,
    onCameraReady: (Camera?) -> Unit,
    zoomRatio: Float,
    onZoomRatioChanged: (Float) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val cameraProviderFuture = remember(context) { ProcessCameraProvider.getInstance(context) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val boundCamera = remember { mutableStateOf<Camera?>(null) }
    var cameraSwitching by remember { mutableStateOf(true) }
    var focusTapPoint by remember { mutableStateOf<Offset?>(null) }
    val focusRingAlpha = remember { Animatable(0f) }
    LaunchedEffect(focusTapPoint) {
        if (focusTapPoint != null) {
            focusRingAlpha.snapTo(1f)
            focusRingAlpha.animateTo(0f, animationSpec = tween(600, delayMillis = 400))
        }
    }
    var lastDetection by remember { mutableStateOf<BarcodeAnalyzer.Detection?>(null) }
    val detectionAlpha = remember { Animatable(0f) }
    LaunchedEffect(lastDetection) {
        if (lastDetection != null) {
            detectionAlpha.snapTo(1f)
            detectionAlpha.animateTo(0f, animationSpec = tween(900, delayMillis = 250))
        }
    }
    val switchOverlayAlpha by
        animateFloatAsState(
            targetValue = if (cameraSwitching) 1f else 0f,
            animationSpec = tween(durationMillis = 180),
            label = "camera-switch-overlay",
        )

    DisposableEffect(Unit) { onDispose { cameraExecutor.shutdown() } }

    LaunchedEffect(cameraProviderFuture) { cameraProvider = cameraProviderFuture.get() }

    LaunchedEffect(boundCamera.value, zoomRatio) {
        val camera = boundCamera.value ?: return@LaunchedEffect
        val zoomState = camera.cameraInfo.zoomState.value ?: return@LaunchedEffect
        val clamped = zoomRatio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        if (clamped != zoomRatio) onZoomRatioChanged(clamped)
        camera.cameraControl.setZoomRatio(clamped)
    }

    DisposableEffect(cameraProvider, previewView, desiredLens, selectedCameraId, resolution) {
        val provider = cameraProvider
        val view = previewView
        if (provider == null || view == null) {
            onDispose {}
        } else {
            cameraSwitching = true
            onCameraReady(null)
            boundCamera.value = null
            val available = availableCameraOptions(provider)
            onAvailableCameras(available)
            val activeCamera =
                available.firstOrNull { it.id == selectedCameraId && it.lens == desiredLens }
                    ?: available.firstOrNull { it.lens == desiredLens }
                    ?: available.firstOrNull()
            if (activeCamera != null) {
                val previewBuilder = Preview.Builder()
                val analysisBuilder =
                    ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        // CameraX's unconfigured default analysis resolution is a low 640x480,
                        // which struggles on small, dense, or distant QR codes that the stock
                        // camera app's much larger preview/capture frames decode fine. Both
                        // 1280x720 and 1920x1080 are mainstream, universally supported
                        // YUV_420_888 sizes; STRATEGY_KEEP_ONLY_LATEST just drops frames rather
                        // than backing up if a device can't keep up with the chosen one.
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        resolveAnalysisTargetSize(context, resolution),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER,
                                    )
                                )
                                .build()
                        )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    activeCamera.physicalCameraId?.let { physicalCameraId ->
                        // CameraSelector carries the physical ID through CameraX's lifecycle
                        // binding. Set it on each use-case as well: this is the Camera2 interop
                        // path that writes OutputConfiguration.setPhysicalCameraId(), which is
                        // required by logical multi-camera implementations such as Pixel's.
                        Camera2Interop.Extender(previewBuilder)
                            .setPhysicalCameraId(physicalCameraId)
                        Camera2Interop.Extender(analysisBuilder)
                            .setPhysicalCameraId(physicalCameraId)
                    }
                }
                val preview =
                    previewBuilder.build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis =
                    analysisBuilder.build().also {
                        it.setAnalyzer(
                            cameraExecutor,
                            BarcodeAnalyzer { detection ->
                                lastDetection = detection
                                onCodeScanned(detection.text)
                            },
                        )
                    }
                runCatching {
                    // CameraX must be fully unbound before a different physical or facing
                    // camera can be selected. Keeping this in one synchronous effect avoids
                    // an old async listener rebinding the previous lens after a user switch.
                    provider.unbindAll()
                    provider
                        .bindToLifecycle(
                            lifecycleOwner,
                            activeCamera.selector,
                            preview,
                            analysis,
                        )
                        .also {
                            // Physical rear-lens selection chooses the sensor; this zoom is a
                            // separate digital crop applied to whichever sensor is active.
                            it.cameraControl.setZoomRatio(zoomRatio)
                        }
                }
                    .onSuccess {
                        boundCamera.value = it
                        onCameraReady(it)
                        cameraSwitching = false
                    }
                    .onFailure {
                        Timber.e(it, "Unable to bind ${activeCamera.id}")
                        cameraSwitching = false
                    }
            } else {
                Timber.w("No camera available for lens %s", desiredLens)
            }
            onDispose {
                boundCamera.value = null
                onCameraReady(null)
                runCatching { provider.unbindAll() }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize().zIndex(-1f),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                // SurfaceView (the default PERFORMANCE mode) can sit above Compose's controls and
                // consume their touch events on some devices, notably Pixel and Zenfone.
                // TextureView
                // keeps the preview below the lens/facing controls while retaining tap-to-focus.
                previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

                // Tap-to-focus: set directly on the PreviewView rather than a Compose pointerInput
                // modifier, since AndroidView touch dispatch to an embedded native View can
                // otherwise
                // swallow gestures before Compose sees them - this is the standard CameraX recipe.
                var scalingGesture = false
                val scaleDetector =
                    ScaleGestureDetector(
                        ctx,
                        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                                scalingGesture = true
                                return true
                            }

                            override fun onScale(detector: ScaleGestureDetector): Boolean {
                                val camera = boundCamera.value ?: return true
                                val zoomState = camera.cameraInfo.zoomState.value ?: return true
                                val nextZoom =
                                    (zoomState.zoomRatio * detector.scaleFactor).coerceIn(
                                        zoomState.minZoomRatio,
                                        zoomState.maxZoomRatio,
                                    )
                                camera.cameraControl.setZoomRatio(nextZoom)
                                onZoomRatioChanged(nextZoom)
                                return true
                            }
                        },
                    )
                previewView.setOnTouchListener { view, event ->
                    val wasScaling = scalingGesture
                    scaleDetector.onTouchEvent(event)
                    if (
                        event.actionMasked == MotionEvent.ACTION_UP &&
                            !wasScaling &&
                            !scalingGesture
                    ) {
                        val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                        val action =
                            FocusMeteringAction.Builder(point)
                                // Keep the tapped focus locked instead of reverting to continuous
                                // AF after a few seconds - that reversion could re-hunt right as
                                // the user is holding the phone steady on a code to decode it,
                                // which read as "focus randomly stops working". A later tap starts
                                // a fresh one-shot action that replaces this lock.
                                .disableAutoCancel()
                                .build()
                        boundCamera.value?.cameraControl?.startFocusAndMetering(action)
                        focusTapPoint = Offset(event.x, event.y)
                        view.performClick()
                    }
                    if (
                        event.actionMasked == MotionEvent.ACTION_UP ||
                            event.actionMasked == MotionEvent.ACTION_CANCEL
                    ) {
                        scalingGesture = false
                    }
                    true
                }

                previewView
            },
            update = { view -> previewView = view },
        )
        focusTapPoint?.let { point ->
            if (focusRingAlpha.value > 0f) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(
                        color = Color.White.copy(alpha = focusRingAlpha.value),
                        radius = 32.dp.toPx(),
                        center = point,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
        }
        lastDetection?.let { detection ->
            if (detectionAlpha.value > 0f) {
                Canvas(Modifier.fillMaxSize()) {
                    val mirror = desiredLens == ScannerLens.Front
                    val mapped =
                        detection.points.map { point ->
                            mapImagePointToView(
                                x = point.x,
                                y = point.y,
                                imageWidth = detection.imageWidth,
                                imageHeight = detection.imageHeight,
                                rotationDegrees = detection.rotationDegrees,
                                viewWidth = size.width,
                                viewHeight = size.height,
                                mirror = mirror,
                            )
                        }
                    if (mapped.isNotEmpty()) {
                        val minX = mapped.minOf { it.x }
                        val minY = mapped.minOf { it.y }
                        val maxX = mapped.maxOf { it.x }
                        val maxY = mapped.maxOf { it.y }
                        // ZXing's finder-pattern points sit near, not at, the QR code's outer
                        // edges - pad the box so the highlight covers the whole symbol.
                        val padX = ((maxX - minX) * 0.2f).coerceAtLeast(12.dp.toPx())
                        val padY = ((maxY - minY) * 0.2f).coerceAtLeast(12.dp.toPx())
                        val color = Color(0xFF00E676).copy(alpha = detectionAlpha.value)
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(minX - padX, minY - padY),
                            size = Size(maxX - minX + padX * 2, maxY - minY + padY * 2),
                            cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                            style = Stroke(width = 3.dp.toPx()),
                        )
                    }
                }
            }
        }
        if (switchOverlayAlpha > 0f) {
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = switchOverlayAlpha },
                contentAlignment = Alignment.Center,
            ) {
                Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {}
                CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

private data class ScannerCameraOption(
    val id: String,
    val lens: ScannerLens,
    val label: String,
    val selector: CameraSelector,
    val physicalCameraId: String? = null,
    // 35mm-equivalent focal length: focal length alone isn't comparable across physical lenses
    // because each has a differently sized sensor (a tele lens' sensor is typically much smaller
    // than the main lens'), so raw focal-length ratios understate its actual optical zoom.
    val equivFocalLength: Float? = null,
    val zoomRatio: Float = 1f,
)

private fun defaultRearCamera(
    cameras: List<ScannerCameraOption>,
    preferredLens: ScannerRearLens,
): ScannerCameraOption? {
    if (cameras.isEmpty()) return null
    val targetZoom =
        when (preferredLens) {
            ScannerRearLens.Automatic,
            ScannerRearLens.Wide -> 1f
            ScannerRearLens.UltraWide -> 0.6f
            ScannerRearLens.Telephoto -> 2f
        }
    return cameras.minByOrNull { abs(it.zoomRatio - targetZoom) }
}

private fun availableCameraOptions(provider: ProcessCameraProvider): List<ScannerCameraOption> {
    val options =
        provider.availableCameraInfos
            .flatMap { info ->
                val camera2Info =
                    runCatching { Camera2CameraInfo.from(info) }.getOrNull()
                        ?: return@flatMap emptyList()
                val facing =
                    camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
                        ?: return@flatMap emptyList()
                val lens =
                    when (facing) {
                        CameraCharacteristics.LENS_FACING_FRONT -> ScannerLens.Front
                        CameraCharacteristics.LENS_FACING_BACK -> ScannerLens.Back
                        else -> return@flatMap emptyList()
                    }
                val physicalInfos =
                    if (lens == ScannerLens.Back) info.physicalCameraInfos else emptySet()
                if (physicalInfos.isNotEmpty()) {
                    physicalInfos.mapNotNull { physicalInfo ->
                        val physicalCamera2Info =
                            runCatching { Camera2CameraInfo.from(physicalInfo) }.getOrNull()
                                ?: return@mapNotNull null
                        val cameraId = physicalCamera2Info.cameraId
                        val focalLength =
                            physicalCamera2Info
                                .getCameraCharacteristic(
                                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                                )
                                ?.firstOrNull()
                        val sensorSize =
                            physicalCamera2Info.getCameraCharacteristic(
                                CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
                            )
                        val equivFocalLength =
                            if (focalLength != null && sensorSize != null) {
                                val diagonal =
                                    hypot(sensorSize.width.toDouble(), sensorSize.height.toDouble())
                                        .toFloat()
                                // 43.27mm is the diagonal of a full-frame (36x24mm) sensor - the
                                // conventional reference for "35mm-equivalent" focal length.
                                focalLength * (43.27f / diagonal)
                            } else {
                                null
                            }
                        ScannerCameraOption(
                            id = "physical:$cameraId",
                            lens = ScannerLens.Back,
                            label = "Rear lens",
                            selector =
                                // Pixel devices expose the ultrawide and wide sensors as physical
                                // cameras behind one logical camera. CameraX applies this ID to the
                                // Preview and ImageAnalysis output configurations, forcing the
                                // requested physical sensor instead of clamping a logical zoom.
                                info.selector(physicalCameraId = cameraId),
                            physicalCameraId = cameraId,
                            equivFocalLength = equivFocalLength,
                        )
                    }
                } else {
                    listOf(
                        ScannerCameraOption(
                            id = "logical:${camera2Info.cameraId}",
                            lens = lens,
                            label =
                                if (lens == ScannerLens.Front) "Front camera" else "Back camera",
                            selector = info.selector(),
                        )
                    )
                }
            }
            .distinctBy { it.id }

    return labelRearCameraOptions(options)
}

private fun CameraInfo.selector(physicalCameraId: String? = null): CameraSelector {
    val cameraId = Camera2CameraInfo.from(this).cameraId
    return CameraSelector.Builder()
        .addCameraFilter { infos ->
            infos.filter { info ->
                runCatching { Camera2CameraInfo.from(info).cameraId == cameraId }
                    .getOrDefault(false)
            }
        }
        .apply { physicalCameraId?.let(::setPhysicalCameraId) }
        .build()
}

private fun labelRearCameraOptions(options: List<ScannerCameraOption>): List<ScannerCameraOption> {
    val rear = options.filter { it.lens == ScannerLens.Back }
    if (rear.size <= 1) return options

    // The lower-middle equivalent focal length among the rear physical sensors is the "1x" wide
    // lens: shorter focal lengths are ultrawide, longer ones are telephoto. Phones with more than
    // one tele lens (e.g. a mid + periscope tele) still put wide just above ultrawide, hence the
    // lower- rather than upper-middle index for an even lens count.
    val sorted = rear.sortedWith(compareBy(nullsLast()) { it.equivFocalLength })
    val referenceFocal = sorted[(sorted.size - 1) / 2].equivFocalLength
    val labelsById =
        sorted
            .mapIndexed { index, option ->
                val zoomRatio =
                    if (option.equivFocalLength != null && referenceFocal != null) {
                        (option.equivFocalLength / referenceFocal).coerceIn(0.5f, 8f)
                    } else {
                        1f
                    }
                val label =
                    if (option.equivFocalLength != null && referenceFocal != null) {
                        formatZoomLabel(zoomRatio)
                    } else {
                        "Rear ${index + 1}"
                    }
                option.id to (label to zoomRatio)
            }
            .toMap()

    return options.map { option ->
        val (label, zoomRatio) = labelsById[option.id] ?: (option.label to option.zoomRatio)
        option.copy(label = label, zoomRatio = zoomRatio)
    }
}

/** Formats an actual zoom ratio, e.g. 0.6, 1, 2 or 4.8, dropping a trailing ".0". */
private fun formatZoomLabel(ratio: Float): String {
    // The sensor-geometry-derived ratio tends to undershoot a tele lens' marketed zoom factor
    // (real telephoto modules often add a bit of hybrid/sensor-crop zoom on top of pure optical),
    // so round up rather than to nearest - e.g. a computed 4.3x reads as "5x", matching what the
    // stock camera app calls the same lens.
    val rounded = if (ratio < 1f) (ratio * 10).roundToInt() / 10f else ceil(ratio)
    val text =
        if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString()
        else "%.1f".format(rounded)
    return "$text×"
}

/** Devices below this much total RAM are treated as the low tier for [ScannerResolution.Auto]. */
private const val LOW_TIER_RAM_BYTES = 4L * 1024 * 1024 * 1024

private val STANDARD_ANALYSIS_SIZE = AndroidSize(1280, 720)
private val HIGH_ANALYSIS_SIZE = AndroidSize(1920, 1080)

/**
 * The barcode scanner runs on everything from a Pixel to a 2018 midrange tablet (the Mi Pad 4 in
 * this app's own test fleet), and ZXing's decode cost tracks pixel count regardless of how fast the
 * device is - so [ScannerResolution.Auto] picks a lower analysis resolution on hardware that's
 * unlikely to keep up with the sharper one, rather than always defaulting to the best case.
 */
private fun resolveAnalysisTargetSize(
    context: Context,
    resolution: ScannerResolution,
): AndroidSize =
    when (resolution) {
        ScannerResolution.Standard -> STANDARD_ANALYSIS_SIZE
        ScannerResolution.High -> HIGH_ANALYSIS_SIZE
        ScannerResolution.Auto -> {
            val activityManager = context.getSystemService(ActivityManager::class.java)
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memoryInfo)
            val lowTier =
                activityManager?.isLowRamDevice == true ||
                    memoryInfo.totalMem in 1 until LOW_TIER_RAM_BYTES
            if (lowTier) STANDARD_ANALYSIS_SIZE else HIGH_ANALYSIS_SIZE
        }
    }

/**
 * Maps a point in the analysis image's own pixel space (as ZXing sees it) into the displayed
 * PreviewView's pixel space, replicating PreviewView's default FILL_CENTER scale type: rotate by
 * the buffer's sensor-to-display rotation, scale up to cover the view while preserving aspect
 * ratio, center-crop the overflow, then mirror horizontally for a front camera's selfie flip.
 */
private fun mapImagePointToView(
    x: Float,
    y: Float,
    imageWidth: Int,
    imageHeight: Int,
    rotationDegrees: Int,
    viewWidth: Float,
    viewHeight: Float,
    mirror: Boolean,
): Offset {
    val (rx, ry, rotatedWidth, rotatedHeight) =
        when (((rotationDegrees % 360) + 360) % 360) {
            90 -> Quadruple(y, imageWidth - x, imageHeight.toFloat(), imageWidth.toFloat())
            180 ->
                Quadruple(
                    imageWidth - x,
                    imageHeight - y,
                    imageWidth.toFloat(),
                    imageHeight.toFloat(),
                )
            270 -> Quadruple(imageHeight - y, x, imageHeight.toFloat(), imageWidth.toFloat())
            else -> Quadruple(x, y, imageWidth.toFloat(), imageHeight.toFloat())
        }
    val scale = max(viewWidth / rotatedWidth, viewHeight / rotatedHeight)
    val offsetX = (viewWidth - rotatedWidth * scale) / 2f
    val offsetY = (viewHeight - rotatedHeight * scale) / 2f
    val vx = rx * scale + offsetX
    val vy = ry * scale + offsetY
    return Offset(if (mirror) viewWidth - vx else vx, vy)
}

private data class Quadruple(val a: Float, val b: Float, val c: Float, val d: Float)
