package net.ankio.ledger.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import net.ankio.ledger.core.Direction
import net.ankio.ledger.core.Money
import net.ankio.ledger.core.PaymentApps
import net.ankio.ledger.core.RecordState
import net.ankio.ledger.core.Source

/** Runs inside LedgerStore's transaction. Stored effects make every reversal idempotent. */
internal object AccountPostings {
    fun create(db:SQLiteDatabase) {
        fun add(table:String,column:String,definition:String) {
            val exists=db.rawQuery("PRAGMA table_info($table)",null).use { c->
                var found=false;while(c.moveToNext())if(c.getString(1)==column)found=true;found
            }
            if(!exists)db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
        }
        add("records","account_uid","TEXT")
        add("records","account_effect","INTEGER NOT NULL DEFAULT 0")
        add("records","affects_balance","INTEGER NOT NULL DEFAULT 1 CHECK(affects_balance IN (0,1))")
        add("records","account_selection_explicit","INTEGER NOT NULL DEFAULT 0 CHECK(account_selection_explicit IN (0,1))")
        // Keep the legacy nonnegative base, allowing an overdraft in its signed adjustment.
        add("accounts","balance_adjustment","INTEGER NOT NULL DEFAULT 0")
        add("accounts","included_in_assets","INTEGER NOT NULL DEFAULT 1 CHECK(included_in_assets IN (0,1))")
        db.execSQL("CREATE INDEX IF NOT EXISTS records_account_time ON records(account_uid,occurred_at DESC,id DESC)")
        db.execSQL("CREATE TABLE IF NOT EXISTS account_preferences(singleton INTEGER PRIMARY KEY CHECK(singleton=1),default_uid TEXT)")
        add("account_preferences","wechat_uid","TEXT")
        add("account_preferences","alipay_uid","TEXT")
    }

    private val channelColumns=mapOf("wechat" to "wechat_uid","alipay" to "alipay_uid")

    fun channelDefaults(db:SQLiteDatabase):Map<String,String> = channelColumns.mapNotNull { (channel,column) ->
        db.rawQuery("SELECT a.uid FROM account_preferences p JOIN accounts a ON a.uid=p.$column WHERE p.singleton=1",null)
            .use { if(it.moveToFirst())channel to it.getString(0) else null }
    }.toMap()

    fun defaultUid(db:SQLiteDatabase,channel:String?=null):String? = channelDefaults(db)[channel] ?: db.rawQuery(
        "SELECT uid FROM accounts ORDER BY CASE WHEN uid=(SELECT default_uid FROM account_preferences WHERE singleton=1) THEN 0 ELSE 1 END,id LIMIT 1",null
    ).use { if(it.moveToFirst())it.getString(0) else null }

    fun setDefault(db:SQLiteDatabase,uid:String?) {
        require(uid==null || account(db,uid)!=null) { "账户已不存在" }
        updatePreference(db,"default_uid",uid)
    }

    fun setChannelDefault(db:SQLiteDatabase,channel:String,uid:String?) {
        val column=requireNotNull(channelColumns[channel]) { "不支持的支付渠道" }
        require(uid==null || account(db,uid)!=null) { "账户已不存在" }
        updatePreference(db,column,uid)
    }

    private fun updatePreference(db:SQLiteDatabase,column:String,uid:String?) {
        db.execSQL("INSERT OR IGNORE INTO account_preferences(singleton) VALUES (1)")
        db.update("account_preferences",ContentValues().apply { put(column,uid) },"singleton=1",null)
    }

    fun clearDeletedAccount(db:SQLiteDatabase,uid:String) {
        db.delete("platform_import_accounts","account_uid=?",arrayOf(uid))
        for(column in listOf("default_uid")+channelColumns.values)
            db.update("account_preferences",ContentValues().apply { putNull(column) },"$column=?",arrayOf(uid))
    }

    internal fun account(db:SQLiteDatabase,uid:String):Pair<Long,AccountType>? = db.rawQuery(
        "SELECT amount+balance_adjustment,type FROM accounts WHERE uid=?",arrayOf(uid)
    ).use { if(it.moveToFirst())it.getLong(0) to AccountType.valueOf(it.getString(1)) else null }

    internal fun adjust(db:SQLiteDatabase,uid:String,delta:Long) {
        if(delta==0L)return
        val current=account(db,uid)?:return
        val next=Math.addExact(current.first,delta)
        require(next in -Money.MAX_CENTS..Money.MAX_CENTS) { "账户余额超出范围" }
        db.execSQL("UPDATE accounts SET balance_adjustment=balance_adjustment+? WHERE uid=?",arrayOf(delta,uid))
    }

