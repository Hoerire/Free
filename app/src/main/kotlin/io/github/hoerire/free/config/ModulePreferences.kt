package io.github.hoerire.free.config

/** App 与 Hook 进程共享的框架配置协议；已发布的名称和类型不可随意复用。 */
internal object ModulePreferences {
    const val GROUP = "module_runtime"
    const val ACTION_PINNED_TARGETS_CHANGED =
        "io.github.hoerire.free.action.PINNED_TARGETS_CHANGED"

    /**
     * 启动标记：模块自己代发的启动（如更多面板里的快捷方式）不希望被改成小窗。
     *
     * 快捷方式指向应用内的具体页面，开成小窗会让流程变形；用显式标记而不是按组件猜，
     * 才不会误伤真正该开小窗的应用启动。
     */
    const val EXTRA_SKIP_ZOOM = "io.github.hoerire.free.extra.SKIP_ZOOM"

    /**
     * system_server → 侧边栏进程：最近任务包名（新的在前）。
     *
     * 侧边栏进程是普通应用 uid，拿不到 `getRecentTasks`；而 ColorOS 不把游戏写进
     * UsageStats（王者荣耀 7 天内 0 条 ACTIVITY_RESUMED），「最近」分组就会漏掉它。
     * 由 system_server 把最近任务顺序推过去，侧边栏只做合并。
     */
    const val ACTION_RECENTS_SYNC = "io.github.hoerire.free.action.RECENTS_SYNC"
    const val EXTRA_RECENT_PACKAGES = "recent_packages"

    /** 侧边栏应用：接收上面的最近任务推送。 */
    const val SIDEBAR_PACKAGE = "com.coloros.smartsidebar"
    const val EXTRA_PINNED_TARGETS = "pinned_targets"
    const val EXTRA_PINS_SAVED = "pins_saved"
    const val EXTRA_MODULE_ENABLED = "module_enabled"
    const val KEY_MODULE_ENABLED = "enabled"
    const val KEY_LEFT_CORNER_ENABLED = "corner_left_enabled"
    const val KEY_RIGHT_CORNER_ENABLED = "corner_right_enabled"
    const val KEY_CORNER_TRIGGER_RANGE_DP = "corner_trigger_range_dp"
    const val KEY_RADIAL_HIT_RANGE_DP = "radial_hit_range_dp"

    /**
     * 扇形展开 / 收回的动画时长（毫秒），由用户自己调。
     *
     * 默认值展开 380、收回 500。上限 1000 是「再长就等得难受」
     * 的经验值；下限 100 保证动画仍然可读——再短等于没有动画。
     *
     * 这两个时长同时驱动入场/退场的图标动画与背景效果（压暗的淡入淡出、模糊的渐变量）。
     */
    const val KEY_RADIAL_EXPAND_DURATION_MS = "radial_expand_duration_ms_v1"
    const val KEY_RADIAL_RETRACT_DURATION_MS = "radial_retract_duration_ms_v1"

    /**
     * 扇形背景的合并滑条：负值为黑色遮罩浓度，正值为系统模糊，0 为不处理背景。
     *
     * 两个方向共用同一条滑块：往左压暗，往右模糊，中间即原样透出背后内容。
     */
    const val KEY_BACKDROP_LEVEL = "backdrop_level"

    /** 分项时代的遮罩浓度与布尔模糊开关，合并后只用于迁移。 */
    const val LEGACY_KEY_SHADE_PERCENT = "shade_percent"
    const val LEGACY_KEY_RADIAL_BLUR_ENABLED = "radial_blur_enabled"


    /** 扇形图标的基准直径（dp）；第三方主题图标与「更多」圆盘都按它对齐。 */
    const val KEY_RADIAL_ICON_SIZE_DP = "radial_icon_size_dp"
    const val KEY_PINNED_TARGETS = "corner_targets_v2"
    /** 第二组扇形固定项：与 [KEY_PINNED_TARGETS] 各存各的，互不影响。 */
    const val KEY_PINNED_TARGETS_ALTERNATE = "corner_targets_alt_v1"
    /** 第二组生效位置（[RadialGroupSide.storedValue]）：默认不启用，两侧都用默认组。 */
    const val KEY_SECONDARY_GROUP_SIDE = "secondary_group_side_v1"
    const val KEY_CORNER_PINS = "corner_pins_v1"
    const val KEY_OUTSIDE_TAP_CLOSE_MODE = "outside_tap_close_mode_v1"
    const val KEY_HANDLE_SWIPE_UP_TO_MINI_ENABLED = "handle_swipe_up_to_mini_enabled"

