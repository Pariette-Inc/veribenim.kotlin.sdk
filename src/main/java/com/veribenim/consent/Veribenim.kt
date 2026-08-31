package com.veribenim.consent

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class VeribenimConfig(
    /** Package name — konsolda kayıtlı `app_identifier` ile birebir aynı olmalı. */
    val appIdentifier: String,
    val platform: String = "android",
    val baseUrl: String = "https://live.veribenim.com",
    /** Rıza kaç gün sonra yeniden sorulsun. Beyan sürümü değişirse zaten sorulur. */
    val reconsentDays: Int = 365,
    val appVersion: String? = null,
    val osVersion: String? = null,
)

/** Ağ katmanı soyutlaması — testler gerçek HTTP yerine sahte taşıyıcı verir. */
interface Transport {
    /** @return (durum kodu, gövde) */
    fun send(url: String, method: String, headers: Map<String, String>, body: String?): Pair<Int, String>
}

/** HttpURLConnection tabanlı varsayılan taşıyıcı: OkHttp bağımlılığı dayatmaz. */
class DefaultTransport : Transport {
    override fun send(url: String, method: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
        }

        return try {
            body?.let { conn.outputStream.use { os -> os.write(it.toByteArray(Charsets.UTF_8)) } }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }
}

class VeribenimException(message: String, val status: Int? = null) : Exception(message)

