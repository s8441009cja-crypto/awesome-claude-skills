package tw.callrecorder

/** 支援錄音的通話來源。packageNames 涵蓋各 App 的常見版本。 */
enum class CallSource(val label: String, val packageNames: Set<String>) {
    PHONE("電話", emptySet()),
    LINE("LINE", setOf("jp.naver.line.android")),
    MESSENGER("Messenger", setOf("com.facebook.orca", "com.facebook.mlite")),
    TELEGRAM(
        "Telegram",
        setOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta",
            "org.thunderdog.challegram", // Telegram X
        ),
    ),
    WHATSAPP("WhatsApp", setOf("com.whatsapp", "com.whatsapp.w4b"));

    companion object {
        fun fromPackage(pkg: String): CallSource? =
            entries.firstOrNull { pkg in it.packageNames }
    }
}
