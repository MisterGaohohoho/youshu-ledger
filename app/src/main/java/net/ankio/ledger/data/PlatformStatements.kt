package net.ankio.ledger.data

import android.util.Xml
import net.ankio.ledger.core.*
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.zip.ZipInputStream

/** Official statement files are parsed locally. Import reuses the ledger's identity/refund engine. */
object PlatformStatements {
    data class Entry(val capture: Capture, val bill: ParsedBill, val note: String, val paymentMethod:String="")
    data class Document(val platform: String, val entries: List<Entry>, val skipped: Int, val errors: List<String>)
    data class Result(val saved: Int, val updated: Int, val duplicates: Int, val review: Int)
    private data class Row(val line: Int, val at: Long, val amount: Long, val direction: Direction?, val merchant: String,
                           val category: String, val order: String?, val merchantOrder: String?, val note: String,
                           val refundRow: Boolean, val refunded: Long, val paymentMethod:String)

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=limit){"文件过大，请分批导入"};out.write(buffer,0,n)}
        return out.toByteArray()
    }

    fun read(input: InputStream, platform: String): Document {
        require(platform in setOf("wechat","alipay")){"支付平台无效"}
        return fromRows(readRows(input),platform)
    }

    internal fun readRows(input:InputStream):List<List<String>> {
        val bytes=readLimited(input,8*1024*1024)
        val rows=if(bytes.size>2 && bytes[0]==80.toByte() && bytes[1]==75.toByte()) xlsx(bytes) else {
            require(!(bytes.size>1 && bytes[0]==0xD0.toByte() && bytes[1]==0xCF.toByte())){"请将旧版 XLS 转为 XLSX 或 CSV 后导入"}
            val text=try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
            catch(_:java.nio.charset.CharacterCodingException){java.nio.charset.Charset.forName("GB18030").newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()}
            LedgerCsvImport.parse(text.removePrefix("\uFEFF"))
        }
        return rows
    }

    internal fun fromRows(rows: List<List<String>>, platform: String): Document {
        require(platform in setOf("wechat","alipay"))
        fun clean(value:String)=value.trim().removePrefix("\uFEFF").removePrefix("'").trim()
        val headerIndex=rows.indexOfFirst { row -> row.map(::clean).let { "交易时间" in it && ("收/支" in it || "收支" in it) && it.any { h->h.startsWith("金额") } } }
        require(headerIndex>=0){"没有找到官方账单表头，请选择微信或支付宝导出的 XLSX / CSV"}
        val header=rows[headerIndex].map(::clean)
        require(header.distinct().size==header.size){"账单表头包含重复字段"}
        // Do not silently file an Alipay export under WeChat or vice versa.
        if("交易分类" in header || "收/付款方式" in header)require(platform=="alipay"){"这是支付宝账单，请从支付宝模块导入"}
        if("当前状态" in header || "商品" in header)require(platform=="wechat"){"这是微信账单，请从微信模块导入"}
        val parsed=mutableListOf<Row>();val errors=mutableListOf<String>();var skipped=0
        for((index,cells) in rows.drop(headerIndex+1).withIndex()) {
            val line=headerIndex+index+2
            if(cells.all { it.isBlank() } || cells.firstOrNull()?.trim()?.startsWith("---")==true)continue
            try {
                fun field(vararg names:String):String { val i=header.indexOfFirst { it in names };return if(i<0)"" else clean(cells.getOrElse(i){""}) }
                val time=field("交易时间");if(time.isBlank()){skipped++;continue}
                val status=field("当前状态","交易状态")
                if(status.contains("关闭") || status.contains("失败") || status.contains("等待") || status.contains("处理中")){skipped++;continue}
                val type=field("收/支","收支");val merchant=field("交易对方").ifBlank { "未填写商户" }
                val description=field("商品","商品说明");val category=field("交易分类","交易类型").ifBlank{"其他"}
                val refundRow=type!="支出" && (listOf(description,category,status).any { it.contains("退款") || it.contains("退票") })
                val direction=when(type){"收入"->Direction.INCOME;"支出"->Direction.EXPENSE;else->null}
                if(direction==null && !refundRow){skipped++;continue}
                val amount=Money.parseCents(field("金额(元)","金额（元）","金额"))?:error("金额无效")
                require(amount>0){"金额须大于零"}
                val at=if(Regex("[0-9]{4,6}(\\.[0-9]+)?").matches(time)) {
                    val seconds=java.math.BigDecimal(time).multiply(java.math.BigDecimal(86400)).setScale(0,java.math.RoundingMode.HALF_UP).longValueExact()
                    LocalDateTime.of(1899,12,30,0,0).plusSeconds(seconds).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                } else LocalDateTime.parse(time.replace('/','-'),DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                require(at>0 && at<4133980800000L){"交易时间无效"}
                fun order(value:String)=value.takeIf { it.isNotBlank() && it!="/" && it!="-" }?.also { require(it.length in 4..160 && it.all { c->c.isLetterOrDigit() || c in "-_" }){"订单号无效"} }
                val refundMatch=Regex("已退款[（(]\\s*[¥￥]?([0-9]+(?:\\.[0-9]{1,2})?)[）)]").find(status)
                val refunded=refundMatch?.groupValues?.get(1)?.let { Money.parseCents(it) }
                    ?: if(status in setOf("已退款","退款成功","已全额退款"))amount else 0L
                require(refunded<=amount){"退款超过原付款金额"}
                require(merchant.length<=1024 && category.length<=1024){"商户或分类过长"}
                parsed+=Row(line,at,amount,direction,merchant,category,order(field("交易单号","交易订单号")),order(field("商户单号","商家订单号")),
                    listOf(description,field("备注")).filter { it.isNotBlank() && it!="/" }.joinToString("；").take(500),refundRow,refunded,
                    PlatformImportAccounts.methodKey(field("支付方式","收/付款方式")))
            } catch(error:Exception){errors+="第 $line 行：${error.message ?: "数据无效"}"}
        }
        val consumed=mutableSetOf<Row>();val entries=mutableListOf<Entry>();val occurrences=mutableMapOf<String,Int>()
        val refunds=parsed.filter { it.refundRow }
        for(row in parsed.filterNot { it.refundRow }) {
            val matching=if(platform=="alipay" && row.direction==Direction.EXPENSE && row.merchantOrder!=null &&
                parsed.count { !it.refundRow && it.direction==Direction.EXPENSE && it.merchantOrder==row.merchantOrder }==1)
                refunds.filter { it.merchantOrder==row.merchantOrder && it.merchant==row.merchant } else emptyList()
            // Refunds need distinct transaction IDs; ambiguous evidence is left for review.
            if(matching.any { it.order==null }) { errors+="第 ${row.line} 行：退款缺少交易单号，请核对后导入";continue }
            val refundTotal=matching.distinctBy { it.order }.sumOf { it.amount }
            val refunded=maxOf(row.refunded,refundTotal)
            if(refunded>row.amount){errors+="第 ${row.line} 行：累计退款超过原付款";continue}
            consumed.addAll(matching)
            val origin=if(platform=="wechat")PaymentApps.WECHAT else PaymentApps.ALIPAY
            val payload=listOf(platform,row.at,row.amount,row.direction,row.merchant,row.order,row.merchantOrder,refunded,row.note).joinToString("\u0000")
            val hash=MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString(""){"%02x".format(it)}
            val occurrence=occurrences.getOrDefault(hash,0);occurrences[hash]=occurrence+1
            val capture=Capture(Source.MANUAL,origin,"账单文件导入：$hash:$occurrence\n${row.note}")
            val bill=ParsedBill(row.amount-refunded,row.direction,row.merchant,row.category,row.at,row.order,true,"",true,
                if(refunded>0 && row.direction==Direction.EXPENSE)RefundInfo(platform,row.amount,refunded) else null)
            // Keep the existing capture fingerprint stable when adding payment-method metadata.
            entries+=Entry(capture,bill,row.note,row.paymentMethod)
        }
        refunds.filterNot { it in consumed }.forEach { errors+="第 ${it.line} 行：退款未找到唯一原支出，请包含原支出一起导入" }
        return Document(platform,entries,skipped,errors)
    }

    fun apply(store:LedgerStore,document:Document,fileName:String="账单文件",selection:PlatformImportAccounts.Selection?=null):Result=synchronized(store) {
        val db=store.writableDatabase;db.beginTransaction()
        try {
            val chosen=selection ?: PlatformImportAccounts.defaults(PlatformImportAccounts.preview(store,document))
            PlatformImportAccounts.validateSelection(store,document,chosen)
            val batches=ImportBatches(store);val before=batches.before()
            val existing=store.all()
            val known=existing.mapTo(mutableSetOf()){it.capture.text}
            val ids=existing.mapTo(mutableSetOf()){it.id};var saved=0;var updated=0;var duplicate=0;var review=0
            for(entry in document.entries) {
                if(entry.capture.text in known){duplicate++;continue}
                val account=RecordAccountSelection(chosen.accountsByMethod[PlatformImportAccounts.methodKey(entry.paymentMethod)],chosen.affectsBalance)
                when(val result=store.ingest(entry.capture,entry.bill,accountSelection=account)) {
                    is IngestResult.Saved -> {
                        val created=ids.add(result.record.id)
                        if(created){if(result.record.state==RecordState.REVIEW)review++ else saved++}
                        else if(result.updated)updated++ else duplicate++
                        // Notes and selection also belong to review drafts, without posting them.
                        if(created)db.update("records",android.content.ContentValues().apply { put("note",entry.note) },"id=?",arrayOf(result.record.id.toString()))
                        known+=entry.capture.text
                    }
                    else -> duplicate++
                }
            }
            batches.finish(before,fileName,if(document.platform=="wechat")"微信" else "支付宝",duplicate,document.errors.size+document.skipped,document.entries.map { it.bill.occurredAt })
            if(chosen.remember)PlatformImportAccounts.remember(store,document.platform,chosen.accountsByMethod)
            db.setTransactionSuccessful();Result(saved,updated,duplicate,review)
        } finally { db.endTransaction() }
    }

    private fun xlsx(bytes:ByteArray):List<List<String>> {
        val files=mutableMapOf<String,ByteArray>();var total=0;var count=0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while(true){val entry=zip.nextEntry?:break;require(++count<=1000){"工作簿文件过多"}
                val data=readLimited(zip,32*1024*1024-total);total+=data.size
                if(entry.name in setOf("xl/sharedStrings.xml","xl/worksheets/sheet1.xml")){
                    require(!files.containsKey(entry.name)){"工作簿包含重复文件"};files[entry.name]=data
                };zip.closeEntry()
            }
        }
        fun xml(data:ByteArray):XmlPullParser {
            val text=data.toString(Charsets.UTF_8);require(!text.contains("<!DOCTYPE") && !text.contains("<!ENTITY")){"不支持包含外部实体的工作簿"}
            return Xml.newPullParser().apply { setInput(text.reader()) }
        }
        val shared=mutableListOf<String>()
        files["xl/sharedStrings.xml"]?.let { data ->
            val p=xml(data);var value=StringBuilder()
            while(p.eventType!=XmlPullParser.END_DOCUMENT){
                if(p.eventType==XmlPullParser.START_TAG && p.name=="si")value=StringBuilder()
                if(p.eventType==XmlPullParser.START_TAG && p.name=="t")value.append(p.nextText())
                if(p.eventType==XmlPullParser.END_TAG && p.name=="si"){require(shared.size<200_000 && value.length<=32768);shared+=value.toString()};p.next()
            }
        }
        val p=xml(requireNotNull(files["xl/worksheets/sheet1.xml"]){"请上传解压后的官方 XLSX 账单"})
        val rows=mutableListOf<List<String>>();var row=mutableListOf<String>();var column=0;var type="";var value=""
        while(p.eventType!=XmlPullParser.END_DOCUMENT){
            if(p.eventType==XmlPullParser.START_TAG)when(p.name){
                "row" -> row=mutableListOf()
                "c" -> { val reference=p.getAttributeValue(null,"r")?:error("单元格缺少位置");column=reference.takeWhile { it.isLetter() }.fold(0){a,c->a*26+(c.uppercaseChar()-'A'+1)}-1;require(column in 0..63);type=p.getAttributeValue(null,"t").orEmpty();value="" }
                "v","t" -> {value+=p.nextText();require(value.length<=32768)}
                "f" -> error("账单中含有公式，请导出原始账单后重试")
            }
            if(p.eventType==XmlPullParser.END_TAG)when(p.name){
                "c" -> {while(row.size<=column)row.add("");row[column]=if(type=="s")shared.getOrNull(value.toIntOrNull()?:-1)?:error("工作簿文本索引无效") else value}
                "row" -> {if(row.any { it.isNotBlank() })rows+=row;require(rows.size<=50_000){"最多导入 50000 行"}}
            };p.next()
        }
        return rows
    }
}
