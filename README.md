# AI OSS Contributor Agent

Java/Spring 오픈소스의 이슈를 탐색하고, 사람이 최종 승인하기 전까지 기여 준비 과정을 자동화하는 Spring Boot 애플리케이션입니다.

## 현재 MVP 골격

- OSS 저장소 등록 및 조회: `POST/GET /api/repositories`
- 스캔 작업 요청 경계: `POST /api/repositories/{id}/scan`
- 기여 후보 조회 API: `GET /api/candidates` (상태·난이도·신뢰도 필터 + 페이지네이션) · `GET /api/candidates/{id}`
- PostgreSQL/Redis 로컬 개발 환경과 H2 기본 프로필
- `DISCOVERED`부터 `PR_CREATED`까지의 후보 상태 모델
- **GitHub 읽기 능력**: 저장소 메타데이터·파일 조회(`RepositorySource`), open 이슈 조회(`IssueSource`).
  타임아웃·재시도를 명시하고, 403을 **권한 오류 / 1차 레이트리밋 / 2차 레이트리밋**으로 구분합니다.
  쓰기 메서드는 존재하지 않습니다 — 원본 저장소로 가는 경로는 읽기뿐입니다(S-1)
- **LLM 능력**: 4개 호출 지점이 공유하는 `LanguageModel`. 송신 직전 시크릿 스크럽이 강제되고,
  호출마다 토큰이 `AgentRun` 에 기록됩니다(S-4)
- **기여 규약 분석**: `CONTRIBUTING`·`AGENTS.md` 등 후보 경로를 수집해 **AI 기여 허용 여부**를 판정합니다.
  🔴 **「읽지 못함」은 「규약 없음」이 아니라 「보류」**입니다 — 판정 불가를 통과로 처리하면 S-5가 무너집니다

구현 순서는 Issue Scanner → 정책/이슈 분석 → Candidate 영속화 → Sandbox 기반 구현·검증 → 사용자 Fork의 Draft PR 생성입니다. 원본 저장소 직접 push와 자동 merge는 지원하지 않습니다.

