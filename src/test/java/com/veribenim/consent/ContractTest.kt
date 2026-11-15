package com.veribenim.consent

import org.json.JSONObject
import kotlin.test.*

/**
 * API sözleşmesini sabitleyen testler — Swift `ContractTests.swift` ile aynı senaryolar.
 *
 * Kaynak: veribenim.api → `AppSessionController::issue`, `ConsentController::log`,
 * `PreferenceCenterController::show`, `EnvironmentOriginCheck::handleNativeRequest`
 * ve `tests/Feature/MobileConsentTest.php`.
 */
private data class Call(val url: String, val method: String, val headers: Map<String, String>, val body: String?)

/** Sıralı yanıt veren, istekleri kaydeden sahte taşıyıcı. Yanıt yoksa ağ hatası. */
private class Scripted(vararg responses: Pair<Int, String>) : Transport {
    val queue = responses.toMutableList()
    val calls = mutableListOf<Call>()
    /** İlk rıza isteğinde bir kez çalışır — eşzamanlılık senaryosu için. */
    var onFirstConsent: (() -> Unit)? = null

    val consentCalls get() = calls.filter { it.url.endsWith("/c") }
    val handshakeCalls get() = calls.filter { it.url.endsWith("/api/app/session") }

    override fun send(url: String, method: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        calls.add(Call(url, method, headers, body))
        if (url.endsWith("/c")) onFirstConsent?.let { onFirstConsent = null; it() }
        if (queue.isEmpty()) throw java.io.IOException("ağ yok")
        return queue.removeAt(0)
    }
}

private fun handshake(token: String = "env-token", device: String = "dev-token", secret: String = "s3cr3t") =
    200 to """{"status":true,"token":"$token","device_token":"$device","secret":"$secret","expires_at":"2099-01-01T00:00:00+00:00"}"""

private val OK = 200 to """{"status":true,"message":"Consent recorded","consent_renewal_required":false}"""

/** Sunucudaki `BotDetector::BOT_PATTERNS` içinden UA'ya karışabilecekler. */
private val BOT_PATTERNS = listOf(
    "bot/", "crawler", "spider", "scraper", "fetcher", "wget/", "curl/", "python-requests",
    "go-http-client", "ruby", "java/", "apache-httpclient", "okhttp", "axios/", "node-fetch",
    "got/", "headlesschrome", "whatsapp",
)

private fun keys(body: String?) = JSONObject(body!!).keys().asSequence().toSet()

private fun sdk(
    t: Scripted,
    storage: ConsentStorage = InMemoryConsentStorage(),
    appVersion: String? = "1.4.2",
    baseUrl: String = "https://api.test",
    clock: () -> Long = System::currentTimeMillis,
) = Veribenim(
    VeribenimConfig("com.ornek.uygulama", baseUrl = baseUrl, appVersion = appVersion, osVersion = "14"),
    storage, t, clock,
)

class HandshakeContractTest {
    @Test
    fun handshakeRequestShape() {
        val t = Scripted(handshake(), OK)
        sdk(t).accept()

        val c = t.handshakeCalls.single()
        assertEquals("https://api.test/api/app/session", c.url)
        assertEquals("POST", c.method)
        assertNull(c.headers[RequestSigner.APP_HEADER])

        val body = JSONObject(c.body!!)
        assertEquals(setOf("app_identifier", "platform", "app_version", "os_version"), keys(c.body))
        assertEquals("com.ornek.uygulama", body.getString("app_identifier"))
        assertEquals("android", body.getString("platform"))
        assertEquals("1.4.2", body.getString("app_version"))
        assertEquals("14", body.getString("os_version"))
    }

    @Test
    fun handshakeOmitsNullVersions() {
        val t = Scripted(handshake(), OK)
        Veribenim(VeribenimConfig("a", baseUrl = "https://api.test"), InMemoryConsentStorage(), t).accept()
        assertEquals(setOf("app_identifier", "platform"), keys(t.handshakeCalls.single().body))
    }

