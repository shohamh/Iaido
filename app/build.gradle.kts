plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.iaido.app"
    compileSdk = 36

    val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
    val releaseKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
    val telemetryBaseUrl = providers.gradleProperty("iaidoTelemetryBaseUrl").getOrElse("")
    val releaseSigningConfigured = listOf(
        releaseKeystorePath,
        releaseKeystorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    ).all { !it.isNullOrBlank() }

    signingConfigs {
        create("iaidoRelease") {
            if (releaseSigningConfigured) {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.iaido.app"
        minSdk = 31
        targetSdk = 36
        versionCode = providers.gradleProperty("iaidoVersionCode").getOrElse("1").toInt()
        versionName = providers.gradleProperty("iaidoVersion").getOrElse("0.1.3")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // `@Ignore` is not honoured by the runner on its own here: with JUnit 4.13.2 the only
        // JUnit on the androidTest classpath, tests annotated `@Ignore` still execute (measured on
        // ImeReelE2eTest: both annotated tests ran and failed, `tests 7 failures 2 skipped 0`).
        // Excluding the annotation explicitly makes `@Ignore` mean what it says, so a test blocked
        // on a known limitation is skipped instead of failing every run.
        testInstrumentationRunnerArguments["notAnnotation"] = "org.junit.Ignore"
        buildConfigField(
            "String",
            "IAIDO_TELEMETRY_BASE_URL",
            "\"${telemetryBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )
    }

    buildTypes {
        getByName("release") {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("iaidoRelease")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    implementation(project(":core-engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    ksp(libs.androidx.room.compiler)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(testFixtures(project(":core-engine")))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    testImplementation(libs.junit.jupiter)
    testImplementation(testFixtures(project(":core-engine")))
    testRuntimeOnly(libs.junit.platform.launcher)
}
