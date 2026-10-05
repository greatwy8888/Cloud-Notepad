# Cloud Notepad V7.1 部署说明

## 推荐方式：Cloudflare 一键部署

这个版本已经修正了之前的 `cloud-notepad-v6-files not found` 问题。

`wrangler.jsonc` 不再绑定一个必须事先存在的 V6 R2 Bucket，也不再写入假的 D1 `database_id`。Cloudflare 部署时会根据绑定自动创建所需资源。

官方部署入口：
https://deploy.workers.cloudflare.com/

如果使用 Cloudflare 的 Deploy to Cloudflare / Workers Builds，从 GitHub 导入本项目即可。

### 项目结构

```text
cloud-notepad-v7-1/
├── src/
│   └── index.js
├── schema.sql
├── wrangler.jsonc
├── package.json
├── README.md
└── DEPLOY.md
```

不要删除或拆开 `src` 文件夹。

### 如果上传到 GitHub

1. 解压 ZIP。
2. 新建 GitHub Repository。
3. 将解压后的**项目内容**上传到仓库根目录。
4. 确认仓库根目录能看到 `src`、`schema.sql`、`wrangler.jsonc`、`package.json`、`README.md`、`DEPLOY.md`。
5. 再从 Cloudflare Workers & Pages 导入这个 GitHub 仓库。

不要把 ZIP 文件本身作为仓库里的唯一文件上传。

## 第一次登录

初始密码：

```text
268
```

密码不会以明文形式保存。第一次访问时，Worker 会自动初始化 D1 表结构和密码派生值。

## 自定义域名

部署成功后：

Cloudflare Dashboard
→ Workers & Pages
→ cloud-notepad
→ Settings
→ Domains & Routes
→ Add → Custom Domain

## Android

使用 Android Chrome 打开部署后的 HTTPS 地址，然后选择“添加到主屏幕 / 安装应用”。

## 本次 V7.1 修复

- 修复旧 V6 R2 Bucket 名称残留。
- 修复旧 V6 Worker 名称残留。
- 移除假的 D1 database_id。
- R2 改为由 Cloudflare 自动创建/绑定。
- 首次访问自动初始化 D1 表结构。
- 保留 V7 已有功能。
