package net.ankio.ledger.data

import net.ankio.ledger.core.Direction
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Small read models: no OCR bodies or image blobs cross the bridge in a list request. */
class LedgerReadView(private val store:LedgerStore) {
    data class Window(val start:Long,val end:Long,val previous:Long)
    fun window(month:String):Window {
        val period=LedgerPeriod.parse(month)
        return Window(period.start,period.end,period.previous)
    }

    private fun reimbursementWhere(value:String):String=when(value) {
        ""->"";"required"->" AND r.requires_reimbursement=1";"notRequired"->" AND r.requires_reimbursement=0"
        else->throw IllegalArgumentException("报销条件无效")
    }
    fun records(month:String,search:String="",offset:Int=0,limit:Int=200,direction:String="",category:String="",beforeAt:Long?=null,beforeId:Long?=null,tag:String="",reimbursement:String="",statistical:Boolean=false,channel:String=""):JSONObject {
        require(offset in 0..1_000_000 && limit in 1..200)
        require((beforeAt==null)==(beforeId==null))
        if(beforeAt!=null)require(beforeAt>0 && beforeId!!>0 && offset==0)
        val (baseWhere,baseArgs)=matchingWhere(month,search,direction,category,tag,reimbursement,statistical,channel)
        val where=StringBuilder(baseWhere);val args=baseArgs.toMutableList()
        if(beforeAt!=null) {
            where.append(" AND (r.occurred_at<? OR (r.occurred_at=? AND r.id<?))")
            args+=beforeAt.toString();args+=beforeAt.toString();args+=beforeId.toString()
        }
        return readRecords(where.toString(),args,offset,limit)
    }

