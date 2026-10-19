plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

dependencies {
    api(project(":ui"))
    // Only the MQTT bridge uses it, behind MqttLink, so it can be swapped for a smaller client if R8 or API 24 object (spike S4).
    implementation(libs.paho.mqttv5)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
