plugins {
    // https://github.com/NordicSemiconductor/Android-Gradle-Plugins/blob/main/plugins/src/main/kotlin/AndroidLibraryConventionPlugin.kt
    alias(libs.plugins.nordic.library)
    // https://github.com/NordicSemiconductor/Android-Gradle-Plugins/blob/main/plugins/src/main/kotlin/AndroidKotlinConventionPlugin.kt
    alias(libs.plugins.nordic.kotlin.android)
    id("com.google.protobuf") version "0.9.4"
}

android {
    namespace = "no.nordicsemi.android.blinky.transport_ble"
}

dependencies {
    implementation(project(":blinky:spec"))

    // Import BLE Library
    implementation(libs.nordic.ble.ktx)
    // BLE events are logged using Timber
    implementation(libs.timber)
    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // gRPC / Protobuf (lite)
    implementation("io.grpc:grpc-okhttp:1.62.2")
    implementation("io.grpc:grpc-protobuf-lite:1.62.2")
    implementation("io.grpc:grpc-stub:1.62.2")
    implementation("com.google.protobuf:protobuf-javalite:3.25.3")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.62.2"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
            task.plugins {
                create("grpc") {
                    option("lite")
                }
            }
        }
    }
}