    /**
     * 横条沉浸：小窗的底部横条与顶部三点、以及全面屏手势指示条，
     * 平时隐藏、触摸后重新显示（不占位、应用不下沉）。
     *
     * 与其他手势项一致，只受模块总开关约束。默认关闭：关掉后系统显隐完全按原厂处理。
     */
    const val KEY_EXTERNAL_HANDLE_ENABLED = "external_handle_enabled_v1"
    const val KEY_PAUSE_IN_LANDSCAPE = "pause_in_landscape"
    const val KEY_PAUSE_IN_GAME_MODE = "pause_in_game_mode"

    /** 屏蔽键盘：输入法弹出时暂停扇形手势（默认关闭）。 */
    const val KEY_PAUSE_IN_KEYBOARD = "pause_in_keyboard_v1"
    const val KEY_VERBOSE_LOG = "verbose_log_enabled"
    const val KEY_BLACKLIST_PACKAGES = "foreground_blacklist_v1"

    /**
     * 「禁用浮窗」：这些应用不会被以任何形式的自由浮窗打开。
     *
     * 与 [KEY_BLACKLIST_PACKAGES] 不是同一个语义，键必须分开：黑名单管的是「暂停模块增强」
     * （扇形、窗外关闭等），这份只影响启动改写。
     */
    const val KEY_ZOOM_EXCLUDED_PACKAGES = "zoom_launch_excluded_v1"
    const val KEY_ZOOM_MAX_WINDOW_COUNT = "zoom_max_window_count"
    /** 贴边浮标同时保留的个数上限（设置项「贴边数量」）。 */
    const val KEY_FLOAT_HANDLE_MAX_COUNT = "float_handle_max_count"
    const val KEY_ZOOM_ALLOW_ANY_APP = "zoom_allow_any_app"
    /** 已退役的「轻量打开」总开关：仅用于升级后清掉残留值。 */
    const val LEGACY_KEY_ZOOM_INTERACTION_ENABLED = "zoom_interaction_enabled"
    /** 小窗跳转细分场景：通知中心点击、通知横幅、控制中心磁贴、应用互跳、侧边栏。 */
    const val KEY_ZOOM_LAUNCH_NOTICE = "zoom_launch_notice_v2"
    const val KEY_ZOOM_LAUNCH_HEADS_UP = "zoom_launch_heads_up_v2"
    const val KEY_ZOOM_LAUNCH_CONTROL_CENTER = "zoom_launch_control_center_v2"
    const val KEY_ZOOM_LAUNCH_INTER_APP = "zoom_launch_inter_app_v2"

    /**
     * 已退役的两个启动场景：登录授权（带回传的跨应用跳转）与应用分享（分享中转页）。
     *
     * 官方自己就会把这两类流程放进浮窗，模块再改写反而会打断回传链路；代码已删，
     * 只保留键名，用来在升级后清掉残留值。
     */
    const val LEGACY_KEY_ZOOM_LAUNCH_INTER_APP_RESULT = "zoom_launch_inter_app_result_v1"
    const val LEGACY_KEY_ZOOM_LAUNCH_SHARE_RELAY = "zoom_launch_share_relay_v1"

    /** 复制链接：复制链接弹窗与系统分发服务代发的 VIEW 启动。 */
    const val KEY_ZOOM_LAUNCH_CLIPBOARD = "zoom_launch_clipboard_v1"

    /** 侧边栏进程就绪：由模块在侧边栏进程内发出，system_server 收到后才去读目录。 */
    const val ACTION_SIDEBAR_READY = "io.github.hoerire.free.action.SIDEBAR_READY"

    /** 窗口超过数量限制时，自动关闭旧窗口而不是收成贴边浮标。 */
    const val KEY_AUTO_CLOSE_WINDOW = "auto_close_window_v1"
    const val DEFAULT_AUTO_CLOSE_WINDOW = false

    /**
     * 气泡点开小窗的时刻（Settings.Global 毫秒时间戳）。
     *
     * 点击气泡与系统真正创建小窗之间隔着一次跨进程调用，模块用这个时间窗告诉
     * system_server「这次启动是气泡来的」。
     */
    const val KEY_BUBBLE_OPEN_AT = "free_bubble_open_at"

    /** 最近任务：把当前处于小窗状态的任务从最近任务卡片里隐藏。 */
    const val KEY_HIDE_ZOOM_IN_RECENTS = "hide_zoom_in_recents_v1"
    const val DEFAULT_HIDE_ZOOM_IN_RECENTS = false

    /** 长按静音：长按迷你窗把该应用静音，再次长按恢复；普通小窗不响应。 */
    const val KEY_LONG_PRESS_MUTE = "mini_long_press_v1"
    const val DEFAULT_LONG_PRESS_MUTE = false

