package com.veribenim.consent

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * İstek imzalayıcı.
 *
 * Sunucu karşılığı: `AppDeviceSession::verifySignature`
 * (veribenim.api/app/Models/AppDeviceSession.php).
 * iOS karşılığı: `VeribenimKit.RequestSigner`.
 *
 * İmzalanan dize: `"{deviceToken}.{timestamp}.{ham gövde}"`
 *   • timestamp → replay penceresi (sunucu ±5 dk kabul eder)
 *   • gövde     → imza başka bir gövdeye yapıştırılamasın diye
 *
 * Bu üçlü bozulursa native isteklerin tamamı 403 döner; bu yüzden üç dilde de
 * aynı dize üretildiğini doğrulayan test vardır.
 */
object RequestSigner {
    const val APP_HEADER = "X-VB-App"
    const val TIMESTAMP_HEADER = "X-VB-Timestamp"
    const val SIGNATURE_HEADER = "X-VB-Signature"

    fun signature(deviceToken: String, secret: String, timestampSeconds: Long, body: String): String {
        val payload = "$deviceToken.$timestampSeconds.$body"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun headers(session: DeviceSession, body: String, nowMillis: Long = System.currentTimeMillis()): Map<String, String> {
        val ts = nowMillis / 1000
        return mapOf(
            APP_HEADER to session.deviceToken,
            TIMESTAMP_HEADER to ts.toString(),
            SIGNATURE_HEADER to signature(session.deviceToken, session.secret, ts, body),
        )
    }
}
