# 외부 의존

> 전부 **경계 밖**이다. 우리 트랜잭션 안에서 호출하지 않는다 — 실패·지연이 우리 DB 락을 잡는다.

## GitHub API

| 항목 | 내용 |
|---|---|
| 용도 | 저장소 메타데이터 · open 이슈 조회 · 기여 규약 파일 조회 · Fork 생성 · push · Draft PR 생성 |
| 인증 | **classic PAT** (`GITHUB_TOKEN`) · 스코프 `public_repo` — Q-1 확정 (2026-09-21) |
| 클라이언트 | **Spring `RestClient` 직접 구현** — Q-1 확정. 라이브러리를 쓰지 않는다 |
| **권한** | 원본은 **읽기만**. 쓰기는 사용자 Fork 에 한정 — [`safety-boundaries.md`](./safety-boundaries.md) S-1 |
| 레이트리밋 | 인증 5,000 req/h. Search API 는 별도(30 req/min) — **스캐너가 가장 먼저 부딪힌다** |

⚠️ **fine-grained PAT 과 GitHub App 설치 토큰은 쓸 수 없다.** 둘 다 우리가 멤버가 아닌
upstream 에 PR 을 만들지 못한다 — 근거와 표는 [`open-questions.md`](./open-questions.md) Q-1.
토큰을 교체할 때 「더 안전해 보인다」는 이유로 fine-grained 로 바꾸면 **PR 생성이 403 으로 죽는다.**

⚠️ classic PAT 은 **저장소별 권한 제한이 불가능**하다. 원본 write 를 권한으로 막을 수 없으므로
**push 직전 owner 어설션이 유일한 방어**다 (S-1).

