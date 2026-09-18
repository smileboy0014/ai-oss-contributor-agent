# AI OSS Contributor Agent

Java/Spring 오픈소스의 이슈를 탐색하고, 사람이 최종 승인하기 전까지 기여 준비 과정을 자동화하는 Spring Boot 애플리케이션입니다.

## 현재 MVP 골격

- OSS 저장소 등록 및 조회: `POST/GET /api/repositories`
- 스캔 작업 요청 경계: `POST /api/repositories/{id}/scan`
- 기여 후보 조회 API 경계: `GET /api/candidates`
- PostgreSQL/Redis 로컬 개발 환경과 H2 기본 프로필
- `DISCOVERED`부터 `PR_CREATED`까지의 후보 상태 모델

구현 순서는 Issue Scanner → 정책/이슈 분석 → Candidate 영속화 → Sandbox 기반 구현·검증 → 사용자 Fork의 Draft PR 생성입니다. 원본 저장소 직접 push와 자동 merge는 지원하지 않습니다.

## 시작하기

```bash
docker compose up -d
DATABASE_URL=jdbc:postgresql://localhost:5432/oss_agent \\
DATABASE_USERNAME=oss_agent DATABASE_PASSWORD=oss_agent \\
./mvnw spring-boot:run
```

Maven Wrapper를 아직 포함하지 않았다면, Maven 설치 후 `mvn spring-boot:run`을 사용합니다. 기본값은 외부 서비스 없이 실행되는 H2 in-memory 데이터베이스입니다.

```bash
mvn test
curl -X POST http://localhost:8080/api/repositories \\
  -H 'Content-Type: application/json' \\
  -d '{"owner":"spring-projects","name":"spring-kafka","url":"https://github.com/spring-projects/spring-kafka"}'
```

## 구조

```text
repository/  등록 저장소와 scan 진입점
issue/       GitHub 이슈 수집·정규화 (다음 단계)
candidate/   기여 후보와 상태 전이
job/         재시도 가능한 비동기 작업
```

GitHub App 권한, LLM 키, sandbox 실행 권한은 애플리케이션 설정과 분리해 Secret Manager 또는 CI/CD 환경변수로 주입합니다.
