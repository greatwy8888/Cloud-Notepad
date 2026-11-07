import java.util.zip.GZIPInputStream
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.kingyi.editor"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.kingyi.editor"
        minSdk = 23
        targetSdk = 28
        versionCode = 2
        versionName = "1.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
val restoreOfflineEditorHtml by tasks.registering {
    doLast {
        val compressed = layout.projectDirectory.file("src/main/offline/index.html.gz").asFile
        val destination = layout.projectDirectory.file("src/main/assets/index.html").asFile
        GZIPInputStream(compressed.inputStream()).use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(restoreOfflineEditorHtml) }
