plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hhvpn.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hhvpn.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
}

dependencies {
    implementation(files("libs/libXray.aar"))
    testImplementation("junit:junit:4.13.2")
}
