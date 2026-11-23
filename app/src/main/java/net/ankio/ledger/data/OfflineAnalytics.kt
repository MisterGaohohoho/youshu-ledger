package net.ankio.ledger.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** Read the same posted ledger as the home screen, without copying bills or OCR text. */
class OfflineAnalytics(private val store: LedgerStore) {
    companion object {
        const val CHANNEL_SQL = """CASE
            WHEN lower(r.origin) LIKE '%com.tencent.mm%' OR r.origin IN ('微信','微信支付') THEN 'wechat'
            WHEN lower(r.origin) LIKE '%com.eg.android.alipaygphone%' OR r.origin='支付宝' THEN 'alipay'
            WHEN r.source IN ('IMAGE','OCR') AND instr(r.raw_text,'微信')>0 AND instr(r.raw_text,'支付宝')=0 THEN 'wechat'
            WHEN r.source IN ('IMAGE','OCR') AND instr(r.raw_text,'支付宝')>0 AND instr(r.raw_text,'微信')=0 THEN 'alipay'
            ELSE 'other' END"""
    }

    private data class Scope(val start:Long,val end:Long,val previous:Long,val mode:String,
                             val channel:String,val reimbursement:String)

    private fun scope(input:JSONObject):Scope {
        val period=LedgerPeriod.parse(input.optString("period","all"))
        require(input.optString("mode",period.mode)==period.mode){"统计周期无效"}
        val channel=input.optString("channel")
        require(channel in setOf("","wechat","alipay","other")){"支付渠道无效"}
        val reimbursement=input.optString("reimbursement")
        require(reimbursement in setOf("","required","notRequired")){"报销筛选无效"}
        return Scope(period.start,period.end,period.previous,period.mode,channel,reimbursement)
    }

    private val amount get() = ReimbursementStore.statisticalAmountSql()
    private fun where(s: Scope, management: Boolean = false): String = (if(management) "r.state IN ('POSTED','REVIEW')" else "r.state='POSTED'") + """
        AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=r.id)
        AND r.occurred_at>=? AND r.occurred_at<?""" +
        (if(management) "" else " AND (r.direction!='INCOME' OR $amount>0)") +
        (if (s.channel.isEmpty()) "" else " AND ($CHANNEL_SQL)=?") +
        when (s.reimbursement) { "required" -> " AND r.requires_reimbursement=1"; "notRequired" -> " AND r.requires_reimbursement=0"; else -> "" }
    private fun args(s: Scope, start: Long = s.start, end: Long = s.end) =
        (listOf(start.toString(), end.toString()) + if (s.channel.isEmpty()) emptyList() else listOf(s.channel)).toTypedArray()

