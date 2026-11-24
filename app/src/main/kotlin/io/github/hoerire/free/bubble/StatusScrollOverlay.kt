package io.github.hoerire.free.bubble

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.TextPaint
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.log.ModuleLog
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * 融入状态栏的滚动通知组件（类似 lyricon 的状态栏歌词）。
 * 不创建独立浮层，而是直接以子视图插入状态栏通知区，并替换时钟位置：
 * 使用状态栏时钟本身的字体与颜色，随时钟随状态栏着色而联动变化。
 */
internal class StatusScrollOverlay(context: Context) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val ticker: TickerView = TickerView(context)
    private var attached = false
    private var hasText = false
    private var host: ViewGroup? = null
    private var overlayAttached = false
    private var clockView: TextView? = null
    private var pendingText: String? = null
    private var hasPending = false
    private val hiddenPeers = ArrayList<View>()
    private val hiddenPeerVisibility = ArrayList<Int>()
    private val removeAction = Runnable { removeImmediately() }
    private val scrollFrame = Choreographer.FrameCallback { stepScroll() }
    private val overlayKeepAliveAction = Runnable { keepOverlayAlive() }
    private var contentWidth = 0
    /** 期望的摆放位置（相对状态栏宿主），宿主如果把子视图摆到别处要按这个纠正。 */
    private var viewportLeft = 0
    private var viewportTop = 0
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var measuredTextWidth = 0f
    private var scrollOffset = 0f
    private var scrollActive = false
    private var scrollPxPerSecond = ModulePreferences.DEFAULT_STATUS_SCROLL_SPEED.toFloat()
    private var lastScrollStepAt = 0L
    private var fullText = ""
    private var shownAt = 0L
    private var displayRounds = 1
    private var completedRounds = 0
    private var finishedAction: (() -> Unit)? = null
    private val fluidCloudBounds = Rect()

    init {
        orientation = LinearLayout.HORIZONTAL
        setGravity(Gravity.CENTER_VERTICAL)
        val pad = dp(5f)
        // 起点对齐时钟左沿：左侧不留内边距，文字只靠 ticker 自己的 2dp 留白。
        setPadding(0, 0, pad, 0)
        setClipToPadding(true)
        // 横向滚动靠 translationX 把整条文字移出容器，超出的部分由本容器的
        // 内边距裁剪挡住，避免画到摄像头挖孔与系统图标上。
        setClipChildren(true)
        setBackgroundColor(Color.TRANSPARENT)
        visibility = View.VISIBLE
        alpha = 0f


        ticker.paint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            11f,
            resources.displayMetrics,
        )
        ticker.paint.color = Color.WHITE
        ticker.setPadding(dp(2f), 0, dp(4f), 0)
        addView(ticker, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
        ))
    }

    /**
     * 纯文本滚动条：自己算基线、自己裁可视区，不经过 TextView 的排版。
     *
     * TextView 在宽度被父级反复改动时会把 Layout 建在旧宽度上，于是出现
     * 换行、文字被顶到顶部、只显示一截。这里直接 drawText：基线固定垂直居中、
     * 横向位移由 [offsetX] 控制，几何完全可控。
     */
    private inner class TickerView(context: Context) : View(context) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        var text: String = ""
            set(value) {
                field = value
                refreshWidth()
                invalidate()
            }
        var textWidth: Float = 0f
            private set
        var offsetX: Float = 0f
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        fun refreshWidth() {
            textWidth = if (text.isEmpty()) 0f else paint.measureText(text)
        }

        override fun onDraw(canvas: Canvas) {
            if (text.isEmpty()) return
            val metrics = paint.fontMetrics
            val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f
            canvas.save()
            canvas.clipRect(
                paddingLeft.toFloat(),
                0f,
                (width - paddingRight).toFloat(),
                height.toFloat(),
            )
            canvas.drawText(text, paddingLeft + offsetX, baseline, paint)
            canvas.restore()
        }
    }

    /** 把组件插入状态栏容器，定位到状态栏时钟所在位置。 */
    fun attachTo(parent: ViewGroup) {
        var discoveredClock: View? = findClockView(parent)
        if (discoveredClock !is TextView) {
            // 被测量的 status_bar_contents / start-side 宿主可能是时钟的兄弟节点，
            // 需要遍历整个 SystemUI 装饰树才能拿到时钟真实的纵向位置。
            val root = parent.rootView
            if (root is ViewGroup && root !== parent) {
                discoveredClock = findClockView(root)
            }
        }
        val clock = discoveredClock as? TextView
        if (clock != null) {
            clockView = clock
        }
        if (host === parent && overlayAttached) return
        detachOverlay()
        host = parent
        attachClockStyle(parent)
        // 画在时钟与图标子视图之上，但不参与 ColorOS 容器的
        // 测量与子视图顺序规则。
        parent.overlay.add(this)
        overlayAttached = true
        var left = clockOffsetInHost(false)
        val height = tickerRowHeight()
        val top = centeredTopInHost(height)
        var right = tickerRightBoundary(parent)
        val tickerBounds = resolveFluidCloudAvoidance(parent, left, right, top, height)
        left = tickerBounds[0]
        right = tickerBounds[1]
        val width = Math.max(1, right - left)
        val textWidth = Math.max(1, width - paddingLeft - paddingRight)
        contentWidth = Math.max(1, textWidth - ticker.paddingLeft - ticker.paddingRight)
        ticker.visibility = View.VISIBLE
        applyLayout(left, top, width, height)
        visibility = View.VISIBLE
        if (!hasText) alpha = 0f
        if (hasPending) {
            val text = pendingText ?: ""
            hasPending = false
            pendingText = null
            show(text)
        }
        // SystemUI 可能晚一帧才量完状态栏宿主：
        // 那时再布局一次，让文本区用上真实宿主边界。
        post { relayoutOverlay() }
        postDelayed({ relayoutOverlay() }, 90L)
        postDelayed({ relayoutOverlay() }, 260L)
        if (hasText) scheduleOverlayKeepAlive()
    }

    private fun scheduleOverlayKeepAlive() {
        handler.removeCallbacks(overlayKeepAliveAction)
        if (overlayAttached && hasText) {
            handler.postDelayed(overlayKeepAliveAction, 180L)
        }
    }

    private fun keepOverlayAlive() {
        val currentHost = host
        if (!overlayAttached || !hasText || currentHost == null) return
        try {
            // SystemUI 重建通知图标行时可能清空 ViewGroupOverlay：
            // 把同一个视图重新加回去，滚动条就不会在
            // 正常的超时/移除流程之前凭空消失。
            currentHost.overlay.remove(this)
            currentHost.overlay.add(this)
            // 滚动进行中可能有新的通知图标视图插入；
            // 每次保活都把它们隐藏，避免与本条重叠。
            hideStatusBarPeers(currentHost)
            // 滚动进行中不再周期性重排：每 180ms 触发一次 measure/layout
            // 会让逐帧位移看起来一顿一顿的。几何变化由 onLayout 通知补。
            if (!scrollActive) relayoutOverlay()
        } catch (ignored: Throwable) {
        }
        scheduleOverlayKeepAlive()
    }

    private fun relayoutOverlay() {
        if (!overlayAttached) return
        val currentHost = host ?: return
        // 折叠状态下的状态栏层级可能先暴露宿主、后挂上它的
        // 时钟子视图：一旦出现就重新绑定，不必等
        // 用户手动刷新状态栏。
        val clock = clockView
        if (clock == null || clock.parent == null) {
            val found = findClockView(currentHost)
            if (found is TextView) attachClockStyle(currentHost)
        }
        if (currentHost.measuredWidth <= 0) return
        var left = clockOffsetInHost(false)
        val height = tickerRowHeight()
        val top = centeredTopInHost(height)
        var right = tickerRightBoundary(currentHost)
        val tickerBounds = resolveFluidCloudAvoidance(currentHost, left, right, top, height)
        left = tickerBounds[0]
        right = tickerBounds[1]
        val width = Math.max(1, right - left)
        val textWidth = Math.max(1, width - paddingLeft - paddingRight)
        contentWidth = Math.max(1, textWidth - ticker.paddingLeft - ticker.paddingRight)
        applyLayout(left, top, width, height)
        if (hasText) {
            hideStatusBarPeers(currentHost)
            copyClockStyle()
            // 可视宽度可能变了：按新边界重算文本宽度，并在原进度上继续滚，
            // 避免打字、旋屏、SystemUI 重建之后位移越界或被清零。
            updateScrollMetrics()
            startOrContinueScroll()
        }
    }

    /**
     * 宿主重排（打字、旋屏、SystemUI 重建、挖孔避让变化）后同步可视宽度。
     *
     * 保活循环在滚动期间不再周期性 relayout（那会让逐帧位移一顿一顿），
     * 几何变化就靠这里补算，滚动进度不丢。
     */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // 宿主（状态栏的 ViewGroupOverlay）也会排我们，它的坐标不保证等于
        // 我们自己算的时钟位。这里只把可视宽度同步过来，位置由 applyLayout 负责，
        // **不要**在 onLayout 里再调 layout() 或改 LayoutParams——那会请求宿主重排，
        // 反过来把我们摆到 (0,0)，表现就是整条文字被顶到状态栏顶部。
        val newContentWidth = Math.max(
            1,
            (r - l) - paddingLeft - paddingRight - ticker.paddingLeft - ticker.paddingRight,
        )
        if (newContentWidth == contentWidth) return
        contentWidth = newContentWidth
        if (!hasText) return
        post {
            updateScrollMetrics()
            startOrContinueScroll()
        }
    }

    /** 统一测量与布局：记录可视区几何，再按精确宽度摆放。 */
    private fun applyLayout(left: Int, top: Int, width: Int, height: Int) {
        viewportLeft = left
        viewportTop = top
        viewportWidth = Math.max(1, width)
        viewportHeight = Math.max(1, height)
        measure(
            View.MeasureSpec.makeMeasureSpec(viewportWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(viewportHeight, View.MeasureSpec.EXACTLY),
        )
        layout(left, top, left + viewportWidth, top + viewportHeight)
    }

    /** 查找状态栏时钟，并让本组件实时跟随其着色变化。 */
    private fun attachClockStyle(root: ViewGroup) {
        val found = findClockView(root)
        if (found !is TextView) return
        clockView = found
        found.viewTreeObserver.addOnPreDrawListener {
            // 状态栏每次重绘（颜色随着色变化）时同步本组件的样式与颜色。
            if (hasText) copyClockStyle()
            true
        }
    }

    private fun hideStatusBarPeers(parent: ViewGroup) {
        val clock = clockView
        if (clock != null && clock.visibility != View.GONE) {
            hiddenPeers.add(clock)
            hiddenPeerVisibility.add(clock.visibility)
            animatePeerOut(clock)
        }
        val icons = findNotificationIcons(parent)
        if (icons != null && icons !== this && icons !== clockView &&
            icons.visibility != View.GONE
        ) {
            hiddenPeers.add(icons)
            hiddenPeerVisibility.add(icons.visibility)
            animatePeerOut(icons)
        }
    }

    private fun animatePeerOut(view: View) {
        val distance = Math.max(statusBarHeight(), height).toFloat()
        view.animate().cancel()
        view.translationY = -distance
        view.animate().alpha(0f).setDuration(150L)
            .withEndAction { view.visibility = View.GONE }.start()
    }

    private fun restorePeers() {
        for (i in 0 until hiddenPeers.size) {
            val view = hiddenPeers[i]
            val visibility = hiddenPeerVisibility[i]
            view.animate().cancel()
            view.visibility = when (visibility) {
                View.INVISIBLE -> View.INVISIBLE
                View.GONE -> View.GONE
                else -> View.VISIBLE
            }
            view.translationY = -Math.max(statusBarHeight(), height).toFloat()
            view.alpha = 0f
            view.animate().translationY(0f).alpha(1f).setDuration(160L).start()
        }
        hiddenPeers.clear()
        hiddenPeerVisibility.clear()
    }

    private fun findNotificationIcons(view: View?): View? {
        if (view == null || view === this || view === clockView) return null
        try {
            if (view.id != View.NO_ID) {
                val idName = view.resources.getResourceEntryName(view.id)
                if (idName == "notificationIcons" ||
                    idName.contains("notification_icon_area")
                ) {
                    return view
                }
            }
        } catch (ignored: Throwable) {
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findNotificationIcons(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun clockOffsetInHost(vertical: Boolean): Int {
        val clock = clockView ?: return 0
        val currentHost = host ?: return 0
        try {
            val clockLocation = IntArray(2)
            val parentLocation = IntArray(2)
            clock.getLocationInWindow(clockLocation)
            currentHost.getLocationInWindow(parentLocation)
            return Math.max(
                0,
                if (vertical) {
                    clockLocation[1] - parentLocation[1]
                } else {
                    clockLocation[0] - parentLocation[0]
                },
            )
        } catch (ignored: Throwable) {
            return if (vertical) clock.top else clock.left
        }
    }

    private fun tickerRowHeight(): Int {
        val currentHost = host
        var hostHeight = currentHost?.height ?: 0
        if (hostHeight <= 0 && currentHost != null) hostHeight = currentHost.measuredHeight
        val clockHeight = clockView?.height ?: 0
        val base = if (hostHeight > 0) {
            hostHeight
        } else {
            if (clockHeight > 0) clockHeight else statusBarHeight()
        }
        return Math.max(dp(18f), Math.min(statusBarHeight(), base))
    }

    private fun centeredTopInHost(height: Int): Int {
        val currentHost = host
        var hostHeight = currentHost?.height ?: 0
        if (hostHeight <= 0 && currentHost != null) hostHeight = currentHost.measuredHeight
        if (hostHeight > 0) return Math.max(0, (hostHeight - height) / 2 + dp(4f))
        // 首次布局前先把这一行放在状态栏原点，
        // 等宿主有了高度，再由延后的一次布局把它居中。
        return dp(4f)
    }

    private fun findRightSystemBoundary(parent: ViewGroup): Int {
        val fallback = parent.measuredWidth
        val boundary = findRightSystemView(parent) ?: return fallback
        try {
            val child = IntArray(2)
            val root = IntArray(2)
            boundary.getLocationInWindow(child)
            parent.getLocationInWindow(root)
            return Math.max(0, child[0] - root[0])
        } catch (ignored: Throwable) {
            return boundary.left
        }
    }

    /**
     * 滚动条的右界：有摄像头挖孔时以挖孔左沿为准（不超过摄像头），没有挖孔才用系统图标区左沿。
     *
     * 只取系统图标区左沿时，ColorOS 17 上那个容器带了一大段左侧留白，
     * 结果滚动条在中段就断掉、右边空着一大块。
     */
    private fun tickerRightBoundary(parent: ViewGroup): Int {
        val camera = cameraLeftInHost(parent)
        val system = findRightSystemBoundary(parent)
        ModuleLog.debug("BubbleNotify") {
            "STATUS_TICKER_BOUNDS_left" + clockOffsetInHost(false) +
                "_camera" + (camera ?: -1) +
                "_system" + system +
                "_parent" + parent.measuredWidth
        }
        // 有摄像头就顶到挖孔左沿（只留 2dp 余量）；没有摄像头才用系统图标区左沿，
        // 那里自带一段留白，多让 24dp。
        return if (camera != null) Math.max(0, camera - dp(2f))
        else Math.max(0, system - dp(24f))
    }

    /** 摄像头挖孔左沿（宿主坐标）；没有挖孔或取不到返回 null。 */
    private fun cameraLeftInHost(parent: ViewGroup): Int? {
        val cutout =
            runCatching { rootWindowInsets?.displayCutout }.getOrNull() ?: return null
        val rects = cutout.boundingRects
        if (rects.isEmpty()) return null
        val hostLocation = IntArray(2)
        runCatching { parent.getLocationOnScreen(hostLocation) }.getOrNull() ?: return null
        var result = Int.MAX_VALUE
        for (rect in rects) {
            if (rect == null || rect.isEmpty) continue
            // boundingRects 用的是窗口坐标；状态栏窗口贴着屏幕左上角，换算到宿主本地坐标即可。
            val inHost = rect.left - hostLocation[0]
            if (inHost in 1 until Math.max(1, parent.measuredWidth)) {
                result = Math.min(result, inHost)
            }
        }
        return if (result == Int.MAX_VALUE) null else result
    }

    /**
     * 让滚动条落在状态栏里没有被可见流体云胶囊占据的那一段。
     * 某些版本里胶囊是独立的 SystemUI 窗口，所以先在屏幕坐标里算出来，
     * 再换回浮层宿主的本地坐标系。
     */
    private fun resolveFluidCloudAvoidance(
        parent: ViewGroup?,
        left: Int,
        right: Int,
        top: Int,
        height: Int,
    ): IntArray {
        if (parent == null || right <= left) return intArrayOf(left, right)
        val cloud = findFluidCloudBounds() ?: return intArrayOf(left, right)
        try {
            val hostLocation = IntArray(2)
            parent.getLocationOnScreen(hostLocation)
            val row = Rect(
                hostLocation[0] + left,
                hostLocation[1] + top,
                hostLocation[0] + right,
                hostLocation[1] + top + height,
            )
            if (!Rect.intersects(row, cloud)) return intArrayOf(left, right)
            val gap = dp(6f)
            val cloudLeft = cloud.left - hostLocation[0]
            val cloudRight = cloud.right - hostLocation[0]
            val leftWidth = Math.max(0, Math.min(right, cloudLeft - gap) - left)
            val rightWidth = Math.max(0, right - Math.max(left, cloudRight + gap))
            // 优先用更宽的无遮挡区间；两段都窄时
            // 保留较宽的一段，交给现有的分页逻辑处理。
            if (rightWidth > leftWidth) {
                val newLeft = Math.min(right, cloudRight + gap)
                return intArrayOf(newLeft, right)
            }
            val newRight = Math.max(left, cloudLeft - gap)
            return intArrayOf(left, newRight)
        } catch (ignored: Throwable) {
            return intArrayOf(left, right)
        }
    }

    private fun findFluidCloudBounds(): Rect? {
        fluidCloudBounds.setEmpty()
        try {
            val globalClass = Class.forName("android.view.WindowManagerGlobal")
            val getInstance = globalClass.getMethod("getInstance")
            val getWindowViews = globalClass.getMethod("getWindowViews")
            val global = getInstance.invoke(null)
            val roots = if (global == null) null else getWindowViews.invoke(global)
            if (roots !is List<*>) return null
            for (root in roots) {
                if (root !is View) continue
                val found = findFluidCloudInView(root) ?: continue
                if (fluidCloudBounds.isEmpty() ||
                    found.width() * found.height() >
                    fluidCloudBounds.width() * fluidCloudBounds.height()
                ) {
                    fluidCloudBounds.set(found)
                }
            }
        } catch (ignored: Throwable) {
        }
        return if (fluidCloudBounds.isEmpty()) null else Rect(fluidCloudBounds)
    }

    private fun findFluidCloudInView(view: View?): Rect? {
        if (view == null) return null
        if (view === this || view.visibility != View.VISIBLE ||
            view.alpha < 0.05f || view.width <= 0 || view.height <= 0
        ) {
            return null
        }
        var identity = view.javaClass.name.lowercase(Locale.ROOT)
        try {
            val id = view.id
            if (id != View.NO_ID && id != 0) {
                val entry = view.resources.getResourceEntryName(id)
                if (entry != null) identity += " " + entry.lowercase(Locale.ROOT)
            }
        } catch (ignored: Throwable) {
        }
        val tag = view.tag
        if (tag != null) identity += " " + tag.toString().lowercase(Locale.ROOT)
        val capsule = identity.contains("fluid") || identity.contains("capsule") ||
            identity.contains("seedling") || identity.contains("mini_capsule") ||
            identity.contains("smartcapsule") || identity.contains("smart_capsule") ||
            identity.contains("fluid_cloud") || identity.contains("fluidcloud") ||
            identity.contains("floating_cloud") || identity.contains("notificationcapsule") ||
            identity.contains("statusbarcapsule") || identity.contains("dynamicisland")
        var own: Rect? = null
        if (capsule && view.height <= dp(180f) &&
            view.width >= dp(24f) &&
            view.width <= Math.round(resources.displayMetrics.widthPixels * 0.82f) &&
            view.height >= dp(18f)
        ) {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            own = Rect(
                location[0],
                location[1],
                location[0] + view.width,
                location[1] + view.height,
            )
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = findFluidCloudInView(view.getChildAt(i))
                val current = own
                if (child != null && (current == null ||
                        child.width() * child.height() > current.width() * current.height())
                ) {
                    own = child
                }
            }
        }
        return own
    }

    private fun findRightSystemView(view: View): View? {
        if (view === this) return null
        var idName = ""
        try {
            if (view.id != View.NO_ID) idName = view.resources.getResourceEntryName(view.id)
        } catch (ignored: Throwable) {
        }
        if (idName.contains("end_side") || idName.contains("system_icon_area") ||
            idName.contains("status_bar_end")
        ) {
            return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findRightSystemView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    /** 从状态栏时钟复制字体与颜色；找不到时钟时退回到状态栏前景色。 */
    private fun copyClockStyle() {
        var color = statusForeground()
        val clock = clockView
        if (clock != null) {
            color = clock.currentTextColor
            val clockSize = clock.textSize
            ticker.paint.textSize = if (clockSize > 0f) {
                Math.max(dp(9f).toFloat(), Math.min(dp(12f).toFloat(), clockSize))
            } else {
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    11f,
                    resources.displayMetrics,
                )
            }
            ticker.paint.typeface = clock.typeface
        } else {
            ticker.paint.textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                11f,
                resources.displayMetrics,
            )
        }
        if (Color.alpha(color) == 0) color = statusForeground()
        ticker.paint.color = color
        // 字号/字体变了，文本宽度要重新量。
        ticker.refreshWidth()
    }

    fun show(
        text: String,
        rounds: Int = 1,
        speedPxPerSecond: Int = ModulePreferences.DEFAULT_STATUS_SCROLL_SPEED,
    ) {
        val currentHost = host
        if (currentHost == null) {
            pendingText = text
            hasPending = true
            return
        }
        if (!overlayAttached) attachTo(currentHost)
        visibility = View.VISIBLE
        animate().cancel()
        alpha = 1f
        shownAt = SystemClock.uptimeMillis()
        attached = true
        displayRounds = ModulePreferences.coerceStatusScrollRounds(rounds)
        scrollPxPerSecond =
            ModulePreferences.coerceStatusScrollSpeed(speedPxPerSecond).toFloat()
        completedRounds = 0
        hideStatusBarPeers(currentHost)
        copyClockStyle()
        fullText = text
        stopScroll()
        ticker.text = text
        hasText = true
        updateScrollMetrics()
        // 一进场就铺满可见：位移从 0 开始，不做"从右缘滑入"，
        // 否则开头那段会留一大片空白。
        ticker.offsetX = 0f
        scrollOffset = 0f
        ticker.alpha = 0f
        ticker.animate().alpha(1f).setDuration(170L).start()
        startOrContinueScroll()
        handler.removeCallbacks(removeAction)
        if (!scrollActive) {
            // 不滚的短通知也要给出完整可见时间：原来按"页数 ≤ 1"落到这条，
            // 现在按"没有滚动"判断，语义不变。
            handler.postDelayed(removeAction, 2_400L)
        }
        scheduleOverlayKeepAlive()
    }

    fun setFinishedAction(action: (() -> Unit)?) {
        finishedAction = action
    }

    fun isShowing(): Boolean {
        return hasText
    }

    fun finishCurrentRoundSoon() {
        if (!hasText) return
        displayRounds = Math.min(displayRounds, completedRounds + 1)
    }

    fun removeImmediately() {
        val elapsed = SystemClock.uptimeMillis() - shownAt
        if (hasText && elapsed >= 0L && elapsed < 1_500L && isScreenInteractive()) {
            // 防止滚动条首帧遇到 SystemUI/状态栏重建而误走公共清理路径；
            // 真正息屏时的清理仍会执行，因为那时显示已经不可交互。
            handler.removeCallbacks(removeAction)
            handler.postDelayed(removeAction, 1_500L - elapsed)
            scheduleOverlayKeepAlive()
            ModuleLog.debug("BubbleNotify") { "状态滚动提前移除已推迟" }
            return
        }
        handler.removeCallbacks(removeAction)
        handler.removeCallbacks(overlayKeepAliveAction)
        attached = false
        hasText = false
        completedRounds = 0
        restorePeers()
        stopScroll()
        ticker.offsetX = 0f
        animate().cancel()
        alpha = 0f
        ticker.alpha = 0f
        ticker.text = ""
        fullText = ""
        measuredTextWidth = 0f
        scrollOffset = 0f
        shownAt = 0L
        // 保持透明宿主挂载：否则 ColorOS 会重建左侧状态栏分组，
        // 让时钟与图标的显隐和这里抢时序。
        val callback = finishedAction
        if (callback != null) handler.post { callback() }
    }

    private fun isScreenInteractive(): Boolean {
        return try {
            val power = context.getSystemService(PowerManager::class.java)
            power == null || power.isInteractive
        } catch (ignored: Throwable) {
            true
        }
    }

    /**
     * 按当前可视宽度重算整条文本的测量宽度，并把已有位移夹回合法区间。
     *
     * 每次重排都调用：可视宽度变了（打字、旋屏、挖孔避让、SystemUI 重建）时，
     * 滚动要接着走而不是被打回起点。
     */
    private fun updateScrollMetrics() {
        if (!hasText || fullText.isEmpty()) return
        // 文本宽度以 paint 的测量值为准，并带上 ticker 自身的左右留白：
        // 这个值同时是"滚动距离"和"ticker 的布局宽度"，两者必须一致，
        // 否则要么末尾断字，要么滚过头留白。
        // 文本宽度直接由 paint 量，带上 ticker 自己的左右留白。
        // 不经过 TextView 的测量，就不会被上一条通知的宽度带偏。
        measuredTextWidth = if (contentWidth <= 0) {
            0f
        } else {
            ticker.textWidth + ticker.paddingLeft + ticker.paddingRight
        }
        if (scrollActive) {
            scrollOffset = HorizontalTickerMotion.clampOffset(scrollOffset, measuredTextWidth)
            ticker.offsetX = scrollOffset
        }
    }

    /** 文本超出可视宽度才滚；短文本保持左对齐静态显示。 */
    private fun startOrContinueScroll() {
        if (!hasText || fullText.isEmpty()) {
            stopScroll()
            return
        }
        if (!HorizontalTickerMotion.shouldScroll(measuredTextWidth, contentWidth.toFloat())) {
            stopScroll()
            ticker.offsetX = 0f
            return
        }
        if (!scrollActive) {
            scrollActive = true
            ModuleLog.debug("BubbleNotify") {
                "STATUS_TICKER_SCROLL_START textW=" + measuredTextWidth.toInt() +
                    " contentW=" + contentWidth + " px/s=" + scrollPxPerSecond +
                    " rounds=" + displayRounds
            }
            // 进场即滚：停留会看起来像掉了一帧。
            scrollOffset = HorizontalTickerMotion.clampOffset(ticker.offsetX, measuredTextWidth)
            lastScrollStepAt = SystemClock.uptimeMillis()
        }
        scrollOffset = HorizontalTickerMotion.clampOffset(scrollOffset, measuredTextWidth)
        ticker.offsetX = scrollOffset
        scheduleScrollStep(0L)
    }

    private fun stopScroll() {
        scrollActive = false
        Choreographer.getInstance().removeFrameCallback(scrollFrame)
    }

    /** 逐帧回调对齐 vsync：比 postDelayed(16) 少一次唤醒抖动。 */
    private fun scheduleScrollStep(delayMs: Long) {
        val choreographer = Choreographer.getInstance()
        choreographer.removeFrameCallback(scrollFrame)
        if (!scrollActive) return
        if (delayMs > 0L) {
            handler.postDelayed({ if (scrollActive) choreographer.postFrameCallback(scrollFrame) }, delayMs)
        } else {
            choreographer.postFrameCallback(scrollFrame)
        }
    }

    private fun stepScroll() {
        if (!hasText || !scrollActive) return
        val now = SystemClock.uptimeMillis()
        // 用真实时间差算位移：任一步被 SystemUI 主线程拖慢时，
        // 下一拍用累计时长补回来，速度仍是恒定的像素/秒。
        val elapsed = Math.max(1L, now - lastScrollStepAt)
        lastScrollStepAt = now
        scrollOffset = HorizontalTickerMotion.advanceOffset(
            offset = scrollOffset,
            elapsedMs = elapsed,
            pxPerSecond = scrollPxPerSecond,
            textWidth = measuredTextWidth,
        )
        if (scrollOffset <= -measuredTextWidth) {
            // 一整遍滚完：这里才是"完成一轮"。
            completedRounds++
            if (HorizontalTickerMotion.isFinished(completedRounds, displayRounds)) {
                removeImmediately()
                return
            }
            scrollOffset = 0f
        }
        ticker.offsetX = scrollOffset
        scheduleScrollStep(0L)
    }

    private fun detachOverlay() {
        handler.removeCallbacks(overlayKeepAliveAction)
        restorePeers()
        val currentHost = host
        if (currentHost != null && overlayAttached) {
            try {
                currentHost.overlay.remove(this)
            } catch (ignored: Throwable) {
            }
        }
        overlayAttached = false
    }

    private fun statusForeground(): Int {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (dark) Color.WHITE else Color.BLACK
    }

    private fun findClockView(root: ViewGroup): View? {
        // 优先按 id 查找状态栏时钟
        var clockId = resources.getIdentifier("status_bar_clock", "id", "android")
        if (clockId == 0) {
            clockId = resources.getIdentifier("clock", "id", "com.android.systemui")
        }
        if (clockId == 0) {
            clockId = resources.getIdentifier("status_bar_clock", "id", "com.android.systemui")
        }
        if (clockId == 0) {
            clockId = resources.getIdentifier("status_bar_elapsed_time", "id", "android")
        }
        if (clockId != 0) {
            val match = root.findViewById<View>(clockId)
            if (match is TextView) return match
        }
        val byClass = findClockByClass(root)
        return byClass ?: findClockByResourceName(root)
    }

    private fun findClockByClass(view: View): View? {
        if (view is TextView) {
            val name = view.javaClass.name
            if (name.endsWith("Clock") || name.contains(".ClockController")) return view
        }
        if (view !is ViewGroup) return null
        for (i in 0 until view.childCount) {
            val found = findClockByClass(view.getChildAt(i))
            if (found != null) return found
        }
        return null
    }

    private fun findClockByResourceName(view: View?): View? {
        if (view == null) return null
        try {
            if (view.id != View.NO_ID) {
                val name = view.resources.getResourceEntryName(view.id)
                if (name != null && name.lowercase(Locale.ROOT).contains("clock") &&
                    view is TextView
                ) {
                    return view
                }
            }
        } catch (ignored: Throwable) {
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findClockByResourceName(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun dp(value: Float): Int {
        return Math.round(value * resources.displayMetrics.density)
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id == 0) dp(24f) else resources.getDimensionPixelSize(id)
    }


}
