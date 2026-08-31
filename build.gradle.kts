plugins {
    id("com.android.library") version "8.5.0"
    kotlin("android") version "2.0.0"
}

android {
    namespace = "com.veribenim.consent"
    compileSdk = 34

    defaultConfig {
        // Firebase ve Play Services'in bugünkü tabanı; daha aşağısı rıza
        // SDK'sı için gereksiz kısıt yaratır.
        minSdk = 21
    }

    compileOptions {
        // java.time (Instant.parse) API 26 altında desugaring ile çalışır.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Sıfır üçüncü parti runtime bağımlılığı: rıza SDK'sı uygulamanın
    // bağımlılık ağacını kirletmemeli. org.json Android'de yerleşiktir.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    testImplementation(kotlin("test"))
    // org.json birim testlerde yerleşik gelmez — JVM karşılığı eklenir.
    testImplementation("org.json:json:20240303")
}