    /**
     * 返回增强：边缘内滑开始返回手势后，手指**上滑或下拉**超过设定距离执行对应动作。
     *
     * 方向即动作：上滑把焦点应用在「全屏 ↔ 小窗」之间切换，下拉打开「更多」面板。
     * 判据是方向 + 距离，没有时长：手指停多久都不会触发，停住期间滑回去就等于取消。
     *
     * 默认关闭，避免升级后行为突变。
     */
    const val KEY_PREDICTIVE_BACK_ENABLED = "predictive_back_enabled_v1"
    const val DEFAULT_PREDICTIVE_BACK_ENABLED = false

    /** 按时长/三选一动作时代的旧键：只用于读取迁移与清除残留。 */
    const val LEGACY_KEY_PREDICTIVE_BACK_ACTION = "predictive_back_action_v1"
    const val LEGACY_KEY_PREDICTIVE_BACK_HOLD_MS = "predictive_back_hold_ms_v1"
    const val LEGACY_PREDICTIVE_BACK_ACTION_DISABLED = -1

    /**
     * 触发用的滑动距离（dp）：边缘内滑后上滑或下拉超过这个距离才执行动作。
     *
     * 默认 100dp：实测比 56dp 更容易判准，手指不必刻意拉很短；下限 16dp 保证还能和抖动分开，
     * 上限 200dp 让手上留有余量而不至于要拉满半屏。
     */
    const val KEY_PREDICTIVE_BACK_SWIPE_DISTANCE_DP = "predictive_back_swipe_distance_dp_v1"
    const val DEFAULT_PREDICTIVE_BACK_SWIPE_DISTANCE_DP = 100
    const val MIN_PREDICTIVE_BACK_SWIPE_DISTANCE_DP = 16
    const val MAX_PREDICTIVE_BACK_SWIPE_DISTANCE_DP = 200

    fun coercePredictiveBackSwipeDistanceDp(value: Int): Int =
        value.coerceIn(
            MIN_PREDICTIVE_BACK_SWIPE_DISTANCE_DP,
            MAX_PREDICTIVE_BACK_SWIPE_DISTANCE_DP,
        )

    /** 上滑：焦点应用在「全屏 ↔ 小窗」之间切换，由 SystemUI 在松手前发出。 */
    const val ACTION_PREDICTIVE_BACK_ZOOM =
        "io.github.hoerire.free.action.PREDICTIVE_BACK_ZOOM"

    /** 下拉：打开「更多」面板。 */
    const val ACTION_PREDICTIVE_BACK_MORE_PANEL =
        "io.github.hoerire.free.action.PREDICTIVE_BACK_MORE_PANEL"

    /** 打开面板时起手的位置：从哪一侧起手、按下的坐标（像素）。 */
    const val EXTRA_GESTURE_SIDE = "gesture_side_left"
    const val EXTRA_GESTURE_X = "gesture_x"
    const val EXTRA_GESTURE_Y = "gesture_y"


    /** 贴边挂机：收成贴边浮标后保持应用继续运行，不停止任务。 */
    const val KEY_FLOAT_KEEP_ALIVE = "float_keep_alive_v1"
    const val DEFAULT_FLOAT_KEEP_ALIVE = false

    /** 「禁用浮窗」默认屏蔽项的一次性迁移标记；写过一次设置后就不再自动补。 */
    const val KEY_ZOOM_EXCLUDED_LINKER_MIGRATED = "zoom_excluded_linker_migrated_v1"

    /**
     * 设备互联（OPPO 互联）包名。
     *
     * 它既没有启动入口、也不走系统通知，靠服务在后台代发启动，
     * 普通判定拦不住，因此默认放进「禁用浮窗」。
     */
    const val DEVICE_LINK_PACKAGE = "com.oplus.linker"


    const val KEY_RADIAL_KEEP_OPEN = "radial_keep_open"

    /**
     * 扇形交互总开关（默认开）。
     *
     * 关掉后底角不再呼出扇形菜单，monitor 一并拆掉、手势原样交回系统；
     * 已配置的应用、特效与尺寸全部保留，重新打开即可恢复。
     */
    const val KEY_RADIAL_INTERACTION_ENABLED = "radial_interaction_enabled_v1"

    /**
     * 流光特效：选中项外沿的白色光圈、星头与运动轨迹（一直在转的动效）。
     *
     * 选中反馈的其余部分（选中放大、外顶距离）在 [KEY_RADIAL_SHIMMER] 里，
     * 关掉本开关只停动效，几何反馈照旧。
     */
    const val KEY_RADIAL_SHIMMER_ENABLED = "radial_shimmer_enabled_v1"

