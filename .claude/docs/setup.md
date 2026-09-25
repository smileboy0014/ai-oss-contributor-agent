# 초기 셋업

## 1. 크리덴셜 분리

```bash
cp .claude/settings.json.example .claude/settings.local.json
```

`settings.local.json` 은 `.gitignore` 대상이다. `<REPLACE_WITH_SECRET_MANAGER>` 자리를 로컬에서만 채운다.

**하지 말 것**
- `.claude/settings.json` 에 토큰 넣기 — 커밋 대상이다
- 소스·테스트 픽스처에 토큰 하드코딩 — [`secret-scan.sh`](../scripts/secret-scan.sh) 가 커밋을 차단한다

### gh 활성 계정 — 이 저장소는 `smileboy0014` 로 작업한다

저장소 소유자가 `smileboy0014` 다. 다른 계정(회사 계정 등)이 활성 상태면 **push 가
`403 Permission denied` 로 죽는다.** 작업 전 확인하고 전환한다.

```bash
gh auth status                       # 활성 계정 확인
gh auth switch --user smileboy0014   # 전환 후 작업
```

⚠️ **저장소 한정 우회는 없다.** `git config --local credential.https://github.com.username` 을
걸어도 gh 크리덴셜 헬퍼가 비활성 계정의 토큰을 내주지 않아 `could not read Password` 로 실패한다.
전역 `gh auth switch` 가 유일한 방법이고, 전역이므로 **다른 저장소 작업에도 영향**을 준다.

403 을 토큰 스코프 문제로 오진하지 않는다 — 스코프(`repo`)는 멀쩡한데 **계정이 다른 것**이다.

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
| JDK 21 | `java -version`. **기본 JDK 가 더 높아도 된다** — Gradle 이 알아서 21 을 고른다(아래) |
| Gradle | **설치 불필요** — `./gradlew` 래퍼를 쓴다 |
| Docker | `docker --version` (로컬 DB · 향후 샌드박스) |
| `gh` CLI | `gh auth status` (PR·이슈 스킬이 의존) |

```bash
./gradlew build      # 컴파일 + 테스트 + 패키징
```

⚠️ `mvn` 은 쓰지 않는다. 2026-09-18 에 Maven → Gradle 로 전환했다.

#### 🟢 데몬 JVM 이 고정돼 있다 — `JAVA_HOME` 을 만질 필요 없다

`gradle/gradle-daemon-jvm.properties` 의 `toolchainVersion=21` 이 **Gradle 데몬이 돌 JVM** 을 정한다.
기본 JDK 가 25 여도 `./gradlew build` 가 그냥 돈다.

**왜 이 파일이 필요한가** — `build.gradle.kts` 의 toolchain 21 은 **컴파일 대상**만 정하고,
**Gradle 자신은 기본 JVM 위에서 돈다.** 그래서 이 파일이 없으면 Gradle 8.14.3 이 JDK 25 에서
아래처럼 죽었다. 버전 번호만 덜렁 나와 원인이 보이지 않는 자리다.

```
FAILURE: Build failed with an exception.
* What went wrong:
25.0.4.1
```

2026-09-25 에 실제로 막혔고(#38 작업 중), #37 이 이 파일을 추가해 해결했다.
같은 날 `JAVA_HOME` 오버라이드 없이 `exit=0` 으로 재확인했다.

⚠️ **이 파일을 지우면 증상이 돌아온다.** `java -version` 은 「JDK 있음」으로 통과하므로
위 표만 보고는 걸러지지 않는다.

### 커밋 훅 등록 — 클론 후 1회, 필수

```bash
git config core.hooksPath .githooks
git config --get core.hooksPath          # .githooks 가 나와야 한다
```

`.githooks/pre-commit` 이 커밋 시점에 [`secret-scan.sh`](../scripts/secret-scan.sh) ·
[`safety-boundary-check.sh`](../scripts/safety-boundary-check.sh) 를 돌린다.

⚠️ **`core.hooksPath` 는 커밋되지 않는 로컬 설정이다.** 새 클론마다 다시 쳐야 한다.
등록을 잊으면 검사가 통째로 빠지는데 **조용히** 빠진다 — 이 함정에 이미 한 번 걸렸다
([`../rules/conventions/commit-convention.md`](../rules/conventions/commit-convention.md)).

설정 자체는 `.git/config` 에 있어 **worktree 전체가 공유**하므로 worktree 마다 다시 칠 필요는 없다.
다만 **`.githooks/` 디렉토리가 그 worktree 체크아웃에 있어야 한다.** 이 디렉토리가 들어오기 전에
만든 브랜치에서 작업 중이라면 훅이 **오류 없이 건너뛰어진다** — git 은 훅 파일이 없으면 조용히 넘어간다.
`main` 을 머지·리베이스하면 해결된다.

잊더라도 CI 가 같은 검사를 `SCAN_MODE=tree` 로 다시 돌린다. 다만 **유출은 커밋 전에 막아야
회수가 가능하다** — CI 에서 걸리면 이미 원격에 올라간 뒤다.

수동으로 돌려볼 때:

```bash
.claude/scripts/secret-scan.sh                    # 스테이징분
SCAN_MODE=tree .claude/scripts/secret-scan.sh     # 추적 파일 전체 (CI 와 동일)
```

`./gradlew check` 는 훅에 없다. **CI 가 게이트다** — [`open-questions.md`](../rules/context/open-questions.md) Q-10.

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
