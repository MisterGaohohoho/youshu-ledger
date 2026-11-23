package net.ankio.ledger

import android.app.UiAutomation
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.ankio.ledger.capture.RecognitionBanner
import net.ankio.ledger.capture.ScreenCaptureService
import net.ankio.ledger.capture.captureServiceComponent
import net.ankio.ledger.core.*
import net.ankio.ledger.data.LedgerAccount
import net.ankio.ledger.data.LedgerRecord
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exercise the real accessibility window, keyboard and store with synthetic bills only. */
@RunWith(AndroidJUnit4::class)
class RecognitionEditingUiTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=ApplicationProvider.getApplicationContext<LedgerApp>()
    private fun await(timeout:Long=8_000,test:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+timeout
        while(!test() && SystemClock.elapsedRealtime()<until)SystemClock.sleep(30)
        assertTrue("Timed out waiting for the recognition result",test())
    }
    private fun root(banner:RecognitionBanner)=RecognitionBanner::class.java.getDeclaredField("view").apply {isAccessible=true}.get(banner) as View
    private fun <T:View> control(banner:RecognitionBanner,tag:String):T=root(banner).findViewWithTag(tag)
    private fun editing(banner:RecognitionBanner)=banner.diagnostics().getString("editing").isNotEmpty()
    private fun click(banner:RecognitionBanner,tag:String) {instrument.runOnMainSync {control<View>(banner,tag).performClick()}}
    private fun fill(banner:RecognitionBanner,value:String) {instrument.runOnMainSync {control<EditText>(banner,"result-editor-input").setText(value)}}
    private fun show(banner:RecognitionBanner,record:LedgerRecord) {
        instrument.runOnMainSync {banner.show("已记账 ¥12.34",recordId=record.id)}
        await {banner.diagnostics().getBoolean("attached") && root(banner).width>0 && root(banner).hasWindowFocus()}
    }
    private fun capture(automation:UiAutomation,name:String) {
        instrument.waitForIdleSync()
        val bitmap=requireNotNull(automation.takeScreenshot())
        val uri=requireNotNull(app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,"image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH,"${Environment.DIRECTORY_PICTURES}/recognition-editing-qa");put(MediaStore.MediaColumns.IS_PENDING,1)
        }))
        try {
            app.contentResolver.openOutputStream(uri)!!.use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
            app.contentResolver.update(uri,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
        } finally {bitmap.recycle()}
    }
    private fun withResult(body:(RecognitionBanner,LedgerRecord,UiAutomation)->Unit) {
        assumeTrue(app.packageName=="net.ankio.ledger.qa")
        val automation=instrument.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver=app.contentResolver
        val oldServices=Settings.Secure.getString(resolver,"enabled_accessibility_services")
        val oldEnabled=Settings.Secure.getInt(resolver,"accessibility_enabled",0)
        val oldKeyboard=Settings.Secure.getString(resolver,"show_ime_with_hard_keyboard")
        val account=app.store.saveAccount(LedgerAccount(name="合成微信钱包",kind="wechat",amountCents=10000))
        val now=System.currentTimeMillis()
        val record=app.store.saveEdited(LedgerRecord(capture=Capture(Source.OCR,PaymentApps.WECHAT,"合成测试原文 ${UUID.randomUUID()}",now),
            bill=ParsedBill(1234,Direction.EXPENSE,"合成测试商户（较长识别名称）","餐饮",now,"SYNTHETIC-${UUID.randomUUID()}",true,"",true),state=RecordState.POSTED),account.uid,true)
        var banner:RecognitionBanner?=null
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            Settings.Secure.putString(resolver,"enabled_accessibility_services",captureServiceComponent(app).flattenToString())
            Settings.Secure.putInt(resolver,"accessibility_enabled",1)
            Settings.Secure.putInt(resolver,"show_ime_with_hard_keyboard",1)
            await {ScreenCaptureService.current!=null}
            ActivityScenario.launch(MainActivity::class.java).use {scenario ->
                val web=WebUiProbe(scenario);web.ready();web.run("go('settings')")
                instrument.runOnMainSync {banner=RecognitionBanner(ScreenCaptureService.current!!)}
                show(banner!!,record)
                try {body(banner!!,record,automation)} catch(error:Throwable) {runCatching {capture(automation,"editing-failure-${System.currentTimeMillis()}.png")};throw error}
                finally {instrument.runOnMainSync {banner?.removeNow()}}
            }
        } finally {
            instrument.runOnMainSync {banner?.removeNow()}
            Settings.Secure.putString(resolver,"enabled_accessibility_services",oldServices)
            Settings.Secure.putInt(resolver,"accessibility_enabled",oldEnabled)
            Settings.Secure.putString(resolver,"show_ime_with_hard_keyboard",oldKeyboard)
            automation.dropShellPermissionIdentity()
            app.store.delete(record.id);app.store.deleteAccount(account.id);app.changed()
        }
    }

    @Test fun inlineEditsSaveCancelPauseExpiryAndPreserveInputOnOutsideAndBack()=withResult {banner,record,automation ->
        val density=app.resources.displayMetrics.density
        instrument.runOnMainSync {
            val toggle=control<View>(banner,"result-reimbursement");val account=control<View>(banner,"result-account")
            assertEquals(toggle.top,account.top)
            for(view in listOf(toggle,account,control<View>(banner,"result-merchant"),control<View>(banner,"result-note")))assertTrue(view.height/density>=48)
        }
        capture(automation,"result-default.png")
        click(banner,"result-merchant");assertEquals(record.merchantName,control<EditText>(banner,"result-editor-input").text.toString())
        fill(banner,"   ");click(banner,"result-editor-save")
        assertTrue(editing(banner));assertEquals(View.VISIBLE,control<View>(banner,"result-editor-error").visibility)
        fill(banner,"合成菜场")
        capture(automation,"result-edit-name.png")
        click(banner,"result-editor-save")
        assertFalse(editing(banner));assertEquals("合成菜场",app.store.get(record.id)!!.merchantName)
        assertEquals(record.bill,app.store.get(record.id)!!.bill);assertEquals(record.capture,app.store.get(record.id)!!.capture)
        assertEquals("合成菜场",control<TextView>(banner,"result-merchant-name").text.toString())
        click(banner,"result-note");fill(banner,"晚餐买菜\n已核对")
        capture(automation,"result-edit-note.png")
        click(banner,"result-editor-save")
        assertEquals("晚餐买菜\n已核对",app.store.get(record.id)!!.note)
        click(banner,"result-note");fill(banner,"未保存草稿")
        instrument.runOnMainSync {
            val outside=MotionEvent.obtain(0,SystemClock.uptimeMillis(),MotionEvent.ACTION_OUTSIDE,-10f,-10f,0)
            try {root(banner).dispatchTouchEvent(outside)} finally {outside.recycle()}
            root(banner).dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK))
            assertEquals("未保存草稿",control<EditText>(banner,"result-editor-input").text.toString())
            assertEquals(Long.MAX_VALUE,RecognitionBanner::class.java.getDeclaredField("resultDeadline").apply {isAccessible=true}.getLong(banner))
            assertNull(RecognitionBanner::class.java.getDeclaredField("hide").apply {isAccessible=true}.get(banner))
        }
        SystemClock.sleep(5_250)
        assertTrue(banner.diagnostics().getBoolean("attached"));assertTrue(editing(banner))
        click(banner,"result-editor-cancel")
        assertEquals("晚餐买菜\n已核对",app.store.get(record.id)!!.note)
        click(banner,"result-note");fill(banner,"字".repeat(501))
        assertEquals(500,control<EditText>(banner,"result-editor-input").length())
        assertEquals("500 / 500",control<TextView>(banner,"result-note-count").text.toString())
        click(banner,"result-editor-save");assertEquals(500,app.store.get(record.id)!!.note.length)
        click(banner,"result-note");fill(banner,"");click(banner,"result-editor-save")
        assertEquals("",app.store.get(record.id)!!.note);assertEquals("添加备注",control<TextView>(banner,"result-note").text.toString())
        assertEquals(record.accountEffect,app.store.get(record.id)!!.accountEffect)
        click(banner,"result-reimbursement");assertTrue(app.store.get(record.id)!!.requiresReimbursement)
        click(banner,"result-account");assertTrue(banner.diagnostics().getBoolean("accountPanelOpen"))
        click(banner,"result-merchant");assertFalse(banner.diagnostics().getBoolean("accountPanelOpen"));assertTrue(editing(banner))
        click(banner,"result-editor-cancel")
        await(7_000) {!banner.diagnostics().getBoolean("attached")}
    }

    @Test fun retiredAndDeletedResultsCannotSaveStaleDrafts()=withResult {banner,record,_ ->
        click(banner,"result-note");fill(banner,"过时草稿")
        val oldSave=control<View>(banner,"result-editor-save")
        instrument.runOnMainSync {banner.show("正在识别…",loading=true);oldSave.performClick()}
        instrument.waitForIdleSync();assertEquals("",app.store.get(record.id)!!.note)
        show(banner,record)
        instrument.runOnMainSync {oldSave.performClick()}
        assertEquals("",app.store.get(record.id)!!.note)
        click(banner,"result-note");fill(banner,"删除后的草稿")
        app.store.delete(record.id);click(banner,"result-editor-save")
        assertNull(app.store.get(record.id));assertFalse(banner.diagnostics().getBoolean("attached"))
    }

    @Test fun keyboardKeepsSaveAndCancelReachable()=withResult {banner,_,automation ->
        click(banner,"result-note");fill(banner,"多行备注\n第二行\n第三行")
        await {control<EditText>(banner,"result-editor-input").hasFocus() && root(banner).rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true}
        val scroll=control<ScrollView>(banner,"result-scroll")
        instrument.runOnMainSync {scroll.fullScroll(View.FOCUS_DOWN)}
        instrument.waitForIdleSync()
        val positions=mutableListOf<Rect>()
        instrument.runOnMainSync {
            for(tag in listOf("result-editor-save","result-editor-cancel")) {
                val control=control<View>(banner,tag);val visible=Rect()
                assertTrue("$tag must be visible above the keyboard",control.getGlobalVisibleRect(visible))
                assertEquals("$tag must not be clipped",control.height,visible.height());positions+=visible
            }
            assertFalse(Rect.intersects(positions[0],positions[1]))
            val metrics=app.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics
            val ime=root(banner).rootWindowInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom
            assertTrue(positions.all {it.bottom<=metrics.bounds.height()-ime})
        }
        capture(automation,"result-note-keyboard-${app.resources.configuration.fontScale}.png")
        click(banner,"result-editor-cancel")
    }
}