    /** 选中流光的参数集合；存成一行分隔串，见 [RadialShimmer]。 */
    const val KEY_RADIAL_SHIMMER = "radial_shimmer_v1"

    /**
     * 呼吸特效：扇形里的图标持续轻微起伏。
     *
     * 和气泡贴边时那口气一样：振幅 ±2%、周期 4.2 秒，逐格错开一点相位，
     * 看起来是整排图标在缓缓呼吸，而不是齐刷刷同时缩放。
     */
    const val KEY_RADIAL_BREATHING_ENABLED = "radial_breathing_enabled_v1"

    /** 扇形半径（百分比）：左右两侧共用同一个值，同时放大或缩小。 */
    const val KEY_RADIAL_RADIUS_PERCENT = "radial_radius_percent_v1"

    /**
     * 「解除限制」：解除内容传送门的长按拖拽准入限制。
     *
     * 系统的长按拖拽只对 sys_view_drag_list.xml 里登记过的包 + Activity + 控件类名生效，
     * 没登记的包连配置都拿不到。打开后 hook 在配置下发处补一份通用白名单，
     * 让任意应用、任意页面都能长按拖起内容。只改「能不能拖」，不改「拖给谁」。
     */
    const val KEY_FREE_DRAG = "free_drag_enabled"

    /** 快速分享：拖拽时弹出模块自绘的分享面板，代替官方面板。 */
    const val KEY_QUICK_SHARE = "quick_share_enabled"
    const val DEFAULT_QUICK_SHARE = false

    /**
     * 是否允许调整触发延迟（默认关闭）。
     *
     * 关闭时**不写**系统长按阈值，应用侧看到的仍是原厂 400ms；打开后才按
     * [KEY_DRAG_LONG_PRESS_MS] 改写。做成独立开关是因为改写的是全局
     * `Settings.Secure.long_press_timeout`：它同时影响桌面图标长按编辑、文本选择等
     * 所有长按判定，用户不主动要就不该被动改。
     */
    const val KEY_DRAG_LONG_PRESS_ENABLED = "drag_long_press_enabled_v1"
    const val DEFAULT_DRAG_LONG_PRESS_ENABLED = false

    /** 拖拽触发延迟：系统长按阈值（毫秒），默认 400 即官方行为。 */
    const val KEY_DRAG_LONG_PRESS_MS = "drag_long_press_ms_v1"
    const val DEFAULT_DRAG_LONG_PRESS_MS = 400
    const val MIN_DRAG_LONG_PRESS_MS = 100
    const val MAX_DRAG_LONG_PRESS_MS = 400

    fun coerceDragLongPressMs(value: Int): Int =
        value.coerceIn(MIN_DRAG_LONG_PRESS_MS, MAX_DRAG_LONG_PRESS_MS)

    // ---- 气泡通知 ----------------------------------------------------------
    //
    // 白名单分两组：主应用键是包名，分身键是 `包名#userId`。两组各自存盘，
    // 勾选互不牵连；匹配只看键，不做任何别名/目录推断。

    const val KEY_BUBBLE_ENABLED = "bubble_enabled_v1"
    const val KEY_BUBBLE_WHITELIST = "bubble_whitelist_v1"
    const val KEY_BUBBLE_CLONE_WHITELIST = "bubble_clone_whitelist_v1"
    const val KEY_BUBBLE_SIZE_PERCENT = "bubble_size_percent_v1"
    const val KEY_BUBBLE_ICON_PERCENT = "bubble_icon_percent_v1"
    const val KEY_BUBBLE_BADGE_PERCENT = "bubble_badge_percent_v1"
    const val KEY_BUBBLE_TIMEOUT_SECONDS = "bubble_timeout_seconds_v1"
    /**
     * 到点变暗留置。v2 把默认改成 false：到点默认「贴边收起」而不是原地变暗。
     * 旧的 v1 值不再读取，首次保存时清掉，避免升级用户继续停在变暗状态。
     */
    const val KEY_BUBBLE_DIM_ON_TIMEOUT = "bubble_dim_on_timeout_v2"
    const val LEGACY_KEY_BUBBLE_DIM_ON_TIMEOUT = "bubble_dim_on_timeout_v1"
    const val KEY_BUBBLE_PREVIEW_ENABLED = "bubble_preview_enabled_v1"
    const val KEY_BUBBLE_PREVIEW_SUPPRESS_BANNER = "bubble_preview_suppress_banner_v1"
    const val KEY_BUBBLE_PREVIEW_EDGE_ONLY = "bubble_preview_edge_only_v1"
    const val KEY_BUBBLE_PREVIEW_ALPHA = "bubble_preview_alpha_v1"
    const val KEY_BUBBLE_PREVIEW_TIMEOUT_SECONDS = "bubble_preview_timeout_seconds_v1"
    const val KEY_BUBBLE_PREVIEW_DYNAMIC_TIMEOUT = "bubble_preview_dynamic_timeout_v1"
    const val KEY_BUBBLE_AUTO_DISMISS = "bubble_auto_dismiss_v1"
    /** 到点行为：true = 直接消失（不贴边、不变暗）。 */
    const val KEY_BUBBLE_DISMISS_ON_TIMEOUT = "bubble_dismiss_on_timeout_v1"
    /**
     * 只通知：气泡从出现起就不接收触摸，点按与拖动直接穿透到下层应用。
     *
     * 与三种到点行为互相独立——开启后气泡到点一律直接消失（见 BubbleCoordinator.timeoutFor），
     * 因为一颗点不掉、拖不走的残留气泡若还贴着边，就再也收不回去了。
     */
    const val KEY_BUBBLE_NOTICE_ONLY = "bubble_notice_only_v1"
    const val KEY_BUBBLE_MOTION_CONTINUOUS = "bubble_motion_continuous_v1"

