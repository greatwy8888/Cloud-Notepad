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
        versionCode = 5
        versionName = "1.1.3"
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
        // Apply UI and UTF-8/GB18030 file decoding fixes to the packaged HTML.
        var html = destination.readText(Charsets.UTF_8)
        // Center first-run setup and unlock labels using the actual element IDs.
        html = html.replace(
            "</style>",
            "#auth #authBtn{display:flex!important;align-items:center;justify-content:center;text-align:center;}\\n</style>"
        )
        // Remove any legacy button whose visible label contains 富文本.
        html = html.replace(
            "</script>",
            "document.querySelectorAll('button').forEach(b=>{if((b.textContent||'').includes('富文本'))b.remove()});\\n</script>"
        )
        // Decode selected local files by BOM/UTF-8 first, then GB18030 (common Chinese Windows encoding).
        html = html.replace(
            "$('open').onclick=",
            """function decodeLocalFile(buffer){
                const bytes=new Uint8Array(buffer);
                if(bytes.length>=3&&bytes[0]===0xEF&&bytes[1]===0xBB&&bytes[2]===0xBF)return new TextDecoder('utf-8').decode(bytes.subarray(3));
                if(bytes.length>=2&&bytes[0]===0xFF&&bytes[1]===0xFE)return new TextDecoder('utf-16le').decode(bytes.subarray(2));
                if(bytes.length>=2&&bytes[0]===0xFE&&bytes[1]===0xFF)return new TextDecoder('utf-16be').decode(bytes.subarray(2));
                try{return new TextDecoder('utf-8',{fatal:true}).decode(bytes)}
                catch(e){try{return new TextDecoder('gb18030').decode(bytes)}catch(e2){return new TextDecoder('utf-8').decode(bytes)}}
            }
            $('open').onclick="""
        )
        html = html.replace("r.onload=()=>{if(f.name", "r.onload=()=>{let fileText=decodeLocalFile(r.result);if(f.name")
        html = html.replace("pre.textContent=r.result;", "pre.textContent=fileText;")
        html = html.replace("$('editor').innerHTML=r.result;", "$('editor').innerHTML=fileText;")
        html = html.replace("r.readAsText(f);", "r.readAsArrayBuffer(f);")
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