    internal fun effect(type:AccountType,amount:Long,direction:Direction):Long {
        val signed=if(direction==Direction.EXPENSE)-amount else amount
        return if(type==AccountType.LIABILITY)-signed else signed
    }

    fun synchronize(db:SQLiteDatabase,id:Long,assignDefault:Boolean=false) {
        val stored=db.rawQuery("SELECT account_uid,account_effect,state,amount,direction,source,origin,affects_balance,account_selection_explicit FROM records WHERE id=?",arrayOf(id.toString())).use {
            if(!it.moveToFirst())return
            Entry(if(it.isNull(0))null else it.getString(0),it.getLong(1),it.getString(2),if(it.isNull(3))null else it.getLong(3),if(it.isNull(4))null else it.getString(4),it.getString(5),it.getString(6),it.getInt(7)==1,it.getInt(8)==1)
        }
        var uid=stored.uid
        if(uid==null && assignDefault && !stored.explicit && stored.state==RecordState.POSTED.name) {
            val channel=if(stored.source==Source.MANUAL.name)null else when(stored.origin) {
                PaymentApps.WECHAT -> "wechat"
                PaymentApps.ALIPAY -> "alipay"
                else -> null
            }
            uid=defaultUid(db,channel)
        }
        val target=uid?.let { account(db,it) }
        val deleted=db.rawQuery("SELECT 1 FROM record_deletions WHERE record_id=?",arrayOf(id.toString())).use { it.moveToFirst() }
        val effect=if(target!=null && stored.affectsBalance && stored.state==RecordState.POSTED.name && !deleted && stored.amount!=null && stored.direction!=null) {
            effect(target.second,stored.amount,Direction.valueOf(stored.direction))
        }else 0L
        if(uid!=null)adjust(db,uid,effect-stored.effect)
        db.update("records",ContentValues().apply { put("account_uid",uid);put("account_effect",effect) },"id=?",arrayOf(id.toString()))
    }

    /** Net by account first, so an offsetting batch cannot overflow at an intermediate row. */
    fun synchronizeBatch(db:SQLiteDatabase,ids:List<Long>) {
        val deltas=mutableMapOf<String,Long>();val effects=mutableListOf<Pair<Long,Long>>()
        for(id in ids)db.rawQuery("SELECT account_uid,account_effect,state,amount,direction,EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=r.id),affects_balance FROM records r WHERE id=?",arrayOf(id.toString())).use { c ->
            check(c.moveToFirst()) { "账单已不存在" }
            val uid=if(c.isNull(0))null else c.getString(0);val target=uid?.let { account(db,it) }
            val next=if(target!=null && c.getString(2)=="POSTED" && !c.isNull(3) && !c.isNull(4) && c.getInt(5)==0 && c.getInt(6)==1)
                effect(target.second,c.getLong(3),Direction.valueOf(c.getString(4))) else 0L
            if(uid!=null)deltas[uid]=Math.addExact(deltas[uid]?:0L,next-c.getLong(1))
            effects+=id to next
        }
        deltas.forEach { (uid,delta)->adjust(db,uid,delta) }
        effects.forEach { (id,effect)->db.execSQL("UPDATE records SET account_effect=? WHERE id=?",arrayOf(effect,id)) }
    }

    fun select(db:SQLiteDatabase,id:Long,uid:String?) {
        require(uid==null || account(db,uid)!=null) { "账户已不存在，请重新选择" }
        db.execSQL("UPDATE records SET account_selection_explicit=1 WHERE id=?",arrayOf(id))
        val unchanged=db.rawQuery("SELECT account_uid FROM records WHERE id=?",arrayOf(id.toString())).use {
            it.moveToFirst() && (if(it.isNull(0))null else it.getString(0))==uid
        }
        if(unchanged) { synchronize(db,id);return }
        reverse(db,id)
        db.update("records",ContentValues().apply { put("account_uid",uid) },"id=?",arrayOf(id.toString()))
        synchronize(db,id)
    }

    fun reverse(db:SQLiteDatabase,id:Long) {
        db.rawQuery("SELECT account_uid,account_effect FROM records WHERE id=?",arrayOf(id.toString())).use {
            if(it.moveToFirst() && !it.isNull(0))adjust(db,it.getString(0),-it.getLong(1))
        }
        db.update("records",ContentValues().apply { put("account_effect",0) },"id=?",arrayOf(id.toString()))
    }

    private data class Entry(val uid:String?,val effect:Long,val state:String,val amount:Long?,val direction:String?,val source:String,val origin:String,
                             val affectsBalance:Boolean,val explicit:Boolean)
}
