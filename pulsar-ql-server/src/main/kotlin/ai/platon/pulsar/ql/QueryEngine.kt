package ai.platon.pulsar.ql

import ai.platon.pulsar.common.PulsarContext
import ai.platon.pulsar.common.PulsarContext.applicationContext
import ai.platon.pulsar.common.PulsarContext.unmodifiedConfig
import ai.platon.pulsar.common.PulsarSession
import ai.platon.pulsar.crawl.fetch.TaskStatusTracker
import ai.platon.pulsar.net.SeleniumEngine
import ai.platon.pulsar.common.config.CapabilityTypes.FETCH_EAGER_FETCH_LIMIT
import ai.platon.pulsar.common.config.CapabilityTypes.QE_HANDLE_PERIODICAL_FETCH_TASKS
import ai.platon.pulsar.dom.data.BrowserControl
import ai.platon.pulsar.persist.metadata.FetchMode
import com.google.common.cache.*
import com.google.common.collect.Lists
import org.apache.commons.collections4.IteratorUtils
import org.h2.api.ErrorCode
import org.h2.message.DbException
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The QueryEngine fuses h2database and pulsar big data engine
 * So we can use SQL to do big data tasks, include but not limited:
 * <ul>
 * <li>Web spider</li>
 * <li>Web scraping</li>
 * <li>Search engine</li>
 * <li>Collect data from variable data source</li>
 * <li>Information extraction</li>
 * <li>TODO: NLP processing</li>
 * <li>TODO: knowledge graph</li>
 * <li>TODO: machine learning</li>
 * </ul>
 */
object QueryEngine {
    private val log = LoggerFactory.getLogger(ai.platon.pulsar.ql.QueryEngine::class.java)

    enum class Status { NOT_READY, INITIALIZING, RUNNING, CLOSING, CLOSED }

    var status: ai.platon.pulsar.ql.QueryEngine.Status = ai.platon.pulsar.ql.QueryEngine.Status.NOT_READY

    private var backgroundSession: PulsarSession = PulsarContext.createSession()

    /**
     * The sessions container
     * A session will be closed if it's expired or the pool is full
     */
    private val sessions: LoadingCache<ai.platon.pulsar.ql.DbSession, ai.platon.pulsar.ql.QuerySession>

    private val taskStatusTracker: TaskStatusTracker

    private val proxyPool: ai.platon.pulsar.common.proxy.ProxyPool

    private val backgroundExecutor: ScheduledExecutorService

    private val backgroundTaskBatchSize: Int

    private var lazyTaskRound = 0

    private val loading = AtomicBoolean()

    private var handlePeriodicalFetchTasks: Boolean

    private val isClosed: AtomicBoolean = AtomicBoolean()

    init {
        ai.platon.pulsar.ql.QueryEngine.status = ai.platon.pulsar.ql.QueryEngine.Status.INITIALIZING

        Runtime.getRuntime().addShutdownHook(Thread(this::close))

        SeleniumEngine.CLIENT_JS = BrowserControl(unmodifiedConfig).getJs()
        ai.platon.pulsar.ql.QueryEngine.sessions = CacheBuilder.newBuilder()
                .maximumSize(200)
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .removalListener(ai.platon.pulsar.ql.QueryEngine.SessionRemovalListener())
                .build(ai.platon.pulsar.ql.QueryEngine.SessionCacheLoader(this))
        ai.platon.pulsar.ql.QueryEngine.proxyPool = ai.platon.pulsar.common.proxy.ProxyPool.getInstance(unmodifiedConfig)
        ai.platon.pulsar.ql.QueryEngine.handlePeriodicalFetchTasks = unmodifiedConfig.getBoolean(QE_HANDLE_PERIODICAL_FETCH_TASKS, true)
        ai.platon.pulsar.ql.QueryEngine.taskStatusTracker = applicationContext.getBean(TaskStatusTracker::class.java)

        ai.platon.pulsar.ql.QueryEngine.backgroundSession.disableCache()
        ai.platon.pulsar.ql.QueryEngine.backgroundTaskBatchSize = unmodifiedConfig.getUint(FETCH_EAGER_FETCH_LIMIT, 20)
        ai.platon.pulsar.ql.QueryEngine.backgroundExecutor = Executors.newScheduledThreadPool(5)
        ai.platon.pulsar.ql.QueryEngine.registerBackgroundTasks()

        ai.platon.pulsar.ql.QueryEngine.status = ai.platon.pulsar.ql.QueryEngine.Status.RUNNING
    }

