/*
 * This file was generated by the Gradle 'init' task.
 */

plugins {
    java
    application
}

repositories {
    mavenLocal()
    maven {
        url = uri("https://repo.maven.apache.org/maven2/")
    }
}

group = "Elvarg"
version = "1.0-SNAPSHOT"
description = "Elvarg-Game-Client"
java.sourceCompatibility = JavaVersion.VERSION_1_8

application {
    mainClass.set("com.runescape.GameWindow")
}
