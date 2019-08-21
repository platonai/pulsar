package ai.platon.pulsar.net.browser

import ai.platon.pulsar.common.*
import ai.platon.pulsar.common.HttpHeaders.*
import ai.platon.pulsar.common.config.*
import ai.platon.pulsar.common.config.CapabilityTypes.*
import ai.platon.pulsar.common.config.PulsarConstants.CMD_WEB_DRIVER_CLOSE_ALL
import ai.platon.pulsar.common.config.PulsarConstants.CMD_WEB_DRIVER_DELETE_ALL_COOKIES
import ai.platon.pulsar.common.proxy.NoProxyException
import ai.platon.pulsar.crawl.fetch.FetchStatus
import ai.platon.pulsar.crawl.fetch.FetchTaskTracker
import ai.platon.pulsar.crawl.protocol.ForwardingResponse
import ai.platon.pulsar.crawl.protocol.Response
import ai.platon.pulsar.dom.Documents
import ai.platon.pulsar.persist.ProtocolStatus
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.metadata.BrowserType
import ai.platon.pulsar.persist.metadata.MultiMetadata
import ai.platon.pulsar.persist.metadata.Name
import ai.platon.pulsar.persist.metadata.ProtocolStatusCodes
import ai.platon.pulsar.proxy.InternalProxyServer
import com.google.common.net.InternetDomainName
import com.google.gson.Gson
import org.apache.commons.codec.digest.DigestUtils
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.OutputType
import org.openqa.selenium.WebDriver
import org.openqa.selenium.WebDriverException
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.htmlunit.HtmlUnitDriver
import org.openqa.selenium.remote.RemoteWebDriver
import org.openqa.selenium.support.ui.FluentWait
import org.slf4j.LoggerFactory
import java.nio.charset.Charset
import java.nio.file.Path
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern.CASE_INSENSITIVE

data class BrowserJsData(
        val status: Status = Status(),
        val initStat: Stat = Stat(),
        val lastStat: Stat = Stat(),
        val initD: Stat = Stat(),
        val lastD: Stat = Stat()
) {
    data class Status(
            val n: Int = 0,
            val scroll: Int = 0,
            val st: String = "",
            val r: String = "",
            val idl: String = ""
    )

    data class Stat(
            val ni: Int = 0,
            val na: Int = 0,
            val nnm: Int = 0,
            val nst: Int = 0,
            val w: Int = 0,
            val h: Int = 0
    )

    override fun toString(): String {
        val s1 = initStat
        val s2 = lastStat
        val s3 = initD
        val s4 = lastD

        val s = String.format(
                "img: %s/%s/%s/%s, a: %s/%s/%s/%s, num: %s/%s/%s/%s, st: %s/%s/%s/%s, " +
                        "w: %s/%s/%s/%s, h: %s/%s/%s/%s",
                s1.ni,  s2.ni,  s3.ni,  s4.ni,
                s1.na,  s2.na,  s3.na,  s4.na,
                s1.nnm, s2.nnm, s3.nnm, s4.nnm,
                s1.nst, s2.nst, s3.nst, s4.nst,
                s1.w,   s2.w,   s3.w,   s4.w,
                s1.h,   s2.h,   s3.h,   s4.h
        )

        val st = status
        return String.format("n:%s scroll:%s st:%s r:%s idl:%s\t%s\t(is,ls,id,ld)",
                st.n, st.scroll, st.st, st.r, st.idl, s)
    }

    companion object {
        val default = BrowserJsData()
    }
}

data class DriverConfig(
        var pageLoadTimeout: Duration,
        var scriptTimeout: Duration,
        var scrollDownCount: Int,
        var scrollInterval: Duration
) {
    constructor(config: ImmutableConfig): this(
            config.getDuration(FETCH_PAGE_LOAD_TIMEOUT, Duration.ofSeconds(60)),
            // wait page ready using script, so it can not smaller than pageLoadTimeout
            config.getDuration(FETCH_SCRIPT_TIMEOUT, Duration.ofSeconds(60)),
            config.getInt(FETCH_SCROLL_DOWN_COUNT, 5),
            config.getDuration(FETCH_SCROLL_DOWN_INTERVAL, Duration.ofMillis(500))
    )
}

