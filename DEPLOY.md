# Cloud Notepad V7.2

本版本针对 Cloudflare GitHub Builds 的 `inherit binding STORAGE` 错误，改为浏览器 Dashboard 绑定资源。

1. 先从 GitHub 部署 Worker。
2. Cloudflare Dashboard → Storage & databases → D1 → Create database，建议名称 `cloud-notepad-db`。
3. Worker → Settings → Bindings → Add binding → D1 database，Variable name 必须为 `DB`。
4. Cloudflare Dashboard → R2 → Create bucket，建议名称 `cloud-notepad-files`。
5. Worker → Settings → Bindings → Add binding → R2 bucket，Variable name 必须为 `STORAGE`。
6. 重新部署。首次访问会自动初始化 D1 表。

初始密码：`268`

项目结构：`src/index.js`、`schema.sql`、`wrangler.jsonc`、`package.json`、`README.md`、`DEPLOY.md`。
