package io.github.hoerire.free.platform.coloros

/**
 * 目录共享常量。
 *
 * 这里原本是「广播 + Messenger + 绑定」那套跨进程协议的常量表；目录读取改成
 * 各进程自己直读侧边栏 APK 之后，只留下跨包引用得到的那几个值。
 */
internal object SidebarEntryProtocol {
    const val MODULE_PACKAGE = "io.github.hoerire.free"
    const val MAX_ENTRIES = 256
    const val ICON_SIZE = 96
}
