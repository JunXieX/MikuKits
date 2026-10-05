plugins {
    java
}

group = "dev.junxiex"
version = "1.3.0"
description = "MikuKits - Paper/Folia kit plugin with dialog menus"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    // sqlite-jdbc 不内置：由 MikuKitsLoader 在启动时通过 Paper 的 MavenLibraryResolver 解析
}

tasks {
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }
}
