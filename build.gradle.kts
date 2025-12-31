import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "plus.wcj.jetbrains.plugins"

version = run {
    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy.M.d.1HHmmss"))
}

repositories {
    mavenLocal()
    maven { url = uri("https://maven.aliyun.com/repository/public/") }
    maven { url = uri("https://maven.aliyun.com/repository/central") }
    maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
//        intellijIdea("2025.2.4")
        intellijIdea("2022.3")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Java PSI is required for control-flow extraction
        bundledPlugin("com.intellij.java")
    }
    implementation(kotlin("stdlib"))
    compileOnly("org.projectlombok:lombok:1.18.32")
    annotationProcessor("org.projectlombok:lombok:1.18.32")
    testCompileOnly("org.projectlombok:lombok:1.18.32")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.32")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "223"
            untilBuild = provider { null }
        }
    }
}
tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
