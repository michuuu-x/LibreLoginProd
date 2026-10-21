import net.minecraftforge.licenser.LicenseProperties

plugins {
    id("net.minecraftforge.licenser") version "1.2.0"
    id("com.diffplug.spotless") version "8.10.4"
}

defaultTasks("updateLicenses", "shadowJar")

version = "0.25.12"

subprojects {
    version = rootProject.version
    group = "xyz.kyngs.librelogin"

    apply {
        plugin("net.minecraftforge.licenser")
        plugin("com.diffplug.spotless")
    }

    tasks.configureEach {
        if (name.contains("jar", true)) {
            dependsOn("updateLicenses")
//            dependsOn("spotlessJavaApply")
        }
    }

    repositories {
        mavenLocal()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
        }
    }

    license {
        header(rootProject.file("HEADER.txt"))
        include("**/*.java")
        newLine(true)

        matching("", closureOf<LicenseProperties> {
            header.set(rootProject.resources.text.fromFile("licenses/FASTLOGIN_LICENSE"))
        });
    }

    spotless {
        java {
            // Blossom 2.x puts its generated sources (LF line endings) into the main source set
            targetExclude("**/build/generated/**")
            googleJavaFormat()
                .aosp()
                .reflowLongStrings()
                .reorderImports(false)
        }
    }
}
