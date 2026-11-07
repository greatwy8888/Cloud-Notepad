# 固定包名与 Release 签名（升级专用）

此项目固定使用：
- Android applicationId / 包名：`com.kingyi.editor`
- 签名别名：`cloudnotepad`
- 签名证书 SHA-256：`E0:C7:8E:9E:E8:45:DE:B0:D6:E2:B3:30:9C:53:4D:3C:63:2F:65:2B:63:6E:BC:80:D9:A1:D7:65:C2:4A:88:16`

## 一次性配置 GitHub Actions Secrets

在仓库 Settings → Secrets and variables → Actions → New repository secret 中添加以下 4 项。值从本次单独提供的 `github-secrets.txt` 文件复制，切勿把该文件或 JKS 密钥提交到仓库：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEY_ALIAS`
- `ANDROID_STORE_PASSWORD`
- `ANDROID_KEY_PASSWORD`

配置完成后，GitHub Actions 会同时构建 Debug APK 和使用固定密钥签名的 Release APK。以后升级必须始终保留包名 `com.kingyi.editor`、同一份 JKS 密钥和相同别名/密码；丢失密钥将无法覆盖安装旧版 Release。

## 重要说明

当前已发给用户的 1.1.0 是 Debug 签名 APK。它与本次新建的 Release 密钥不同，不能直接覆盖安装。首次改用 Release 签名时，Android 可能要求先卸载 Debug 版；卸载前请先备份应用内文件。之后所有 Release 版本都可使用同一签名进行覆盖升级。不要更改 applicationId。