    fun createQuerySession(dbSession: ai.platon.pulsar.ql.DbSession): ai.platon.pulsar.ql.QuerySession {
        val querySession = ai.platon.pulsar.ql.QuerySession(dbSession, ai.platon.pulsar.ql.SessionConfig(dbSession, unmodifiedConfig))

        ai.platon.pulsar.ql.QueryEngine.sessions.put(dbSession, querySession)

        return querySession
    }

    /**
     * Get a query session from h2 session
     */
    fun getSession(dbSession: ai.platon.pulsar.ql.DbSession): ai.platon.pulsar.ql.QuerySession {
        try {
            return ai.platon.pulsar.ql.QueryEngine.sessions.get(dbSession)
        } catch (e: ExecutionException) {
            throw DbException.get(ErrorCode.DATABASE_IS_CLOSED, e)
        }
    }

    fun close() {
        if (ai.platon.pulsar.ql.QueryEngine.isClosed.getAndSet(true)) {
            return
        }
        ai.platon.pulsar.ql.QueryEngine.status = ai.platon.pulsar.ql.QueryEngine.Status.CLOSING

        ai.platon.pulsar.ql.QueryEngine.log.info("[Destruction] Destructing QueryEngine ...")

        ai.platon.pulsar.ql.QueryEngine.backgroundExecutor.shutdownNow()

        ai.platon.pulsar.ql.QueryEngine.sessions.asMap().values.forEach { it.close() }
        ai.platon.pulsar.ql.QueryEngine.sessions.cleanUp()

        ai.platon.pulsar.ql.QueryEngine.taskStatusTracker.close()

        ai.platon.pulsar.ql.QueryEngine.proxyPool.close()

        ai.platon.pulsar.ql.QueryEngine.status = ai.platon.pulsar.ql.QueryEngine.Status.CLOSED
    }

    private fun registerBackgroundTasks() {
        val r = { ai.platon.pulsar.ql.QueryEngine.runSilently { ai.platon.pulsar.ql.QueryEngine.loadLazyTasks() } }
        ai.platon.pulsar.ql.QueryEngine.backgroundExecutor.scheduleAtFixedRate(r, 10, 30, TimeUnit.SECONDS)

        if (ai.platon.pulsar.ql.QueryEngine.handlePeriodicalFetchTasks) {
            val r2 = { ai.platon.pulsar.ql.QueryEngine.runSilently { ai.platon.pulsar.ql.QueryEngine.fetchSeeds() } }
            ai.platon.pulsar.ql.QueryEngine.backgroundExecutor.scheduleAtFixedRate(r2, 30, 120, TimeUnit.SECONDS)
        }

        val r3 = { ai.platon.pulsar.ql.QueryEngine.runSilently { ai.platon.pulsar.ql.QueryEngine.maintainProxyPool() } }
        ai.platon.pulsar.ql.QueryEngine.backgroundExecutor.scheduleAtFixedRate(r3, 120, 120, TimeUnit.SECONDS)
    }

    private fun runSilently(target: () -> Unit) {
        try {
            target()
        } catch (e: Throwable) {
            // Do not throw anything
            ai.platon.pulsar.ql.QueryEngine.log.error(e.toString())
        }
    }

