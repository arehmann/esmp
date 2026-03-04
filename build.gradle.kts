plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.mgmt)
    alias(libs.plugins.spotless)
    java
}

group = "com.esmp"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.neo4j)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.micrometer.prometheus)
    implementation(libs.flyway.core)
    implementation(libs.flyway.mysql)
    implementation(libs.mysql.connector)
    implementation(libs.qdrant.client)
    implementation(libs.grpc.stub)
    implementation(libs.openrewrite.java)
    implementation(libs.openrewrite.java.jdk21)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.vaadin.server)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.neo4j)
    testImplementation(libs.testcontainers.mysql)
}

spotless {
    java {
        googleJavaFormat(libs.versions.google.java.format.get())
        removeUnusedImports()
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
