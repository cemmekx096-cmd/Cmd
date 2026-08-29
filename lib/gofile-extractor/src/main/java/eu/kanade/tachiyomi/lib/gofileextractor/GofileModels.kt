package eu.kanade.tachiyomi.lib.gofileextractor

/**
 * Data classes for Gofile API responses
 * 
 * These models are used to parse JSON responses from Gofile API.
 * Using simple data classes instead of Jackson/Gson for minimal dependencies.
 */

/**
 * Response from createAccount API
 * Format: {"status":"ok","data":{"token":"xxx"}}
 */
data class GofileAccountResponse(
    val status: String,
    val data: Map<String, String>
)

/**
 * Single file/content item from Gofile
 */
data class GofileContentItem(
    val id: String,
    val type: String,
    val name: String,
    val link: String
)

/**
 * Response from getContent API
 * Format: {"status":"ok","data":{"contents":{"id1":{...},"id2":{...}}}}
 */
data class GofileContentResponse(
    val status: String,
    val data: GofileContentData
)

/**
 * Data section of content response
 */
data class GofileContentData(
    val contents: Map<String, Map<String, String>>
)
