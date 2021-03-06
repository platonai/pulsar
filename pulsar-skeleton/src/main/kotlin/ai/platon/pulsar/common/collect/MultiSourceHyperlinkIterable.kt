package ai.platon.pulsar.common.collect

import ai.platon.pulsar.common.Priority13
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.urls.UrlAware
import ai.platon.pulsar.crawl.common.collect.PriorityDataCollectorsFormatter

class MultiSourceHyperlinkIterable(
    val fetchCaches: FetchCacheManager,
    val lowerCacheSize: Int = 100,
    val enableDefaults: Boolean = false
) : Iterable<UrlAware> {
    private val logger = getLogger(this)

    private val realTimeCollector = FetchCacheCollector(fetchCaches.realTimeCache, Priority13.HIGHEST)
        .apply { name = "FCC@RealTime" }
    private val delayCollector = DelayCacheCollector(fetchCaches.delayCache, Priority13.HIGHER5)
        .apply { name = "DelayCC@Delay" }
    private val multiSourceDataCollector = MultiSourceDataCollector<UrlAware>()

    val loadingIterable =
        ConcurrentLoadingIterable(multiSourceDataCollector, realTimeCollector, delayCollector, lowerCacheSize)
    val cacheSize get() = loadingIterable.cacheSize

    val openCollectors: Collection<PriorityDataCollector<UrlAware>>
        get() = multiSourceDataCollector.collectors

    val collectors: List<PriorityDataCollector<UrlAware>> get() {
        val list = mutableListOf<PriorityDataCollector<UrlAware>>()
        list += realTimeCollector
        list += delayCollector
        list += this.openCollectors
        list.sortBy { it.priority }
        return list
    }

    init {
        if (enableDefaults && openCollectors.isEmpty()) {
            addDefaultCollectors()
        }
    }

    val abstract: String get() = PriorityDataCollectorsFormatter(collectors).abstract()

    val report: String get() = PriorityDataCollectorsFormatter(collectors).toString()

    /**
     * Add a hyperlink to the very beginning of the fetch queue, so it will be served immediately
     * */
    fun addFirst(url: UrlAware) = loadingIterable.addFirst(url)

    fun addLast(url: UrlAware) = loadingIterable.addLast(url)

    override fun iterator(): Iterator<UrlAware> = loadingIterable.iterator()

    fun addDefaultCollectors(): MultiSourceHyperlinkIterable {
        multiSourceDataCollector.collectors.removeIf { it is FetchCacheCollector }
        fetchCaches.caches.forEach { (priority, fetchCache) ->
            val collector = FetchCacheCollector(fetchCache, priority)
            collector.name = "FCC@" + collector.hashCode()
            addCollector(collector)
        }
        return this
    }

    fun addCollector(collector: PriorityDataCollector<UrlAware>): MultiSourceHyperlinkIterable {
        multiSourceDataCollector.collectors += collector
        return this
    }

    fun addCollectors(collectors: Iterable<PriorityDataCollector<UrlAware>>): MultiSourceHyperlinkIterable {
        multiSourceDataCollector.collectors += collectors
        return this
    }

    fun getCollectors(name: String): List<PriorityDataCollector<UrlAware>> {
        return multiSourceDataCollector.collectors.filter { it.name == name }
    }

    fun getCollectors(names: Iterable<String>): List<PriorityDataCollector<UrlAware>> {
        return multiSourceDataCollector.collectors.filter { it.name in names }
    }

    fun getCollectors(regex: Regex): List<PriorityDataCollector<UrlAware>> {
        return multiSourceDataCollector.collectors.filter { it.name.matches(regex) }
    }

    fun remove(collector: PriorityDataCollector<UrlAware>): Boolean {
        return multiSourceDataCollector.collectors.remove(collector)
    }

    fun removeAll(collectors: Collection<PriorityDataCollector<UrlAware>>): Boolean {
        return multiSourceDataCollector.collectors.removeAll(collectors)
    }

    fun clear() {
        realTimeCollector.fetchCache.clear()
        delayCollector.queue.clear()
        multiSourceDataCollector.collectors.clear()
    }
}
