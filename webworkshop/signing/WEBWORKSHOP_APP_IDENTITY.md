# 网页工坊正式版身份记录

> 本文件只保存公开身份信息，不包含私钥、JKS、Base64 密钥库或密码。

- 应用名称：网页工坊
- Android applicationId / package name：`com.greatwy8888.webworkshop`
- 签名密钥别名：`webworkshop-release`
- 证书主题（Subject）：`CN=WebWorkshop Release, OU=Android, O=WebWorkshop, L=Unknown, ST=Unknown, C=US`
- 证书 SHA-256 指纹：`01:1E:3A:6F:AE:59:BF:DF:CF:DD:E6:5D:79:73:A9:96:33:C0:BB:7B:C2:6D:14:7A:31:00:A5:2E:DB:80:40:0B`
- 证书公钥算法：RSA 3072-bit
- 当前构建验证：GitHub Actions Run #75，APK Signature Scheme v2 验证通过

证书文件：`webworkshop-release-cert.pem`（仅公开 X.509 证书，可用于比对签名身份）。

## 升级规则

1. 网页工坊自身必须继续使用包名 `com.greatwy8888.webworkshop` 和同一签名私钥签名。
2. 不能将 JKS 私钥、密码或 Base64 私钥材料提交到 Git 仓库；私钥继续只保存在 GitHub Actions Repository Secrets 中。
3. 任何重新生成的网页工坊 APK，安装前应核对其签名证书 SHA-256 指纹与本文件一致。
4. 网页工坊生成的每个独立应用也必须保留其自身稳定的包名和项目签名备份；不要用网页工坊自身的证书替代项目证书。
