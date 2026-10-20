plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "net.kuecker.lodestar"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.kuecker.lodestar"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.1"
    }

    // Release signing comes from Gradle properties (e.g. ~/.gradle/gradle.properties or -P on the command line),
    // never from the repository: lodestar.signing.storeFile, .storePassword, .keyAlias, .keyPassword.
    val signingStore = providers.gradleProperty("lodestar.signing.storeFile").orNull
    if (signingStore != null) {
        signingConfigs {
            create("upload") {
                storeFile = file(signingStore)
                storePassword = providers.gradleProperty("lodestar.signing.storePassword").get()
                keyAlias = providers.gradleProperty("lodestar.signing.keyAlias").getOrElse("upload")
                keyPassword = providers.gradleProperty("lodestar.signing.keyPassword")
                    .orElse(providers.gradleProperty("lodestar.signing.storePassword")).get()
            }
        }
    }

    buildTypes {
        release {
            if (signingStore != null) signingConfig = signingConfigs.getByName("upload")
            isMinifyEnabled = true
            isShrinkResources = true
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

dependencies {
    implementation(project(":lodestar-kit"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.maplibre.android)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.work.runtime)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(kotlin("test-junit"))
}
