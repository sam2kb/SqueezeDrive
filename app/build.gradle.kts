/*
 * This file is part of SqueezeDrive, an Android Automotive OS client for the Lyrion music server.
 * Copyright (c) 2026 SqueezeDrive contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Portions of this project are adapted from Squeeze Client,
 * Copyright (c) 2024 Danny Baumann, under the same license. See NOTICE.
 */

import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")
}

val privateBuildProperties = Properties().apply {
    System.getenv("SQUEEZEDRIVE_PRIVATE_PROPERTIES")?.let { path ->
        file(path).inputStream().use { load(it) }
    }
}
val defaultServerUrl = privateBuildProperties.getProperty("defaultServerUrl", "").trim()
require(defaultServerUrl.none { it == '\n' || it == '\r' }) { "Invalid default server URL" }
val defaultServerLiteral = "\"" + defaultServerUrl.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.wittyfinch.squeezedrive"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wittyfinch.squeezedrive"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "0.1.3"

        buildConfigField("String", "DEFAULT_SERVER_URL", defaultServerLiteral)

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Optional local signing config; credentials and keystores stay outside the repository.
    val signingProperties = System.getenv("SQUEEZEDRIVE_SIGNING_PROPERTIES")?.let { path ->
        Properties().apply { file(path).inputStream().use { load(it) } }
    }
    val externalReleaseSigning = signingProperties?.let { properties ->
        signingConfigs.create("externalRelease") {
            fun required(key: String): String = requireNotNull(properties.getProperty(key)) {
                "Missing release signing property: $key"
            }
            storeFile = file(required("storeFile"))
            storePassword = required("storePassword")
            keyAlias = required("keyAlias")
            keyPassword = required("keyPassword")
        }
    }

    buildTypes {
        release {
            // Without an external configuration, release artifacts remain unsigned.
            signingConfig = externalReleaseSigning
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    coreLibraryDesugaring(libs.android.desugar.jdk.libs)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.coil.android)
    implementation(libs.coil.okhttp)
    implementation(libs.google.material)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
}
