package eu.kanade.tachiyomi.animeextension.id.lk21series

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object LK21Filters {

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("NOTE: Filter tidak bisa digabung!"),
        AnimeFilter.Separator(),
        GenreFilter(),
        CountryFilter(),
    )

    class GenreFilter : UriPartFilter(
        "Genre",
        arrayOf(
            Pair("<Pilih>", ""),
            Pair("Action", "action"),
            Pair("Adventure", "adventure"),
            Pair("Animation", "animation"),
            Pair("Biography", "biography"),
            Pair("Comedy", "comedy"),
            Pair("Crime", "crime"),
            Pair("Documentary", "documentary"),
            Pair("Drama", "drama"),
            Pair("Family", "family"),
            Pair("Fantasy", "fantasy"),
            Pair("Film-Noir", "film-noir"),
            Pair("History", "history"),
            Pair("Horror", "horror"),
            Pair("Music", "music"),
            Pair("Musical", "musical"),
            Pair("Mystery", "mystery"),
            Pair("Romance", "romance"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Sport", "sport"),
            Pair("Thriller", "thriller"),
            Pair("War", "war"),
            Pair("Western", "western"),
        ),
    )

    class CountryFilter : UriPartFilter(
        "Negara",
        arrayOf(
            Pair("<Pilih>", ""),
            Pair("Indonesia", "indonesia"),
            Pair("USA", "usa"),
            Pair("United Kingdom", "united-kingdom"),
            Pair("Korea", "south-korea"),
            Pair("Japan", "japan"),
            Pair("China", "china"),
            Pair("Thailand", "thailand"),
            Pair("Hong Kong", "hong-kong"),
            Pair("India", "india"),
            Pair("France", "france"),
            Pair("Germany", "germany"),
            Pair("Spain", "spain"),
            Pair("Italy", "italy"),
            Pair("Canada", "canada"),
            Pair("Australia", "australia"),
        ),
    )

    open class UriPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    fun getFilterList(): AnimeFilterList = FILTER_LIST

    // Data class untuk search parameters
    data class SearchParameters(
        val genre: String = "",
        val country: String = "",
    )

    // Extract search parameters dari filters
    fun getSearchParameters(filters: AnimeFilterList): SearchParameters {
        var genre = ""
        var country = ""

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> if (filter.state != 0) genre = filter.toUriPart()
                is CountryFilter -> if (filter.state != 0) country = filter.toUriPart()
                else -> {}
            }
        }

        return SearchParameters(genre, country)
    }
}