**설계 제약**
- 이슈 수집은 `updated_at` 커서 + `ETag` 조건부 요청으로 증분화한다. 매 스캔 전량 조회는 레이트리밋을 태운다
- `X-RateLimit-Remaining` 이 임계 미만이면 **작업을 실패시키지 말고 지연**시킨다. 리밋 소진은 정상 운영 상황이다.
  🔴 **판정은 어댑터가 한다** (#8) — `GitHubApiClient` 가 `github.rate-limit-threshold` 미만이면
  **호출하지 않고** `GitHubRateLimitException(PRIMARY)` 을 던진다. 소진된 뒤가 아니라 **임계 미만이
  되는 순간부터**다. 남은 예산을 끝까지 태우면 같은 토큰을 쓰는 다른 작업(규약 수집 · 코드 검색)이 전부 막힌다.
  레이트리밋은 GitHub 의 개념이므로 **도메인 계약(`IssuePage` 등)에 싣지 않는다** — 규율 ①.
  ⚠️ 던지는 자리는 **다음 호출 진입부**다. 응답을 받은 뒤에 던지면 **이미 지불한 호출의 결과를 버린다**
- 2차 레이트리밋(abuse detection)은 429 가 아니라 403 으로 온다. 403 을 권한 오류로만 처리하면 무한 재시도에 빠진다
- 🔴 **읽기 타임아웃은 두 갈래로 온다.** 보통은 `ResourceAccessException` 이지만, 취소가 레이스를
  이기면 **`CancellationException` 이 맨몸으로** 올라온다. 그것은 `RestClientException` 계열이 아니라
  catch 를 전부 빠져나가고, 그러면 재시도 정책이 보지 못해 **「타임아웃인데 재시도 안 됨」**이 된다 —
  아래 함정 기록

### ⚠️ Spring 이 `CancellationException` 을 번역하지 않는다 — 함정 기록 (2026-09-25)

부하가 걸릴 때만 재현돼 **flaky 테스트로 오인하기 쉽다.** 실제로는 운영 코드 결함이다.

`JdkClientHttpRequest.executeInternal`(spring-web 6.2.7)은 `ExecutionException` 에 **감싸져 온**
취소만 `HttpTimeoutException` 으로 바꾼다.

```java
catch (ExecutionException ex) {
    if (cause instanceof CancellationException) throw new HttpTimeoutException(...);   // ← 이 경로만
```

그런데 `TimeoutHandler` 가 레이스를 이겨 future 가 **이미 취소된 뒤** `get()` 이 불리면
`CompletableFuture.get()` 이 `CancellationException` 을 **직접** 던지고, 그 분기에 걸리지 않는다.
CPU 가 바쁠수록 핸들러가 자주 이긴다.

| 영향 | |
|---|---|
| 재시도 | `GitHubRetryPolicy` 가 `GitHubApiException` 만 잡는다 → **재시도 안 됨** |
| 예외 매핑 | 타입 없는 예외가 `support/web` 으로 올라간다 |
| 규약 파싱 | 「못 읽음」 분류를 예외 타입에 거는 쪽이 오분류한다 — #7 이 그래서 **fail-closed**(`RuntimeException` 전부를 「못 읽음」)로 갔다 |

**우리가 방어한다** — `GitHubApiClient` 가 `CancellationException` 을 잡아
`GitHubTransientException` 으로 번역한다. 새 어댑터를 만들 때 **같은 catch 를 빠뜨리지 않는다.**

## LLM API

| 항목 | 내용 |
|---|---|
| 용도 | 이슈 분석 · 구현 계획 · 코드 생성 · diff 리뷰 (PRD §6.1 의 4개 지점) — **`ANALYZE` 는 실제로 배선됐다**(#11), 나머지 셋은 미구현 |
| 인증 | `ANTHROPIC_API_KEY` |
| 모델 | `ANTHROPIC_MODEL` — 기본 `claude-sonnet-5` |
| 클라이언트 | **공식 `com.anthropic:anthropic-java` SDK** — #10 확정 (2026-09-25) |

⚠️ **GitHub 과 결론이 다르다.** Q-1 은 직접 구현(`RestClient`)을 택했지만 그 근거 3개
(ETag 커서 직접 제어 · 403 으로 오는 2차 리밋 구분 · 라이브러리가 1년 넘게 RC)가
**LLM 에는 하나도 성립하지 않는다.** 채택 근거는 「SDK 가 편해서」가 아니라
**능력 인터페이스가 `agent/domain` 에 있어 어댑터 한 장만 갈아끼우면 된다**는 것이다.
**Q-1 을 모든 대외 의존에 일반화하지 않는다** — 경계마다 근거를 다시 본다 (Q-11).

⚠️ **SDK 의 `fromEnv()` 를 쓰지 않는다.** `ANTHROPIC_BASE_URL`·`ANTHROPIC_AUTH_TOKEN` 까지
함께 읽어 **환경변수 하나로 프롬프트 송신 대상 호스트가 바뀔 수 있다.** 키만 환경에서 받고
목적지(`agent.llm.base-url`)는 설정으로 고정한다 — S-4.

**설계 제약**
- **저장소 전체를 넘기지 않는다** (PRD §12). 키워드 → 코드 검색 → 관련 파일로 단계적으로 좁힌다
- 프롬프트에 넣기 전 시크릿 스크럽 — S-4. 이 경로가 가장 흔한 유출구다.
  `PromptScrubber` 를 **송신 직전 반드시** 통과시킨다. 구현은 `TokenRedactor` 에 위임하고 두 벌을 두지 않는다
- 호출마다 `AgentRun` 에 입출력 토큰을 기록한다. 비용이 보이지 않으면 재시도 루프가 조용히 돈을 태운다.
  **실패도 기록한다** — 타임아웃으로 끊긴 호출도 모델은 이미 토큰을 생성했다
- 출력은 **신뢰하지 않는다.** 계획은 검증하고, 코드는 빌드·테스트로 거른다. 모델 응답을 그대로 진실로 쓰는 경로를 만들지 않는다.
  **상한 절단·거부는 성공이 아니라 예외다** — 잘린 JSON 을 소비자가 파싱하게 두지 않는다
- 타임아웃·재시도를 어댑터에서 명시한다. **SDK 내장 재시도는 끈다**(`maxRetries(0)`) — 맡기면 몇 번 재전송했는지 관측할 수 없다

**재시도 상한은 두 축이다** — 섞으면 후보가 코드 문제 없이 `FAILED` 로 떨어진다.

| 축 | 설정 키 | 무엇을 세나 |
|---|---|---|
| **전송 계층** | `agent.llm.max-retries` (2) | 429 · 5xx · 연결 실패 · 타임아웃. `github.max-retries` 와 같은 성격 |
| **파이프라인** | `agent.execution.max-retries` (3) | `CODE → VERIFY → REVIEW` 한 바퀴 — Q-6 확정. `AgentRun.attempt` 에 기록 |

⚠️ 절단(`TRUNCATED`)은 **전송 재시도 대상이 아니다.** 같은 상한으로 재전송하면 같은 지점에서
잘려 입력 토큰만 배로 태운다. 전송 실패가 아니라 출력이 예산을 넘은 것이고, 고칠 주체는 호출자다.

## Docker 샌드박스

| 항목 | 내용 |
|---|---|
| 용도 | 대상 저장소 clone · build · test · lint |
| 이미지 | `SANDBOX_DOCKER_IMAGE` (기본 `eclipse-temurin:21-jdk`) |
| 제한 | 네트워크 `none` · CPU · 메모리 · 타임아웃 — S-3 |

| 클라이언트 | **docker-java** (`docker-java-core` + `transport-zerodep`) — #17 확정 (2026-09-26) |

⚠️ **`ProcessBuilder` 로 docker CLI 를 부르지 않는다.** `safety-boundary-check.sh` 가 막는
경로이고, S-3 의 실행체를 만들면서 S-3 가드를 `safety-ok` 로 우회하는 것은 앞뒤가 맞지 않는다.
CLI 인자 조립은 주입면도 넓다.

⚠️ **Q-1 을 일반화한 것이 아니다**(Q-11). GitHub 은 직접 구현이지만 여기는 라이브러리다 —
경계마다 근거를 다시 본다. 결정적 근거는 `ExternalAdapters.NETWORK_CLIENTS` 가 이미
`DockerClient` 를 가정하고 있었다는 것이다.

**설계 제약**
- 대상 저장소마다 Java 버전·빌드 도구가 다르다. `RepositoryPolicy` 의 **값**으로 이미지·명령을
  고른다. 🔴 **엔티티를 import 하지 않는다**(규율 ④) — `agent` 와 `repository` 는 다른 애그리거트다
- 🔴 **`javaVersion` 이 그대로 이미지 좌표가 되면 안 된다.** LLM 이 대상 저장소 문서에서
  뽑은 값이라(#7), 조립하면 **공격자 레지스트리 이미지를 우리가 받아 실행**한다.
  화이트리스트로만 매핑한다. 캐시 볼륨 이름도 같다 — 경로 형태면 Docker 가 **볼륨이 아니라
  호스트 경로 바인드**로 해석한다
- 🔴 **명령은 argv 다. 쉘을 경유하지 않는다.** `buildCommand` 를 `sh -c "<문자열>"` 로 돌리면
  `;`·`&&`·`$(…)` 가 살고 `FOO=bar cmd` 로 환경변수까지 주입된다
- 네트워크를 끊으면 의존성 해석이 실패한다. **3단계**로 가른다 — 워밍(네트워크 O · 볼륨 미마운트)
  → 씨딩(우리 `cp` · 네트워크 X) → 실행(네트워크 X · 캐시 RO). Q-4 참조
- 컨테이너는 실행마다 새로 만들고 끝나면 지운다. 상태를 재사용하면 앞 실행의 산출물이 다음
  판정을 오염시킨다. **의존성 캐시만 예외**이고, 그래서 실행 단계에서 **읽기전용**이다 —
  읽기전용이면 오염 경로가 닫힌다
- **정리 실패를 조용히 넘기지 않는다.** `SandboxResult.cleanedUp` 으로 보고하고 `WARN` 을
  남긴다. 넘기면 컨테이너가 쌓이고, 그것은 시간이 지나야 드러나는 고장이다
- **이미지를 우리가 pull 하지 않는다.** 데몬 자격증명이 관여하는 행위라 운영이 미리 받아 둔다.
  없으면 `SandboxTransientException` — 준비되면 같은 요청이 성공한다
- 🔴 **빌드 실패는 예외가 아니라 종료코드다.** 그것은 게이트가 작동한 모습이고, 예외로
  내보내면 호출자가 재시도 루프에서 삼킨다. 예외는 **실행 자체를 못 한 경우**에만
- 미결: Q-4 는 **Gradle 한정 부분 확정**이다. Maven 미지원 · 실측 미완
  ([`open-questions.md`](./open-questions.md) Q-4)

## PostgreSQL

| 항목 | 내용 |
|---|---|
| 용도 | 저장소·이슈·후보·실행이력·PR 영속화 |
| 로컬 | `docker compose up -d` (`postgres:17-alpine`) |
| 기본값 | **미설정 시 H2 in-memory** — `application.yml` 이 그렇게 되어 있다 |

스키마 정본은 **Flyway 마이그레이션**(`db/migration`)이고 `ddl-auto` 는 `validate` 다 — Q-2 확정.

⚠️ 마이그레이션 SQL 은 **H2 와 PostgreSQL 양쪽에서 같은 한 벌**이 돌아야 한다.
벤더 고유 문법(JSONB · 파티셔닝 · TEXT 계열 차이)을 쓰지 않는다.
`flyway-database-postgresql` 모듈이 빠지면 PostgreSQL 에서 기동하지 않는다.

## Redis

| 항목 | 내용 |
|---|---|
| 용도(예정) | 잡 큐(`oss.scan` → `oss.analyze` → …), 스캔 커서 캐시 |
| 현재 | **애플리케이션이 쓰지 않는다.** `docker-compose.yml` 에만 있다 |

PRD §21 은 「MVP 는 Scheduler + DB 로 시작하고, Worker 분리가 필요해지면 Redis Streams 를 적용」이라고 정했다.
지금 Redis 의존을 넣는 것은 이 결정을 앞서간다.

## 공통 규율

| 규율 | 이유 |
|---|---|
| **트랜잭션 밖에서 호출** | 대외 호출 지연이 DB 커넥션·락 점유로 번진다 |
| **능력 인터페이스는 domain 에, 구현은 adapter/out 에** | [`../conventions/architecture.md`](../conventions/architecture.md) 규율 ③ |
| **타임아웃·재시도·실패 처리를 어댑터에서 명시** | 기본값에 맡기면 무한 대기가 생긴다 |
| **응답을 그대로 신뢰하지 않는다** | GitHub 응답은 스키마 검증, LLM 응답은 빌드·테스트 검증 |
| **모를 때는 「되돌릴 수 없는 쪽」을 피한다** | 「보수적으로」가 아니다 — 아래 |

### ⚠️ 「모르면 보수적으로」는 규칙이 아니다 — 무엇이 걸려 있느냐가 기준이다

같은 「모르는 상황」에 **정반대 대응**이 옳은 자리가 둘 있다. 규칙을 「보수적으로」로 적어 두면
둘이 모순으로 보이고, 다음 사람이 한쪽을 다른 쪽에 맞춰 「일관성 있게」 고친다.

| 판단 | 모를 때 | 틀리면 |
|---|---|---|
| **규약 판정** (#7) | **못 읽은 것으로** 본다 — fail-closed | 🔴 남의 저장소에 **규약 위반 PR 이 나간다** (S-5). 되돌릴 수 없다 |
| **레이트리밋 차단** (#8) | **막지 않는다** | 우리 호출이 실패할 뿐이다 |

가르는 것은 보수성의 정도가 아니라 **실패의 방향이 되돌릴 수 있는가**다.

⚠️ 실제로 이 오해에서 사고가 났다 — 「리밋도 안전 경계니 막는 쪽이 맞겠지」로 짠 선제 차단이,
`resetAt` 을 모를 때 **갱신할 응답이 영영 오지 않아 재기동 전까지 모든 GitHub 호출을 실패**시켰다.
**방어가 스스로를 잠그는 구조**였다. 막아서 잃는 것이 되돌릴 수 있는 종류라면, 막지 않는 것이 옳다.
