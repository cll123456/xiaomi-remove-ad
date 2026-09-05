plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.jingqi.guard"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.jingqi.guard"
        minSdk = 28
        targetSdk = 35
        versionCode = 22
        versionName = "0.9.10"

        // The current website/internal build targets modern Xiaomi devices.
        // Add per-ABI website artifacts before widening public device support.
        ndk { abiFilters += "arm64-v8a" }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        debug {
            buildConfigField("boolean", "EXPERT_PREVIEW", "true")
        }
        release {
            buildConfigField("boolean", "EXPERT_PREVIEW", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("preview") {
            initWith(getByName("release"))
            buildConfigField("boolean", "EXPERT_PREVIEW", "true")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    lint { lintConfig = file("lint.xml") }

    packaging.resources.excludes += setOf(
        "/META-INF/{AL2.0,LGPL2.1}",
        "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    )
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.flyfishxu:kadb:1.2.1") {
        exclude(group = "org.lsposed.hiddenapibypass", module = "hiddenapibypass")
    }
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
