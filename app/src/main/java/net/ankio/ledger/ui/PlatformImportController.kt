package net.ankio.ledger.ui

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.*
import net.ankio.ledger.data.PlatformStatements
import net.ankio.ledger.data.UnifiedBillImport
import net.ankio.ledger.data.LedgerCsvImport
import net.ankio.ledger.data.PlatformImportAccounts
import net.ankio.ledger.core.Money
import net.ankio.ledger.ledger
import org.json.JSONObject

internal class PlatformImportController(private val activity:MainActivity) {
    var previewSheet:MintDialog?=null
        private set
    fun open(platform:String) {
        require(platform in setOf("auto","wechat","alipay"))
        activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type="*/*";addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("text/csv","application/csv","text/comma-separated-values","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/octet-stream"))
        },when(platform){"wechat"->WECHAT;"alipay"->ALIPAY;else->AUTO})
    }

    fun read(uri:Uri,platform:String) { activity.uiScope.launch {
        activity.dispatch("fileOperation",JSONObject.quote("正在读取账单"))
        try {
            val fileName=withContext(Dispatchers.IO) {
                runCatching { activity.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst())it.getString(0) else null } }.getOrNull()?.take(255)?:"账单文件"
            }
            val imported=withContext(Dispatchers.IO) {
                (activity.contentResolver.openInputStream(uri)?:error("无法读取文件")).use {
                    if(platform=="auto")UnifiedBillImport.read(it) else UnifiedBillImport.Platform(PlatformStatements.read(it,platform))
                }
            }
            activity.dispatch("fileOperation","null")
            if(activity.isFinishing || activity.isDestroyed)return@launch
            if(imported is UnifiedBillImport.General){
                val preview=withContext(Dispatchers.IO){LedgerCsvImport.preview(activity.ledger.store,imported.document)}
                activity.previewBills(imported.document,preview,fileName);return@launch
            }
            if(imported is UnifiedBillImport.Jiezhang){previewJiezhang(imported,fileName);return@launch}
            val document=(imported as UnifiedBillImport.Platform).document
            val accountPreview=withContext(Dispatchers.IO){PlatformImportAccounts.preview(activity.ledger.store,document)}
            if(activity.isFinishing || activity.isDestroyed)return@launch
            val sheet=MintDialog(activity,"${if(document.platform=="wechat")"微信" else "支付宝"}账单导入","文件在本机解析")
            sheet.content.addView(sheet.text("可处理 ${document.entries.size} 笔",20,MintDialog.INK,true))
            sheet.content.addView(sheet.text("跳过 ${document.skipped} 行 · 需核对 ${document.errors.size} 行",14,MintDialog.MUTED))
            val dates=document.entries.map{it.bill.occurredAt}
            val date=java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.ROOT)
            if(dates.isNotEmpty())sheet.content.addView(sheet.text("${date.format(java.util.Date(dates.min()))} 至 ${date.format(java.util.Date(dates.max()))}",13,MintDialog.MUTED))
            for(direction in net.ankio.ledger.core.Direction.entries){
                val entries=document.entries.filter{it.bill.direction==direction}
                sheet.content.addView(sheet.text("${if(direction==net.ankio.ledger.core.Direction.INCOME)"收入" else "支出"} ${entries.size} 笔 · ¥${Money.format(entries.sumOf{it.bill.amountCents?:0})}",14,MintDialog.INK))
            }
            val accountPanel=PlatformImportAccountPanel(sheet,accountPreview)
            document.entries.take(4).forEach { entry -> sheet.content.addView(sheet.text("${entry.bill.merchant.take(24)} · ¥${Money.format(entry.bill.amountCents!!)}${if(entry.bill.refund!=null)"（退款后）" else ""}",14,MintDialog.INK)) }
            if(document.errors.isNotEmpty()) {
                sheet.label("以下行不会导入")
                document.errors.take(6).forEach { sheet.content.addView(sheet.text(it,12,MintDialog.MUTED)) }
            }
            sheet.content.addView(sheet.text("按订单号核对重复账单。已有账单的退款沿用原账户和余额规则；疑似重复记录进入待确认，确认前不影响余额。",13,MintDialog.MUTED))
            val confirm=if(document.entries.isNotEmpty())sheet.action("确认导入",true) {
                val selection=try { accountPanel.selection() } catch(error:IllegalArgumentException) {
                    activity.message(error.message?:"请核对关联账户");return@action
                }
                sheet.dismiss()
                activity.uiScope.launch {
                    activity.dispatch("fileOperation",JSONObject.quote("正在导入账单"))
                    try {
                        val result=withContext(Dispatchers.IO){ensureActive();PlatformStatements.apply(activity.ledger.store,document,fileName,selection)}
                        activity.ledger.changed()
                        activity.message("新增 ${result.saved}，更新 ${result.updated}，重复 ${result.duplicates}，待确认 ${result.review}")
                    } catch(cancelled:CancellationException){throw cancelled}
                    catch(error:Exception){activity.message(error.message?.take(80)?:"导入失败，账本未修改")}
                    finally{activity.dispatch("fileOperation","null")}
                }
            } else null
            val cancel=sheet.action("取消"){sheet.dismiss()}
            if(confirm!=null)sheet.fixedActions(cancel,confirm)
            previewSheet=sheet
            sheet.dialog.setOnDismissListener { if(previewSheet===sheet)previewSheet=null }
            sheet.show()
        } catch(cancelled:CancellationException){throw cancelled}
        catch(error:Exception){activity.message(error.message?.take(100)?:"无法读取账单文件")}
        finally{activity.dispatch("fileOperation","null")}
    } }
    private suspend fun previewJiezhang(document:UnifiedBillImport.Jiezhang,fileName:String) {
        val selection=withContext(Dispatchers.IO){UnifiedBillImport.preview(activity.ledger.store,document)}
        if(activity.isFinishing || activity.isDestroyed)return
        val sheet=MintDialog(activity,"导入账单预览","已识别：洁账 XLSX / CSV")
        sheet.content.addView(sheet.text("可导入 ${selection.fresh.size} 笔 · 重复 ${selection.duplicates} 笔",18,MintDialog.INK,true))
        sheet.content.addView(sheet.text("疑似重复 ${selection.possible.size} 笔将进入待确认；无效 ${document.errors.size} 行",13,MintDialog.MUTED))
        val dates=document.rows.map{it.record.bill.occurredAt}
        val date=java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.ROOT)
        if(dates.isNotEmpty())sheet.content.addView(sheet.text("${date.format(java.util.Date(dates.min()))} 至 ${date.format(java.util.Date(dates.max()))}",13,MintDialog.MUTED))
        for(direction in net.ankio.ledger.core.Direction.entries){
            val rows=selection.fresh.filter{it.record.bill.direction==direction}
            sheet.content.addView(sheet.text("${if(direction==net.ankio.ledger.core.Direction.INCOME)"收入" else "支出"} ${rows.size} 笔 · ¥${Money.format(rows.sumOf{it.record.bill.amountCents?:0})}",14,MintDialog.INK))
        }
        val accounts=synchronized(activity.ledger.store){activity.ledger.store.accounts()}
        val mapping=mutableMapOf<String,String?>()
        selection.fresh.map{it.asset}.distinct().forEach { asset ->
            sheet.label("原资产：$asset")
            val ids=listOf<String?>(null,"@new")+accounts.map{it.uid}
            val labels=listOf("暂不关联账户","新建同名账户（初始余额为 0）")+accounts.map{it.name}
            val spinner=android.widget.Spinner(activity)
            spinner.minimumHeight=sheet.dp(56)
            spinner.adapter=object:android.widget.ArrayAdapter<String>(activity,android.R.layout.simple_spinner_dropdown_item,labels) {
                private fun fit(view:android.view.View):android.view.View {
                    (view as android.widget.TextView).apply {
                        maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END
                        minimumHeight=sheet.dp(48)
                    }
                    return view
                }
                override fun getView(position:Int,convertView:android.view.View?,parent:android.view.ViewGroup)=fit(super.getView(position,convertView,parent))
                override fun getDropDownView(position:Int,convertView:android.view.View?,parent:android.view.ViewGroup)=fit(super.getDropDownView(position,convertView,parent))
            }
            val match=accounts.indexOfFirst{net.ankio.ledger.data.LedgerAccount.nameKey(it.name)==net.ankio.ledger.data.LedgerAccount.nameKey(asset)}
            val initial=if(match>=0)match+2 else 0;mapping[asset]=ids[initial];spinner.setSelection(initial)
            spinner.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:android.view.View?,position:Int,id:Long){mapping[asset]=ids[position]}
                override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
            }
            sheet.content.addView(spinner,android.widget.LinearLayout.LayoutParams(-1,-2))
        }
        document.errors.take(6).forEach{sheet.content.addView(sheet.text(it,12,MintDialog.MUTED))}
        sheet.content.addView(sheet.text("使用创建时间作为账单日期，保留父子分类和备注。原文件没有订单号与商户字段，疑似重复请核对后入账。关联账户会按已入账收支调整余额，导入历史账单后可在资产页校准余额。",13,MintDialog.MUTED))
        if(selection.fresh.isNotEmpty())sheet.action("确认导入",true){
            sheet.dismiss();activity.uiScope.launch {
                activity.dispatch("fileOperation",JSONObject.quote("正在导入账单"))
                try {
                    val result=withContext(Dispatchers.IO){UnifiedBillImport.apply(activity.ledger.store,document,mapping.toMap(),fileName)}
                    activity.ledger.changed();activity.message("已入账 ${result.first} 笔，待确认 ${result.second} 笔")
                }catch(cancelled:CancellationException){throw cancelled}
                catch(error:Exception){activity.message(error.message?.take(90)?:"导入失败，账本未修改")}
                finally{activity.dispatch("fileOperation","null")}
            }
        }
        sheet.action("取消"){sheet.dismiss()};sheet.show()
    }
    companion object { const val WECHAT=17;const val ALIPAY=18;const val AUTO=19 }
}