/**
 * Veribenim mobil rıza yöneticisi (Android).
 *
 * ```kotlin
 * val vb = Veribenim(VeribenimConfig("com.ornek.uygulama"), storage)
 * if (vb.needsConsent()) { /* rıza ekranını göster */ }
 * vb.accept(ConsentPreferences.GRANTED)
 * if (vb.isAllowed(ConsentCategory.ANALYTICS)) { /* ölçüm başlat */ }
 * ```
 *
 * Ağ çağrıları BLOKLAYICIDIR — çağıran kendi coroutine scope'unda
 * (`Dispatchers.IO`) çalıştırmalıdır. SDK kendi thread politikasını dayatmaz;
 * uygulamanın mimarisi SDK'nın işi değildir.
 */
class Veribenim(
    private val config: VeribenimConfig,
    private val storage: ConsentStorage,
    private val transport: Transport = DefaultTransport(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var session: DeviceSession? = storage.loadSession()
    private var record: ConsentRecord? = storage.loadRecord()
    private var latestVersion: String? = null
    private val observers = mutableListOf<(ConsentPreferences) -> Unit>()

    // ── Durum sorgulama ──────────────────────────────────────────────────────

    val preferences: ConsentPreferences
        get() = record?.preferences ?: ConsentPreferences.DENIED

    /**
     * Rıza gerekiyor mu? Üç sebepten biri: hiç karar yok, karar eskidi,
     * veya sunucudaki beyan sürümü değişti.
     */
    fun needsConsent(): Boolean {
        val current = record ?: return true

        val ageDays = (clock() - current.decidedAtMillis) / 86_400_000.0
        if (ageDays > config.reconsentDays) return true

        val latest = latestVersion
        if (latest != null && current.consentVersion != null && latest != current.consentVersion) return true

        return false
    }

    /** Ölçüm/reklam SDK'ları bu kapıdan geçmeli. */
    fun isAllowed(category: ConsentCategory): Boolean = preferences[category]

    fun onConsentChange(handler: (ConsentPreferences) -> Unit) {
        observers.add(handler)
        handler(preferences)
    }

    // ── Karar yazma ──────────────────────────────────────────────────────────

    fun accept(preferences: ConsentPreferences = ConsentPreferences.GRANTED) {
        val action = if (preferences == ConsentPreferences.GRANTED) ConsentAction.ACCEPT_ALL
                     else ConsentAction.SAVE_PREFERENCES
        write(preferences, action)
    }

    fun rejectAll() = write(ConsentPreferences.DENIED, ConsentAction.REJECT_ALL)

    fun withdraw() = write(ConsentPreferences.DENIED, ConsentAction.WITHDRAW)

    private fun write(preferences: ConsentPreferences, action: ConsentAction) {
        val sessionId = record?.sessionId ?: UUID.randomUUID().toString()
        val now = clock()

        val entry = ConsentRecord(
            preferences = preferences,
            action = action,
            decidedAtMillis = now,
            consentVersion = latestVersion,
            sessionId = sessionId,
        )

        // Önce DİSKE, sonra ağa. Ters sırada, ağ hatasında ziyaretçinin kararı
        // hiç var olmamış gibi olur ve banner bir sonraki açılışta yeniden çıkardı.
        record = entry
        storage.saveRecord(entry)
        observers.forEach { it(preferences) }

        storage.saveQueue(storage.loadQueue() + QueuedConsent(action, preferences, sessionId, now))

        runCatching { flush() }
    }

    // ── Sunucu iletişimi ─────────────────────────────────────────────────────

    private fun ensureSession(): DeviceSession {
        session?.let { if (it.isUsable(clock())) return it }

        val body = JSONObject().apply {
            put("app_identifier", config.appIdentifier)
            put("platform", config.platform)
            config.appVersion?.let { put("app_version", it) }
            config.osVersion?.let { put("os_version", it) }
        }.toString()

        val (status, payload) = transport.send(
            "${config.baseUrl}/api/app/session", "POST", emptyMap(), body
        )

        // 404 = uygulama bulunamadı VEYA mobil rıza modülü kapalı. Sunucu bu ikisini
        // bilerek ayırmıyor (numaralandırma kanalı); SDK da ayırt etmeye çalışmaz.
        if (status != 200) throw VeribenimException("El sıkışma başarısız", status)

        val json = JSONObject(payload)
        val fresh = DeviceSession(
            environmentToken = json.getString("token"),
            deviceToken = json.getString("device_token"),
            secret = json.getString("secret"),
            expiresAtMillis = parseIso8601(json.getString("expires_at")) ?: (clock() + 30L * 86_400_000),
        )

        session = fresh
        storage.saveSession(fresh)
        return fresh
    }

    /** Sunucudaki beyanı çeker; sürüm değiştiyse `needsConsent()` yeniden true olur. */
    fun refresh(): String {
        val active = ensureSession()
        val sessionParam = record?.sessionId?.let { "&session_id=$it" } ?: ""

        val (status, payload) = transport.send(
            "${config.baseUrl}/api/preferences/${active.environmentToken}?platform=mobile$sessionParam",
            "GET", emptyMap(), null
        )

        if (status != 200) throw VeribenimException("Beyan alınamadı", status)
        return payload
    }

    // ── Offline kuyruk ───────────────────────────────────────────────────────

    /**
     * Kuyruğu boşaltmayı dener. Ağ yoksa kayıtlar diskte kalır ve bir sonraki
     * açılışta yeniden denenir — rıza ispatı (KVKK Md.3) kaybolmaz.
     */
    fun flush() {
        val queue = storage.loadQueue()
        if (queue.isEmpty()) return

        val active = try { ensureSession() } catch (e: Exception) { return }

        val remaining = queue.filter { entry ->
            val body = JSONObject().apply {
                put("action", entry.action.key)
                put("consents", JSONObject(entry.preferences.toPayload() as Map<*, *>))
                put("session_id", entry.sessionId)
                put("platform", "mobile")
            }.toString()

            val headers = RequestSigner.headers(active, body, clock())

            val failed = try {
                val (status, _) = transport.send(
                    "${config.baseUrl}/api/v/${active.environmentToken}/c", "POST", headers, body
                )
                // 403 → oturum düşmüş veya modül kapanmış; yeniden el sıkışılsın
                // diye oturum atılır, kayıt kuyrukta kalır.
                if (status == 403) { session = null; storage.clearSession() }
                status != 200
            } catch (e: Exception) {
                true
            }

            failed
        }

        storage.saveQueue(remaining)
    }

    private fun parseIso8601(value: String): Long? = runCatching {
        java.time.Instant.parse(value).toEpochMilli()
    }.recoverCatching {
        java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
    }.getOrNull()
}
