plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// vcpkg checkout with the arm64-android packages (CLAUDE.md, "vcpkg Dependencies")
val vcpkgRoot: String = System.getenv("VCPKG_ROOT")
    ?: "${System.getProperty("user.home")}/projects/vcpkg"

/*
 * Version from git.  Releases are tagged boxxy-v<version> (the repository
 * also carries xemu's own v0.x tags, which must not be picked up): at the tag
 * the version is "1.0", later commits read "1.0-12-gabc1234", and "-dirty"
 * marks uncommitted changes.  The version code is the commit count, so every
 * build of a later commit installs over an earlier one.  CI checks out the
 * full history for this (.github/workflows/android.yml).
 */
fun git(vararg args: String): String? = try {
    val proc = ProcessBuilder("git", *args).directory(rootDir).redirectErrorStream(false).start()
    val out = proc.inputStream.bufferedReader().readText().trim()
    if (proc.waitFor() == 0 && out.isNotEmpty()) out else null
} catch (e: Exception) {
    null
}
val gitCommit = git("rev-parse", "--short=10", "HEAD") ?: "unknown"
val gitDescribe = git("describe", "--tags", "--match", "boxxy-v*", "--dirty")
val boxxyVersionName = gitDescribe?.removePrefix("boxxy-v") ?: "0.0-dev-g$gitCommit"
val boxxyVersionCode = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
val boxxyCommitDate = git("log", "-1", "--format=%cs") ?: "unknown"

/*
 * Release signing key (android/tools/make-release-key.sh): from
 * ~/.gradle/gradle.properties on a developer machine, or from the environment
 * in CI (.github/workflows/release.yml).  Without it, release builds fall back
 * to the debug key, so anyone can still build from source -- but such a build
 * won't install over an official one.
 */
fun releaseSetting(name: String): String? =
    (project.findProperty(name) as String?) ?: System.getenv(name)
val releaseStoreFile = releaseSetting("BOXXY_RELEASE_STORE_FILE")?.let { file(it) }?.takeIf { it.isFile }

android {
    namespace = "com.boxxy"
    compileSdk = 34
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.boxxy"
        minSdk = 33
        targetSdk = 34
        versionCode = boxxyVersionCode
        versionName = boxxyVersionName
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        buildConfigField("String", "COMMIT_DATE", "\"$boxxyCommitDate\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters.add("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments("-DANDROID_STL=c++_shared", "-DVCPKG_ROOT=$vcpkgRoot")
            }
        }
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseSetting("BOXXY_RELEASE_STORE_PASSWORD")
                keyAlias = releaseSetting("BOXXY_RELEASE_KEY_ALIAS") ?: "boxxy"
                keyPassword = releaseSetting("BOXXY_RELEASE_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
            // `run-as` (needed for simpleperf to read the app's data dir) refuses
            // non-debuggable packages. Doesn't affect optimization/codegen, only
            // allows debugger/profiler attachment -- same "not for distribution"
            // caveat as the signing config above.
            // `-PreleaseDebuggable=false` builds it as users would get it: ART
            // only compiles non-debuggable apps ahead of time, so launch-time
            // measurements need this (a debuggable build runs interpreted).
            isDebuggable = (project.findProperty("releaseDebuggable") as String?) != "false"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // Required by libadrenotools: hook .so files must be extracted to
        // nativeLibraryDir on disk so adrenotools_open_libvulkan() can find them.
        jniLibs {
            useLegacyPackaging = true
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.05.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Extended Material icon set (Outlined style used throughout settings)
    implementation("androidx.compose.material:material-icons-extended")

    // Cover art image loading
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Encrypted storage for API keys
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
