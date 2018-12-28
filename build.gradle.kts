
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar


val kotlinversion = "1.3.11"

buildscript {

    repositories {
        mavenCentral()
        jcenter()
    }
}

group = "com.wolle.ssyncbrowser"
version = "1.0-SNAPSHOT"

plugins {
    kotlin("jvm") version "1.3.11"
    id("idea")
    id("application")
    id("com.github.ben-manes.versions") version "0.20.0"
    id("com.github.johnrengelman.shadow") version "4.0.3"
    id("edu.sc.seis.macAppBundle") version "2.3.0"
}

tasks.withType<Wrapper> {
    gradleVersion = "4.4.1"
}

application {
    mainClassName = "MainKt"
    //defaultTasks = tasks.run
}

tasks.withType<Jar> {
    manifest {
        attributes(mapOf(
                "Main-Class" to "test.ApplicationKt",
                "Description" to "SSyncBrowser JAR",
                "Implementation-Title" to "SSyncBrowser",
                "Implementation-Version" to version,
                "Main-Class" to "MainKt"
        ))
    }
}

tasks.withType<ShadowJar> {
    // uses manifest from above!
    baseName = "app"
    classifier = ""
    version = ""
    mergeServiceFiles() // essential to enable flac etc
}

macAppBundle {
    mainClassName = "MainKt"
    icon = "icon.icns"
    bundleJRE = false
    javaProperties["apple.laf.useScreenMenuBar"] = "true"
}

repositories {
    mavenCentral()
    jcenter()
}

dependencies {
    compile("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinversion")
    compile("org.jetbrains.kotlin:kotlin-reflect:$kotlinversion")
    compile("io.github.microutils:kotlin-logging:1.6.22")
    compile("com.hierynomus:sshj:0.26.0")
    compile("org.slf4j:slf4j-simple:1.8.0-beta2") // no colors, everything stderr
    compile("no.tornado:tornadofx:1.7.17")
    compile("io.methvin:directory-watcher:0.9.3")
    compile("org.bouncycastle:bcprov-jdk15on:1.60")
    compile("org.bouncycastle:bcpkix-jdk15on:1.60")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}


task("dist") {
    dependsOn("shadowJar") // fat jar
    dependsOn("createApp") // macappbundle
}
