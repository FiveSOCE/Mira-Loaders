plugins {
    java
}

group = "com.mira"
version = "0.2.3-1.18.2"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

val paperApiVersion = providers.gradleProperty("paperApiVersion").orElse("1.18.2-R0.1-SNAPSHOT")
val compileJavaVersion = providers.gradleProperty("compileJavaVersion").map(String::toInt).orElse(17)
val bytecodeJavaVersion = providers.gradleProperty("bytecodeJavaVersion").map(String::toInt).orElse(17)

dependencies {
    compileOnly("io.papermc.paper:paper-api:${paperApiVersion.get()}")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(compileJavaVersion.get()))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(bytecodeJavaVersion.get())
}

tasks.jar {
    archiveFileName.set("MiraLoaders_1.18.2.jar")
}
