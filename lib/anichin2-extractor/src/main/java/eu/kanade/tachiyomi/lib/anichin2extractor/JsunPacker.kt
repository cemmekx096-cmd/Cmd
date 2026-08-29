package eu.kanade.tachiyomi.lib.anichin2extractor // Pastikan package ini sesuai dengan lokasi filemu

object JsUnpacker {
    // Tambahkan fungsi ini agar eror 'detect' hilang
    fun detect(packedJs: String): Boolean {
        return packedJs.contains("eval(function(p,a,c,k,e,r)", ignoreCase = true)
    }

    fun unpack(packedJs: String): String {
        return try {
            val payloadRegex = Regex("""}\('(.*?)',(\d+),(\d+),'(.*?)'\.split""")
            val match = payloadRegex.find(packedJs) ?: return packedJs
            val payload = match.groupValues[1]
            val radix = match.groupValues[2].toIntOrNull() ?: 36
            val count = match.groupValues[3].toIntOrNull() ?: 0
            val keywords = match.groupValues[4].split('|')

            var result = payload
            for (i in count - 1 downTo 0) {
                val encoded = i.toString(radix)
                val keyword = if (i < keywords.size && keywords[i].isNotEmpty()) keywords[i] else encoded
                result = result.replace(Regex("\\b$encoded\\b"), keyword)
            }
            result
        } catch (e: Exception) { packedJs }
    }
}
