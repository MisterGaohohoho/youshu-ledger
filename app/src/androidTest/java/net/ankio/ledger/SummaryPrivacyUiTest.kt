package net.ankio.ledger

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.data.DisplayPreferences
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SummaryPrivacyUiTest {
    @Test fun nativePreferenceSurvivesActivityAndWebViewRecreationAcrossHomeModes() {
        val app=ApplicationProvider.getApplicationContext<LedgerApp>()
        assumeTrue(app.packageName=="net.ankio.ledger.qa")
        val before=app.displayPreferences.summaryAmountsHidden()
        val budget=app.monthlyBudget.cents()
        try {
            app.displayPreferences.setSummaryAmountsHidden(false);app.monthlyBudget.set(765432);app.changed()
            ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                val web=WebUiProbe(scenario);web.ready()
                web.run("go('ledger');document.querySelector('#hide-summary').click()")
                web.await("summaryAmountsHidden && document.querySelector('.balance-number').textContent==='****'")
                assertTrue(DisplayPreferences(app).summaryAmountsHidden())
                for(mode in listOf("all","income","expense")) {
                    web.run("document.querySelector('[data-filter=\"$mode\"]').click()")
                    web.await("summaryAmountsHidden && document.querySelector('.balance-number').textContent==='****'")
                }
                scenario.recreate();web.ready()
                web.await("summaryAmountsHidden && document.querySelector('#hide-summary').getAttribute('aria-pressed')==='true'")
                assertEquals("月限额 ****",web.text("document.querySelector('.budget-label').textContent"))
                assertEquals("100",web.text("document.querySelector('.budget-meter').getAttribute('aria-valuemax')"))
            }
            ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                val web=WebUiProbe(scenario);web.ready()
                web.await("summaryAmountsHidden && document.querySelector('.balance-number').textContent==='****'")
                web.run("document.querySelector('#hide-summary').click()")
                web.await("!summaryAmountsHidden && document.querySelector('#hide-summary').getAttribute('aria-label')==='隐藏统计金额'")
                assertFalse(DisplayPreferences(app).summaryAmountsHidden())
                assertTrue(web.text("document.querySelector('.budget-label').textContent").contains("7,654.32"))
            }
        } finally {app.displayPreferences.setSummaryAmountsHidden(before);app.monthlyBudget.set(budget);app.changed()}
    }
}
