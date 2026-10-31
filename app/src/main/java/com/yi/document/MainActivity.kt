package com.yi.document

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom

class MainActivity : ComponentActivity() {
    private lateinit var editor: WebView
    private lateinit var status: TextView
    private var currentName = "未命名文档.html"
    private var dirty = false
    private var pendingMedia = "image"
    private var pendingClose = false
    private val account by lazy { getSharedPreferences("yi_document_account", MODE_PRIVATE) }
    private var loggedIn = false

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::loadPickedFile) }
    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { if (it != null) writeDocument(it) else pendingClose = false }
    private val pickMedia = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) insertMedia(uri, pendingMedia) else runJs("cancelPendingInsert()")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showLoginGate()
    }


    private fun showLoginGate() {
        loggedIn = false
        val greenDark = Color.rgb(13, 91, 61)
        val green = Color.rgb(27, 132, 88)
        val greenLight = Color.rgb(224, 245, 233)

        val root = android.widget.FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(10, 83, 59), Color.rgb(24, 139, 91), Color.rgb(91, 178, 119))
            )
        }
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val brand = TextView(this).apply {
            text = "毅"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.argb(45, 255, 255, 255))
                setStroke(dp(1), Color.argb(110, 255, 255, 255))
            }
        }
        column.addView(brand, LinearLayout.LayoutParams(dp(72), dp(72)).apply { bottomMargin = dp(16) })
        column.addView(TextView(this).apply {
            text = "毅的文档"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, dp(42)))
        column.addView(TextView(this).apply {
            text = "让灵感与资料井然有序"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(225, 246, 233))
        }, LinearLayout.LayoutParams(-1, dp(34)).apply { bottomMargin = dp(24) })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(26), dp(24), dp(24))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(22).toFloat()
            }
            elevation = dp(12).toFloat()
        }
        card.addView(TextView(this).apply {
            text = "欢迎回来"
            textSize = 22f
            setTextColor(Color.rgb(35, 57, 47))
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, dp(36)))
        card.addView(TextView(this).apply {
            text = "请输入密码，继续使用文档"
            textSize = 13f
            setTextColor(Color.rgb(126, 145, 134))
        }, LinearLayout.LayoutParams(-1, dp(28)).apply { bottomMargin = dp(14) })

        val pass = field("请输入登录密码", true).apply {
            textSize = 15f
            setTextColor(Color.rgb(41, 62, 51))
            setHintTextColor(Color.rgb(151, 169, 158))
            singleLine = true
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(246, 250, 247))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(220, 233, 224))
            }
            setPadding(dp(15), 0, dp(15), 0)
        }
        card.addView(pass, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(14) })

        val login = Button(this).apply {
            text = "登 录"
            textSize = 16f
            isAllCaps = false
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(green)
                cornerRadius = dp(12).toFloat()
            }
            elevation = dp(3).toFloat()
            setOnClickListener {
                val salt = account.getString("salt", null)
                if (salt == null) showFirstSetup()
                else if (hash(pass.text.toString(), salt) == account.getString("password_hash", "")) enterEditor()
                else {
                    pass.error = "密码错误，请重试"
                    pass.text?.clear()
                }
            }
        }
        card.addView(login, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(8) })
        val forgot = TextView(this).apply {
            text = "忘记密码？通过密保问题找回"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(green)
            setPadding(0, dp(12), 0, dp(8))
            setOnClickListener { showRecoveryDialog() }
        }
        card.addView(forgot, LinearLayout.LayoutParams(-1, dp(42)))
        column.addView(card, LinearLayout.LayoutParams(-1, -2))
        column.addView(TextView(this).apply {
            text = "本地账户 · 文档数据保存在本机"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(222, 244, 229))
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        scroll.addView(column, android.widget.ScrollView.LayoutParams(-1, -1))
        root.addView(scroll, android.widget.FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        if (account.getString("salt", null) == null) {
            root.postDelayed({ if (!isFinishing && !loggedIn) showFirstSetup() }, 250)
        }
    }

    private fun enterEditor() {
        loggedIn=true
        buildUi()
        editor.loadDataWithBaseURL("https://editor.local/",EDITOR_HTML,"text/html","UTF-8",null)
        intent?.data?.let { loadPickedFile(it) }
    }
    private fun hash(value:String,salt:String):String {
        val bytes=MessageDigest.getInstance("SHA-256").digest((salt+value).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
    private fun newSalt():String {
        val bytes=ByteArray(16); SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)
    }
    private fun field(label:String,secret:Boolean=false)=android.widget.EditText(this).apply {
        hint=label
        if(secret) inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    private fun showFirstSetup() {
        if(account.getString("salt",null)!=null) return
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(22,0,22,0) }
        val p1=field("设置登录密码（至少6位）",true)
        val p2=field("再次输入登录密码",true)
        val q=field("设置密保问题，例如：我的第一所学校？")
        val a=field("设置密保答案",true)
        listOf(p1,p2,q,a).forEach { box.addView(it,LinearLayout.LayoutParams(-1,52)) }
        val d=AlertDialog.Builder(this).setTitle("首次使用 · 创建账户")
            .setMessage("请设置登录密码和密保问题。忘记或修改密码时，需要验证密保答案。")
            .setView(box).setCancelable(false).setPositiveButton("保存并登录",null).show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if(p1.text.length<6) { p1.error="密码至少6位"; return@setOnClickListener }
            if(p1.text.toString()!=p2.text.toString()) { p2.error="两次密码不一致"; return@setOnClickListener }
            if(q.text.isBlank() || a.text.isBlank()) { q.error="请填写密保问题和答案"; return@setOnClickListener }
            val salt=newSalt(); val asalt=newSalt()
            account.edit().putString("salt",salt).putString("password_hash",hash(p1.text.toString(),salt))
                .putString("question",q.text.toString().trim()).putString("answer_salt",asalt)
                .putString("answer_hash",hash(a.text.toString().trim().lowercase(),asalt)).apply()
            d.dismiss(); enterEditor()
        }
    }
    private fun showRecoveryDialog() {
        val question=account.getString("question",null)
        if(question==null) { showFirstSetup(); return }
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(22,0,22,0) }
        box.addView(TextView(this).apply { text=question; textSize=16f; setTextColor(Color.rgb(35,61,91)) })
        val answer=field("请输入密保答案",true)
        val p1=field("设置新密码（至少6位）",true)
        val p2=field("再次输入新密码",true)
        listOf(answer,p1,p2).forEach { box.addView(it,LinearLayout.LayoutParams(-1,52)) }
        val d=AlertDialog.Builder(this).setTitle("密保验证").setView(box).setNegativeButton("取消",null).setPositiveButton("验证并重设",null).show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val asalt=account.getString("answer_salt","")!!
            if(hash(answer.text.toString().trim().lowercase(),asalt)!=account.getString("answer_hash","")) { answer.error="密保答案不正确"; return@setOnClickListener }
            if(p1.text.length<6 || p1.text.toString()!=p2.text.toString()) { p2.error="新密码须至少6位且两次一致"; return@setOnClickListener }
            val salt=newSalt()
            account.edit().putString("salt",salt).putString("password_hash",hash(p1.text.toString(),salt)).apply()
            d.dismiss(); Toast.makeText(this,"密码已重设，请重新登录",Toast.LENGTH_LONG).show()
        }
    }
    private fun showAccountMenu() {
        AlertDialog.Builder(this).setTitle("账户").setItems(arrayOf("修改密码（需验证密保）","退出登录")) { _,which ->
            if(which==0) showRecoveryDialog()
            else AlertDialog.Builder(this).setTitle("退出登录").setMessage("确定退出当前账户吗？未保存的修改可能丢失。")
                .setNegativeButton("取消",null).setPositiveButton("退出") { _,_ ->
                    loggedIn=false; clearDocument(); showLoginGate()
                }.show()
        }.show()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(245,248,252)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(12,7,12,7); setBackgroundColor(Color.WHITE) }
        val title = TextView(this).apply { text = "毅的文档"; textSize = 20f; setTextColor(Color.rgb(35,61,91)); setTypeface(null, android.graphics.Typeface.BOLD) }
        header.addView(title, LinearLayout.LayoutParams(0,48,1f))
        header.addView(actionButton("打开") { openFile.launch(arrayOf("*/*")) })
        header.addView(actionButton("保存") { askSave() })
        header.addView(actionButton("关闭") { closeDocument() })
        header.addView(actionButton("账户") { showAccountMenu() })
        root.addView(header)

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(7,5,7,5); setBackgroundColor(Color.rgb(235,243,252)) }
        listOf("撤销" to "undo()", "重做" to "redo()", "粗体" to "bold()", "斜体" to "italic()", "下划线" to "underline()").forEach { (label, js) -> tools.addView(actionButton(label) { runJs(js) }) }
        val sizes = intArrayOf(3,3,4,5,6,7,7)
        tools.addView(dropdown(listOf("字号","小号","四号","三号","二号","大号","特大")) { i -> runJs("setFontSize(" + sizes[i] + ")") })
        val colors = listOf("", "#25364b", "#234c78", "#2878d0", "#d64545", "#23865b", "#8056b3", "#d9822b")
        tools.addView(dropdown(listOf("文字颜色","黑色","深蓝","蓝色","红色","绿色","紫色","橙色")) { i -> runJs("setColor('" + colors[i] + "')") })
        tools.addView(actionButton("图片") { runJs("prepareInsert('image')"); pendingMedia="image"; pickMedia.launch("image/*") })
        tools.addView(actionButton("视频") { runJs("prepareInsert('video')"); pendingMedia="video"; pickMedia.launch("video/*") })
        tools.addView(dropdown(listOf("图片比例","20%","50%","80%","100%","自定义")) { i ->
            when(i) { 1->runJs("setMediaScale(20)"); 2->runJs("setMediaScale(50)"); 3->runJs("setMediaScale(80)"); 4->runJs("setMediaScale(100)"); 5->customScale() }
        })
        root.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(tools) }, LinearLayout.LayoutParams(-1,56))
        editor = WebView(this).apply {
            setBackgroundColor(Color.rgb(245,248,252)); settings.javaScriptEnabled=true; settings.domStorageEnabled=true
            settings.allowFileAccess=false; settings.mediaPlaybackRequiresUserGesture=true
            webChromeClient=WebChromeClient(); webViewClient=WebViewClient(); addJavascriptInterface(EditorBridge(),"Native")
        }
        root.addView(editor, LinearLayout.LayoutParams(-1,0,1f))
        status = TextView(this).apply { text="就绪 · 支持文字、网页、代码、图片与视频"; textSize=12f; setTextColor(Color.rgb(100,119,143)); setPadding(16,10,16,10); setBackgroundColor(Color.WHITE) }
        root.addView(status); setContentView(root)
    }

    private fun dropdown(items: List<String>, selected: (Int)->Unit): Spinner {
        val s=Spinner(this,Spinner.MODE_DROPDOWN)
        s.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,items)
        s.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:android.view.View?,position:Int,id:Long) { if(position>0) selected(position) }
        }
        return s
    }
    private fun customScale() {
        val input=android.widget.EditText(this).apply { inputType=android.text.InputType.TYPE_CLASS_NUMBER; hint="输入 1–100 的百分比" }
        AlertDialog.Builder(this).setTitle("自定义图片比例").setView(input).setPositiveButton("应用") { _,_ ->
            val n=input.text.toString().toIntOrNull()
            if(n!=null && n in 1..100) runJs("setMediaScale($n)") else Toast.makeText(this,"请输入 1–100 的整数",Toast.LENGTH_SHORT).show()
        }.setNegativeButton("取消",null).show()
    }
    private fun dp(value:Int)= (value*resources.displayMetrics.density).toInt()
    private fun actionButton(label:String,action:()->Unit)=Button(this).apply {
        text=label; textSize=13f; isAllCaps=false
        typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)
        setTextColor(Color.rgb(42,103,173))
        background=android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadius=dp(12).toFloat()
            setStroke(dp(1),Color.rgb(218,229,242))
        }
        elevation=dp(2).toFloat()
        minHeight=dp(42); minimumHeight=dp(42)
        setPadding(dp(12),0,dp(12),0)
        stateListAnimator=null
        setOnClickListener { action() }
    }
    private fun runJs(code:String) { editor.evaluateJavascript("Editor.$code",null) }

    private fun loadPickedFile(uri:Uri) {
        val name=queryName(uri); currentName=name
        val ext=name.substringAfterLast('.', "").lowercase()
        if(ext in listOf("pdf","doc","docx","xls","xlsx","ppt","pptx","odt","ods","odp")) {
            try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,contentResolver.getType(uri) ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); status.text="$name · 已交由兼容应用预览" }
            catch(_:Exception) { Toast.makeText(this,"未找到可打开此文件的应用",Toast.LENGTH_LONG).show() }; return
        }
        try {
            val raw=contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val b64=Base64.encodeToString(raw.toByteArray(Charsets.UTF_8),Base64.NO_WRAP)
            val mode=if(ext in listOf("html","htm","xhtml")) "html" else "code"
            editor.evaluateJavascript("Editor.loadText('$b64','$mode')",null); dirty=false; status.text="$name · 已打开"
        } catch(_:Exception) { Toast.makeText(this,"文件读取失败或编码不受支持",Toast.LENGTH_LONG).show() }
    }
    private fun queryName(uri:Uri):String {
        var name=uri.lastPathSegment ?: "document.txt"
        try { contentResolver.query(uri,null,null,null,null)?.use { c -> if(c.moveToFirst()) { val i=c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if(i>=0) name=c.getString(i) ?: name } } } catch(_:Exception) {}
        return name
    }
    private fun askSave() {
        AlertDialog.Builder(this).setTitle("保存文档").setMessage("是否保存当前文档？")
            .setPositiveButton("保存") { _,_ -> saveFile.launch(currentName.substringAfterLast('/').ifBlank { "文档.html" }) }
            .setNegativeButton("不保存") { _,_ -> dirty=false; status.text="未保存的修改已放弃" }.setNeutralButton("取消",null).show()
    }
    private fun closeDocument() {
        if(!dirty) { clearDocument(); return }
        AlertDialog.Builder(this).setTitle("关闭文档").setMessage("当前文档有未保存的修改，是否保存后关闭？")
            .setPositiveButton("保存并关闭") { _,_ -> pendingClose=true; saveFile.launch(currentName.substringAfterLast('/').ifBlank { "文档.html" }) }
            .setNegativeButton("不保存") { _,_ -> dirty=false; clearDocument() }.setNeutralButton("取消",null).show()
    }
    private fun clearDocument() { editor.evaluateJavascript("Editor.loadText('','html')",null); currentName="未命名文档.html"; dirty=false; status.text="文档已关闭 · 可以打开新文件" }
    private fun writeDocument(uri:Uri) {
        editor.evaluateJavascript("Editor.exportHtml()") { result ->
            try {
                val html=String(Base64.decode(result?.trim('"') ?: "",Base64.DEFAULT),Charsets.UTF_8)
                contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(html) }
                dirty=false; currentName=queryName(uri); status.text="$currentName · 已保存"; Toast.makeText(this,"保存成功",Toast.LENGTH_SHORT).show()
                if(pendingClose) { pendingClose=false; clearDocument() }
            } catch(_:Exception) { Toast.makeText(this,"保存失败",Toast.LENGTH_LONG).show() }
        }
    }
    private fun insertMedia(uri:Uri,type:String) {
        try {
            val bytes=contentResolver.openInputStream(uri)?.use { input ->
                val out=ByteArrayOutputStream(); val buf=ByteArray(16384); var total=0
                while(true) { val n=input.read(buf); if(n<0) break; total+=n; if(total>200*1024*1024) throw IllegalArgumentException("媒体文件不能超过200MB"); out.write(buf,0,n) }
                out.toByteArray()
            } ?: return
            val mime=contentResolver.getType(uri) ?: if(type=="image") "image/jpeg" else "video/mp4"
            val b64=Base64.encodeToString(bytes,Base64.NO_WRAP)
            editor.evaluateJavascript("Editor.insertMedia('data:$mime;base64,$b64','$type')",null); dirty=true
        } catch(e:Exception) { Toast.makeText(this,e.message ?: "媒体插入失败",Toast.LENGTH_LONG).show() }
    }
    inner class EditorBridge {
        @JavascriptInterface fun changed() { runOnUiThread { dirty=true; status.text="$currentName · 有未保存修改" } }
        @JavascriptInterface fun chooseImage() { runOnUiThread { pendingMedia="image"; pickMedia.launch("image/*") } }
        @JavascriptInterface fun chooseVideo() { runOnUiThread { pendingMedia="video"; pickMedia.launch("video/*") } }
        @JavascriptInterface fun setStatus(message:String) { runOnUiThread { status.text=message } }
    }
    override fun onBackPressed() { if(dirty) askSave() else super.onBackPressed() }

    companion object {
        private val EDITOR_HTML = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<style>
*{box-sizing:border-box}body{margin:0;background:#f5f8fc;color:#25364b;font-family:Arial,sans-serif}
#paper{background:white;max-width:980px;min-height:calc(100vh - 28px);margin:14px auto;padding:28px 24px;box-shadow:0 4px 22px #dce6f2;border-radius:14px;outline:none;line-height:1.7;overflow-wrap:anywhere}
#paper:empty:before{content:'在这里开始写作……';color:#a9b7c8}
.media{display:block;position:relative;width:100%;max-width:100%;margin:14px auto;padding:0;border:1px dashed #9bbde5;border-radius:10px;overflow:hidden;touch-action:none;user-select:none;background:#f4f7fb}
.media img,.media video{display:block;width:100%;height:auto;max-width:100%;object-fit:contain}
.media video{background:#101820;min-height:90px}
.media.selected{outline:2px solid #3987d8}
.media-hint{font:12px Arial;color:#6d829b;padding:4px 8px;background:#f4f7fb}
pre{white-space:pre-wrap;overflow-wrap:anywhere;font-family:monospace;background:#f5f7fa;padding:14px;border-radius:8px}
@media(min-width:700px){#paper{margin:24px auto;padding:44px 56px;min-height:calc(100vh - 48px)}}
</style></head><body><div id="paper" contenteditable="true" spellcheck="false"></div>
<script>
const paper=document.getElementById('paper');let history=[],pos=-1,lock=false,pendingMarker=null,selectedMedia=null,drag=null;
function snap(){if(lock)return;let v=paper.innerHTML;if(history[pos]===v)return;history=history.slice(0,pos+1);history.push(v);if(history.length>20)history.shift();pos=history.length-1;Native.changed();}
function restore(i){if(i<0||i>=history.length)return;lock=true;pos=i;paper.innerHTML=history[pos];lock=false;}
function saveCaret(){const s=getSelection();if(!s||!s.rangeCount)return;const r=s.getRangeAt(0);if(!paper.contains(r.startContainer))return;const m=document.createElement('span');m.id='media-caret-marker';m.textContent='\u200b';r.insertNode(m);pendingMarker=m;}
paper.addEventListener('input',()=>{clearTimeout(window.st);window.st=setTimeout(snap,180)});
paper.addEventListener('pointerdown',e=>{const box=e.target.closest('.media');if(!box)return;selectedMedia=box;document.querySelectorAll('.media').forEach(x=>x.classList.toggle('selected',x===box));if(e.target.tagName==='VIDEO')return;e.preventDefault();drag={box,x:e.clientX,y:e.clientY,dx:Number(box.dataset.dx||0),dy:Number(box.dataset.dy||0)};box.setPointerCapture(e.pointerId)});
paper.addEventListener('pointermove',e=>{if(!drag)return;const d=drag;d.box.style.transform='translate('+(d.dx+e.clientX-d.x)+'px,'+(d.dy+e.clientY-d.y)+'px)';d.box.dataset.dx=d.dx+e.clientX-d.x;d.box.dataset.dy=d.dy+e.clientY-d.y});
paper.addEventListener('pointerup',()=>{if(drag){drag=null;snap()}});paper.addEventListener('pointercancel',()=>drag=null);
paper.addEventListener('click',e=>{const m=e.target.closest('.media');if(m){selectedMedia=m;document.querySelectorAll('.media').forEach(x=>x.classList.toggle('selected',x===m))}});
paper.addEventListener('keydown',e=>{if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='z'){e.preventDefault();e.shiftKey?Editor.redo():Editor.undo()}});
const Editor={
undo(){restore(pos-1)},redo(){restore(pos+1)},bold(){document.execCommand('bold')},italic(){document.execCommand('italic')},underline(){document.execCommand('underline')},
setFontSize(n){document.execCommand('fontSize',false,n)},setColor(c){document.execCommand('foreColor',false,c)},
prepareInsert(type){saveCaret();Native.setStatus(type==='image'?'请选择要插入的图片':'请选择要插入的视频')},cancelPendingInsert(){if(pendingMarker){pendingMarker.remove();pendingMarker=null}},
image(){this.prepareInsert('image');Native.chooseImage()},video(){this.prepareInsert('video');Native.chooseVideo()},
insertMedia(data,type){const wrap=document.createElement('div');wrap.className='media';wrap.contentEditable='false';wrap.dataset.dx='0';wrap.dataset.dy='0';const el=document.createElement(type==='image'?'img':'video');el.src=data;if(type==='video'){el.controls=true;el.playsInline=true;el.preload='metadata';el.setAttribute('webkit-playsinline','true')}wrap.appendChild(el);const hint=document.createElement('div');hint.className='media-hint';hint.textContent=type==='image'?'图片 · 按住拖动 · 使用比例菜单调整大小':'视频 · 点击播放 · 按住拖动';wrap.appendChild(hint);if(pendingMarker&&pendingMarker.isConnected){pendingMarker.replaceWith(wrap);pendingMarker=null}else{paper.appendChild(wrap)}const spacer=document.createElement('p');spacer.innerHTML='<br>';wrap.after(spacer);snap()},
setMediaScale(percent){const target=selectedMedia||paper.querySelector('.media:last-of-type');if(!target){Native.setStatus('请先点选图片或视频');return}target.style.width=percent+'%';target.style.maxWidth='100%';const media=target.querySelector('img,video');if(media){media.style.width='100%';media.style.height='auto';media.style.objectFit='contain'}snap()},
loadText(b64,mode){let t=decodeURIComponent(escape(atob(b64)));paper.innerHTML='';if(mode==='html')paper.innerHTML=t;else{let p=document.createElement('pre');p.contentEditable='true';p.textContent=t;paper.appendChild(p)}history=[];pos=-1;snap()},
exportHtml(){let d='<!doctype html><meta charset="utf-8"><title>毅的文档</title><style>body{font-family:Arial;line-height:1.7}.media{display:block;max-width:100%;overflow:hidden}.media img,.media video{width:100%;height:auto;object-fit:contain}</style>'+paper.innerHTML;return btoa(unescape(encodeURIComponent(d)))}
};window.Editor=Editor;snap();
</script></body></html>
        """.trimIndent()
    }
}
