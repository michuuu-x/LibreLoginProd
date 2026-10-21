import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("java")
    id("com.gradleup.shadow") version "9.4.3"
    id("net.kyori.blossom").version("2.2.0")
    id("java-library")
    id("xyz.kyngs.librarian.plugin").version("2.0.0-SNAPSHOT")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // Minimum supported Minecraft is 26.2 (Java 25), but the same jar also runs on Velocity
    options.release.set(21)
}

repositories {
    // mavenLocal()
    maven { url = uri("https://repo.opencollab.dev/maven-snapshots/") }
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
    maven { url = uri("https://hub.spigotmc.org/nexus/") }
    maven { url = uri("https://repo.kyngs.xyz/public/") }
    maven { url = uri("https://mvn.exceptionflug.de/repository/exceptionflug-public/") }
    maven { url = uri("https://repo.dmulloy2.net/repository/public/") }
    maven { url = uri("https://repo.alessiodp.com/releases/") }
    maven { url = uri("https://jitpack.io/") }
    maven { url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/") }
    maven { url = uri("https://repo.codemc.io/repository/maven-releases/") }
}

sourceSets {
    main {
        blossom {
            javaSources {
                property("version", project.version.toString())
            }
        }
    }
}

tasks.withType<ShadowJar> {
    destinationDirectory.set(file("../target"))

    archiveBaseName.set("LibreLoginProd")
    archiveClassifier = null

    dependencies {
        exclude(dependency("org.slf4j:.*:.*"))
        exclude(dependency("org.checkerframework:.*:.*"))
        exclude(dependency("com.google.errorprone:.*:.*"))
        exclude(dependency("com.google.protobuf:.*:.*"))
    }

    relocate("co.aikar.acf", "xyz.kyngs.librelogin.lib.acf")
    relocate("com.github.benmanes.caffeine", "xyz.kyngs.librelogin.lib.caffeine")
    relocate("com.typesafe.config", "xyz.kyngs.librelogin.lib.hocon")
    relocate("com.zaxxer.hikari", "xyz.kyngs.librelogin.lib.hikari")
    relocate("org.mariadb", "xyz.kyngs.librelogin.lib.mariadb")
    relocate("org.bstats", "xyz.kyngs.librelogin.lib.metrics")
    relocate("org.intellij", "xyz.kyngs.librelogin.lib.intellij")
    relocate("org.jetbrains", "xyz.kyngs.librelogin.lib.jetbrains")
    relocate("io.leangen.geantyref", "xyz.kyngs.librelogin.lib.reflect")
    relocate("org.spongepowered.configurate", "xyz.kyngs.librelogin.lib.configurate")
    relocate("net.byteflux.libby", "xyz.kyngs.librelogin.lib.libby")
    relocate("org.postgresql", "xyz.kyngs.librelogin.lib.postgresql")
    relocate("com.github.retrooper.packetevents", "xyz.kyngs.librelogin.lib.packetevents.api")
    relocate("io.github.retrooper.packetevents", "xyz.kyngs.librelogin.lib.packetevents.platform")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<Jar> {
    from("../LICENSE.txt")
}

// paper-api 26.x is published for Java 25, while our own bytecode targets Java 21 (see release above)
configurations.compileClasspath {
    attributes {
        attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
}

librarian {
    excludeDependency("org.slf4j:.*:.*")
    excludeDependency("org.checkerframework:.*:.*")
    excludeDependency("com.google.errorprone:.*:.*")
    excludeDependency("com.google.protobuf:.*:.*")

    // Often redeploys the same version, so calculating checksum causes false flags
    noChecksumDependency("com.github.retrooper.packetevents:.*:.*")
}

//configurations.all {
//    // I hate this, but it needs to be done as bungeecord does not support newer versions of adventure, and packetevents includes it
//    resolutionStrategy {
//        force("net.kyori:adventure-text-minimessage:4.14.0")
//        force("net.kyori:adventure-text-serializer-gson:4.14.0")
//        force("net.kyori:adventure-text-serializer-legacy:4.14.0")
//        force("net.kyori:adventure-text-serializer-json:4.14.0")
//        force("net.kyori:adventure-api:4.14.0")
//        force("net.kyori:adventure-nbt:4.14.0")
//        force("net.kyori:adventure-key:4.14.0")
//    }
//}

dependencies {
    //API
    implementation(project(":API"))

    //Velocity
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    compileOnly("com.velocitypowered:velocity-proxy:3.2.0-SNAPSHOT-277")
    compileOnly("com.github.ProxioDev.ValioBungee:RedisBungee-Bungee:0.13.0")

    //MySQL
    librarian("org.mariadb.jdbc:mariadb-java-client:3.5.4")
    librarian("com.zaxxer:HikariCP:6.3.2")

    //SQLite
    librarian("org.xerial:sqlite-jdbc:3.50.3.0")

    //PostgreSQL
    librarian("org.postgresql:postgresql:42.7.8")

    //ACF
    librarian("com.github.kyngs.commands:acf-velocity:7d5bf7cac0")
    librarian("com.github.kyngs.commands:acf-paper:7d5bf7cac0")

    //Utils
    librarian("com.github.ben-manes.caffeine:caffeine:3.2.2")
    librarian("org.spongepowered:configurate-hocon:4.2.0")
    librarian("at.favre.lib:bcrypt:0.10.2")
    librarian("dev.samstevens.totp:totp:1.7.1")
    compileOnly("dev.simplix:protocolize-api:2.4.2")
    librarian("org.bouncycastle:bcprov-jdk18on:1.81")
    librarian("org.apache.commons:commons-email:1.6.0")
    librarian("net.kyori:adventure-text-minimessage:4.25.0")

    //Geyser
    compileOnly("org.geysermc.floodgate:api:2.2.0-SNAPSHOT")
    //LuckPerms
    compileOnly("net.luckperms:api:5.5")

    //BStats
    librarian("org.bstats:bstats-velocity:3.1.0")
    librarian("org.bstats:bstats-bukkit:3.1.0")

    //Paper
    compileOnly("io.papermc.paper:paper-api:26.2.build.134-stable")
    implementation("com.github.retrooper:packetevents-spigot:2.14.0")
    compileOnly("org.apache.logging.log4j:log4j-core:2.25.1")

    //Libby (runtime library loader; librarian.json is generated by the Librarian gradle plugin)
    implementation("xyz.kyngs.libby:libby-bukkit:1.6.0")
    implementation("xyz.kyngs.libby:libby-velocity:1.6.0")
    implementation("xyz.kyngs.libby:libby-paper:1.6.0")
}

tasks.withType<ProcessResources> {
    outputs.upToDateWhen { false }
    filesMatching("plugin.yml") {
        expand(mapOf("version" to version))
    }
    filesMatching("paper-plugin.yml") {
        expand(mapOf("version" to version))
    }
}