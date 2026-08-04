package com.peerlink.app.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.peerlink.app.cast.CastViewHolder
import com.peerlink.app.cast.ScreenDecoder
import com.peerlink.app.network.PeerSessionHolder
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

enum class CastFitMode { Fit, Fill, Original }

private enum class PipMode { Bubble, Window, Fullscreen }

private val PipAccent = Color(0xFF3B6FD9)
private val ResizeGrip = Color(0xFF9AA3B2)

/**
 * Floating receive preview. Video TextureView is always a child of the visible
 * video region (never under a shadowed sibling) so OEM Compose stacks don't
 * hide the Surface.
 */
@Composable
fun FloatingCastPip(
    decoder: ScreenDecoder,
    receiving: Boolean,
    fitMode: CastFitMode,
    mirrored: Boolean = false,
    onToggleMirror: () -> Unit = {},
    onPressShutter: () -> Unit = {}
) {
    var mode by remember { mutableStateOf(PipMode.Window) }
    val videoSize by decoder.videoSize.collectAsState()
    val aspect = (videoSize.first / videoSize.second).coerceIn(0.45f, 2.2f)

    BoxWithConstraints(modifier = Modifier.fillMaxSize().zIndex(8f)) {
        val density = LocalDensity.current
        val bubbleSize = 52.dp
        val chromeH = 28.dp
        val minMiniW = 96.dp
        val minVideoH = 72.dp
        val collapseW = 84.dp
        val collapseH = 60.dp
        val maxMiniW = with(density) { (constraints.maxWidth * 0.92f).toDp() }.coerceAtLeast(minMiniW)
        val maxVideoH = with(density) {
            (constraints.maxHeight * 0.72f).toDp() - chromeH
        }.coerceAtLeast(minVideoH)

        var miniW by remember { mutableStateOf(168.dp) }
        var miniVideoH by remember { mutableStateOf(168.dp / aspect) }
        val miniTotalH = chromeH + miniVideoH
        val miniWPx = with(density) { miniW.toPx() }

        val maxBubbleX = (constraints.maxWidth - with(density) { bubbleSize.toPx() }).coerceAtLeast(0f)
        val maxBubbleY = (constraints.maxHeight - with(density) { bubbleSize.toPx() }).coerceAtLeast(0f)
        val maxWinX = (constraints.maxWidth - miniWPx).coerceAtLeast(0f)
        val maxWinY = (constraints.maxHeight - with(density) { miniTotalH.toPx() }).coerceAtLeast(0f)

        var winX by remember { mutableFloatStateOf(maxWinX - with(density) { 12.dp.toPx() }) }
        var winY by remember { mutableFloatStateOf(with(density) { 120.dp.toPx() }) }
        var bubbleX by remember { mutableFloatStateOf(maxBubbleX) }
        var bubbleY by remember { mutableFloatStateOf(with(density) { 220.dp.toPx() }) }

        fun snapBubbleX(x: Float): Float = if (x < maxBubbleX / 2f) 0f else maxBubbleX

        fun toBubble() {
            bubbleX = snapBubbleX(winX.coerceIn(0f, maxBubbleX))
            bubbleY = winY.coerceIn(0f, maxBubbleY)
            mode = PipMode.Bubble
        }

        fun maybeCollapse(w: Dp, h: Dp): Boolean {
            if (w <= collapseW || h <= collapseH) {
                toBubble()
                return true
            }
            return false
        }

        fun resizeFromEnd(dx: Float, dy: Float) {
            val rawW = with(density) { (miniW.toPx() + dx).toDp() }
            val rawH = with(density) { (miniVideoH.toPx() + dy).toDp() }
            if (maybeCollapse(rawW, rawH)) return
            miniW = rawW.coerceIn(minMiniW, maxMiniW)
            miniVideoH = rawH.coerceIn(minVideoH, maxVideoH)
            winX = winX.coerceIn(0f, (constraints.maxWidth - with(density) { miniW.toPx() }).coerceAtLeast(0f))
            winY = winY.coerceIn(
                0f,
                (constraints.maxHeight - with(density) { (chromeH + miniVideoH).toPx() }).coerceAtLeast(0f)
            )
        }

        fun resizeFromStart(dx: Float, dy: Float) {
            val oldWpx = with(density) { miniW.toPx() }
            val rawWpx = oldWpx - dx
            val rawW = with(density) { rawWpx.toDp() }
            val rawH = with(density) { (miniVideoH.toPx() + dy).toDp() }
            if (maybeCollapse(rawW, rawH)) return
            val nextWpx = rawWpx.coerceIn(
                with(density) { minMiniW.toPx() },
                with(density) { maxMiniW.toPx() }
            )
            val dw = nextWpx - oldWpx
            winX = (winX - dw).coerceAtLeast(0f)
            miniW = with(density) { nextWpx.toDp() }
            miniVideoH = rawH.coerceIn(minVideoH, maxVideoH)
            val maxX = (constraints.maxWidth - with(density) { miniW.toPx() }).coerceAtLeast(0f)
            winX = winX.coerceIn(0f, maxX)
            winY = winY.coerceIn(
                0f,
                (constraints.maxHeight - with(density) { (chromeH + miniVideoH).toPx() }).coerceAtLeast(0f)
            )
        }

        val chromePx = with(density) { chromeH.toPx() }
        val videoWpx = with(density) { miniW.toPx() }
        val videoHpx = with(density) { miniVideoH.toPx() }

        // One TextureView for Window + Fullscreen so mode switch doesn't tear the decoder.
        if (mode == PipMode.Bubble) {
            CastVideoSurface(
                decoder = decoder,
                receiving = receiving,
                fitMode = fitMode,
                videoAspect = aspect,
                enableGestures = false,
                mirrored = mirrored,
                refreshToken = 0,
                modifier = Modifier
                    .offset { IntOffset(winX.roundToInt(), (winY + chromePx).roundToInt()) }
                    .size(miniW, miniVideoH)
                    .graphicsLayer { alpha = 0f }
            )
            Box(
                modifier = Modifier
                    .offset { IntOffset(bubbleX.roundToInt(), bubbleY.roundToInt()) }
                    .size(bubbleSize)
                    .shadow(8.dp, CircleShape)
                    .clip(CircleShape)
                    .background(PipAccent)
                    .border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape)
                    .pointerInput(maxBubbleX, maxBubbleY) {
                        detectDragGestures(
                            onDragEnd = { bubbleX = snapBubbleX(bubbleX) },
                            onDrag = { change, drag ->
                                change.consume()
                                bubbleX = (bubbleX + drag.x).coerceIn(0f, maxBubbleX)
                                bubbleY = (bubbleY + drag.y).coerceIn(0f, maxBubbleY)
                            }
                        )
                    }
                    .clickable {
                        winX = bubbleX.coerceIn(0f, maxWinX)
                        winY = bubbleY.coerceIn(0f, maxWinY)
                        if (miniW < minMiniW) miniW = 168.dp
                        if (miniVideoH < minVideoH) miniVideoH = 168.dp / aspect
                        mode = PipMode.Window
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Cast, "展开小窗", tint = Color.White, modifier = Modifier.size(24.dp))
            }
        } else {
            val isFull = mode == PipMode.Fullscreen
            if (isFull) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .zIndex(0f)
                )
            }

            CastVideoSurface(
                decoder = decoder,
                receiving = receiving,
                fitMode = fitMode,
                videoAspect = aspect,
                enableGestures = isFull,
                mirrored = mirrored,
                refreshToken = if (isFull) 2 else 1,
                modifier = if (isFull) {
                    Modifier.fillMaxSize().zIndex(1f)
                } else {
                    Modifier
                        .offset {
                            IntOffset(winX.roundToInt(), (winY + chromePx).roundToInt())
                        }
                        .size(
                            width = with(density) { videoWpx.toDp() },
                            height = with(density) { videoHpx.toDp() }
                        )
                        .zIndex(1f)
                }
            )

            if (!isFull) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(winX.roundToInt(), winY.roundToInt()) }
                        .width(miniW)
                        .height(miniTotalH)
                        .zIndex(2f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(chromeH)
                            .shadow(6.dp, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                            .background(Color(0xFFF3F5F9))
                            .border(1.dp, Color(0xFFD0D6E0), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                            .pointerInput(maxWinX, maxWinY) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    winX = (winX + drag.x).coerceIn(0f, maxWinX)
                                    winY = (winY + drag.y).coerceIn(0f, maxWinY)
                                }
                            }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Icon(
                            Icons.Rounded.Fullscreen, "全屏", tint = Color(0xFF445066),
                            modifier = Modifier.size(18.dp).clickable { mode = PipMode.Fullscreen }
                        )
                        Icon(
                            Icons.Rounded.PhotoCamera, "快门", tint = Color(0xFF445066),
                            modifier = Modifier.size(18.dp).clickable(onClick = onPressShutter)
                        )
                        Icon(
                            Icons.Rounded.Remove, "收起浮标", tint = Color(0xFF445066),
                            modifier = Modifier.size(18.dp).clickable { toBubble() }
                        )
                    }
                    // Transparent hit area keeps layout; video renders underneath at zIndex 1.
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(miniVideoH)
                            .border(
                                1.dp,
                                Color(0xFFD0D6E0),
                                RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                            )
                    )
                    ResizeCornerHandle(
                        corner = ResizeCorner.BottomStart,
                        modifier = Modifier.align(Alignment.BottomStart)
                    ) { dx, dy -> resizeFromStart(dx, dy) }
                    ResizeCornerHandle(
                        corner = ResizeCorner.BottomEnd,
                        modifier = Modifier.align(Alignment.BottomEnd)
                    ) { dx, dy -> resizeFromEnd(dx, dy) }
                }
            } else {
                // Top-right chrome: mirror / exit fullscreen / bubble
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(2f)
                        .padding(top = 28.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.Top
                ) {
                    RoundIconButton(
                        Icons.Rounded.Flip,
                        "镜像",
                        tint = if (mirrored) PipAccent else Color.White,
                        onClick = onToggleMirror
                    )
                    RoundIconButton(Icons.Rounded.FullscreenExit, "回到小窗") { mode = PipMode.Window }
                    RoundIconButton(Icons.Rounded.Remove, "收起浮标") { toBubble() }
                }
                // Large shutter in lower half, horizontally centered — easy thumb reach.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(3f)
                        .padding(bottom = 72.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .shadow(6.dp, CircleShape)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.92f))
                            .border(4.dp, Color.White, CircleShape)
                            .clickable(onClick = onPressShutter),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .clip(CircleShape)
                                .background(PipAccent)
                                .border(2.dp, Color.White.copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.PhotoCamera,
                                "快门",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class ResizeCorner { BottomStart, BottomEnd }

@Composable
private fun ResizeCornerHandle(
    corner: ResizeCorner,
    modifier: Modifier = Modifier,
    onDrag: (dx: Float, dy: Float) -> Unit
) {
    val alignPad = if (corner == ResizeCorner.BottomStart) Alignment.BottomStart else Alignment.BottomEnd
    Box(
        modifier = modifier
            .size(28.dp)
            .pointerInput(corner) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onDrag(drag.x, drag.y)
                }
            },
        contentAlignment = alignPad
    ) {
        Canvas(
            modifier = Modifier
                .padding(5.dp)
                .size(12.dp)
        ) {
            val stroke = 2.2.dp.toPx()
            val inset = stroke / 2f
            val w = size.width
            val h = size.height
            when (corner) {
                ResizeCorner.BottomEnd -> {
                    // └ rotated → bottom-right 「⌟」
                    drawLine(
                        color = ResizeGrip,
                        start = Offset(inset, h - inset),
                        end = Offset(w - inset, h - inset),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = ResizeGrip,
                        start = Offset(w - inset, inset),
                        end = Offset(w - inset, h - inset),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                }
                ResizeCorner.BottomStart -> {
                    // bottom-left 「⌞」
                    drawLine(
                        color = ResizeGrip,
                        start = Offset(inset, h - inset),
                        end = Offset(w - inset, h - inset),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = ResizeGrip,
                        start = Offset(inset, inset),
                        end = Offset(inset, h - inset),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}

@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .shadow(3.dp, CircleShape)
            .clip(CircleShape)
            .background(Color(0xCC1E1E1E))
            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun CastVideoSurface(
    decoder: ScreenDecoder,
    receiving: Boolean,
    fitMode: CastFitMode,
    videoAspect: Float,
    enableGestures: Boolean,
    mirrored: Boolean = false,
    refreshToken: Int = 0,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(receiving, refreshToken) {
        if (receiving) {
            PeerSessionHolder.session?.sendControl("need_key")
            delay(100)
            PeerSessionHolder.session?.sendControl("need_key")
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val maxW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val maxH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val gestureMod = if (enableGestures) {
            Modifier
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 6f)
                        scale = newScale
                        if (newScale <= 1.01f) offset = Offset.Zero
                        else {
                            val boundX = (maxW * (newScale - 1f)) / 2f
                            val boundY = (maxH * (newScale - 1f)) / 2f
                            offset = Offset(
                                (offset.x + pan.x).coerceIn(-boundX, boundX),
                                (offset.y + pan.y).coerceIn(-boundY, boundY)
                            )
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        scale = 1f
                        offset = Offset.Zero
                    })
                }
        } else Modifier

        Box(modifier = Modifier.fillMaxSize().then(gestureMod)) {
            AndroidView(
                factory = { ctx ->
                    val host = FrameLayout(ctx)
                    val tv = TextureView(ctx).apply {
                        isOpaque = true
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER
                        )
                    }
                    host.addView(tv)
                    tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        private var output: Surface? = null
                        override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                            if (w > 0 && h > 0) s.setDefaultBufferSize(w.coerceAtLeast(2), h.coerceAtLeast(2))
                            val surface = Surface(s)
                            output = surface
                            decoder.attachSurface(surface)
                            CastViewHolder.textureView = tv
                            applyFitTransform(tv, w, h, videoAspect, fitMode, mirrored)
                            PeerSessionHolder.session?.sendControl("need_key")
                        }
                        override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {
                            if (w > 0 && h > 0) s.setDefaultBufferSize(w.coerceAtLeast(2), h.coerceAtLeast(2))
                            applyFitTransform(tv, w, h, videoAspect, fitMode, mirrored)
                        }
                        override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean {
                            if (CastViewHolder.textureView === tv) {
                                CastViewHolder.textureView = null
                            }
                            decoder.detachSurface()
                            output?.release()
                            output = null
                            return true
                        }
                        override fun onSurfaceTextureUpdated(s: SurfaceTexture) = Unit
                    }
                    host.tag = tv
                    host
                },
                update = { host ->
                    val tv = host.tag as? TextureView ?: return@AndroidView
                    if (tv.isAvailable) {
                        applyFitTransform(tv, tv.width, tv.height, videoAspect, fitMode, mirrored)
                    }
                },
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
            )
            if (!receiving) {
                Text(
                    "等待画面",
                    color = Color(0xFFB0B8C4),
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

private fun applyFitTransform(
    textureView: TextureView,
    viewW: Int,
    viewH: Int,
    videoAspect: Float,
    mode: CastFitMode,
    mirrored: Boolean
) {
    if (viewW <= 0 || viewH <= 0) return
    val viewAspect = viewW.toFloat() / viewH.toFloat()
    val matrix = Matrix()
    when (mode) {
        CastFitMode.Fill -> {
            if (videoAspect > viewAspect) {
                val s = videoAspect / viewAspect
                matrix.setScale(s, 1f, viewW / 2f, viewH / 2f)
            } else {
                val s = viewAspect / videoAspect
                matrix.setScale(1f, s, viewW / 2f, viewH / 2f)
            }
        }
        CastFitMode.Fit -> {
            if (videoAspect > viewAspect) {
                val s = viewAspect / videoAspect
                matrix.setScale(1f, s, viewW / 2f, viewH / 2f)
            } else {
                val s = videoAspect / viewAspect
                matrix.setScale(s, 1f, viewW / 2f, viewH / 2f)
            }
        }
        CastFitMode.Original -> matrix.reset()
    }
    if (mirrored) {
        matrix.postScale(-1f, 1f, viewW / 2f, viewH / 2f)
    }
    textureView.setTransform(matrix)
}
