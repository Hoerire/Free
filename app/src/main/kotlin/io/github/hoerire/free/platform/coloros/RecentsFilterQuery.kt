package io.github.hoerire.free.platform.coloros

import java.io.File
import java.lang.reflect.Method
import org.luckypray.dexkit.DexKitBridge

/**
 * 桌面最近任务的卡片过滤器。
 *
 * 最近任务的卡片列表由桌面自己拼装，拼装时每条记录都会过一遍桌面内置的过滤器
 * （返回 true = 这张卡不显示）。这个类在不同 ROM 上被 R8 混淆成不同的短类名，
 * 所以按「参数形状」在桌面的 dex 里现找，而不是写死类名：
 * 形如 `(int, int, GroupedTaskInfo, ArrayList) -> boolean` 的方法；
 * 同形状可能命中多个混淆类，再用反射校验「同类里还有 (GroupedTaskInfo) -> boolean」来收窄。
 */
internal object RecentsFilterQuery {
    const val GROUPED_TASK_INFO = "com.android.wm.shell.shared.GroupedTaskInfo"

    data class Symbols(val className: String, val methodName: String)

    fun find(bridge: DexKitBridge): List<Symbols> =
        bridge.findMethod {
            matcher {
                paramTypes("int", "int", GROUPED_TASK_INFO, "java.util.ArrayList")
                returnType = "boolean"
            }
        }.mapNotNull { hit ->
            hit.declaredClass?.let { Symbols(it.name, hit.name) }
        }.distinct()

    /** 在桌面进程里解析并绑定过滤器方法；所有候选都不符合形状时抛异常，由调用方只记一条日志。 */
    fun resolve(loader: ClassLoader, apk: File): Method {
        System.loadLibrary("dexkit")
        val candidates = DexKitBridge.create(apk.path).use { bridge ->
            bridge.setThreadNum(1)
            find(bridge)
        }
        val grouped = loader.loadClass(GROUPED_TASK_INFO)
        for (candidate in candidates) {
            val method = runCatching {
                val owner = loader.loadClass(candidate.className)
                val hasSingleTaskVariant = owner.declaredMethods.any {
                    it.parameterCount == 1 &&
                        it.parameterTypes[0] == grouped &&
                        it.returnType == Boolean::class.javaPrimitiveType
                }
                require(hasSingleTaskVariant) { "LAUNCHER_RECENTS_FILTER_SHAPE_MISMATCH" }
                owner.getDeclaredMethod(
                    candidate.methodName,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    grouped,
                    ArrayList::class.java,
                )
            }.getOrNull() ?: continue
            method.isAccessible = true
            return method
        }
        throw NoSuchMethodException("LAUNCHER_RECENTS_FILTER_MISSING")
    }
}
