# King Yi 编辑器（离线 Android 版）

此分支用于构建完全离线运行的 Android 编辑器。首次启动由用户自行设置本机密码，不在源代码中预置明文密码。密码使用 PBKDF2-SHA256（310,000 次迭代 + 随机盐）派生摘要后保存在应用 WebView 本地存储中；文档也保存在本机，不需要服务器。

在 GitHub Actions 中运行 Build Android APK，即可下载 `KingYiEditor-debug-APK` 工件。

当前版本保留富文本编辑、基础格式、撤销/恢复、图片插入、HTML/TXT 导入导出、内部保存、修改密码和打印/PDF（调用系统打印）。Markdown 编辑模式及 Markdown 转换功能已移除。
