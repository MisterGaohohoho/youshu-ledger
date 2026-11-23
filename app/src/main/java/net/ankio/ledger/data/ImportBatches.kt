package net.ankio.ledger.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Provenance belongs to the transaction that creates a record, never a matching date range. */
class ImportBatches(private val store: LedgerStore) {
    fun before(): Map<String, String> = snapshots(store.readableDatabase)

    /** Must run inside the import's existing transaction, after all account and refund effects. */
    fun finish(before: Map<String, String>, fileName: String, source: String, duplicates: Int, invalid: Int, dates: List<Long> = emptyList()): String {
        val db = store.writableDatabase
        check(db.inTransaction())
        val after = snapshots(db)
        val created = after.filterKeys { it !in before }
        val updated = after.count { (uid, value) -> uid in before && before[uid] != value }
        val uid = UUID.randomUUID().toString()
        val rows = store.all().filter { it.uid in created }
        val range=dates.ifEmpty { rows.map { it.bill.occurredAt } }
        val metadata = JSONObject().put("uid", uid).put("fileName", fileName.take(255).ifBlank { source + "账单" })
            .put("source", source.take(40)).put("importedAt", System.currentTimeMillis())
            .put("from", range.minOrNull() ?: 0L)
            .put("through", range.maxOrNull() ?: 0L)
            .put("created", created.size).put("review", rows.count { it.state.name == "REVIEW" })
            .put("updated", updated).put("duplicates", duplicates).put("invalid", invalid)
        db.insertOrThrow("import_batches", null, ContentValues().apply {
            put("uid", uid); put("imported_at", metadata.getLong("importedAt")); put("metadata", metadata.toString())
        })
        val ids=rows.associate { it.uid to it.id }
        created.forEach { (recordUid, fingerprint) ->
            db.insertOrThrow("import_batch_items", null, ContentValues().apply {
                put("batch_uid", uid); put("record_uid", recordUid); put("record_id", ids.getValue(recordUid)); put("fingerprint", fingerprint)
            })
        }
        return uid
    }

    fun list(offset: Int): JSONObject = synchronized(store) {
        require(offset in 0..1_000_000)
        val items = JSONArray()
        store.readableDatabase.rawQuery("SELECT metadata FROM import_batches ORDER BY imported_at DESC,uid DESC LIMIT 51 OFFSET ?", arrayOf(offset.toString())).use { c ->
            while (c.moveToNext()) items.put(summary(JSONObject(c.getString(0))))
        }
        val more = items.length() > 50
        if (more) items.remove(50)
        JSONObject().put("ok", true).put("items", items).put("hasMore", more)
    }

    fun detail(uid: String, offset: Int): JSONObject = synchronized(store) {
        require(offset in 0..1_000_000)
        val metadata = metadata(uid)
        val rows = JSONArray()
        store.readableDatabase.rawQuery("SELECT r.id,COALESCE(r.display_name,r.merchant),r.amount,r.direction,r.occurred_at,r.state FROM records r JOIN import_batch_items i ON i.record_uid=r.uid AND i.record_id=r.id WHERE i.batch_uid=? AND $ACTIVE ORDER BY r.occurred_at DESC,r.id DESC LIMIT 101 OFFSET ?", arrayOf(uid, offset.toString())).use { c ->
            while (c.moveToNext()) rows.put(JSONObject().put("id", c.getLong(0)).put("merchant", c.getString(1))
                .put("amount", if(c.isNull(2)) 0L else c.getLong(2)).put("direction", c.getString(3))
                .put("at", c.getLong(4)).put("state", c.getString(5)))
        }
        val more = rows.length() > 100
        if (more) rows.remove(100)
        JSONObject().put("ok", true).put("batch", summary(metadata)).put("rows", rows).put("hasMore", more)
    }

    fun metadata(uid: String): JSONObject {
        require(UUID.fromString(uid).toString() == uid)
        return store.readableDatabase.rawQuery("SELECT metadata FROM import_batches WHERE uid=?", arrayOf(uid)).use {
            require(it.moveToFirst()) { "导入记录不存在" }; JSONObject(it.getString(0))
        }
    }

    private fun summary(metadata: JSONObject): JSONObject {
        val uid = metadata.getString("uid")
        store.readableDatabase.rawQuery("SELECT COUNT(*),COALESCE(SUM(CASE WHEN r.direction='INCOME' THEN r.amount ELSE 0 END),0),COALESCE(SUM(CASE WHEN r.direction='EXPENSE' THEN r.amount ELSE 0 END),0) FROM records r JOIN import_batch_items i ON i.record_uid=r.uid AND i.record_id=r.id WHERE i.batch_uid=? AND $ACTIVE", arrayOf(uid)).use {
            it.moveToFirst(); metadata.put("remaining", it.getInt(0)).put("income", it.getLong(1)).put("expense", it.getLong(2))
        }
        return metadata
    }

    fun editedCount(uid: String): Int {
        val saved = mutableMapOf<String, String>()
        store.readableDatabase.rawQuery("SELECT i.record_uid,i.fingerprint FROM import_batch_items i JOIN records r ON r.uid=i.record_uid AND r.id=i.record_id WHERE i.batch_uid=? AND $ACTIVE", arrayOf(uid)).use {
            while(it.moveToNext()) saved[it.getString(0)] = it.getString(1)
        }
        val current = snapshots(store.readableDatabase)
        return saved.count { (key, fingerprint) -> current[key] != fingerprint }
    }