    /** 状态栏滚动开关：气泡总闸之下的子开关，可以只滚不出气泡。 */
    const val KEY_STATUS_SCROLL_ENABLED = "status_scroll_enabled_v1"
    const val KEY_STATUS_SCROLL_ROUNDS = "status_scroll_rounds_v1"
    /** 滚动速度（像素/秒）：恒定速度，不随文本长度变化。 */
    const val KEY_STATUS_SCROLL_SPEED = "status_scroll_speed_px_v1"
    /**
     * 免打扰：三种场景下不出气泡与建议。
     *
     * - 当前应用：通知/建议指向正在使用的应用时没有信息增量；
     * - 游戏：全屏游戏里弹气泡会打断操作；
     * - 锁屏与息屏：锁屏上气泡被挡在后面，息屏时更不该有任何浮层。
     */
    const val KEY_BUBBLE_SKIP_FOREGROUND = "bubble_skip_foreground_v1"
    const val KEY_BUBBLE_SKIP_GAME = "bubble_skip_game_v1"
    const val KEY_BUBBLE_SKIP_LOCKED = "bubble_skip_locked_v1"
    // ---- 即时建议 ----------------------------------------------------------
    const val KEY_SUGGESTIONS_ENABLED = "suggestions_enabled_v1"
    const val KEY_SUGGESTION_FLASHLIGHT = "suggestion_flashlight_v1"
    const val KEY_SUGGESTION_MUSIC = "suggestion_music_v1"
    const val KEY_SUGGESTION_APP_INSTALL = "suggestion_app_install_v1"
    /** 剪贴板建议默认关闭：涉及读取剪贴板，由用户主动开启。 */
    const val KEY_SUGGESTION_CLIPBOARD = "suggestion_clipboard_v1"
    /** 常亮建议：屏幕即将休眠时建议保持常亮。 */
    const val KEY_SUGGESTION_KEEP_AWAKE = "suggestion_keep_awake_v1"
    const val KEY_SUGGESTION_NO_EDGE = "suggestion_no_edge_v1"

    /**
     * system_server → SystemUI：屏幕正处于「连续变暗、即将休眠」的状态。
     *
     * 写进 `Settings.Global`：1 = 正在连续变暗，0 = 已回到常亮。只做一件事——
     * 告诉 SystemUI 该弹「保持常亮」气泡了；SystemUI 侧按固定间隔轮询该标志，
     * 并要求它连续保持一段时间才真的弹（视频播放时的瞬时抖动不算）。
     */
    const val KEY_PRE_SLEEP_ARMED = "free_pre_sleep_armed"

    /**
     * system_server → SystemUI：分身通知的深链 Intent 短命缓存。
     *
     * 分身通知的 `contentIntent` 是不透明的 PendingIntent，直接发送只会按它的签发用户
     * 落到主应用；要进分身的聊天页，必须拿到原始 Intent 后在目标用户上下文里重建再发送。
     * 记录由 system_server 在通知入库时捕获、SystemUI 在点气泡时读取，
     * 格式见 `bubble/CloneDeepLinkStore.kt`。
     */
    const val KEY_CLONE_DEEP_LINK = "free_bubble_clone_deep_link"

    /** system_server → SystemUI：剪贴板分类结果（即时建议）。 */
    const val ACTION_CLIPBOARD_SUGGESTION =
        "io.github.hoerire.free.action.CLIPBOARD_SUGGESTION"
    const val EXTRA_CLIP_KINDS = "clip_kinds"
    const val EXTRA_CLIP_VALUES = "clip_values"
    const val EXTRA_CLIP_DISPLAYS = "clip_displays"
    const val EXTRA_CLIP_APPS = "clip_apps"

