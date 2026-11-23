package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ImportBatchesTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val stores=mutableListOf<Pair<String,LedgerStore>>()
    private val at=1_790_000_000_000L
    private fun store():LedgerStore { val name="imports-${UUID.randomUUID()}.db";return LedgerStore(context,name).also { stores+=name to it } }
    @After fun cleanup(){stores.forEach { (name,store)->store.purgeExpiredDeletes(Long.MAX_VALUE);store.all().forEach { store.delete(it.id) };store.close();context.deleteDatabase(name) }}
    private fun record(index:Int,account:String?=null)=LedgerRecord(capture=Capture(Source.MANUAL,"import:test","合成文件行 $index",at+index*300_000L),
        bill=ParsedBill(100,Direction.EXPENSE,"合成商户 $index","其他",at+index*300_000L,"IMPORT-$index",true,"",true),state=RecordState.POSTED,accountUid=account)
    private fun csv(rows:List<LedgerRecord>)=LedgerCsvImport.Document("通用 CSV",rows,emptyList(),rows.size)
    private fun batch(store:LedgerStore)=ImportBatches(store).list(0).getJSONArray("items").getJSONObject(0).getString("uid")
    private fun selection(uid:String)=JSONObject().put("importBatch",uid).put("all",true).put("ids",org.json.JSONArray()).put("excluded",org.json.JSONArray()).toString()

    @Test fun overlappingImportsKeepOwnershipAndBatchDeleteUsesAllPagesAndCurrentBalances(){
        val store=store();val account=store.saveAccount(LedgerAccount(name="合成账户",amountCents=100_000))
        val rows=(1..251).map { record(it,account.uid) }
        LedgerCsvImport.apply(store,csv(rows),"第一次.csv");val first=batch(store)
        LedgerCsvImport.apply(store,csv(listOf(rows.first(),record(999,account.uid))),"第二次.csv")
        val batches=ImportBatches(store)
        val second=batches.list(0).getJSONArray("items").let { items -> (0 until items.length()).map { items.getJSONObject(it) }.first { it.getString("fileName")=="第二次.csv" } }
        assertEquals(1,second.getInt("created"));assertEquals(1,second.getInt("duplicates"))
        assertEquals(rows.first().bill.occurredAt,second.getLong("from"))
        val edited=store.all().first { it.uid==rows.first().uid };store.saveEdited(edited.copy(bill=edited.bill.copy(amountCents=200)))
        val manager=LedgerBatchDeletion(store);val preview=manager.preview(selection(first))
        assertEquals(251,preview.getInt("count"));assertEquals(25200L,preview.getLong("expense"));assertEquals(1,preview.getInt("editedCount"))
        assertTrue(batches.detail(first,0).getBoolean("hasMore"))
        val balance=store.getAccount(account.id)!!.amountCents
        val deletion=manager.commit(preview.getString("token"))
        assertEquals(1,store.all().size);assertEquals(balance+25200,store.getAccount(account.id)!!.amountCents)
        assertEquals(0,batches.detail(first,0).getJSONObject("batch").getInt("remaining"))
        assertTrue(store.undoDeleteBatch(deletion.batchId!!));assertEquals(252,store.all().size)
        assertEquals(balance,store.getAccount(account.id)!!.amountCents)
        val stale=manager.preview(selection(first));store.saveEdited(store.get(edited.id)!!.copy(note="确认后修改"))
        try{manager.commit(stale.getString("token"));fail("Changed bill must require confirmation")}catch(_:IllegalArgumentException){}
    }

    @Test fun metadataFailureRollsBackImportAndAccounts(){
        val store=store();val account=store.saveAccount(LedgerAccount(name="合成账户",amountCents=1000))
        store.writableDatabase.execSQL("CREATE TRIGGER reject_import BEFORE INSERT ON import_batch_items BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
        try{LedgerCsvImport.apply(store,csv(listOf(record(1,account.uid))),"失败.csv");fail("Expected rollback")}catch(_:android.database.sqlite.SQLiteException){}
        assertTrue(store.all().isEmpty());assertEquals(1000L,store.getAccount(account.id)!!.amountCents)
        assertEquals(0,ImportBatches(store).list(0).getJSONArray("items").length())
    }

    @Test fun reimportAfterPermanentDeletionBelongsOnlyToTheNewBatch(){
        val store=store();val row=record(1)
        LedgerCsvImport.apply(store,csv(listOf(row)),"旧批次.csv");val old=batch(store)
        val manager=LedgerBatchDeletion(store);manager.commit(manager.preview(selection(old)).getString("token"))
        store.purgeExpiredDeletes(Long.MAX_VALUE)
        LedgerCsvImport.apply(store,csv(listOf(row)),"重新导入.csv")
        assertEquals(1,store.all().size)
        assertEquals(0,manager.preview(selection(old)).getInt("count"))
        val items=ImportBatches(store).list(0).getJSONArray("items")
        val latest=(0 until items.length()).map { items.getJSONObject(it) }.first { it.getString("fileName")=="重新导入.csv" }
        assertEquals(1,manager.preview(selection(latest.getString("uid"))).getInt("count"))
    }

    @Test fun platformRefundUpdatesExistingBillWithoutTransferringItsOwnership(){
        val store=store()
        val original=record(1).copy(capture=Capture(Source.OCR,PaymentApps.WECHAT,"合成原付款",at))
        val saved=(store.ingest(original.capture,original.bill) as IngestResult.Saved).record
        val refund=original.bill.copy(amountCents=60,refund=RefundInfo("wechat",100,40))
        val next=record(2)
        val document=PlatformStatements.Document("wechat",listOf(
            PlatformStatements.Entry(Capture(Source.OCR,PaymentApps.WECHAT,"合成退款",at+10_000),refund,""),
            PlatformStatements.Entry(Capture(Source.OCR,PaymentApps.WECHAT,"合成新增",at+300_000),next.bill,"")
        ),0,emptyList())
        PlatformStatements.apply(store,document,"微信.xlsx")
        val b=ImportBatches(store).detail(batch(store),0).getJSONObject("batch")
        assertEquals(1,b.getInt("created"));assertEquals(1,b.getInt("updated"))
        val manager=LedgerBatchDeletion(store);manager.commit(manager.preview(selection(b.getString("uid"))).getString("token"))
        assertNotNull(store.get(saved.id));assertEquals(60L,store.get(saved.id)!!.bill.amountCents)
    }

    @Test fun jiezhangPendingRowsAndCreatedAccountsAreTracked(){
        val store=store();val original=record(1);store.merge(listOf(original))
        val imported=record(1).copy(uid=UUID.randomUUID().toString(),capture=original.capture.copy(origin="import:jiezhang"))
        val doc=UnifiedBillImport.Jiezhang(listOf(UnifiedBillImport.Item(imported,"测试现金")),emptyList())
        UnifiedBillImport.apply(store,doc,mapOf("测试现金" to "@new"),"洁账.xlsx")
        val b=ImportBatches(store).detail(batch(store),0).getJSONObject("batch")
        assertEquals(1,b.getInt("created"));assertEquals(1,b.getInt("review"))
        val manager=LedgerBatchDeletion(store);manager.commit(manager.preview(selection(b.getString("uid"))).getString("token"))
        assertEquals(1,store.accounts().size);assertEquals(original.uid,store.all().single().uid)
    }

    @Test fun backupRestoresBatchesWithoutClaimingExistingRowsAndOldBackupsRemainReadable(){
        val source=store();val existing=record(1);val added=record(2)
        LedgerCsvImport.apply(source,csv(listOf(existing,added)),"来源.csv")
        val encoded=LedgerBackup.encode(source,ChannelSettings(context))
        val old=JSONObject(encoded).apply { put("version",8);remove("importBatches") }
        assertEquals(0,LedgerBackup.decode(old.toString().byteInputStream()).importBatches.length())
        val target=store();target.merge(listOf(existing.copy(note="本地已有编辑")))
        val backup=LedgerBackup.decode(encoded.byteInputStream());assertEquals(1,LedgerBackup.restore(target,backup))
        assertEquals(1,ImportBatches(target).detail(batch(source),0).getJSONObject("batch").getInt("remaining"))
        assertEquals(0,LedgerBackup.restore(target,backup))
        val conflict=JSONObject(encoded)
        conflict.getJSONArray("importBatches").getJSONObject(0).put("fileName","不一致的来源.csv")
        try{LedgerBackup.restore(target,LedgerBackup.decode(conflict.toString().byteInputStream()));fail("Conflicting batch metadata must not overwrite local provenance")}catch(_:IllegalArgumentException){}
        assertEquals(2,target.all().size)
        val zip=ByteArrayOutputStream().also { LedgerArchive.write(source,ChannelSettings(context),it) }.toByteArray()
        val empty=store();LedgerArchive.read(zip.inputStream(),context.cacheDir).use { LedgerArchive.restore(empty,it) }
        assertEquals(2,ImportBatches(empty).detail(batch(source),0).getJSONObject("batch").getInt("remaining"))
        val bad=JSONObject(encoded);bad.getJSONArray("importBatches").getJSONObject(0).getJSONArray("items").getJSONObject(0).put("recordUid",UUID.randomUUID().toString())
        try{LedgerBackup.decode(bad.toString().byteInputStream());fail("Dangling provenance must fail")}catch(_:IllegalArgumentException){}
    }

    @Test fun migrationFrom15PreservesUnassignedOldBills(){
        val store=store();store.merge(listOf(record(1)))
        store.writableDatabase.execSQL("DROP TABLE import_batch_items");store.writableDatabase.execSQL("DROP TABLE import_batches")
        store.writableDatabase.version=15;store.close()
        val name=stores.last().first
        LedgerStore(context,name).use { reopened ->
            assertEquals(18,reopened.readableDatabase.version);assertEquals(1,reopened.all().size)
            assertEquals(0,ImportBatches(reopened).list(0).getJSONArray("items").length())
        }
    }
}
