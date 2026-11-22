package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.core.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
class OfflineBillsTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="offline-${UUID.randomUUID()}.db"
    private val store=LedgerStore(context,name)
    @After fun close(){store.close();context.deleteDatabase(name)}
    private val header=listOf("交易时间","交易类型","交易对方","商品","收/支","金额(元)","支付方式","当前状态","交易单号","商户单号","备注")
    private fun wx(amount:String="100.00",status:String="支付成功",order:String="SYNTHETIC-ORDER")=PlatformStatements.fromRows(listOf(header,
        listOf("2026-11-10 12:00:00","餐饮","测试商户","午餐","支出",amount,"零钱",status,order,"MERCHANT-001","")),"wechat")
    private fun query(channel:String="",mode:String="month",period:String="2026-11")=JSONObject().put("channel",channel).put("mode",mode).put("period",period)
    private fun time(value:String)=LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun fileImportIsIdempotentAndUpdatesTheSameRefundAndAccount() {
        val account=store.saveAccount(LedgerAccount(name="测试微信",amountCents=100_000))
        store.setChannelDefaultAccount("wechat",account.uid)
        assertEquals(1,PlatformStatements.apply(store,wx(),selection=PlatformImportAccounts.Selection(mapOf("零钱" to account.uid),affectsBalance=true)).saved)
        val original=store.all().single()
        assertEquals(90_000L,store.accounts().single { it.uid==account.uid }.amountCents)
        assertEquals(1,PlatformStatements.apply(store,wx()).duplicates)
        assertEquals(1,PlatformStatements.apply(store,wx(status="已退款(￥30.00)")).updated)
        assertEquals(original.id,store.all().single().id)
        assertEquals(7_000L,store.get(original.id)!!.bill.amountCents)
        assertEquals(93_000L,store.accounts().single { it.uid==account.uid }.amountCents)
        val repeatRefund=PlatformStatements.apply(store,wx(status="已退款(￥30.00)"))
        assertEquals(0,repeatRefund.saved);assertEquals(1,repeatRefund.duplicates)
        assertEquals(7_000L,OfflineAnalytics(store).summary(query("wechat")).getJSONObject("totals").getLong("expense"))
        PlatformStatements.apply(store,wx()) // Old exports must never undo a newer refund.
        assertEquals(7_000L,store.get(original.id)!!.bill.amountCents)
    }

    @Test fun importRecognizesAnExistingAutomaticallyCapturedPayment() {
        val entry=wx().entries.single()
        val saved=(store.ingest(entry.capture.copy(source=Source.OCR),entry.bill) as IngestResult.Saved).record
        assertEquals(1,PlatformStatements.apply(store,wx()).duplicates)
        assertEquals(saved.id,store.all().single().id)
        assertEquals(1,PlatformStatements.apply(store,wx(status="已退款")).updated)
        assertEquals(0L,OfflineAnalytics(store).summary(query()).getJSONObject("totals").getLong("expense"))
    }

    @Test fun newRefundImportsUseChannelAccountAndDeletionReversesItsNetEffect() {
        val global=store.saveAccount(LedgerAccount(name="通用账户",amountCents=100_000))
        val channel=store.saveAccount(LedgerAccount(name="微信账户",amountCents=50_000))
        store.setDefaultAccount(global.uid)
        store.setChannelDefaultAccount("wechat",channel.uid)
        assertEquals(1,PlatformStatements.apply(store,wx(status="已退款(￥30.00)"),selection=PlatformImportAccounts.Selection(mapOf("零钱" to channel.uid),affectsBalance=true)).saved)
        val record=store.all().single()
        assertEquals(channel.uid,record.accountUid)
        assertEquals("午餐",record.note)
        assertEquals(100_000L,store.getAccount(global.id)!!.amountCents)
        assertEquals(43_000L,store.getAccount(channel.id)!!.amountCents)
        store.delete(record.id)
        assertEquals(50_000L,store.getAccount(channel.id)!!.amountCents)
        assertEquals(1,PlatformStatements.apply(store,wx(status="已退款",order="FULL-REFUND-ORDER"),selection=PlatformImportAccounts.Selection(mapOf("零钱" to channel.uid),affectsBalance=true)).saved)
        assertEquals(50_000L,store.getAccount(channel.id)!!.amountCents)
    }

    @Test fun filtersKeepChannelsPeriodsAndPendingRecordsSeparate() {
        PlatformStatements.apply(store,wx())
        fun add(origin:String,amount:Long,at:String,state:RecordState=RecordState.POSTED)=LedgerRecord(
            capture=Capture(Source.MANUAL,origin,""),bill=ParsedBill(amount,Direction.INCOME,"测试收入","工资",time(at),null,true,"",true),state=state)
        store.merge(listOf(add(PaymentApps.ALIPAY,50_000,"2026-11-12T08:00:00"),add(PaymentApps.ALIPAY,90_000,"2026-10-12T08:00:00"),
            add(PaymentApps.WECHAT,5_000,"2026-11-13T08:00:00",RecordState.REVIEW)))
        val analytics=OfflineAnalytics(store)
        assertEquals(50_000L,analytics.summary(query()).getJSONObject("totals").getLong("income"))
        assertEquals(0L,analytics.summary(query("wechat")).getJSONObject("totals").getLong("income"))
        assertEquals(2,analytics.details(query("wechat").put("management",true)).getJSONArray("records").length())
        assertEquals(1,analytics.details(query("alipay").put("search","测试")).getJSONArray("records").length())
        val year=analytics.summary(query("alipay","year","2026"))
        assertEquals(140_000L,year.getJSONObject("totals").getLong("income"));assertEquals(12,year.getJSONArray("trend").length())
        assertEquals(29,analytics.summary(query("","month","2024-02")).getJSONArray("trend").length())
        store.delete(store.all().first { it.bill.direction==Direction.EXPENSE }.id)
        assertEquals(0L,analytics.summary(query()).getJSONObject("totals").getLong("expense"))
    }

    @Test fun alipayRefundsAreMergedAndUnlinkedRefundsAreReported() {
        val header=listOf("交易时间","交易分类","交易对方","商品说明","收/支","金额","收/付款方式","交易状态","交易订单号","商家订单号")
        val doc=PlatformStatements.fromRows(listOf(header,
            listOf("2026-11-10 12:00:00","购物","测试商户","商品","支出","100.00","余额","交易成功","PAY-0001","MERCHANT-001"),
            listOf("2026-11-11 12:00:00","退款","测试商户","退款","不计收支","30.00","余额","退款成功","REFUND-001","MERCHANT-001"),
            listOf("2026-11-11 12:00:00","退款","另一商户","退款","收入","10.00","余额","退款成功","REFUND-002","MERCHANT-002")),"alipay")
        assertEquals(1,doc.entries.size);assertEquals(7_000L,doc.entries.single().bill.amountCents);assertEquals(1,doc.errors.size)
        PlatformStatements.apply(store,doc)
        assertEquals(7_000L,OfflineAnalytics(store).summary(query("alipay")).getJSONObject("totals").getLong("expense"))
    }

    @Test fun csvEncodingQuotedMerchantAndWrongChannelAreHandled() {
        val csv=header.joinToString(",")+"\r\n2026-11-10 12:00:00,餐饮,\"商户,分店\",早餐,支出,12.34,零钱,支付成功,SYNTHETIC-ID,MERCHANT-ID,备注\r\n"
        for(charset in listOf(Charsets.UTF_8,java.nio.charset.Charset.forName("GB18030"))) {
            val doc=PlatformStatements.read(ByteArrayInputStream(csv.toByteArray(charset)),"wechat")
            assertEquals("商户,分店",doc.entries.single().bill.merchant);assertEquals(1234L,doc.entries.single().bill.amountCents)
        }
        try{PlatformStatements.read(ByteArrayInputStream(csv.toByteArray()),"alipay");fail("Wrong channel accepted")}
        catch(_:IllegalArgumentException){}
    }

    @Test fun xlsxSupportsSharedStringsInlineCellsAndRejectsFormulas() {
        fun workbook(formula:Boolean=false):ByteArray {
            val rows=listOf(header,listOf("2026-11-10 12:00:00","餐饮","测试商户","午餐","支出","12.34","零钱","支付成功","XLSX-ORDER","SHOP-ORDER",""))
            val strings=rows.flatten().distinct()
            val shared="<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"+strings.joinToString(""){"<si><t>$it</t></si>"}+"</sst>"
            val sheet="<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"+rows.mapIndexed{r,row->
                "<row r=\"${r+1}\">"+row.mapIndexed{c,value->
                    val pos="${('A'.code+c).toChar()}${r+1}"
                    if(r==1&&c==2)"<c r=\"$pos\" t=\"inlineStr\"><is><t>$value</t></is></c>"
                    else "<c r=\"$pos\" t=\"s\">${if(formula&&r==1&&c==5)"<f>1+1</f>" else ""}<v>${strings.indexOf(value)}</v></c>"
                }.joinToString("")+"</row>"
            }.joinToString("")+"</sheetData></worksheet>"
            val out=java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(out).use{zip->for((name,text)in mapOf("xl/sharedStrings.xml" to shared,"xl/worksheets/sheet1.xml" to sheet)){
                zip.putNextEntry(java.util.zip.ZipEntry(name));zip.write(text.toByteArray());zip.closeEntry()
            }};return out.toByteArray()
        }
        val result=PlatformStatements.read(ByteArrayInputStream(workbook()),"wechat")
        assertEquals(1234L,result.entries.single().bill.amountCents)
        assertEquals("测试商户",result.entries.single().bill.merchant)
        try{PlatformStatements.read(ByteArrayInputStream(workbook(true)),"wechat");fail("Formula accepted")}
        catch(_:IllegalStateException){}
    }

    @Test fun unifiedImportDetectsJiezhangAndKeepsAccountsCategoriesAndDuplicates() {
        val header=listOf("子分类","父分类","类型","资产","备注","金额","创建时间","更新时间")
        val row=listOf("午餐","餐饮","支出","微信钱包","合成测试","16.0","2026-03-07 11:13:00","2026-03-07 11:13:11")
        val csv=(header.joinToString(",")+"\n"+row.joinToString(",")+"\n").toByteArray()
        val doc=UnifiedBillImport.read(csv.inputStream()) as UnifiedBillImport.Jiezhang
        assertTrue(doc.errors.isEmpty());assertEquals("餐饮/午餐",doc.rows.single().record.bill.category)
        assertEquals(1,UnifiedBillImport.apply(store,doc,mapOf("微信钱包" to "@new")).first)
        assertEquals(-1600L,store.accounts().single().amountCents)
        assertEquals(1,UnifiedBillImport.preview(store,doc).duplicates)
        assertEquals(0,UnifiedBillImport.apply(store,doc,mapOf("微信钱包" to "@new")).first)
        assertEquals(-1600L,store.accounts().single().amountCents)
        val listed=LedgerReadView(store).records("all",channel="wechat").getJSONArray("records").getJSONObject(0)
        assertEquals("洁账导入",listed.getString("importLabel"));assertEquals("wechat",listed.getString("paymentProvider"))
        assertEquals(1600L,OfflineAnalytics(store).summary(query("wechat","all","all")).getJSONObject("totals").getLong("expense"))
        val changed=UnifiedBillImport.jiezhang(listOf(header,row.toMutableList().apply{this[4]="修改过备注"}))
        assertEquals(1,UnifiedBillImport.preview(store,changed).possible.size)
        assertEquals(1,UnifiedBillImport.apply(store,changed,mapOf("微信钱包" to store.accounts().single().uid)).second)
        assertEquals(-1600L,store.accounts().single().amountCents)
        assertEquals(1,UnifiedBillImport.jiezhang(listOf(header,row.toMutableList().apply{this[5]="1.001"})).errors.size)
        val deleted=store.all().first{it.state==RecordState.POSTED}.id
        val deletedAt=System.currentTimeMillis()
        assertTrue(store.softDelete(deleted,deletedAt))
        assertEquals(1,UnifiedBillImport.preview(store,doc).duplicates)
        assertEquals(0,UnifiedBillImport.apply(store,doc,emptyMap()).first)
        assertEquals(0L,store.accounts().single().amountCents)
        store.purgeExpiredDeletes(deletedAt+LedgerStore.UNDO_WINDOW_MS+1)
        assertEquals(0,UnifiedBillImport.preview(store,doc).duplicates)
        assertEquals(1,UnifiedBillImport.apply(store,doc,mapOf("微信钱包" to store.accounts().single().uid)).first)
        assertEquals(-1600L,store.accounts().single().amountCents)
    }

    @Test fun jiezhangXlsxDetectsNumericAmountsAndTextDatesWithBlankNotes() {
        val rows=listOf(listOf("子分类","父分类","类型","资产","备注","金额","创建时间","更新时间"),
            listOf("地铁","交通","支出","微信钱包","","3.20","2026-03-07 08:00:00","2026-03-07 08:01:00"),
            listOf("工资","收入","收入","银行卡","合成数据","1234.56","2026-03-08 09:00:00","2026-03-08 09:00:01"))
        val sheet="<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"+rows.mapIndexed{r,row->
            "<row r=\"${r+1}\">"+row.mapIndexed{c,value->
                val pos="${('A'.code+c).toChar()}${r+1}"
                if(r>0&&c==5)"<c r=\"$pos\" t=\"n\"><v>$value</v></c>"
                else "<c r=\"$pos\" t=\"inlineStr\"><is><t>$value</t></is></c>"
            }.joinToString("")+"</row>"
        }.joinToString("")+"</sheetData></worksheet>"
        val out=java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use{zip->
            zip.putNextEntry(java.util.zip.ZipEntry("xl/worksheets/sheet1.xml"));zip.write(sheet.toByteArray());zip.closeEntry()
        }
        val doc=UnifiedBillImport.read(out.toByteArray().inputStream()) as UnifiedBillImport.Jiezhang
        assertTrue(doc.errors.isEmpty());assertEquals(listOf(320L,123456L),doc.rows.map{it.record.bill.amountCents})
        assertEquals(time("2026-03-07T08:00:00"),doc.rows.first().record.bill.occurredAt)
        assertEquals("",doc.rows.first().record.note);assertEquals("交通/地铁",doc.rows.first().record.bill.category)
        assertEquals(2,UnifiedBillImport.apply(store,doc,emptyMap()).first)
        val totals=OfflineAnalytics(store).summary(query("","all","all")).getJSONObject("totals")
        assertEquals(320L,totals.getLong("expense"));assertEquals(123456L,totals.getLong("income"))
        assertEquals(2,UnifiedBillImport.preview(store,doc).duplicates)
    }

    @Test fun yearAllAndCustomScopesAgreeAcrossLedgerReportsAndPagination() {
        val times=listOf("2025-12-31T23:59:59","2026-01-01T00:00:00","2026-01-31T23:59:59","2026-02-01T00:00:00")
        store.merge(times.mapIndexed { i,at->LedgerRecord(capture=Capture(Source.MANUAL,PaymentApps.WECHAT,""),
            bill=ParsedBill(100L*(i+1),Direction.EXPENSE,"合成商户 $i","餐饮",time(at),null,true,"",true),state=RecordState.POSTED) })
        val ledger=LedgerReadView(store);val analytics=OfflineAnalytics(store)
        for((period,mode,expected) in listOf(Triple("2026","year",900L),Triple("all","all",1000L),Triple("2026-01-01..2026-01-31","custom",500L))){
            assertEquals(expected,ledger.summary(period).getJSONObject("totals").getLong("expense"))
            val report=analytics.summary(query("wechat",mode,period))
            assertEquals(expected,report.getJSONObject("totals").getLong("expense"))
            val trend=report.getJSONArray("trend")
            assertEquals(expected,(0 until trend.length()).sumOf{trend.getJSONObject(it).getLong("expense")})
            if(mode!="year")assertFalse(report.getBoolean("comparable"))
        }
        val page=ledger.records("all",limit=2,channel="wechat");val cursor=page.getJSONObject("nextCursor")
        val second=ledger.records("all",limit=2,channel="wechat",beforeAt=cursor.getLong("occurredAt"),beforeId=cursor.getLong("id"))
        assertEquals(2,second.getJSONArray("records").length());assertFalse(second.getBoolean("hasMore"))
        assertEquals(0,ledger.records("all",channel="alipay").getJSONArray("records").length())
        try{LedgerPeriod.parse("2026-03-01..2026-02-01");fail("Reversed dates accepted")}catch(_:IllegalArgumentException){}
    }
}
