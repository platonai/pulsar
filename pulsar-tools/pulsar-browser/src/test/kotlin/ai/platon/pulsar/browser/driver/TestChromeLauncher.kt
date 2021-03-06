package ai.platon.pulsar.browser.driver

import ai.platon.pulsar.browser.driver.chrome.ChromeDevtoolsOptions
import ai.platon.pulsar.browser.driver.chrome.ChromeLauncher
import ai.platon.pulsar.browser.driver.chrome.LauncherConfig
import ai.platon.pulsar.common.AppPaths
import com.google.gson.Gson
import org.junit.Ignore
import org.junit.Test
import java.time.LocalDateTime
import kotlin.test.assertTrue

class TestChromeLauncher {

    @Ignore("Temporary disabled")
    @Test
    fun testLauncher() {
        val launchOptions = ChromeDevtoolsOptions()
        launchOptions.headless = false
        launchOptions.userDataDir = AppPaths.CHROME_TMP_DIR.resolve(LocalDateTime.now().second.toString())
        val launcher = ChromeLauncher(config = LauncherConfig())
        val chrome = launcher.launch(launchOptions)
        val tab = chrome.createTab("https://www.baidu.com")
        val versionString = Gson().toJson(chrome.version)
        assertTrue(!chrome.version.browser.isNullOrBlank())
        assertTrue(versionString.contains("Mozilla"))
        // println(Gson().toJson(chrome.version))
        // assertEquals("百度", tab.title)
    }
}
