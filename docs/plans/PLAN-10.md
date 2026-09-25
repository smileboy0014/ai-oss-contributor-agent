# PLAN-10: LLM 능력 인터페이스 + 어댑터 — 토큰·비용 기록 포함

**이슈**: [#10](https://github.com/smileboy0014/ai-oss-contributor-agent/issues/10)
**타입**: feature
**작성일**: 2026-09-25 (rev.2 — 격리 검토 반영)

---

## 0. 계약 표면 (먼저 읽는다)

`agent` 도메인의 **첫 코드**이고, 파이프라인 4개 단계가 공유할 계약을 세운다.
계약이 흔들리면 #7·#11·#13·#16 이 함께 흔들리므로 여기서 고정한다.

```
                      ┌─ 소비자 이슈가 주입받는 유일한 빈 ─┐
                      │                                │
agent/domain          │   LanguageModel                │   ← 능력 선언 (기술 없음)
  LanguageModel       │     .complete(AgentRunContext, │
  PromptScrubber      │                LlmRequest)     │
  AgentRunRecorder    └────────────────┬───────────────┘
                                       │ 구현은 항상 데코레이터
                      agent/adapter/out/llm
                        RecordingLanguageModel  ──감쌈──▶ AnthropicLanguageModel
                              │                            (Anthropic Java SDK)
                              │ 기록                        │ 송신 직전 스크럽
                              ▼                            ▼
                    candidate/application                PromptScrubber
                      RecordAgentRunUseCase              (agent/adapter/out/llm)
                        (트랜잭션 경계)
                              ▼
                        AgentRun (candidate/domain)
```

### 세 가지를 **구조로** 강제한다 — 규약으로 두지 않는다

| 강제할 것 | 규약으로 두면 | 구조 |
|---|---|---|
| **호출마다 토큰 기록** | 소비자가 recorder 를 잊으면 컴파일된다 | `AgentRunContext` 를 `complete()` **필수 인자**로 올리고, 노출 빈을 `RecordingLanguageModel` **하나로** 만든다. 기록 없이 호출할 경로가 존재하지 않는다 |
| **송신 전 시크릿 스크럽** | 소비자 4곳이 각자 기억해야 한다 | `AnthropicLanguageModel` 이 송신 직전 `PromptScrubber` 를 **반드시** 통과시킨다. 우회 인자를 두지 않는다 |
| **잘린 응답을 성공으로 넘기지 않음** | 소비자가 잘린 JSON 을 파싱한다 | `MAX_TOKENS`·`REFUSAL` 은 **예외**다. 정상 반환 경로가 없다 |

### 타입 목록

| 타입 | 위치 | 역할 |
|---|---|---|
| `LanguageModel` | `agent/domain` | **유일한 LLM 능력.** 4개 호출 지점이 공유 |
| `PromptScrubber` | `agent/domain` | 송신 전 시크릿 제거 능력 — S-4 |
| `AgentRunRecorder` | `agent/domain` | 토큰·결과 기록 능력 |
| `LlmRequest`·`LlmResponse`·`LlmUsage` | `agent/domain` | 값 타입. SDK 타입을 막는 벽 |
| `LlmCallSite` | `agent/domain` | `ANALYZE`·`PLAN`·`CODE`·`REVIEW` — PRD §6.1 |
| `AgentRunContext` | `agent/domain` | `candidateId`·`callSite`·`attempt` |
| `LlmFailureReason` | `agent/domain` | `TIMEOUT`·`RATE_LIMITED`·`REJECTED`·`TRUNCATED`·`UNAVAILABLE` |
| `LlmException` 계열 | `agent/domain` | 재시도 가능/불가를 **타입으로** 가른다 |
| `RecordingLanguageModel` | `agent/adapter/out/llm` | 기록 강제 데코레이터 · MDC |
| `AnthropicLanguageModel` | `agent/adapter/out/llm` | SDK 호출 · 스크럽 · 재시도 루프 · 파싱 · 에러 번역 |
| `DisabledLanguageModel` | `agent/adapter/out/llm` | 키 미설정 시. 호출하면 **원인이 분명한 예외** |
| `PatternPromptScrubber` | `agent/adapter/out/llm` | 최소 토큰 패턴 차단. #28 이 교체 |
| `RecordAgentRunUseCase` | `candidate/application` | 트랜잭션 경계 · `LlmCallSite`→`Stage` 매핑 |

**`agent` 는 `candidate` 를 import 하지 않는다.** `AgentRun` 은 `candidate` 애그리거트 소유이므로
(규율 ④ · `agent/package-info.java` 가 명시), `agent` 가 **능력으로 선언**하고 `candidate` 가 **구현**한다.
의존 방향 `candidate → agent` 는 코드맵의 단방향 참조와 일치한다.

---

## 1. 요구사항

### 배경

PRD §6.1 의 LLM 호출 지점 4개가 각자 SDK 를 부르면 타임아웃·재시도·토큰 기록이 네 벌로 갈라진다.
갈라지는 순간 **비용이 보이지 않게 되고**, 재시도 루프가 조용히 돈을 태운다. 하나의 능력으로 모은다.

이 제품의 품질 축은 「좋은 코드를 쓰는가」가 아니라 **「나쁜 결과를 걸러내는가」**다
(project-overview.md). 이 능력은 **모델 출력을 진실로 승격시키지 않는 형태**여야 한다.

### 기능 요구사항 (FR)

| # | 요구 | 완료조건 | 강제 수단 |
|---|---|---|---|
| FR-1 | 능력은 `agent/domain` 에 **능력 이름**으로 · 구현은 `agent/adapter/out/llm` | ☑ 1 | 패키지 배치 |
| FR-2 | **호출마다** 입출력 토큰을 `AgentRun` 에 기록 | ☑ 2 | `RecordingLanguageModel` 데코레이터 |
| FR-3 | 타임아웃·재시도를 어댑터에서 **명시** | ☑ 3 | SDK 내장 재시도 **끄고** 직접 루프 |
| FR-4 | 응답 파싱을 어댑터에서 끝낸다 | ☑ 4 | `agent/domain` 에 `com.anthropic` import 0 |
| FR-5 | 모델 응답을 **그대로 진실로 쓰는 경로 없음** | ☑ 5 | `MAX_TOKENS`·`REFUSAL` → 예외 · 파싱 편의 메서드 없음 |
| FR-6 | 프롬프트 전문이 로그로 나가지 않는다 | ☑ 6 | 길이·해시만 기록 |
| FR-7 | 고정 응답 페이크 + 실패 주입 | ☑ 7 | `FakeLanguageModel` |
| **FR-8** | **프롬프트 송신 전 시크릿 스크럽** | 안전 경계 절 | `PromptScrubber` 필수 통과 |

FR-8 은 완료조건 목록에는 없지만 이슈의 **「안전 경계」 절이 본문으로 요구**한다 —
「`S-4` — 프롬프트가 가장 흔한 시크릿 유출 경로다」. `external-deps.md` 도 같다.

### 비기능 요구사항 (NFR)

| # | 요구 |
|---|---|
| NFR-1 | `./gradlew build` 통과 — Q-10 확정으로 **정식 게이트는 CI** 이나, 이 베이스에 CI 가 아직 없으므로 로컬 build 로 검증한다 |
| NFR-2 | `@SpringBootTest` 에 **실제 Anthropic 어댑터가 올라오지 않는다** |
| NFR-3 | `ANTHROPIC_API_KEY` 없이도 애플리케이션이 기동한다 |
| NFR-4 | 재시도는 **유한**. 무한 대기·무한 재시도 경로 없음 |

---

## 2. 게이트 판정

### 안전 경계 (S-1 ~ S-6)

**2개 조항 접촉.**

| 조항 | 접촉 지점 | 방어 |
|---|---|---|
| `S-4` 🔴 | **송신 프롬프트** · 키 · 로그 · 예외 | 5중 — 아래 |
| `S-6` 🔴 | 어댑터 재시도 | 두 축 분리 — 아래 |

#### S-4 — 5중 방어 (①이 본류다)

**① 송신 직전 스크럽 — 유일하게 「밖으로 나가는 것」을 막는 방어**

나머지 넷은 전부 **우리 안쪽**(로그·DB·예외)을 막는다. 유출 상대가 **모델 제공자**인 경로는 이것뿐이다.
대상 저장소 파일을 프롬프트에 싣는 것은 소비자 이슈의 일상 동작이므로, 이음매를 지금 고정하지 않으면
**소비자 4곳이 각자 기억해야 하는 구조**가 확정된다.

- `agent/domain` 에 `PromptScrubber` 능력 선언. `AnthropicLanguageModel` 이 송신 직전 **반드시** 통과
- 이 이슈의 구현은 `PatternPromptScrubber` — **fail-closed**. 패턴 적중 시 치환하고, 치환 사실을
  `WARN` 으로 남긴다(무엇이 걸렸는지는 남기지 않는다)
- 패턴: `ghp_` · `gho_` · `github_pat_` · `sk-ant-` · `AKIA` · `xox` — S-4 가 열거한 것 그대로
- ⚠️ **#28 과 중복이 아니다.** #28 은 「저장소 컨텍스트 수집 단계의 파일 배제 + 공통 스크럽 모듈」이고,
  여기는 「LLM 어댑터의 송신 이음매」다. #28 의 `TokenRedactor` 가 오면 **`PromptScrubber` 구현만
  갈아끼운다** — 능력이 domain 에 있으므로 교체 비용이 0 이다

**② 키는 환경변수에서만.** `ANTHROPIC_API_KEY` 만 환경에서 읽는다.
⚠️ `AnthropicOkHttpClient.fromEnv()` 를 **쓰지 않는다** — `ANTHROPIC_BASE_URL`·`ANTHROPIC_AUTH_TOKEN`
까지 함께 읽어, 환경변수 하나로 **프롬프트 송신 대상 호스트가 바뀔 수 있다.**
`.apiKey(...)` 로 키만 명시 주입하고 base URL 은 우리 설정값으로 고정한다. 기동 시 엔드포인트
호스트를 `INFO` 로 한 번 남겨 오설정을 조기에 잡는다.

**③ 프롬프트·응답 전문을 로그에 남기는 경로를 만들지 않는다.** `debug` 에서도 남기지 않고
**길이와 해시만** 남긴다 — logging.md 「남기지 말아야 할 것: 대용량 payload 원본(프롬프트 전문)
— 크기와 해시만」. 이슈 문구(「`debug` + 스크럽 후에만」)는 **허용 상한**이지 요구가 아니므로,
더 엄격한 선택은 위반이 아니고 **부재는 테스트로 증명 가능**해 검증 가능성이 높다.

**④ SDK 예외 메시지를 그대로 옮기지 않는다.** 예외 본문에 요청 URL·헤더가 실려 나오는 것이 가장 흔한 사고다.
우리 예외는 `LlmFailureReason` + `LlmCallSite` 만 담는다. **HTTP 상태코드를 domain 예외에 넣지 않는다** —
규율 ①(domain 에 HTTP 의미 금지) · 「HTTP 매핑은 `support/web` 한 곳」. 상태코드는 **어댑터 로그에만**.

**⑤ 응답 원문을 이 이슈에서 DB 에 적재하지 않는다.** `AgentRun` 은 토큰·상태·시각만 쓴다.

#### S-6 — 재시도 두 축을 섞지 않는다

Q-6 이 2026-09-25 에 확정했고, **그 결론이 이 설계를 그대로 지목한다**:

> 전송 계층 재시도가 이미 별개 축으로 흡수한다 — 5xx·타임아웃은 `github.max-retries`
> (그리고 **#10 의 LLM 어댑터**)에서 처리되고 파이프라인 카운터를 태우지 않는다.

| | 무엇 | 값 | 누가 |
|---|---|---|---|
| **전송 재시도** | 429·5xx·IO 오류 재전송 | `agent.llm.max-retries` (2) | 어댑터 |
| **파이프라인 재시도** | `CODE`→`VERIFY`→`REVIEW` 한 바퀴 | `agent.execution.max-retries` (3) | 후보 상태머신 |

🔴 **어댑터는 `agent.execution.max-retries` 를 읽지도 바꾸지도 않는다.**
- 비재시도(400·401·403·404·`REFUSAL`)는 **즉시 실패**. 재시도하면 무한 루프가 된다
- 타임아웃은 명시값. 무한 대기 없음
- 곱셈 예산 — 후보 1건당 최대 **3 × (1 + 2) = 9회**. Q-6 이 계산해 둔 그대로다

`S-1`·`S-2`·`S-3`·`S-5` 미접촉 — push·PR·샌드박스·대상 저장소 산출물 경로가 없다.

### 미결 대조 (Q-1 ~ Q-10)

| Q | 판정 | 처리 |
|---|---|---|
| `Q-6` | ✅ **확정됨** (2026-09-25) | `attempt` = `CODE`→`VERIFY`→`REVIEW` 사이클 번호. **어댑터는 여전히 해석하지 않는다** — 호출자가 `AgentRunContext.attempt` 로 넘긴 값을 그대로 기록하고, 전송 재시도 횟수를 더하지 않는다. `ANALYZE`·`PLAN` 은 파이프라인 재시도가 없으므로 항상 `1` |
| `Q-9` | ✅ **확정됨** (2026-09-25 · #4) | 대역은 **3계층**이고 「LLM API — 3계층 전부, #10」이 우리 몫으로 지목됐다. 아래 「대역 3계층」 참조. 폴백(자체 페이크만)으로 가지 않는다 |
| `Q-10` | ✅ **확정됨** | **게이트는 CI 다.** `./gradlew check` 가 pre-commit 훅에서 빠졌고, 훅은 `secret-scan`·`safety-boundary-check` 2종만 남는다. 로컬 build 는 여전히 돌리되 「유일한 게이트」는 아니다 |
| `Q-3` | 🔵 인접 | 프로필 미분리. 이 이슈는 호출자를 만들지 않아 지금은 무해 |
| **신규** | 🆕 | **Q-11 신설** — 「Q-1(직접 구현)을 모든 대외 의존에 일반화하지 않는다」. 아래 §3 |

**선행 #4** = `[decision] 대외 의존 테스트 대역 전략 확정 — GitHub · LLM · 샌드박스`. **닫혔다.**

### ⚠️ 베이스 표류 — 위 확정들이 아직 `main` 에 없다

이 브랜치의 베이스는 `origin/main` = `9b324fa` 이고, **Q-6·Q-9·Q-10 확정과 git 훅 전환은
미머지 작업분**(#35 · #4 · #27)이다. 규칙 문서의 현재 상태를 정본으로 받아 설계하되,
아래를 전제한다.

- `codemaps/architecture.md` · `README.md` 는 **PR #35 도 수정 중**이다 → 리베이스 시 충돌을 예상한다.
  충돌 표면을 줄이기 위해 이 PR 의 문서 수정은 **LLM 관련 줄만** 외과적으로 건드린다
- `.githooks/` 는 이 베이스에 없다. `core.hooksPath` 설정은 #27 머지 후에 의미가 생긴다
- 머지 순서가 뒤바뀌면 `external-deps.md`·`open-questions.md` 편집이 충돌한다. **본문을 다시 쓰지 않고
  해당 절만 최소 수정**한다

---

## 3. 기술 설계

### 필수 체크리스트

| # | 항목 | 답 |
|---|---|---|
| 1 | 소유 도메인 | **`agent`** — LLM 호출·스크럽·재시도. `AgentRun` **엔티티**는 `candidate` 소유이고 이 작업이 옮기지 않는다 |
| 2 | 레이어 배치 | 능력·값 → `domain` / SDK·스크럽 → `agent/adapter/out/llm` / **기록 트랜잭션 → `candidate/application`** / 조립 → `config` |
| 3 | 능력 인터페이스 | `LanguageModel`·`PromptScrubber`·`AgentRunRecorder` ↔ `AnthropicLanguageModel`·`PatternPromptScrubber`·`RecordAgentRunUseCase` |
| 4 | 🔴 트랜잭션 안 대외 호출 | **없다.** 기록은 호출 **전/후 짧은 트랜잭션 2개**(UseCase 경계). 호출 시점에 활성 트랜잭션이 없음을 **테스트로 단언** |
| 5 | 상태 전이 영향 | `CandidateStatus` 무변경. 단 **`AgentRun` 에 전이 메서드를 추가**한다(아래) |
| 6 | 멱등성 | `AgentRun` 은 append-only. 같은 호출을 두 번 하면 행이 둘 생기는 것이 **맞다** — 비용이 두 번 나갔다 |
| 7 | `Clock` 주입 | ✅ `startedAt`·`finishedAt`. `Instant.now()` 직접 호출 없음 |
| 8 | 🔴 안전 경계 | S-4 · S-6 — §2 |

### ⚠️ `AgentRun` 에 생성·전이 수단이 없다 — 이 작업이 추가한다

현재 `AgentRun` 은 `@Getter` + `@NoArgsConstructor(PROTECTED)` 뿐이고 **public 생성자·정적 팩토리·
전이 메서드가 하나도 없다**(Q-7 이 `@Setter`·`@Builder`·`@AllArgsConstructor` 를 금지).
즉 지금 상태로는 `RUNNING` 행을 만들 수도, 토큰을 채울 수도 없다. **다른 애그리거트의 도메인 변경**이므로
계약 표면으로 다룬다.

```java
// candidate/domain/AgentRun.java — 추가
public static AgentRun start(Long candidateId, Stage stage, int attempt, Clock clock)
public void succeed(int inputTokens, int outputTokens, Clock clock)
public void fail(String reason, Clock clock)
```

- 전이 규칙을 엔티티에 둔다 — `RUNNING` 에서만 종단으로 가고, **종단(`SUCCEEDED`·`FAILED`)에서 나가는
  전이를 만들지 않는다.** S-6 의 종단 상태 원칙과 같은 모양이다
- `fail(reason)` 의 `reason` 은 `@ExternalText(EXCEPTION)` 필드에 들어간다. **SDK 예외 원문을 넣지 않는다**
  — `LlmFailureReason` 이름만

### `LlmCallSite` → `AgentRun.Stage` 매핑은 누가 하나

**`candidate/application/RecordAgentRunUseCase` 가 한다.** `agent` 가 `Stage` 를 알면 규율 ④ 위반이고,
`candidate` 가 `LlmCallSite` 를 아는 것은 허용 방향(`candidate → agent`)이다.

| `LlmCallSite` | `AgentRun.Stage` |
|---|---|
| `ANALYZE` · `PLAN` · `CODE` · `REVIEW` | 동명 4종 |
| — | `VERIFY` 는 샌드박스 단계라 LLM 호출 지점이 아니다 |

### 왜 공식 Anthropic Java SDK 인가 — Q-1 과 결론이 다른 이유

Q-1(GitHub = 직접 구현)의 근거 3개가 **여기서는 하나도 성립하지 않는다.**

| Q-1 의 근거 | LLM 에서는 |
|---|---|
| ETag 조건부 요청 + `updated_at` 커서를 직접 제어 | 해당 없음 |
| 2차 레이트리밋이 403 으로 와서 직접 구분 | 해당 없음 — 429 가 정상적으로 온다 |
| 라이브러리가 1년 넘게 RC | **해당 없음** — 아래 표 |

**버전 근거** (Q-1 이 hub4j 를 검증한 것과 같은 형식):

| 항목 | `com.anthropic:anthropic-java` |
|---|---|
| 채택 버전 | **2.65.0** (2026-09-22) |
| 안정성 | 2.x GA. RC 에 머문 이력 없음 |
| 릴리스 빈도 | 2.50.0 → 2.65.0 이 최근 구간에 연속 배포 |

**진짜 근거는 「갈아끼울 수 있는가」다.** 스트리밍·thinking 은 §6 에서 범위 밖으로 밀었으므로
채택 근거가 될 수 없다. 남는 실익은 토큰 회계·에러 분류이고 그것만으로는 Q-1 의 논증과 대칭이다.
결정적인 것은 **능력 인터페이스가 `agent/domain` 에 있어 어댑터 교체 비용이 낮다**는 점이다 —
#6 의 「갈아끼울 수 있게 설계한다」와 같은 논리. SDK 가 문제가 되면 `AnthropicLanguageModel` 한 장을
직접 구현으로 바꾸면 되고, 소비자는 영향받지 않는다.

🆕 **Q-11 로 남긴다** — 「Q-1 을 모든 대외 의존에 일반화하지 않는다. 경계마다 근거를 다시 본다」.
흔적이 계획서에만 남으면 다음에 같은 논쟁이 반복된다.

### ⚠️ SDK 내장 재시도를 끈다 — 비용이 장부에서 사라지지 않게

`.maxRetries(0)` 으로 **명시적으로 끄고** 어댑터가 루프를 직접 돈다.

SDK 에 맡기면 몇 번 재전송했는지, 각 시도가 얼마나 걸렸는지 **관측할 수 없다.** 그런데
`logging.md` 「반드시 남겨야 할 것」은 재시도에 대해 「**몇 번째인지 · 직전 실패 사유**」를 요구하고,
이 이슈의 존재 이유가 「재시도 루프가 조용히 돈을 태운다」이다. 관측 불가능한 재시도는 이 이슈가
없애려는 바로 그 상태다.

- 총 전송 시도 횟수를 구조화 로그에 남긴다
- 타임아웃으로 끊긴 시도는 usage 를 못 받지만 **모델은 이미 토큰을 생성했다.** 시작 행을 먼저
  남기는 이유가 이것이다 — 장부에서 사라지지 않게

### 빈 배선 — `agent.llm.enabled` 플래그를 두지 않는다

**빈은 항상 등록한다.** `ANTHROPIC_API_KEY` 유무로 구현을 고른다.

| 상황 | 주입되는 것 |
|---|---|
| 키 있음 | `RecordingLanguageModel(AnthropicLanguageModel)` |
| 키 없음 | `RecordingLanguageModel(DisabledLanguageModel)` — 호출 시 원인이 분명한 예외 |
| 테스트 | `FakeLanguageModel` (`@TestConfiguration`) |

조건부 등록(`@ConditionalOnProperty`)을 **쓰지 않는 이유** — 빈이 없으면 소비자가 필수 의존으로
받는 순간 기본 설정으로 기동이 안 되고, 그러면 누군가 기본값을 뒤집거나
`@Autowired(required=false)` 로 눕힌다. 후자면 **「조용히 통과」가 그대로 돌아온다.**
빈이 항상 있으면 NFR-2·3 을 둘 다 만족하면서 배선 자체를 테스트할 수 있다.

### 로그 계약

| 레벨 | 남기는 것 |
|---|---|
| `INFO` | 모델 · `callSite` · 입력/출력 토큰 · 소요 ms · 결과 · **전송 시도 횟수** |
| `WARN` | 재시도 발생(몇 번째 · 사유) · 스크럽 적중(패턴 내용은 제외) · 레이트리밋 지연 |
| `ERROR` | `LlmFailureReason` + `callSite`. **예외는 마지막 인자** |
| `DEBUG` | 프롬프트 **길이 · 해시만.** 전문 금지 |

MDC 는 `RecordingLanguageModel` 이 `put` / `finally clear` 한다 —
`candidateId` · `stage` · `attempt`. `AgentRunContext` 의 3필드와 정확히 일치한다.

### 생성 파일

| 파일 | 내용 |
|---|---|
| `agent/domain/LanguageModel.java` | `LlmResponse complete(AgentRunContext, LlmRequest)` |
| `agent/domain/PromptScrubber.java` | `String scrub(String)` — S-4 본류 |
| `agent/domain/AgentRunRecorder.java` | `started` · `succeeded` · `failed` |
| `agent/domain/LlmRequest.java` | record — `system`·`userPrompt`·`maxOutputTokens` |
| `agent/domain/LlmResponse.java` | record — `text`·`usage`. **파싱 편의 메서드 없음** |
| `agent/domain/LlmUsage.java` | record — `inputTokens`·`outputTokens` |
| `agent/domain/LlmCallSite.java` | enum 4종 |
| `agent/domain/AgentRunContext.java` | record — `candidateId`·`callSite`·`attempt` |
| `agent/domain/LlmFailureReason.java` | enum 5종 |
| `agent/domain/LlmException.java` 외 | `LlmTimeout`·`LlmRateLimited`·`LlmRejected`·`LlmTruncated`·`LlmUnavailable` |
| `agent/adapter/out/llm/RecordingLanguageModel.java` | 기록 강제 · MDC |
| `agent/adapter/out/llm/AnthropicLanguageModel.java` | 스크럽 → SDK → 재시도 루프 → 파싱 → 번역 |
| `agent/adapter/out/llm/DisabledLanguageModel.java` | 키 미설정 시 |
| `agent/adapter/out/llm/PatternPromptScrubber.java` | 최소 패턴 차단 |
| `agent/adapter/out/llm/AnthropicProperties.java` | `agent.llm.*` |
| `candidate/application/RecordAgentRunUseCase.java` | 트랜잭션 경계 · Stage 매핑 |
| `candidate/adapter/out/persistence/AgentRunRepository.java` | Spring Data |
| `config/LanguageModelConfig.java` | 조립 |

### 수정 파일

| 파일 | 변경 |
|---|---|
| **`candidate/domain/AgentRun.java`** | **정적 팩토리 + 전이 메서드** — 위 참조 |
| `gradle/libs.versions.toml` | `anthropic-java = "2.65.0"` · WireMock |
| `build.gradle.kts` | `com.anthropic:anthropic-java` · `wiremock-standalone`(test) |
| `src/main/resources/application.yml` | `agent.llm.*` |
| `.env.example` | `ANTHROPIC_API_KEY` · `ANTHROPIC_MODEL` |
| `README.md` | 구조 표의 `agent/ (경계만)` 정정 |
| `agent/package-info.java` | 「아직 비어 있다」 삭제 · 2층 능력 구조 명시 |
| `.claude/codemaps/architecture.md` | **3곳** — 아래 |
| `.claude/rules/context/external-deps.md` | **재시도 키 정정** — 아래 |
| `.claude/rules/context/open-questions.md` | **Q-11 신설** |

#### 코드맵 정정 3곳

1. **`AgentRun` 소유 행** — 표는 `agent` 소유라 적었지만 실제 코드(#5)와 `package-info.java` 는
   `candidate` 소유다. 이 작업이 그 경계를 실제로 넘나들므로 같은 PR 에서 고친다
2. **LLM 능력 행** — 표는 `IssueAnalyst`·`ImplementationPlanner`·`CodingAgent`·`DiffReviewer` 4종만
   적는다. **2층 구조임을 명시**한다 — 「`LanguageModel`(공용 전송 능력) + 그 위에 얹히는 도메인 능력 4종
   (소비자 이슈)」. 남겨 두면 소비자 이슈가 두 설계를 동시에 읽는다
3. **구현 현황** — `agent` 도메인 ❌ → 부분

#### `external-deps.md` 정정 — 정본을 뒤집는 결정이다

현재 LLM 절: 「재시도 상한은 `agent.execution.max-retries`」.
Q-6 확정으로 **전송 축과 파이프라인 축이 갈렸으므로** 이 문장은 더 이상 맞지 않는다.
「전송 재시도 = `agent.llm.max-retries` / 파이프라인 상한 = `agent.execution.max-retries`」로 개정하고,
커밋 본문에 `[S-6]` 근거를 남긴다(`commit-convention.md` 「안전 경계 인용」).
두 문서가 어긋난 채로 두면 다음 사람이 어느 쪽이 정본인지 모른다.

### 설정

```yaml
agent:
  llm:
    model: ${ANTHROPIC_MODEL:claude-sonnet-5}   # external-deps.md 가 정한 기본값
    base-url: ${ANTHROPIC_BASE_URL:https://api.anthropic.com}
    max-output-tokens: 16000
    timeout-seconds: 120
    max-retries: 2            # ⚠ agent.execution.max-retries(3) 와 다른 축이다
```

**수치 근거** — `max-output-tokens: 16000` 은 비스트리밍 요청의 SDK HTTP 타임아웃을 넘지 않는
범위의 상한이다. `timeout-seconds: 120` 은 Q-3(프로필 미분리 · API 스레드 점유)을 감안해 보수적으로
잡았다. 코딩 단계의 diff 가 가장 길어 `LlmCallSite` 별로 값을 달리 줄 여지가 있으나,
**호출 패턴이 없는 지금 나누지 않는다** — 소비자 이슈가 실측한 뒤 판단한다.

### 대역 3계층 — Q-9 확정 적용 (⚠️ 한 층은 우리에게 성립하지 않는다)

Q-9 은 「LLM API — 3계층 전부」를 지목했다. 그런데 **중간 층이 우리 어댑터에는 적용 불가능**하다.

| 층 | Q-9 의 지정 | #10 에서 | 왜 |
|---|---|---|---|
| 능력 소비자 | 자체 페이크 | ✅ `FakeLanguageModel` | 그대로 |
| 어댑터 매핑 | `MockRestServiceServer` | ❌ → **WireMock 으로 대체** | 아래 |
| 전송 계약 | WireMock | ✅ WireMock | 그대로 |

🔴 **`MockRestServiceServer` 는 Spring `RestClient`/`RestTemplate` 전용**이다. 우리 어댑터는
Anthropic SDK(OkHttp)를 쓰므로 **가로챌 대상이 없다.** Q-9 의 3계층은 GitHub 어댑터
(`RestClient` 직접 구현 · Q-1)를 염두에 두고 쓰인 것이고, LLM 에는 그 전제가 없다.

요청 조립·응답 파싱·오류 변환은 **WireMock 이 함께 본다** — base URL 을 WireMock 으로 돌리면
같은 소켓 위에서 매핑과 전송 계약을 둘 다 검증할 수 있다. `agent.llm.base-url` 설정을
S-4 ② 때문에 이미 두었으므로 추가 비용이 없다. **층이 줄어든 것이 아니라 도구가 합쳐진 것**이다.

❌ 녹화 응답(VCR)은 쓰지 않는다 — 녹음본에 토큰이 섞여 커밋된다(S-4). Q-9 과 같은 판단.

### 테스트

| 테스트 | 무엇을 잡나 |
|---|---|
| `FakeLanguageModel` | 고정 응답 · 호출 기록 · 예외 주입 — 소비자 층 |
| `AnthropicLanguageModelWireMockTest` | **전송 계약** — 읽기 타임아웃이 실제로 걸리는가 · 연결 실패 · 429 재시도 · 요청 본문 매핑 |
| `토큰_기록_없이_LLM을_호출할_수_있는_경로가_없다()` | **FR-2 강제** — 컨텍스트가 내주는 빈이 항상 recorder 를 탄다 · 예외 경로에서도 `failed` 가 남는다 |
| `프롬프트에_토큰_패턴이_있으면_송신되지_않는다_S4()` | **FR-8 본류** — 송신 본문 검증 |
| `프롬프트_전문이_로그에_남지_않는다_S4()` | 길이·해시만 |
| `예외에_키와_URL이_섞이지_않는다_S4()` | 번역된 예외만 |
| `상한에서_잘린_응답은_성공으로_반환되지_않는다()` | **FR-5** — `MAX_TOKENS` → `LlmTruncated` |
| `거부된_응답은_재시도하지_않는다()` | `REFUSAL` → 비재시도 |
| `LLM_호출_시점에_활성_트랜잭션이_없다()` | 규율 🔴 — 이 프로젝트에서 처음 실질적 의미를 갖는 지점 |
| `재시도가_유한하다_S6()` | 전송 상한 · `agent.execution.max-retries` 미참조 |
| `AgentRun_종단_상태에서_나가는_전이가_없다()` | 엔티티 전이 규칙 |
| `AgentRunTest` | 토큰 기록 · 실패도 기록 |
| `실제_Anthropic_어댑터가_테스트_컨텍스트에_없다()` | NFR-2 |

조항 코드를 테스트 이름에 넣는다 — `testing-philosophy.md`.

---

## 4. 구현 순서

`contract-and-impl` → **sequential**. 능력 선언이 나머지 전부의 선행이다.

| Stage | 내용 | 선행 | 복잡도 |
|---|---|---|---|
| 1 | `agent/domain` 능력·값·예외 | — | 낮음 |
| 2 | 의존성·설정 | — | 낮음 |
| 3 | `AgentRun` 팩토리·전이 (**다른 애그리거트**) | — | 중간 |
| 4 | `AnthropicLanguageModel` + 스크럽 + 재시도 루프 + 번역 | 1·2 | **높음** |
| 5 | `RecordingLanguageModel` + `RecordAgentRunUseCase` + 조립 | 1·3·4 | 중간 |
| 6 | 페이크 + 테스트 12종 | 1~5 | 중간 |
| 7 | 문서 — 코드맵 3곳 · `external-deps.md` · Q-11 · README · package-info | 1~6 | 낮음 |

---

## 5. 리스크

| 리스크 | 대응 |
|---|---|
| 🔴 **프롬프트에 시크릿이 실려 모델 제공자로 나간다** (S-4 본류) | `PromptScrubber` 필수 통과 + 송신 본문 테스트 |
| 🔴 **소비자가 기록을 잊어 비용이 안 보인다** | 데코레이터가 유일한 빈. 잊을 경로가 없다 |
| 🔴 **잘린 응답을 진실로 파싱한다** | `MAX_TOKENS` → 예외. 정상 반환 경로 없음 |
| 🔴 전송/파이프라인 재시도 혼선 (S-6) | 키 분리 + `external-deps.md` 정정 + 테스트 |
| SDK 예외에 키·URL 이 실려 로그로 | 우리 타입으로 번역. `LlmFailureReason` 만 |
| SDK 타입이 domain 으로 샌다 | `agent/domain` 의 import 에 `com.anthropic` 이 없음을 확인 |
| `agent` 가 `AgentRun` 을 직접 쓰고 싶어진다 | 규율 ④ 위반. `AgentRunRecorder` 가 유일한 경로 |
| **프롬프트 미기록의 운영 비용** | 응답이 이상할 때 재현 수단이 없다. 대체 진단 수단 = 프롬프트 **해시 + 길이 + `callSite`**. #28 머지 후 `debug` + 스크럽 재개방을 **재검토**한다(기본은 계속 미기록) |
| 비용 — 재시도 루프 | 전송 상한 2 · 곱 9회 · 모든 호출이 `AgentRun` 에 남는다 |

---

## 6. 범위 밖

- **LLM 호출자** — 분석·계획·코딩·리뷰 UseCase 는 소비자 이슈(#7·#11 등)
- **도메인 능력 4종**(`IssueAnalyst` 등) — `LanguageModel` 위에 얹히는 2층. 소비자 이슈
- **스트리밍 · thinking · 프롬프트 캐싱** — 호출 패턴이 정해진 뒤. **그래서 SDK 채택 근거로 쓰지 않았다**
- **`PromptScrubber` 의 완성형 구현** — #28. 이 이슈는 **이음매와 최소 패턴**까지
- **불변식 ⑧(재시도 상한)을 후보 루트가 어떤 필드로 드는가** — #21 · #12
- **샌드박스 능력(`CodeSandbox`)** — 이슈 #10 은 LLM 만 말한다
