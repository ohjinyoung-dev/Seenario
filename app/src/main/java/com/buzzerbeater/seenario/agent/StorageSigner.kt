package com.buzzerbeater.seenario.agent

import com.buzzerbeater.seenario.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Supabase Storage의 signed URL을 만든다(배포 전 테스트용, [FakeAgentClient]에서만 쓴다).
 * Storage 플러그인 없이 REST(`POST /storage/v1/object/sign/{bucket}/{path}`)를 로그인한 사용자 토큰으로 부른다.
 */
class StorageSigner(private val supabase: SupabaseClient) {

    /** [bucket] 안의 [path]에 대해 [expiresInSec]초 동안 쓸 수 있는 전체 URL. */
    suspend fun sign(bucket: String, path: String, expiresInSec: Int): String = withContext(Dispatchers.IO) {
        val token = supabase.auth.currentAccessTokenOrNull() ?: throw IOException("로그인 토큰 없음")
        val base = BuildConfig.SUPABASE_URL.trimEnd('/')
        val encodedPath = path.split('/').joinToString("/") { encodeSegment(it) }
        val connection = (URL("$base/storage/v1/object/sign/${encodeSegment(bucket)}/$encodedPath")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { it.write("""{"expiresIn":$expiresInSec}""".toByteArray()) }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("signed URL 만들기 실패: HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val signed = Json.parseToJsonElement(body).jsonObject["signedURL"]?.jsonPrimitive?.content
                ?: throw IOException("signed URL 응답에 signedURL 없음")
            // 응답은 "/object/sign/..." 형태의 상대 경로다.
            "$base/storage/v1/${signed.trimStart('/')}"
        } finally {
            connection.disconnect()
        }
    }

    private fun encodeSegment(segment: String): String = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private companion object {
        const val TIMEOUT_MS = 10_000
    }
}
