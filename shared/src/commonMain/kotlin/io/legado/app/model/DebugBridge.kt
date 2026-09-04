package io.legado.app.model

/**
 * Debug(app)桥接: shared 层规则编排器的调试日志经此转发, 由 App.kt 接线。
 * 未接线时 no-op, 行为等同于"无调试输出"。
 */
object DebugBridge {

    /** 源调试日志; sourceUrl 为空时按纯消息输出(Debug.log(msg) 语义) */
    var log: (sourceUrl: String?, msg: String) -> Unit = { _, _ -> }

    /** 应用调试日志(AppLog.putDebug, tag 为源标签) */
    var putDebug: (tag: String?, msg: String) -> Unit = { _, _ -> }

}