    /** Sunucu `max:32` doğrular; uzun dize el sıkışmayı 422'ye düşürürdü. */
    @Test
    fun handshakeTruncatesLongVersion() {
        val t = Scripted(handshake(), OK)
        sdk(t, appVersion = "9".repeat(60)).accept()
        assertEquals(32, JSONObject(t.handshakeCalls.single().body!!).getString("app_version").length)
    }

    /** Sondaki `/` çift bölü üretip 404'e yol açmamalı. */
    @Test
    fun trailingSlashInBaseUrlIsTolerated() {
        val t = Scripted(handshake(), OK)
        sdk(t, baseUrl = "https://api.test/").accept()
        assertEquals("https://api.test/api/app/session", t.calls[0].url)
        assertEquals("https://api.test/api/v/env-token/c", t.calls[1].url)
    }

    @Test
    fun sessionIsStoredAndReused() {
        val storage = InMemoryConsentStorage()
        val t = Scripted(handshake(), OK, OK)
        val vb = sdk(t, storage)
        vb.accept()
        vb.rejectAll()

        assertEquals(1, t.handshakeCalls.size)
        val s = storage.loadSession()!!
        assertEquals("env-token", s.environmentToken)
        assertEquals("dev-token", s.deviceToken)
        assertEquals("s3cr3t", s.secret)
        assertEquals(4070908800000L, s.expiresAtMillis) // 2099-01-01T00:00:00Z
    }

    @Test
    fun sessionSurvivesRestart() {
        val storage = InMemoryConsentStorage()
        val t = Scripted(handshake(), OK, OK)
        sdk(t, storage).accept()
        sdk(t, storage).rejectAll()
        assertEquals(1, t.handshakeCalls.size)
    }

    @Test
    fun nearlyExpiredSessionIsRenewed() {
        val storage = InMemoryConsentStorage()
        storage.saveSession(DeviceSession("old-env", "old-dev", "x", System.currentTimeMillis() + 600_000))
        val t = Scripted(handshake(), OK)
        sdk(t, storage).accept()

        assertEquals(1, t.handshakeCalls.size)
        assertEquals("https://api.test/api/v/env-token/c", t.consentCalls.single().url)
        assertEquals("dev-token", storage.loadSession()!!.deviceToken)
    }

    /** 404 = bilinmeyen uygulama VEYA modül kapalı; sunucu bilerek ayırmaz. */
    @Test
    fun handshake404KeepsDecisionQueued() {
        val storage = InMemoryConsentStorage()
        val t = Scripted(404 to """{"status":false}""")
        val vb = sdk(t, storage)
        vb.accept()

        assertEquals(1, t.calls.size)
        assertNull(storage.loadSession())
        assertEquals(1, storage.loadQueue().size)
        assertFalse(vb.needsConsent())
    }
}

class ConsentContractTest {
    @Test
    fun consentBodyMatchesContract() {
        val t = Scripted(handshake(), OK)
        sdk(t).accept(ConsentPreferences(functional = true, marketing = true))

        val c = t.consentCalls.single()
        assertEquals("POST", c.method)
        assertEquals("https://api.test/api/v/env-token/c", c.url)
        assertNull(c.headers["Origin"], "Origin varsa sunucu native dalı hiç denemez.")

        assertEquals(setOf("action", "consents", "session_id", "user_agent"), keys(c.body),
            "`platform` gövdede olmamalı: sunucu damgayı imzalı oturumdan okur.")
        val body = JSONObject(c.body!!)
        assertEquals("save_preferences", body.getString("action"))
        val consents = body.getJSONObject("consents")
        assertEquals(setOf("strictly_necessary", "functional", "analytics", "marketing"), consents.keys().asSequence().toSet())
        assertTrue(consents.getBoolean("strictly_necessary"))
        assertTrue(consents.getBoolean("functional"))
        assertFalse(consents.getBoolean("analytics"))
        assertTrue(consents.getBoolean("marketing"))
        assertTrue(body.getString("session_id").isNotEmpty())
    }

