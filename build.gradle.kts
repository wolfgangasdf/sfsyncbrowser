
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar


val kotlin_version = "1.3.11"

buildscript {

    repositories {
        mavenCentral()
        jcenter()
    }
    dependencies {
        classpath("com.hierynomus:sshj:0.26.0") // TODO: why???
    }
}

group = "com.wolle.ssyncbrowser"
version = "1.0-SNAPSHOT"

plugins {
    kotlin("jvm") version "1.3.11"
    id("idea")
    id("application")
    id("com.github.ben-manes.versions") version "0.20.0" // TODO gradle5
    id("com.github.johnrengelman.shadow") version "4.0.3" // TODO gradle5 test, can work!
    id("edu.sc.seis.macAppBundle") version "2.3.0" // TODO test gradle 5
}

tasks.withType<Wrapper> {
    gradleVersion = "4.4.1"
}

//java.sourceSets {
//    main.kotlin.exclude "**/Connection.kt"
//    main.kotlin.exclude "**/Profile.kt"
//}

// TODO
//idea {
//    module {
//        downloadJavadoc = true
//        downloadSources = true
//    }
//}

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
    compile("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlin_version")
    compile("org.jetbrains.kotlin:kotlin-reflect:$kotlin_version")
    compile("io.github.microutils:kotlin-logging:1.6.22")
    compile("com.hierynomus:sshj:0.26.0")
    compile("org.slf4j:slf4j-simple:1.8.0-beta2") // no colors, everything stderr
    compile("no.tornado:tornadofx:1.7.17")
    compile("io.methvin:directory-watcher:0.9.3")
//    implementation "com.beust:klaxon:3.0.1"
//    compile "com.fasterxml.jackson.module:jackson-module-kotlin:2.9.+"
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}


task("dist") {
    dependsOn("shadowJar") // fat jar
    dependsOn("createApp") // macappbundle
}
