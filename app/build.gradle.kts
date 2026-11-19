plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wifiguard.limiter"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wifiguard.limiter"
        minSdk = 26
        targetSdk = 34
        val buildNum = (project.findProperty("buildNumber") as? String)?.toIntOrNull() ?: 1
        versionCode = buildNum
        versionName = "1.0.$buildNum"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("wifiguard-release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "wifiguard2026"
            keyAlias = System.getenv("KEY_ALIAS") ?: "wifiguard"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "wifiguard2026"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "ENABLE_GITHUB_UPDATER", "true")
            buildConfigField("boolean", "ENABLE_DNS_ADBLOCK", "true")
        }
        create("playStore") {
            dimension = "distribution"
            buildConfigField("boolean", "ENABLE_GITHUB_UPDATER", "false")
            buildConfigField("boolean", "ENABLE_DNS_ADBLOCK", "false")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
