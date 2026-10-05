plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = "io.hypercode"
version = "0.1.0"

android {
    namespace = "io.hypercode.hyperblekit"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        // android.util.Log and SystemClock are stubs in local JVM tests; the runtime tests log a lot.
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        // The published artifact is the release AAR, with its sources next to it.
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    // Every declaration states its visibility: only what an app needs is public.
    explicitApi()
}

dependencies {
    // StateFlow and SharedFlow are part of the public API.
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    // Virtual time for the runtime tests over in-process transports.
    testImplementation(libs.kotlinx.coroutines.test)
    // Real org.json on the unit-test classpath: android.jar ships org.json only as throwing stubs,
    // so WireJson (and the vector tests) could not run as local JVM tests without this.
    testImplementation(libs.json)
}

/**
 * Publishing: `io.hypercode:hyperblekit:<version>` as an AAR with a sources jar.
 *
 * Nothing about where it goes is in the repository. Each value is read from a Gradle property
 * (`-P…` or `~/.gradle/gradle.properties`) or, failing that, from an environment variable:
 *
 * | Gradle property              | Environment variable        | Meaning                                   |
 * |------------------------------|-----------------------------|-------------------------------------------|
 * | `hyperblekit.publish.url`    | `HYPERBLEKIT_PUBLISH_URL`   | The Maven repository to publish to, e.g. a GitHub Packages URL |
 * | `hyperblekit.publish.user`   | `HYPERBLEKIT_PUBLISH_USER`  | The account that publishes                |
 * | `hyperblekit.publish.token`  | `HYPERBLEKIT_PUBLISH_TOKEN` | Its token                                 |
 * | `hyperblekit.pom.url`        | `HYPERBLEKIT_POM_URL`       | The project's web page, for the POM       |
 * | `hyperblekit.pom.scm`        | `HYPERBLEKIT_POM_SCM`       | The source repository's URL, for the POM  |
 *
 * Without the first three there is no remote repository and only `publishToMavenLocal` exists;
 * without the last two the POM simply has no `url` / `scm`.
 */
fun setting(property: String, environment: String): String? =
    providers.gradleProperty(property).orElse(providers.environmentVariable(environment)).orNull

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "hyperblekit"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("HyperBleKit")
                description.set(
                    "Local multiplayer for nearby phones, Android and iOS mixed, over Bluetooth Low Energy only: " +
                        "discovery, connection, reliable state sync, a real-time frame lane, reconnect and host migration."
                )
                setting("hyperblekit.pom.url", "HYPERBLEKIT_POM_URL")?.let { url.set(it) }
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                setting("hyperblekit.pom.scm", "HYPERBLEKIT_POM_SCM")?.let { repository ->
                    scm { url.set(repository) }
                }
            }
        }
    }
    repositories {
        val repository = setting("hyperblekit.publish.url", "HYPERBLEKIT_PUBLISH_URL")
        val user = setting("hyperblekit.publish.user", "HYPERBLEKIT_PUBLISH_USER")
        val token = setting("hyperblekit.publish.token", "HYPERBLEKIT_PUBLISH_TOKEN")
        if (repository != null && user != null && token != null) {
            maven {
                name = "GitHubPackages"
                url = uri(repository)
                credentials {
                    username = user
                    password = token
                }
            }
        }
    }
}
