plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.greatwy8888.webworkshop"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.greatwy8888.webworkshop"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.3.1"
    }


    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("WEBWORKSHOP_RELEASE_KEYSTORE_FILE")
            if (!storeFilePath.isNullOrBlank()) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("WEBWORKSHOP_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("WEBWORKSHOP_RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("WEBWORKSHOP_RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources.excludes += setOf(
            "prebuilt/linux/**", "prebuilt/macosx/**", "prebuilt/windows/**",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        )
    }
    androidResources { noCompress += "apk" }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("com.android.tools.build:apksig:8.7.3") {
        exclude(group = "com.google.guava", module = "listenablefuture")
    }
    implementation(files("libs/apktool/apktool-android-1.0.0.aar"))
    implementation(fileTree(mapOf("dir" to "libs/apktool/runtime", "include" to listOf("*.jar"))))
}

val prepareGeneratedTemplate by tasks.registering {
    dependsOn(":template:assembleDebug")
    val output = layout.projectDirectory.dir("app/src/main/assets")
    outputs.file(output.file("generated-template.apk"))
    doLast {
        val source = project(":template").layout.buildDirectory.file("outputs/apk/debug/template-debug.apk").get().asFile
        check(source.exists()) { "Generated app template APK was not built: $source" }
        output.asFile.mkdirs()
        source.copyTo(output.file("generated-template.apk").asFile, overwrite = true)
    }
}
tasks.named("preBuild").configure { dependsOn(prepareGeneratedTemplate) }


configurations.configureEach {
    exclude(group = "com.google.guava", module = "listenablefuture")
}
