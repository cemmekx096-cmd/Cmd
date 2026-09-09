package eu.kanade.tachiyomi.lib.lk21extractor

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * TurboVIP menyembunyikan segmen MPEG-TS di dalam file PNG palsu yang di-hosting
 * di lh3.googleusercontent.com (abuse Google Photos/Drive CDN buat hosting gratis).
 *
 * Format: [PNG header+data valid][IEND chunk][padding 0xFF][MPEG-TS asli mulai sync 0x47]
 *
 * Interceptor ini otomatis strip bagian PNG+padding dan cuma sisain byte TS asli,
 * supaya ExoPlayer (yang expect MPEG-TS murni) bisa langsung mainkan tanpa modifikasi lain.
 */
class TurboVipSegmentInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // Cuma proses response dari domain penyimpan segmen "disamarkan"
        if (!request.url.host.contains("googleusercontent.com")) {
            return response
        }

        val body = response.body ?: return response
        val bytes = body.bytes()

        if (bytes.size < 8 || !bytes.copyOfRange(0, 8).contentEquals(PNG_MAGIC)) {
            // Bukan PNG samaran — kembalikan apa adanya (rebuild karena body sudah dibaca)
            return response.newBuilder()
                .body(bytes.toResponseBody(body.contentType()))
                .build()
        }

        val extracted = extractHiddenTs(bytes)
            ?: return response.newBuilder()
                .body(bytes.toResponseBody(body.contentType()))
                .build()

        return response.newBuilder()
            .header("Content-Type", "video/mp2t")
            .body(extracted.toResponseBody("video/mp2t".toMediaType()))
            .build()
    }

    // Skip IEND -> skip padding 0xFF -> cari sync 0x47 tiap 188 byte
    private fun extractHiddenTs(data: ByteArray): ByteArray? {
        val iendIdx = indexOf(data, IEND_MARKER)
        if (iendIdx == -1) return null
        val iendEnd = iendIdx + 4 + 4 // "IEND" (4 byte) + CRC (4 byte)

        var offset = iendEnd
        while (offset < data.size && data[offset] == 0xFF.toByte()) offset++

        val scanLimit = minOf(4096, data.size - offset)
        for (extra in 0 until scanLimit) {
            val testOffset = offset + extra
            if (looksLikeTsSync(data, testOffset)) {
                return data.copyOfRange(testOffset, data.size)
            }
        }
        return null
    }

    private fun looksLikeTsSync(data: ByteArray, offset: Int, packetSize: Int = 188, checkPackets: Int = 10): Boolean {
        if (offset >= data.size) return false
        var countOk = 0
        for (i in 0 until checkPackets) {
            val pos = offset + i * packetSize
            if (pos >= data.size) break
            if (data[pos] == 0x47.toByte()) countOk++ else return false
        }
        return countOk >= 3
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return -1
    }

    companion object {
        private val PNG_MAGIC = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        private val IEND_MARKER = "IEND".toByteArray()
    }
}
