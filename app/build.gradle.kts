import java.util.Properties

@Suppress("DSL_SCOPE_VIOLATION") // Suppress false positive for libs
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services)
    id("kotlin-parcelize")
}

// Load Stripe key from local.properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

val stripeKey: String = localProperties["STRIPE_PUBLISHABLE_KEY"]?.toString()
    ?: throw GradleException("Missing STRIPE_PUBLISHABLE_KEY in local.properties")

android {
    namespace = "com.gari.yahdsell2"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gari.yahdsell2"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "STRIPE_PUBLISHABLE_KEY", "\"$stripeKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core & AndroidX
    implementation(libs.androidxCoreKtx)
    implementation(libs.androidxLifecycleRuntimeKtx)
    implementation(libs.androidxActivityCompose)
    implementation(libs.androidxNavigationCompose)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Compose (using BOM)
    implementation(platform(libs.androidxComposeBom))
    implementation(libs.composeUi)
    implementation(libs.composeUiGraphics)
    implementation(libs.composeUiToolingPreview)
    implementation(libs.composeMaterial3)
    implementation(libs.composeMaterialIconsExtended)
    androidTestImplementation(platform(libs.androidxComposeBom))

    // Firebase (using BOM)
    implementation(platform(libs.firebaseBom))
    implementation(libs.firebaseAuth)
    implementation(libs.firebaseFirestore)
    implementation(libs.firebaseStorage)
    implementation(libs.firebaseFunctions)
    implementation(libs.firebaseAppcheck)
    implementation(libs.firebaseMessaging)

    // Hilt
    implementation(libs.hiltAndroid)
    ksp(libs.hiltCompiler)
    implementation(libs.hiltNavigationCompose)

    // Google Services (Maps & Location)
    implementation(libs.googlePlayServicesLocation)
    implementation(libs.googlePlayServicesMaps)
    implementation(libs.googleMapsCompose)
    implementation(libs.googleMapsComposeUtils)

    // Stripe
    implementation(libs.stripeAndroid)

    // Coroutines
    implementation(libs.kotlinxCoroutinesAndroid)
    implementation(libs.kotlinxCoroutinesPlayServices)

    // Media & UI
    implementation(libs.coilCompose)
    implementation(libs.media3Ui)
    implementation(libs.media3Exoplayer)
    implementation("androidx.media3:media3-session:1.3.1") // For video player lifecycle
    implementation(libs.accompanistSwiperefresh)
    implementation(libs.gson)


    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidxTestExtJunit)
    androidTestImplementation(libs.androidxEspressoCore)
    androidTestImplementation(libs.androidxUiTestJunit4)
    debugImplementation(libs.composeUiTooling)
    debugImplementation(libs.androidxUiTestManifest)
}

// Set JVM target for Kotlin compilation
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

