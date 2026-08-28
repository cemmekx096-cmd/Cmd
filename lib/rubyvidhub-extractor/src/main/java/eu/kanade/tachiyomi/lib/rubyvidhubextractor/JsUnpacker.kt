package eu.kanade.tachiyomi.animeextension.id.anichin.extractors

object JsUnpacker {

    fun unpack(packedJs: String): String {
        return try {
            val payloadRegex = Regex("""}\('(.*?)',(\d+),(\d+),'(.*?)'\.split""")
            val match = payloadRegex.find(packedJs) ?: return packedJs

            val payload = match.groupValues[1]
            val radix = match.groupValues[2].toIntOrNull() ?: 36
            val count = match.groupValues[3].toIntOrNull() ?: 0
            val keywords = match.groupValues[4].split('|')

            decodePayload(payload, radix, count, keywords)
        } catch (e: Exception) {
            packedJs
        }
    }

    private fun decodePayload(
        payload: String,
        radix: Int,
        count: Int,
        keywords: List<String>,
    ): String {
        var result = payload

        for (i in count - 1 downTo 0) {
            val encoded = encodeBase(i, radix)
            val keyword = if (i < keywords.size) keywords[i] else ""

            if (keyword.isNotEmpty()) {
                result = result.replace(Regex("\\b$encoded\\b"), keyword)
            }
        }

        return result
    }

    private fun encodeBase(num: Int, radix: Int): String {
        return if (num == 0) {
            "0"
        } else {
            num.toString(radix)
        }
    }

    fun extractSourceUrl(js: String): String? {
        val unpacked = if (js.contains("eval(function(p,a,c,k,e,d)")) {
            unpack(js)
        } else {
            js
        }

        val patterns = listOf(
            """sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""".toRegex(),
            """file:\s*["']([^"']+\.m3u8[^"']*)["']""".toRegex(),
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
