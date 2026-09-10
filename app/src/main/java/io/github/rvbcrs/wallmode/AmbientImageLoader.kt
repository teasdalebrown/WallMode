package io.github.rvbcrs.wallmode

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

internal object AmbientImageLoader {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_DIMENSION = 1280
    private const val MAX_REDIRECTS = 5

    fun load(url: String): Result<Bitmap> = runCatching {
        var currentUrl = url.trim()
        require(KioskPreferences.isValidAmbientMediaUrl(currentUrl)) { "Invalid image URL" }

        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 8_000
                readTimeout = 12_000
                setRequestProperty("Accept", "image/*")
                setRequestProperty("User-Agent", "WallMode")
            }
            try {
                val responseCode = connection.responseCode
                if (responseCode == 301 || responseCode == 302 || responseCode == 303 ||
                    responseCode == 307 || responseCode == 308
                ) {
                    check(redirectCount < MAX_REDIRECTS) { "Too many redirects" }
                    val location = connection.getHeaderField("Location")
                        ?: error("Redirect has no Location header")
                    currentUrl = URL(URL(currentUrl), location).toString()
                    require(KioskPreferences.isValidAmbientMediaUrl(currentUrl)) {
                        "Redirected to an invalid image URL"
                    }
                    return@repeat
                }
                check(responseCode in 200..299) { "Image returned HTTP $responseCode" }
                check(connection.contentType?.substringBefore(';')?.trim()
                    ?.startsWith("image/", ignoreCase = true) == true
                ) { "URL did not return an image" }
                val contentLength = connection.contentLengthLong
                check(contentLength < 0 || contentLength <= MAX_BYTES.toLong()) {
                    "Image exceeds 8 MiB"
                }

                val encoded = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream(
                        contentLength.takeIf { it in 1L..MAX_BYTES.toLong() }?.toInt() ?: 32 * 1024
                    )
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        check(total <= MAX_BYTES) { "Image exceeds 8 MiB" }
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
                check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Image could not be decoded" }
                return@runCatching BitmapFactory.decodeByteArray(
                    encoded,
                    0,
                    encoded.size,
                    BitmapFactory.Options().apply {
                        inSampleSize = ambientImageSampleSize(
                            bounds.outWidth,
                            bounds.outHeight,
                            MAX_DIMENSION
                        )
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                ) ?: error("Image could not be decoded")
            } finally {
                connection.disconnect()
            }
        }
        error("Too many redirects")
    }
}

internal fun ambientImageSampleSize(width: Int, height: Int, maxDimension: Int = 1280): Int {
    require(width > 0 && height > 0 && maxDimension > 0)
    var sampleSize = 1
    while (maxOf(width, height).toLong() > maxDimension.toLong() * sampleSize) sampleSize *= 2
    return sampleSize
}