    @Test
    fun actionsMapToServerEnum() {
        val t = Scripted(handshake(), OK, OK, OK, OK)
        val vb = sdk(t)
        vb.accept()
        vb.accept(ConsentPreferences(analytics = true))
        vb.rejectAll()
        vb.withdraw()

        val bodies = t.consentCalls.map { JSONObject(it.body!!) }
        assertEquals(listOf("accept_all", "save_preferences", "reject_all", "withdraw"), bodies.map { it.getString("action") })
        assertEquals(1, bodies.map { it.getString("session_id") }.toSet().size)
    }

    /** Sunucu `user_agent` boşsa kaydı bot sayar (BotDetector: empty_ua). */
    @Test
    fun userAgentIsSentAndNotBotLike() {
        val t = Scripted(handshake(), OK)
        sdk(t).accept()

        val ua = JSONObject(t.consentCalls.single().body!!).getString("user_agent")
        assertEquals("VeribenimSDK/${Veribenim.SDK_VERSION} (android 14; kotlin)", ua)
        BOT_PATTERNS.forEach { assertFalse(ua.lowercase().contains(it), "UA bot kalıbı içeriyor: $it") }
    }

    @Test
    fun signatureCoversExactBodyAndUsesSeconds() {
        val now = 1_700_000_000_123L
        val t = Scripted(handshake(), OK)
        sdk(t, clock = { now }).accept()

        val c = t.consentCalls.single()
        assertEquals("dev-token", c.headers[RequestSigner.APP_HEADER])
        assertEquals("1700000000", c.headers[RequestSigner.TIMESTAMP_HEADER])
        assertEquals(RequestSigner.signature("dev-token", "s3cr3t", 1_700_000_000L, c.body!!), c.headers[RequestSigner.SIGNATURE_HEADER])
    }

    @Test
    fun forbiddenRehandshakesAndRetriesOnce() {
        val storage = InMemoryConsentStorage()
        val t = Scripted(handshake(), 403 to """{"status":false,"message":"Unauthorized device"}""",
            handshake("new-env", "new-dev", "new"), OK)
        sdk(t, storage).accept()

        assertEquals(2, t.handshakeCalls.size)
        assertEquals(2, t.consentCalls.size)
        val retry = t.consentCalls.last()
        assertEquals("https://api.test/api/v/new-env/c", retry.url)
        assertEquals("new-dev", retry.headers[RequestSigner.APP_HEADER])
        assertTrue(storage.loadQueue().isEmpty())
        assertEquals("new-dev", storage.loadSession()!!.deviceToken)
    }

    @Test
    fun forbiddenTwiceStopsAndKeepsRecord() {
        val storage = InMemoryConsentStorage()
        val t = Scripted(handshake(), 403 to "{}", handshake(), 403 to "{}", OK)
        sdk(t, storage).accept()

        assertEquals(4, t.calls.size, "Sonsuz el sıkışma döngüsü olmamalı.")
        assertNull(storage.loadSession())
        assertEquals(1, storage.loadQueue().size)
    }

    @Test
    fun quotaExceededKeepsQueueAndOrder() {
        val storage = InMemoryConsentStorage()
        val t = Scripted()
        val vb = sdk(t, storage)
        vb.accept()
        vb.withdraw()
        assertEquals(2, storage.loadQueue().size)

        t.queue.addAll(listOf(handshake(), 402 to """{"status":false,"message":"Impression quota exceeded"}"""))
        vb.flush()

        assertEquals(1, t.consentCalls.size, "İlk kayıt gidemediyse ikincisi öne geçmemeli.")
        assertEquals(listOf(ConsentAction.ACCEPT_ALL, ConsentAction.WITHDRAW), storage.loadQueue().map { it.action })
    }

