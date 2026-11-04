# WebWorkshop package identity and signing policy

## Fixed identity of WebWorkshop itself

- Android applicationId: `com.greatwy8888.webworkshop`
- Kotlin/Android namespace: `com.greatwy8888.webworkshop`
- Version baseline: `1.0.0` / versionCode `1`

Do not change the applicationId or signing certificate after distributing an installable WebWorkshop APK. A changed applicationId installs as a different app; a changed signing certificate prevents an in-place upgrade.

## Identity of APKs created by WebWorkshop

Each website project must receive its own generated applicationId once, then retain it for every later build of that project. The generated project record must also retain its versionCode and signing-key reference. The app should export a backup package containing the generated project configuration and its signing keystore, protected by a user-chosen backup password.

## Signing-key security

A signing keystore is a private credential. Never commit a raw `.jks`/`.keystore` file, plaintext password, or private key into Git history. GitHub is suitable for source and non-secret identity metadata. If cloud backup is later enabled, upload only a strongly encrypted keystore backup; keep the encryption password outside the repository. Loss of the keystore means the corresponding generated app cannot be upgraded with the same Android package identity.

## Current implementation status

The fixed applicationId above is committed in `app/build.gradle.kts`. This prototype does not yet generate customer APKs or create/persist customer signing keystores. The current GitHub Actions workflow only compiles the WebWorkshop application itself. A successful WebWorkshop APK build must not be mistaken for the website-to-APK export feature.