    /** Shared by the visible list and select-all; pagination never limits deletion scope. */
    internal fun matchingWhere(month:String,search:String="",direction:String="",category:String="",tag:String="",reimbursement:String="",statistical:Boolean=false,channel:String=""):Pair<String,List<String>> {
        val w=window(month);require(search.length<=200)
        require(direction.isEmpty()||direction in Direction.entries.map { it.name })
        if(tag.isNotEmpty())BillTags.validate(listOf(tag))
        require(channel in setOf("","wechat","alipay","other"))
        val where=StringBuilder("NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=r.id)")
        val args=mutableListOf<String>()
        if(statistical)where.append(" AND (r.direction<>'INCOME' OR ${ReimbursementStore.statisticalAmountSql()}>0)")
        where.append(reimbursementWhere(reimbursement))
        if(channel.isNotEmpty()){where.append(" AND (${OfflineAnalytics.CHANNEL_SQL})=?");args+=channel}
        if(search.isBlank()) { where.append(" AND r.occurred_at>=? AND r.occurred_at<?");args+=w.start.toString();args+=w.end.toString() }
        else { where.append(" AND (r.display_name LIKE ? ESCAPE '\\' OR r.merchant LIKE ? ESCAPE '\\' OR r.category LIKE ? ESCAPE '\\' OR r.note LIKE ? ESCAPE '\\' OR r.transaction_id LIKE ? ESCAPE '\\' OR CASE r.source WHEN 'OCR' THEN '摇一摇' WHEN 'IMAGE' THEN '图片导入' WHEN 'MANUAL' THEN '手动' WHEN 'SMS' THEN '自动 · 短信' WHEN 'NOTIFICATION' THEN '自动 · 通知' END LIKE ? ESCAPE '\\')")
            val term="%"+search.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";repeat(6){args+=term} }
        if(direction.isNotBlank()) { where.append(if(direction=="EXPENSE")" AND (r.direction=? OR r.direction IS NULL)" else " AND r.direction=?");args+=direction }
        if(tag.isNotEmpty()) { where.append(" AND r.state='POSTED' AND instr(r.tags,?)>0");args+=BillTags.toStorage(listOf(tag)) }
        if(category.isNotBlank()) { where.append(" AND r.category=?");args+=category }
        if(category.isNotBlank())where.append(" AND r.state='POSTED'")
        return where.toString() to args
    }

    private fun readRecords(where:String,args:List<String>,offset:Int,limit:Int):JSONObject {
        val rows=JSONArray()
        store.readableDatabase.rawQuery("""SELECT r.id,r.uid,COALESCE(r.display_name,r.merchant),r.category,r.note,r.amount,r.direction,r.occurred_at,r.state,r.source,
            r.reason,r.duplicate_id,r.refund_provider,r.refund_original,r.refund_amount,r.refund_mode,r.refund_id,
            i.merchant_key,length(a.image), (SELECT MAX(sequence) FROM capture_history h WHERE h.record_id=r.id),r.transaction_id,
            CASE WHEN lower(r.origin) LIKE '%com.tencent.mm%' OR r.origin IN ('微信','微信支付') THEN 'wechat'
                WHEN lower(r.origin) LIKE '%com.eg.android.alipaygphone%' OR r.origin='支付宝' THEN 'alipay'
                WHEN r.source IN ('IMAGE','OCR') AND instr(r.raw_text,'微信')>0 AND instr(r.raw_text,'支付宝')=0 THEN 'wechat'
                WHEN r.source IN ('IMAGE','OCR') AND instr(r.raw_text,'支付宝')>0 AND instr(r.raw_text,'微信')=0 THEN 'alipay' END,r.tags,r.requires_reimbursement,${ReimbursementStore.repaymentAmountSql()},r.origin,CASE WHEN r.raw_text LIKE '账单文件导入：%' THEN '账单导入' WHEN r.raw_text LIKE '洁账导入：%' THEN '洁账导入' ELSE '' END
            FROM records r LEFT JOIN merchant_icons i ON i.record_id=r.id LEFT JOIN merchant_icon_assets a ON a.merchant_key=i.merchant_key
            WHERE $where ORDER BY r.occurred_at DESC,r.id DESC LIMIT ${limit+1} OFFSET $offset""",args.toTypedArray()).use { c ->
            while(c.moveToNext())rows.put(JSONObject().apply {
                put("id",c.getLong(0));put("uid",c.getString(1));put("merchant",c.getString(2));put("category",c.getString(3));put("note",c.getString(4))
                put("amountCents",if(c.isNull(5))JSONObject.NULL else c.getLong(5));put("direction",if(c.isNull(6))JSONObject.NULL else c.getString(6))
                put("occurredAt",c.getLong(7));put("state",c.getString(8));put("source",c.getString(9));put("reason",c.getString(10))
                put("duplicateId",if(c.isNull(11))JSONObject.NULL else c.getLong(11))
                put("refund",if(c.isNull(12))JSONObject.NULL else JSONObject().put("provider",c.getString(12)).put("originalAmountCents",c.getLong(13))
                    .put("refundedCents",c.getLong(14)).put("mode",c.getString(15)).put("refundId",if(c.isNull(16))JSONObject.NULL else c.getString(16)))
                put("hasMerchantIcon",!c.isNull(18));put("merchantIconKey",if(c.isNull(18))JSONObject.NULL else c.getString(17));put("merchantIconRevision",c.getInt(18))
                put("historyRevision",c.getInt(19).toString());put("hasScreenshot",false);put("captureMode","");put("iconStatus",if(!c.isNull(18))"cached" else "")
                put("transactionId",if(c.isNull(20))JSONObject.NULL else c.getString(20))
                put("paymentProvider",if(c.isNull(21))JSONObject.NULL else c.getString(21));put("tags",JSONArray(BillTags.fromStorage(c.getString(22))))
                put("origin",c.getString(25));put("importLabel",c.getString(26))
                put("requiresReimbursement",c.getInt(23)==1)
                put("reimbursementReceivedCents",c.getLong(24));put("isReimbursementRepayment",c.getLong(24)>0)
                put("statisticalAmountCents",if(c.isNull(5))JSONObject.NULL else c.getLong(5)-c.getLong(24))
            })
        }
        val more=rows.length()>limit;if(more)rows.remove(rows.length()-1)
        val last=rows.optJSONObject(rows.length()-1)
        return JSONObject().put("records",rows).put("hasMore",more).put("offset",offset).put("limit",limit)
            .put("nextCursor",if(more && last!=null)JSONObject().put("occurredAt",last.getLong("occurredAt")).put("id",last.getLong("id")) else JSONObject.NULL)
    }
    fun summary(month:String,reimbursement:String="",channel:String="",search:String=""):JSONObject {
        val w=window(month);val result=JSONObject().put("month",month)
        require(channel in setOf("","wechat","alipay","other"))
        val scope=reimbursementWhere(reimbursement)+(if(channel.isEmpty())"" else " AND (${OfflineAnalytics.CHANNEL_SQL})='$channel'")
        val amount=ReimbursementStore.statisticalAmountSql()
        val statisticalScope=" AND (r.direction!='INCOME' OR $amount>0)"
        val (matched,matchedArgs)=matchingWhere(month,search,reimbursement=reimbursement,statistical=true,channel=channel)
        val currentWhere="state='POSTED' AND $matched"
        fun totals(start:Long,end:Long):JSONObject {
            val total=JSONObject().put("expense",0).put("income",0).put("expenseCount",0).put("incomeCount",0)
            val current=start==w.start && end==w.end
            if(search.isNotBlank() && !current)return total
            val where=if(current)currentWhere else "state='POSTED' AND occurred_at>=? AND occurred_at<? AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=r.id)$scope$statisticalScope"
            val arguments=if(current)matchedArgs.toTypedArray() else arrayOf(start.toString(),end.toString())
            store.readableDatabase.rawQuery("SELECT direction,COALESCE(SUM($amount),0),COUNT(*) FROM records r WHERE $where GROUP BY direction",arguments).use { c ->
                while(c.moveToNext()){val key=if(c.getString(0)=="INCOME")"income" else "expense";total.put(key,c.getLong(1));total.put(key+"Count",c.getInt(2))}
            };return total
        }
        result.put("totals",totals(w.start,w.end));result.put("previous",totals(w.previous,w.start))
        val categories=JSONArray();store.readableDatabase.rawQuery("SELECT direction,category,SUM($amount),COUNT(*) FROM records r WHERE $currentWhere GROUP BY direction,category ORDER BY SUM($amount) DESC",matchedArgs.toTypedArray()).use { c ->
            while(c.moveToNext())categories.put(JSONObject().put("direction",c.getString(0)).put("name",c.getString(1)).put("amount",c.getLong(2)).put("count",c.getInt(3)))
        };result.put("categories",categories)
        val days=JSONArray();store.readableDatabase.rawQuery("SELECT strftime('%Y-%m-%d',occurred_at/1000,'unixepoch','localtime'),direction,SUM($amount) FROM records r WHERE $currentWhere GROUP BY 1,2 ORDER BY 1",matchedArgs.toTypedArray()).use { c ->
            while(c.moveToNext())days.put(JSONObject().put("date",c.getString(0)).put("day",c.getString(0).takeLast(2).toInt()).put("direction",c.getString(1)).put("amount",c.getLong(2)))
        };result.put("days",days)
        result.put("postedCount",store.readableDatabase.rawQuery("SELECT COUNT(*) FROM records r WHERE state='POSTED' AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=r.id)$scope",null).use { it.moveToFirst();it.getInt(0) })
        return result
    }
}
