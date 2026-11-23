package net.ankio.ledger.data

import android.content.Context

/** Device-local presentation preferences survive process and WebView recreation. */
class DisplayPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("display_preferences", Context.MODE_PRIVATE)
    fun summaryAmountsHidden(): Boolean = preferences.getBoolean("summary_amounts_hidden", false)
    fun setSummaryAmountsHidden(hidden: Boolean) {
        check(preferences.edit().putBoolean("summary_amounts_hidden", hidden).commit()) { "金额显示设置保存失败，请重试" }
    }
}
