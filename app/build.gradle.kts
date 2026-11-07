import java.util.Base64
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
        versionCode = 7
        versionName = "1.1.5"
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
            destination.parentFile.mkdirs()
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        var html = destination.readText(Charsets.UTF_8)
        html = html.replace(
            "</style>",
            "#auth #authBtn{display:flex!important;align-items:center;justify-content:center;text-align:center;}\n</style>"
        )

        // Remove the obsolete rich-text mode control and Markdown conversion controls.
        html = html.replace(Regex("""<button[^>]*>[^<]*(?:富文本|Markdown|MD转换|MD 转换)[^<]*</button>""", RegexOption.IGNORE_CASE), "")
        html = html.replace(Regex("""<button[^>]*(?:富文本|Markdown|mdConvert|md-convert)[^>]*>.*?</button>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")

        // Decode local files from their bytes rather than assuming UTF-8.
        val decoder = """
function decodeLocalFile(buffer){
  var bytes=new Uint8Array(buffer);
  if(bytes.length>=3&&bytes[0]===239&&bytes[1]===187&&bytes[2]===191) return new TextDecoder('utf-8').decode(bytes.subarray(3));
  if(bytes.length>=2&&bytes[0]===255&&bytes[1]===254) return new TextDecoder('utf-16le').decode(bytes.subarray(2));
  if(bytes.length>=2&&bytes[0]===254&&bytes[1]===255) return new TextDecoder('utf-16be').decode(bytes.subarray(2));
  try { return new TextDecoder('utf-8',{fatal:true}).decode(bytes); }
  catch(e) { try { return new TextDecoder('gb18030').decode(bytes); } catch(e2) { return new TextDecoder('utf-8').decode(bytes); } }
}
""".trimIndent()
        if (!html.contains("function decodeLocalFile(buffer)")) {
            html = html.replaceFirst("function openLocalFile", decoder + "\nfunction openLocalFile")
        }
        html = html.replace("txt=String(ev.target.result||'')", "txt=decodeLocalFile(ev.target.result)")
        html = html.replace("reader.readAsText(file,'UTF-8')", "reader.readAsArrayBuffer(file)")
        html = html.replace("reader.readAsText(file)", "reader.readAsArrayBuffer(file)")

        // Back should show a readable Chinese confirmation, then close the editor screen.
        html = html.replace(
            "showConfirm('确定要离开吗？\\n\\n未保存的内容将丢失！',function(){lockApp();});",
            "showConfirm('确定要退出记事本吗？\\n\\n未保存的内容将丢失！',function(){if(window.Android&&Android.exitApp){Android.exitApp();}else{lockApp();}});"
        )
        destination.writeText(html, Charsets.UTF_8)

        val encodedIcon = layout.projectDirectory.file("src/main/icon/ic_launcher.png.b64").asFile
        val iconFile = layout.projectDirectory.file("src/main/res/mipmap-nodpi/ic_launcher.png").asFile
        iconFile.parentFile.mkdirs()
        iconFile.writeBytes(Base64.getMimeDecoder().decode(encodedIcon.readText(Charsets.US_ASCII)))
    }
}
tasks.named("preBuild").configure { dependsOn(restoreOfflineEditorHtml) }