    const val DEFAULT_BUBBLE_ENABLED = false
    const val DEFAULT_BUBBLE_SIZE_PERCENT = 100
    const val MIN_BUBBLE_SIZE_PERCENT = 60
    const val MAX_BUBBLE_SIZE_PERCENT = 140
    const val DEFAULT_BUBBLE_ICON_PERCENT = 100
    const val MIN_BUBBLE_ICON_PERCENT = 60
    const val MAX_BUBBLE_ICON_PERCENT = 150
    const val DEFAULT_BUBBLE_BADGE_PERCENT = 100
    const val MIN_BUBBLE_BADGE_PERCENT = 50
    const val MAX_BUBBLE_BADGE_PERCENT = 180
    const val DEFAULT_BUBBLE_TIMEOUT_SECONDS = 7
    const val MIN_BUBBLE_TIMEOUT_SECONDS = 3
    const val MAX_BUBBLE_TIMEOUT_SECONDS = 60
    const val DEFAULT_BUBBLE_DIM_ON_TIMEOUT = false
    const val DEFAULT_BUBBLE_PREVIEW_ENABLED = true
    const val DEFAULT_BUBBLE_PREVIEW_SUPPRESS_BANNER = true
    const val DEFAULT_BUBBLE_PREVIEW_EDGE_ONLY = false
    const val DEFAULT_BUBBLE_PREVIEW_ALPHA = 88
    const val MIN_BUBBLE_PREVIEW_ALPHA = 35
    const val MAX_BUBBLE_PREVIEW_ALPHA = 100
    const val DEFAULT_BUBBLE_PREVIEW_TIMEOUT_SECONDS = 5
    const val MIN_BUBBLE_PREVIEW_TIMEOUT_SECONDS = 2
    const val MAX_BUBBLE_PREVIEW_TIMEOUT_SECONDS = 15
    const val DEFAULT_BUBBLE_PREVIEW_DYNAMIC_TIMEOUT = true
    const val DEFAULT_BUBBLE_AUTO_DISMISS = true
    const val DEFAULT_BUBBLE_DISMISS_ON_TIMEOUT = false
    const val DEFAULT_BUBBLE_NOTICE_ONLY = false
    const val DEFAULT_BUBBLE_MOTION_CONTINUOUS = true
    const val DEFAULT_STATUS_SCROLL_ENABLED = false
    const val DEFAULT_STATUS_SCROLL_ROUNDS = 1
    const val DEFAULT_SUGGESTIONS_ENABLED = false
    const val DEFAULT_SUGGESTION_FLASHLIGHT = true
    const val DEFAULT_SUGGESTION_MUSIC = true
    const val DEFAULT_SUGGESTION_APP_INSTALL = true
    const val DEFAULT_SUGGESTION_CLIPBOARD = false
    const val DEFAULT_SUGGESTION_KEEP_AWAKE = false
    const val DEFAULT_SUGGESTION_NO_EDGE = false
    const val DEFAULT_BUBBLE_SKIP_FOREGROUND = true
    const val DEFAULT_BUBBLE_SKIP_GAME = true
    const val DEFAULT_BUBBLE_SKIP_LOCKED = true
    const val MIN_STATUS_SCROLL_ROUNDS = 1
    const val MAX_STATUS_SCROLL_ROUNDS = 5
    /**
     * 滚动速度区间（px/s）。默认 150px/s 大约是每秒 8 个汉字，
     * 长通知也不会滚太久；下限 40 适合慢慢读，上限 300 给习惯快读的人。
     */
    const val DEFAULT_STATUS_SCROLL_SPEED = 150
    const val MIN_STATUS_SCROLL_SPEED = 40
    const val MAX_STATUS_SCROLL_SPEED = 300
    private const val MAX_BUBBLE_WHITELIST = 200

    fun coerceBubbleSizePercent(value: Int): Int =
        value.coerceIn(MIN_BUBBLE_SIZE_PERCENT, MAX_BUBBLE_SIZE_PERCENT)

    fun coerceBubbleIconPercent(value: Int): Int =
        value.coerceIn(MIN_BUBBLE_ICON_PERCENT, MAX_BUBBLE_ICON_PERCENT)

    fun coerceBubbleBadgePercent(value: Int): Int =
        value.coerceIn(MIN_BUBBLE_BADGE_PERCENT, MAX_BUBBLE_BADGE_PERCENT)

