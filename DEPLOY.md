# Cloud Notepad V7 部署说明

## 一、项目结构

上传到 GitHub 时，请保持下面的目录结构，不要把 `src` 文件夹里的内容单独拿出来：

```text
cloud-notepad-v7/
├── src/
│   └── index.js
├── schema.sql
├── wrangler.jsonc
├── package.json
├── README.md
└── DEPLOY.md
```

其中 **`src` 是源码目录，不能删除、改名或拆开**。

## 二、GitHub 上传

1. 新建一个 GitHub Repository。
2. 打开仓库后选择 **Add file → Upload files**。
3. 把 ZIP 解压后的**整个项目内容**上传。
4. 上传完成后，在仓库首页应该能看到：

- `src`
- `schema.sql`
- `wrangler.jsonc`
- `package.json`
- `README.md`
- `DEPLOY.md`

特别注意：不要只上传 `src/index.js`。

## 三、Cloudflare 部署

使用 Cloudflare 的 Deploy to Cloudflare：

```text
https://deploy.workers.cloudflare.com/
```

登录 Cloudflare 后选择 GitHub 项目。

项目中的 `wrangler.jsonc` 已经定义：

- Worker
- D1 Database
- R2 Bucket

按照 Cloudflare 页面提示创建/绑定即可。

## 四、第一次登录

初始密码：

```text
268
```

密码不会以明文形式保存；首次部署后建议尽快修改密码。

## 五、自定义域名

部署成功后：

Cloudflare Dashboard
→ Workers & Pages
→ cloud-notepad-v7
→ Settings
→ Domains & Routes
→ Add
→ Custom Domain

输入自己的域名即可。

## 六、Android 安装为 App

部署并绑定 HTTPS 域名后：

Android Chrome
→ 打开你的云笔记网址
→ 浏览器菜单
→ 添加到主屏幕 / 安装应用

V7 已提供 PWA Manifest 和 Service Worker。

## 七、如果 GitHub 页面看不到 src

不要重新打包或删除 src。

正确状态必须是：

```text
仓库根目录
  ├─ src/
  │   └─ index.js
  ├─ wrangler.jsonc
  ├─ schema.sql
  ├─ package.json
  ├─ README.md
  └─ DEPLOY.md
```

如果上传 ZIP 时 GitHub 把整个 ZIP 文件作为一个文件上传，请先在本地解压 ZIP，再进入解压后的项目目录，把里面的文件上传。
