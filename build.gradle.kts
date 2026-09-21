plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "com.ossagent"
version = "0.0.1-SNAPSHOT"
description = "Human-supervised OSS contribution workflow automation"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // 선언하지 않으면 Gradle 이 자기 버전의 launcher 를 끼워 넣어 BOM 이 관리하는 engine 과 어긋난다.
    // 증상이 「테스트를 못 찾는다」로 나와 원인을 짚기 어렵다.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // 기본 출력은 예외 클래스와 발생 위치만 남기고 메시지를 버린다.
    // 컨텍스트 로딩 실패처럼 원인이 중첩된 경우 무엇이 틀렸는지 알 수 없어 전문을 남긴다.
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
        events("failed")
    }
}
