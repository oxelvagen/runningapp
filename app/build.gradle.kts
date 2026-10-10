plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "se.oxelv.mintracker"
    compileSdk = 35
    defaultConfig {
        applicationId = "se.oxelv.mintracker"; minSdk = 26; targetSdk = 35
        versionCode = providers.gradleProperty("appVersionCode").orElse("3").get().toInt()
        versionName = providers.gradleProperty("appVersionName").orElse("1.0.2").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val repository = providers.gradleProperty("updateRepository").orElse("oxelvagen/runningapp").get()
        require(repository.isEmpty() || repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { "updateRepository must be owner/repository" }
        buildConfigField("String", "UPDATE_REPOSITORY", "\"$repository\"")
    }
    val signingStore = providers.environmentVariable("MINTRACKER_KEYSTORE")
    if (signingStore.isPresent) {
        signingConfigs.create("betaRelease") {
            storeFile = file(signingStore.get())
            storePassword = providers.environmentVariable("MINTRACKER_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("MINTRACKER_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("MINTRACKER_KEY_PASSWORD").get()
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("betaRelease")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

// Never accidentally distribute an unsigned release when credentials are absent.
tasks.matching { it.name == "packageRelease" }.configureEach {
    doFirst { check(providers.environmentVariable("MINTRACKER_KEYSTORE").isPresent) {
        "Release signing is not configured. Set MINTRACKER_KEYSTORE and signing credentials; do not distribute an unsigned APK."
    } }
}

dependencies {
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("com.android.tools.build:apksig:8.10.1")
    implementation(platform("io.github.jan-tennert.supabase:bom:3.2.3"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:storage-kt")
    implementation("io.ktor:ktor-client-android:3.1.3")
    implementation("androidx.browser:browser") { version { strictly("1.8.0") } }
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    kapt("androidx.room:room-compiler:2.7.1")
    implementation("org.maplibre.gl:android-sdk:11.13.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

