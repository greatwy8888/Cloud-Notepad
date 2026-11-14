# WebWorkshop one-time serial service

This service is hosted by the existing Cloudflare Worker in this repository. It is separate from APK generation and does not yet modify the Android app's activation UI.

## GitHub Actions secrets required

Add these repository Actions secrets before deploying:

- `CLOUDFLARE_API_TOKEN`: Cloudflare API token with permission to manage Workers and D1 databases for the target account.
- `CLOUDFLARE_ACCOUNT_ID`: Cloudflare account ID.
- `WEBWORKSHOP_ADMIN_KEY`: a private random secret of at least 32 characters. Keep it private; it protects serial issuance and revocation.

The workflow creates (or reuses) the D1 database `webworkshop-licenses`, applies `migrations/0001_webworkshop_licenses.sql`, stores the admin key as a Worker secret, and deploys the Worker.

Workflow:
`.github/workflows/deploy-webworkshop-license.yml`

## Admin page

After a successful deployment, open:

`https://<your-worker-host>/admin/webworkshop-licenses`

Enter the `WEBWORKSHOP_ADMIN_KEY` value. Generate serials for a specific WebWorkshop build ID. The serial text is returned only once; the database stores only SHA-256 hashes.

## Activation endpoint

`POST /api/webworkshop/activate`

JSON body:

```json
{
  "serial": "WW-....",
  "build_id": "1.4.6",
  "device_id": "app-generated-install-id",
  "package_name": "com.greatwy8888.webworkshop",
  "app_version": "1.4.6"
}
```

The database update is conditional on `used_at IS NULL` and `revoked_at IS NULL`; only the first successful activation can consume a serial. A different unused serial can be activated on the same device. An already consumed serial cannot be reused after reinstall or clearing app data.

## Important current limitation

The service code and deployment workflow are committed, but deployment is blocked until the three GitHub secrets above are added. The Android app does not yet call the activation endpoint or enforce the activation screen. That integration must use the actual Worker hostname returned after deployment; do not guess the hostname.

First activation must be online. After a successful activation, the intended app behavior is permanent offline use, with the activation receipt stored locally. Local storage alone cannot resist a rooted or reverse-engineered device, so this is protection against ordinary reuse, not an absolute DRM guarantee.