    fun backup(): JSONArray = synchronized(store) {
        val result = JSONArray()
        store.readableDatabase.rawQuery("SELECT uid,metadata FROM import_batches ORDER BY imported_at,uid", null).use { c ->
            while(c.moveToNext()) {
                val items = JSONArray()
                store.readableDatabase.rawQuery("SELECT i.record_uid,i.fingerprint FROM import_batch_items i JOIN records r ON r.uid=i.record_uid AND r.id=i.record_id WHERE i.batch_uid=? AND $ACTIVE", arrayOf(c.getString(0))).use {
                    while(it.moveToNext()) items.put(JSONObject().put("recordUid", it.getString(0)).put("fingerprint", it.getString(1)))
                }
                result.put(JSONObject(c.getString(1)).put("items", items))
            }
        }
        result
    }

    /** Existing local rows keep their provenance even when a backup claims the same UUID. */
    fun restore(batches: JSONArray, newlyRestored: Set<String>) {
        val db = store.writableDatabase
        check(db.inTransaction())
        val restoredIds=store.activeRecordIdsByUid()
        for(index in 0 until batches.length()) {
            val batch = JSONObject(batches.getJSONObject(index).toString())
            val items = batch.getJSONArray("items"); batch.remove("items")
            val uid = batch.getString("uid")
            val existing=db.rawQuery("SELECT metadata FROM import_batches WHERE uid=?",arrayOf(uid)).use { if(it.moveToFirst())JSONObject(it.getString(0)) else null }
            if(existing!=null)require(existing.length()==batch.length() && existing.keys().asSequence().all { existing.opt(it)==batch.opt(it) }) { "导入批次元数据冲突，请核对备份来源" }
            db.insertWithOnConflict("import_batches", null, ContentValues().apply {
                put("uid", uid); put("imported_at", batch.getLong("importedAt")); put("metadata", batch.toString())
            }, SQLiteDatabase.CONFLICT_IGNORE)
            for(i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                if(item.getString("recordUid") !in newlyRestored) continue
                db.insertWithOnConflict("import_batch_items", null, ContentValues().apply {
                    put("batch_uid", uid); put("record_uid", item.getString("recordUid")); put("record_id", restoredIds.getValue(item.getString("recordUid"))); put("fingerprint", item.getString("fingerprint"))
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }

    companion object {
        const val ACTIVE = "r.state IN ('POSTED','REVIEW') AND NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=r.id)"
        fun create(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS import_batches(uid TEXT PRIMARY KEY NOT NULL,imported_at INTEGER NOT NULL,metadata TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS import_batch_items(batch_uid TEXT NOT NULL,record_uid TEXT NOT NULL,record_id INTEGER NOT NULL UNIQUE,fingerprint TEXT NOT NULL,PRIMARY KEY(batch_uid,record_uid))")
            db.execSQL("CREATE INDEX IF NOT EXISTS import_items_batch ON import_batch_items(batch_uid)")
        }
        private fun snapshots(db: SQLiteDatabase): Map<String, String> = db.rawQuery("SELECT uid,amount,direction,merchant,category,occurred_at,note,account_uid,tags,requires_reimbursement,state,transaction_id,raw_text,affects_balance,account_selection_explicit FROM records", null).use { c ->
            buildMap { while(c.moveToNext()) {
                val values = JSONArray(); for(i in 1 until c.columnCount-2) values.put(if(c.isNull(i)) JSONObject.NULL else c.getString(i))
                // Preserve fingerprints of v16 batches whose accounting semantics have not changed.
                if(c.getInt(13)!=1 || c.getInt(14)!=0)values.put("account-settings").put(c.getInt(13)).put(c.getInt(14))
                val hash = MessageDigest.getInstance("SHA-256").digest(values.toString().toByteArray()).joinToString("") { "%02x".format(it) }
                put(c.getString(0), hash)
            } }
        }
        fun validate(batches: JSONArray, records: List<LedgerRecord>): JSONArray {
            require(batches.length() <= 100_000) { "导入记录过多" }
            val recordUids = records.mapTo(mutableSetOf()) { it.uid }; val batchUids = mutableSetOf<String>(); val owners = mutableSetOf<String>()
            for(i in 0 until batches.length()) {
                val b = batches.getJSONObject(i)
                val uid = b.getString("uid"); require(UUID.fromString(uid).toString() == uid && batchUids.add(uid))
                require(b.getString("fileName").length in 1..255 && b.getString("source").length in 1..40)
                for(key in listOf("importedAt", "from", "through", "created", "review", "updated", "duplicates", "invalid")) {
                    val value = b.get(key); require(value is Int || value is Long)
                    require((value as Number).toLong() in 0..if(key in setOf("importedAt","from","through"))4_133_980_800_000L else 100_000L)
                }
                require(b.getLong("importedAt") > 0 && b.getLong("from") <= b.getLong("through"))
                require(b.getLong("review") <= b.getLong("created"))
                val items = b.getJSONArray("items"); require(items.length() <= 100_000 && items.length().toLong() <= b.getLong("created"))
                for(j in 0 until items.length()) {
                    val item = items.getJSONObject(j); val recordUid = item.getString("recordUid")
                    require(recordUid in recordUids && owners.add(recordUid)) { "导入批次账单关联无效" }
                    require(item.getString("fingerprint").matches(Regex("[0-9a-f]{64}")))
                }
            }
            return batches
        }
    }
}
