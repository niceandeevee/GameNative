plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.dagger.hilt)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.jetbrains.serialization)
    alias(libs.plugins.kotlinter)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

room {
    schemaDirectory("$projectDir/schemas")
}

// Debug-only: package the repo's manifest.json so debug builds read it locally (never in release).
val copyDebugManifest by tasks.registering(Copy::class) {
    from(rootProject.file("manifest.json"))
    into(layout.buildDirectory.dir("generated/debugManifest"))
}

android {
    namespace = "app.gamenative"
    compileSdk = 36

    // https://developer.android.com/ndk/downloads
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "app.gamenative"

        minSdk = 26

        manifestPlaceholders["screenOrientation"] = "unspecified"

        // CI builds pass -PversionCodeOverride so every build is a higher version than the last one
        // and installs over it as a normal update.
        versionCode = (project.findProperty("versionCodeOverride") as String?)?.toIntOrNull() ?: 24
        versionName = "1.4.0"

        val iconValue = "@mipmap/ic_launcher"
        val iconRoundValue = "@mipmap/ic_launcher_round"
        manifestPlaceholders.putAll(
            mapOf(
                "icon" to iconValue,
                "roundIcon" to iconRoundValue,
            ),
        )

        // Localization support - specify which languages to include

        vectorDrawables {
            useSupportLibrary = true
        }

        proguardFiles(
            // getDefaultProguardFile("proguard-android-optimize.txt"),
            getDefaultProguardFile("proguard-android.txt"),
            "proguard-rules.pro",
        )
    }

    flavorDimensions += "androidApi"
    productFlavors {
        create("modern") {
            dimension = "androidApi"
            minSdk = 29
            targetSdk = 36
            ndk.abiFilters += listOf("arm64-v8a")
            buildConfigField("String", "PRELOAD_BIONIC_SO", "\"libredirect-bionic-wx.so\"")
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Keep only the app's own translations (drops library locales such as AppCompat's).
    androidResources {
        localeFilters += listOf(
            "en", "es", "da", "pt-BR", "zh-TW", "zh-CN", "fr", "de", "uk", "it", "ro", "pl", "ru", "ko", "ja",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/DebugProbesKt.bin"
            excludes += "/junit/runner/smalllogo.gif"
            excludes += "/junit/runner/logo.gif"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
        jniLibs {
            // Native libs must be extracted to nativeLibraryDir: several are exec'd or LD_PRELOADed
            useLegacyPackaging = true
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // CI passes -PtestForks=2 -PtestHeap=3g to split the suite over two test JVMs
                it.maxParallelForks = (project.findProperty("testForks") as String?)?.toIntOrNull() ?: 1
                it.maxHeapSize = (project.findProperty("testHeap") as String?) ?: "4g"
                it.testLogging { events("failed") }
            }
        }
    }

    // Configure Assets to be used in different variants
    sourceSets {
        getByName("modern") {
            assets {
                srcDirs("src/modern/assets", "src/main/assets")
            }
        }
        getByName("debug") {
            assets.srcDir(copyDebugManifest)
        }
    }

    kotlinter {
        ignoreFormatFailures  = false
    }
}

dependencies {
    implementation(libs.material)

    // Chrome Custom Tabs (Nexus / account sign-in)
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // JavaSteam
    val localBuild = false // Change to 'true' needed when building JavaSteam manually
    if (localBuild) {
        implementation(files("../../JavaSteam/build/libs/javasteam-1.8.0.1-26-SNAPSHOT.jar"))
        implementation(files("../../JavaSteam/javasteam-depotdownloader/build/libs/javasteam-depotdownloader-1.8.0.1-26-SNAPSHOT.jar"))
        implementation(libs.bundles.javasteam.dev)
    } else {
        implementation(libs.javasteam) {
            isChanging = version?.contains("SNAPSHOT") ?: false
        }
        implementation(libs.javasteam.depotdownloader) {
            isChanging = version?.contains("SNAPSHOT") ?: false
        }
    }
    implementation(libs.okhttp.dnsoverhttps)


    // Winlator
    implementation(libs.bundles.winlator)
    implementation(libs.libarchive.android)
    implementation(libs.zstd.jni) { artifact { type = "aar" } }
    implementation(libs.xz)
    implementation(libs.jzlib)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.landscapist.coil)
    debugImplementation(libs.androidx.ui.tooling)

    // Support
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.apng)
    implementation(libs.datastore.preferences)
    implementation(libs.jetbrains.kotlinx.json)
    implementation(libs.kotlin.coroutines)
    implementation(libs.timber)
    implementation(libs.zxing)

    // Google Protobufs (JavaSteam's messages extend them)
    implementation(libs.protobuf.java)

    // Hilt
    implementation(libs.bundles.hilt)

    // KSP (Hilt, Room)
    ksp(libs.bundles.ksp)

    // Room Database
    implementation(libs.bundles.room)

    // Testing
    debugImplementation(libs.androidx.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.mockk)
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.zstd.jni)
    testImplementation(libs.orgJson)
    testImplementation(libs.mockwebserver)

    implementation("com.auth0.android:jwtdecode:2.0.2")

    // Samsung Performance SDK
    implementation(files("src/main/lib/perfsdk-v1.0.0.jar"))
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}
