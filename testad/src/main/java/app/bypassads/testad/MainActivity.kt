package app.bypassads.testad

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Internal, fully-controlled test app for the M2.2 Active Experimental Trial
 * (first-round allowlist). It fabricates advertisement-like scenes the
 * Shadow detector recognizes (right-top skip button + countdown), plus the
 * adversarial variants the safety chain must handle conservatively.
 *
 * Scenes (cycled in order, counter shown on the picker):
 *   S1 standard   : right-top "跳过" + countdown 5..0
 *   S2 cta-sibling: right-top small "跳过" + big center "立即体验"
 *   S3 overlap    : "跳过" + a smaller clickable "跳过" stacked over it
 *   S4 disappear  : "跳过" vanishes when countdown reaches 0
 *   S5 mutate     : "跳过" relabels/relocates 3s in
 *   S6 ambiguous  : two "跳过" targets (right-top and center-top)
 *
 * Tapping the real "跳过" (by the human tester) removes it, which lets the
 * observer see the target vanish — the honest outcome signal for SUCCESS.
 * Nothing here performs any action itself; it only renders scenes.
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private val handler = Handler(Looper.getMainLooper())
    private var caseNumber = 0

    private var sceneView: View? = null
    private var countdownTick: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        root.setBackgroundColor(Color.WHITE)
        setContentView(root)
        showPicker()
    }

    override fun onDestroy() {
        countdownTick?.let(handler::removeCallbacks)
        super.onDestroy()
    }

    // ---- Scene picker ----------------------------------------------------

    private fun showPicker() {
        caseNumber++
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        wrap.addView(TextView(this).apply {
            text = "Bypass Ads Test · case #$caseNumber"
            textSize = 20f
            setTextColor(Color.BLACK)
        })
        wrap.addView(TextView(this).apply {
            text = "按顺序构造的广告场景；由人工监督实验逐 case 打开。"
            textSize = 13f
            setTextColor(Color.DKGRAY)
        })
        for (i in 1..6) {
            val label = when (i) {
                1 -> "S1 标准：右上跳过 + 倒计时"
                2 -> "S2 相似CTA：右上跳过 + 立即体验"
                3 -> "S3 重叠：两个跳过叠加"
                4 -> "S4 消失：倒计时归零后跳过消失"
                5 -> "S5 变化：3秒后标签/位置改变"
                else -> "S6 双目标：两个跳过"
            }
            wrap.addView(Button(this).apply {
                text = "打开 $label"
                setOnClickListener { showScene(i) }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 24 })
        }
        setScene(ScrollView(this).apply { addView(wrap) })
    }

    // ---- Scenes ----------------------------------------------------------

    private fun showScene(index: Int) {
        countdownTick?.let(handler::removeCallbacks)
        val scene = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        when (index) {
            1 -> standardSkip(scene)
            2 -> ctaSibling(scene)
            3 -> overlap(scene)
            4 -> disappearing(scene)
            5 -> mutating(scene)
            else -> ambiguous(scene)
        }
        setScene(scene)
    }

    private fun setScene(view: View) {
        sceneView?.let { countdownTick?.let(handler::removeCallbacks) }
        root.removeAllViews()
        sceneView = view
        root.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    /** The right-top skip button shared by several scenes. */
    private fun skipButton(text: String = "跳过", paddingDp: Int = 18, bottomMarginDp: Int = 40): TextView {
        val px = { dp: Int -> (dp * resources.displayMetrics.density).toInt() }
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(px(paddingDp), px(10), px(paddingDp), px(10))
            background = rounded(Color.rgb(0, 122, 255))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                // Human/trial tap: the ad target vanishes like a real skip.
                // Remove from the actual parent (the scene FrameLayout).
                (this.parent as? ViewGroup)?.removeView(this)
                Toast.makeText(this@MainActivity, "已跳过（场景结束）", Toast.LENGTH_SHORT).show()
                handler.postDelayed({ showPicker() }, 400)
            }
        }.also { tv ->
            tv.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply { topMargin = px(140); marginEnd = px(40); bottomMargin = px(bottomMarginDp) }
        }
    }

    private fun countdownText(seconds: Int): TextView = TextView(this).apply {
        text = "$seconds"
        textSize = 20f
        setTextColor(Color.DKGRAY)
        gravity = Gravity.CENTER
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        ).apply {
            topMargin = (90 * resources.displayMetrics.density).toInt()
            marginEnd = (40 * resources.displayMetrics.density).toInt()
        }
    }

    private fun startCountdown(textView: TextView, from: Int, onZero: (() -> Unit)? = null) {
        var remaining = from
        textView.text = "$remaining"
        val tick = object : Runnable {
            override fun run() {
                remaining--
                if (remaining <= 0) {
                    textView.text = "0"
                    onZero?.invoke()
                    return
                }
                textView.text = "$remaining"
                handler.postDelayed(this, 1000)
            }
        }
        countdownTick = tick
        handler.postDelayed(tick, 1000)
    }

    // S1
    private fun standardSkip(scene: FrameLayout) {
        val countdown = countdownText(5)
        val skip = skipButton()
        scene.addView(countdown)
        scene.addView(skip)
        startCountdown(countdown, 5) { /* ad ends by itself; keep scene until tester taps */ }
    }

    // S2
    private fun ctaSibling(scene: FrameLayout) {
        val skip = skipButton(paddingDp = 12)
        val cta = TextView(this).apply {
            text = "立即体验"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(255, 120, 0))
            isClickable = true
            isFocusable = true
        }
        scene.addView(cta, FrameLayout.LayoutParams(
            (420 * resources.displayMetrics.density).toInt(),
            (140 * resources.displayMetrics.density).toInt(),
            Gravity.CENTER,
        ))
        scene.addView(skip)
    }

    // S3: two clickable "跳过" stacked at the same spot.
    private fun overlap(scene: FrameLayout) {
        val skip = skipButton()
        val tiny = TextView(this).apply {
            text = "跳过"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(200, 60, 60))
            isClickable = true
            isFocusable = true
            setOnClickListener { Toast.makeText(this@MainActivity, "小覆盖块被点", Toast.LENGTH_SHORT).show() }
        }
        val d = resources.displayMetrics.density
        scene.addView(skip)
        scene.addView(tiny, FrameLayout.LayoutParams(
            (110 * d).toInt(),
            (48 * d).toInt(),
            Gravity.TOP or Gravity.END,
        ).apply {
            topMargin = (170 * d).toInt()
            marginEnd = (70 * d).toInt()
        })
    }

    // S4: skip vanishes at countdown zero.
    private fun disappearing(scene: FrameLayout) {
        val countdown = countdownText(5)
        val skip = skipButton()
        scene.addView(countdown)
        scene.addView(skip)
        startCountdown(countdown, 5) {
            scene.removeView(skip)
            handler.postDelayed({ showPicker() }, 600)
        }
    }

    // S5: skip relabels/relocates after 3s.
    private fun mutating(scene: FrameLayout) {
        val skip = skipButton()
        scene.addView(skip)
        handler.postDelayed({
            skip.text = "开始"
            skip.setTextColor(Color.DKGRAY)
            skip.background = rounded(Color.LTGRAY)
            val lp = skip.layoutParams as FrameLayout.LayoutParams
            lp.gravity = Gravity.CENTER
            skip.layoutParams = lp
        }, 3000)
    }

    // S6: two "跳过" targets.
    private fun ambiguous(scene: FrameLayout) {
        val d = resources.displayMetrics.density
        val top = skipButton()
        val center = TextView(this).apply {
            text = "跳过"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(0, 150, 90))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                (this.parent as? ViewGroup)?.removeView(this)
                Toast.makeText(this@MainActivity, "中部跳过被点", Toast.LENGTH_SHORT).show()
            }
        }
        scene.addView(center, FrameLayout.LayoutParams(
            (200 * d).toInt(),
            (80 * d).toInt(),
            Gravity.CENTER,
        ))
        scene.addView(top)
    }

    private fun rounded(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = (16 * resources.displayMetrics.density)
        }
}
