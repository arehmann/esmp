plugins {
    java
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.apache.tika:tika-core:2.9.2")
    implementation("org.apache.tika:tika-parsers-standard-package:2.9.2")
}

application {
    mainClass.set("ExtractDocs")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.named<JavaExec>("run") {
    args = listOf(
        "docs",
        "../../src/main/bundles/legacy-docs"
    )
}
