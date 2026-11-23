package net.ankio.ledger.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ResultMetadataTest {
    private val at=1_793_800_000_000L
    private fun withStore(test:(LedgerStore,Context,String)->Unit) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="result-metadata-${UUID.randomUUID()}.db"
        val store=LedgerStore(context,name)
        try {test(store,context,name)} finally {store.close();context.deleteDatabase(name)}
    }
    private fun bill(time:Long=at)=LedgerRecord(capture=Capture(Source.OCR,PaymentApps.WECHAT,"合成识别原文，保留支付依据",time),
        bill=ParsedBill(1234,Direction.EXPENSE,"合成长名称商户","餐饮",time,UUID.randomUUID().toString(),true,"",true),state=RecordState.POSTED)

    @Test fun narrowEditsPreserveOtherMetadataIdentitiesAndAccountBalances()=withStore {store,_,_ ->
        val first=store.saveAccount(LedgerAccount(name="合成钱包甲",kind="cash",amountCents=10000))
        val second=store.saveAccount(LedgerAccount(name="合成钱包乙",kind="wechat",amountCents=20000))
        store.setDefaultAccount(first.uid)
        val original=store.saveEdited(bill())
        val history=store.captureHistory(original.id);val balances=store.accounts()
        val renamed=store.setRecordDisplayName(original.id,original.uid,"  合成菜场  ",original.merchantName)
        assertEquals(original.copy(displayName="合成菜场"),renamed)
        assertEquals(balances,store.accounts());assertEquals(history,store.captureHistory(original.id))
        store.setRecordAccount(original.id,original.uid,second.uid)
        val latest=store.setRequiresReimbursement(original.id,original.uid,true)
        val movedBalances=store.accounts()
        val noted=store.setRecordNote(original.id,original.uid,"晚餐买菜\n已核对",original.note)
        assertEquals(latest.copy(note="晚餐买菜\n已核对"),noted)
        assertEquals(movedBalances,store.accounts());assertEquals(original.capture,noted.capture);assertEquals(original.identities,noted.identities)
        assertEquals(history,store.captureHistory(original.id))
        val another=store.saveEdited(bill(at+86_400_000))
        assertEquals("合成长名称商户",another.merchantName);assertNull(another.displayName)
        val cleared=store.setRecordNote(original.id,original.uid,"",noted.note)
        assertEquals("",cleared.note)
        val reset=store.setRecordDisplayName(original.id,original.uid,original.bill.merchant,cleared.merchantName)
        assertNull(reset.displayName);assertEquals(original.bill.merchant,reset.merchantName)
    }

    @Test fun aliasesSurviveAutomaticIdentityCompletionDuplicatesAndRefunds()=withStore {store,_,_ ->
        val originalBill=bill().bill.copy(transactionId=null,occurredAt=at+15_000,timeExplicit=false,reason="已识别即时支付成功页；页面未显示交易时间，使用本机捕获时间")
        val original=(store.ingest(Capture(Source.OCR,PaymentApps.WECHAT,"支付成功",at+15_000),originalBill) as IngestResult.Saved).record
        store.setRecordDisplayName(original.id,original.uid,"合成快捷名称",original.merchantName)
        store.setRecordNote(original.id,original.uid,"保留快捷备注","")
        val explicit=originalBill.copy(transactionId="SYNTHETIC-ORDER",occurredAt=at,timeExplicit=true,reason="")
        val captured=Capture(Source.OCR,PaymentApps.WECHAT,"账单详情",at+3_600_000)
        val completed=(store.ingest(captured,explicit) as IngestResult.Saved).record
        assertEquals(original.id,completed.id);assertEquals("合成快捷名称",completed.merchantName)
        assertEquals("合成长名称商户",completed.bill.merchant);assertEquals("保留快捷备注",completed.note)
        store.ingest(captured.copy(receivedAt=at+4_000_000),explicit)
        assertEquals(1,store.all().size)
        val refund=explicit.copy(amountCents=1000,refund=RefundInfo("wechat",1234,234))
        val refunded=(store.ingest(captured.copy(text="已部分退款",receivedAt=at+8_000_000),refund) as IngestResult.Saved).record
        assertEquals(original.id,refunded.id);assertEquals(1000L,refunded.bill.amountCents)
        assertEquals("合成快捷名称",refunded.merchantName);assertEquals("保留快捷备注",refunded.note);assertEquals(1,store.all().size)
    }

    @Test fun staleDeletedInvalidAndConflictingEditsCannotOverwriteARecord()=withStore {store,_,_ ->
        val original=store.saveEdited(bill())
        for(name in listOf(" ","长".repeat(1025),"两行\n名称"))assertTrue(runCatching {store.setRecordDisplayName(original.id,original.uid,name,original.merchantName)}.isFailure)
        assertTrue(runCatching {store.setRecordNote(original.id,original.uid,"字".repeat(501),"")}.isFailure)
        assertTrue(runCatching {store.setRecordNote(original.id,UUID.randomUUID().toString(),"错误标识","")}.isFailure)
        assertEquals(original,store.get(original.id))
        val edited=store.setRecordNote(original.id,original.uid,"新备注","")
        assertTrue(runCatching {store.setRecordNote(original.id,original.uid,"过时输入","")}.isFailure)
        assertTrue(runCatching {store.setRecordDisplayName(original.id,original.uid,"过时名称","另一名称")}.isFailure)
        assertEquals(edited,store.get(original.id))
        store.delete(original.id)
        assertTrue(runCatching {store.setRecordNote(original.id,original.uid,"删除后修改","新备注")}.isFailure)
        assertTrue(runCatching {store.setRecordDisplayName(original.id,original.uid,"删除后改名",original.merchantName)}.isFailure)
        assertNull(store.get(original.id))
        assertTrue(store.undoDelete(original.id));assertEquals(edited,store.get(original.id))
    }

    @Test fun listSearchReportsExportsAndBackupUseTheDisplayName()=withStore {store,context,_ ->
        val account=store.saveAccount(LedgerAccount(name="合成导出钱包",kind="cash",amountCents=10000))
        store.setDefaultAccount(account.uid)
        val original=store.saveEdited(bill())
        val alias="合成别名"
        val renamed=store.setRecordDisplayName(original.id,original.uid,alias,original.merchantName)
        for(search in listOf(alias,original.bill.merchant)) {
            val rows=LedgerReadView(store).records("all",search).getJSONArray("records")
            assertEquals(1,rows.length());assertEquals(alias,rows.getJSONObject(0).getString("merchant"))
            assertEquals(alias,OfflineAnalytics(store).details(JSONObject().put("search",search)).getJSONArray("records").getJSONObject(0).getString("merchant"))
        }
        assertEquals(alias,AccountDetailReadView(store).query(account.uid,"all").getJSONArray("rows").getJSONObject(0).getString("merchant"))
        val date=Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        assertEquals(alias,BillReports(store).generate(BillReport.Criteria(date,date)).rows.single().merchant)
        val csv=ByteArrayOutputStream().also {LedgerCsvExport.write(store.all(),LedgerCsvExport.Format.GENERAL,it)}.toString("UTF-8")
        assertTrue(csv.contains(alias));assertFalse(csv.contains(original.bill.merchant))
        val backup=LedgerBackup.encode(store.all(),ChannelSettings(context),store.accounts())
        val decoded=LedgerBackup.decode(backup.byteInputStream()).records.single()
        assertEquals(alias,decoded.merchantName);assertEquals(renamed.bill,decoded.bill);assertEquals(renamed.capture,decoded.capture)
        val legacy=JSONObject(backup).apply {getJSONArray("records").getJSONObject(0).remove("displayName")}
        assertNull(LedgerBackup.decode(legacy.toString().byteInputStream()).records.single().displayName)
        withStore {target,_,_ ->
            target.merge(listOf(decoded),store.accounts())
            assertEquals(alias,target.all().single().merchantName)
        }
    }

    @Test fun version17UpgradeAddsNullablePresentationOnlyMetadata()=withStore {store,context,name ->
        val original=store.saveEdited(bill());val history=store.captureHistory(original.id)
        store.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use {db ->
            val schema=db.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='records'",null).use {it.moveToFirst();it.getString(0)}
            val columns=db.rawQuery("PRAGMA table_info(records)",null).use {c ->buildList {while(c.moveToNext())if(c.getString(1)!="display_name")add(c.getString(1))}}.joinToString(",")
            val indexes=db.rawQuery("SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name='records' AND sql IS NOT NULL",null).use {c ->buildList {while(c.moveToNext())add(c.getString(0))}}
            db.beginTransaction()
            try {
                db.execSQL(schema.replaceFirst("CREATE TABLE records","CREATE TABLE legacy_records").replace("display_name TEXT,", ""))
                db.execSQL("INSERT INTO legacy_records($columns) SELECT $columns FROM records")
                db.execSQL("DROP TABLE records");db.execSQL("ALTER TABLE legacy_records RENAME TO records")
                indexes.forEach(db::execSQL);db.version=17;db.setTransactionSuccessful()
            } finally {db.endTransaction()}
        }
        LedgerStore(context,name).use {upgraded ->
            assertEquals(18,upgraded.readableDatabase.version);assertEquals(original,upgraded.get(original.id))
            assertEquals(history,upgraded.captureHistory(original.id))
            assertEquals("迁移后别名",upgraded.setRecordDisplayName(original.id,original.uid,"迁移后别名",original.merchantName).merchantName)
        }
    }
}
