package cz.duke390.hlidac.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cz.duke390.hlidac.scrape.Pipeline
import cz.duke390.hlidac.scrape.Report
import cz.duke390.hlidac.work.RefreshWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RefreshState(val running: Boolean = false, val progress: String? = null, val error: String? = null)
data class Prefs(val country: String = "all", val showDealers: Boolean = false, val original: Boolean = false, val tab: Int = 0)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val sp = app.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val wm = WorkManager.getInstance(app)

    private val _report = MutableStateFlow<Report?>(null)
    val report: StateFlow<Report?> = _report.asStateFlow()

    private val _prefs = MutableStateFlow(
        Prefs(sp.getString("country", "all")!!, sp.getBoolean("dealers", false), sp.getBoolean("orig", false), sp.getInt("tab", 0))
    )
    val prefs: StateFlow<Prefs> = _prefs.asStateFlow()

    private var lastFinished = emptySet<String>()

    /** Stav ruční i periodické aktualizace; po dokončení se znovu načte report. */
    val refresh: StateFlow<RefreshState> = combine(
        wm.getWorkInfosForUniqueWorkFlow(RefreshWorker.MANUAL),
        wm.getWorkInfosByTagFlow("periodic"),
    ) { a, b -> a + b }.map { infos ->
        val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED && it.tags.none { t -> t == "periodic" } }
        val finished = infos.filter { it.state.isFinished }.map { it.id.toString() }.toSet()
        if ((finished - lastFinished).isNotEmpty()) load()
        lastFinished = finished
        val failed = infos.filter { it.state == WorkInfo.State.FAILED }.maxByOrNull { it.id.toString() }
        RefreshState(
            running = running != null,
            progress = running?.progress?.getString(RefreshWorker.PROGRESS),
            error = failed?.outputData?.getString(RefreshWorker.ERROR),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RefreshState())

    init {
        viewModelScope.launch {
            val r = load()
            // první spuštění nebo report starší než 3 h -> rovnou aktualizovat (jen jednou při otevření)
            val old = r == null || runCatching {
                java.time.LocalDateTime.parse(r.updatedAt).isBefore(java.time.LocalDateTime.now().minusHours(3))
            }.getOrDefault(true)
            if (old) refreshNow()
        }
    }

    private suspend fun load(): Report? {
        val r = withContext(Dispatchers.IO) { Pipeline(getApplication()).loadReport() }
        _report.value = r
        return r
    }

    fun refreshNow() = RefreshWorker.refreshNow(getApplication())

    fun setPrefs(p: Prefs) {
        _prefs.value = p
        sp.edit().putString("country", p.country).putBoolean("dealers", p.showDealers)
            .putBoolean("orig", p.original).putInt("tab", p.tab).apply()
    }
}
