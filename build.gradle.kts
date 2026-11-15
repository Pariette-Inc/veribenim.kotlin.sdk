plugins {
    id("com.android.library") version "8.5.0"
    kotlin("android") version "2.0.0"
    // Kotlin 2.0'dan itibaren Compose derleyicisi ayrı eklentidir;
    // `composeOptions.kotlinCompilerExtensionVersion` artık kullanılmaz ve
    // eklenti olmadan `compose = true` derlemeyi durdurur.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0"
    `maven-publish`
}

group = "com.veribenim"
version = "1.0.0" // Veribenim.SDK_VERSION ile aynı tutun.

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

android {
    buildFeatures { compose = true }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// JitPack ya da Maven Central için yayın tanımı. Maven Central ayrıca imzalama
// ve POM'da geliştirici/SCM bilgisi ister; bkz. README → "Yayın".
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.veribenim"
            artifactId = "consent"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }

            pom {
                name.set("Veribenim Android")
                description.set("KVKK & GDPR rıza yönetimi — native Android SDK")
                url.set("https://github.com/Pariette-Inc/veribenim.kotlin.sdk")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                scm { url.set("https://github.com/Pariette-Inc/veribenim.kotlin.sdk") }
            }
        }
    }
}

dependencies {
    // Compose YALNIZCA hazır ConsentSheet için gerekli ve compileOnly:
    // kendi rıza ekranını çizen uygulama Compose'u bağımlılık olarak almasın.
    compileOnly(platform("androidx.compose:compose-bom:2024.06.00"))
    compileOnly("androidx.compose.material3:material3")
    compileOnly("androidx.compose.ui:ui")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Sıfır üçüncü parti runtime bağımlılığı: rıza SDK'sı uygulamanın
    // bağımlılık ağacını kirletmemeli. org.json Android'de yerleşiktir.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // kotlin-test + JUnit4 çalıştırıcısı (Android birim testleri JUnit4 ile koşar).
    testImplementation(kotlin("test-junit"))
    // org.json birim testlerde yerleşik gelmez — JVM karşılığı eklenir.
    testImplementation("org.json:json:20240303")
}
