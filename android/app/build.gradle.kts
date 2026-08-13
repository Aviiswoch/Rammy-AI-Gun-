plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun signingValue(name: String): String? =
    providers.gradleProperty(name)
        .orElse(providers.environmentVariable(name))
        .orNull
        ?.takeIf(String::isNotBlank)

val rammyReleaseStoreFile = signingValue("RAMMY_RELEASE_STORE_FILE")
val rammyReleaseStorePassword = signingValue("RAMMY_RELEASE_STORE_PASSWORD")
val rammyReleaseKeyAlias = signingValue("RAMMY_RELEASE_KEY_ALIAS")
val rammyReleaseKeyPassword = signingValue("RAMMY_RELEASE_KEY_PASSWORD")
val hasRammyReleaseSigning = listOf(
    rammyReleaseStoreFile,
    rammyReleaseStorePassword,
    rammyReleaseKeyAlias,
    rammyReleaseKeyPassword,
).all { it != null }
val useLocalTestSigning = providers.gradleProperty("rammyLocalTestSigning")
    .orNull
    .toBoolean()

android {
    namespace = "com.rammy.aigun"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rammy.aigun"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        ndk.abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
    }

    signingConfigs {
        if (hasRammyReleaseSigning) {
            create("rammyRelease") {
                storeFile = file(rammyReleaseStoreFile!!)
                storePassword = rammyReleaseStorePassword
                keyAlias = rammyReleaseKeyAlias
                keyPassword = rammyReleaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            when {
                hasRammyReleaseSigning -> signingConfig = signingConfigs.getByName("rammyRelease")
                useLocalTestSigning -> signingConfig = signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions.jvmTarget = "17"
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")

    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Maintained native libuvc + USB host implementation. Frames stay outside Compose.
    implementation("com.herohan:UVCAndroid:1.0.13")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