    @Test
    fun validationErrorDropsOnlyThatEntry() {
        val storage = InMemoryConsentStorage()
        val t = Scripted()
        val vb = sdk(t, storage)
        vb.accept()
        vb.rejectAll()

        t.queue.addAll(listOf(handshake(), 422 to """{"message":"invalid"}""", OK))
        vb.flush()

        assertEquals(2, t.consentCalls.size)
        assertTrue(storage.loadQueue().isEmpty())
    }

    @Test
    fun serverErrorAndThrottleKeepQueue() {
        for (status in listOf(429, 500, 503)) {
            val storage = InMemoryConsentStorage()
            sdk(Scripted(handshake(), status to "{}"), storage).accept()
            assertEquals(1, storage.loadQueue().size, "$status geçicidir")
            assertNotNull(storage.loadSession(), "$status oturumu düşürmemeli")
        }
    }

    @Test
    fun offlineQueueSendsInOrderWhenBack() {
        val storage = InMemoryConsentStorage()
        val t = Scripted()
        val vb = sdk(t, storage)
        vb.accept()
        vb.withdraw()

        t.queue.addAll(listOf(handshake(), OK, OK))
        vb.flush()

        assertEquals(listOf("accept_all", "withdraw"), t.consentCalls.map { JSONObject(it.body!!).getString("action") })
        assertTrue(storage.loadQueue().isEmpty())
    }

    /** Boşaltma sürerken (başka thread'de) verilen karar kaybolmamalı, iki kez gitmemeli. */
    @Test
    fun decisionDuringFlushIsNotLostOrDuplicated() {
        val storage = InMemoryConsentStorage()
        val t = Scripted(handshake(), OK, OK)
        val vb = sdk(t, storage)

        t.onFirstConsent = {
            // İlk rıza isteği uçuştayken ikinci karar başka bir thread'den gelir.
            val other = Thread { vb.rejectAll() }
            other.start()
            Thread.sleep(100)
        }
        vb.accept()
        vb.flush()

        assertEquals(listOf("accept_all", "reject_all"), t.consentCalls.map { JSONObject(it.body!!).getString("action") })
        assertTrue(storage.loadQueue().isEmpty())
    }
}

class PreferencesContractTest {
    @Test
    fun preferencesRequestShape() {
        val storage = InMemoryConsentStorage()
        storage.saveRecord(ConsentRecord(ConsentPreferences.GRANTED, ConsentAction.ACCEPT_ALL, 0L, null, "cihaz 1"))
        val t = Scripted(handshake(), 200 to """{"status":true,"categories":[],"platform":"mobile"}""")
        sdk(t, storage, clock = { 0L }).refresh()

        val c = t.calls.last()
        assertEquals("GET", c.method)
        assertEquals("https://api.test/api/preferences/env-token?platform=mobile&session_id=cihaz+1", c.url)
        assertNull(c.headers[RequestSigner.SIGNATURE_HEADER])
    }

    @Test
    fun preferencesErrorIsThrown() {
        val t = Scripted(handshake(), 404 to """{"status":false}""")
        val e = assertFailsWith<VeribenimException> { sdk(t).refresh() }
        assertEquals(404, e.status)
    }
}

class StorageJsonTest {
    @Test
    fun queueRoundTripsThroughJson() {
        val q = listOf(QueuedConsent(ConsentAction.WITHDRAW, ConsentPreferences(analytics = true), "s", 42L))
        assertEquals(q, ConsentJson.queueFromJson(ConsentJson.queueToJson(q)))
    }

    @Test
    fun sessionRoundTripsThroughJson() {
        val s = DeviceSession("e", "d", "x", 99L)
        assertEquals(s, ConsentJson.sessionFromJson(ConsentJson.sessionToJson(s)))
    }

    @Test
    fun corruptJsonIsIgnored() {
        assertNull(ConsentJson.recordFromJson("{bozuk"))
        assertTrue(ConsentJson.queueFromJson("{bozuk").isEmpty())
    }
}
