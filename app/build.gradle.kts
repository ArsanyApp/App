plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * Licensing configuration comes from Gradle properties or environment variables, never from source:
 *   LICENSE_API_URL     https://<your-worker>.workers.dev
 *   LICENSE_PUBLIC_KEY  base64 SPKI public key (public; the private key stays in Cloudflare)
 * CI reads them from GitHub Actions variables. LICENSE_ALLOW_INSECURE is only for emulator tests.
 */
fun licenseSetting(property: String, env: String): String =
    (project.findProperty(property) as String?)?.takeIf { it.isNotBlank() } ?: System.getenv(env).orEmpty()

val licenseApiUrl = licenseSetting("licenseApiUrl", "LICENSE_API_URL").trim()
val licensePublicKey = licenseSetting("licensePublicKey", "LICENSE_PUBLIC_KEY").trim()
val licenseAllowInsecure = licenseSetting("licenseAllowInsecure", "LICENSE_ALLOW_INSECURE") == "true"

android {
    namespace = "com.choice.autotap"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.choice.autotap"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"

        buildConfigField("String", "LICENSE_API_URL", "\"$licenseApiUrl\"")
        buildConfigField("String", "LICENSE_PUBLIC_KEY", "\"$licensePublicKey\"")
        buildConfigField("boolean", "LICENSE_ALLOW_INSECURE", licenseAllowInsecure.toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":gesture-engine"))
    implementation(project(":overlay-ui"))
    implementation(project(":license-core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.reorderable)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
