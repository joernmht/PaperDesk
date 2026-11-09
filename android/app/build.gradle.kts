import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun lp(key: String, def: String) = (localProps.getProperty(key) ?: def).replace("\"", "")

android {
    namespace = "com.joernmht.paperdesk"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.joernmht.paperdesk"
        minSdk = 33
        targetSdk = 36
        versionCode = 7
        versionName = "0.7"
        // Baked in from android/local.properties (never committed); overridable in Settings.
        buildConfigField("String", "DEFAULT_URL", "\"${lp("paperdesk.url", "https://paper1.tailbf37ab.ts.net:8447")}\"")
        buildConfigField("String", "DEFAULT_TOKEN", "\"${lp("paperdesk.token", "")}\"")
    }
    buildTypes {
        // R8-shrunk build (56 MB debug → a few MB; icons-extended is huge). Debug-signed: never published.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(bom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    // Markdown in chat answers; 0.35.0 = last release built with Kotlin 2.1 (the project uses Kotlin 2.2.10)
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.35.0")
}
