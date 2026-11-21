package de.drehtuer.nextalarm.domain

import java.util.Locale

// What the weather screen says and allows. The wording is specified in docs/design.md.

/** The search for a place, and what it found. */
sealed interface PlaceSearch {
    /** Nothing asked, or too little typed to ask. */
    data object None : PlaceSearch
    data object Searching : PlaceSearch
    data class Found(val places: List<WeatherPlace>) : PlaceSearch
    data object Failed : PlaceSearch
}

/** What a look at the forecast found. */
sealed interface WeatherPreview {
    data object None : WeatherPreview
    data object Getting : WeatherPreview
    data class Said(val text: String) : WeatherPreview
    data object Failed : WeatherPreview
}

/** What the user has entered on the weather screen, and what the settings hold. */
data class WeatherDraft(
    val query: String = "",
    val search: PlaceSearch = PlaceSearch.None,
    val saved: WeatherSource? = null,
    val preview: WeatherPreview = WeatherPreview.None,
)

/** What the weather screen shows. */
data class WeatherModel(
    val query: String,
    val helper: String,
    val searchFailed: Boolean,
    /** The places found, by [WeatherPlace.label], to choose from. */
    val places: List<String>,
    /** "Forecast for Berlin", once a place is chosen. */
    val chosen: String?,
    val useCurrentLocation: Boolean,
    val canUseCurrentLocation: Boolean,
    val language: ValuePicker<String?>,
    val canPreview: Boolean,
    val previewing: Boolean,
    val previewLine: String?,
    val canTurnOff: Boolean,
)

/** A search needs this much, as a single letter matches thousands of places. */
const val MIN_PLACE_QUERY = 2

const val WEATHER_AUTO_LANGUAGE_LABEL = "Auto: the phone's language"
const val WEATHER_PREVIEW_FAILED = "The forecast can't be had right now. Check the phone's connection."

/** The helper text below the field. */
fun placeHelper(search: PlaceSearch): String = when (search) {
    PlaceSearch.None -> "Type the name of a town or city."
    PlaceSearch.Searching -> "Searching…"
    is PlaceSearch.Found -> if (search.places.isEmpty()) "No place of this name was found." else "Choose one of these."
    PlaceSearch.Failed -> "The search didn't work. Check the phone's connection."
}

/** Auto first, then each language there is a sentence in, by its English name. */
fun weatherLanguagePicker(chosen: String?): ValuePicker<String?> {
    fun label(tag: String?) = if (tag == null) {
        WEATHER_AUTO_LANGUAGE_LABEL
    } else {
        Locale.forLanguageTag(tag).getDisplayName(Locale.ENGLISH).ifEmpty { tag }
    }
    val chosenLabel = label(chosen?.takeIf { it in WEATHER_LANGUAGES })
    return ValuePicker(chosenLabel, (listOf<String?>(null) + WEATHER_LANGUAGES).map { PickerOption(it, label(it)) })
}

/** The value of the "Weather" row on screen 5. */
fun weatherLabel(weather: WeatherSource?): String = when {
    weather == null -> "Off"
    weather.useCurrentLocation -> "Current location, else ${weather.placeName}"
    else -> weather.placeName
}

fun buildWeather(draft: WeatherDraft): WeatherModel {
    val saved = draft.saved
    val search = draft.search
    return WeatherModel(
        query = draft.query,
        helper = placeHelper(search),
        searchFailed = search == PlaceSearch.Failed,
        places = (search as? PlaceSearch.Found)?.places.orEmpty().map { it.label },
        chosen = saved?.let { "Forecast for ${it.placeName}" },
        useCurrentLocation = saved?.useCurrentLocation ?: false,
        canUseCurrentLocation = saved != null,
        language = weatherLanguagePicker(saved?.language),
        canPreview = saved != null && draft.preview != WeatherPreview.Getting,
        previewing = draft.preview == WeatherPreview.Getting,
        previewLine = when (val preview = draft.preview) {
            WeatherPreview.None -> null
            WeatherPreview.Getting -> "Getting the forecast…"
            is WeatherPreview.Said -> preview.text
            WeatherPreview.Failed -> WEATHER_PREVIEW_FAILED
        },
        canTurnOff = saved != null,
    )
}
