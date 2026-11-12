import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
// -Ppaperdesk.public=true: the build anyone may have. Nothing from local.properties is baked in; the app
// starts on its setup screen and is connected with a pairing link (paperdesk://pair?…).
val publicBuild = (findProperty("paperdesk.public") as String?).toBoolean()
fun lp(key: String) = if (publicBuild) "" else (localProps.getProperty(key) ?: "").replace("\"", "").replace("\\", "")

android {
    namespace = "com.joernmht.paperdesk"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.joernmht.paperdesk"
        minSdk = 33
        targetSdk = 36
        versionCode = 12
        versionName = "0.12"
        // Private build only: baked in from android/local.properties (never committed); overridable in Settings.
        buildConfigField("String", "DEFAULT_URL", "\"${lp("paperdesk.url")}\"")
        buildConfigField("String", "DEFAULT_TOKEN", "\"${lp("paperdesk.token")}\"")
        buildConfigField("String", "DEFAULT_AGENT_URL", "\"${lp("paperdesk.agent")}\"")
        buildConfigField("boolean", "PUBLIC_BUILD", "$publicBuild")
    }
    // Public builds are signed with the PaperDesk release key (keystore + password file outside the repo,
    // default ~/.config/paperdesk/release.{jks,pass}); Joern's private build stays on the debug key so his
    // installed app keeps updating. Updates of an installed app must always use the same key.
    val ks = file((findProperty("paperdesk.keystore") as String?) ?: "${System.getProperty("user.home")}/.config/paperdesk/release.jks")
    val ksPass = file(ks.path.removeSuffix(".jks") + ".pass")
    if (publicBuild && ks.exists() && ksPass.exists()) {
        signingConfigs.create("paperdesk") {
            storeFile = ks
            storePassword = ksPass.readText().trim()
            keyAlias = "paperdesk"
            keyPassword = ksPass.readText().trim()
        }
    }
    buildTypes {
        // R8-shrunk build (56 MB debug → a few MB; icons-extended is huge).
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("paperdesk") ?: signingConfigs.getByName("debug")
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
    // QR scanner for the setup wizard: Google's ready-made scanner screen, needs no camera permission
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // play-services drags in Fragment 1.0, which breaks registerForActivityResult (lint: InvalidFragmentVersionForActivityResult)
    implementation("androidx.fragment:fragment:1.8.5")
}
