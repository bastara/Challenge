plugins {
    kotlin("jvm") version "2.3.0"
    id("application")
}

group = "ru.challenge"
version = "1.0-SNAPSHOT"

application {
    mainClass.set("MainKt")   // класс, содержащий функцию main()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    implementation("org.json:json:20231013")  // библиотека для парсинга JSON
    testImplementation(kotlin("test"))
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}