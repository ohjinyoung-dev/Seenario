package com.buzzerbeater.seenario.sound

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** 재생할 음원 파일을 구해 준다. */
interface SoundSource {
    /** 기기에 이미 저장돼 있는지(빠르게 확인). */
    fun hasCached(soundId: String): Boolean

    /** 저장된 파일이 있으면 그 파일, 없으면 [url]에서 받아 저장한 파일. url이 없거나 받지 못하면 null. */
    suspend fun fileFor(soundId: String, url: String?): File?
}

/**
 * 음원을 앱 내부 저장소(`files/sounds/<sound_id>`)에 받아 두고 다음부터 재사용한다.
 * 로그에는 sound_id, 크기, 결과만 남기고 URL은 남기지 않는다.
 */
class SoundCache(context: Context) : SoundSource {
    private val dir = File(context.applicationContext.filesDir, DIR_NAME)

    override fun hasCached(soundId: String): Boolean = fileOf(soundId).let { it.exists() && it.length() > 0 }

    override suspend fun fileFor(soundId: String, url: String?): File? = withContext(Dispatchers.IO) {
        val file = fileOf(soundId)
        if (file.exists() && file.length() > 0) {
            SoundLog.d("다운로드 생략(저장된 파일 사용) id=$soundId, ${file.length() / 1024}KB")
            return@withContext file
        }
        if (url.isNullOrBlank()) {
            SoundLog.d("재생 안 함: url 없음, 저장된 파일도 없음 id=$soundId")
            return@withContext null
        }
        download(soundId, url, file)
    }

    private suspend fun download(soundId: String, url: String, file: File): File? {
        dir.mkdirs()
        val part = File(dir, "${file.name}.part")
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                SoundLog.d("다운로드 실패 id=$soundId, HTTP $code")
                return null
            }
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (part.length() == 0L || !part.renameTo(file)) {
                SoundLog.d("다운로드 실패 id=$soundId, 파일 저장 실패")
                return null
            }
            SoundLog.d("다운로드 성공 id=$soundId, ${file.length() / 1024}KB")
            return file
        } catch (e: IOException) {
            // 예외 메시지에 URL이 들어갈 수 있어서 종류만 남긴다.
            SoundLog.d("다운로드 실패 id=$soundId, ${e.javaClass.simpleName}")
            return null
        } finally {
            connection?.disconnect()
            if (part.exists()) part.delete()
        }
    }

    private fun fileOf(soundId: String) = File(dir, safeName(soundId))

    companion object {
        private const val DIR_NAME = "sounds"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUFFER_SIZE = 16 * 1024

        /** sound_id를 파일 이름으로 쓸 수 있게 바꾼다(경로 문자, 점 등 제거. 예: ".." → "__"). */
        fun safeName(soundId: String): String = soundId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(100)
    }
}
