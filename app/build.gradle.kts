plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.zenscan"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.zenscan"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = true
        disable += setOf("ObsoleteSdkInt", "OldTargetApi")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation("androidx.activity:activity-ktx:1.9.0")
    
    implementation(libs.androidx.recyclerview)

    // ZenScan AI & Image Dependencies
    implementation(libs.mlkit.text)
    implementation(libs.mlkit.face)
    implementation(libs.androidx.exifinterface)
    implementation(libs.play.services.mlkit.document.scanner)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}