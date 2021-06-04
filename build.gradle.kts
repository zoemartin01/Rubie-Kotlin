import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "1.5.10"
    application
    id("com.github.johnrengelman.shadow") version "5.2.0"
    java
    id("com.palantir.git-version") version "0.12.3"
}

val MAIN_CLASS_NAME = "me.zoemartin.rubie.Bot"
val JDA_VERSION = "4.2.0_203"

group = "me.zoemartin"
val gitVersion: groovy.lang.Closure<String> by extra
version = gitVersion()


repositories {
    mavenCentral()
    jcenter()
}

application {
    mainClassName = MAIN_CLASS_NAME
}

tasks {
    named<ShadowJar>("shadowJar") {
        manifest {
            attributes(mapOf("Main-Class" to MAIN_CLASS_NAME,
                "Implementation-Version" to version,
                "jda-version" to JDA_VERSION))
        }
    }
}

repositories {
    mavenCentral()
    jcenter()
}

dependencies {
    implementation(group = "net.dv8tion", name = "JDA", version = JDA_VERSION)
    implementation("net.oneandone.reflections8:reflections8:0.11.7")
    implementation(group = "de.androidpit", name = "color-thief", version = "1.1.2")
    implementation(group = "org.hibernate", name = "hibernate-core", version = "5.4.10.Final")
    implementation(group = "org.postgresql", name = "postgresql", version = "42.2.16")
    implementation(group = "com.vladmihalcea", name = "hibernate-types-52", version = "2.9.13")
    implementation("club.minnced:discord-webhooks:0.5.0")
    implementation(group = "net.jodah", name = "expiringmap", version = "0.5.9")
    implementation(group = "org.json", name = "json", version = "20200518")
    implementation(group = "org.apache.commons", name = "commons-lang3", version = "3.11")
    implementation("com.google.code.gson:gson:2.8.6")
    implementation("com.sedmelluq:lavaplayer:1.3.50")
    implementation("joda-time:joda-time:2.10.6")
    implementation(group = "commons-cli", name = "commons-cli", version = "1.4")
    implementation("io.javalin:javalin:3.12.0")
    implementation(group = "org.apache.logging.log4j", name = "log4j-core", version = "2.14.0")
    implementation(group = "org.apache.logging.log4j", name = "log4j-api", version = "2.14.0")
    implementation("org.apache.logging.log4j:log4j-slf4j-impl:2.11.2")
    implementation(group = "com.google.auto.service", name = "auto-service", version = "1.0-rc7")
    annotationProcessor(group = "com.google.auto.service", name = "auto-service", version = "1.0-rc7")
}

tasks.withType<KotlinCompile>() {
    kotlinOptions.jvmTarget = "16"
}

tasks {
    build {
        dependsOn(shadowJar)
    }
}

tasks {
    withType<JavaCompile> {
        options.fork(mapOf(Pair("jvmArgs", listOf("--add-opens", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED"))))
        options.release.set(16)
        options.encoding = "UTF-8"
    }
}