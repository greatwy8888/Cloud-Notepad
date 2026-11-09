import java.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
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
        versionCode = 13
        versionName = "1.2.1"
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
        val rawHtml = GZIPInputStream(compressed.inputStream()).use { it.readBytes() }
        destination.parentFile.mkdirs()

        // Decode legacy Chinese source safely before writing UTF-8.
        fun decodeHtml(bytes: ByteArray): String {
            val candidates = listOf("UTF-8", "Big5", "GB18030")
            for (charsetName in candidates) {
                try {
                    val decoder = Charset.forName(charsetName).newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                    return decoder.decode(ByteBuffer.wrap(bytes)).toString()
                } catch (_: Exception) { }
            }
            return String(bytes, Charsets.UTF_8)
        }
        var html = decodeHtml(rawHtml)

        // Keep Android Back responsive on the password screen and mark login state.
        html = html.replace(
            "function lockApp(){",
            "function lockApp(){Android.setEditorUnlocked(false);"
        )
        html = html.replace(
            "document.getElementById('authOverlay').classList.add('hidden');document.getElementById('appRoot').classList.remove('hidden');",
            "Android.setEditorUnlocked(true);document.getElementById('authOverlay').classList.add('hidden');document.getElementById('appRoot').classList.remove('hidden');"
        )

        // Keep existing passwords valid, then migrate them to a faster PBKDF2 work factor.
        html = html.replace("async function hash(p,s){", "async function hash(p,s,it=120000){")
        html = html.replace("iterations:310000,hash:'SHA-256'", "iterations:it,hash:'SHA-256'")
        html = html.replace(
            "async function valid(p){let s=localStorage.getItem(SK),h=localStorage.getItem(HK);return !!s&&!!h&&(await hash(p,un64(s)))===h}",
            "async function valid(p){let s=localStorage.getItem(SK),h=localStorage.getItem(HK),it=Number(localStorage.getItem('ky_iter_v1')||310000);if(!s||!h)return false;let salt=un64(s);if((await hash(p,salt,it))!==h)return false;if(it!==120000){localStorage.setItem(HK,await hash(p,salt,120000));localStorage.setItem('ky_iter_v1','120000')}return true}"
        )
        html = html.replace(
            "localStorage.setItem(SK,b64(s));localStorage.setItem(HK,await hash(p,s))",
            "localStorage.setItem(SK,b64(s));localStorage.setItem(HK,await hash(p,s));localStorage.setItem('ky_iter_v1','120000')"
        )
        html = html.replace(
            "localStorage.setItem(SK,b64(s));localStorage.setItem(HK,await hash(b,s))",
            "localStorage.setItem(SK,b64(s));localStorage.setItem(HK,await hash(b,s));localStorage.setItem('ky_iter_v1','120000')"
        )

        // Receive TXT files opened from Android after the password screen has been passed.
        val receiveTxt = """
window.receiveExternalTxt=()=>{if(!$('auth').classList.contains('hidden'))return;try{if(!window.AndroidBridge)return;let item=JSON.parse(AndroidBridge.takePendingFile());if(!item.success){if(item.message)say(item.message);return}let pre=document.createElement('pre');pre.textContent=item.content;$('editor').replaceChildren(pre);$('fname').value=item.filename.replace(/\.[^.]*$/,'');$('fmt').value='txt';snap();say('已打开：'+item.filename)}catch(e){say('打开 TXT 文件失败')}};
""".trimIndent()
        html = html.replace("async function auth(){", receiveTxt + "\nasync function auth(){")
        html = html.replace(
            "$('auth').classList.add('hidden');$('bar').classList.remove('hidden');",
            "if(window.AndroidBridge)AndroidBridge.setEditorUnlocked(true);$('auth').classList.add('hidden');$('bar').classList.remove('hidden');"
        )
        html = html.replace(
            "$('editor').classList.remove('hidden');snap()}",
            "$('editor').classList.remove('hidden');snap();window.receiveExternalTxt()}"
        )
        html = html.replace(
            "$('lock').onclick=()=>{location.reload()};",
            "$('lock').onclick=()=>{if(window.AndroidBridge)AndroidBridge.setEditorUnlocked(false);$('pw').value='';$('pw2').value='';$('err').textContent='';$('authBtn').textContent='解锁';$('tip').textContent='请输入访问密码';$('pw2').classList.add('hidden');$('auth').classList.remove('hidden');$('bar').classList.add('hidden');$('editor').classList.add('hidden');$('pw').focus()};"
        )
        html = html.replace(
            "$('auth').classList.remove('hidden');$('bar').classList.add('hidden');$('editor').classList.add('hidden');return true};",
            "if(window.AndroidBridge)AndroidBridge.setEditorUnlocked(false);$('auth').classList.remove('hidden');$('bar').classList.add('hidden');$('editor').classList.add('hidden');return true};"
        )
        html = html.replace("""id="lock">锁定</button>""", """id="lock">退出登录</button>""")
        html = html.replace(
            "@media print {",
            "#authOverlay button,#authSubmit,#authBtn,#unlockBtn{display:flex!important;align-items:center!important;justify-content:center!important;text-align:center!important;line-height:1.2!important;box-sizing:border-box!important;padding-top:12px!important;padding-bottom:12px!important;margin-left:auto!important;margin-right:auto!important;}\\n@media print {"
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
