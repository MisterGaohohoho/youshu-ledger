package net.ankio.ledger.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PlatformImportAccountsTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private fun withStore(block:(LedgerStore,String)->Unit) {
        val name="platform-accounts-${UUID.randomUUID()}.db"
        val store=LedgerStore(context,name)
        try { block(store,name) } finally { store.close();context.deleteDatabase(name) }
    }
    private fun document(platform:String="wechat",method:String=if(platform=="wechat")"零钱" else "余额",
                         order:String="IMPORT-PAY-001",status:String="支付成功",direction:String="支出"):PlatformStatements.Document {
        val header=if(platform=="wechat")listOf("交易时间","交易类型","交易对方","商品","收/支","金额(元)","支付方式","当前状态","交易单号","商户单号","备注")
            else listOf("交易时间","交易分类","交易对方","商品说明","收/支","金额","收/付款方式","交易状态","交易订单号","商家订单号","备注")
        return PlatformStatements.fromRows(listOf(header,listOf("2026-11-21 12:00:00","餐饮","合成商户 $order","午餐",direction,"100.00",method,status,order,"SHOP-$order","合成备注")),platform)
    }
    private fun account(store:LedgerStore,name:String,kind:String="wechat",amount:Long=100_000)=
        store.saveAccount(LedgerAccount(name=name,kind=kind,amountCents=amount))
    private fun select(document:PlatformStatements.Document,uid:String?,affectsBalance:Boolean=false,remember:Boolean=false)=
        PlatformImportAccounts.Selection(document.entries.associate { it.paymentMethod to uid },affectsBalance,remember)
    private fun reject(block:()->Unit) { try { block();fail("Expected validation failure") } catch(_:Exception) {} }
    private fun decode(value:String)=LedgerBackup.decode(value.byteInputStream())

    @Test fun uniqueAccountTypeSurvivesRenameAndNeverFallsBackToUnrelatedGlobalAccount()=withStore { store,_ ->
        val global=account(store,"通用现金","cash",-Money.MAX_CENTS)
        val wallet=account(store,"生活钱包")
        store.setDefaultAccount(global.uid)
        val renamed=store.saveAccount(wallet.copy(name="改名后的日常资金"))
        val doc=document()
        assertEquals(renamed.uid,PlatformImportAccounts.preview(store,doc).groups.single().suggestedUid)
        assertEquals(1,PlatformStatements.apply(store,doc).saved)
        val bill=store.all().single()
        assertEquals(renamed.uid,bill.accountUid);assertFalse(bill.affectsBalance);assertEquals(0L,bill.accountEffect)
        assertEquals(100_000L,store.getAccount(wallet.id)!!.amountCents)
        assertEquals(-Money.MAX_CENTS,store.getAccount(global.id)!!.amountCents)
        assertEquals(10_000L,AccountDetailReadView(store).query(wallet.uid,"2026-11","all").getLong("expenseCents"))
        val alipay=document("alipay",order="ALIPAY-NO-ACCOUNT")
        PlatformStatements.apply(store,alipay)
        assertNull(store.all().single { it.bill.transactionId=="ALIPAY-NO-ACCOUNT" }.accountUid)
        val ali=account(store,"家庭备用金","alipay")
        val income=document("alipay",order="ALIPAY-INCOME",direction="收入")
        PlatformStatements.apply(store,income)
        assertEquals(ali.uid,store.all().single { it.bill.transactionId=="ALIPAY-INCOME" }.accountUid)
        assertEquals(100_000L,store.getAccount(ali.id)!!.amountCents)
    }

    @Test fun ambiguityRequiresSelectionAndRememberingDoesNotChangeCaptureDefaults()=withStore { store,name ->
        val first=account(store,"微信生活")
        val second=account(store,"微信工作")
        val doc=document()
        assertTrue(PlatformImportAccounts.preview(store,doc).groups.single().requiresChoice)
        reject { PlatformStatements.apply(store,doc) }
        assertTrue(store.all().isEmpty())
        store.setChannelDefaultAccount("wechat",first.uid)
        assertEquals(first.uid,PlatformImportAccounts.preview(store,doc).groups.single().suggestedUid)
        PlatformStatements.apply(store,doc,selection=select(doc,second.uid,remember=true))
        assertEquals(first.uid,store.defaultAccount("wechat")!!.uid)
        assertEquals(second.uid,PlatformImportAccounts.preview(store,doc).groups.single().suggestedUid)
        store.close()
        LedgerStore(context,name).use { reopened ->
            assertEquals(second.uid,PlatformImportAccounts.preview(reopened,doc).groups.single().suggestedUid)
            val keepUnlinked=document(order="EXPLICIT-NONE")
            PlatformStatements.apply(reopened,keepUnlinked,selection=select(keepUnlinked,null,remember=true))
            assertNull(PlatformImportAccounts.preview(reopened,doc).groups.single().suggestedUid)
            assertEquals(first.uid,reopened.defaultAccount("wechat")!!.uid)
        }
    }

    @Test fun realPaymentMethodsAreGroupedAndRequireReviewBeforeUsingWalletBalances()=withStore { store,_ ->
        val wallet=account(store,"微信零钱")
        store.setChannelDefaultAccount("wechat",wallet.uid)
        val cash=document()
        val bank=document(method="工商银行储蓄卡（1234）",order="BANK-PAY")
        assertEquals("工商银行储蓄卡(1234)",bank.entries.single().paymentMethod)
        val mixed=cash.copy(entries=cash.entries+bank.entries)
        val groups=PlatformImportAccounts.preview(store,mixed).groups
        assertEquals(2,groups.size);assertEquals(wallet.uid,groups.first().suggestedUid)
        assertTrue(groups.last().requiresChoice)
        reject { PlatformStatements.apply(store,mixed) }
        val bankAccount=account(store,"测试工商1234","bank_icbc")
        val mapping=mapOf(cash.entries.single().paymentMethod to wallet.uid,bank.entries.single().paymentMethod to bankAccount.uid)
        PlatformStatements.apply(store,mixed,selection=PlatformImportAccounts.Selection(mapping,true,true))
        assertEquals(90_000L,store.getAccount(wallet.id)!!.amountCents)
        assertEquals(90_000L,store.getAccount(bankAccount.id)!!.amountCents)
        assertFalse(PlatformImportAccounts.preview(store,mixed).groups.last().requiresChoice)
        val ali=account(store,"支付宝资产","alipay")
        store.setChannelDefaultAccount("alipay",ali.uid)
        assertTrue(PlatformImportAccounts.preview(store,document("alipay","花呗","HUABEI-PAY")).groups.single().requiresChoice)
        assertTrue(PlatformImportAccounts.preview(store,document("alipay","余额宝","YUEBAO-PAY")).groups.single().requiresChoice)
    }

    @Test fun historyKeepsBalanceThroughEditsReassignmentRefundsAndBatchUndo()=withStore { store,_ ->
        val first=account(store,"微信零钱")
        val second=account(store,"另一个账户","cash",200_000)
        val doc=document()
        PlatformStatements.apply(store,doc)
        val initial=store.all().single()
        val edited=store.saveEdited(initial.copy(note="修改历史备注",affectsBalance=true,accountSelectionExplicit=false))
        assertFalse(edited.affectsBalance);assertTrue(edited.accountSelectionExplicit)
        store.setRecordAccount(initial.id,initial.uid,second.uid)
        assertEquals(1,PlatformStatements.apply(store,document(status="已退款(￥30.00)"),selection=select(doc,first.uid,true)).updated)
        val refunded=store.get(initial.id)!!
        assertEquals(second.uid,refunded.accountUid);assertEquals(7_000L,refunded.bill.amountCents)
        assertFalse(refunded.affectsBalance);assertEquals(0L,refunded.accountEffect)
        val now=System.currentTimeMillis()
        val removed=store.softDeleteBatch(listOf(initial.id),now)
        val batch=requireNotNull(removed.batchId)
        assertTrue(store.undoDeleteBatch(batch,now+1))
        assertFalse(store.undoDeleteBatch(batch,now+2))
        assertEquals(100_000L,store.getAccount(first.id)!!.amountCents)
        assertEquals(200_000L,store.getAccount(second.id)!!.amountCents)
        store.delete(initial.id)
        assertEquals(200_000L,store.getAccount(second.id)!!.amountCents)
    }

    @Test fun linkedImportsCreditIncomeAndOnlyNewLinkedRowsAffectMixedBatchUndo()=withStore { store,_ ->
        val wallet=account(store,"微信零钱")
        val expense=document(order="LINKED-EXPENSE")
        val income=document(order="LINKED-INCOME",direction="收入")
        val history=document(order="HISTORY-EXPENSE")
        PlatformStatements.apply(store,expense,selection=select(expense,wallet.uid,true))
        PlatformStatements.apply(store,income,selection=select(income,wallet.uid,true))
        PlatformStatements.apply(store,history)
        assertEquals(100_000L,store.getAccount(wallet.id)!!.amountCents)
        assertEquals(0,PlatformStatements.apply(store,history,selection=select(history,wallet.uid,true)).saved)
        assertFalse(store.all().single { it.bill.transactionId=="HISTORY-EXPENSE" }.affectsBalance)
        val linked=store.all().single { it.bill.transactionId=="LINKED-EXPENSE" }
        store.saveEdited(linked.copy(bill=linked.bill.copy(amountCents=15_000)))
        assertEquals(95_000L,store.getAccount(wallet.id)!!.amountCents)
        val now=System.currentTimeMillis();val removal=store.softDeleteBatch(store.all().map { it.id },now)
        assertEquals(100_000L,store.getAccount(wallet.id)!!.amountCents)
        assertTrue(store.undoDeleteBatch(removal.batchId!!,now+1))
        assertEquals(95_000L,store.getAccount(wallet.id)!!.amountCents)
    }

    @Test fun duplicatesAndRefundsKeepAnExistingLivePaymentsAccountAndBalanceRule()=withStore { store,_ ->
        val wallet=account(store,"默认微信")
        val card=account(store,"实际扣款银行卡","bank_icbc")
        val doc=document();val entry=doc.entries.single()
        val live=(store.ingest(entry.capture.copy(source=Source.OCR),entry.bill,accountSelection=RecordAccountSelection(card.uid,true)) as IngestResult.Saved).record
        assertEquals(1,PlatformStatements.apply(store,doc).duplicates)
        assertEquals(card.uid,store.get(live.id)!!.accountUid)
        assertEquals(1,PlatformStatements.apply(store,document(status="已退款(￥30.00)")).updated)
        assertTrue(store.get(live.id)!!.affectsBalance)
        assertEquals(93_000L,store.getAccount(card.id)!!.amountCents)
        assertEquals(100_000L,store.getAccount(wallet.id)!!.amountCents)
        PlatformStatements.apply(store,document(status="已退款(￥30.00)"))
        assertEquals(93_000L,store.getAccount(card.id)!!.amountCents)
    }

    @Test fun pendingRowsKeepBothSelectedAccountsAndExplicitlyUnlinkedChoicesUntilConfirmation()=withStore { store,_ ->
        val wallet=account(store,"微信零钱")
        fun pending(order:String):PlatformStatements.Document {
            val doc=document(order=order)
            return doc.copy(entries=doc.entries.map { it.copy(bill=it.bill.copy(reliable=false,reason="合成待确认")) })
        }
        val doc=pending("PENDING-LINKED")
        assertEquals(1,PlatformStatements.apply(store,doc,selection=select(doc,wallet.uid,true)).review)
        val linked=store.all().single()
        assertEquals(RecordState.REVIEW,linked.state);assertEquals(wallet.uid,linked.accountUid)
        assertEquals("午餐；合成备注",linked.note);assertEquals(100_000L,store.getAccount(wallet.id)!!.amountCents)
        store.saveEdited(linked)
        assertEquals(90_000L,store.getAccount(wallet.id)!!.amountCents)
        val none=pending("PENDING-NONE")
        PlatformStatements.apply(store,none,selection=select(none,null,true))
        val unlinked=store.all().single { it.state==RecordState.REVIEW }
        assertNull(store.saveEdited(unlinked).accountUid)
        assertEquals(90_000L,store.getAccount(wallet.id)!!.amountCents)
        val history=pending("PENDING-HISTORY")
        PlatformStatements.apply(store,history,selection=select(history,wallet.uid))
        store.saveEdited(store.all().single { it.state==RecordState.REVIEW })
        assertEquals(90_000L,store.getAccount(wallet.id)!!.amountCents)
    }

    @Test fun deletedAccountAndMidImportFailureRollBackRowsBalancesAndRememberedMappings()=withStore { store,_ ->
        val wallet=account(store,"将被删除的账户")
        val doc=document();val choice=select(doc,wallet.uid,true,true)
        store.deleteAccount(wallet.id)
        reject { PlatformStatements.apply(store,doc,selection=choice) }
        assertTrue(store.all().isEmpty());assertTrue(PlatformImportAccounts.preferences(store).isEmpty())
        val next=account(store,"保留账户")
        store.writableDatabase.execSQL("CREATE TRIGGER fixture_abort_import BEFORE INSERT ON import_batches BEGIN SELECT RAISE(ABORT,'fixture rollback'); END")
        try { PlatformStatements.apply(store,doc,selection=select(doc,next.uid,true,true));fail("Expected SQL rollback") }
        catch(_:android.database.sqlite.SQLiteException) {}
        assertTrue(store.all().isEmpty());assertTrue(PlatformImportAccounts.preferences(store).isEmpty())
        assertEquals(100_000L,store.getAccount(next.id)!!.amountCents)
    }

    @Test fun jsonAndZipPreserveModesPreferencesAndIdempotentRestoreIntoExistingAccounts()=withStore { store,_ ->
        val wallet=account(store,"微信零钱")
        val history=document(order="BACKUP-HISTORY")
        PlatformStatements.apply(store,history,selection=select(history,wallet.uid,remember=true))
        val live=document(order="BACKUP-LINKED")
        PlatformStatements.apply(store,live,selection=select(live,wallet.uid,true))
        val encoded=LedgerBackup.encode(store,ChannelSettings(context))
        val backup=decode(encoded)
        assertEquals(10,JSONObject(encoded).getInt("version"))
        val zip=ByteArrayOutputStream().also { LedgerArchive.write(store,ChannelSettings(context),it) }.toByteArray()
        withStore { fresh,_ ->
            LedgerArchive.read(zip.inputStream(),context.cacheDir).use { prepared ->
                assertEquals(2,LedgerArchive.restore(fresh,prepared));assertEquals(0,LedgerArchive.restore(fresh,prepared))
            }
            assertEquals(90_000L,fresh.accounts().single().amountCents)
            assertEquals(PlatformImportAccounts.preferences(store),PlatformImportAccounts.preferences(fresh))
            val restored=fresh.all().single { !it.affectsBalance }
            assertTrue(restored.accountSelectionExplicit);fresh.delete(restored.id)
            assertEquals(90_000L,fresh.accounts().single().amountCents)
        }
        withStore { existing,_ ->
            val local=existing.saveAccount(wallet.copy(id=0,amountCents=300_000))
            PlatformImportAccounts.remember(existing,"wechat",mapOf("零钱" to null))
            assertEquals(2,LedgerBackup.restore(existing,backup));assertEquals(0,LedgerBackup.restore(existing,backup))
            assertEquals(290_000L,existing.getAccount(local.id)!!.amountCents)
            assertNull(PlatformImportAccounts.preferences(existing).single().accountUid)
            val old=existing.all().single { !it.affectsBalance }
            existing.saveEdited(old.copy(bill=old.bill.copy(amountCents=5000)))
            assertEquals(290_000L,existing.getAccount(local.id)!!.amountCents)
        }
        val corrupt=JSONObject(encoded);corrupt.getJSONArray("records").getJSONObject(0).put("affectsBalance","false")
        reject { decode(corrupt.toString()) }
        val orphan=JSONObject(encoded).put("importAccountPreferences",JSONArray().put(JSONObject().put("platform","wechat").put("method","零钱").put("accountUid",UUID.randomUUID().toString())))
        reject { decode(orphan.toString()) }
    }

    @Test fun versionSixteenMigrationAndVersionNineBackupKeepExistingFinancialSemantics()=withStore { store,name ->
        val wallet=account(store,"旧版账户")
        val batches=ImportBatches(store);var batch=""
        val db=store.writableDatabase;db.beginTransaction()
        try {
            val before=batches.before();val entry=document().entries.single()
            store.ingest(entry.capture.copy(source=Source.OCR),entry.bill)
            batch=batches.finish(before,"旧版合成账单.csv","微信",0,0)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        val records=store.all();val accounts=store.accounts()
        val legacy=JSONObject(LedgerBackup.encode(store,ChannelSettings(context))).put("version",9)
        legacy.remove("importAccountPreferences")
        val oldRows=legacy.getJSONArray("records")
        repeat(oldRows.length()) { oldRows.getJSONObject(it).remove("affectsBalance");oldRows.getJSONObject(it).remove("accountSelectionExplicit") }
        assertTrue(decode(legacy.toString()).records.single().affectsBalance)
        store.close();LegacyLedgerSchemaFixture.project(context.getDatabasePath(name).path,16)
        LedgerStore(context,name).use { upgraded ->
            assertEquals(17,upgraded.readableDatabase.version)
            assertEquals(records,upgraded.all());assertEquals(accounts,upgraded.accounts())
            assertEquals(0,ImportBatches(upgraded).editedCount(batch))
            val record=upgraded.all().single();upgraded.delete(record.id)
            assertEquals(100_000L,upgraded.getAccount(wallet.id)!!.amountCents)
        }
    }
}
