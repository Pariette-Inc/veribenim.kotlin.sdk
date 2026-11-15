package com.veribenim.consent

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class VeribenimConfig(
    /** Package name — konsolda kayıtlı `app_identifier` ile birebir aynı olmalı. */
    val appIdentifier: String,
    val platform: String = "android",
    /** Bugünkü üretim adresi; staging ya da kendi proxy'niz için değiştirin. */
    val baseUrl: String = DEFAULT_BASE_URL,
    /** Rıza kaç gün sonra yeniden sorulsun. Beyan sürümü değişirse zaten sorulur. */
    val reconsentDays: Int = 365,
    /** El sıkışmada gönderilir (ör. BuildConfig.VERSION_NAME). Sunucu en çok 32 karakter kabul eder. */
    val appVersion: String? = null,
    /** El sıkışmada gönderilir (ör. Build.VERSION.RELEASE). */
    val osVersion: String? = null,
    /** Rıza kaydının `user_agent` alanı; null ise [defaultUserAgent]. Sunucu boş değeri bot sayar. */
    val userAgent: String? = null,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://live.veribenim.com"

        /**
         * Dört mobil SDK'da aynı biçim: `VeribenimSDK/<sürüm> (<platform>[ <os>]; <dil>)`.
         * Sunucudaki `BotDetector` kalıplarından (`okhttp`, `java/`, `bot/` …)
         * hiçbirini içermemeli — aksi hâlde her mobil rıza bot sayılır.
         */
        fun defaultUserAgent(platform: String, osVersion: String? = null): String {
            val os = if (osVersion != null) "$platform $osVersion" else platform
            return "VeribenimSDK/${Veribenim.SDK_VERSION} ($os; kotlin)"
        }
    }

    internal val resolvedUserAgent: String
        get() = userAgent ?: defaultUserAgent(platform, osVersion)

    internal val apiBase: String
        get() = baseUrl.trimEnd('/')
}

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
    companion object {
        /** Paket sürümü — `user_agent` alanına girer. Etiketle birlikte güncelleyin. */
        const val SDK_VERSION = "1.0.0"

        /** Sunucu `app_version` / `os_version` için `max:32` doğrular. */
        internal const val VERSION_MAX_LENGTH = 32
    }

    @Volatile private var session: DeviceSession? = storage.loadSession()
    @Volatile private var record: ConsentRecord? = storage.loadRecord()
    private var latestVersion: String? = null
    private val observers = java.util.concurrent.CopyOnWriteArrayList<(ConsentPreferences) -> Unit>()

    /** Aynı anda tek kuyruk boşaltma: iki eşzamanlı tur aynı kaydı iki kez gönderirdi. */
    private val flushLock = Any()

    /** Kuyruk okuma-yazma kilidi; ağ beklenirken TUTULMAZ. */
    private val queueLock = Any()

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

        synchronized(queueLock) {
            storage.saveQueue(storage.loadQueue() + QueuedConsent(action, preferences, sessionId, now))
        }

        runCatching { flush() }
    }

    // ── Sunucu iletişimi ─────────────────────────────────────────────────────

    private fun ensureSession(): DeviceSession {
        session?.let { if (it.isUsable(clock())) return it }

        val body = JSONObject().apply {
            put("app_identifier", config.appIdentifier)
            put("platform", config.platform)
            config.appVersion?.let { put("app_version", it.take(VERSION_MAX_LENGTH)) }
            config.osVersion?.let { put("os_version", it.take(VERSION_MAX_LENGTH)) }
        }.toString()

        val (status, payload) = transport.send(
            "${config.apiBase}/api/app/session", "POST", emptyMap(), body
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
        val sessionParam = record?.sessionId
            ?.let { "&session_id=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""

        val (status, payload) = transport.send(
            "${config.apiBase}/api/preferences/${active.environmentToken}?platform=mobile$sessionParam",
            "GET", emptyMap(), null
        )

        if (status != 200) throw VeribenimException("Beyan alınamadı", status)
        return payload
    }

    // ── Offline kuyruk ───────────────────────────────────────────────────────

    /** Rıza kaydı gövdesi — alanlar `ConsentController::log` doğrulamasıyla birebir. */
    internal fun consentBody(entry: QueuedConsent): String = JSONObject().apply {
        put("action", entry.action.key)
        put("consents", JSONObject(entry.preferences.toPayload() as Map<*, *>))
        put("session_id", entry.sessionId)
        // `platform` GÖNDERİLMEZ: sunucu damgayı imzalı cihaz oturumundan okur.
        // `user_agent` şart: sunucu boş değeri bot sayar (BotDetector: empty_ua).
        put("user_agent", config.resolvedUserAgent)
    }.toString()

    /** @return HTTP durumu; ağ hatasında null. */
    private fun sendConsent(active: DeviceSession, entry: QueuedConsent): Int? {
        val body = consentBody(entry)
        val headers = RequestSigner.headers(active, body, clock())
        return try {
            transport.send("${config.apiBase}/api/v/${active.environmentToken}/c", "POST", headers, body).first
        } catch (e: Exception) {
            null
        }
    }

    private fun dropSession() {
        session = null
        storage.clearSession()
    }

    /**
     * Kuyruğu boşaltmayı dener. Ağ yoksa kayıtlar diskte kalır ve bir sonraki
     * açılışta yeniden denenir — rıza ispatı (KVKK Md.3) kaybolmaz.
     *
     * Kurallar dört SDK'da aynı (README → "Sunucu sözleşmesi"):
     * - Sıra korunur: geçici hatada DURULUR. Sunucu aynı `session_id` satırını
     *   günceller; "geri çek" ile "kabul" yer değiştirirse son durum ters yazılırdı.
     * - 403 / 404 → oturum atılır, BİR KEZ yeniden el sıkışılır, aynı kayıt yeniden denenir.
     * - 400 / 422 → kalıcı ret; kayıt kuyruğu tıkamasın diye atılır.
     * - 402 (kota), 429, 5xx, ağ hatası → kayıt kuyrukta kalır.
     */
    fun flush() {
        synchronized(flushLock) { drainOnce() }
    }

    private fun drainOnce() {
        val snapshot = synchronized(queueLock) { storage.loadQueue() }
        if (snapshot.isEmpty()) return

        var active = try { ensureSession() } catch (e: Exception) { return }
        var handled = 0
        var rehandshaken = false

        loop@ for (entry in snapshot) {
            var status = sendConsent(active, entry)

            if ((status == 403 || status == 404) && !rehandshaken) {
                rehandshaken = true
                dropSession()
                active = try { ensureSession() } catch (e: Exception) { break@loop }
                status = sendConsent(active, entry)
            }

            when {
                status != null && status in 200..299 -> handled++
                status == 400 || status == 422 -> handled++
                else -> {
                    if (status == 403) dropSession()
                    break@loop
                }
            }
        }

        // Tur sürerken eklenen kayıtlar kuyruğun SONUNDA durur; yalnızca bu
        // turda işlenen baştaki kayıtlar çıkarılır.
        synchronized(queueLock) {
            val current = storage.loadQueue()
            storage.saveQueue(current.drop(minOf(handled, current.size)))
        }
    }

    private fun parseIso8601(value: String): Long? = runCatching {
        java.time.Instant.parse(value).toEpochMilli()
    }.recoverCatching {
        java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
    }.getOrNull()
}
