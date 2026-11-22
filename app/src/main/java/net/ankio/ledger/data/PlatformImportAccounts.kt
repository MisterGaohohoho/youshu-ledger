package net.ankio.ledger.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/** File-import choices are independent of live-capture defaults. Account identity is always a UUID. */
object PlatformImportAccounts {
    data class Preference(val platform:String,val method:String,val accountUid:String?)
    data class Group(val method:String,val count:Int,val suggestedUid:String?,val requiresChoice:Boolean,val explanation:String) {
        val label get() = method.ifEmpty { "未提供支付方式" }
    }
    data class Preview(val accounts:List<LedgerAccount>,val groups:List<Group>)
    data class Selection(val accountsByMethod:Map<String,String?>,val affectsBalance:Boolean=false,val remember:Boolean=false)

    fun create(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS platform_import_accounts(platform TEXT NOT NULL,payment_method TEXT NOT NULL,account_uid TEXT,PRIMARY KEY(platform,payment_method))")
    }

    fun methodKey(value:String):String {
        val key=Normalizer.normalize(value,Normalizer.Form.NFKC).replace(Regex("\\s+"),"").lowercase(Locale.ROOT)
        require(key.length<=160) { "支付方式过长，请核对账单文件" }
        return if(key in setOf("/","-","--","不详","未知"))"" else key
    }

    fun preferences(store:LedgerStore):List<Preference> = store.readableDatabase.rawQuery(
        "SELECT platform,payment_method,account_uid FROM platform_import_accounts ORDER BY platform,payment_method",null
    ).use { c -> buildList { while(c.moveToNext())add(Preference(c.getString(0),c.getString(1),if(c.isNull(2))null else c.getString(2))) } }

    fun preview(store:LedgerStore,document:PlatformStatements.Document):Preview = synchronized(store) {
        require(document.platform in setOf("wechat","alipay")) { "支付平台无效" }
        val accounts=store.accounts()
        val saved=preferences(store).filter { it.platform==document.platform }.associateBy { it.method }
        val channel=store.channelDefaultAccounts()[document.platform]?.let { uid->accounts.find { it.uid==uid } }
        val matching=accounts.filter { it.kind==document.platform }
        val counts=document.entries.groupingBy { methodKey(it.paymentMethod) }.eachCount()
        require(counts.size<=200) { "支付方式过多，请分批导入" }
        val groups=counts.map { (method,count) ->
            val preference=saved[method]
            val wallet=method in if(document.platform=="wechat")setOf("零钱","微信零钱","零钱支付") else setOf("余额","支付宝余额","余额支付")
            when {
                preference!=null && (preference.accountUid==null || accounts.any { it.uid==preference.accountUid }) ->
                    Group(method,count,preference.accountUid,false,"沿用上次记住的导入账户")
                method.isNotEmpty() && !wallet && accounts.isNotEmpty() ->
                    Group(method,count,null,true,"请核对实际收付款账户；也可选择暂不关联")
                wallet && channel!=null && channel.kind !in setOf(document.platform,"custom") ->
                    Group(method,count,null,true,"支付方式与平台默认账户“${channel.name}”不一致，请选择")
                channel!=null -> Group(method,count,channel.uid,false,"使用已设置的平台默认账户")
                matching.size==1 -> Group(method,count,matching.single().uid,false,"已匹配同类型账户，可更改")
                matching.size>1 -> Group(method,count,null,true,"找到多个同类型账户，请选择")
                else -> Group(method,count,null,false,"暂无同类型账户，可选择已有账户或暂不关联")
            }
        }
        Preview(accounts,groups)
    }

    fun defaults(preview:Preview):Selection {
        require(preview.groups.none { it.requiresChoice }) { "请先核对各支付方式的关联账户" }
        return Selection(preview.groups.associate { it.method to it.suggestedUid })
    }

    internal fun validateSelection(store:LedgerStore,document:PlatformStatements.Document,selection:Selection) {
        require(document.platform in setOf("wechat","alipay")) { "支付平台无效" }
        require(selection.accountsByMethod.keys==document.entries.map { methodKey(it.paymentMethod) }.toSet()) { "账户选择与账单不一致，请重新导入" }
        val ids=store.accounts().mapTo(mutableSetOf()) { it.uid }
        require(selection.accountsByMethod.values.all { it==null || it in ids }) { "关联账户已不存在，请重新选择" }
    }

    internal fun remember(store:LedgerStore,platform:String,mapping:Map<String,String?>) {
        val values=mapping.map { (method,uid)->Preference(platform,method,uid) }
        validatePreferences(values,store.accounts())
        values.forEach { value -> store.writableDatabase.insertWithOnConflict("platform_import_accounts",null,ContentValues().apply {
            put("platform",value.platform);put("payment_method",value.method);put("account_uid",value.accountUid)
        },SQLiteDatabase.CONFLICT_REPLACE) }
    }

    internal fun validatePreferences(values:List<Preference>,accounts:List<LedgerAccount>) {
        require(values.size<=10_000 && values.map { it.platform to it.method }.distinct().size==values.size) { "导入账户偏好过多或重复" }
        val ids=accounts.mapTo(mutableSetOf()) { it.uid }
        values.forEach { value ->
            require(value.platform in setOf("wechat","alipay") && value.method==methodKey(value.method)) { "导入账户偏好格式错误" }
            require(value.accountUid==null || UUID.fromString(value.accountUid).toString()==value.accountUid && value.accountUid in ids) { "导入账户偏好引用了不存在的账户" }
        }
    }

    internal fun encode(values:List<Preference>):JSONArray = JSONArray().apply {
        values.forEach { put(JSONObject().put("platform",it.platform).put("method",it.method).put("accountUid",it.accountUid?:JSONObject.NULL)) }
    }

    internal fun decode(array:JSONArray,accounts:List<LedgerAccount>):List<Preference> {
        require(array.length()<=10_000) { "导入账户偏好过多" }
        return (0 until array.length()).map { index ->
            val value=array.getJSONObject(index)
            fun text(key:String)=value.get(key) as? String ?: error("导入账户偏好格式错误")
            require(value.has("accountUid")) { "导入账户偏好缺少账户字段" }
            Preference(text("platform"),text("method"),if(value.isNull("accountUid"))null else text("accountUid"))
        }.also { validatePreferences(it,accounts) }
    }
}
