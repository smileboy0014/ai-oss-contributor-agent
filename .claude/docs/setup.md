# 초기 셋업

## 1. 크리덴셜 분리

```bash
cp .claude/settings.json.example .claude/settings.local.json
```

`settings.local.json` 은 `.gitignore` 대상이다. `<REPLACE_WITH_SECRET_MANAGER>` 자리를 로컬에서만 채운다.

**하지 말 것**
- `.claude/settings.json` 에 토큰 넣기 — 커밋 대상이다
- 소스·테스트 픽스처에 토큰 하드코딩 — [`secret-scan.sh`](../scripts/secret-scan.sh) 가 커밋을 차단한다

## 2. 애플리케이션 환경변수

`.env.example` 을 복사해 `.env` 를 만든다. `.env` 는 `.gitignore` 대상이다.

```bash
cp .env.example .env
```

| 변수 | 없으면 |
|---|---|
| `DATABASE_URL` · `DATABASE_USERNAME` · `DATABASE_PASSWORD` | **H2 in-memory 로 기동한다** (`application.yml` 기본값) |
| `GITHUB_TOKEN` · `GITHUB_FORK_OWNER` | GitHub 연동 불가 (아직 미구현이라 기동은 된다) |
| `ANTHROPIC_API_KEY` | LLM 호출 불가 (아직 미구현) |
| `SANDBOX_*` | 샌드박스 실행 불가 (아직 미구현) |

⚠️ `GITHUB_TOKEN` 은 **classic PAT · 스코프 `public_repo`** 다 (Q-1 확정).

**fine-grained PAT 으로 바꾸지 않는다.** 더 안전해 보이지만, 우리가 멤버가 아닌 upstream 에
PR 을 만들지 못해 **PR 생성이 403 으로 죽는다** — [Q-1](../rules/context/open-questions.md).

classic PAT 은 저장소별 권한 제한이 불가능하다. 즉 **원본 write 를 권한으로 막을 수 없고,
push 직전 owner 어설션이 유일한 방어**다 — [S-1](../rules/context/safety-boundaries.md).
`GITHUB_FORK_OWNER` 를 반드시 채운다. 비어 있으면 어설션이 무력해진다.

**새 환경변수를 코드에 추가하면 `.env.example` 에 같은 커밋으로 반영한다.**

## 3. 빌드 환경

| 필요 | 확인 |
|---|---|
| JDK 21 | `java -version` |
| Gradle | **설치 불필요** — `./gradlew` 래퍼를 쓴다 |
| Docker | `docker --version` (로컬 DB · 향후 샌드박스) |
| `gh` CLI | `gh auth status` (PR·이슈 스킬이 의존) |

```bash
./gradlew build      # 컴파일 + 테스트 + 패키징
```

⚠️ `mvn` 은 쓰지 않는다. 2026-09-18 에 Maven → Gradle 로 전환했다.

## 4. 로컬 인프라

```bash
docker compose up -d       # postgres:17-alpine · redis:7-alpine
```

PostgreSQL 로 붙이려면:

```bash
DATABASE_URL=jdbc:postgresql://localhost:5432/oss_agent \
DATABASE_USERNAME=oss_agent DATABASE_PASSWORD=oss_agent \
./gradlew bootRun
```

붙이지 않으면 H2 로 뜬다. 스키마는 양쪽 다 **Flyway 마이그레이션**(`db/migration`)이 만들고
`ddl-auto` 는 `validate` 다 — 같은 SQL 한 벌이 양쪽에서 돈다 ([Q-2](../rules/context/open-questions.md)).

⚠️ 그래도 **H2 에서 됐다고 PostgreSQL 에서 된다고 보지 않는다.** 같은 SQL 을 쓰더라도 H2 는
PostgreSQL 모드 흉내일 뿐이다. 마이그레이션을 추가했으면 **양쪽에서 한 번씩 띄워 본다** ([Q-2b](../rules/context/open-questions.md)).

```bash
./gradlew build                       # H2 로 검증 (테스트가 Flyway 를 돌린다)
docker compose up -d                  # PostgreSQL 로 검증
DATABASE_URL=jdbc:postgresql://localhost:5432/oss_agent \
DATABASE_USERNAME=oss_agent DATABASE_PASSWORD=oss_agent ./gradlew bootRun
```

Redis 는 `docker-compose.yml` 에만 있고 애플리케이션이 아직 쓰지 않는다.

## 5. 확인

```bash
./gradlew bootRun &
curl -s localhost:8080/actuator/health
curl -s -X POST localhost:8080/api/repositories \
  -H 'Content-Type: application/json' \
  -d '{"owner":"spring-projects","name":"spring-kafka","url":"https://github.com/spring-projects/spring-kafka"}'
curl -s localhost:8080/api/repositories
```

`GET /api/candidates` 는 **빈 배열 고정**이고 `POST /api/repositories/{id}/scan` 은
**요청 사실만 기록**한다. 아직 구현이 없는 것이지 고장난 것이 아니다 —
[`project-overview.md`](../rules/context/project-overview.md) 「지금 어디까지 와 있나」.

## 6. MCP (선택)

`settings.json.example` 에 GitHub MCP 서버 설정이 들어 있다. Docker 로 뜬다.

```
"GITHUB_PERSONAL_ACCESS_TOKEN": "<REPLACE_WITH_SECRET_MANAGER>"
```

Claude Code 실행 후 `/mcp` 로 연결을 확인한다. MCP 없이도 `gh` CLI 로 대부분 된다.