    fun summary(input: JSONObject): JSONObject {
        val s = scope(input)
        fun totals(start: Long, end: Long): JSONObject {
            val out = JSONObject().put("income", 0).put("expense", 0).put("incomeCount", 0).put("expenseCount", 0)
            store.readableDatabase.rawQuery("SELECT r.direction,COALESCE(SUM($amount),0),COUNT(*) FROM records r WHERE ${where(s)} GROUP BY r.direction", args(s,start,end)).use { c ->
                while (c.moveToNext()) {
                    val key = if (c.getString(0) == "INCOME") "income" else "expense"
                    out.put(key,c.getLong(1)).put(key+"Count",c.getInt(2))
                }
            }
            return out.put("balance",out.getLong("income")-out.getLong("expense"))
        }
        val categories = JSONArray()
        store.readableDatabase.rawQuery("SELECT r.direction,r.category,SUM($amount),COUNT(*) FROM records r WHERE ${where(s)} GROUP BY r.direction,r.category ORDER BY SUM($amount) DESC,r.category",args(s)).use { c ->
            while(c.moveToNext()) categories.put(JSONObject().put("direction",c.getString(0)).put("name",c.getString(1)).put("amount",c.getLong(2)).put("count",c.getInt(3)))
        }
        fun date(at:Long)=java.time.Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
        var first=date(s.start);var last=date(s.end).minusDays(1)
        if(s.mode=="all")store.readableDatabase.rawQuery("SELECT MIN(r.occurred_at),MAX(r.occurred_at) FROM records r WHERE ${where(s)}",args(s)).use { c ->
            if(c.moveToFirst() && !c.isNull(0)){first=date(c.getLong(0));last=date(c.getLong(1))}else{first=LocalDate.now();last=first}
        }
        val span=java.time.temporal.ChronoUnit.DAYS.between(first,last)
        val unit=when(s.mode){"month"->"day";"year"->"month";else->if(span<=62)"day" else if(span<=1096)"month" else "year"}
        val pattern=when(unit){"day"->"%Y-%m-%d";"month"->"%Y-%m";else->"%Y"}
        val buckets=mutableMapOf<String,JSONObject>()
        store.readableDatabase.rawQuery("SELECT strftime('$pattern',r.occurred_at/1000,'unixepoch','localtime'),r.direction,SUM($amount) FROM records r WHERE ${where(s)} GROUP BY 1,2 ORDER BY 1",args(s)).use { c ->
            while(c.moveToNext())buckets.getOrPut(c.getString(0)){JSONObject().put("income",0).put("expense",0)}
                .put(if(c.getString(1)=="INCOME")"income" else "expense",c.getLong(2))
        }
        var cursor=when(unit){"month"->first.withDayOfMonth(1);"year"->first.withDayOfYear(1);else->first}
        val trend=JSONArray()
        while(cursor<=last){
            val key=when(unit){"day"->cursor.toString();"month"->cursor.toString().take(7);else->cursor.year.toString()}
            val next=when(unit){"day"->cursor.plusDays(1);"month"->cursor.plusMonths(1);else->cursor.plusYears(1)}
            val from=maxOf(cursor,date(s.start));val through=minOf(next.minusDays(1),date(s.end).minusDays(1))
            trend.put((buckets[key]?:JSONObject().put("income",0).put("expense",0)).put("index",trend.length()+1)
                .put("label",if(s.mode=="month")"${cursor.dayOfMonth}日" else if(s.mode=="year")"${cursor.monthValue}月" else key)
                .put("period","$from..$through"))
            cursor=next
        }
        val top=details(JSONObject(input.toString()).put("direction","EXPENSE").put("sort","amount").put("limit",10)).getJSONArray("records")
        return JSONObject().put("ok",true).put("totals",totals(s.start,s.end)).put("previous",totals(s.previous,s.start))
            .put("categories",categories).put("trend",trend).put("trendUnit",unit).put("top",top).put("period",input.optString("period","all"))
            .put("comparable",s.mode in setOf("month","year"))
    }

    fun details(input: JSONObject): JSONObject {
        val s=scope(input); val management=input.optBoolean("management",false)
        val selectedAmount=if(management)"COALESCE(r.amount,0)" else amount
        val parameters=args(s).toMutableList(); val condition=StringBuilder(where(s,management))
        val direction=input.optString("direction")
        require(direction in setOf("","INCOME","EXPENSE")) { "收支类型无效" }
        if(direction.isNotEmpty()) { condition.append(" AND r.direction=?");parameters+=direction }
        val category=input.optString("category");require(category.length<=1024)
        if(category.isNotEmpty()){condition.append(" AND r.category=?");parameters+=category}
        val search=input.optString("search").trim();require(search.length<=200)
        if(search.isNotEmpty()) {
            condition.append(" AND (r.display_name LIKE ? ESCAPE '\\' OR r.merchant LIKE ? ESCAPE '\\' OR r.note LIKE ? ESCAPE '\\' OR r.transaction_id LIKE ? ESCAPE '\\')")
            val term="%"+search.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"
            repeat(4){parameters+=term}
        }
        val offset=input.optInt("offset",0);val limit=input.optInt("limit",50)
        require(offset in 0..1_000_000 && limit in 1..200)
        val sort=input.optString("sort","time");require(sort in setOf("time","amount"))
        val order=if(sort=="amount")"$selectedAmount DESC,r.occurred_at DESC,r.id DESC" else "r.occurred_at DESC,r.id DESC"
        val rows=JSONArray()
        store.readableDatabase.rawQuery("SELECT r.id,COALESCE(r.display_name,r.merchant),r.category,r.occurred_at,$selectedAmount,r.direction,($CHANNEL_SQL),r.note,r.transaction_id,r.state FROM records r WHERE $condition ORDER BY $order LIMIT ${limit+1} OFFSET $offset",parameters.toTypedArray()).use { c ->
            while(c.moveToNext())rows.put(JSONObject().put("id",c.getLong(0)).put("merchant",c.getString(1)).put("category",c.getString(2))
                .put("occurredAt",c.getLong(3)).put("amountCents",c.getLong(4)).put("direction",c.getString(5)).put("channel",c.getString(6))
                .put("note",c.getString(7)).put("transactionId",if(c.isNull(8))JSONObject.NULL else c.getString(8)).put("state",c.getString(9)))
        }
        val more=rows.length()>limit;if(more)rows.remove(rows.length()-1)
        return JSONObject().put("ok",true).put("records",rows).put("hasMore",more)
    }
}
