# Veribenim (Android / Kotlin)

KVKK & GDPR rıza yönetimi — native Android uygulamaları için. iOS karşılığı:
`../veribenim.swift.sdk`. API sözleşmesi ve imza algoritması birebir aynıdır.

Sıfır üçüncü parti runtime bağımlılığı (`org.json` Android'de yerleşiktir).

## Kurulum

JitPack (etiketlendikten sonra):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { maven("https://jitpack.io") } }

// build.gradle.kts
dependencies { implementation("com.github.Pariette-Inc:veribenim.kotlin.sdk:1.0.0") }
```

## Konsol kurulumu

Ürününüzün (sitenizin) konsolunda **Uygulamalar** ekranından iOS ve/veya Android
sürümünü ekleyin (`mobile_consent` modülü gerekir; ürün başına bir kez satılır,
iki platformu da kapsar). SDK'daki `appIdentifier` konsolda girilen bundle id /
package name ile **birebir** aynı olmalı — el sıkışma kimlik + platform çiftiyle
eşleşir. Aynı kimlik iOS ve Android için ayrı iki kayıt olarak eklenir.

## Kullanım

```kotlin
val vb = Veribenim(
    VeribenimConfig(
        appIdentifier = "com.ornek.uygulama",
        appVersion = BuildConfig.VERSION_NAME,
        osVersion = Build.VERSION.RELEASE,
        // baseUrl = "https://live.veribenim.com" (varsayılan; staging / proxy için değiştirin)
    ),
    storage = SharedPreferencesConsentStorage(context),
)

if (vb.needsConsent()) {
    // Kendi rıza ekranınızı gösterin.
}

vb.accept(ConsentPreferences.GRANTED)
vb.rejectAll()

if (vb.isAllowed(ConsentCategory.ANALYTICS)) {
    FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(true)
}
```

### Google Consent Mode v2

```kotlin
vb.onConsentChange { prefs ->
    FirebaseAnalytics.getInstance(context).setConsent(mapOf(
        ConsentType.ANALYTICS_STORAGE  to consent(prefs.analytics),
        ConsentType.AD_STORAGE         to consent(prefs.marketing),
        ConsentType.AD_USER_DATA       to consent(prefs.marketing),
        ConsentType.AD_PERSONALIZATION to consent(prefs.marketing),
    ))
}
```

## Threading

Ağ çağrıları **bloklayıcıdır**. Çağıran kendi scope'unda çalıştırır:

```kotlin
lifecycleScope.launch(Dispatchers.IO) { vb.accept(prefs) }
```

SDK kendi thread politikasını dayatmaz — uygulamanın mimarisi SDK'nın işi değildir.

## Tasarım notları

Bkz. `../veribenim.swift.sdk/README.md` — iki SDK aynı kararları paylaşır:
önce diske sonra ağa, offline kuyruk, imzalı istekler, `strictlyNecessary`
ayarlanamaz.

## Sunucu sözleşmesi

Dört mobil SDK (Swift, Kotlin, Flutter, React Native) aynı sözleşmeyi uygular;
kaynak `veribenim.api` (`AppSessionController`, `ConsentController::log`,
`EnvironmentOriginCheck`, `PreferenceCenterController::show`).

| Adım | İstek | Not |
|---|---|---|
| El sıkışma | `POST /api/app/session` `{app_identifier, platform, app_version?, os_version?}` | İmzasız. Yanıt `{token, device_token, secret, expires_at}`; 30 gün geçerli. 404 = bilinmeyen uygulama **veya** `mobile_consent` modülü kapalı (sunucu bilerek ayırmaz). |
| Rıza kaydı | `POST /api/v/{token}/c` `{action, consents, session_id, user_agent}` | `X-VB-App`, `X-VB-Timestamp` (saniye), `X-VB-Signature` = HMAC-SHA256(`secret`, `"{device_token}.{timestamp}.{ham gövde}"`). Sunucu ±300 sn kabul eder. `Origin`/`Referer` gönderilmez. |
| Tercih okuma | `GET /api/preferences/{token}?platform=mobile&session_id=…` | İmzasız. Kategoriler, mobil kapsamlı izleyiciler, `current_consents`. |

- `platform` rıza gövdesinde **gönderilmez**: sunucu damgayı imzalı cihaz oturumundan okur.
- `user_agent` **şarttır**: sunucu boş değeri bot sayar ve kayıt raporlardan düşer.
  Varsayılan `VeribenimSDK/<sürüm> (<platform> <os>; <dil>)`; yapılandırmadan değiştirilebilir.
- Hata işleme (çevrimdışı kuyruk):
  - 403 / 404 → cihaz oturumu atılır, **bir kez** yeniden el sıkışılır ve aynı kayıt
    yeniden denenir (oturum iptali, süre dolumu, ürün devri).
  - 400 / 422 → kalıcı ret; kayıt atılır, kuyruğu tıkamaz.
  - 402 (kota dolu), 429, 5xx, ağ hatası → kayıt kuyrukta kalır, **sıra korunur**
    (ilk kayıt gidemezse sonrakiler gönderilmez).
- Aynı anda tek kuyruk boşaltma çalışır; boşaltma sürerken verilen karar kaybolmaz.

## Test

```bash
./gradlew test
```

Depoda Gradle wrapper yok; ilk kez `gradle wrapper --gradle-version 8.7`
ile üretin (AGP 8.5 en az Gradle 8.7 ister).
