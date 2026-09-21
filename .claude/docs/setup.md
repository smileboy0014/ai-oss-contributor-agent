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

⚠️ `GITHUB_TOKEN` 에 **원본 저장소 write 권한을 주지 않는다** — [S-1](../rules/context/safety-boundaries.md).
권한을 처음부터 주지 않는 것이 코드로 막는 것보다 확실하다.

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

붙이지 않으면 H2 로 뜬다. 두 경우 스키마 생성 방식이 다르므로
(`ddl-auto: update`, [Q-2](../rules/context/open-questions.md)) **H2 에서 됐다고 PostgreSQL 에서 된다고 보지 않는다.**

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
