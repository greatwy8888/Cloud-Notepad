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
        versionCode = 3
        versionName = "1.1.1"
    }
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("ANDROID_KEYSTORE_FILE")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_STORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
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
        // Final UI polish: center the unlock action and remove any legacy
        // button explicitly labeled as rich-text/Markdown conversion.
        val html = destination.readText()
        val centered = html.replace(
            ".login button{",
            ".login button{text-align:center;display:flex;align-items:center;justify-content:center;"
        ).replace(
            "window.handleAndroidBack=",
            "document.querySelectorAll('button').forEach(b=>{if(/富文本/.test((b.textContent||'').trim()))b.remove()});\nwindow.handleAndroidBack="
        )
        destination.writeText(centered)
    }
}
tasks.named("preBuild").configure { dependsOn(restoreOfflineEditorHtml) }