class FetchTask(
        val batchId: Int,
        val taskId: Int,
        val priority: Int,
        val page: WebPage,
        val volatileConfig: VolatileConfig
) {
    val url get() = page.url
    lateinit var response: Response

    companion object {
        val NIL = FetchTask(0, 0, 0, WebPage.NIL, VolatileConfig.EMPTY)
    }
}

class FetchResult(
        val task: FetchTask,
        val response: Response
)

private data class VisitResult(
        val protocolStatus: ProtocolStatus,
        val jsData: BrowserJsData
) {
    companion object {
        val canceled = VisitResult(ProtocolStatus.STATUS_CANCELED, BrowserJsData.default)
    }
}

class IncompleteContentException: Exception {
    constructor() : super() {}

    constructor(message: String) : super(message) {}

    constructor(message: String, cause: Throwable) : super(message, cause) {}

    constructor(cause: Throwable) : super(cause) {}
}

/**
 * Created by vincent on 18-1-1.
 * Copyright @ 2013-2017 Platon AI. All rights reserved
 *
 * Note: SeleniumEngine should be process scope
 */
class SeleniumEngine(
        browserControl: BrowserControl,
        private val driverManager: WebDriverManager,
        private val internalProxyServer: InternalProxyServer,
        private val fetchTaskTracker: FetchTaskTracker,
        private val immutableConfig: ImmutableConfig
): Parameterized, AutoCloseable {
    val log = LoggerFactory.getLogger(SeleniumEngine::class.java)!!

    private val browserDataGson = Gson()
    private val monthDay = DateTimeUtil.now("MMdd")

    private val libJs = browserControl.parseLibJs(false)
    private val clientJs = browserControl.parseJs(false)
    private val supportAllCharsets get() = immutableConfig.getBoolean(PARSE_SUPPORT_ALL_CHARSETS, false)
    private var charsetPattern = if (supportAllCharsets) systemAvailableCharsetPattern else defaultCharsetPattern
    private var fetchMaxRetry = immutableConfig.getInt(HTTP_FETCH_MAX_RETRY, 3)
    private val defaultDriverConfig = DriverConfig(immutableConfig)
    private val maxCookieView = 40
    private val closed = AtomicBoolean(false)
    private val isClosed get() = closed.get()

    init {
        instanceCount.incrementAndGet()
        params.withLogger(log).info()
    }

    override fun getParams(): Params {
        return Params.of(
                "instanceCount", instanceCount,
                "charsetPattern", charsetPattern,
                "pageLoadTimeout", defaultDriverConfig.pageLoadTimeout,
                "scriptTimeout", defaultDriverConfig.scriptTimeout,
                "scrollDownCount", defaultDriverConfig.scrollDownCount,
                "scrollInterval", defaultDriverConfig.scrollInterval,
                "clientJsLength", clientJs.length,
                "webDriverCapacity", driverManager.capacity
        )
    }

    internal fun fetchContentInternal(task: FetchTask): FetchResult {
        if (RuntimeUtils.hasLocalFileCommand(CMD_WEB_DRIVER_CLOSE_ALL)) {
            log.info("Executing local file command {}", CMD_WEB_DRIVER_CLOSE_ALL)
            driverManager.closeAll()
        }

        val response: Response
        if (isClosed) {
            log.info("System is closed, cancel the task | {}", task.url)
            response = ForwardingResponse(task.url, ProtocolStatus.STATUS_CANCELED)
        } else {
            fetchTaskTracker.totalTaskCount.getAndIncrement()
            fetchTaskTracker.batchTaskCounters.computeIfAbsent(task.batchId) { AtomicInteger() }.incrementAndGet()
            response = visitPageAndRetrieveContentWithRetry(task)
        }

        return FetchResult(task, response)
    }

    private fun visitPageAndRetrieveContentWithRetry(task: FetchTask): Response {
        val maxRetry = task.volatileConfig.getInt(HTTP_FETCH_MAX_RETRY, fetchMaxRetry)
        var response: Response? = null
        var exception: Exception? = null

        var i = 0
        while (i++ < maxRetry && response == null && !isClosed && !Thread.currentThread().isInterrupted) {
            if (i > 1) {
                log.warn("Round {} retrying another Web driver ... | {}", i, task.url)
            }

            var driverCrashed = false
            exception = null
            val driver = driverManager.poll(task.priority, task.volatileConfig)
                    ?: return ForwardingResponse(task.page.url, ProtocolStatus.STATUS_RETRY)

            try {
                response = if (internalProxyServer.isEnabled) {
                    internalProxyServer.run {
                        visitPageAndRetrieveContent(driver, task)
                    }
                } else {
                    visitPageAndRetrieveContent(driver, task)
                }
            } catch (e: NoProxyException) {
                log.warn("No proxy, request is canceled - {}", task.url)
                response = ForwardingResponse(task.url, ProtocolStatus.STATUS_CANCELED)
            } catch (e: org.openqa.selenium.NoSuchSessionException) {
                log.warn("Web driver is crashed - {}", StringUtil.simplifyException(e))

                response = null
                exception = e
                driverCrashed = true
            } catch (e: org.apache.http.conn.HttpHostConnectException) {
                log.warn("Web driver is crashed - {}", StringUtil.simplifyException(e))

                response = null
                exception = e
                driverCrashed = true
            } catch (e: IncompleteContentException) {
                log.warn("Content incomplete - {}", StringUtil.simplifyException(e))

                response = null
                exception = e
                driverCrashed = true
            } finally {
                if (internalProxyServer.isEnabled) {
                    task.page.metadata.set(Name.PROXY, internalProxyServer.proxyEntry?.hostPort)
                }

                if (driverCrashed) {
                    driverManager.retire(driver, exception)
                } else {
                    if (RuntimeUtils.hasLocalFileCommand(CMD_WEB_DRIVER_DELETE_ALL_COOKIES, Duration.ZERO)) {
                        log.info("Executing local file command {}", CMD_WEB_DRIVER_DELETE_ALL_COOKIES)
                        driver.driver.manage().deleteAllCookies()
                    }

                    driverManager.offer(driver)
                }
            }
        }

        return when {
            response != null -> response
            exception != null -> ForwardingResponse(task.url, ProtocolStatus.failed(exception))
            else -> ForwardingResponse(task.url, ProtocolStatus.STATUS_FAILED)
        }
    }

    private fun visitPageAndRetrieveContent(driver: ManagedWebDriver, task: FetchTask): Response {
        val batchId = task.batchId
        val page = task.page
        // page.location is the last working address, and page.url is the permanent internal address
        val url = page.url
        var location = page.location
        if (location.isBlank()) {
            location = url
        }
        if (url != location) {
            log.warn("Page location does't match url | {} - {}", url, location)
        }

        val driverConfig = getDriverConfig(task.priority, task.page, task.volatileConfig)
        var status: ProtocolStatus
        var jsData = BrowserJsData.default
        val headers = MultiMetadata()
        val startTime = System.currentTimeMillis()
        headers.put(Q_REQUEST_TIME, startTime.toString())

        var pageSource = ""

        try {
            val result = visit(task, driver.driver, driverConfig)
            status = result.protocolStatus
            jsData = result.jsData
            // TODO: handle with frames
            // driver.switchTo().frame(1);
        } catch (e: org.openqa.selenium.TimeoutException) {
            // log.warn(e.toString())
            status = ProtocolStatus.failed(ProtocolStatusCodes.WEB_DRIVER_TIMEOUT)
        } catch (e: org.openqa.selenium.NoSuchElementException) {
            // failed to wait for body
            status = ProtocolStatus.STATUS_RETRY
            log.warn(e.message)
        } catch (e: org.openqa.selenium.UnhandledAlertException) {
            // failed to wait for body
            status = ProtocolStatus.STATUS_RETRY
            log.warn(StringUtil.simplifyException(e)?:"UnhandledAlertException")
        } catch (e: org.openqa.selenium.NoSuchSessionException) {
            // failed to wait for body
            status = ProtocolStatus.STATUS_BROWSER_RETRY
            log.warn("Web driver is crashed - {}", StringUtil.simplifyException(e))
        } catch (e: org.apache.http.conn.HttpHostConnectException) {
            // failed to wait for body
            status = ProtocolStatus.STATUS_BROWSER_RETRY
            log.warn("Web driver is crashed - {}", StringUtil.simplifyException(e))
        } catch (e: org.openqa.selenium.WebDriverException) {
            // 1. session deleted because of page crash
            // 2. org.openqa.selenium.NoSuchSessionException: invalid session id

            status = ProtocolStatus.STATUS_RETRY
            log.warn("Unexpected WebDriver exception", e)
        } catch (e: Throwable) {
            // must not throw again
            status = ProtocolStatus.STATUS_EXCEPTION
            log.warn("Unexpected exception", e)
        }

        pageSource = getPageSourceSilently(driver)

        val proxyError = pageSource.length == 76
                && pageSource.indexOf("<html>") != -1
                && pageSource.lastIndexOf("</html>") != -1
        if (proxyError) {
            throw IncompleteContentException("Retrieved only 76 bytes, browser fetched nothing")
        }

        if (status.minorCode == ProtocolStatusCodes.WEB_DRIVER_TIMEOUT
                || status.minorCode == ProtocolStatusCodes.DOCUMENT_READY_TIMEOUT) {
            // The javascript set data-error flag to indicate if the vision information of all DOM nodes are calculated
            val oldStatus = status
            val integrity = checkHtmlIntegrity(pageSource)
            if (integrity.first) {
                status = ProtocolStatus.STATUS_SUCCESS
            }

            if (status.isSuccess) {
                log.info("Html is OK but timeout ({}) after {} with {} | {}",
                        oldStatus.minorName, DateTimeUtil.elapsedTime(startTime),
                        StringUtil.readableByteCount(pageSource.length.toLong()), page.url)
            } else {
                log.info("Timeout with page source check {}", integrity.second)
                handleWebDriverTimeout(page.url, startTime, pageSource, driverConfig)
            }
        }

        handleFetchFinish(page, driver, headers)
        if (jsData !== BrowserJsData.default) {
            page.metadata.set(Name.BROWSER_JS_DATA, browserDataGson.toJson(jsData, BrowserJsData::class.java))
        }
        pageSource = handlePageSource(pageSource, status, page, driver)
        headers.put(CONTENT_LENGTH, pageSource.length.toString())
        if (status.isSuccess) {
            handleFetchSuccess(batchId)
        }

        // TODO: handle redirect
        // TODO: collect response header
        // TODO: fetch only the major pages, css, js, etc, ignore the rest resources, ignore external resources
        // TODO: ignore timeout and get the page source

        return ForwardingResponse(page.url, pageSource, status, headers)
    }

    private fun checkHtmlIntegrity(pageSource: String): Pair<Boolean, String> {
        val p1 = pageSource.indexOf("<body")
        if (p1 <= 0) return false to "NO_BODY_START"
        val p2 = pageSource.indexOf(">", p1)
        if (p2 < p1) return false to "NO_BODY_END"
        // no any link, it's incomplete
        val p3 = pageSource.indexOf("<a", p2)
        if (p3 < p2) return false to "NO_ANCHOR"

        // TODO: optimization using region match
        val bodyTag = pageSource.substring(p1, p2)
        val r = bodyTag.contains("data-error=\"0\"")
        if (!r) {
            return false to "NO_JS_OK"
        }

        return true to "OK"
    }

    private fun visit(task: FetchTask, driver: WebDriver, driverConfig: DriverConfig): VisitResult {
        val batchId = task.batchId
        val taskId = task.taskId
        val url = task.url
        val page = task.page

        val t = fetchTaskTracker
        log.info("Fetching task {}/{}/{} in thread {}, drivers: {}/{}/{} | {} | timeouts: {}/{}/{}",
                taskId, t.batchTaskCounters[batchId], t.totalTaskCount,
                Thread.currentThread().id,
                driverManager.workingSize, driverManager.freeSize, driverManager.totalSize,
                page.configuredUrl,
                driverConfig.pageLoadTimeout, driverConfig.scriptTimeout, driverConfig.scrollInterval
        )

        val timeouts = driver.manage().timeouts()
        timeouts.pageLoadTimeout(driverConfig.pageLoadTimeout.seconds, TimeUnit.SECONDS)
        timeouts.setScriptTimeout(driverConfig.scriptTimeout.seconds, TimeUnit.SECONDS)
        driver.get(url)

        // Block and wait for the document is ready: all css and resources are OK
        if (!JavascriptExecutor::class.java.isAssignableFrom(driver.javaClass)) {
            log.warn("Web driver is not a JavascriptExecutor, cancel the task")
            return VisitResult.canceled
        }

        return executeJs(url, driver, driverConfig)
    }

    @Throws(WebDriverException::class)
    private fun executeJs(url: String, driver: WebDriver, driverConfig: DriverConfig): VisitResult {
        val jsExecutor = driver as? JavascriptExecutor?: return VisitResult.canceled

        var status = ProtocolStatus.STATUS_SUCCESS
        val pageLoadTimeout = driverConfig.pageLoadTimeout.seconds

        try {
            val documentWait = FluentWait<WebDriver>(driver)
                    .withTimeout(pageLoadTimeout, TimeUnit.SECONDS)
                    .pollingEvery(1, TimeUnit.SECONDS)
                    .ignoring(InterruptedException::class.java)

            try {
                // make sure the document is ready
                val initialScroll = 2
                val maxRound = pageLoadTimeout - 10 // leave 10 seconds to wait for script finish
                // TODO: wait for expected ni, na, nnum, nst, etc; required element
                val js = ";$libJs;return __utils__.waitForReady($maxRound, $initialScroll);"
                val r = documentWait.until { (it as? JavascriptExecutor)?.executeScript(js) }

                if (r == "timeout") {
                    log.debug("Hit max round $maxRound to wait for document | {}", url)
                } else {
                    log.trace("Document is ready. {} | {}", r, url)
                }
            } catch (e: org.openqa.selenium.TimeoutException) {
                log.trace("Timeout to wait for document ready, timeout {}s | {}", pageLoadTimeout, url)
                status = ProtocolStatus.failed(ProtocolStatusCodes.DOCUMENT_READY_TIMEOUT)
            }

            performScrollDown(driver, driverConfig)
        } catch (e: InterruptedException) {
            log.warn("Waiting for document interrupted | {}", url)
            Thread.currentThread().interrupt()
            status = ProtocolStatus.STATUS_CANCELED
        } catch (e: WebDriverException) {
            if (e.cause is org.apache.http.conn.HttpHostConnectException) {
                // Web driver closed
                // status = ProtocolStatus.failed(ProtocolStatus.WEB_DRIVER_GONE, e)
            } else if (e.cause is InterruptedException) {
                // Web driver closed
            } else {
                // log.warn("Web driver exception | {} \n>>>\n{}\n<<<", url, e.message)
            }
            throw e
        } catch (e: Exception) {
            log.warn("Unexpected exception | {}", url)
            log.warn(StringUtil.stringifyException(e))
            throw e
        }

        val result = jsExecutor.executeScript(clientJs)
        if (result is String) {
            val jsData = browserDataGson.fromJson(result, BrowserJsData::class.java)
            if (log.isDebugEnabled) {
                log.debug("{} | {}", jsData, url)
            }

            return VisitResult(status, jsData)
        }

        return VisitResult(status, BrowserJsData.default)
    }

    private fun performScrollDown(driver: WebDriver, driverConfig: DriverConfig) {
        val scrollDownCount = driverConfig.scrollDownCount.toLong()
        val scrollDownWait = driverConfig.scrollInterval
        val timeout = scrollDownCount * scrollDownWait.toMillis() + 3 * 1000
        val scrollWait = FluentWait<WebDriver>(driver)
                .withTimeout(timeout, TimeUnit.MILLISECONDS)
                .pollingEvery(scrollDownWait.toMillis(), TimeUnit.MILLISECONDS)
                .ignoring(org.openqa.selenium.TimeoutException::class.java)

        try {
            // TODO: which one is the better? browser side timer or selenium side timer?
            val js = ";$libJs;return __utils__.scrollDownN($scrollDownCount);"
            scrollWait.until { (it as? JavascriptExecutor)?.executeScript(js) }
        } catch (e: org.openqa.selenium.TimeoutException) {
            // ignore
        }
    }

    /**
     * Perform click on the selected element and wait for the new page location
     * */
    private fun performJsClick(selector: String, driver: ManagedWebDriver, driverConfig: DriverConfig): String {
        val timeout = driverConfig.pageLoadTimeout
        val scrollWait = FluentWait<WebDriver>(driver.driver)
                .withTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .pollingEvery(1000, TimeUnit.MILLISECONDS)
                .ignoring(org.openqa.selenium.TimeoutException::class.java)

        try {
            // TODO: which one is the better? browser side timer or selenium side timer?
            val js = ";$libJs;return __utils__.navigateTo($selector);"
            val location = scrollWait.until { (it as? JavascriptExecutor)?.executeScript(js) }
            if (location is String) {
                return location
            }
        } catch (e: org.openqa.selenium.TimeoutException) {
            // ignore
        }

        return ""
    }

    private fun getPageSourceSilently(driver: ManagedWebDriver): String {
        return try { driver.driver.pageSource } catch (e: Throwable) { "" }
    }

    private fun handleFetchFinish(page: WebPage, driver: ManagedWebDriver, headers: MultiMetadata) {
        // The page content's encoding is already converted to UTF-8 by Web driver
        headers.put(CONTENT_ENCODING, "UTF-8")
        headers.put(Q_TRUSTED_CONTENT_ENCODING, "UTF-8")
        headers.put(Q_RESPONSE_TIME, System.currentTimeMillis().toString())
        headers.put(Q_WEB_DRIVER, driver.javaClass.name)

        when (driver.driver) {
            is ChromeDriver -> page.lastBrowser = BrowserType.CHROME
            is HtmlUnitDriver -> page.lastBrowser = BrowserType.HTMLUNIT
            else -> {
                log.warn("Actual browser is set to be NATIVE by selenium engine")
                page.lastBrowser = BrowserType.NATIVE
            }
        }

        tryRefreshCookie(page, driver)
    }

    private fun tryRefreshCookie(page: WebPage, driver: ManagedWebDriver) {
        val url = page.url
        val host = URLUtil.getHost(url)
        val stat = fetchTaskTracker.hostStatistics.computeIfAbsent(host) { FetchStatus(it) }
        if (stat.cookieView > maxCookieView) {
            // log.info("Delete all cookies under {} after {} tasks", URLUtil.getDomainName(url), stat.cookieView)
            // driver.manage().deleteAllCookies()
            stat.cookieView = 0
        }
    }

    private fun handleFetchSuccess(batchId: Int) {
        val t = fetchTaskTracker
        t.batchSuccessCounters.computeIfAbsent(batchId) { AtomicInteger() }.incrementAndGet()
        t.totalSuccessCount.incrementAndGet()

        // TODO: A metrics system is required
        if (t.totalTaskCount.get() % 20 == 0) {
            log.debug("Selenium task success: {}/{}, total task success: {}/{}",
                    t.batchSuccessCounters[batchId], t.batchTaskCounters[batchId],
                    t.totalSuccessCount,
                    t.totalTaskCount
            )
        }
    }

    private fun takeScreenshot(contentLength: Int, page: WebPage, driver: RemoteWebDriver) {
        if (RemoteWebDriver::class.java.isAssignableFrom(driver.javaClass)) {
            try {
                if (contentLength > 100) {
                    val bytes = driver.getScreenshotAs(OutputType.BYTES)
                    export(page, bytes, ".png")
                }
            } catch (e: Exception) {
                log.warn("Cannot take screenshot, page length {} | {}", contentLength, page.url)
            }
        }
    }

    private fun handlePageSource(pageSource: String, status: ProtocolStatus, page: WebPage, driver: ManagedWebDriver): String {
        if (pageSource.isEmpty()) {
            return ""
        }

        // take the head part and replace charset to UTF-8
        val pos = pageSource.indexOf("</head>")
        var head = pageSource.take(pos)
        // TODO: can still faster
        // Some parsers use html directive to decide the content's encoding, correct it to be UTF-8
        head = charsetPattern.matcher(head).replaceAll("UTF-8")

        // append the rest
        val sb = StringBuilder(head)
        var i = pos
        while (i < pageSource.length) {
            sb.append(pageSource[i])
            ++i
        }

        val content = sb.toString()

        if (log.isDebugEnabled && content.isNotEmpty()) {
            export(sb, status, content, page)

            if (log.isTraceEnabled) {
                takeScreenshot(content.length, page, driver.driver as RemoteWebDriver)
            }
        }

        return content
    }

    private fun export(sb: StringBuilder, status: ProtocolStatus, content: String, page: WebPage) {
        val document = Documents.parse(content, page.baseUrl)
        document.absoluteLinks()
        val prettyHtml = document.prettyHtml

        sb.setLength(0)
        sb.append(status.minorName).append('/').append(monthDay)
        if (prettyHtml.length < 2000) {
            sb.append("/a").append(prettyHtml.length / 500 * 500)
        } else {
            sb.append("/b").append(prettyHtml.length / 20000 * 20000)
        }

        val ident = sb.toString()
        val path = export(page, prettyHtml.toByteArray(), ident)

        page.metadata.set(Name.ORIGINAL_EXPORT_PATH, path.toString())
    }

    private fun handleWebDriverTimeout(url: String, startTime: Long, pageSource: String, driverConfig: DriverConfig) {
        val elapsed = Duration.ofMillis(System.currentTimeMillis() - startTime)
        if (log.isDebugEnabled) {
            log.debug("Selenium timeout,  elapsed {} length {} drivers: {}/{}/{} timeouts: {}/{}/{} | {}",
                    elapsed, String.format("%,7d", pageSource.length),
                    driverManager.workingSize, driverManager.freeSize, driverManager.totalSize,
                    driverConfig.pageLoadTimeout, driverConfig.scriptTimeout, driverConfig.scrollInterval,
                    url
            )
        } else {
            log.warn("Selenium timeout, elapsed: {} length: {} | {}", elapsed, String.format("%,7d", pageSource.length), url)
        }
    }

    private fun getDriverConfig(priority: Int, page: WebPage, config: ImmutableConfig): DriverConfig {
        // Page load timeout
        val pageLoadTimeout = config.getDuration(FETCH_PAGE_LOAD_TIMEOUT, defaultDriverConfig.pageLoadTimeout)
        // Script timeout
        val scriptTimeout = config.getDuration(FETCH_SCRIPT_TIMEOUT, defaultDriverConfig.scriptTimeout)
        // Scrolling
        var scrollDownCount = config.getInt(FETCH_SCROLL_DOWN_COUNT, defaultDriverConfig.scrollDownCount)
        if (scrollDownCount > 20) {
            scrollDownCount = 20
        }
        var scrollDownWait = config.getDuration(FETCH_SCROLL_DOWN_INTERVAL, defaultDriverConfig.scrollInterval)
        if (scrollDownWait > pageLoadTimeout) {
            scrollDownWait = pageLoadTimeout
        }

        // TODO: handle proxy

        return DriverConfig(pageLoadTimeout, scriptTimeout, scrollDownCount, scrollDownWait)
    }

    private fun export(page: WebPage, content: ByteArray, ident: String = "", suffix: String = ".htm"): Path {
        val browser = page.lastBrowser.name.toLowerCase()

        val u = Urls.getURLOrNull(page.url)?: return PulsarPaths.TMP_DIR
        val domain = if (StringUtil.isIpPortLike(u.host)) u.host else InternetDomainName.from(u.host).topPrivateDomain().toString()
        val filename = ident + "-" + DigestUtils.md5Hex(page.url) + suffix
        val path = PulsarPaths.get(PulsarPaths.WEB_CACHE_DIR.toString(), "original", browser, domain, filename)
        PulsarFiles.saveTo(content, path, true)
        return path
    }

    override fun close() {
        if (closed.getAndSet(true)) {
            return
        }
    }

    companion object {
        private var instanceCount = AtomicInteger()
        val defaultSupportedCharsets = "UTF-8|GB2312|GB18030|GBK|Big5|ISO-8859-1" +
                "|windows-1250|windows-1251|windows-1252|windows-1253|windows-1254|windows-1257"
        val systemAvailableCharsets = Charset.availableCharsets().values.joinToString("|") { it.name() }
        // All charsets are supported by the system
        // The set is big, can use a static cache to hold them if necessary
        val defaultCharsetPattern = defaultSupportedCharsets.replace("UTF-8\\|?", "").toPattern(CASE_INSENSITIVE)
        val systemAvailableCharsetPattern = systemAvailableCharsets.replace("UTF-8\\|?", "").toPattern(CASE_INSENSITIVE)
    }
}
