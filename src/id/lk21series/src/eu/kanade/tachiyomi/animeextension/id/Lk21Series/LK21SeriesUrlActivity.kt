package eu.kanade.tachiyomi.animeextension.id.lk21series

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Deep link handler untuk LK21Series
 * Intercept URL nontondrama.my dan redirect ke Aniyomi
 */
class LK21SeriesUrlActivity : Activity() {

    private val tag = "LK21SeriesUrlActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pathSegments = intent?.data?.pathSegments
        if (pathSegments != null && pathSegments.isNotEmpty()) {
            val item = pathSegments.last()

            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.ANIMESEARCH"
                putExtra("query", "${LK21Series::class.java.name}:$item")
                putExtra("filter", packageName)
            }

            try {
                startActivity(mainIntent)
            } catch (e: ActivityNotFoundException) {
                Log.e(tag, "Could not start activity", e)
            }
        } else {
            Log.e(tag, "No path segments in intent data")
        }

        finish()
        exitProcess(0)
    }

    private fun exitProcess(status: Int) {
        // No-op for safety
    }
}
