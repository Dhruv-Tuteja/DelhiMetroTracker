// app/build.gradle.kts
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    id("com.google.gms.google-services")
    // REMOVED: id("com.squareup.wire") -> We are using Manual Mode below
}

// 1. Create a configuration to download the compiler tool manually
val wireCompiler by configurations.creating

android {
    namespace = "com.metro.delhimetrotracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.metro.delhimetrotracker"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
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

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // 2. Tell Android Studio where to find the generated code
    sourceSets {
        getByName("main") {
            val wireDir = layout.buildDirectory.dir("generated/source/wire").get().asFile
            // Tell both Java and Kotlin compilers where the files are
            java.srcDir(wireDir)
            kotlin.srcDir(wireDir)
        }
    }
}

dependencies {
    // 3. Add the Compiler Tool (Used by the task below)
    wireCompiler("com.squareup.wire:wire-compiler:5.0.0")

    // 4. The Runtime (Required for the generated code to work)
    implementation("com.squareup.wire:wire-runtime:5.0.0")

    // ... YOUR EXISTING DEPENDENCIES ...
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.android.gms:play-services-auth:20.7.0")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(libs.firebase.analytics.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation("androidx.lifecycle:lifecycle-service:2.6.2")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.service)

    val roomVersion = "2.7.0"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.1")
    implementation(libs.google.play.services.location)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("androidx.room:room-testing:$roomVersion")
}

// 5. THE MANUAL GENERATION TASK
val generateGtfsProtos by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Generates Kotlin classes from GTFS Realtime protos"

    // Use the compiler downloaded in dependencies
    classpath = wireCompiler
    mainClass.set("com.squareup.wire.WireCompiler")

    // Output directory
    val outputDir = layout.buildDirectory.dir("generated/source/wire").get().asFile

    // Arguments for the compiler
    args = listOf(
        "--proto_path=src/main/proto",       // Input folder
        "--kotlin_out=$outputDir",           // Output folder
        "gtfs-realtime.proto"                // Specific file to compile
    )

    // Create directory before running
    doFirst {
        outputDir.mkdirs()
    }
}

// 6. Force generation before compilation
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateGtfsProtos)
}