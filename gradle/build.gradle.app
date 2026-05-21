
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
    id 'kotlin-kapt'
}

android {
    namespace 'android.kimyona.jammer'
    compileSdk 35

    defaultConfig {
        applicationId "android.kimyona.jammer"
        minSdk 26
        targetSdk 35
        versionCode 1
        versionName "0.1.0-alpha"
    }

    buildFeatures {
        viewBinding true
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = '17'
    }
}

dependencies {
    // Android Core
    implementation 'androidx.core:core-ktx:1.13.1'
    implementation 'androidx.appcompat:appcompat:1.7.0'
    implementation 'com.google.android.material:material:1.12.0'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'

    // Lifecycle + ViewModel
    implementation 'androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.1'
    implementation 'androidx.lifecycle:lifecycle-livedata-ktx:2.8.1'
    implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.8.1'
    implementation 'androidx.activity:activity-ktx:1.9.0'
    implementation 'androidx.fragment:fragment-ktx:1.7.1'

    // Room (banco local)
    implementation 'androidx.room:room-runtime:2.6.1'
    implementation 'androidx.room:room-ktx:2.6.1'
    kapt 'androidx.room:room-compiler:2.6.1'

    // ExoPlayer (playback)
    implementation 'com.google.android.exoplayer:exoplayer-core:2.19.1'
    implementation 'com.google.android.exoplayer:exoplayer-ui:2.19.1'
    implementation 'com.google.android.exoplayer:extension-mediasession:2.19.1'

    // Media (compat)
    implementation 'androidx.media:media:1.7.0'

    // ViewPager2 (abas)
    implementation 'androidx.viewpager2:viewpager2:1.1.0'

    // Coroutines
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1'

    // JSON parsing (pro Rust)
    implementation 'org.json:json:20231013'
}
