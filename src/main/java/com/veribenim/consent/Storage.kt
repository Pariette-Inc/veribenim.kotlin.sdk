package com.veribenim.consent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Rızanın kalıcı olarak saklandığı yer.
 *
 * Arayüz olması test için değil DOĞRULUK için: uygulama kendi güvenli deposunu
 * (EncryptedSharedPreferences, DataStore) getirebilmeli.
 */
interface ConsentStorage {
    fun loadRecord(): ConsentRecord?
    fun saveRecord(record: ConsentRecord)
    fun clearRecord()

    fun loadSession(): DeviceSession?
    fun saveSession(session: DeviceSession)
    fun clearSession()

    fun loadQueue(): List<QueuedConsent>
    fun saveQueue(queue: List<QueuedConsent>)
}

/** JSON serileştirme — Android'in yerleşik org.json'u; ek bağımlılık yok. */
internal object ConsentJson {
    fun recordToJson(r: ConsentRecord): String = JSONObject().apply {
        put("preferences", JSONObject(r.preferences.toPayload() as Map<*, *>))
        put("action", r.action.key)
        put("decided_at", r.decidedAtMillis)
        put("consent_version", r.consentVersion ?: JSONObject.NULL)
        put("session_id", r.sessionId)
    }.toString()

    fun recordFromJson(raw: String): ConsentRecord? = runCatching {
        val o = JSONObject(raw)
        val prefs = o.getJSONObject("preferences")
        ConsentRecord(
            preferences = ConsentPreferences(
                functional = prefs.optBoolean(ConsentCategory.FUNCTIONAL.key),
                analytics = prefs.optBoolean(ConsentCategory.ANALYTICS.key),
                marketing = prefs.optBoolean(ConsentCategory.MARKETING.key),
            ),
            action = ConsentAction.entries.first { it.key == o.getString("action") },
            decidedAtMillis = o.getLong("decided_at"),
            consentVersion = o.optString("consent_version").takeIf { it.isNotEmpty() && it != "null" },
            sessionId = o.getString("session_id"),
        )
    }.getOrNull()

    fun sessionToJson(s: DeviceSession): String = JSONObject().apply {
        put("environment_token", s.environmentToken)
        put("device_token", s.deviceToken)
        put("secret", s.secret)
        put("expires_at", s.expiresAtMillis)
    }.toString()

    fun sessionFromJson(raw: String): DeviceSession? = runCatching {
        val o = JSONObject(raw)
        DeviceSession(
            environmentToken = o.getString("environment_token"),
            deviceToken = o.getString("device_token"),
            secret = o.getString("secret"),
            expiresAtMillis = o.getLong("expires_at"),
        )
    }.getOrNull()

    fun queueToJson(queue: List<QueuedConsent>): String = JSONArray().apply {
        queue.forEach { q ->
            put(JSONObject().apply {
                put("action", q.action.key)
                put("preferences", JSONObject(q.preferences.toPayload() as Map<*, *>))
                put("session_id", q.sessionId)
                put("occurred_at", q.occurredAtMillis)
            })
        }
    }.toString()

    fun queueFromJson(raw: String): List<QueuedConsent> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val prefs = o.getJSONObject("preferences")
            QueuedConsent(
                action = ConsentAction.entries.first { it.key == o.getString("action") },
                preferences = ConsentPreferences(
                    functional = prefs.optBoolean(ConsentCategory.FUNCTIONAL.key),
                    analytics = prefs.optBoolean(ConsentCategory.ANALYTICS.key),
                    marketing = prefs.optBoolean(ConsentCategory.MARKETING.key),
                ),
                sessionId = o.getString("session_id"),
                occurredAtMillis = o.getLong("occurred_at"),
            )
        }
    }.getOrDefault(emptyList())
}

/** Bellekte tutan depo — testler ve önizleme için. */
class InMemoryConsentStorage : ConsentStorage {
    private var record: ConsentRecord? = null
    private var session: DeviceSession? = null
    private var queue: List<QueuedConsent> = emptyList()

    override fun loadRecord() = record
    override fun saveRecord(record: ConsentRecord) { this.record = record }
    override fun clearRecord() { record = null }

    override fun loadSession() = session
    override fun saveSession(session: DeviceSession) { this.session = session }
    override fun clearSession() { session = null }

    override fun loadQueue() = queue
    override fun saveQueue(queue: List<QueuedConsent>) { this.queue = queue }
}
