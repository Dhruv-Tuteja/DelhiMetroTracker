// app/build.gradle.kts
plugins {
    alias(libs.plugins.android.application)
    // Remove the kotlin.android alias if you enabled android.builtInKotlin=true in gradle.properties
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.metro.delhimetrotracker"
    // AGP 9.0 supports up to API 36
    compileSdk = 36

    defaultConfig {
        applicationId = "com.metro.delhimetrotracker"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // New DSL for AGP 9.0
    buildTypes {
        release {
            isMinifyEnabled = false
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

    // This block is often redundant in AGP 9.0 with builtInKotlin,
    // but kept here for specific JVM targeting.
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }

    // Kotlin 2.3.0 and the new Compose plugin handle this automatically;
    // the old composeOptions block can be removed.

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core Android (Using 2026 stable versions)
    implementation(libs.androidx.core.ktx) // Should be 1.17.0+
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation("androidx.lifecycle:lifecycle-service:2.6.2")

    // Lifecycle (2.9.0 is common for 2026 stacks)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.service)

    // Room Database
    // Room 2.7.0 is the 2026 stable standard
    val roomVersion = "2.7.0"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")


    // Coroutines (1.10.0+ for 2026)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.1")

    // Google Play Services (v26.02 is current for Jan 2026)
    //implementation("com.google.android.gms:play-services-location:26.0.2")
    implementation(libs.google.play.services.location)

    // Jetpack Compose (Compose 1.8.0 / BOM 2026.01.00)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.activity.compose)


    // Navigation (Navigation 3 is now stable and recommended)
    implementation("androidx.navigation:navigation-compose:2.9.0")

    // DataStore (1.2.0 stable)
    implementation("androidx.datastore:datastore-preferences:1.2.0")

    // Gson (2.13.2 is latest stable)
    implementation("com.google.code.gson:gson:2.13.2")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    // Testing
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("androidx.room:room-testing:$roomVersion")
}