> ⚠️ 샌드박스 실행·PR 생성은 **아직 구현되지 않았습니다.** `scan`은 요청 사실만 기록합니다.
> 후보 조회 API 는 동작하지만 **후보를 만드는 경로(#11)가 없어 결과는 아직 비어 있습니다.**

## 시작하기

```bash
git config core.hooksPath .githooks   # 커밋 훅 등록 — 클론 후 1회, 필수

docker compose up -d

DATABASE_URL=jdbc:postgresql://localhost:5432/oss_agent \
DATABASE_USERNAME=oss_agent DATABASE_PASSWORD=oss_agent \
./gradlew bootRun
```

⚠️ `core.hooksPath` 는 **커밋되지 않는 로컬 설정**입니다. 등록하지 않으면 시크릿·안전 경계 검사가
커밋 시점에 돌지 않습니다. 잊더라도 CI 가 같은 검사를 다시 돌리지만, **유출은 커밋 전에 막아야
회수가 가능합니다.**

환경변수를 주지 않으면 외부 서비스 없이 H2 in-memory로 기동합니다. Gradle은 별도 설치가 필요 없고 `./gradlew` 래퍼를 사용합니다 (JDK 21 필요).

```bash
./gradlew build     # 컴파일 + 테스트 + 패키징
./gradlew test      # 테스트만

curl -X POST http://localhost:8080/api/repositories \
  -H 'Content-Type: application/json' \
  -d '{"owner":"spring-projects","name":"spring-kafka","url":"https://github.com/spring-projects/spring-kafka"}'
```

## 구조

```text
├── build.gradle.kts           단일 Gradle 프로젝트
├── settings.gradle.kts
├── gradle/libs.versions.toml  의존성 버전 단일 관리
├── docker-compose.yml         postgres · redis
├── .env.example               환경변수 예시 (실제 값은 커밋 금지)
├── .githooks/pre-commit       커밋 차단 검사 2종 (core.hooksPath 로 등록)
├── .github/workflows/         CI — 안전 게이트 + build
├── docs/                      PRD · 구현 계획(plans/) 등 산출물
├── .claude/                   Claude Code 하네스 — 규칙 · 스킬 · 에이전트 · 훅
└── src/main/java/com/ossagent/
    ├── config/                조립 전용 (Clock · GitHub 클라이언트)
    ├── support/               도메인 없는 공통
    │   ├── web/               HTTP 예외 매핑
    │   ├── github/            GitHub 읽기 클라이언트 — 토큰 · 레이트리밋 · 403 구분
    │   └── secret/            토큰 마스킹 (S-4)
    ├── repository/            대상 저장소 등록 · 메타데이터/파일 조회 · 기여 규약 분석
    ├── issue/                 이슈 증분 수집 ✅ · 규칙 필터 ✅ (트리거 없음)
    ├── candidate/             기여 후보 · 상태 전이
    ├── agent/                 LLM 능력·어댑터 ✅ · Docker 샌드박스 ✅ (호출자 없음)
    └── pullrequest/           Fork · Draft PR            (경계만)
```

각 도메인 내부는 **헥사고날 라이트**로 `domain / application / adapter{in,out}` 3계층을 갖습니다. 상세는 [`.claude/docs/structure.md`](.claude/docs/structure.md).

## 안전 경계

이 프로젝트의 사고는 외부 OSS 커뮤니티로 직접 나갑니다. 아래 6가지는 예외 없이 지킵니다 — 상세는 [`.claude/rules/context/safety-boundaries.md`](.claude/rules/context/safety-boundaries.md).

| # | 규칙 |
|---|---|
| S-1 | 쓰기 대상은 **사용자 Fork 뿐**. 원본 저장소는 읽기만 |
| S-2 | PR은 **항상 draft**. 자동 머지·ready 전환·리뷰어 지정 금지 |
| S-3 | 대상 저장소 코드는 **샌드박스 밖에서 실행하지 않음** |
| S-4 | 시크릿은 코드·로그·**LLM 프롬프트** 어디에도 넣지 않음 |
| S-5 | 대상 저장소의 기여 규약이 우리 규약보다 우선 |
| S-6 | 사람의 승인 지점을 코드로 우회하지 않음 |

GitHub 토큰, LLM 키, sandbox 실행 권한은 애플리케이션 설정과 분리해 Secret Manager 또는 CI/CD 환경변수로 주입합니다. `.env`는 커밋 대상이 아닙니다. 시크릿 패턴과 안전 경계 위반은 **두 곳**에서 막습니다 — `git commit` 시점의 [`.githooks/pre-commit`](.githooks/pre-commit)(스테이징분)과, `--no-verify` 우회·훅 미등록까지 잡는 **CI**(추적 파일 전체).

GitHub 인증은 **classic PAT(`public_repo`)** 입니다. fine-grained PAT과 GitHub App 설치 토큰은 우리가 멤버가 아닌 upstream에 PR을 만들지 못해 사용할 수 없습니다 — 근거는 [`open-questions.md`](.claude/rules/context/open-questions.md) Q-1.

## 개발 규칙

| 문서 | 내용 |
|---|---|
| [`.claude/README.md`](.claude/README.md) | 하네스 전체 인덱스 |
| [`.claude/docs/setup.md`](.claude/docs/setup.md) | 초기 셋업 |
| [`.claude/docs/rules.md`](.claude/docs/rules.md) | 컨텍스트·컨벤션·코드맵 읽는 순서 |
| [`.claude/rules/context/open-questions.md`](.claude/rules/context/open-questions.md) | 미결 대장 — **착수 전 확인** |
| [`docs/ai-oss-contributor-agent-prd.md`](docs/ai-oss-contributor-agent-prd.md) | PRD v1.1 (Draft) |
