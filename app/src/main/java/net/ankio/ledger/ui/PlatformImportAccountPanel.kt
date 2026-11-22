package net.ankio.ledger.ui

import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.*
import net.ankio.ledger.data.PlatformImportAccounts

/** The preview owns a frozen selection; confirmation never silently rereads changed defaults. */
internal class PlatformImportAccountPanel(private val sheet:MintDialog,private val preview:PlatformImportAccounts.Preview) {
    private val choices=preview.groups.filterNot { it.requiresChoice }.associate { it.method to it.suggestedUid }.toMutableMap()
    private val adjust=RadioButton(sheet.context)
    private val remember=CheckBox(sheet.context)

    init {
        sheet.label("关联账户")
        preview.groups.forEachIndexed { index,group ->
            sheet.content.addView(sheet.text("${group.label} · ${group.count} 笔",14,MintDialog.INK,true).apply {
                setPadding(0,sheet.dp(8),0,sheet.dp(4))
            })
            val labels=(if(group.requiresChoice)listOf("请选择账户或暂不关联") else emptyList())+
                listOf("暂不关联账户")+preview.accounts.map { it.name }
            val noneIndex=if(group.requiresChoice)1 else 0
            val spinner=Spinner(sheet.context).apply {
                tag="platform-import-account-$index"
                contentDescription="${group.label}关联账户"
                minimumHeight=sheet.dp(48)
                background=sheet.shape(MintDialog.PALE,12)
                adapter=object:ArrayAdapter<String>(sheet.context,android.R.layout.simple_spinner_dropdown_item,labels) {
                    private fun fit(view:View):View=(view as TextView).apply {
                        maxLines=2;ellipsize=TextUtils.TruncateAt.END;minimumHeight=sheet.dp(48)
                        setTextColor(MintDialog.INK);textSize=14f;setPadding(sheet.dp(12),sheet.dp(8),sheet.dp(12),sheet.dp(8))
                    }
                    override fun getView(position:Int,convertView:View?,parent:ViewGroup)=fit(super.getView(position,convertView,parent))
                    override fun getDropDownView(position:Int,convertView:View?,parent:ViewGroup)=fit(super.getDropDownView(position,convertView,parent))
                }
                val accountIndex=preview.accounts.indexOfFirst { it.uid==group.suggestedUid }
                setSelection(if(group.requiresChoice)0 else if(accountIndex>=0)noneIndex+1+accountIndex else noneIndex)
                onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) {
                        if(group.requiresChoice && position==0)choices.remove(group.method)
                        else choices[group.method]=if(position==noneIndex)null else preview.accounts[position-noneIndex-1].uid
                    }
                    override fun onNothingSelected(parent:AdapterView<*>?) {}
                }
            }
            sheet.content.addView(spinner,LinearLayout.LayoutParams(-1,-2))
            sheet.content.addView(sheet.text(group.explanation,12,MintDialog.MUTED).apply { setPadding(0,sheet.dp(4),0,sheet.dp(4)) })
        }
        val tint=ColorStateList.valueOf(MintDialog.GREEN)
        remember.apply {
            tag="platform-import-remember";text="记住账户选择，供下次同平台导入使用"
            textSize=13f;setTextColor(MintDialog.MUTED);buttonTintList=tint;minHeight=sheet.dp(48)
        }
        sheet.content.addView(remember,LinearLayout.LayoutParams(-1,-2))
        sheet.label("余额处理")
        val keep=RadioButton(sheet.context).apply {
            id=View.generateViewId();tag="platform-import-keep-balance";text="保留当前余额（推荐）"
        }
        adjust.apply { id=View.generateViewId();tag="platform-import-adjust-balance";text="按新增收支调整余额" }
        val radios=RadioGroup(sheet.context).apply {
            orientation=RadioGroup.VERTICAL
            listOf(keep,adjust).forEach { button ->
                button.textSize=14f;button.setTextColor(MintDialog.INK);button.buttonTintList=tint;button.minHeight=sheet.dp(48)
                addView(button,RadioGroup.LayoutParams(-1,-2))
            }
            check(keep.id)
        }
        sheet.content.addView(radios)
        val explanation=sheet.text("适合补充历史账单。新增记录计入账户明细与收支统计，修改、退款或删除这些记录也不增减余额。",12,MintDialog.MUTED)
        sheet.content.addView(explanation)
        radios.setOnCheckedChangeListener { _,id ->
            explanation.text=if(id==keep.id)"适合补充历史账单。新增记录计入账户明细与收支统计，修改、退款或删除这些记录也不增减余额。"
                else "适合从期初余额补账。新增账单按收支联动账户余额，修改、退款、删除和撤销也同步调整。"
        }
    }

    fun selection():PlatformImportAccounts.Selection {
        require(preview.groups.all { choices.containsKey(it.method) }) { "请先核对各支付方式的关联账户，也可选择暂不关联" }
        return PlatformImportAccounts.Selection(choices.toMap(),adjust.isChecked,remember.isChecked)
    }
}
