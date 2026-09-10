package io.github.rvbcrs.wallmode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class AmbientWeather(
    val temperatureC: Int,
    val weatherCode: Int,
    val isDay: Boolean,
    val location: String,
    val condition: String,
    val message: String
)

internal object AmbientWeatherClient {
    fun fetch(location: String): AmbientWeather {
        val geoUrl = Uri.parse("https://geocoding-api.open-meteo.com/v1/search")
            .buildUpon()
            .appendQueryParameter("name", location.trim())
            .appendQueryParameter("count", "1")
            .appendQueryParameter("language", Locale.getDefault().language)
            .appendQueryParameter("format", "json")
            .build()
            .toString()
        val place = getJson(geoUrl).optJSONArray("results")?.optJSONObject(0)
            ?: error("Location not found")

        val forecastUrl = Uri.parse("https://api.open-meteo.com/v1/forecast")
            .buildUpon()
            .appendQueryParameter("latitude", place.getDouble("latitude").toString())
            .appendQueryParameter("longitude", place.getDouble("longitude").toString())
            .appendQueryParameter("current", "temperature_2m,weather_code,is_day")
            .appendQueryParameter("timezone", "auto")
            .build()
            .toString()
        val current = getJson(forecastUrl).getJSONObject("current")
        val code = current.getInt("weather_code")

        return AmbientWeather(
            temperatureC = current.getDouble("temperature_2m").roundToInt(),
            weatherCode = code,
            isDay = current.optInt("is_day", 1) == 1,
            location = place.optString("name", location),
            condition = conditionFor(code),
            message = messageFor(code)
        )
    }

    internal fun conditionFor(code: Int): String = when (code) {
        0 -> "Clear"
        1, 2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Foggy"
        in 51..57 -> "Drizzle"
        in 61..67 -> "Rain"
        in 71..77 -> "Snow"
        in 80..82 -> "Rain showers"
        in 85..86 -> "Snow showers"
        in 95..99 -> "Thunderstorms"
        else -> "Current weather"
    }

    internal fun messageFor(code: Int): String = when (code) {
        0 -> "Clear skies. Enjoy the view."
        1, 2 -> "A little cloud, plenty of sky."
        3 -> "The clouds are keeping things soft."
        45, 48 -> "A hazy view outside."
        in 51..67, in 80..82 -> "Keep an umbrella close."
        in 71..77, in 85..86 -> "Snow is in the air."
        in 95..99 -> "Thunderstorms nearby."
        else -> "Weather for your location."
    }

    private fun getJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "WallMode")
        }
        return try {
            if (connection.responseCode !in 200..299) {
                error("Weather service returned HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally {
            connection.disconnect()
        }
    }
}

