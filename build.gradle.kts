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

    // 스키마 정본은 db/migration 의 SQL 이다. ddl-auto 는 validate 로만 쓴다 — Q-2
    implementation("org.flywaydb:flyway-core")
    // Flyway 10 부터 DB 별 지원이 모듈로 분리됐다. 이게 없으면 PostgreSQL 에서 기동하지 않는다.
    // H2 는 core 에 남아 있어 별도 모듈이 없다(flyway-database-h2 는 존재하지 않는다)
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("com.h2database:h2")

    // 엔티티 보일러플레이트를 줄인다 — Q-7 (2026-09-22 도입 결정)
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // 스키마 검증은 실 DB 가 아니면 의미가 없다 — H2 는 PostgreSQL 흉내일 뿐이다 (Q-2b)
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")

    // 선언하지 않으면 Gradle 이 자기 버전의 launcher 를 끼워 넣어 BOM 이 관리하는 engine 과 어긋난다.
    // 증상이 「테스트를 못 찾는다」로 나와 원인을 짚기 어렵다.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // Testcontainers 가 물고 오는 docker-java 의 기본 협상 버전이 낮아, 최신 Docker 엔진이
    // /v1.32/info 를 400 으로 거부한다. 증상은 「Could not find a valid Docker environment」라
    // Docker 가 안 떠 있는 것처럼 보이지만, 실제로는 API 버전 거부다 (엔진은 v1.41+ 만 받는다).
    //
    // DOCKER_HOST 도 함께 준다 — 이게 있어야 EnvironmentAndSystemPropertyClientProviderStrategy 가
    // 활성화되고, 그 전략만이 DOCKER_API_VERSION 을 읽는다. 소켓 전략들은 자체 설정을 만들어
    // 버전 지정을 무시한다. 이미 설정된 환경변수가 있으면 그것을 존중한다.
    environment("DOCKER_HOST", System.getenv("DOCKER_HOST") ?: "unix:///var/run/docker.sock")
    environment("DOCKER_API_VERSION", System.getenv("DOCKER_API_VERSION") ?: "1.44")

    // 기본 출력은 예외 클래스와 발생 위치만 남기고 메시지를 버린다.
    // 컨텍스트 로딩 실패처럼 원인이 중첩된 경우 무엇이 틀렸는지 알 수 없어 전문을 남긴다.
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
        events("failed")
    }
}
