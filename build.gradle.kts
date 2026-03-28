plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    `maven-publish`
}

dependencies {
    implementation("ltd.lulz.thoth.library:agent-plugin:0.1.0-SNAPSHOT")
    implementation("ltd.lulz.thoth.library:event:0.1.0-SNAPSHOT")
    implementation("ltd.lulz.thoth.library:tool-plugin:0.1.0-SNAPSHOT")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

}

group = "ltd.lulz.thoth.provider"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(25)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("raw-tcp")
                description.set("Raw TCP tool for Thoth")
            }
        }
    }
}

repositories {
    mavenLocal()
    mavenCentral()
}

tasks {
    jar {
        from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
            exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        }
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    named("processResources") {
        dependsOn("createServiceLoader")
    }
    register("createServiceLoader") {
        val outputDir = file("build/resources/main/META-INF/services")
        outputs.dir(outputDir)
        doLast {
            outputDir.mkdirs()
            file("${outputDir}/ltd.lulz.thoth.library.tool.ToolProvider")
                .writeText("ltd.lulz.thoth.tool.TcpSocket\n")
        }
    }
}
