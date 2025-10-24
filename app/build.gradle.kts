plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.safetyapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.safetyapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Optimize native libraries
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    // Split APKs by architecture to reduce size
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true  // Also generate a universal APK
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true  // Enable code shrinking
            isShrinkResources = true  // Remove unused resources
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    // Optimize packaging
    packagingOptions {
        resources {
            excludes += listOf(
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)

    // Basic Android libraries
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)
    implementation ("androidx.activity:activity:1.10.1")
    implementation ("com.google.android.material:material:1.9.0")
    implementation ("com.google.firebase:firebase-database:21.0.0")
    implementation ("com.google.firebase:firebase-storage:21.0.1")
    implementation ("com.google.android.gms:play-services-maps:19.2.0")
    implementation ("com.google.android.gms:play-services-location:21.3.0")
    implementation ("com.google.android.gms:play-services-auth:21.3.0")
    implementation ("jp.wasabeef:blurry:4.0.0")
    implementation ("androidx.browser:browser:1.5.0")

    // TensorFlow Lite (standard version only - removes ~400MB!)
    implementation ("org.tensorflow:tensorflow-lite:2.14.0")

    // ML Kit for Face Detection (FREE)
    implementation ("com.google.mlkit:face-detection:16.1.7")

    // Glide for image loading
    implementation ("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor ("com.github.bumptech.glide:compiler:4.16.0")

    // Image Cropper
    implementation ("com.vanniktech:android-image-cropper:4.5.0")

    // CameraX for modern video recording
    implementation ("androidx.camera:camera-core:1.3.1")
    implementation ("androidx.camera:camera-camera2:1.3.1")
    implementation ("androidx.camera:camera-lifecycle:1.3.1")
    implementation ("androidx.camera:camera-video:1.3.1")
    implementation ("androidx.camera:camera-view:1.3.1")

    // Lifecycle Service for background camera recording
    implementation ("androidx.lifecycle:lifecycle-service:2.6.2")

    // Guava for ListenableFuture (required by CameraX)
    implementation ("com.google.guava:guava:31.1-android")

}