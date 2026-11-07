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
        versionCode = 4
        versionName = "1.1.2"
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
        // Apply the UI fixes to the original offline HTML before it is packaged.
        var html = destination.readText()
        // Remove the legacy rich-text mode toggle by its stable element id.
        html = html.replace(
            Regex("""<button\b(?=[^>]*\bid=["']modeRich["'])[^>]*>.*?</button>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
            ""
        )
        // Center both the first-run setup and later unlock button labels.
        html = html.replace(
            "</style>",
            "#authOverlay #authSubmit{display:flex!important;align-items:center;justify-content:center;text-align:center;}\n</style>"
        )
        // Close nested layers, then lock the editor, then let Android exit.
        val oldBack = "window.handleAndroidBack = function(){ if(previewOverlay.classList.contains('active')){closeImagePreview();return true;} if(document.getElementById('confirmOverlay').classList.contains('active')){hideConfirm();return true;} showConfirm('确定要离开吗？\\n\\n未保存的内容将丢失！',function(){lockApp();}); return true; };"
        val newBack = """window.handleAndroidBack = function(){
            if (previewOverlay.classList.contains('active')) { closeImagePreview(); return true; }
            var confirmLayer = document.getElementById('confirmOverlay');
            if (confirmLayer && confirmLayer.classList.contains('active')) { hideConfirm(); return true; }
            var passwordLayer = document.getElementById('pwdModal');
            if (passwordLayer && getComputedStyle(passwordLayer).display !== 'none') { passwordLayer.style.display = 'none'; return true; }
            var root = document.getElementById('appRoot');
            if (root && !root.classList.contains('hidden')) { lockApp(); return true; }
            return false;
        };"""
        if (html.contains(oldBack)) html = html.replace(oldBack, newBack)
        destination.writeText(html)
    }
}
tasks.named("preBuild").configure { dependsOn(restoreOfflineEditorHtml) }
