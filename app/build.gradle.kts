plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "app.sojourn"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "app.sojourn"
        minSdk = 26
        targetSdk = 37
        // The Makefile passes the build date (yyyyMMddHH), so every build is a valid update of
        // the one before. A property rather than a clock read keeps the configuration cache honest.
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("versionName").orNull ?: "dev"
    }

    signingConfigs {
        // A fixed, public debug key. Gradle would otherwise make a new one inside every build
        // container, and Android refuses an update signed with a different key.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        // Debug builds install beside a release build, so both can live on one phone.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        // Release APKs are left unsigned here and signed by scripts/sign-release.sh, so the
        // release key's password never passes through Gradle or its caches.
        release {
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

    lint {
        abortOnError = true
        // Version-bump hints change with every upstream release, not with this code.
        disable += setOf("NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion")
    }

    room {
        schemaDirectory("$projectDir/schemas")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // Robolectric only sees the app's merged assets, so the exported Room schemas ride along in
    // debug builds for the migration tests; release builds never include them.
    sourceSets.getByName("debug").assets.directories.add("$projectDir/schemas")
}

kotlin {
    compilerOptions {
        // Expressive components are adopted deliberately while material3 1.5 is in beta.
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        showStandardStreams = true
    }
    // Screenshot tests render through Robolectric and take far longer than the rest of
    // the suite, so they only run when asked for (make screenshots).
    if (providers.gradleProperty("screenshots").isPresent) {
        filter.includeTestsMatching("*ScreenshotTest")
        outputs.upToDateWhen { false }
    } else {
        exclude("**/*ScreenshotTest*")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.documentfile)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // Reads boarding pass barcodes from screenshots and PDFs, on the phone; pure Java, no permissions.
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
