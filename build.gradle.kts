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

    // 🔴 전송 계약(읽기 타임아웃·리다이렉트 거부·연결 실패)은 실제 소켓이 아니면 검증되지 않는다.
    //    MockRestServiceServer 는 ClientHttpRequestFactory 를 통째로 갈아끼워 JDK HttpClient 가
    //    아예 돌지 않으므로, 그 층에서는 「설정값이 프로퍼티에 있다」까지만 확인된다 — Q-9 3계층
    testImplementation(libs.wiremock.standalone)

    // 선언하지 않으면 Gradle 이 자기 버전의 launcher 를 끼워 넣어 BOM 이 관리하는 engine 과 어긋난다.
    // 증상이 「테스트를 못 찾는다」로 나와 원인을 짚기 어렵다.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // Testcontainers 가 물고 오는 docker-java 는 API 버전을 협상하지 않고 기본값(v1.32)으로
    // 요청하는데, 최신 Docker 엔진이 이를 400 으로 거부한다. 증상이
    // 「Could not find a valid Docker environment」라 Docker 가 안 떠 있는 것처럼 보이지만
    // 실제로는 API 버전 거부다 (이 엔진은 v1.41+ 만 받는다).
    //
    // ⚠ DOCKER_API_VERSION 환경변수로는 고쳐지지 않는다. docker-java 는 시스템 프로퍼티의
    //   점 표기(api.version)를 읽는다. 환경변수만 주면 전략이 전부 400 으로 떨어진다
    systemProperty("api.version", "1.44")

    // 기본 출력은 예외 클래스와 발생 위치만 남기고 메시지를 버린다.
    // 컨텍스트 로딩 실패처럼 원인이 중첩된 경우 무엇이 틀렸는지 알 수 없어 전문을 남긴다.
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
        events("failed")
    }
}
