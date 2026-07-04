plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "nl.joozt.kiep.keyboard"
    compileSdk = 37

    defaultConfig {
        applicationId = "nl.joozt.kiep.keyboard"
        minSdk = 24
        targetSdk = 37
        versionCode = 59
        versionName = "Dependencies upgrade"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.preference)
    implementation(libs.play.services.measurement.api)
    implementation(libs.material)
    implementation(libs.app.update)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}