    fun coerceBubbleTimeoutSeconds(value: Int): Int =
        value.coerceIn(MIN_BUBBLE_TIMEOUT_SECONDS, MAX_BUBBLE_TIMEOUT_SECONDS)

    fun coerceBubblePreviewAlpha(value: Int): Int =
        value.coerceIn(MIN_BUBBLE_PREVIEW_ALPHA, MAX_BUBBLE_PREVIEW_ALPHA)

    fun coerceBubblePreviewTimeoutSeconds(value: Int): Int =
        value.coerceIn(MIN_BUBBLE_PREVIEW_TIMEOUT_SECONDS, MAX_BUBBLE_PREVIEW_TIMEOUT_SECONDS)

    fun coerceStatusScrollRounds(value: Int): Int =
        value.coerceIn(MIN_STATUS_SCROLL_ROUNDS, MAX_STATUS_SCROLL_ROUNDS)

    fun coerceStatusScrollSpeed(value: Int): Int =
        value.coerceIn(MIN_STATUS_SCROLL_SPEED, MAX_STATUS_SCROLL_SPEED)

    /**
     * 气泡白名单键：包名，或 `包名#userId`。
     *
     * 解析与编码只在这里做一次，UI 和 hook 都调它，避免两边各拼一份字符串。
     */
    fun decodeBubbleKeys(raw: String?): List<String> =
        raw.orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { BUBBLE_KEY_PATTERN.matches(it) }
            .distinct()
            .take(MAX_BUBBLE_WHITELIST)
            .toList()

    fun encodeBubbleKeys(keys: List<String>): String =
        keys.filter { BUBBLE_KEY_PATTERN.matches(it) }
            .distinct()
            .take(MAX_BUBBLE_WHITELIST)
            .joinToString("\n")

