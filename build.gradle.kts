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