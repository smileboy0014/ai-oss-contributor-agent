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

    // LLM 호출. 능력 인터페이스가 agent/domain 에 있어 어댑터 한 장만 갈아끼우면 되므로
    // SDK 를 써도 갇히지 않는다 — 이것이 Q-1(직접 구현)과 다른 결론을 낸 진짜 근거다
    implementation(libs.anthropic.java)

    // 샌드박스 — S-3. docker CLI 를 ProcessBuilder 로 부르지 않는다(훅이 막고, 인자 조립은
    // 주입면이 넓다). 데몬 API 를 직접 부른다 — PLAN-17 §4
    implementation(libs.docker.java.core)
    implementation(libs.docker.java.transport.zerodep)

    // 🔴 docker-java-core 가 끌고 오는 전이 의존이 낡았다 — guava 19.0(2016) ·
    //    commons-compress 1.21. 둘 다 알려진 취약점이 있는 버전이라 올려 고정한다.
    //    의존성을 들이는 것과 그 전이 의존을 방치하는 것은 별개다.
    //    ⚠ docker-java 가 쓰는 것은 두 라이브러리의 기본 API 뿐이다. 어긋나면 빌드가 아니라
    //      런타임 NoSuchMethodError 로 나타나므로, 버전을 움직일 때는 어댑터 테스트가
    //      실제로 그 경로를 타는지 확인한 뒤에 한다
    constraints {
        implementation("com.google.guava:guava:" + libs.versions.guava.get()) {
            because("전이 버전 19.0 은 2016년 판이다 — CVE-2018-10237 · CVE-2020-8908")
        }
        implementation("org.apache.commons:commons-compress:" + libs.versions.commonsCompress.get()) {
            because("전이 버전 1.21 은 CVE-2024-25710 · CVE-2024-26308 대상이다")
        }
    }

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
    //    LLM 어댑터는 사정이 한 겹 더하다 — Anthropic SDK 는 자체 HTTP 스택이라
    //    MockRestServiceServer 가 바인딩할 대상 자체가 없다. 중간 층은 SDK 가 공개하는
    //    ClientOptions.httpClient 주입점에 스텁을 꽂아 대신한다 (StubHttpClient)
    testImplementation(libs.wiremock.standalone)

    // 🔴 구조 규칙을 테스트로 고정한다 — 「selectedAt 을 쓰는 메서드가 selectByHuman 하나뿐」
    //    (#24 · S-6 불변식 ②). 손으로 메서드를 열거하면 새 메서드가 추가될 때 조용히 빠지고,
    //    소스 텍스트 스캔은 주석·문자열·리네임에 뚫린 줄 모른 채 초록이 된다.
    //    ⚠ 규칙에는 반드시 양성 대조를 붙인다 — 이름 하나만 바뀌어도 규칙은 0건을 검사하고
    //      초록이 된다 (ExternalAdapterIsolationTest 가 같은 이유로 미끼를 둔다)
    testImplementation(libs.archunit.junit5)

    // 선언하지 않으면 Gradle 이 자기 버전의 launcher 를 끼워 넣어 BOM 이 관리하는 engine 과 어긋난다.
    // 증상이 「테스트를 못 찾는다」로 나와 원인을 짚기 어렵다.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // 🔴 소스 밖 파일을 읽는 테스트는 그 파일을 입력으로 선언해야 다시 돈다.
    //   SecretPatternDriftTest 가 .claude/scripts 를 런타임에 읽는데 Gradle 은 모른다 —
    //   스크립트만 고치면 이 태스크가 UP-TO-DATE 로 건너뛰어진다.
    //
    //   실제로 그랬다(#58). 커밋 차단 패턴을 고쳐 놓고 테스트를 돌렸는데 초록이었다.
    //   --rerun-tasks 로 강제하니 그제서야 빨개졌다. 가드가 있는데 돌지 않는 것은
    //   가드가 없는 것과 같고, 「초록이었다」가 「검증했다」로 읽힌다는 점에서 더 나쁘다.
    //
    //   🕳 여기서 닫는 것은 .claude/scripts 뿐이다. PromptBoundaryTest 는 src/main 을
    //   텍스트로 읽는데 그쪽은 선언하지 않았다 — 선언하면 주석 한 줄만 고쳐도
    //   Testcontainers 포함 전 스위트가 다시 돈다. 남는 구멍은 「바이트코드를 바꾸지 않는
    //   소스 변경」뿐이고(주석·공백), 그 검사가 보는 것은 import 와 log 호출이라
    //   주석만으로 위반이 생기지 않는다. 비용/위험을 저울질해 두고 가는 것이지
    //   닫았다고 말하지 않는다.
    //
    //   ⚠ 이 선언은 모든 Test 태스크에 붙는다. 훅 스크립트 하나만 고쳐도 전 스위트가
    //   다시 돈다 — 게이트가 조용히 꺼지는 것보다 낫다고 보고 감수한다.
    inputs.files(fileTree("$rootDir/.claude/scripts") { include("**/*.sh") })
            .withPropertyName("harnessScripts")
            .withPathSensitivity(PathSensitivity.RELATIVE)

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
