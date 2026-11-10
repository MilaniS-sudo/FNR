package za.ac.richfield.fnr.data.remote

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The AI Gateway's two endpoints (see fnr-gateway/main.py). */
interface GatewayApi {
    @GET("health")
    suspend fun health(): HealthResponse

    @Multipart
    @POST("v1/process")
    suspend fun process(
        @Part audio: MultipartBody.Part,
        @Part("language") language: RequestBody,
        @Part("duration_ms") durationMs: RequestBody,
    ): ProcessResponse
}

data class HealthResponse(
    val status: String?,
    val stt: String?,
    val segmenter: String?,
)

data class SegmentDto(
    @SerializedName("start_ms") val startMs: Long,
    @SerializedName("end_ms") val endMs: Long,
    val text: String?,
)

data class KeyPointDto(
    val title: String?,
    val summary: String?,
    val concept: String?,
    @SerializedName("start_ms") val startMs: Long?,
    @SerializedName("end_ms") val endMs: Long?,
)

data class ProcessResponse(
    val text: String?,
    val language: String?,
    val segments: List<SegmentDto>?,
    @SerializedName("key_points") val keyPoints: List<KeyPointDto>?,
    val models: String?,
    @SerializedName("processing_ms") val processingMs: Long?,
)

private data class ErrorBody(val detail: String?)

object GatewayClient {
    fun create(baseUrl: String, token: String, deviceId: String): GatewayApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("X-FNR-Token", token)
                        .header("X-FNR-Device", deviceId)
                        .build()
                )
            }
            .build()
        return Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GatewayApi::class.java)
    }

    /** Turns any network failure into a sentence a student can act on. */
    fun friendlyError(error: Throwable): String = when (error) {
        is HttpException -> {
            val detail = runCatching {
                Gson().fromJson(error.response()?.errorBody()?.string(), ErrorBody::class.java)?.detail
            }.getOrNull()
            when (error.code()) {
                401 -> "The app token does not match the Gateway. Check FNR_APP_TOKEN."
                429 -> "Too many requests. Wait a while and try again."
                else -> detail ?: "The AI Gateway returned an error (${error.code()})."
            }
        }
        is java.net.SocketTimeoutException -> "The AI Gateway took too long to answer. Try again."
        is IOException -> "Could not reach the AI Gateway. Check your internet connection and the Gateway address in Profile."
        else -> error.message ?: "Something went wrong."
    }
}

/** Wraps a request body and reports when the whole file has been sent (for the progress steps). */
class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onUploaded: () -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        var written = 0L
        var reported = false
        val counting = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                written += byteCount
                if (!reported && total > 0 && written >= total) {
                    reported = true
                    onUploaded()
                }
            }
        }.buffer()
        delegate.writeTo(counting)
        counting.flush()
    }
}
