import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.fabricmc.loom.task.RemapJarTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("fabric-loom") version "1.13-SNAPSHOT"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    kotlin("jvm") version "2.0.21"
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String
base.archivesName.set(project.property("archives_base_name") as String)

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

// Configuration for libraries we want to shade into our jar
val shadowImpl: Configuration by configurations.creating {
    configurations.implementation.get().extendsFrom(this)
}

dependencies {
    // Minecraft + Fabric
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${project.property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")

    // FLK: solo declarado en fabric.mod.json como depends. El servidor ya lo tiene.
    // NO se incluye aqui — Loom no puede remap JARs con Kotlin 2.2+ metadata.
    // El kotlin stdlib lo provee el plugin kotlin("jvm"), no FLK.

    // Cobblemon y Trinkets: acceso via reflexion en tiempo de ejecucion (ver PokemonPCSync.kt y TrinketsSync.kt).
    // No se compila contra ellos para evitar el problema de Loom/kotlinx-metadata-jvm.

    // HikariCP connection pool — shaded and relocated to avoid conflicts with other mods
    shadowImpl("com.zaxxer:HikariCP:5.1.0") {
        exclude(group = "org.slf4j")
    }

    // MySQL JDBC connector — shaded and relocated
    shadowImpl("com.mysql:mysql-connector-j:8.4.0") {
        exclude(group = "com.google.protobuf")
        exclude(group = "com.google.guava")
        exclude(group = "com.google.errorprone")
    }

    // Cliente Redis (coordinacion cross-server, ver network/CrossServerCoordinator.kt) — shaded y relocado
    shadowImpl("redis.clients:jedis:5.2.0") {
        exclude(group = "org.slf4j")
        exclude(group = "org.json")
    }
}

tasks {
    processResources {
        inputs.property("version", project.version)
        filteringCharset = "UTF-8"
        filesMatching("fabric.mod.json") {
            expand("version" to project.version)
        }
    }

    val shadowJar by named<ShadowJar>("shadowJar") {
        configurations = listOf(shadowImpl)
        // Relocate to avoid class conflicts with any other mod that bundles these libraries
        relocate("com.zaxxer.hikari", "com.cobblemonguard.libs.hikari")
        relocate("com.mysql", "com.cobblemonguard.libs.mysql")
        relocate("com.google.common", "com.cobblemonguard.libs.guava")
        relocate("redis.clients.jedis", "com.cobblemonguard.libs.jedis")
        relocate("org.apache.commons.pool2", "com.cobblemonguard.libs.commonspool2")
        mergeServiceFiles()
        archiveClassifier.set("dev-shadow")
    }

    // Use shadow jar as input for loom's remapJar (the final distributable jar)
    named<RemapJarTask>("remapJar") {
        inputFile.set(shadowJar.archiveFile)
        dependsOn(shadowJar)
        archiveClassifier.set("")
    }

    named<org.gradle.jvm.tasks.Jar>("jar") {
        archiveClassifier.set("dev")
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
