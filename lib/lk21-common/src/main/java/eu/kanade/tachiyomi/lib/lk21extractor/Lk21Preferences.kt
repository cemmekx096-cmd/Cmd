package eu.kanade.tachiyomi.lib.lk21extractor

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

class LK21Filters {
    open class DynamicCheckBox(name: String, val id: String) : AnimeFilter.CheckBox(name)
    open class DynamicSelect(name: String, val values: Array<String>) : AnimeFilter.Select<String>(name, values)

    fun getFilterList(genres: List<Pair<String, String>>, countries: List<Pair<String, String>>, years: List<String>): AnimeFilterList {
        val filterElements = mutableListOf<AnimeFilter<*>>()

        if (genres.isNotEmpty()) {
            filterElements.add(AnimeFilter.Header("Genres"))
            filterElements.add(GenreGroup(genres.map { DynamicCheckBox(it.first, it.second) }))
        }

        if (countries.isNotEmpty()) {
            filterElements.add(AnimeFilter.Separator())
            filterElements.add(CountryGroup(countries.map { DynamicCheckBox(it.first, it.second) }))
        }
        
        if (years.isNotEmpty()) {
            filterElements.add(AnimeFilter.Separator())
            filterElements.add(DynamicSelect("Tahun", (listOf("Semua") + years).toTypedArray()))
        }

        return AnimeFilterList(filterElements)
    }

    private class GenreGroup(genres: List<DynamicCheckBox>) : AnimeFilter.Group<DynamicCheckBox>("Genre", genres)
    private class CountryGroup(countries: List<DynamicCheckBox>) : AnimeFilter.Group<DynamicCheckBox>("Negara", countries)
}
