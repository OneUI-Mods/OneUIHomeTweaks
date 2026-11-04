package dev.ryuya.oneuihometweaks

/** Recognizes Samsung's internal Home-key dispatch while excluding an app's first return. */
internal object FolderHomePolicy {
    fun preservesPreparedClose(
        preparedManager: Any?,
        resetManager: Any?,
        alreadyClosingToHome: Boolean,
    ): Boolean = preparedManager != null && preparedManager === resetManager && alreadyClosingToHome

    fun isVisibleHomeRequest(
        fromHomeKey: Boolean,
        rootVisible: Boolean,
        hasWindowFocus: Boolean,
        hasActionOrCategories: Boolean,
    ): Boolean = fromHomeKey && rootVisible && (hasWindowFocus || !hasActionOrCategories)
}
