// Hugo's Horrific Adventure (Hugo for Windows v1.5) - Kotlin port (Swing UI, LWJGL OpenAL sound).
// Build:  ./gradlew run --args="/path/to/HWIN_1"
//         ./gradlew fatJar       (single runnable jar)
//         ./gradlew releaseZip   (release zip + SHA-256 in build/release)
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.2.20"   // 2.2.20+ runs on JDK 25
    application
}

group = "hugo"
version = "1.0.0"

val portVersion = version.toString()
val releaseName = "hugo-port-$portVersion"
val releaseDir = layout.buildDirectory.dir("release")

repositories { mavenCentral() }

val lwjglVersion = "3.3.6"
val lwjglModules = listOf("lwjgl", "lwjgl-openal")   // OpenAL for sound effects; the UI is Swing
// Natives for every desktop we ship; the fat jar carries all of them.
val nativeClassifiers = listOf("natives-linux", "natives-linux-arm64", "natives-windows", "natives-windows-arm64")

dependencies {
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    lwjglModules.forEach { m ->
        implementation("org.lwjgl:$m")
        nativeClassifiers.forEach { c -> runtimeOnly("org.lwjgl:$m::$c") }
    }
}

// Build with whatever JDK runs Gradle (17 or newer), but emit Java 17 bytecode so the jar
// still runs on Java 17. (A jvmToolchain(17) would demand an installed JDK 17.)
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

application {
    mainClass.set("hugo.MainKt")
}

tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Single runnable jar with all dependencies and natives"
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "hugo.MainKt"
        attributes["Implementation-Title"] = "Hugo's Horrific Adventure - Kotlin JVM port"
        attributes["Implementation-Version"] = portVersion
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

// Release zip: the fat jar plus the launchers, README, changelog and licence, under a
// versioned top-level folder. Nothing from the original game is included.
tasks.register<Zip>("releaseZip") {
    group = "build"
    description = "Release zip (fat jar, launchers and docs) in build/release"
    dependsOn("fatJar")
    archiveFileName.set("$releaseName.zip")
    destinationDirectory.set(releaseDir)

    from(tasks.named("fatJar")) { into(releaseName) }
    from("README.md") { into(releaseName) }
    from("CHANGELOG.md") { into(releaseName) }
    from("LICENSE") { into(releaseName) }
    from("THIRD-PARTY-NOTICES.md") { into(releaseName) }
    from("licenses") { into("$releaseName/licenses") }
    from("dist") { into(releaseName) }

    eachFile {
        when {
            name.endsWith(".sh") || name.endsWith(".command") -> permissions { unix("rwxr-xr-x") }
            name.endsWith(".bat") -> permissions { unix("rw-r--r--") }
        }
    }
}

// SHA-256 beside the zip, e.g. for a release page.
val releaseChecksum = tasks.register("releaseChecksum") {
    group = "build"
    description = "Write the SHA-256 checksum of the release zip"
    val zipFile = tasks.named<Zip>("releaseZip").flatMap { it.archiveFile }
    val checksumFile = zipFile.map { File(it.asFile.parentFile, it.asFile.name + ".sha256") }
    inputs.file(zipFile)
    outputs.file(checksumFile)
    doLast {
        val f = zipFile.get().asFile
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        checksumFile.get().writeText("$hash  ${f.name}\n")
    }
}
tasks.named("releaseZip") { finalizedBy(releaseChecksum) }
