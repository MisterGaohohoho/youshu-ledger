package net.ankio.ledger.capture

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import net.ankio.ledger.data.LedgerRecord

/** One inline draft owns focus until an explicit save/cancel or the result is retired. */
internal class ResultTextEditor(private val context: Context) {
    enum class Field { NAME, NOTE }
    private var input: EditText? = null
    private var host: LinearLayout? = null
    private var onClose: (() -> Unit)? = null
    private var session = 0
    var field: Field? = null
        private set
    val showing get() = input != null

    fun show(field: Field, record: LedgerRecord, card: LinearLayout, save: (String) -> String?, closed: () -> Unit): Boolean {
        if (showing) return false
        val target = card.findViewWithTag<LinearLayout>("result-editor-host") ?: return false
        val colors = ResultColors(context)
        val note = field == Field.NOTE
        val token = ++session
        val title = colors.text(if (note) "备注" else "修改显示名", 14, true).apply { id = View.generateViewId() }
        target.addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = colors.dp(8) })
        val editor = EditText(context).apply {
            tag = "result-editor-input"
            textSize = 16f; setTextColor(colors.ink); setHintTextColor(colors.secondary)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                if (note) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
            if (note) { minLines = 2; maxLines = 3; gravity = Gravity.TOP or Gravity.START }
            else setSingleLine(true)
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
                if (note) EditorInfo.IME_FLAG_NO_ENTER_ACTION else EditorInfo.IME_ACTION_DONE
            filters = arrayOf(InputFilter.LengthFilter(if (note) 500 else 1024))
            minimumHeight = colors.dp(48)
            setPadding(colors.dp(12), colors.dp(10), colors.dp(12), colors.dp(10))
            background = colors.background(colors.surface, 12).apply { setStroke(colors.dp(1).coerceAtLeast(1), colors.primary) }
            hint = if (note) "添加备注（最多 500 字）" else "输入显示名称"
            contentDescription = if (note) "编辑备注" else "编辑显示名称"
            setText(if (note) record.note else record.merchantName)
        }
        title.labelFor = editor.id.takeIf { it != View.NO_ID } ?: View.generateViewId().also { editor.id = it }
        target.addView(editor, LinearLayout.LayoutParams(-1, -2))
        val help = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        help.addView(colors.text(if (note) "编辑时保持展开" else "仅修改这笔账单的显示名称", 12).apply { setTextColor(colors.secondary) }, LinearLayout.LayoutParams(0, -2, 1f))
        val counter = colors.text("${editor.length()} / 500", 12).apply { setTextColor(colors.secondary); tag = "result-note-count" }
        if (note) help.addView(counter, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = colors.dp(8) })
        target.addView(help, LinearLayout.LayoutParams(-1, -2).apply { topMargin = colors.dp(8); bottomMargin = colors.dp(8) })
        val error = colors.text("", 13).apply {
            tag = "result-editor-error"; setTextColor(colors.error); visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        target.addView(error, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = colors.dp(4) })
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(value: Editable?) {
                counter.text = "${value?.length ?: 0} / 500"
                error.visibility = View.GONE
            }
        })
        fun submit() {
            if (session != token || input !== editor) return
            val value = editor.text.toString()
            val problem = if (!note && value.isBlank()) "请输入显示名称" else save(value)
            if (problem == null) close() else {
                error.text = problem; error.visibility = View.VISIBLE
                editor.requestFocus()
            }
        }
        val actions = LinearLayout(context)
        actions.addView(colors.action("取消").apply {
            tag = "result-editor-cancel"; setTextColor(colors.ink)
            background = colors.ripple(colors.background(if (colors.dark) 0xff2c3632.toInt() else 0xfff0f5f4.toInt(), 12))
            setOnClickListener { if (session == token && input === editor) close() }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = colors.dp(8) })
        actions.addView(colors.action(if (note) "保存备注" else "保存名称").apply {
            tag = "result-editor-save"
            setTextColor(if (colors.dark) colors.surface else Color.WHITE)
            background = colors.ripple(colors.background(if (colors.dark) colors.primary else 0xff287e68.toInt(), 12))
            setOnClickListener { submit() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        target.addView(actions, LinearLayout.LayoutParams(-1, -2))
        editor.setOnEditorActionListener { _, action, _ ->
            if (!note && action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        this.field = field; host = target; input = editor; onClose = closed
        target.visibility = View.VISIBLE
        editor.requestFocus(); editor.setSelection(editor.length())
        editor.post {
            if (session == token && input === editor && editor.isAttachedToWindow) {
                context.getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        return true
    }

    fun hideKeyboard() {
        input?.let { context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
    }

    fun close(notify: Boolean = true) {
        ++session
        val callback = onClose; onClose = null
        hideKeyboard(); input?.clearFocus(); input = null; field = null
        host?.apply { removeAllViews(); visibility = View.GONE }; host = null
        if (notify) callback?.invoke()
    }
}
