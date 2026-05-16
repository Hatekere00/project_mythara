import java.nio.file.Files
import java.util.Properties

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// Capability Expansion v3 — Meta DAT SDK is hosted on Meta's GitHub
// Maven registry (NOT mavenCentral). Pulling requires a personal-access
// token with `read:packages` scope, supplied either as the GITHUB_TOKEN
// env var (preferred for CI / build agents) or as `github_token=...` in
// local.properties (per-developer convenience).
val mwdatLocalProperties = Properties().apply {
    val path = rootDir.toPath().resolve("local.properties")
    if (Files.exists(path)) {
        Files.newInputStream(path).use { load(it) }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven {
            name = "MetaWearablesDAT"
            url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
            credentials {
                // Empty username is correct for GitHub Packages — the
                // token does all the auth. Token resolved from env first,
                // then local.properties. If neither is set, the build
                // will fail with a clear 401 mentioning this maven name.
                username = System.getenv("GITHUB_USERNAME") ?: "x-access-token"
                password = System.getenv("GITHUB_TOKEN")
                    ?: mwdatLocalProperties.getProperty("github_token")
                    ?: ""
            }
            content {
                // Scope this repo to ONLY Meta wearables artefacts so a
                // misconfigured token (or empty token) doesn't 401 on
                // unrelated dependency lookups.
                includeGroup("com.meta.wearable")
            }
        }
    }
}

rootProject.name = "Mythara"
include(":app")
include(":wear")
include(":watchface")
