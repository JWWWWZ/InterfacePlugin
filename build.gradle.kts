plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion")
        )
        bundledPlugin("com.intellij.java")
        bundledPlugin("Git4Idea")
        javaCompiler()
    }
    // OkHttp for HTTP requests
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks {
    // 禁用这个导致报错的任务
    buildSearchableOptions {
        enabled = false
    }

    // Disable bytecode instrumentation when building offline (JARs not cached)
    // Safe to disable: only affects @NotNull runtime assertions injected by the compiler.
    // Remove this block after running one online build to cache the required JARs.
    instrumentCode {
        enabled = false
    }
}