package com.veribenim.consent

import kotlin.test.*

/** Sahte taşıyıcı — istekleri kaydeder, hazır yanıt döner. */
class StubTransport : Transport {
    var responses = mutableListOf<Pair<Int, String>>()
    val requests = mutableListOf<Triple<String, Map<String, String>, String?>>()

    override fun send(url: String, method: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        requests.add(Triple(url, headers, body))
        if (responses.isEmpty()) throw VeribenimException("ağ yok")
        return responses.removeAt(0)
    }
}

private const val HANDSHAKE = """
{"status":true,"token":"env-token","device_token":"dev-token","secret":"s3cr3t","expires_at":"2099-01-01T00:00:00Z"}
"""

class SignatureTest {
    /**
     * Sunucudaki `AppDeviceSession::verifySignature` ve iOS'taki
     * `RequestSigner.signature` ile BİREBİR aynı dizeyi imzaladığımızın kanıtı.
     * Beklenen değer PHP'den alındı:
     *   php -r 'echo hash_hmac("sha256",
     *     "dev-token.1700000000." . json_encode(["action"=>"accept_all"]), "s3cr3t");'
     */
    @Test
    fun signatureMatchesServerAlgorithm() {
        val sig = RequestSigner.signature(
            "dev-token", "s3cr3t", 1_700_000_000L, """{"action":"accept_all"}"""
        )
        assertEquals("78ae089b104fd31c6509393ffd15e1b9c4875b43877de1a69bcf55399bbe9ff7", sig)
    }

    @Test
    fun signatureChangesWithBody() {
        val a = RequestSigner.signature("d", "s", 1L, "a")
        val b = RequestSigner.signature("d", "s", 1L, "b")
        assertNotEquals(a, b, "Gövde imzaya girmezse imza başka bir isteğe yapıştırılabilir.")
    }
}

class ConsentModelTest {
    @Test
    fun strictlyNecessaryAlwaysGranted() {
        assertTrue(ConsentPreferences.DENIED[ConsentCategory.STRICTLY_NECESSARY])
        assertEquals(true, ConsentPreferences.DENIED.toPayload()["strictly_necessary"])
    }

    @Test
    fun payloadRoundTrip() {
        val prefs = ConsentPreferences(functional = true, marketing = true)
        assertEquals(prefs, ConsentPreferences.fromPayload(prefs.toPayload()))
    }
}

class ConsentFlowTest {
    private fun sdk(transport: StubTransport, storage: ConsentStorage = InMemoryConsentStorage()) =
        Veribenim(VeribenimConfig("com.ornek.uygulama", baseUrl = "https://api.test"), storage, transport)

    @Test
    fun needsConsentBeforeAnyDecision() {
        assertTrue(sdk(StubTransport()).needsConsent())
    }

    @Test
    fun acceptPersistsAndUnlocksCategories() {
        val transport = StubTransport().apply { responses = mutableListOf(200 to HANDSHAKE, 200 to "{}") }
        val storage = InMemoryConsentStorage()
        val vb = sdk(transport, storage)

        vb.accept(ConsentPreferences.GRANTED)

        assertFalse(vb.needsConsent())
        assertTrue(vb.isAllowed(ConsentCategory.ANALYTICS))
        assertNotNull(storage.loadRecord())
        assertTrue(storage.loadQueue().isEmpty(), "Başarılı gönderimden sonra kuyruk boşalmalı.")
    }

    /** Ağ yokken karar KAYBOLMAMALI — rıza ispatı veri sorumlusundadır. */
    @Test
    fun consentSurvivesNetworkFailure() {
        val storage = InMemoryConsentStorage()
        val vb = sdk(StubTransport(), storage)   // yanıt yok → her istek hata

        vb.accept(ConsentPreferences.GRANTED)

        assertFalse(vb.needsConsent(), "Ağ hatası kullanıcıya soruyu ikinci kez sordurmamalı.")
        assertEquals(1, storage.loadQueue().size, "Gönderilemeyen kayıt kuyrukta beklemeli.")
    }

    @Test
    fun queueDrainsOnNextFlush() {
        val transport = StubTransport()
        val storage = InMemoryConsentStorage()
        val vb = sdk(transport, storage)

        vb.accept(ConsentPreferences.GRANTED)
        assertEquals(1, storage.loadQueue().size)

        transport.responses = mutableListOf(200 to HANDSHAKE, 200 to "{}")
        vb.flush()

        assertTrue(storage.loadQueue().isEmpty())
    }

    /** 403 = oturum düşmüş veya modül kapanmış. Oturum atılır, kayıt korunur. */
    @Test
    fun forbiddenDropsSessionButKeepsRecord() {
        val transport = StubTransport().apply { responses = mutableListOf(200 to HANDSHAKE, 403 to "{}") }
        val storage = InMemoryConsentStorage()

        sdk(transport, storage).accept(ConsentPreferences.GRANTED)

        assertNull(storage.loadSession(), "403 sonrası cihaz oturumu atılmalı.")
        assertEquals(1, storage.loadQueue().size, "Rıza kaydı silinmemeli.")
    }

    @Test
    fun consentRequestIsSigned() {
        val transport = StubTransport().apply { responses = mutableListOf(200 to HANDSHAKE, 200 to "{}") }
        sdk(transport).accept(ConsentPreferences.GRANTED)

        val (url, headers, body) = transport.requests.last()
        assertEquals("https://api.test/api/v/env-token/c", url)
        assertNotNull(headers[RequestSigner.SIGNATURE_HEADER])

        val expected = RequestSigner.signature(
            "dev-token", "s3cr3t", headers[RequestSigner.TIMESTAMP_HEADER]!!.toLong(), body!!
        )
        assertEquals(expected, headers[RequestSigner.SIGNATURE_HEADER])
    }

    @Test
    fun handshakeIsUnsigned() {
        val transport = StubTransport().apply { responses = mutableListOf(200 to HANDSHAKE, 200 to "{}") }
        sdk(transport).accept(ConsentPreferences.GRANTED)

        val (url, headers, _) = transport.requests.first()
        assertEquals("https://api.test/api/app/session", url)
        assertNull(headers[RequestSigner.SIGNATURE_HEADER], "El sıkışma imzasızdır — imzalayacak sır henüz yok.")
    }
}
