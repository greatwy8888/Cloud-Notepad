# 网页工坊 1.4.6 一次性序列号授权说明

## 当前部署状态

- Cloudflare Worker 已部署：
  `https://cloud-notepad.3985196992.workers.dev`
- D1 数据库：`webworkshop-licenses`
- 管理页面：
  `https://cloud-notepad.3985196992.workers.dev/admin/webworkshop-licenses`
- 激活接口：
  `POST https://cloud-notepad.3985196992.workers.dev/api/webworkshop/activate`
- Android 应用版本：`1.4.6`
- Android 包名：`com.greatwy8888.webworkshop`

管理员页面是独立网页，不嵌入网页工坊 APK。

## 管理员操作

1. 打开上方“管理页面”。
2. 在管理员密钥框中输入 GitHub Actions Secret `WEBWORKSHOP_ADMIN_KEY` 的值。不要把密钥发送给任何人，也不要提交到代码仓库。
3. “适用网页工坊版本标识”填写 `1.4.6`。必须与待激活 APK 的版本标识完全一致。
4. 输入生成数量（1–500），点击“生成序列号”。
5. 生成结果只显示一次。立即复制或导出 TXT 并妥善保存；数据库只保存序列号的 SHA-256 摘要，之后无法找回完整明文。
6. “刷新记录”可查看序列号提示、版本、是否使用及激活设备信息。尚未使用的序列号可以撤销；已使用序列号不能恢复或再次使用。

## 序列号规则

- 每个序列号只能成功激活一次；成功后即永久作废，不能在另一台设备、重装后的应用或清除数据后的应用中再次使用。
- 同一台设备可以先后使用多个不同的新序列号。
- 不同设备之间不能共用同一个序列号。
- 序列号按版本标识发放；例如给 `1.4.6` 生成的序列号不能激活其他版本标识的 APK。

## 用户激活流程

1. 安装或覆盖升级到网页工坊 `1.4.6`。包名保持 `com.greatwy8888.webworkshop`，发布签名保持不变，可在原安装上覆盖升级。
2. 首次打开时出现“网页工坊授权激活”页面。
3. 手机联网，输入管理员生成的序列号，点击“联网激活”。
4. 激活成功后自动进入网页工坊。授权回执使用 Android Keystore 的 AES-GCM 密钥加密，保存在应用私有目录。
5. 后续正常启动可离线使用，不需要每次联网。
6. 如需更换序列号，可在主页的授权状态区域进入重新激活。新序列号激活失败时，原有本机授权仍保留；成功后使用新回执。

## 重要限制

- 清除网页工坊数据或卸载应用会丢失本机授权回执；重新安装后需要另一个未使用的新序列号。
- 当前机制适用于普通用户的一次性序列号管理，不应视为可抵抗 Root、反编译或被篡改 APK 的绝对 DRM。回执在本机加密保存，但客户端并未使用服务器非对称签名回执进行独立验签。
- 不要在公开截图、聊天或代码中暴露管理员密钥。用户只需获得序列号，不需要管理员密钥。

## GitHub Actions

Android 构建工作流：
`.github/workflows/webworkshop-build.yml`

Cloudflare 部署工作流：
`.github/workflows/deploy-webworkshop-license.yml`

Cloudflare 部署所需的 GitHub Actions Secrets：
- `CLOUDFLARE_API_TOKEN`
- `CLOUDFLARE_ACCOUNT_ID`
- `WEBWORKSHOP_ADMIN_KEY`（至少 32 个字符）

Android 发布签名配置应继续使用仓库现有的网页工坊签名 Secrets。不要更换包名或发布签名，否则可能无法覆盖升级现有安装。

## 当前验证情况

- Cloudflare Worker 部署流程已成功。
- Android `1.4.6` 构建已成功，APK 签名与仓库记录的网页工坊发布证书指纹一致。
- 客户端激活页面和服务端接口已接入。
- 尚需使用管理员页面实际生成的一个序列号，在真实 Android 手机上完成首次联网激活测试；在完成这一步前，不把真实设备激活称为已验证。
