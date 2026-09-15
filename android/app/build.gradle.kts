import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.psychron.node"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.psychron.node"
        // Android 10. Old enough for any phone still being used as a sensor, new
        // enough that foreground service types, notification channels and the
        // PKCS#8 key path all exist in the platform without a compatibility layer.
        minSdk = 29
        targetSdk = 36
        versionCode = 6
        versionName = "0.5.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // The one runtime dependency. Paho is a plain Java client: it needs a socket
    // factory and nothing else, which is what lets the TLS context be built here
    // from the project's own CA instead of the platform trust store.
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    testImplementation("junit:junit:4.13.2")
}
