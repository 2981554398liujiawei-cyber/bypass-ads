package app.bypassads.testad

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Debug/self-use deterministic splash scenes. This module is never part of
 * the Bypass Ads APK or its normal user navigation. */
class MainActivity : Activity() {
    private lateinit var root: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        setContentView(root)
        showPicker()
    }

    private fun showPicker() {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(48), dp(24), dp(24)) }
        list.addView(TextView(this).apply { text = "Bypass Ads · 开屏回归测试"; textSize = 21f; setTextColor(Color.BLACK) })
        list.addView(TextView(this).apply { text = "仅用于 debug/self-use：精确规则、通用规则和安全拒绝场景。"; setTextColor(Color.DKGRAY) })
        sceneButton(list, "A 精确规则：跳过广告", "exact")
        sceneButton(list, "B 通用规则：跳过", "generic")
        sceneButton(list, "C 非广告：下一步 / 取消 / 关闭", "safe")
        sceneButton(list, "D Master OFF：跳过", "master-off")
        sceneButton(list, "E Per-app OFF：跳过广告", "app-off")
        setScene(list)
    }

    private fun sceneButton(parent: LinearLayout, label: String, mode: String) {
        parent.addView(Button(this).apply { text = label; setOnClickListener { showScene(mode) } }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    private fun showScene(mode: String) {
        val scene = FrameLayout(this).apply { setBackgroundColor(Color.rgb(25, 31, 46)) }
        scene.addView(TextView(this).apply { text = "测试场景：$mode"; textSize = 17f; setTextColor(Color.WHITE) }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(24), dp(64), 0, 0) })
        when (mode) {
            "safe" -> listOf("下一步", "取消", "关闭").forEachIndexed { index, text -> scene.addView(button(text, false), FrameLayout.LayoutParams(dp(160), dp(56), Gravity.CENTER).apply { topMargin = dp((index - 1) * 80) }) }
            "exact", "app-off" -> scene.addView(button("跳过广告", true), topEndParams())
            else -> scene.addView(button("跳过", true), topEndParams())
        }
        setScene(scene)
    }

    private fun button(text: String, clickable: Boolean) = TextView(this).apply {
        this.text = text; textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(Color.rgb(37, 123, 229)); cornerRadius = dp(14).toFloat() }
        isClickable = clickable; isFocusable = clickable
        setOnClickListener { (parent as? ViewGroup)?.removeView(this); showPicker() }
    }

    private fun topEndParams() = FrameLayout.LayoutParams(dp(120), dp(56), Gravity.TOP or Gravity.END).apply { topMargin = dp(120); marginEnd = dp(24) }
    private fun setScene(view: View) { root.removeAllViews(); root.addView(view, FrameLayout.LayoutParams(-1, -1)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
