# Veribenim (Android / Kotlin)

KVKK & GDPR rıza yönetimi — native Android uygulamaları için. iOS karşılığı:
`../veribenim.swift.sdk`. API sözleşmesi ve imza algoritması birebir aynıdır.

Sıfır üçüncü parti runtime bağımlılığı (`org.json` Android'de yerleşiktir).

## Kullanım

```kotlin
val vb = Veribenim(
    VeribenimConfig(appIdentifier = "com.ornek.uygulama"),
    storage = /* EncryptedSharedPreferences tabanlı ConsentStorage */,
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

## Test

```bash
./gradlew test
```
