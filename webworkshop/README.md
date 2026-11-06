# 网页工坊（WebWorkshop）

网页工坊在 Android 手机上本机生成独立 APK：

- **网址模式**：生成的 APK 直接打开原网站，运行时需要网络。
- **本地网页模式**：导入 HTML 文件夹，将 HTML/CSS/JS/图片等本地资源一并封装，生成的 APK 可离线运行。
- 每个来源项目使用独立包名、签名身份和版本 code。
- 修改同一项目网页内容或显示名称后重新生成，沿用原包名与签名证书，可覆盖安装升级。
- 每个项目签名可以通过密码保护的 PKCS#12 文件备份和恢复，供换机、重装网页工坊后继续升级。
- 网页工坊自身固定包名为 `com.greatwy8888.webworkshop`，正式版必须始终使用同一 GitHub Actions release keystore。

## 正式构建与签名

GitHub Actions 通过以下四个 **Repository Secrets** 签署网页工坊自身 APK：

- `WEBWORKSHOP_RELEASE_KEYSTORE_BASE64`
- `WEBWORKSHOP_RELEASE_STORE_PASSWORD`
- `WEBWORKSHOP_RELEASE_KEY_ALIAS`
- `WEBWORKSHOP_RELEASE_KEY_PASSWORD`

请在本仓库的 **Settings → Secrets and variables → Actions → Repository secrets** 中配置。仅当四项都能被新启动的工作流读取时，才会执行 `assembleRelease` 和 APK 签名校验；否则只生成 debug 预览版，不能保证跨构建覆盖升级。

Actions 上传的产物名称为 `webworkshop-apk`。下载后还应核对构建日志中是否出现 **Signed release APK**，不能仅凭任务显示成功就认定为正式签名版。

## 生成应用签名

每个生成项目的 PKCS#12 私钥在本机生成，使用 AndroidKeyStore AES-GCM 加密保存。建议每次生成首个 APK 后立即点“备份当前项目签名”，将加密备份保存在手机以外的安全位置。更换手机或清除网页工坊数据后，重新选择同一个项目来源并恢复对应备份，再生成 APK 即可继续覆盖升级。

**不要丢失签名备份或备份密码。** Android 不允许用另一把签名密钥覆盖安装同包名应用。

## Build

在本目录执行：

```bash
gradle assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`
