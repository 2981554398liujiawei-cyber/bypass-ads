package app.bypassads.testad

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Debug/self-use deterministic splash scenes. This module is never part of
 * the Bypass Ads APK or its normal user navigation. */
class MainActivity : Activity() {
    private lateinit var root: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        setContentView(root)
        intent.getStringExtra(EXTRA_SCENARIO)?.takeIf { it in scenarioNames }?.let(::showScene) ?: showPicker()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_SCENARIO)?.takeIf { it in scenarioNames }?.let(::showScene) ?: showPicker()
    }

    private fun showPicker() {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        list.addView(TextView(this).apply { text = "Bypass Ads · 开屏回归测试"; textSize = 21f; setTextColor(Color.BLACK) })
        list.addView(TextView(this).apply { text = "仅用于 debug/self-use；每个场景也可通过 adb extra 独立启动。"; setTextColor(Color.DKGRAY) })
        scenarioNames.forEach { mode -> sceneButton(list, scenarioLabel(mode), mode) }
        setScene(ScrollView(this).apply { addView(list) })
    }

    private fun sceneButton(parent: LinearLayout, label: String, mode: String) {
        parent.addView(Button(this).apply { text = label; setOnClickListener { showScene(mode) } }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    private fun showScene(mode: String) {
        val scene = FrameLayout(this).apply { setBackgroundColor(Color.rgb(25, 31, 46)) }
        scene.addView(TextView(this).apply {
            text = "测试场景 ${mode.uppercase()}: ${scenarioLabel(mode)}"
            textSize = 17f
            setTextColor(Color.WHITE)
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(24), dp(64), 0, 0) })
        when (mode) {
            "a" -> scene.addView(skipTarget(text = "跳过广告") { showResult(mode) }, topEndParams())
            "b" -> scene.addView(skipTarget(desc = "跳过") { showResult(mode) }, topEndParams())
            "c" -> scene.addView(skipTarget(id = R.id.splash_skip_control) { showResult(mode) }, topEndParams())
            "d" -> addClickableParent(scene, mode)
            "e" -> addGestureSafeTarget(scene, mode)
            "f" -> scene.addView(skipTarget(text = "NEXT") { showUnexpectedAction(mode) }, topEndParams())
            "g" -> scene.addView(skipTarget(text = "跳过片头") { showUnexpectedAction(mode) }, topEndParams())
            "h", "i" -> scene.addView(skipTarget(text = "跳过") { showUnexpectedAction(mode) }, topEndParams())
            "j" -> scene.addView(skipTarget(desc = "跳过") { showUnexpectedAction(mode) }, topEndParams())
            "k" -> addClickableParent(scene, mode)
            "l" -> scene.addView(skipTarget(text = "跳过") { /* Action succeeds but the ad stays visible. */ }, topEndParams())
            "m" -> addDelayedClickableTarget(scene, mode)
            "n" -> scene.addView(skipTarget(text = "跳过", clickable = false).apply {
                // The visual target intentionally has no accessibility node.
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, topEndParams())
            "o" -> scene.addView(skipTarget(text = "跳过") { showResult(mode) }, topEndParams())
            // R6.2 strategy matrix scenes (p..w). Scenes with an explicit
            // "广告" label establish STRONG ad context for the V2 gate.
            // D  text=关闭          -> AGGRESSIVE+ may click
            "p" -> addAdLabeled(scene) { addView(skipTarget(text = "关闭") { showResult(mode) }, topEndParams()) }
            // E  desc=关闭广告      -> AGGRESSIVE+ may click
            "q" -> addAdLabeled(scene) { addView(skipTarget(desc = "关闭广告") { showResult(mode) }, topEndParams()) }
            // F  vid=ad_close       -> AGGRESSIVE+ may click
            "r" -> addAdLabeled(scene) { addView(skipTarget(id = R.id.ad_close) { showResult(mode) }, topEndParams()) }
            // G  text=×             -> AGGRESSIVE+ may click (small glyph)
            "s" -> addAdLabeled(scene) { addView(skipTarget(text = "×", desc = "close") { showResult(mode) }, tinyParams()) }
            // H  structural X (no text/desc View) -> CRAZY-only
            "t" -> addAdLabeled(scene) { addView(structuralXTarget { showResult(mode) }, tinyParams()) }
            // I  coordinate-only close (non-clickable node, no semantic) -> CRAZY-only
            "u" -> addAdLabeled(scene) { addView(structuralXTarget(clickable = false) { showResult(mode) }, tinyParams()) }
            // L  ordinary non-ad close button -> must NOT be clicked in any mode
            "w" -> scene.addView(skipTarget(text = "关闭") { showUnexpectedAction(mode) }, largeParams())
            // R6.3:
            // X  multi-stage Skip -> X -> Close: ONE session, 3 attempts, 1 success
            "x" -> addAdLabeled(scene) {
                addView(skipTarget(text = "跳过") {
                    addAdLabeled(scene) {
                        addView(skipTarget(text = "×") {
                            addAdLabeled(scene) {
                                addView(skipTarget(text = "关闭") { showResult(mode) }, topEndParams())
                            }
                        }, tinyParams())
                    }
                }, topEndParams())
            }
            // Y  ACTION_RESULT_TRUE_BUT_AD_REMAINS: action accepted but the ad
            //    stays -> must NOT be SUCCESS (OutcomeVerifier decides).
            "y" -> addAdLabeled(scene) { addView(skipTarget(text = "跳过") { /* ad remains */ }, topEndParams()) }
            // Z  external landing misclick: click jumps to the launcher/browser
            //    -> MISCLICK_SUSPECTED, session stops immediately.
            "z" -> addAdLabeled(scene) {
                addView(skipTarget(text = "关闭") {
                    runCatching {
                        startActivity(
                            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }, topEndParams())
            }
            // AA trusted dedicated close (rule without bypassMode -> BUNDLED_DEDICATED)
            //    -> clicked even in CONSERVATIVE.
            "aa" -> scene.addView(skipTarget(text = "关闭") { showResult(mode) }, topEndParams())
            // AB teach fixture: plain clickable node with a close-ish desc.
            "ab" -> addAdLabeled(scene) {
                addView(skipTarget(text = "", desc = "关闭广告区域") { showResult(mode) }, tinyParams())
            }
        }
        setScene(scene)
    }

    /** Adds a small "广告" label so the V2 gate sees STRONG ad context. */
    private fun addAdLabeled(scene: FrameLayout, block: FrameLayout.() -> Unit) {
        scene.addView(TextView(this).apply {
            text = "广告"
            textSize = 12f
            setTextColor(Color.rgb(180, 180, 180))
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(24), dp(120), 0, 0) })
        scene.block()
    }

    private fun largeParams() = FrameLayout.LayoutParams(dp(520), dp(320), Gravity.CENTER).apply { topMargin = dp(120) }
    private fun tinyParams() = FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP or Gravity.END).apply { topMargin = dp(120); marginEnd = dp(24) }

    private fun addClickableParent(scene: FrameLayout, mode: String) {
        val parent = FrameLayout(this).apply {
            isClickable = true
            setOnClickListener { showResult(mode) }
            background = targetBackground()
        }
        parent.addView(skipTarget(text = "跳过", clickable = false), FrameLayout.LayoutParams(-1, -1))
        scene.addView(parent, topEndParams())
    }

    private fun addGestureSafeTarget(scene: FrameLayout, mode: String) {
        val target = skipTarget(text = "跳过", clickable = false)
        scene.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP &&
                event.x >= target.left && event.x <= target.right &&
                event.y >= target.top && event.y <= target.bottom
            ) {
                showResult(mode)
            }
            true
        }
        scene.addView(target, topEndParams())
    }

    private fun addDelayedClickableTarget(scene: FrameLayout, mode: String) {
        val target = skipTarget(text = "跳过", clickable = false)
        scene.addView(target, topEndParams())
        target.postDelayed({
            target.isClickable = true
            target.isFocusable = true
            target.setOnClickListener { showResult(mode) }
        }, 900)
    }

    private fun skipTarget(
        text: String = "",
        desc: String? = null,
        id: Int = View.NO_ID,
        clickable: Boolean = true,
        forceDesc: Boolean = false,
        onClick: (() -> Unit)? = null,
    ) = TextView(this).apply {
        if (forceDesc) {
            // Structural-X scene: no text, no desc, plain ImageView-like node.
            this.text = ""
            contentDescription = null
        } else {
            this.text = text
            contentDescription = desc
        }
        this.id = id
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(Color.rgb(37, 123, 229)); cornerRadius = dp(14).toFloat() }
        isClickable = clickable
        isFocusable = clickable
        onClick?.let { handler -> setOnClickListener { handler() } }
    }

    private fun targetBackground() = GradientDrawable().apply { setColor(Color.rgb(37, 123, 229)); cornerRadius = dp(14).toFloat() }

    /** A plain View with no text/desc: structural X / coordinate-only fixture. */
    private fun structuralXTarget(clickable: Boolean = true, onClick: (() -> Unit)? = null) =
        View(this).apply {
            background = targetBackground()
            isClickable = clickable
            isFocusable = clickable
            onClick?.let { handler -> setOnClickListener { handler() } }
        }
    private fun showResult(mode: String) = setScene(TextView(this).apply {
        text = "测试已跳过 (${mode.uppercase()})"
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(36, 112, 69))
    })
    private fun showUnexpectedAction(mode: String) = setScene(TextView(this).apply {
        text = "错误：发生了不应执行的动作 (${mode.uppercase()})"
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(180, 52, 43))
    })
    private fun topEndParams() = FrameLayout.LayoutParams(dp(88), dp(40), Gravity.TOP or Gravity.END).apply { topMargin = dp(120); marginEnd = dp(24) }
    private fun setScene(view: View) { root.removeAllViews(); root.addView(view, FrameLayout.LayoutParams(-1, -1)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun scenarioLabel(mode: String) = when (mode) {
        "a" -> "A 精确规则：可点击跳过"
        "b" -> "B 通用规则：contentDescription 跳过"
        "c" -> "C 通用规则：view id 包含 skip"
        "d" -> "D 通用规则：可点击父节点"
        "e" -> "E 通用规则：不可点击节点的安全手势"
        "f" -> "F 非广告：NEXT"
        "g" -> "G 非广告：跳过片头"
        "h" -> "H 总开关关闭"
        "i" -> "I 本应用关闭"
        "j" -> "J 通用开屏保护关闭"
        "k" -> "K 不可点击目标 + 可点击父节点"
        "l" -> "L 动作返回成功但目标仍存在"
        "m" -> "M 延迟后才可点击"
        "n" -> "N 无 Accessibility 节点"
        "o" -> "O 导航栈验证 Hook"
        "p" -> "P 策略：text=关闭"
        "q" -> "Q 策略：desc=关闭广告"
        "r" -> "R 策略：vid=ad_close"
        "s" -> "S 策略：text=×"
        "t" -> "T 策略：结构 X（无文本）"
        "u" -> "U 策略：坐标-only 关闭"
        "w" -> "W 策略：普通关闭（非广告，禁止点击）"
        "x" -> "X 多阶段：跳过 → X → 关闭（1 会话 3 动作）"
        "y" -> "Y 动作成功但广告仍在（不得计成功）"
        "z" -> "Z 外部落地误触（跳到桌面，立即停止）"
        "aa" -> "AA 可信专用关闭（保守也可点）"
        "ab" -> "AB 教学节点固定场景"
        else -> mode
    }

    companion object {
        const val EXTRA_SCENARIO = "scenario"
        private val scenarioNames = setOf(
            "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o",
            "p", "q", "r", "s", "t", "u", "w", "x", "y", "z", "aa", "ab",
        )
    }
}
