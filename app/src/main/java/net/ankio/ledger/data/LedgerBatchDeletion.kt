package net.ankio.ledger.data

import org.json.JSONObject
import java.util.UUID

/** A confirmation belongs to an immutable selection, never to a later page of results. */
class LedgerBatchDeletion(private val store:LedgerStore) {
    private data class Row(val id:Long,val amount:Long,val direction:String,val state:String,val fingerprint:List<String?>)
    private data class Plan(val token:String,val request:String,val rows:List<Row>,val at:Long)
    private var plan:Plan?=null

    fun preview(request:String,now:Long=System.currentTimeMillis()):JSONObject = synchronized(store) {
        val rows=select(request)
        val next=Plan(UUID.randomUUID().toString(),request,rows,now);plan=next
        JSONObject().put("ok",true).put("token",next.token).put("count",rows.size)
            .put("income",rows.filter { it.direction=="INCOME" }.sumOf { it.amount })
            .put("expense",rows.filter { it.direction!="INCOME" }.sumOf { it.amount })
            .put("reviewCount",rows.count { it.state=="REVIEW" }).also { result ->
                JSONObject(request).optString("importBatch").takeIf { it.isNotEmpty() }?.let { uid ->
                    val batches=ImportBatches(store)
                    result.put("batch",batches.metadata(uid)).put("editedCount",batches.editedCount(uid))
                }
            }
    }

    fun commit(token:String,now:Long=System.currentTimeMillis()):DeletedRecord = synchronized(store) {
        val pending=plan
        require(pending!=null && pending.token==token && now-pending.at in 0..300_000) { "确认已过期，请重新选择" }
        require(pending.rows.isNotEmpty()) { "请先选择账单" }
        // Re-read financial fields as well as membership. Background OCR or editing must not
        // silently change the count or amounts the user just confirmed.
        require(select(pending.request)==pending.rows) { "账单已变化，请重新核对删除范围" }
        store.softDeleteBatch(pending.rows.map { it.id },now).also { plan=null }
    }

    private fun select(request:String):List<Row> {
        require(request.length<=1_048_576) { "选择范围过大" }
        val q=JSONObject(request)
        val batch=q.optString("importBatch")
        val (where,args)=if(batch.isNotEmpty()) {
            ImportBatches(store).metadata(batch)
            "EXISTS(SELECT 1 FROM import_batch_items i WHERE i.record_uid=r.uid AND i.record_id=r.id AND i.batch_uid=?) AND ${ImportBatches.ACTIVE}" to listOf(batch)
        } else LedgerReadView(store).matchingWhere(q.getString("period"),q.optString("search"),q.optString("direction"),
            reimbursement=q.optString("reimbursement"),channel=q.optString("channel"))
        require(q.get("all") is Boolean) { "选择方式无效" }
        val all=q.getBoolean("all");val array=q.getJSONArray(if(all)"excluded" else "ids")
        val ids=buildSet { repeat(array.length()) { index ->
            val id=array.getLong(index);require(id in 1..9_007_199_254_740_991L);add(id)
        } }
        if(!all && ids.isEmpty())return emptyList()
        return store.readableDatabase.rawQuery("SELECT r.id,r.amount,r.direction,r.state,r.uid,r.merchant,r.category,r.occurred_at,r.note,r.account_uid,r.account_effect,r.requires_reimbursement,r.tags,r.transaction_id,r.affects_balance,r.account_selection_explicit FROM records r WHERE ($where) AND r.state IN ('POSTED','REVIEW') ORDER BY r.id",args.toTypedArray()).use { c ->
            buildList { while(c.moveToNext()) {
                val id=c.getLong(0)
                if(if(all)id !in ids else id in ids)add(Row(id,if(c.isNull(1))0 else c.getLong(1),c.getString(2).orEmpty(),c.getString(3),
                    (4 until c.columnCount).map { if(c.isNull(it))null else c.getString(it) }))
            } }
        }
    }
}
