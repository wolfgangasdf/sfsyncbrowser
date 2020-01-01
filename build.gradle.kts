
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.openjfx.gradle.JavaFXModule
import org.openjfx.gradle.JavaFXOptions

val kotlinversion = "1.3.61"

buildscript {
    repositories {
        mavenCentral()
        jcenter()
    }
}

group = "com.wolle.ssyncbrowser"
version = "1.0-SNAPSHOT"
val cPlatforms = listOf("mac") // compile for these platforms. "mac", "linux", "win"

println("Current Java version: ${JavaVersion.current()}")
if (JavaVersion.current().toString() != "13") throw GradleException("Use Java 13")

plugins {
    kotlin("jvm") version "1.3.61"
    id("idea")
    application
    id("org.openjfx.javafxplugin") version "0.0.8"
    id("com.github.ben-manes.versions") version "0.27.0"
    id("org.beryx.runtime") version "1.8.0"
}

application {
    mainClassName = "MainKt"
    //defaultTasks = tasks.run
}

repositories {
    mavenCentral()
    jcenter()
    maven { setUrl("https://oss.sonatype.org/content/repositories/snapshots") } // tornadofx snapshots
}

javafx {
    version = "12"
    modules("javafx.base", "javafx.controls", "javafx.web")
    // set compileOnly for crosspackage to avoid packaging host javafx jmods for all target platforms
    configuration = if (project.gradle.startParameter.taskNames.intersect(listOf("crosspackage", "dist")).isNotEmpty()) "compileOnly" else "compile"
}
val javaFXOptions = the<JavaFXOptions>()

dependencies {
    compile("org.jetbrains.kotlin:kotlin-stdlib:$kotlinversion")
    compile("org.jetbrains.kotlin:kotlin-reflect:$kotlinversion")
    compile("io.github.microutils:kotlin-logging:1.7.8")
    compile("org.slf4j:slf4j-simple:1.8.0-beta4") // no colors, everything stderr
    compile("no.tornado:tornadofx:2.0.0-SNAPSHOT")
    compile("com.hierynomus:sshj:0.27.0")
    compile("io.methvin:directory-watcher:0.9.5")
    runtime("org.bouncycastle:bcprov-jdk15on:1.61")
    runtime("org.bouncycastle:bcpkix-jdk15on:1.61")

    cPlatforms.forEach {platform ->
        val cfg = configurations.create("javafx_$platform")
        JavaFXModule.getJavaFXModules(javaFXOptions.modules).forEach { m ->
            project.dependencies.add(cfg.name,"org.openjfx:${m.artifactName}:${javaFXOptions.version}:$platform")
        }
    }

}

runtime {
    options.set(listOf("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages"))
    // first row: suggestedModules
    modules.set(listOf("java.desktop", "java.logging", "java.prefs", "java.xml", "jdk.unsupported", "jdk.jfr", "jdk.jsobject", "jdk.xml.dom",
            "jdk.crypto.cryptoki","jdk.crypto.ec")) // for some https (apod: ec)

    if (cPlatforms.contains("mac")) targetPlatform("mac", System.getenv("JDK_MAC_HOME"))
    if (cPlatforms.contains("win")) targetPlatform("win", System.getenv("JDK_WIN_HOME"))
    if (cPlatforms.contains("linux")) targetPlatform("linux", System.getenv("JDK_LINUX_HOME"))
}

open class CrossPackage : DefaultTask() {
    var execfilename = "execfilename"
    var macicnspath = "macicnspath" // name should be execfilename.icns

    @TaskAction
    fun crossPackage() {
        project.runtime.targetPlatforms.get().forEach { (t, _) ->
            println("targetplatform: $t")
            val imgdir = "${project.runtime.imageDir.get()}/${project.name}-$t"
            println("imagedir: $imgdir")
            when(t) {
                "mac" -> {
                    val appp = File(project.buildDir.path + "/crosspackage/mac/$execfilename.app").path
                    project.delete(appp)
                    project.copy {
                        into(appp)
                        from(macicnspath) {
                            into("Contents/Resources").rename { "$execfilename.icns" }
                        }
                        from("$imgdir/${project.application.executableDir}/${project.application.applicationName}") {
                            into("Contents/MacOS")
                        }
                        from(imgdir) {
                            into("Contents")
                        }
                    }
                    val pf = File("$appp/Contents/Info.plist")
                    pf.writeText("""
                        <?xml version="1.0" ?>
                        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                        <plist version="1.0">
                         <dict>
                          <key>LSMinimumSystemVersion</key>
                          <string>10.9</string>
                          <key>CFBundleDevelopmentRegion</key>
                          <string>English</string>
                          <key>CFBundleAllowMixedLocalizations</key>
                          <true/>
                          <key>CFBundleExecutable</key>
                          <string>$execfilename</string>
                          <key>CFBundleIconFile</key>
                          <string>$execfilename.icns</string>
                          <key>CFBundleIdentifier</key>
                          <string>main</string>
                          <key>CFBundleInfoDictionaryVersion</key>
                          <string>6.0</string>
                          <key>CFBundleName</key>
                          <string>${project.name}</string>
                          <key>CFBundlePackageType</key>
                          <string>APPL</string>
                          <key>CFBundleShortVersionString</key>
                          <string>${project.version}</string>
                          <key>CFBundleSignature</key>
                          <string>????</string>
                          <!-- See http://developer.apple.com/library/mac/#releasenotes/General/SubmittingToMacAppStore/_index.html
                               for list of AppStore categories -->
                          <key>LSApplicationCategoryType</key>
                          <string>Unknown</string>
                          <key>CFBundleVersion</key>
                          <string>100</string>
                          <key>NSHumanReadableCopyright</key>
                          <string>Copyright (C) 2019</string>
                          <key>NSHighResolutionCapable</key>
                          <string>true</string>
                         </dict>
                        </plist>
                    """.trimIndent())
                    // touch folder to update Finder
                    File(appp).setLastModified(System.currentTimeMillis())
                    // zip it
                    ant.withGroovyBuilder {
                        "zip"("destfile" to "${project.buildDir.path}/crosspackage/$execfilename-mac.zip",
                                "basedir" to "${project.buildDir.path}/crosspackage/mac") {
                        }
                    }
                }
                "win" -> {
                    ant.withGroovyBuilder {
                        "zip"("destfile" to "${project.buildDir.path}/crosspackage/$execfilename-win.zip",
                                "basedir" to imgdir) {
                        }
                    }
                }
                "linux" -> {
                    ant.withGroovyBuilder {
                        "zip"("destfile" to "${project.buildDir.path}/crosspackage/$execfilename-linux.zip",
                                "basedir" to imgdir) {
                        }
                    }
                }
            }
        }
    }
}

tasks.register<CrossPackage>("crosspackage") {
    dependsOn("runtime")
    execfilename = "SSyncBrowser"
    macicnspath = "./icon.icns"
}

tasks.withType(CreateStartScripts::class).forEach {script ->
    script.doFirst {
        script.classpath =  files("lib/*")
    }
}

// copy jmods for each platform
tasks["runtime"].doLast {
    cPlatforms.forEach { platform ->
        println("Copy jmods for platform $platform")
        val cfg = configurations["javafx_$platform"]
        cfg.resolvedConfiguration.files.forEach { f ->
            copy {
                from(f)
                into("${project.runtime.imageDir.get()}/${project.name}-$platform/lib")
            }
        }
    }
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}


task("dist") {
    dependsOn("crosspackage")
    doLast { println("Created zips in build/crosspackage") }
}
