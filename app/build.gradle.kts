// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.luftaquila.tern"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.luftaquila.tern"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // Play upload key, read from Gradle properties (~/.gradle/gradle.properties), never from the
    // project. Without them the release build is unsigned.
    val uploadStoreFile = providers.gradleProperty("ternUploadStoreFile").orNull
    signingConfigs {
        if (uploadStoreFile != null) {
            create("upload") {
                storeFile = file(uploadStoreFile)
                storePassword = providers.gradleProperty("ternUploadStorePassword").get()
                keyAlias = providers.gradleProperty("ternUploadKeyAlias").get()
                keyPassword = providers.gradleProperty("ternUploadKeyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            if (uploadStoreFile != null) signingConfig = signingConfigs.getByName("upload")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // BouncyCastle jars (bcprov/bcpkix/bcutil) ship identical license files.
            pickFirsts += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
            )
        }
    }
}

dependencies {
    implementation(project(":terminal"))
    implementation(project(":ssh"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.billing.ktx)
    // Play Billing brings play-services-base with fragment 1.1; ActivityResult APIs need 1.3 or later.
    implementation(libs.androidx.fragment)
    runtimeOnly(libs.slf4j.nop)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.kotlin.test.junit)
}
