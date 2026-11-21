package de.drehtuer.nextalarm.ui

import de.drehtuer.nextalarm.domain.LastLocation
import de.drehtuer.nextalarm.domain.MIN_PLACE_QUERY
import de.drehtuer.nextalarm.domain.PlaceSearch
import de.drehtuer.nextalarm.domain.SettingsRepository
import de.drehtuer.nextalarm.domain.WeatherDraft
import de.drehtuer.nextalarm.domain.WeatherModel
import de.drehtuer.nextalarm.domain.WeatherPreview
import de.drehtuer.nextalarm.domain.WeatherService
import de.drehtuer.nextalarm.domain.WeatherSource
import de.drehtuer.nextalarm.domain.buildWeather
import de.drehtuer.nextalarm.domain.spokenForecast
import de.drehtuer.nextalarm.domain.weatherSourceFor
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the weather screen needs: the service, for the search and the preview, and where the phone was last. */
class WeatherPorts(
    val service: WeatherService,
    val location: LastLocation,
    val phoneLocale: () -> Locale = Locale::getDefault,
)

/**
 * Runs the weather screen: the search for a place, the choice, the preview. What is chosen is stored at once and asks
 * for a new plan, as alarms take the weather from their snapshot. Everything here runs on the thread of [scope], which
 * has to be a single one.
 */
class WeatherController(
    private val scope: CoroutineScope,
    private val ports: WeatherPorts,
    private val settings: SettingsRepository,
    /** Stores the weather and asks for a new plan. It goes on by itself, whatever happens to the work here. */
    private val store: (WeatherSource?) -> Unit,
    private val publish: (WeatherModel) -> Unit,
) {
    private var draft = WeatherDraft()
    private var opened = false
    private var search: Job? = null
    private var preview: Job? = null

    /** Starts from what is stored, unless the screen is only being rebuilt. */
    fun open() {
        if (opened) return
        opened = true
        scope.launch {
            draft = WeatherDraft(saved = settings.load().weather)
            show()
        }
    }

    fun close() {
        opened = false
    }

    /** A search starts once the typing has paused, so that not every letter asks the service. */
    fun setQuery(query: String) {
        if (query == draft.query) return
        search?.cancel()
        val trimmed = query.trim()
        draft = draft.copy(query = query, search = if (trimmed.length >= MIN_PLACE_QUERY) PlaceSearch.Searching else PlaceSearch.None)
        show()
        if (trimmed.length < MIN_PLACE_QUERY) return
        search = scope.launch {
            delay(SEARCH_DELAY_MILLIS)
            val found = ports.service.search(trimmed)
            draft = draft.copy(search = if (found != null) PlaceSearch.Found(found) else PlaceSearch.Failed)
            show()
        }
    }

    /** The [index]th place found becomes the place of the forecast, with the other settings kept. */
    fun choose(index: Int) {
        val place = (draft.search as? PlaceSearch.Found)?.places?.getOrNull(index) ?: return
        draft = draft.copy(query = "", search = PlaceSearch.None, preview = WeatherPreview.None)
        save(weatherSourceFor(place, draft.saved))
    }

    /** Only with a place: it is what the forecast falls back on. */
    fun setUseCurrentLocation(use: Boolean) {
        val saved = draft.saved ?: return
        save(saved.copy(useCurrentLocation = use))
    }

    fun setLanguage(language: String?) {
        val saved = draft.saved ?: return
        save(saved.copy(language = language))
    }

    /** Gets the forecast the next alarm would say, and shows it. */
    fun showPreview() {
        val saved = draft.saved ?: return
        preview?.cancel()
        draft = draft.copy(preview = WeatherPreview.Getting)
        show()
        preview = scope.launch {
            val spoken = spokenForecast(saved, ports.service, ports.location, ports.phoneLocale())
            draft = draft.copy(preview = if (spoken != null) WeatherPreview.Said(spoken.text) else WeatherPreview.Failed)
            show()
        }
    }

    fun turnOff() {
        search?.cancel()
        preview?.cancel()
        // What is stored stays in the draft until it is replaced, so that the change is seen and stored.
        draft = WeatherDraft(saved = draft.saved)
        save(null)
    }

    private fun save(source: WeatherSource?) {
        val changed = source != draft.saved
        draft = draft.copy(saved = source, preview = if (changed) WeatherPreview.None else draft.preview)
        show()
        if (changed) store(source)
    }

    private fun show() = publish(buildWeather(draft))

    private companion object {
        /** As for the address of a feed: long enough to tell a paste from typing. */
        const val SEARCH_DELAY_MILLIS = 800L
    }
}
