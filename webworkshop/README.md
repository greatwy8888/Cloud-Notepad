# 网页工坊（WebWorkshop）

一个简洁的 Android 网页应用制作工具，规划为两种模式：

1. **网址模式**：输入网址，生成安装后直接打开该网站的独立 APK。网站内容由远程网站提供，因此访问时需要网络。
2. **本地 HTML 模式**：选择本地 HTML 页面，连同其本地资源一起封装进 APK，安装后离线运行。

## 当前阶段

当前提交是 Android 工程骨架和界面/网页预览原型，**尚未实现 APK 二次构建、APK 签名密钥持久化、图标/启动画面自定义表单和完整的 HTML 项目资源导入**。不会把预览按钮误称为 APK 生成。

## 重要的离线构建说明

要在 Android 手机上完全离线生成其他 APK，需要将 Android 构建工具链、平台组件和依赖以可维护的方式集成到应用内，工作量和存储占用都明显高于普通 WebView 应用。先验证基础工程，再逐步实现本地打包器。GitHub Actions 仅用于构建“网页工坊”自身的测试 APK，不参与用户日常生成。

## Build

在本目录执行：

```bash
gradle assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`
