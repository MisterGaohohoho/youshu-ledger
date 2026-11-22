package net.ankio.ledger

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.data.*
import net.ankio.ledger.ui.MainActivity
import net.ankio.ledger.ui.MintDialog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real document parsing, native preview, confirmation and the persisted accounting choice. */
@RunWith(AndroidJUnit4::class)
class PlatformImportAccountsUiTest {
    private val app=ApplicationProvider.getApplicationContext<LedgerApp>()
    private fun views(view:View):List<View> = listOf(view)+(if(view is ViewGroup)(0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList())
    private fun awaitValue(test:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+10_000
        while(!test() && SystemClock.elapsedRealtime()<end)SystemClock.sleep(40)
        assertTrue("Timed out waiting for the import",test())
    }
    private fun screenshot(name:String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val bitmap=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        // Connected tests uninstall the app before adb collects artifacts. Shared test media survives that cleanup.
        val uri=requireNotNull(app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,"image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH,"${Environment.DIRECTORY_PICTURES}/platform-import-qa")
            put(MediaStore.MediaColumns.IS_PENDING,1)
        }))
        try {
            requireNotNull(app.contentResolver.openOutputStream(uri)).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
            app.contentResolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)
        }
        finally { bitmap.recycle() }
    }
    private fun flow(method:String,test:(ActivityScenario<MainActivity>,MintDialog,LedgerAccount,String)->Unit) {
        val marker="UI-${UUID.randomUUID()}"
        val before=app.store.channelDefaultAccounts()["wechat"]
        val wallet=app.store.saveAccount(LedgerAccount(name="合成微信-${marker.takeLast(6)}",kind="wechat",amountCents=100_000))
        app.store.setChannelDefaultAccount("wechat",wallet.uid)
        val uri=requireNotNull(app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,"synthetic-platform-$marker.csv");put(MediaStore.MediaColumns.MIME_TYPE,"text/csv")
        }))
        try {
            val csv="交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注\n"+
                "2026-11-21 12:00:00,餐饮,合成测试商户,午餐,支出,12.34,$method,支付成功,$marker,SHOP-$marker,合成测试\n"
            app.contentResolver.openOutputStream(uri)!!.use { it.write(csv.toByteArray()) }
            app.changed()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val web=WebUiProbe(scenario);web.ready()
                scenario.onActivity { it.platformImports.read(uri,"auto") }
                var sheet:MintDialog?=null
                awaitValue { scenario.onActivity { sheet=it.platformImports.previewSheet };sheet!=null }
                assertFalse(app.store.all().any { it.bill.transactionId==marker })
                test(scenario,requireNotNull(sheet),wallet,marker)
            }
        } finally {
            synchronized(app.store) {
                app.store.all().filter { it.bill.transactionId==marker }.forEach { app.store.delete(it.id) }
                app.store.setChannelDefaultAccount("wechat",before)
                app.store.deleteAccount(wallet.id)
            }
            app.changed();app.contentResolver.delete(uri,null,null)
        }
    }

    @Test fun autoSelectedWalletDefaultsToKeepingBalanceAndOnlyCommitsOnConfirmation()=flow("零钱") { scenario,sheet,wallet,marker ->
        scenario.onActivity {
            val children=views(sheet.content)
            assertEquals(wallet.name,children.filterIsInstance<Spinner>().single().selectedItem)
            assertTrue(children.filterIsInstance<RadioButton>().single { it.tag=="platform-import-keep-balance" }.isChecked)
            assertFalse(children.filterIsInstance<CheckBox>().single().isChecked)
        }
        screenshot("wallet-default.png")
        scenario.onActivity {
            val button=views(sheet.dialog.window!!.decorView).filterIsInstance<Button>().single { it.text.toString()=="确认导入" }
            assertTrue(button.isShown);button.performClick()
        }
        awaitValue { app.store.all().any { it.bill.transactionId==marker } }
        val record=app.store.all().single { it.bill.transactionId==marker }
        assertEquals(wallet.uid,record.accountUid);assertFalse(record.affectsBalance)
        assertEquals(100_000L,app.store.getAccount(wallet.id)!!.amountCents)
        assertEquals(0L,record.accountEffect)
    }

    @Test fun bankGroupWaitsForAnExplicitChoiceAndCanRememberLinkedBalanceMode()=flow("工商银行储蓄卡(1234)") { scenario,sheet,wallet,marker ->
        scenario.onActivity {
            val confirm=views(sheet.dialog.window!!.decorView).filterIsInstance<Button>().single { it.text.toString()=="确认导入" }
            confirm.performClick()
            assertTrue(sheet.dialog.isShowing)
            assertFalse(app.store.all().any { record->record.bill.transactionId==marker })
        }
        screenshot("payment-method-review.png")
        scenario.onActivity {
            val children=views(sheet.content)
            val picker=children.filterIsInstance<Spinner>().single()
            val position=(0 until picker.count).single { picker.getItemAtPosition(it)==wallet.name }
            picker.setSelection(position)
            children.filterIsInstance<RadioButton>().single { it.tag=="platform-import-adjust-balance" }.performClick()
            children.filterIsInstance<CheckBox>().single().performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity {
            views(sheet.dialog.window!!.decorView).filterIsInstance<Button>().single { it.text.toString()=="确认导入" }.performClick()
        }
        awaitValue { app.store.all().any { it.bill.transactionId==marker } }
        val record=app.store.all().single { it.bill.transactionId==marker }
        assertEquals(wallet.uid,record.accountUid);assertTrue(record.affectsBalance)
        assertEquals(98_766L,app.store.getAccount(wallet.id)!!.amountCents)
        assertTrue(PlatformImportAccounts.preferences(app.store).any { it.method=="工商银行储蓄卡(1234)" && it.accountUid==wallet.uid })
    }
}
