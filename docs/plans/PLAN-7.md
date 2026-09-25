# PLAN-7: 대상 저장소 기여 규약 수집·분석 (RepositoryPolicy)

**이슈**: [#7](https://github.com/smileboy0014/ai-oss-contributor-agent/issues/7)
**타입**: feature
**작성일**: 2026-09-25 (rev.2 — 격리 검토 반영 · 중대 4건)

---

## 0. 계약 표면

이 작업은 **S-5 의 실행체**다. 여기서 「못 읽음」을 「규약 없음」으로 번역하면 규약 위반 PR 이
외부 OSS 로 나간다.

```
repository/domain                                   ← 능력 선언 (기술 없음)
  ContributionRuleInterpreter  .interpret(RepositoryDocuments) → RuleReading
        ▲
        │ 구현
repository/adapter/out/llm
  LlmContributionRuleInterpreter ──위임──▶ agent/domain/LanguageModel  (#10)
                                            └ 송신 직전 PromptScrubber 통과 (S-4)
```

### ⚠️ 블로커였던 것 — 규약 분석에는 후보가 없다

`LanguageModel.complete(AgentRunContext, LlmRequest)` 의 `AgentRunContext` 는 `candidateId` 를
**필수**로 받는데, **규약 분석은 저장소 단위**라 그 시점에 후보가 존재하지 않는다.
`LlmCallSite` 에도 해당 값이 없고, `agent_run.candidate_id` 는 `NOT NULL` 이며, 노출 빈이
`RecordingLanguageModel` 하나뿐이라 기록을 건너뛸 수도 없다(#10 이 일부러 그렇게 조립했다).

**결론 — 전제가 틀렸다.** 「모든 LLM 호출은 후보에 속한다」가 성립하지 않는다.
가짜 `candidateId`(0·-1)나 `ANALYZE` 재사용은 비용 장부와 MDC 를 오염시키므로 하지 않는다.

| 고칠 것 | 위치 |
|---|---|
| `LlmCallSite.POLICY` 추가 | `agent/domain` |
| `AgentRunContext.candidateId` **nullable 완화** + `forRepository(callSite)` 팩토리 | `agent/domain` |
| `AgentRun.Stage.POLICY` 추가 · `start()` 의 candidateId null 허용 | `candidate/domain` |
| `RecordAgentRunUseCase.toStage` 매핑 | `candidate/application` |
| **`agent_run.candidate_id` NULL 허용** | `db/migration/V5` |

- 사용자 승인 완료 (2026-09-25)
- **#12 세션 합의 완료** — `AgentRun.java` 는 이쪽 소관, `Stage.POLICY` 는 Q-6 카운터와 무충돌
  (`POLICY` 는 `CODE→VERIFY→REVIEW` 루프 밖이라 `ANALYZE`·`PLAN` 과 같은 취급, `attempt` 는 항상 1)
- `architecture.md` 가 `AgentRun` 을 **독립 애그리거트**로 규정한 근거(무한정 쌓이는 append-only)는
  그대로 서고, NULL 허용은 그 독립성을 오히려 강화한다

### 타입 목록

| 타입 | 위치 | 역할 |
|---|---|---|
| `ContributionRuleInterpreter` | `repository/domain` | 규약 문서 → 판정. **능력 이름** |
| `PolicyDocumentPath` | `repository/domain` | 경로 + **역할**(필수/부가) — 아래 |
| `RepositoryDocuments` | `repository/domain` | 수집 결과 + `hasUnreadableRequired()` |
| `DocumentFetchOutcome` | `repository/domain` | `READ` · `ABSENT` · `UNREADABLE` |
| `FetchedDocument` | `repository/domain` | 경로 + outcome + 내용(READ 일 때만) + 사유 코드 |
| `UnreadableReason` | `repository/domain` | `RATE_LIMITED` · `SERVER_ERROR` · `TOO_LARGE` · `TRUNCATED` · `UNKNOWN` |
| `RuleReading` | `repository/domain` | 판정 값 |
| `ScrubbedRules` | `repository/domain` | **스크럽을 거쳐야만 만들 수 있는** 규약 요약 값 |
| `LlmContributionRuleInterpreter` | `repository/adapter/out/llm` | `LanguageModel` 소비 |
| `AnalyzeRepositoryPolicyUseCase` | `repository/application` | 흐름 · 트랜잭션 경계 · 게이트 단언 |

### 왜 `LanguageModel` 을 `repository/domain` 이 직접 쓰지 않나

`repository` 가 알아야 하는 것은 「규약 문서를 주면 판정을 돌려준다」뿐이다. `LanguageModel` 을
직접 import 하면 도메인이 프롬프트·토큰·재시도에 묶인다. 자기 말로 능력을 선언하고 어댑터가
갈아 끼운다 — #10 이 `AgentRunRecorder` 를 뒤집은 것과 같은 모양이다.

---

## 1. 요구사항

### 기능 요구사항 (FR)

| # | 요구 | 근거 |
|---|---|---|
| FR-1 | 후보 문서 수집 → LLM 파싱 → `RepositoryPolicy` 영속화 | 완료조건 1 |
| FR-2 | **AI 기여 금지 저장소를 후보에서 제외** | 완료조건 2 |
| FR-3 | **파싱 실패는 「허용」이 아니라 「보류」** | 완료조건 3 |
| FR-4 | `RepositoryPolicy` 없이 구현 단계로 못 가게 막는다 | 완료조건 4 |
| FR-5 | 프롬프트 송신 전 시크릿 스크럽 | 완료조건 5 |
| FR-6 | **PR 템플릿·sign-off·CLA·이슈 참조·테스트 필수**를 수집한다 | 「수집 대상」 · S-5 표 |
| FR-7 | 보류 사유를 **코드로 구분해** 기록 | Q-8 확정 ② |

### 비기능 요구사항 (NFR)

| # | 요구 |
|---|---|
| NFR-1 | `./gradlew build` 통과 |
| NFR-2 | 대외 호출이 **트랜잭션 밖**이다 |
| NFR-3 | 테스트가 실제 GitHub·LLM 을 타지 않는다 |

---

## 2. 게이트 판정

### 안전 경계 — S-5 · S-4 접촉

#### S-5 ① — 「읽었는가」로 가른다 (Q-8 확정)

| 상황 | outcome | 판정 | `aiContributionAllowed` |
|---|---|---|---|
| 읽었고 금지 문구 없음 | `READ` | 허용 | `TRUE` |
| 읽었고 금지 문구 있음 | `READ` | 금지 | `FALSE` |
| **필수 경로가 전부 404** | `ABSENT` | 허용 | `TRUE` — 금지 표기가 존재할 수 없다 |
| **필수 경로를 하나라도 못 읽음** | `UNREADABLE` | 🔴 **보류** | `NULL` |
| LLM 이 판정 못 함 | — | 🔴 **보류** | `NULL` |

일부 `READ` + 일부 `ABSENT` 는 **읽은 것으로 판정**한다. 없는 문서에 금지 표기가 있을 수 없다.

#### S-5 ② — 경로를 역할로 가른다 🔴

Q-8 의 8경로는 **「AI 금지 판정」을 위한 최소 경로**이지 수집 대상 전체가 아니다.
이슈의 「수집 대상」과 S-5 표는 **README·PR 템플릿**도 요구한다. 그런데 둘을 같은 엄격도로
다루면 **AI 판정과 무관한 README 의 5xx 하나가 저장소를 통째로 보류**시킨다 —
보류는 사람만 풀 수 있고 **그 API(#24)는 아직 없다.** Phase 1 대상이 1곳이므로
복구 수단 없이 파이프라인이 멈춘다.

| 역할 | 경로 | 못 읽으면 |
|---|---|---|
| **판정 필수** (Q-8 8종) | `AGENTS.md` · `CLAUDE.md` · `CONTRIBUTING.md` · `CONTRIBUTING.adoc` · `CONTRIBUTING.rst` · `CONTRIBUTING` · `.github/CONTRIBUTING.md` · `.github/CONTRIBUTING.adoc` | 🔴 **전체 보류** |
| **부가 수집** | `.github/PULL_REQUEST_TEMPLATE.md` · `.github/pull_request_template.md` · `docs/PULL_REQUEST_TEMPLATE.md` · `README.md` · `README.adoc` | 해당 필드만 미수집. **보류 아님** |

이것은 Q-8 이탈이 아니라 Q-8 이 전제한 **「후보 경로」의 정의를 명시**하는 것이다.

⚠️ **확장자 변종을 빠뜨리면 이 방어가 통째로 무너진다.** `.md` 만 찾으면 spring-boot·
spring-data-redis 에서 404 를 받고 「규약이 있는데 못 읽은 것」을 「규약 없음 → 허용」으로 번역한다.
**한 파일만 읽고 끝내지 않는다** — spring-kafka 의 `AGENTS.md` 는 한 줄이고
「`CONTRIBUTING.md` 를 보라」가 전부다.

#### S-5 ③ — UNREADABLE 은 fail-closed, 단 범위를 좁힌다

**수집 호출 그 한 줄만** `catch (RuntimeException)` 으로 감싸 `UNREADABLE` 로 옮긴다.

- 타입을 쫓아가지 않는 이유 — S-5 에서 가르는 선은 「무슨 오류인가」가 아니라 **「읽었는가」**다.
  오류를 분류하지 못한다는 사실 자체가 「못 읽음」이다. 실제로 지금 구멍이 있다:
  읽기 타임아웃이 `CancellationException` 으로 **타입 없이** 올라와 `GitHubApiException` 계층을
  빠져나간다(#12 세션 확정). fail-closed 면 그 수정 전후와 무관하게 판정이 옳다
- ⚠️ **판정 로직까지 감싸지 않는다.** 넓게 감싸면 우리 쪽 버그(NPE·IllegalArgument)가
  「저장소를 못 읽었다」로 둔갑해 **보류 뒤에 숨는다**

#### S-5 ④ — 일시적 실패는 **보류 행을 만들지 않는다** 🔴

`external-deps.md` — 「`X-RateLimit-Remaining` 이 임계 미만이면 **작업을 실패시키지 말고 지연**시킨다.
리밋 소진은 **정상 운영 상황**이다」.

그런데 보류는 **사람만 푼다**(Q-8 ②). 그리고 그 해소 API(#24)가 **아직 없다.**
즉 지금 보류 행을 만들면 **되돌릴 수단이 저장소에 없는 상태**가 된다.
레이트리밋 같은 정상 운영 상황을 거기에 넣으면, 한 시간 뒤면 저절로 풀렸을 일이
**영구 보류**가 된다.

**지속성으로 가른다.**

| 실패 | 처리 | 왜 |
|---|---|---|
| **일시적** — 리밋 · 5xx · 타임아웃 | 재시도 → 그래도 실패하면 **아무 행도 쓰지 않고 중단** | 다음 스캔에서 자연히 재시도된다. 정책이 없으므로 **구현 단계는 어차피 막힌다**(FR-4) — 안전 성질은 보류와 동일하고 복구만 자동이다 |
| **영구적** — 1MB 초과 · 절단 · 권한 · 분류 불가 | **보류 행 생성** (`NULL` + 사유 코드) | 재시도해도 같다. 사람이 봐야 풀린다 |

⚠️ Q-8 의 표는 「못 읽음 → `aiContributionAllowed = NULL`」이라고 적지만, 그 조항이 막으려는 것은
**「못 읽음이 허용으로 번역되는 것」**이다. 행을 쓰지 않으면 허용으로도 번역되지 않는다 —
안전 성질은 그대로이고 **복구 가능성만 좋아진다.** 이 판단을 PR 본문에 남긴다.

`pending_reason` 에 **사유 코드**(`TOO_LARGE`·`TRUNCATED`·`UNKNOWN`)를 남겨 #24 가 일괄 처리
대상을 고를 수 있게 한다.

#### S-5 ⑤ — 보류·금지는 재분석으로 풀리지 않는다 🔴 **엔티티 불변식**

Q-8 확정 ②: 「보류는 자동으로 풀리지 않는다 — 재분석·시간경과·횟수소진 전부 배제」.

UseCase 의 `if` 로 두면 다음 사람이 지운다. **엔티티가 거부한다.**

```java
// RepositoryPolicy
public void reanalyze(RuleReading reading, Clock clock) {
    if (isAiContributionUndetermined())      throw new IllegalStateException("보류는 재분석으로 풀리지 않는다 — #24");
    if (Boolean.FALSE.equals(aiContributionAllowed)) throw new IllegalStateException("금지 판정은 재분석으로 뒤집히지 않는다");
    ...
}
```

**금지(`FALSE`)도 같이 막는다.** 막지 않으면 FR-2 가 재분석 한 번으로 풀린다.
해소는 #24 의 별도 메서드(`resolvedByHuman`)로만 열고 **이 PR 에 만들지 않는다.**

UseCase 는 기존 정책이 보류·금지면 **LLM 을 부르기 전에 중단**한다 — 반영할 수 없는 판정에
토큰을 쓰지 않는다.

#### S-5 ⑥ — 절단은 보류다

문서 상한을 `agent.policy.max-document-chars`(기본 40,000)로 **설정값으로 뺀다.**
`GitHubRepositorySource` 가 1MB 초과를 이미 거르므로 이 상한은 40KB~1MB 구간에 작동하고,
README 를 넣으면 실제로 발생한다.

🔴 규칙은 **「절단이 발생하면 보류」로 단순화**한다. 「놓쳤을 수 있으면 보류」는 판정 불가능한
조건이라 구현자마다 갈린다. 잘린 이상 금지 문구를 놓쳤을 가능성은 **항상** 있다.

#### S-4 — 유출 경로는 둘이다

**① 송신 경로** — #10 의 `AnthropicLanguageModel` 이 `PromptScrubber` 를 생성자 필수 인자로
강제한다. 이 작업이 따로 할 일이 없다. 다만 **이 이슈의 경로에서 증명**한다
(`testing-philosophy.md` — 안전 경계 관련 경로 100%).

**② 영속 경로 — 여기가 우회로다** 🔴

`contribution_rules` 는 `@ExternalText(TARGET_REPOSITORY)` 로 **표시만** 돼 있다.
애노테이션은 스크럽을 실행하지 않는다. 대상 저장소 원문을 그대로 넣으면 **스크럽을 한 번도
타지 않고** DB 에 앉고, 이 컬럼은 PR 본문 조립(#23)의 입력이 될 가능성이 높아
**유출 종착지가 대상 저장소의 공개 PR** 이 된다.

| 넣을 것 | 안 넣을 것 |
|---|---|
| LLM 판정의 **정규화 결과**(JSON) — sign-off·이슈 참조·CLA·테스트 필수·PR 본문 형식 요약 | 문서 **원문** |
| **판정 근거 경로 목록 + 각 문서의 해시** | 원문 인용 덩어리 |

원문이 필요하면 경로+해시로 다시 읽는다. 구조 강제 — `RepositoryPolicy.analyzed(...)` 가
원시 `String` 이 아니라 **`ScrubbedRules`**(스크럽을 거쳐야만 생성되는 값 타입)를 받는다.
`AnthropicLanguageModel` 이 생성자로 스크러버를 강제한 것과 같은 수법이다.

⚠️ `pending_reason` 에도 예외 원문을 넣지 않는다 — **사유 코드만**.

`S-1`·`S-2`·`S-3`·`S-6` 미접촉 — push·PR·샌드박스 경로가 없고 상태머신 전이를 만들지 않는다.

### 미결 대조

| Q | 판정 | 처리 |
|---|---|---|
| `Q-8` | ✅ 확정 (이 이슈를 위해) | 결론이 곧 명세. 위 ①~⑥ |
| `Q-9` | ✅ 확정 | 3계층. 소비자 페이크 + 어댑터 매핑. 전송 계약은 #6·#10 이 덮음 |
| `Q-2` | 🔵 인접 | V5 — 벤더 고유 문법 금지 |
| `Q-5` | 🔵 인접 | 보류 해소 API 는 #24 |

### 병렬 세션 조율 — **합의 완료**

| 세션 | 작업 | 합의 |
|---|---|---|
| #12 (peer-75) | `candidate/domain/{CandidateStatus,ContributionCandidate,StatusTransition,CandidateTransitionException}` · **V4** · `codemaps/domain.md`·`data.md` | ✅ `AgentRun.java` 는 **내 소관** · `Stage.POLICY` 무충돌 · 🔴 **머지 순서 #12 → #7** |
| #42 (peer-01) | `support/testing/**` · `ExternalAdapter` · `testing-philosophy.md` | ✅ 애노테이션만 쓰면 무영향. 그 파일들 안 건드림 |

🔴 **머지 순서가 중요하다.** `baseline-on-migrate: false` 라 V5 가 먼저 적용된 DB 에 V4 가 나중에
오면 Flyway 가 **out-of-order** 로 보고 `validate` 에서 기동을 막는다. H2 는 매번 새로 떠서
드러나지 않고 **PostgreSQL 개발 DB 에서만 터진다.**
내 PR 이 먼저 준비되면 #12 에 알린다 — 그쪽이 V5 로 내리기로 했다(아직 커밋 전이라 비용 0).

`support/github` · `codemaps/domain.md` 는 건드리지 않는다.

---

## 3. 기술 설계

### 필수 체크리스트

| # | 항목 | 답 |
|---|---|---|
| 1 | 소유 도메인 | **`repository`**. `agent`·`candidate` 는 좌표 확장분만 (§0) |
| 2 | 레이어 배치 | 능력·값 → `domain` / LLM 소비 → `adapter/out/llm` / 흐름 → `application` |
| 3 | 능력 인터페이스 | `ContributionRuleInterpreter` ↔ `LlmContributionRuleInterpreter` |
| 4 | 🔴 트랜잭션 안 대외 호출 | **없다.** 수집·판정을 끝내고 영속화만 짧은 트랜잭션 |
| 5 | 상태 전이 | `CandidateStatus` 무변경. `RepositoryPolicy` 에 팩토리 + **불변식** |
| 6 | 멱등성 | `UNIQUE(repository_id)` 존재. 재분석은 기존 행 갱신하되 **보류·금지는 거부** |
| 7 | `Clock` 주입 | ✅ |
| 8 | 🔴 안전 경계 | S-5 ①~⑥ · S-4 ①② |

### 흐름

```
AnalyzeRepositoryPolicyUseCase.analyze(repositoryId)
   │
   ├─ 1. 저장소 + 기존 정책 조회                     ── 짧은 트랜잭션
   │      기존이 보류·금지 → 즉시 중단 (LLM 안 부름) — S-5 ⑤
   │
   ├─ 2. fetchMetadata — archived 면 중단            ── 트랜잭션 밖
   │      보관된 저장소는 PR 을 못 연다. 여기서 안 거르면
   │      파이프라인 끝까지 돌고 PR 생성에서야 실패한다
   │
   ├─ 3. 필수 8 + 부가 5 경로 수집                    ── 트랜잭션 밖
   │      Optional.present → READ  (상한 초과 시 TRUNCATED → UNREADABLE)
   │      Optional.empty   → ABSENT                   (404 하나뿐 — #6 계약)
   │      RuntimeException → UNREADABLE + 사유 코드    (fail-closed, 이 한 줄만)
   │      일시적 실패는 재시도 후에도 실패할 때만 UNREADABLE — S-5 ④
   │
   ├─ 4. 필수 경로에 UNREADABLE 있음 → 보류. LLM 안 부름
   ├─ 5. 필수 경로가 전부 ABSENT   → 허용. LLM 안 부름
   │
   ├─ 6. READ 있음 → ContributionRuleInterpreter      ── 트랜잭션 밖
   │      (AgentRunContext.forRepository(POLICY) 로 기록된다)
   │      판정 불가 → 보류
   │
   └─ 7. RepositoryPolicy 저장/갱신                   ── 짧은 트랜잭션
```

4·5 에서 **LLM 을 건너뛰는 것이 설계다.** 판정이 설 수 없는 입력에 토큰을 쓰지 않는다.

### FR-4 게이트 — `boolean` 이 아니라 **단언**으로

`OssRepository.isContributionBlocked()` 만 두면 호출자가 무시할 수 있고, `policy` 가
**LAZY `@OneToOne(mappedBy)`** 라 트랜잭션 밖에서 부르면 `LazyInitializationException` 이 난다.

```java
// AnalyzeRepositoryPolicyUseCase — 조회 기반, 트랜잭션 안
@Transactional(readOnly = true)
public void assertContributionAllowed(Long repositoryId)   // 위반이면 예외
```

`boolean` 은 무시할 수 있지만 단언은 무시하기 어렵다 — **S-1 의 push 직전 어설션과 같은 모양**이다.
이 PR 은 단언을 제공하고, 실제 호출자는 #11·#24 다.
⚠️ **그 사실을 이슈에 남긴다** — 계획서에만 적으면 사라지고, 게이트가 「있는데 아무도 안 부르는」
상태가 된다.

### 생성 파일

`repository/domain/` — `ContributionRuleInterpreter` · `PolicyDocumentPath` · `RepositoryDocuments` ·
`DocumentFetchOutcome` · `FetchedDocument` · `UnreadableReason` · `RuleReading` · `ScrubbedRules`
`repository/adapter/out/llm/LlmContributionRuleInterpreter` · `PolicyPromptFactory`
`repository/adapter/out/persistence/RepositoryPolicyRepository`
`repository/application/AnalyzeRepositoryPolicyUseCase`
`db/migration/V5__policy_pending_reason_and_nullable_candidate.sql`

### 수정 파일

| 파일 | 변경 |
|---|---|
| `repository/domain/RepositoryPolicy.java` | 팩토리 `analyzed`·`pending` + **불변식 `reanalyze`** |
| `agent/domain/LlmCallSite.java` | `POLICY` |
| `agent/domain/AgentRunContext.java` | candidateId nullable + `forRepository` |
| `candidate/domain/AgentRun.java` | `Stage.POLICY` · candidateId null 허용 |
| `candidate/application/RecordAgentRunUseCase.java` | 매핑 |
| `src/main/resources/application.yml` | `agent.policy.max-document-chars` |
| `.claude/codemaps/data.md`·`architecture.md` · `glossary.md` | — |

### V5 마이그레이션

```sql
ALTER TABLE repository_policy ADD COLUMN pending_reason TEXT;
ALTER TABLE agent_run ALTER COLUMN candidate_id DROP NOT NULL;
```

⚠️ H2·PostgreSQL 양쪽에서 도는 문법인지 확인한다 (Q-2). `DROP NOT NULL` 은 H2 에서
`SET NULL` 이 필요할 수 있으므로 **양쪽 기동으로 검증**한다.

### 테스트

| 테스트 | 무엇을 잡나 |
|---|---|
| `필수_경로를_못_읽으면_보류한다_S5()` | 핵심 |
| `부가_경로를_못_읽어도_보류하지_않는다_S5()` | 역할 구분 (S-5 ②) |
| `일부는_읽고_일부는_404면_읽은_것으로_판정한다_S5()` | **혼합** — 실전에서 가장 흔한 형태 |
| `필수_경로가_전부_404면_허용한다_S5()` | 금지 표기가 존재할 수 없다 |
| `금지_문구를_찾으면_후보에서_제외한다_S5()` | FR-2 |
| `LLM_판정_불가는_보류다_S5()` | 모델 응답을 진실로 쓰지 않는다 |
| `확장자_변종을_필수_경로에_포함한다_S5()` | `.adoc`·`.rst`·확장자 없음 |
| `상한을_넘는_문서는_절단하지_않고_보류한다_S5()` | S-5 ⑥ |
| `보류_상태에_재분석을_돌려도_허용으로_바뀌지_않는다_S5()` | **허용 판정을 던져도** 거부 |
| `금지_판정은_재분석으로_뒤집히지_않는다_S5()` | FR-2 영속 |
| `못_읽으면_LLM을_부르지_않는다()` | 토큰 절약 |
| `보관된_저장소는_분석하지_않는다()` | 조기 차단 |
| `문서에_토큰_패턴이_있어도_모델로_나가지_않는다_S4()` | **송신 경로** |
| `contribution_rules_에_토큰_패턴이_남지_않는다_S4()` | **영속 경로** — 우회로 |
| `pending_reason_에_예외_원문이_들어가지_않는다_S4()` | 사유 코드만 |
| `정책이_없거나_보류거나_금지면_단언이_실패한다_S5()` | FR-4 |
| `분석_중_활성_트랜잭션이_없다()` | 규율 🔴 |
| `FakeContributionRuleInterpreter` | 소비자 대역 (`@FakeAdapter`) |

🔴 `@AgentIntegrationTest` 만 쓴다 — #42 가 프로파일 이름을 바꾼다.

---

## 4. 구현 순서

| Stage | 내용 | 복잡도 |
|---|---|---|
| 1 | `repository/domain` 능력·값 (역할 구분 · 3분류 · `ScrubbedRules`) | 중간 |
| 2 | `agent`·`candidate` 좌표 확장 (§0) + V5 + 양쪽 DB 검증 | 중간 |
| 3 | `RepositoryPolicy` 팩토리 + **불변식** | 중간 |
| 4 | `LlmContributionRuleInterpreter` — 프롬프트·파싱·보류 판정 | **높음** |
| 5 | `AnalyzeRepositoryPolicyUseCase` + 단언 + 영속 + 조립 | 중간 |
| 6 | 페이크 + 테스트 18종 | **높음** |
| 7 | 문서 동기화 | 낮음 |

---

## 5. 리스크

| 리스크 | 대응 |
|---|---|
| 🔴 확장자 변종 누락 → 「규약 없음」 오역 | Q-8 목록 · 테스트 |
| 🔴 못 읽음을 허용으로 번역 | 3분류 + fail-closed(수집 호출만) |
| 🔴 **원문이 `contribution_rules` 로 스크럽 없이 들어감** | `ScrubbedRules` 값 타입으로 구조 강제 |
| 🔴 재분석이 보류·금지를 덮어씀 | **엔티티 불변식**으로 거부 |
| 🔴 **보류를 만들었는데 푸는 수단이 없다 (#24 미구현)** | 일시적 실패는 재시도 후에만 보류 · 사유 코드로 #24 가 일괄 처리 가능하게. **이 PR 로 보류가 생기면 사람이 DB 를 직접 봐야 한다는 사실을 PR 본문에 명시** |
| V4/V5 out-of-order | **#12 → #7** 머지 순서 합의. 뒤집히면 #12 가 V5 로 내림 |
| `javaVersion`·`buildCommand`·`testCommand` 가 대체로 NULL | §6 에 명시. 빌드 설정 파싱은 #15 |

---

## 6. 범위 밖 — 명시적으로 남긴 것

- **빌드 설정 파싱**(`build.gradle` 에서 Java 버전·명령 추출) — #15.
  ⚠️ **그래서 `javaVersion`·`buildCommand`·`testCommand` 는 이 PR 이후에도 대체로 NULL 이다.**
  `external-deps.md` 가 「`RepositoryPolicy` 의 값으로 이미지·명령을 고른다」고 샌드박스를
  여기 의존시켜 뒀으므로, **「NULL 인 정책으로 샌드박스에 진입할 수 있는가」를 #15·#17 의 선결**로 남긴다.
  적어 두지 않으면 다음 사람이 「채워져 있겠거니」로 읽는다
- **보류 해소 API** — Q-5 · #24
- **임계 정책**(보류율 상한) — Q-8 이 「지금 정하지 않는다」
- **게이트를 실제로 호출하는 지점** — #11 · #24. 단언만 제공하고 **이슈에 남긴다**
- **스캔 트리거 연결** — #14
