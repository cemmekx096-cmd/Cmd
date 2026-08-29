package eu.kanade.tachiyomi.lib.acefileextractor

import android.util.Log
import kotlin.math.pow

/**
 * JavaScript Unpacker
 * 
 * Detects and unpacks P.A.C.K.E.R. encoded JavaScript.
 * Ported from CloudStream3.
 * 
 * Usage:
 * ```
 * val unpacker = JsUnpacker(packedJS)
 * if (unpacker.detect()) {
 *     val unpackedJS = unpacker.unpack()
 * }
 * ```
 */
class JsUnpacker(private val packedJS: String?) {
    
    /**
     * Detects whether the javascript is P.A.C.K.E.R. coded.
     *
     * @return true if it's P.A.C.K.E.R. coded.
     */
    fun detect(): Boolean {
        val js = packedJS?.replace(" ", "") ?: return false
        val pattern = "eval\\(function\\(p,a,c,k,e,[rd]".toRegex()
        return pattern.containsMatchIn(js)
    }
    
    /**
     * Unpack the javascript
     *
     * @return the javascript unpacked or null.
     */
    fun unpack(): String? {
        val js = packedJS ?: return null
        
        try {
            // Match packed JavaScript pattern
            val pattern = """\}\s*\('(.*)',\s*(.*?),\s*(\d+),\s*'(.*?)'\.split\('\|'\)""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(js) ?: return null
            
            if (match.groupValues.size != 5) {
                return null
            }
            
            val payload = match.groupValues[1].replace("\\'", "'")
            val radixStr = match.groupValues[2]
            val countStr = match.groupValues[3]
            val symtab = match.groupValues[4].split("|").toTypedArray()
            
            var radix = 36
            var count = 0
            
            try {
                radix = radixStr.toIntOrNull() ?: radix
            } catch (_: Exception) {
                // Keep default radix
            }
            
            try {
                count = countStr.toIntOrNull() ?: 0
            } catch (_: Exception) {
                // Keep default count
            }
            
            if (symtab.size != count) {
                throw Exception("Unknown p.a.c.k.e.r. encoding")
            }
            
            val unbase = Unbase(radix)
            val wordPattern = """\b[a-zA-Z0-9_]+\b""".toRegex()
            val matches = wordPattern.findAll(payload).toList()
            
            val decoded = StringBuilder(payload)
            var replaceOffset = 0
            
            for (match in matches) {
                val word = match.value
                val x = unbase.unbase(word)
                var value: String? = null
                
                if (x < symtab.size && x >= 0) {
                    value = symtab[x]
                }
                
                if (!value.isNullOrEmpty() && word.isNotEmpty()) {
                    decoded.replace(
                        match.range.first + replaceOffset,
                        match.range.last + 1 + replaceOffset,
                        value
                    )
                    replaceOffset += value.length - word.length
                }
            }
            
            return decoded.toString()
            
        } catch (e: Exception) {
            Log.e("JsUnpacker", "Error unpacking: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Helper class for radix conversion
     */
    private inner class Unbase(private val radix: Int) {
        private val ALPHABET_62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private val ALPHABET_95 =
            " !\"#\$%&\\'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\\\]^_`abcdefghijklmnopqrstuvwxyz{|}~"
        
        private var alphabet: String? = null
        private var dictionary: HashMap<String, Int>? = null
        
        init {
            if (radix > 36) {
                when {
                    radix < 62 -> {
                        alphabet = ALPHABET_62.substring(0, radix)
                    }
                    radix in 63..94 -> {
                        alphabet = ALPHABET_95.substring(0, radix)
                    }
                    radix == 62 -> {
                        alphabet = ALPHABET_62
                    }
                    radix == 95 -> {
                        alphabet = ALPHABET_95
                    }
                }
                dictionary = HashMap(95)
                alphabet?.let { alph ->
                    for (i in alph.indices) {
                        dictionary!![alph.substring(i, i + 1)] = i
                    }
                }
            }
        }
        
        fun unbase(str: String): Int {
            var ret = 0
            if (alphabet == null) {
                ret = str.toInt(radix)
            } else {
                val tmp = str.reversed()
                for (i in tmp.indices) {
                    ret += (radix.toDouble().pow(i.toDouble()) * dictionary!![tmp.substring(i, i + 1)]!!).toInt()
                }
            }
            return ret
        }
    }
}
