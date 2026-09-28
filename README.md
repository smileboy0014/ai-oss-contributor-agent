# AI OSS Contributor Agent

Java/Spring 오픈소스의 이슈를 탐색하고, 사람이 최종 승인하기 전까지 기여 준비 과정을 자동화하는 Spring Boot 애플리케이션입니다.

## 현재 상태 (2026-09-28)

파이프라인 **전 단계가 코드로 있습니다.** 아직 없는 것은 `spring-kafka` 를 상대로 끝까지 돌린 **실측 기록**입니다.

- OSS 저장소 등록 및 조회: `POST/GET /api/repositories`
- **스캔 파이프라인**: `POST /api/repositories/{id}/scan` → `202` · `GET /api/repositories/{id}/scan` 진행 조회.
  규약 분석 → 이슈 증분 수집 → 규칙 필터 → LLM 분석까지 잇고 🔴 **`ANALYZED` 에서 멈춥니다**(S-6).
  정기 스캔은 기본 꺼짐(`SCAN_SCHEDULE_ENABLED`)
- 기여 후보 조회 API: `GET /api/candidates` (상태·난이도·신뢰도 필터 + 페이지네이션) · `GET /api/candidates/{id}`
- **사람의 승인 지점 셋**(S-6): `POST /api/candidates/{id}/select`(선정) · `…/implement`(착수) ·
  `…/pull-request`(Draft PR 생성). 취소는 `…/reject`, 규약 보류 해소는 `POST /api/repositories/{id}/policy/resolution`.
  규약 문서가 침묵하는 빌드·테스트 명령은 사람이 `POST /api/repositories/{id}/policy/commands` 로 넣고(#102),
  저장소별 스캔 주기는 `PATCH /api/repositories/{id}/scan-interval` 로 정합니다(#108).
  🔴 **스케줄러·워커가 이 선을 넘지 않습니다** — ArchUnit 이 「web 어댑터만 부른다」를 고정합니다
- **구현 루프**: 계획 → 코딩 → 샌드박스 검증(컴파일·테스트·diff) → AI 리뷰를 **최대 3바퀴**. 상한 소진은 `FAILED`
- **Fork push · Draft PR**: 게이트 뒤에서 upstream 을 재clone 해 저장된 diff 를 입히고 **사용자 Fork 에만** push 한 뒤
  **draft** PR 을 엽니다(S-1 · S-2). 원본 저장소 직접 push 와 자동 merge 는 없습니다
- **GitHub 접근**: 읽기(`GitHubApiClient`)와 쓰기(`GitHubWriteClient`, 저장소 전체에서 하나)가 갈려 있고,
  쓰기는 매 호출 직전 Fork owner 를 단언합니다. 403 을 **권한 오류 / 1차 / 2차 레이트리밋**으로 구분하고
  레이트리밋은 실패가 아니라 **지연**(503 + `Retry-After`)입니다
- **LLM 능력**: 다섯 호출 지점이 공유하는 `LanguageModel`. 송신 직전 시크릿 스크럽이 강제되고,
  호출마다 토큰·비용이 `AgentRun`·메트릭에 기록됩니다(S-4)
- **기여 규약 분석**: `CONTRIBUTING`·`AGENTS.md` 등 후보 경로를 수집해 **AI 기여 허용 여부**를 판정합니다.
  🔴 **「읽지 못함」은 「규약 없음」이 아니라 「보류」**입니다 — 판정 불가를 통과로 처리하면 S-5가 무너집니다
- PostgreSQL/Redis 로컬 개발 환경과 H2 기본 프로필. Redis 는 아직 애플리케이션이 쓰지 않습니다

> ⚠️ 미결: `web`/`worker` 프로필 분리·잡 큐(Q-3), Maven 대상·네트워크를 요구하는 테스트(Q-4). 분산 락은 #26 이 DB 로 풀었습니다.
> 위 ✅ 는 전부 대역(페이크·WireMock·Testcontainers)으로 검증된 것이고, 실 GitHub·LLM·샌드박스를 이어 돌린
> End-to-End 는 아직 한 번도 없습니다.

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

### 전부 컨테이너로 띄우기

앱까지 컨테이너로 돌리려면 `app` 프로필을 켭니다. 시크릿은 `.env` 에서 읽습니다 — `.env.example` 을 복사해 채우세요.

```bash
cp .env.example .env                        # GITHUB_TOKEN · ANTHROPIC_API_KEY · GITHUB_FORK_OWNER 등
docker compose --profile app up --build     # postgres + redis + app (:8080)
```

- 앱 컨테이너에 **호스트 Docker 소켓**이 마운트됩니다. 앱이 그 통로로 샌드박스 컨테이너를 만듭니다.
  샌드박스 컨테이너 자체에는 소켓이 들어가지 않습니다(S-3) — 바인드 목록을 코드가 워크스페이스·캐시 볼륨 둘로 고정합니다.
- `SANDBOX_WORKSPACE_ROOT` 는 **호스트와 컨테이너에서 같은 절대경로**여야 합니다. 앱이 넘기는 바인드 소스를 데몬이 호스트 경로로 해석하기 때문입니다.
  기본값은 `$PWD/build/sandbox-workspaces` 이고 compose 가 같은 경로로 마운트합니다.
- 샌드박스 이미지(`oss-agent-sandbox:<java>`)는 앱이 pull 도 build 도 하지 않습니다. `docker/sandbox/build.sh` 로 미리 빌드해 두세요.
  stock `eclipse-temurin` 에는 `git` 이 없어 검증의 DIFF 단계가 컨테이너 기동에서 죽습니다(#97) — 그래서 우리 이미지입니다.
- 이미지 빌드는 테스트를 돌리지 않습니다(`-x test`). 테스트 게이트는 CI 입니다.

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
├── docker-compose.yml         postgres · redis · app(`--profile app`, 로컬 전체 기동)
├── Dockerfile                 앱 이미지 — 로컬 compose 전용 · 테스트 미실행
├── docker/sandbox/            샌드박스 이미지(oss-agent-sandbox:<java>) — temurin + git · build.sh 로 미리 빌드 (#97)
├── .dockerignore
├── .env.example               환경변수 예시 (실제 값은 커밋 금지)
├── .githooks/pre-commit       커밋 차단 검사 2종 (core.hooksPath 로 등록)
├── .github/workflows/         CI — 안전 게이트 + build
├── docs/                      PRD · 구현 계획(plans/) 등 산출물
├── .claude/                   Claude Code 하네스 — 규칙 · 스킬 · 에이전트 · 훅
└── src/main/java/com/ossagent/
    ├── config/                조립 전용 (Clock · GitHub · LLM · 샌드박스 · 스케줄링 · 비동기 풀)
    ├── support/               도메인 없는 공통
    │   ├── web/               HTTP 예외 매핑 — 레이트리밋 → 503 + Retry-After
    │   ├── github/            GitHub 읽기 클라이언트 — 토큰 · 레이트리밋 예산(읽기·쓰기 공유) · 403 구분
    │   ├── secret/            토큰 마스킹 · 시크릿 경로 배제 (S-4)
    │   └── observability/     파이프라인 메트릭 — 태그를 만드는 유일한 지점
    ├── repository/            등록 · 규약 분석·문서 지문 · 스캔 파이프라인 · 스케줄러(저장소별 주기 · DB 분산 락 #26) · 컨텍스트 축소
    ├── issue/                 이슈 증분 수집 · 3상태 규칙 필터
    ├── candidate/             후보 상태머신 · 승인 게이트 셋 · 계획 · 구현 루프 · 검증 · 리뷰 · PR 생성 UseCase
    │   └── adapter/out/notification/  새 후보 알림 — 🔴 로그·메트릭뿐이다 (외부 전송 아님)
    ├── agent/                 LLM 능력·어댑터 · Docker 샌드박스 · 워크스페이스(JGit, push 없음)
    └── pullrequest/           Fork 확보 · 동기화 · commit · push (유일한 쓰기 클라이언트) · Draft PR
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
| [`docs/ai-oss-contributor-agent-prd.md`](docs/ai-oss-contributor-agent-prd.md) | PRD v1.2 (Draft) |
