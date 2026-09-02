plugins {
    id("com.android.application")
}

android {
    namespace = "com.pairdrop.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pairdrop.android"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.environmentVariable("VERSION_CODE").orNull?.toIntOrNull() ?: 1
        versionName = providers.environmentVariable("VERSION_NAME").orNull ?: "0.1.0-beta.1"
    }

    signingConfigs {
        val keystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties"
        )
    }
}

dependencies {
    val ktorVersion = "3.4.3"

    implementation("com.google.android.material:material:1.13.0")
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets-jvm:$ktorVersion")
    implementation("org.slf4j:slf4j-nop:2.0.17")
}
