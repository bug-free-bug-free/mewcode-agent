plugins {
    java
    application
    id("com.gradleup.shadow") version "8.3.6"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "com.mewcode.MewCode"
}

repositories {
    mavenCentral()
}

dependencies {
    // TUI framework (Bubble Tea Java port)
    implementation("com.williamcallahan:tui4j:0.3.3")

    // Markdown terminal rendering
    implementation("com.github.ajalt.mordant:mordant:3.0.2")
    implementation("com.github.ajalt.mordant:mordant-markdown:3.0.2")

    // MCP SDK
    implementation("io.modelcontextprotocol.sdk:mcp:1.1.3")
    implementation("org.slf4j:slf4j-nop:2.0.16")

    // LLM SDKs
    implementation("com.anthropic:anthropic-java:2.34.0")
    implementation("com.openai:openai-java:4.37.0")

    // Config & JSON
    implementation("org.yaml:snakeyaml:2.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.3")

    // Test
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName = "mewcode"
    archiveClassifier = ""
    archiveVersion = ""
    mergeServiceFiles()
}

tasks.distZip { dependsOn(tasks.shadowJar) }
tasks.distTar { dependsOn(tasks.shadowJar) }
tasks.startScripts { dependsOn(tasks.shadowJar) }
tasks.named("startShadowScripts") { dependsOn(tasks.jar) }