    private val BUBBLE_KEY_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+(?:#[1-9][0-9]{0,5})?")

    const val DEFAULT_ENABLED = false
    const val DEFAULT_CORNER_ENABLED = true
    const val DEFAULT_CORNER_TRIGGER_RANGE_DP = 84
    const val DEFAULT_RADIAL_HIT_RANGE_DP = 100
    const val DEFAULT_RADIAL_ICON_SIZE_DP = 44
    const val MIN_RADIAL_ICON_SIZE_DP = 32
    const val MAX_RADIAL_ICON_SIZE_DP = 56

    /** 扇形展开/收回时长：默认与引入设置之前的固定值一致，升级后观感不变。 */
    const val DEFAULT_RADIAL_EXPAND_DURATION_MS = 380
    const val DEFAULT_RADIAL_RETRACT_DURATION_MS = 500
    const val MIN_RADIAL_DURATION_MS = 100
    const val MAX_RADIAL_DURATION_MS = 1000

    val DEFAULT_OUTSIDE_TAP_CLOSE_MODE = OutsideTapCloseMode.Disabled
    const val DEFAULT_HANDLE_SWIPE_UP_TO_MINI_ENABLED = false
    const val DEFAULT_EXTERNAL_HANDLE_ENABLED = false
    const val DEFAULT_PAUSE_IN_LANDSCAPE = true
    const val DEFAULT_PAUSE_IN_GAME_MODE = true
    const val DEFAULT_PAUSE_IN_KEYBOARD = false
    const val DEFAULT_VERBOSE_LOG = false
    /** 默认不处理背景：呼出扇形时既不压暗也不模糊。 */
    const val DEFAULT_BACKDROP_LEVEL = 0
    const val MIN_BACKDROP_LEVEL = -100
    const val MAX_BACKDROP_LEVEL = 100
    /** 滑条正值满格对应的模糊半径（dp）。 */
    const val MAX_RADIAL_BLUR_DP = 100
    /** 布尔开关时代开着模糊的机器迁移到的程度：对应上一版固定半径 24dp。 */
    const val LEGACY_BLUR_LEVEL = 24

    const val DEFAULT_ZOOM_MAX_WINDOW_COUNT = 1
    const val MIN_ZOOM_MAX_WINDOW_COUNT = 1
    const val MAX_ZOOM_MAX_WINDOW_COUNT = 5
    /** 贴边数量：系统手机默认 2 个，只放大不缩小，上限 10。 */
    const val DEFAULT_FLOAT_HANDLE_MAX_COUNT = 2
    const val MIN_FLOAT_HANDLE_MAX_COUNT = 2
    const val MAX_FLOAT_HANDLE_MAX_COUNT = 10
    const val DEFAULT_ZOOM_ALLOW_ANY_APP = false
    const val DEFAULT_RADIAL_KEEP_OPEN = false
    /** 扇形交互默认开启：不想要扇形菜单的用户在设置里关掉即可。 */
    const val DEFAULT_RADIAL_INTERACTION_ENABLED = true
    const val DEFAULT_RADIAL_SHIMMER_ENABLED = false
    /** 呼吸特效默认开启：幅度只有 ±2%，不喜欢可以在外观细节里关掉。 */
    const val DEFAULT_RADIAL_BREATHING_ENABLED = true
    /** 解除限制默认关闭：改写的是系统级拖拽准入，由用户主动开启。 */
    const val DEFAULT_FREE_DRAG = false
    /**
     * 扇形最多能放几个应用（收藏上限同值）。须定义在引用它的常量之前。
     *
     * 扇形里还有一个常驻的「更多」格，所以实际槽位是 [MAX_PINNED_APPS] + 1，
     * 最多 8 格；`RadialIconGeometry` 按槽位数反推最小半径，保证不互相贴住。
     */
    const val MAX_PINNED_APPS = 7
    const val MIN_CORNER_TRIGGER_RANGE_DP = 24
    const val MAX_CORNER_TRIGGER_RANGE_DP = 160
    const val MIN_RADIAL_HIT_RANGE_DP = 40
    /**
     * 上限 220：图标圆心在半径 242 基准 dp 的圆弧上，命中半径到这个量级
     * 才能一直覆盖到屏幕直角本身；再大也不会有新的覆盖区域。
     */
    const val MAX_RADIAL_HIT_RANGE_DP = 220

    /** 扇形半径的允许区间与默认值（百分比，100 为基准半径）。 */
    const val MIN_RADIAL_RADIUS_PERCENT = 70
    const val MAX_RADIAL_RADIUS_PERCENT = 140
    const val DEFAULT_RADIAL_RADIUS_PERCENT = 100

    fun coerceRadialRadiusPercent(value: Int): Int =
        value.coerceIn(MIN_RADIAL_RADIUS_PERCENT, MAX_RADIAL_RADIUS_PERCENT)

    fun coerceCornerTriggerRangeDp(value: Int): Int =
        value.coerceIn(MIN_CORNER_TRIGGER_RANGE_DP, MAX_CORNER_TRIGGER_RANGE_DP)

    fun coerceRadialHitRangeDp(value: Int): Int =
        value.coerceIn(MIN_RADIAL_HIT_RANGE_DP, MAX_RADIAL_HIT_RANGE_DP)

    fun coerceRadialIconSizeDp(value: Int): Int =
        value.coerceIn(MIN_RADIAL_ICON_SIZE_DP, MAX_RADIAL_ICON_SIZE_DP)

    fun coerceRadialExpandDurationMs(value: Int): Int =
        value.coerceIn(MIN_RADIAL_DURATION_MS, MAX_RADIAL_DURATION_MS)

    fun coerceRadialRetractDurationMs(value: Int): Int =
        value.coerceIn(MIN_RADIAL_DURATION_MS, MAX_RADIAL_DURATION_MS)

    fun coerceFloatHandleMaxCount(value: Int): Int =
        value.coerceIn(MIN_FLOAT_HANDLE_MAX_COUNT, MAX_FLOAT_HANDLE_MAX_COUNT)

    fun coerceZoomMaxWindowCount(value: Int): Int =
        value.coerceIn(MIN_ZOOM_MAX_WINDOW_COUNT, MAX_ZOOM_MAX_WINDOW_COUNT)

    fun coerceBackdropLevel(value: Int): Int =
        value.coerceIn(MIN_BACKDROP_LEVEL, MAX_BACKDROP_LEVEL)

    /** 遮罩不透明度（0–1）：只有滑条的负值部分参与压暗。 */
    fun backdropShadeAlpha(level: Int): Float =
        (-coerceBackdropLevel(level).coerceAtMost(0)).toFloat() / -MIN_BACKDROP_LEVEL

    /** 模糊半径（dp）：只有滑条的正值部分参与模糊。 */
    fun backdropBlurDp(level: Int): Int =
        coerceBackdropLevel(level).coerceAtLeast(0) * MAX_RADIAL_BLUR_DP / MAX_BACKDROP_LEVEL


    /** 黑名单包名：换行分隔，逐条校验，最多 200 条。 */
    fun decodePackages(raw: String?): List<String> =
        raw.orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { PACKAGE_PATTERN.matches(it) }
            .distinct()
            .take(MAX_BLACKLIST_PACKAGES)
            .toList()

    fun encodePackages(packages: List<String>): String =
        packages
            .filter { PACKAGE_PATTERN.matches(it) }
            .distinct()
            .take(MAX_BLACKLIST_PACKAGES)
            .joinToString("\n")

    private val PACKAGE_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    private const val MAX_BLACKLIST_PACKAGES = 200
}
