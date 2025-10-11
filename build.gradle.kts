plugins {
    id("java")
}

group = "com.illumine.woodcutter"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

repositories {
    mavenLocal()
    maven {
        url = uri("https://repo.runelite.net")
        content {
            includeGroupByRegex("net\\.runelite.*")
        }
    }
    mavenCentral()
}

val apiVersion = "latest.release"

dependencies {
    compileOnly("net.runelite:client:$apiVersion")
    compileOnly("com.tonic:base-api:$apiVersion")
    compileOnly("com.tonic:api:$apiVersion")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(11)
}

// --- Copy built JAR to RuneLite sideload folder (Windows/WSL) ---
val sideloadWin = "C:/Users/joshm/.runelite/sideloaded-plugins"
val sideloadWsl = "/mnt/c/Users/joshm/.runelite/sideloaded-plugins"

val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val isWsl = !isWindows && (System.getenv("WSL_DISTRO_NAME") != null || System.getenv("WSL_INTEROP") != null)

val jarTask = tasks.named("jar")

tasks.register<Copy>("copyJarToSideloadWin") {
    description = "Copy plugin JAR to RuneLite sideload folder on Windows"
    dependsOn(jarTask)
    from(jarTask) // copies the JAR output
    into(sideloadWin)
}

tasks.register<Copy>("copyJarToSideloadWsl") {
    description = "Copy plugin JAR to RuneLite sideload folder on WSL"
    dependsOn(jarTask)
    from(jarTask) // copies the JAR output
    into(sideloadWsl)
}

tasks.named("build") {
    // After build, run the appropriate copy task for the environment
    if (isWindows) {
        finalizedBy("copyJarToSideloadWin")
    } else if (isWsl) {
        finalizedBy("copyJarToSideloadWsl")
    }
}