class AmbientScreensaverView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private enum class CustomMedia {
        NONE,
        IMAGE,
        VIDEO
    }

    private val customImages = Array(2) {
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = GONE
        }
    }
    private val customImageBitmaps = arrayOfNulls<Bitmap>(2)
    private var activeCustomImageIndex = 0
    private var customImageGeneration = 0
    private val customImage: ImageView
        get() = customImages[activeCustomImageIndex]
    private val customVideo = TextureView(context).apply {
        visibility = GONE
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                startCustomVideo(surface)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                updateCustomVideoTransform()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                stopCustomVideo()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }
    private val customScrim = View(context).apply {
        setBackgroundColor(Color.argb(88, 0, 0, 0))
        visibility = GONE
    }
    private val cloudVideo = TextureView(context).apply {
        visibility = GONE
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                startCloudVideo(surface)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                stopCloudVideo()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }
    private val content = AmbientScreensaverContentView(context)
    private var customMedia = CustomMedia.NONE
    private var customVideoUrl: String? = null
    private var customVideoWidth = 0
    private var customVideoHeight = 0
    private var customVideoPrepared = false
    private var customVideoGeneration = 0
    private var customPlayer: MediaPlayer? = null
    private var cloudsVisible = false
    private var cloudPlayer: MediaPlayer? = null
    private var lastWeather: AmbientWeather? = null
    var scene: AmbientScene = AmbientScene.AURORA_WEATHER
        set(value) {
            if (field == value) return
            field = value
            content.scene = value
            showClouds(value == AmbientScene.AURORA_WEATHER && hasCloudVideo(lastWeather?.weatherCode))
        }
    var photoClockMode: Boolean = false
        set(value) {
            field = value
            content.photoClockMode = value
        }

    init {
        setBackgroundColor(Color.rgb(2, 11, 17))
        val matchParent = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        customImages.forEach { addView(it, matchParent) }
        addView(customVideo, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(customScrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(cloudVideo)
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun useBuiltInBackground() {
        customMedia = CustomMedia.NONE
        customVideoUrl = null
        customVideoGeneration++
        stopCustomVideo()
        clearCustomImages()
        customVideo.visibility = GONE
        customScrim.visibility = GONE
        content.accentColor = AmbientPalette.forWeather(
            lastWeather?.weatherCode,
            lastWeather?.isDay ?: true
        )
        showClouds(scene == AmbientScene.AURORA_WEATHER && hasCloudVideo(lastWeather?.weatherCode))
    }

    fun showCustomImage(bitmap: Bitmap, animate: Boolean = true) {
        customMedia = CustomMedia.IMAGE
        customVideoUrl = null
        customVideoGeneration++
        stopCustomVideo()
        showClouds(false)
        customVideo.visibility = GONE
        crossfadeToCustomImage(bitmap, animate)
        content.accentColor = accentFromBitmap(bitmap)
        customScrim.visibility = VISIBLE
    }

    private fun crossfadeToCustomImage(bitmap: Bitmap, animate: Boolean) {
        val generation = ++customImageGeneration
        val outgoingIndex = activeCustomImageIndex
        val incomingIndex = if (customImageBitmaps[outgoingIndex] == null) {
            outgoingIndex
        } else {
            1 - outgoingIndex
        }
        val outgoing = customImages[outgoingIndex]
        val incoming = customImages[incomingIndex]

        customImages.forEach { it.animate().cancel() }
        customImageBitmaps[incomingIndex]?.takeIf { it !== bitmap }?.recycle()
        incoming.setImageBitmap(bitmap)
        customImageBitmaps[incomingIndex] = bitmap
        incoming.visibility = VISIBLE
        val shouldAnimate = animate && incomingIndex != outgoingIndex &&
            outgoing.visibility == VISIBLE && customImageBitmaps[outgoingIndex] != null
        incoming.alpha = if (shouldAnimate) 0f else 1f
        activeCustomImageIndex = incomingIndex

        if (!shouldAnimate) {
            if (incomingIndex != outgoingIndex) clearCustomImage(outgoingIndex, except = bitmap)
            return
        }

        incoming.animate().alpha(1f).setDuration(PHOTO_CROSSFADE_MS).start()
        outgoing.animate().alpha(0f).setDuration(PHOTO_CROSSFADE_MS).withEndAction {
            if (generation == customImageGeneration && activeCustomImageIndex == incomingIndex) {
                clearCustomImage(outgoingIndex, except = bitmap)
            }
        }.start()
    }

    private fun clearCustomImages() {
        customImageGeneration++
        customImages.indices.forEach { clearCustomImage(it) }
        activeCustomImageIndex = 0
    }

    private fun clearCustomImage(index: Int, except: Bitmap? = null) {
        customImages[index].animate().cancel()
        customImages[index].setImageDrawable(null)
        customImages[index].alpha = 1f
        customImages[index].visibility = GONE
        customImageBitmaps[index]?.takeIf { it !== except && !it.isRecycled }?.recycle()
        customImageBitmaps[index] = null
    }

    private fun accentFromBitmap(bitmap: Bitmap): Int {
        val pixels = IntArray(64)
        var index = 0
        for (row in 0 until 8) {
            val y = ((row + 0.5f) * bitmap.height / 8f).toInt().coerceIn(0, bitmap.height - 1)
            for (column in 0 until 8) {
                val x = ((column + 0.5f) * bitmap.width / 8f).toInt()
                    .coerceIn(0, bitmap.width - 1)
                pixels[index++] = bitmap.getPixel(x, y)
            }
        }
        return AmbientPalette.fromPixels(
            pixels,
            AmbientPalette.forWeather(lastWeather?.weatherCode, lastWeather?.isDay ?: true)
        )
    }

    fun showBundledImage() {
        customMedia = CustomMedia.IMAGE
        customVideoUrl = null
        customVideoGeneration++
        stopCustomVideo()
        showClouds(false)
        customVideo.visibility = GONE
        clearCustomImages()
        customImage.setImageResource(R.drawable.weather_cloud_layer)
        customImage.alpha = 1f
        customImage.visibility = VISIBLE
        customScrim.visibility = VISIBLE
    }

    fun showCustomVideo(url: String) {
        val candidate = url.trim()
        if (!KioskPreferences.isValidAmbientMediaUrl(candidate)) {
            useBuiltInBackground()
            return
        }
        showVideo(candidate)
    }

    fun showBundledVideo() {
        showVideo("android.resource://${context.packageName}/${R.raw.weather_clouds}")
    }

    private fun showVideo(source: String) {
        customMedia = CustomMedia.VIDEO
        customVideoUrl = source
        customVideoGeneration++
        stopCustomVideo()
        showClouds(false)
        clearCustomImages()
        customVideo.visibility = VISIBLE
        customScrim.visibility = VISIBLE
        customVideo.surfaceTexture?.let(::startCustomVideo)
    }

    fun releaseCustomMedia() {
        useBuiltInBackground()
    }

    fun showWeatherLoading(location: String) {
        lastWeather = null
        showClouds(false)
        content.showWeatherLoading(location)
    }

    internal fun showWeather(weather: AmbientWeather) {
        lastWeather = weather
        showClouds(scene == AmbientScene.AURORA_WEATHER && hasCloudVideo(weather.weatherCode))
        if (!photoClockMode || customMedia != CustomMedia.IMAGE) {
            content.accentColor = AmbientPalette.forWeather(weather.weatherCode, weather.isDay)
        }
        content.showWeather(weather)
    }

    fun showWeatherError(locationMissing: Boolean) {
        lastWeather = null
        showClouds(false)
        content.showWeatherError(locationMissing)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val padding = (min(width, height) * 0.04f).roundToInt()
        val gap = (padding * 0.65f).roundToInt()
        val top = (height * 0.57f).roundToInt() + gap
        cloudVideo.layoutParams = LayoutParams(width - padding * 2, height - top - padding).apply {
            leftMargin = padding
            topMargin = top
        }
        updateCustomVideoTransform()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && cloudsVisible) cloudPlayer?.start() else cloudPlayer?.pause()
        if (customVideoPrepared) {
            customPlayer?.runCatching {
                if (visibility == VISIBLE && customMedia == CustomMedia.VIDEO) start() else pause()
            }
        }
    }

    override fun onDetachedFromWindow() {
        stopCloudVideo()
        stopCustomVideo()
        super.onDetachedFromWindow()
    }

    internal fun drawPreview(canvas: Canvas) {
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(Color.rgb(2, 11, 17))
        when (customMedia) {
            CustomMedia.IMAGE -> customImages.forEach { image ->
                if (image.visibility == VISIBLE) image.draw(canvas)
            }
            CustomMedia.VIDEO -> drawTexturePreview(canvas, customVideo, 0f, 0f)
            CustomMedia.NONE -> Unit
        }
        if (customMedia != CustomMedia.NONE) {
            canvas.drawColor(Color.argb(88, 0, 0, 0))
        } else if (cloudsVisible && cloudVideo.isAvailable) {
            cloudVideo.bitmap?.let { frame ->
                canvas.save()
                canvas.translate(cloudVideo.left.toFloat(), cloudVideo.top.toFloat())
                canvas.scale(
                    cloudVideo.width.toFloat() / frame.width,
                    cloudVideo.height.toFloat() / frame.height
                )
                canvas.drawBitmap(frame, 0f, 0f, null)
                canvas.restore()
                frame.recycle()
            }
        }
        content.draw(canvas)
        canvas.restore()
    }

    private fun showClouds(show: Boolean) {
        val visible = show && customMedia == CustomMedia.NONE
        cloudsVisible = visible
        cloudVideo.visibility = if (visible) VISIBLE else GONE
        content.cloudVideoActive = visible
        if (!visible && customMedia != CustomMedia.NONE) stopCloudVideo()
        if (visible && cloudVideo.isAvailable && cloudPlayer == null) {
            startCloudVideo(cloudVideo.surfaceTexture!!)
        }
        if (visible && visibility == VISIBLE) cloudPlayer?.start() else cloudPlayer?.pause()
    }

    private fun startCloudVideo(surfaceTexture: SurfaceTexture) {
        stopCloudVideo()
        val surface = Surface(surfaceTexture)
        cloudPlayer = runCatching {
            MediaPlayer.create(context, R.raw.weather_clouds)?.apply {
                setSurface(surface)
                isLooping = true
                setVolume(0f, 0f)
                if (cloudsVisible && this@AmbientScreensaverView.visibility == VISIBLE) start()
            }
        }.getOrNull()
        surface.release()
    }

    private fun stopCloudVideo() {
        cloudPlayer?.release()
        cloudPlayer = null
    }

    private fun startCustomVideo(surfaceTexture: SurfaceTexture) {
        val url = customVideoUrl ?: return
        if (customMedia != CustomMedia.VIDEO) return
        stopCustomVideo()
        val generation = customVideoGeneration
        val surface = Surface(surfaceTexture)
        val player = MediaPlayer()
        customVideoPrepared = false
        customPlayer = player
        runCatching {
            player.setSurface(surface)
            if (url.startsWith("android.resource://")) {
                player.setDataSource(context, Uri.parse(url))
            } else {
                player.setDataSource(url)
            }
            player.isLooping = true
            player.setVolume(0f, 0f)
            player.setOnVideoSizeChangedListener { current, videoWidth, videoHeight ->
                if (customPlayer === current) {
                    customVideoWidth = videoWidth
                    customVideoHeight = videoHeight
                    updateCustomVideoTransform()
                }
            }
            player.setOnPreparedListener { current ->
                if (generation != customVideoGeneration || customPlayer !== current ||
                    customMedia != CustomMedia.VIDEO
                ) {
                    current.release()
                    return@setOnPreparedListener
                }
                customVideoWidth = current.videoWidth
                customVideoHeight = current.videoHeight
                customVideoPrepared = true
                updateCustomVideoTransform()
                if (visibility == VISIBLE) current.start()
            }
            player.setOnErrorListener { current, _, _ ->
                if (customPlayer === current) fallbackFromCustomVideo()
                true
            }
            player.prepareAsync()
        }.onFailure {
            if (customPlayer === player) fallbackFromCustomVideo() else player.release()
        }
        surface.release()
    }

    private fun stopCustomVideo() {
        customPlayer?.runCatching { release() }
        customPlayer = null
        customVideoPrepared = false
        customVideoWidth = 0
        customVideoHeight = 0
    }

    private fun fallbackFromCustomVideo() {
        stopCustomVideo()
        customMedia = CustomMedia.NONE
        customVideoUrl = null
        customVideo.visibility = GONE
        customScrim.visibility = GONE
        showClouds(scene == AmbientScene.AURORA_WEATHER && hasCloudVideo(lastWeather?.weatherCode))
    }

    private fun updateCustomVideoTransform() {
        val (scaleX, scaleY) = centerCropScale(
            customVideo.width,
            customVideo.height,
            customVideoWidth,
            customVideoHeight
        )
        customVideo.setTransform(Matrix().apply {
            setScale(scaleX, scaleY, customVideo.width / 2f, customVideo.height / 2f)
        })
    }

    private fun drawTexturePreview(canvas: Canvas, textureView: TextureView, left: Float, top: Float) {
        if (!textureView.isAvailable) return
        textureView.bitmap?.let { frame ->
            canvas.save()
            canvas.translate(left, top)
            canvas.scale(
                textureView.width.toFloat() / frame.width,
                textureView.height.toFloat() / frame.height
            )
            canvas.drawBitmap(frame, 0f, 0f, null)
            canvas.restore()
            frame.recycle()
        }
    }

}

internal fun centerCropScale(
    viewWidth: Int,
    viewHeight: Int,
    mediaWidth: Int,
    mediaHeight: Int
): Pair<Float, Float> {
    if (viewWidth <= 0 || viewHeight <= 0 || mediaWidth <= 0 || mediaHeight <= 0) return 1f to 1f
    val viewRatio = viewWidth.toFloat() / viewHeight
    val mediaRatio = mediaWidth.toFloat() / mediaHeight
    return if (mediaRatio > viewRatio) mediaRatio / viewRatio to 1f
    else 1f to viewRatio / mediaRatio
}

private fun hasCloudVideo(code: Int?): Boolean = code != null && code != 0 && code !in 45..48

private const val CLOCK_SECOND_DOT_COUNT = 60
private const val CLOCK_SECOND_DOT_FRAME_MS = 50L
private const val PHOTO_CROSSFADE_MS = 1_200L

internal fun secondDotFlashIntensity(nanoOfSecond: Int): Float {
    val phase = nanoOfSecond.coerceIn(0, 999_999_999) / 1_000_000_000.0
    return ((1.0 - cos(phase * Math.PI * 2.0)) * 0.5).toFloat()
}

internal fun fillRoundedRectClockDots(
    out: FloatArray,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    radius: Float
) {
    require(out.size >= CLOCK_SECOND_DOT_COUNT * 2)
    val width = right - left
    val height = bottom - top
    require(width > 0f && height > 0f)

    val cornerRadius = radius.coerceIn(0f, min(width, height) / 2f)
    val horizontal = width - cornerRadius * 2f
    val vertical = height - cornerRadius * 2f
    val topHalf = horizontal / 2f
    val arc = (Math.PI * cornerRadius / 2.0).toFloat()
    val afterTopRight = topHalf + arc
    val afterRight = afterTopRight + vertical
    val afterBottomRight = afterRight + arc
    val afterBottom = afterBottomRight + horizontal
    val afterBottomLeft = afterBottom + arc
    val afterLeft = afterBottomLeft + vertical
    val afterTopLeft = afterLeft + arc
    val perimeter = afterTopLeft + topHalf

    repeat(CLOCK_SECOND_DOT_COUNT) { index ->
        val distance = perimeter * index / CLOCK_SECOND_DOT_COUNT
        val (x, y) = when {
            distance < topHalf -> left + width / 2f + distance to top
            distance < afterTopRight -> {
                val angle = -Math.PI / 2.0 + (distance - topHalf) / cornerRadius
                right - cornerRadius + cornerRadius * cos(angle).toFloat() to
                    top + cornerRadius + cornerRadius * sin(angle).toFloat()
            }
            distance < afterRight -> right to top + cornerRadius + distance - afterTopRight
            distance < afterBottomRight -> {
                val angle = (distance - afterRight) / cornerRadius
                right - cornerRadius + cornerRadius * cos(angle) to
                    bottom - cornerRadius + cornerRadius * sin(angle)
            }
            distance < afterBottom -> right - cornerRadius - (distance - afterBottomRight) to bottom
            distance < afterBottomLeft -> {
                val angle = Math.PI / 2.0 + (distance - afterBottom) / cornerRadius
                left + cornerRadius + cornerRadius * cos(angle).toFloat() to
                    bottom - cornerRadius + cornerRadius * sin(angle).toFloat()
            }
            distance < afterLeft -> left to bottom - cornerRadius - (distance - afterBottomLeft)
            distance < afterTopLeft -> {
                val angle = Math.PI + (distance - afterLeft) / cornerRadius
                left + cornerRadius + cornerRadius * cos(angle).toFloat() to
                    top + cornerRadius + cornerRadius * sin(angle).toFloat()
            }
            else -> left + cornerRadius + distance - afterTopLeft to top
        }
        out[index * 2] = x
        out[index * 2 + 1] = y
    }
}

private class AmbientScreensaverContentView(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val card = RectF()
    private val cardClip = Path()
    private val secondDotBounds = RectF()
    private val secondDotPositions = FloatArray(CLOCK_SECOND_DOT_COUNT * 2)
    private var secondDotTrackRadius = -1f
    private val clockTypeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val strongTypeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val regularTypeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val secondsFormatter = DateTimeFormatter.ofPattern(":ss")
    private var weather: AmbientWeather? = null
    private var weatherStatus = context.getString(R.string.screensaver_weather_loading)
    private var requestedLocation = ""
    private var lastAccessibilityMinute = -1
    var accentColor: Int = AmbientPalette.forWeather(null, isDay = true)
        set(value) {
            field = value
            invalidate()
        }
    var photoClockMode: Boolean = false
        set(value) {
            field = value
            lastAccessibilityMinute = -1
            invalidate()
        }
    var scene: AmbientScene = AmbientScene.AURORA_WEATHER
        set(value) {
            field = value
            lastAccessibilityMinute = -1
            invalidate()
        }
    var cloudVideoActive = false
        set(value) {
            field = value
            invalidate()
        }

    fun showWeatherLoading(location: String) {
        requestedLocation = location
        weatherStatus = context.getString(R.string.screensaver_weather_loading)
        lastAccessibilityMinute = -1
        invalidate()
    }

    internal fun showWeather(weather: AmbientWeather) {
        this.weather = weather
        requestedLocation = weather.location
        weatherStatus = ""
        lastAccessibilityMinute = -1
        invalidate()
    }

    fun showWeatherError(locationMissing: Boolean) {
        weather = null
        weatherStatus = context.getString(
            if (locationMissing) {
                R.string.screensaver_weather_location_needed
            } else {
                R.string.screensaver_weather_unavailable
            }
        )
        lastAccessibilityMinute = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return

        val now = ZonedDateTime.now()
        updateAccessibilityDescription(now)
        val padding = min(width, height) * 0.04f
        if (photoClockMode) {
            val photoCardWidth = width * 0.46f
            val photoCardHeight = height * 0.30f
            card.set(
                padding,
                height - padding - photoCardHeight,
                padding + photoCardWidth,
                height - padding
            )
            drawClockCard(canvas, card, now)
        } else if (scene == AmbientScene.GLOW_CLOCK) {
            card.set(padding, padding, width - padding, height - padding)
            drawClockCard(canvas, card, now)
        } else {
            val gap = padding * 0.65f
            val clockBottom = height * 0.57f
            card.set(padding, padding, width - padding, clockBottom)
            drawClockCard(canvas, card, now)
            card.set(padding, clockBottom + gap, width - padding, height - padding)
            drawWeatherCard(canvas, card)
        }

        if (visibility == VISIBLE) {
            if (photoClockMode || scene == AmbientScene.GLOW_CLOCK ||
                cloudVideoActive && weather?.weatherCode == 3
            ) {
                postInvalidateDelayed(CLOCK_SECOND_DOT_FRAME_MS)
            } else {
                postInvalidateOnAnimation()
            }
        }
    }

    private fun drawOrb(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
        color: Int,
        maxAlpha: Int
    ) {
        paint.style = Paint.Style.FILL
        paint.color = color
        for (layer in 5 downTo 1) {
            paint.alpha = maxAlpha * layer / 5
            canvas.drawCircle(centerX, centerY, radius * layer / 5f, paint)
        }
        paint.alpha = 255
    }

    private fun drawClockCard(canvas: Canvas, rect: RectF, now: ZonedDateTime) {
        drawGlassCard(canvas, rect)
        drawClockBackdrop(canvas, rect)
        drawSecondDots(canvas, rect, now.second, now.nano)
        val mainTime = now.format(timeFormatter)
        val seconds = now.format(secondsFormatter)
        val mainSize = min(rect.width() / 5.05f, rect.height() * 0.36f)
        val secondsSize = mainSize * 0.34f

        paint.typeface = clockTypeface
        paint.textSize = mainSize
        val mainWidth = paint.measureText(mainTime)
        paint.textSize = secondsSize
        val secondsWidth = paint.measureText(seconds)
        val secondsGap = -secondsSize * 0.08f
        val startX = rect.centerX() - (mainWidth + secondsGap + secondsWidth) / 2f
        val baseline = rect.centerY() - rect.height() * 0.03f

        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.textSize = mainSize
        paint.typeface = clockTypeface
        paint.color = Color.WHITE
        paint.setShadowLayer(mainSize * 0.32f, 0f, 0f, accentColor)
        canvas.drawText(mainTime, startX, baseline, paint)
        paint.clearShadowLayer()
        paint.textSize = secondsSize
        paint.color = accentColor
        paint.setShadowLayer(secondsSize * 0.7f, 0f, 0f, accentColor)
        canvas.drawText(seconds, startX + mainWidth + secondsGap, baseline, paint)
        paint.clearShadowLayer()

        val locale = Locale.getDefault()
        val day = now.format(DateTimeFormatter.ofPattern("EEEE", locale)).uppercase(locale)
        drawCenteredText(
            canvas,
            day,
            rect.centerX(),
            rect.centerY() + rect.height() * 0.21f,
            min(rect.width() * 0.052f, rect.height() * 0.075f),
            Color.rgb(214, 216, 216),
            strongTypeface
        )
        drawCenteredText(
            canvas,
            now.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", locale)),
            rect.centerX(),
            rect.centerY() + rect.height() * 0.32f,
            min(rect.width() * 0.045f, rect.height() * 0.064f),
            Color.rgb(176, 181, 181),
            regularTypeface
        )

    }

    private fun drawSecondDots(canvas: Canvas, rect: RectF, second: Int, nanoOfSecond: Int) {
        val minDimension = min(rect.width(), rect.height())
        val inset = minDimension * 0.027f
        val left = rect.left + inset
        val top = rect.top + inset
        val right = rect.right - inset
        val bottom = rect.bottom - inset
        val trackRadius = (minDimension * 0.065f - inset).coerceAtLeast(0f)
        if (secondDotTrackRadius != trackRadius ||
            secondDotBounds.left != left || secondDotBounds.top != top ||
            secondDotBounds.right != right || secondDotBounds.bottom != bottom
        ) {
            secondDotBounds.set(left, top, right, bottom)
            secondDotTrackRadius = trackRadius
            fillRoundedRectClockDots(
                secondDotPositions,
                left,
                top,
                right,
                bottom,
                trackRadius
            )
        }

        paint.style = Paint.Style.FILL
        paint.clearShadowLayer()
        paint.alpha = 255
        val dotRadius = maxOf(1.5f, minDimension * 0.0055f)
        repeat(CLOCK_SECOND_DOT_COUNT) { index ->
            paint.color = Color.argb(48, 117, 151, 166)
            canvas.drawCircle(
                secondDotPositions[index * 2],
                secondDotPositions[index * 2 + 1],
                dotRadius * 0.78f,
                paint
            )
        }

        val activeX = secondDotPositions[second.coerceIn(0, 59) * 2]
        val activeY = secondDotPositions[second.coerceIn(0, 59) * 2 + 1]
        val flash = 0.18f + secondDotFlashIntensity(nanoOfSecond) * 0.82f
        paint.color = withAlpha(accentColor, (28 * flash).roundToInt())
        canvas.drawCircle(activeX, activeY, dotRadius * 3.6f, paint)
        paint.color = withAlpha(accentColor, (86 * flash).roundToInt())
        canvas.drawCircle(activeX, activeY, dotRadius * 2.15f, paint)
        paint.color = withAlpha(accentColor, (255 * flash).roundToInt())
        canvas.drawCircle(activeX, activeY, dotRadius * 1.22f, paint)
        paint.alpha = 255
    }

    private fun drawClockBackdrop(canvas: Canvas, rect: RectF) {
        val radius = min(rect.width() * 0.36f, rect.height() * 0.9f)
        val centerY = rect.centerY() - rect.height() * 0.08f
        val cornerRadius = min(rect.width(), rect.height()) * 0.065f
        cardClip.reset()
        cardClip.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(cardClip)
        paint.alpha = 255
        paint.shader = RadialGradient(
            rect.centerX(),
            centerY,
            radius,
            intArrayOf(
                withAlpha(accentColor, 115),
                withAlpha(accentColor, 48),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(rect.centerX(), centerY, radius, paint)
        paint.shader = null
        canvas.restore()
    }

    private fun drawWeatherCard(canvas: Canvas, rect: RectF) {
        if (cloudVideoActive) drawVideoCornerMask(canvas, rect)
        drawGlassCard(canvas, rect, if (cloudVideoActive) 48 else 218)
        val accent = AmbientPalette.forWeather(weather?.weatherCode, weather?.isDay ?: true)
        drawWeatherBackdrop(canvas, rect, weather?.weatherCode, weather?.isDay ?: true)
        val left = rect.left + rect.width() * 0.09f
        val current = weather
        val temperature = current?.let { "${it.temperatureC}°" } ?: "--°"
        drawText(
            canvas,
            temperature,
            left,
            rect.top + rect.height() * 0.34f,
            min(rect.width() * 0.27f, rect.height() * 0.25f),
            Color.WHITE,
            strongTypeface
        )
        drawText(
            canvas,
            current?.condition ?: weatherStatus,
            left,
            rect.top + rect.height() * 0.55f,
            min(rect.width() * 0.075f, rect.height() * 0.075f),
            Color.rgb(235, 241, 245),
            strongTypeface
        )
        val detail = current?.message ?: requestedLocation
        if (detail.isNotBlank()) {
            drawText(
                canvas,
                detail,
                left,
                rect.top + rect.height() * 0.66f,
                min(rect.width() * 0.046f, rect.height() * 0.052f),
                Color.rgb(190, 202, 210),
                regularTypeface
            )
        }
        current?.location?.let {
            drawText(
                canvas,
                it,
                left,
                rect.bottom - rect.height() * 0.15f,
                min(rect.width() * 0.045f, rect.height() * 0.05f),
                accent,
                strongTypeface
            )
        }
        drawText(
            canvas,
            context.getString(R.string.screensaver_weather_attribution),
            left,
            rect.bottom - rect.height() * 0.06f,
            min(rect.width() * 0.031f, rect.height() * 0.032f),
            Color.rgb(126, 145, 156),
            regularTypeface
        )
    }

    private fun drawWeatherBackdrop(canvas: Canvas, rect: RectF, code: Int?, isDay: Boolean) {
        canvas.save()
        canvas.clipRect(rect)

        when (code) {
            0 -> drawSunOrMoon(canvas, rect, isDay)
            1, 2 -> drawSunOrMoon(canvas, rect, isDay)
            45, 48 -> drawFog(canvas, rect)
            in 51..67, in 80..82 -> drawRain(canvas, rect)
            in 71..77, in 85..86 -> drawSnow(canvas, rect)
            in 95..99 -> {
                drawRain(canvas, rect)
                drawLightning(canvas, rect)
            }
        }
        canvas.restore()
    }

    private fun drawSunOrMoon(canvas: Canvas, rect: RectF, isDay: Boolean) {
        val pulse = 1f + sin(SystemClock.elapsedRealtime() / 850.0).toFloat() * 0.04f
        val color = if (isDay) Color.rgb(255, 158, 42) else Color.rgb(198, 220, 242)
        drawOrb(
            canvas,
            rect.right - rect.width() * 0.18f,
            rect.top + rect.height() * 0.28f,
            min(rect.width(), rect.height()) * 0.27f * pulse,
            color,
            if (isDay) 34 else 24
        )
    }

    private fun drawRain(canvas: Canvas, rect: RectF) {
        val phase = SystemClock.elapsedRealtime() / 1_100f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.argb(105, 104, 202, 255)
        repeat(18) { index ->
            val x = rect.left + rect.width() * ((index * 0.173f) % 1f)
            val progress = (phase + index * 0.113f) % 1f
            val y = rect.top + rect.height() * progress
            canvas.drawLine(x, y, x - rect.width() * 0.018f, y + rect.height() * 0.08f, paint)
        }
        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL
    }

    private fun drawSnow(canvas: Canvas, rect: RectF) {
        val phase = SystemClock.elapsedRealtime() / 4_500f
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(145, 229, 244, 255)
        repeat(20) { index ->
            val progress = (phase + index * 0.071f) % 1f
            val sway = sin((phase * 6f + index).toDouble()).toFloat() * rect.width() * 0.018f
            val x = rect.left + rect.width() * ((index * 0.137f) % 1f) + sway
            val y = rect.top + rect.height() * progress
            canvas.drawCircle(x, y, 1.8f + index % 3, paint)
        }
    }

    private fun drawFog(canvas: Canvas, rect: RectF) {
        val phase = SystemClock.elapsedRealtime() / 8_000f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(3f, rect.height() * 0.018f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.argb(55, 198, 218, 225)
        repeat(5) { index ->
            val offset = sin((phase + index * 0.8f).toDouble()).toFloat() * rect.width() * 0.04f
            val y = rect.top + rect.height() * (0.18f + index * 0.16f)
            canvas.drawLine(rect.left + rect.width() * 0.42f + offset, y, rect.right - rect.width() * 0.06f + offset, y, paint)
        }
        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL
    }

    private fun drawLightning(canvas: Canvas, rect: RectF) {
        val flash = (SystemClock.elapsedRealtime() % 7_000L) / 7_000f
        if (flash > 0.07f) return
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = Color.argb(((1f - flash / 0.07f) * 180).roundToInt(), 225, 214, 255)
        val x = rect.right - rect.width() * 0.22f
        val y = rect.top + rect.height() * 0.22f
        canvas.drawLine(x, y, x - rect.width() * 0.045f, y + rect.height() * 0.18f, paint)
        canvas.drawLine(x - rect.width() * 0.045f, y + rect.height() * 0.18f, x, y + rect.height() * 0.17f, paint)
        canvas.drawLine(x, y + rect.height() * 0.17f, x - rect.width() * 0.07f, y + rect.height() * 0.38f, paint)
        paint.strokeJoin = Paint.Join.MITER
        paint.style = Paint.Style.FILL
    }

    private fun drawVideoCornerMask(canvas: Canvas, rect: RectF) {
        val radius = min(rect.width(), rect.height()) * 0.065f
        cardClip.reset()
        cardClip.fillType = Path.FillType.EVEN_ODD
        cardClip.addRect(rect, Path.Direction.CW)
        cardClip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(2, 11, 17)
        paint.alpha = 255
        canvas.drawPath(cardClip, paint)
        cardClip.fillType = Path.FillType.WINDING
    }

    private fun drawGlassCard(canvas: Canvas, rect: RectF, fillAlpha: Int = 218) {
        val radius = min(rect.width(), rect.height()) * 0.065f
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(fillAlpha, 18, 31, 37)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.argb(105, 117, 151, 166)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        baseline: Float,
        textSize: Float,
        color: Int,
        typeface: Typeface
    ) {
        paint.textSize = textSize
        paint.typeface = typeface
        drawText(canvas, text, centerX - paint.measureText(text) / 2f, baseline, textSize, color, typeface)
    }

    private fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        textSize: Float,
        color: Int,
        typeface: Typeface
    ) {
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.textSize = textSize
        paint.typeface = typeface
        paint.color = color
        canvas.drawText(text, x, baseline, paint)
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun updateAccessibilityDescription(now: ZonedDateTime) {
        val minute = now.dayOfYear * 1_440 + now.hour * 60 + now.minute
        if (minute == lastAccessibilityMinute) return
        lastAccessibilityMinute = minute
        val locale = Locale.getDefault()
        val date = now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale))
        val weatherText = weather?.takeIf { scene == AmbientScene.AURORA_WEATHER }?.let {
            "${it.temperatureC} degrees, ${it.condition}, ${it.location}"
        } ?: weatherStatus.takeIf { scene == AmbientScene.AURORA_WEATHER }
        contentDescription = listOfNotNull("${now.format(timeFormatter)}, $date", weatherText)
            .joinToString(". ")
    }
}
