plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "net.kuecker.lodestar.wear"
    compileSdk = 37

    defaultConfig {
        // The phone app's id: Play offers the watch app of a listing to the watches paired with the phone that has it,
        // and the data layer matches the two by package name.
        applicationId = "net.kuecker.lodestar"
        minSdk = 30
        targetSdk = 36
        // Play wants a version code no bundle of the listing has used: the phone app's, plus one million.
        versionCode = 1_000_004
        versionName = "1.1"
    }

    // Release signing as in :app: from Gradle properties, never from the repository.
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
    }
}

dependencies {
    // Only the message format and its words come from the kit.
    implementation(project(":lodestar-kit"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.play.services.wearable)
    testImplementation(kotlin("test-junit"))
}
