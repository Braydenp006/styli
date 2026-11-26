plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.protobuf")
}

android {
    namespace = "app.styli.ink"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.styli.ink"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    sourceSets {
        // The ink format's schema and sample file are shared with the Go core.
        (getByName("main") as ExtensionAware).extensions.getByName<SourceDirectorySet>("proto").srcDir("../../proto")
        getByName("test").resources.srcDir("../../proto/testdata")
    }
}

val protobufVersion = "4.31.1"
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protobufVersion" }
    generateProtoTasks {
        all().forEach { it.builtins { create("java") { option("lite") } } }
    }
}

dependencies {
    // androidx.ink is still alpha: its API can change between releases, so the version is pinned.
    val ink = "1.1.0-alpha12"
    implementation("androidx.ink:ink-authoring:$ink")
    implementation("androidx.ink:ink-brush:$ink")
    implementation("androidx.ink:ink-rendering:$ink")
    implementation("androidx.ink:ink-strokes:$ink")
    // Predicts where the pen is heading, which hides some latency.
    implementation("androidx.input:input-motionprediction:1.0.1")

    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("com.google.protobuf:protobuf-javalite:$protobufVersion")
    testImplementation("junit:junit:4.13.2")
}