    /**
     * Get background tasks and run them
     */
    private fun loadLazyTasks() {
        if (ai.platon.pulsar.ql.QueryEngine.loading.get()) {
            return
        }

        for (mode in FetchMode.values()) {
            val urls = ai.platon.pulsar.ql.QueryEngine.taskStatusTracker.takeLazyTasks(mode, ai.platon.pulsar.ql.QueryEngine.backgroundTaskBatchSize)
            if (!urls.isEmpty()) {
                ai.platon.pulsar.ql.QueryEngine.loadAll(urls.map { it.toString() }, ai.platon.pulsar.ql.QueryEngine.backgroundTaskBatchSize, mode)
            }
        }
    }

    /**
     * Get periodical tasks and run them
     */
    private fun fetchSeeds() {
        if (ai.platon.pulsar.ql.QueryEngine.loading.get()) {
            return
        }

        for (mode in FetchMode.values()) {
            val urls = ai.platon.pulsar.ql.QueryEngine.taskStatusTracker.getSeeds(mode, 1000)
            if (!urls.isEmpty()) {
                ai.platon.pulsar.ql.QueryEngine.loadAll(urls, ai.platon.pulsar.ql.QueryEngine.backgroundTaskBatchSize, mode)
            }
        }
    }

    private fun maintainProxyPool() {
        ai.platon.pulsar.ql.QueryEngine.proxyPool.recover(100)
    }

    private fun loadAll(urls: Iterable<String>, batchSize: Int, mode: FetchMode) {
        if (!urls.iterator().hasNext() || batchSize <= 0) {
            ai.platon.pulsar.ql.QueryEngine.log.debug("Not loading lazy tasks")
            return
        }

        ai.platon.pulsar.ql.QueryEngine.loading.set(true)

        val loadOptions = ai.platon.pulsar.common.options.LoadOptions()
        loadOptions.fetchMode = mode
        loadOptions.isBackground = true

        val partitions: List<List<String>> = Lists.partition(IteratorUtils.toList(urls.iterator()), batchSize)
        partitions.forEach { ai.platon.pulsar.ql.QueryEngine.loadAll(it, loadOptions) }

        ai.platon.pulsar.ql.QueryEngine.loading.set(false)
    }

    private fun loadAll(urls: Collection<String>, loadOptions: ai.platon.pulsar.common.options.LoadOptions) {
        ++ai.platon.pulsar.ql.QueryEngine.lazyTaskRound
        ai.platon.pulsar.ql.QueryEngine.log.debug("Running {}th round for lazy tasks", ai.platon.pulsar.ql.QueryEngine.lazyTaskRound)
        ai.platon.pulsar.ql.QueryEngine.backgroundSession.parallelLoadAll(urls, loadOptions)
    }

    private class SessionCacheLoader(val engine: ai.platon.pulsar.ql.QueryEngine): CacheLoader<ai.platon.pulsar.ql.DbSession, ai.platon.pulsar.ql.QuerySession>() {
        override fun load(dbSession: ai.platon.pulsar.ql.DbSession): ai.platon.pulsar.ql.QuerySession {
            ai.platon.pulsar.ql.QueryEngine.log.warn("Create PulsarSession for h2 h2session {} via SessionCacheLoader (not expected ...)", dbSession)
            return ai.platon.pulsar.ql.QueryEngine.createQuerySession(dbSession)
        }
    }

    private class SessionRemovalListener : RemovalListener<ai.platon.pulsar.ql.DbSession, ai.platon.pulsar.ql.QuerySession> {
        override fun onRemoval(notification: RemovalNotification<ai.platon.pulsar.ql.DbSession, ai.platon.pulsar.ql.QuerySession>) {
            val cause = notification.cause
            val dbSession = notification.key
            when (cause) {
                RemovalCause.EXPIRED, RemovalCause.SIZE -> {
                    // It's safe to close h2 h2session, @see {org.h2.api.ErrorCode#DATABASE_CALLED_AT_SHUTDOWN}
                    // h2session.close()
                    notification.value.close()
                    ai.platon.pulsar.ql.QueryEngine.log.info("Session {} is closed for reason '{}', remaining {} sessions",
                            dbSession, cause, ai.platon.pulsar.ql.QueryEngine.sessions.size())
                }
                else -> {
                }
            }
        }
    }
}