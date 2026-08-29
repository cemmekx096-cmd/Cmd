package eu.kanade.tachiyomi.lib.rubyvidhubextractor

/**
 * Utility untuk unpack JavaScript yang di-obfuscate dengan p-a-c-k-e-r
 * Format: eval(function(p,a,c,k,e,d){...}('payload', base, count, 'keywords'.split('|'), ...))
 */
object JsUnpacker {

    /**
     * Unpack packed JavaScript code
     *
     * @param packedJs Packed JavaScript string
     * @return Unpacked JavaScript code
     */
    fun unpack(packedJs: String): String {
        return try {
            // Extract the packed content
            val payloadRegex = Regex("""}\('(.*?)',(\d+),(\d+),'(.*?)'\.split""")
            val match = payloadRegex.find(packedJs) ?: return packedJs

            val payload = match.groupValues[1]
            val radix = match.groupValues[2].toIntOrNull() ?: 36
            val count = match.groupValues[3].toIntOrNull() ?: 0
            val keywords = match.groupValues[4].split('|')

            // Decode payload
            decodePayload(payload, radix, count, keywords)
        } catch (e: Exception) {
            packedJs // Return original jika gagal unpack
        }
    }

    /**
     * Decode payload menggunakan radix dan keywords
     */
    private fun decodePayload(
        payload: String,
        radix: Int,
        count: Int,
        keywords: List<String>,
    ): String {
        var result = payload

        // Replace encoded words dengan keywords
        for (i in count - 1 downTo 0) {
            val encoded = encodeBase(i, radix)
            val keyword = if (i < keywords.size) keywords[i] else ""

            if (keyword.isNotEmpty()) {
                // Replace whole words only
                result = result.replace(Regex("\\b$encoded\\b"), keyword)
            }
        }

        return result
    }

    /**
     * Encode number ke base tertentu (misal base 36)
     */
    private fun encodeBase(num: Int, radix: Int): String {
        return if (num == 0) {
            "0"
        } else {
            num.toString(radix)
        }
    }

    /**
     * Extract source URL dari packed atau unpacked JavaScript
     *
     * @param js JavaScript code (packed atau unpacked)
     * @return Source URL jika ditemukan, null jika tidak
     */
    fun extractSourceUrl(js: String): String? {
        // Unpack dulu jika packed
        val unpacked = if (js.contains("eval(function(p,a,c,k,e,d)")) {
            unpack(js)
        } else {
            js
        }

        // Pattern untuk extract source URL
        val patterns = listOf(
            // sources:[{file:"URL"}]
            """sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""".toRegex(),
            // file:"URL"
            """file:\s*["']([^"']+\.m3u8[^"']*)["']""".toRegex(),
            // "file":"URL"
            """"file":\s*"([^"]+\.m3u8[^"]*?)"""".toRegex(),
        )

        for (pattern in patterns) {
            pattern.find(unpacked)?.groupValues?.get(1)?.let { url ->
                if (url.isNotEmpty()) return url
            }
        }

        return null
    }
}
