# 网页工坊身份与升级签名规范

## 1. 网页工坊自身升级

- 固定 applicationId / namespace：`com.greatwy8888.webworkshop`
- 当前发布版本：versionCode `3`，versionName `1.2.0`
- 所有正式版必须使用同一份 JKS：别名 `webworkshop-release`
- GitHub Actions 从 Repository Secrets 读取签名材料，不得把 JKS、密码或私钥提交到仓库。
- 首次从旧的 CI debug 预览版切换到正式 release 版时，因签名不同需要先卸载旧预览版；此后所有正式版均可覆盖升级。

### 一次性 GitHub Secrets 配置

使用随项目交付的 `webworkshop-signing-credentials.zip`，在 GitHub 仓库 Settings -> Secrets and variables -> Actions 中创建：

- `WEBWORKSHOP_RELEASE_KEYSTORE_BASE64`
- `WEBWORKSHOP_RELEASE_STORE_PASSWORD`
- `WEBWORKSHOP_RELEASE_KEY_ALIAS`（值为 `webworkshop-release`）
- `WEBWORKSHOP_RELEASE_KEY_PASSWORD`

配置完成后，Actions 会生成签名正式版并验证签名；未配置时只生成明确标记为不可升级的 debug 预览版。

## 2. 网页工坊生成的每个应用升级

- 包名由来源项目身份生成，不再包含应用显示名称；更改应用名称不会改变包名。
- URL 项目以 URL 身份识别；本地 HTML / 文件夹以 SAF 来源 URI 识别。再次选择同一来源并更新网页内容时，包名保持不变。
- 每个包名拥有独立 RSA 私钥和证书，私钥以 PKCS#12 保存，再用 AndroidKeyStore AES-GCM 密钥加密后存放于网页工坊私有目录。
- 后续重新生成同一项目 APK 时，使用同一签名身份，因此 Android 可覆盖安装升级。
- 版本 code 以本机计数和当前 epoch 秒数取较大值，避免在新设备恢复签名后版本号倒退。

### 签名备份 / 恢复

在网页工坊中先选择对应 URL 或本地网页来源：
1. 生成过该项目至少一个 APK 后，点“备份当前项目签名”，设置至少 8 位备份密码，导出 PKCS#12 文件。
2. 更换手机、重装网页工坊或清除其数据后，重新选择同一项目来源，点“恢复项目签名备份”，选择对应 PKCS#12 文件并输入备份密码。
3. 恢复成功后，再生成同一项目 APK，即可沿用原签名证书升级已安装应用。

兼容说明：如果项目是在第一版网页工坊中生成、签名保存在旧版 AndroidKeyStore 中，更新后的网页工坊会优先沿用该旧签名，保证原设备上的覆盖升级；AndroidKeyStore 私钥不可导出，因此旧项目无法补做跨设备备份。请勿卸载旧版网页工坊或清除其数据，直到确认旧项目已不再需要。新建项目使用可备份的 PKCS#12 签名。

**每个项目的签名备份都必须长期保留。** 丢失私钥后，Android 不允许用新签名覆盖安装同包名旧应用。备份密码无法找回。

## 3. 安全原则

- 不要把任何 JKS / PKCS#12 私钥、密码或 base64 密钥放入 Git 仓库。
- 网页工坊自身签名只放 GitHub Actions Secrets。
- 生成应用签名只在用户设备本机保存；导出备份时由用户设置密码保护。
- AndroidKeyStore 本机加密密钥与网页工坊应用数据绑定；卸载或清除数据前，必须先导出每个项目的签名备份。
