plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    defaultConfig {
        // Paho loads its logger by name, so R8 must keep it in every app that depends on this module (spike S4).
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(project(":ui"))
    // Only the MQTT bridge uses it, behind MqttLink, so it can be swapped for a smaller client if R8 or API 24 object (spike S4).
    implementation(libs.paho.mqttv5)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(project(":testing"))
}
