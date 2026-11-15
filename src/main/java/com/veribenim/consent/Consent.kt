package com.veribenim.consent

/**
 * Platformun otoritatif rıza kategorileri.
 *
 * Web SDK'sı (`@veribenim/core`), iOS (`VeribenimKit.ConsentCategory`) ve sunucu
 * (`CookieProvider::SIGNAL_CATEGORIES`) ile birebir aynı dört anahtar.
 */
enum class ConsentCategory(val key: String) {
    STRICTLY_NECESSARY("strictly_necessary"),
    FUNCTIONAL("functional"),
    ANALYTICS("analytics"),
    MARKETING("marketing");

    val isRequired: Boolean get() = this == STRICTLY_NECESSARY

    companion object {
        fun fromKey(key: String): ConsentCategory? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Kategori bazlı rıza durumu.
 *
 * `strictlyNecessary` bilinçli olarak yapıcı parametresi DEĞİL: reddedilemeyen
 * bir kategoriyi ayarlanabilir göstermek, çağıranı kapatabileceği yanılgısına düşürür.
 */
data class ConsentPreferences(
    val functional: Boolean = false,
    val analytics: Boolean = false,
    val marketing: Boolean = false,
) {
    val strictlyNecessary: Boolean get() = true

    operator fun get(category: ConsentCategory): Boolean = when (category) {
        ConsentCategory.STRICTLY_NECESSARY -> true
        ConsentCategory.FUNCTIONAL -> functional
        ConsentCategory.ANALYTICS -> analytics
        ConsentCategory.MARKETING -> marketing
    }

    /** Sunucunun beklediği `consents` sözlüğü. */
    fun toPayload(): Map<String, Boolean> = mapOf(
        ConsentCategory.STRICTLY_NECESSARY.key to true,
        ConsentCategory.FUNCTIONAL.key to functional,
        ConsentCategory.ANALYTICS.key to analytics,
        ConsentCategory.MARKETING.key to marketing,
    )

    companion object {
        /** Rıza alınmadan önceki durum: yalnızca zorunlu. */
        val DENIED = ConsentPreferences()

        val GRANTED = ConsentPreferences(functional = true, analytics = true, marketing = true)

        fun fromPayload(payload: Map<String, Boolean>) = ConsentPreferences(
            functional = payload[ConsentCategory.FUNCTIONAL.key] ?: false,
            analytics = payload[ConsentCategory.ANALYTICS.key] ?: false,
            marketing = payload[ConsentCategory.MARKETING.key] ?: false,
        )
    }
}

enum class ConsentAction(val key: String) {
    ACCEPT_ALL("accept_all"),
    REJECT_ALL("reject_all"),
    SAVE_PREFERENCES("save_preferences"),
    WITHDRAW("withdraw"),
}

/** Kaydedilmiş rıza — karar + ne zaman ve hangi beyan sürümüne verildiği. */
data class ConsentRecord(
    val preferences: ConsentPreferences,
    val action: ConsentAction,
    val decidedAtMillis: Long,
    /** Sunucudaki beyan sürümü; değişirse rıza yeniden sorulur. */
    val consentVersion: String?,
    val sessionId: String,
)

/** Ağ yokken diske yazılan rıza kaydı — rıza ispatı kaybolmamalı. */
data class QueuedConsent(
    val action: ConsentAction,
    val preferences: ConsentPreferences,
    val sessionId: String,
    val occurredAtMillis: Long,
)

/** Cihaz oturumu — sunucudaki `app_device_sessions` satırının istemci tarafı. */
data class DeviceSession(
    val environmentToken: String,
    val deviceToken: String,
    /** Paylaşılan sır; asla loglanmaz. Nerede saklandığını [ConsentStorage] belirler. */
    val secret: String,
    val expiresAtMillis: Long,
) {
    /**
     * Süresi dolmadan önce yenilenir — tam sınırda yenilemek, uçuştaki bir
     * isteğin sunucuya süresi dolmuş olarak varmasına yol açar.
     */
    fun isUsable(nowMillis: Long = System.currentTimeMillis(), marginMillis: Long = 3_600_000): Boolean =
        expiresAtMillis - nowMillis > marginMillis
}
