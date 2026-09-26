# PLAN-12: Candidate 상태머신 구현 + 전이 불변식

**이슈**: [#12](https://github.com/smileboy0014/ai-oss-contributor-agent/issues/12)
**type**: feature
**작성일**: 2026-09-25
**작성자**: smileboy0014

> **rev 2** — 격리 검토(`gap-analyzer`) 반영. 🔴 2건 · 🟡 6건. 변경 요약은 §10.

## 1. 요구사항

### 배경

`CandidateStatus` enum 은 있지만 **전이 규칙이 코드에 없다.** 지금은 아무 상태에서 아무 상태로
갈 수 있고, 막는 것이 아무것도 없다.

이 제품의 **승인 지점이 전부 상태 전이에 걸려 있다.** 「사람이 최종 승인한다」는 제품 정의는
`ANALYZED → SELECTED` 가 사람 행위로만 일어난다는 것으로 표현되고, 「재시도 상한 소진은
사람에게 넘기는 신호」는 `→ FAILED` 로 표현된다. 전이가 자유로우면 **제품 정의가 코드에
존재하지 않는 것**이다.

오늘 두 미결이 닫히면서 이 이슈를 막던 것이 사라졌다.

| 미결 | 확정 | 이 이슈에 미치는 영향 |
|---|---|---|
| **Q-6** (#36·#39) | `CODE→VERIFY→REVIEW` 한 바퀴 = `attempt` 1, 상한 3, `ANALYZE`·`PLAN` 은 카운터 밖·실패 시 즉시 `FAILED` | 불변식 ⑧의 판정 필드 · `ANALYZING → FAILED` 전이 |
| **Q-5** (#47) | `POST /select` 신설 · `SELECTED → REJECTED` 개방 | 전이 목록 확정 |

### 기능 요구사항 (FR)

| # | 요구사항 | 근거 |
|---|---------|------|
| FR-1 | 허용된 전이만 가능하고, 그 외는 예외로 거부한다 | 이슈 완료조건 · PRD §10 |
| FR-2 | **종단 상태에서 나가는 전이가 없다** (`PR_CREATED`·`REJECTED`·`FAILED`) | 불변식 ① · S-6 |
| FR-3 | `SELECTED` 는 **사람의 명시적 행위로만** 도달하고 `selectedAt` 이 그 증거다 | 불변식 ② · S-6 · Q-5 |
| FR-4 | `SELECTED → REJECTED` (선택 취소)도 사람 행위로만 일어난다 | Q-5 확정 ② |
| FR-5 | 재시도 상한 소진 시 `FAILED` · **상한을 무한으로 만들 수 없다** | 불변식 ⑧ · S-6 |
| **FR-6** | 전이가 **이전 → 이후를 값으로 반환**한다. 로깅은 호출자(UseCase)가 한다 | 🔄 rev 2 — 아래 |
| FR-7 | 후보 루트가 재시도 사이클 수를 자기 상태로 들고 있다 | Q-6 「남은 것」 |
| FR-8 | 테스트 이름에 조항 코드를 넣는다 (`..._S6()`) | `testing-philosophy.md` |
| **FR-9** | **`ANALYZING → FAILED`** — 분석 실패 후보가 박히지 않는다 | 🆕 rev 2 · Q-6 확정 |
| **FR-10** | 전이는 `updatedAt` 을 갱신하고, 동시 전이는 **낙관적 락**으로 막는다 | 🆕 rev 2 |
| **FR-11** | 후보 **생성 팩토리** — `DISCOVERED` 로 들어오는 유일한 문 | 🆕 rev 2 |

#### 🔄 FR-6 을 바꾼 이유 — 엔티티 로깅을 철회한다

rev 1 은 엔티티가 SLF4J 로 직접 로깅하게 했다. **철회한다.** 규율 ① 해석 문제가 아니라
**로그가 거짓말을 하기 때문**이다.

전이는 UseCase 트랜잭션 안에서 일어난다. 커밋 실패·후속 예외로 **롤백되면 DB 는 되돌아가지만
로그 라인은 이미 나갔다.** `logging.md` 는 안전 게이트 로그의 목적을 「사고 후 **막았는가**를
증명할 수 있어야 한다」로 규정하는데, 커밋 여부와 어긋날 수 있는 로그는 그 증명을 못 한다.
`selectByHuman` 이 S-6 승인의 증거라 이 괴리가 가장 아픈 자리다.

두 번째 이유 — #24 가 UseCase 를 만들면 거기서도 전이를 로깅하게 되고(안 하면 MDC 를 채울
이유가 없다) **같은 전이가 두 줄** 찍힌다. 그때 누군가 엔티티 로그를 지운다.

대신 전이 메서드가 `StatusTransition(from, to)` 를 반환한다. **테스트에서 전이 결과를 직접
단언할 수 있어 검증이 오히려 강해진다** — 로그 캡처 테스트가 필요 없다.

⚠️ 「지금 UseCase 가 없어 아무것도 로그되지 않는다」는 **요구를 재조정할 사유이지 아키텍처
규칙을 구부릴 사유가 아니다.** 호출자가 없으면 관측할 대상 자체가 없다.

### 비기능 요구사항 (NFR)

| # | 항목 | 기준 |
|---|------|------|
| NFR-1 | 커버리지 | **안전 경계 경로 100%** — 예외 없음 |
| NFR-2 | 순수성 | 대외 호출·DB 조회 없이 판정. 유닛 테스트만으로 전수 검증 가능 |
| NFR-3 | 레이트리밋·타임아웃·LLM 비용 | **해당 없음** — 대외 호출이 없다 |

## 2. 게이트 판정

### 안전 경계 (S-1 ~ S-6)

| 조항 | 접촉 | 어떻게 지키는가 |
|---|---|---|
| S-1 원본 저장소 쓰기 금지 | — | push·remote 를 다루지 않는다. 이 도메인에 GitHub 호출이 없다 |
| S-2 항상 draft · 자동 머지 금지 | 🔵 인접 | draft 고정은 `PullRequestStatus` 단일값 + DB `CHECK` 가 이미 보장. **다만 `markPrCreated()` 가 `pullRequest != null` 을 요구**한다 — 「`PR_CREATED` 인데 PR 행이 없다」는 애그리거트 안에서만 막을 수 있다(불변식 ③) |
| S-3 샌드박스 밖 실행 금지 | — | 대상 저장소 코드를 실행하지 않는다 |
| S-4 시크릿 유출 금지 | 🔵 인접 | 반환값·예외 메시지에 **상태 이름과 식별자만** 싣는다. `analysis`(LLM 응답)·`diff` 를 담지 않는다 |
| **S-5 대상 저장소 규약 우선** | 🟡 **인접 — 게이트의 문을 이 PR 이 만든다** | 아래 |
| **S-6 승인 지점 우회 금지** | 🔴 **이 이슈가 실행체다** | 아래 |

#### 🟡 S-5 — 「미접촉」이 아니다 (rev 2 정정)

판정 **데이터**(`RepositoryPolicy`·`aiContributionAllowed`)는 `repository` 애그리거트에 있다.
그러나 S-5 가 지키는 문은 「`RepositoryPolicy` 없이 **구현 단계로 넘어가지 않는다**」이고,
**그 문이 이 PR 이 만드는 `startImplementing()`** 이다.

이 PR 이후 도메인에는 전제조건 없이 `IMPLEMENTING` 으로 들어갈 수 있는 공개 메서드가 존재한다.
S-5 준수는 전적으로 「#24 가 UseCase 에서 잊지 않고 검사한다」에 걸린다.

**이번에는 타입 강제를 넣지 않는다.** `ContributionReadiness` 같은 값 타입을 만들어도
**그것을 생산할 UseCase 가 아직 없어** 계약이 추측이 된다. 대신 두 가지를 남긴다.

1. `startImplementing()` javadoc 에 **「호출자는 S-5 를 확인할 의무를 진다」**를 명시
2. **#24 가 S-5 블로킹 의무를 승계**한다는 것을 이 계획서와 PR 본문에 남긴다

⚠️ 「—」로 두면 리뷰 체크리스트에서 이 조항이 영영 사라진다. 그래서 🟡 로 올린다.

#### 🔴 S-6 준수 방법 — 이 PR 의 핵심

| 요구 | 코드에서 |
|---|---|
| `SELECTED` 는 사람 행위로만 | `selectByHuman(Clock)` **하나만** `selectedAt` 을 채운다. `setStatus` 류 공개 메서드를 만들지 않는다 |
| 종단에서 나가는 전이 없음 | 허용 집합을 `CandidateStatus` 가 소유하고, 종단 3개의 집합이 **빈 집합**이다 |
| 재시도 상한이 살아 있음 | **도메인이 절대 상한을 소유**하고 넘겨받은 값을 검증한다 — 아래 설계 ③ |
| 승인 게이트 통과를 증명 | 전이가 `StatusTransition` 을 반환해 호출자가 **막은 것도 통과한 것도** 남길 수 있게 한다 |

### 미결 대조 (Q-1 ~ Q-11)

| 항목 | 걸리는가 | 처리 |
|---|---|---|
| **Q-6** 재시도 단위 | ✅ | **확정됨**(#36·#39) — 그대로 구현. FR-9 가 「`ANALYZE`·`PLAN` 실패는 즉시 `FAILED`」를 이행 |
| **Q-5** 선택 UI | ✅ | **확정됨**(#47) — `select` · `SELECTED→REJECTED` |
| Q-3·Q-4·Q-8·Q-9·Q-10·Q-11 | — | 미접촉 |

**가정** — 미결은 아니지만 해석이 갈릴 수 있어 고정한다.

1. **상한 3 = 최대 3바퀴**(첫 시도 포함). 근거는 Q-6 의 곱셈 예산 **`3 × (1+2) = 9`** 다 —
   파이프라인 쪽 `3` 이 총 시도 수로 계산돼 있다. PRD §17 의 `{Retry Count < 3?}` 와도 맞는다
   (그 게이트는 **실패 후에만** 도달하므로 1·2 바퀴째 실패에서만 통과 → 정확히 3바퀴).

   ⚠️ **`application.yml` 의 이름은 `agent.execution.max-retries` 인데 의미는 attempts 다.**
   1 만큼 다른 개념이라, 나중에 누군가 이름을 읽고 「off-by-one 버그」로 오인해 고칠 자리다.
   그때 **Q-6 의 곱셈 예산이 함께 무효가 된다.** 지금 개명하지 않는 이유는 그 키를
   `open-questions.md`·`architecture.md`·`external-deps.md` 가 인용하고 있고
   `external-deps.md` 는 방금 #41 이 고친 파일이라 교차 충돌을 만들기 때문이다.
   **개명은 #21(재시도 전략)이 그 프로퍼티를 실제로 읽을 때 함께 한다** — 이 계획이 그 사실을 남긴다.

## 3. 스코프

| 도메인 | 변경 | 소유 판정 근거 |
|---|---|---|
| `candidate` | 수정 | 상태머신의 주체가 `ContributionCandidate` 다 |

**도메인 간 계약** — 해당 없음. `candidate/domain` 안에서 닫힌다.

### 불변식 10개 — 전수 회계 (rev 2)

경계를 「데이터가 어디 있는가」가 아니라 **「어느 전이가 그 불변식을 지키는 문인가」**로 가른다.

| # | 불변식 | 이 PR | 근거 |
|---|---|---|---|
| ① | 종단에서 나가는 전이 없음 | ✅ **구현** | 허용 집합 공집합 + 전수 대조 테스트 |
| ② | `SELECTED` 는 사람 행위로만 | ✅ **구현** | `selectByHuman(Clock)` 단일 경로 |
| ③ | PR 은 항상 draft | 🟡 **부분** | draft 고정은 enum + `CHECK` 가 이미 보장. **`markPrCreated()` 의 `pullRequest != null` 요구만 이 PR** |
| ④ | push 대상은 Fork 뿐 | ❌ 범위 밖 | 이 도메인에 push 코드가 **물리적으로 없다** — #22 |
| ⑤ | 대상 저장소 실행은 샌드박스 | ❌ 범위 밖 | 〃 — #17 |
| ⑥ | `RepositoryPolicy` 없이 구현 못 감 | 🟡 **문만 이 PR** | 판정은 UseCase(#24). javadoc 의무 명시 — §2 S-5 |
| ⑦ | AI 기여 판정 실패는 보류 | 🟡 **문만 이 PR** | 〃 — #7 이 판정, #24 가 게이트 |
| ⑧ | 재시도 상한 무한 금지 | ✅ **구현** | 도메인 절대 상한 — 설계 ③ |
| ⑨ | 후보는 이슈당 1건 | ✅ **이미 보장** | `uk_contribution_candidate_issue UNIQUE(issue_id)` — `V2:107`. 코드 변경 불필요 |
| ⑩ | 종단 상태 행을 삭제하지 않는다 | ❌ 범위 밖 | **삭제 경로가 존재하지 않는다.** `cascade` 미설정이 이미 그 표현. ⚠️ 원래 「삭제 API 가 생기는 #13 이 지킨다」고 적었으나 **#13 에 삭제 API 는 없다**(대상이 `GET` 둘뿐) — 2026-09-26 정정. 삭제 경로를 만드는 이슈가 생기면 그때 지킨다 |

### 범위 밖 — 의도적으로 하지 않는 것

| 하지 않는 것 | 어디서 | 왜 |
|---|---|---|
| UseCase · API 엔드포인트 | #13 · #24 | 이 PR 은 **도메인 규칙**이다. 트랜잭션 경계는 UseCase 몫 |
| 전이 로깅 · MDC | #13 · #24 | FR-6 참조 — 롤백 시 로그가 거짓이 된다 |
| 불법 전이의 HTTP 매핑(409) | #24 | 엔드포인트가 없다. `ApiExceptionHandler` 승계 대상으로 기록 |
| `agent.execution.max-retries` 개명 | #21 | §2 가정 1 |

## 4. 기술 설계

### 변경 파일

| # | 경로 | 레이어 | 신규/수정 | 내용 |
|---|------|--------|----------|------|
| 1 | `candidate/domain/CandidateStatus.java` | domain | 수정 | 허용 전이 집합 · `isTerminal()` · `canTransitionTo()` |
| 2 | `candidate/domain/StatusTransition.java` | domain | **신규** | `record StatusTransition(CandidateStatus from, CandidateStatus to)` |
| 3 | `candidate/domain/CandidateTransitionException.java` | domain | **신규** | `IllegalStateException` 상속 |
| 4 | `candidate/domain/ContributionCandidate.java` | domain | 수정 | 팩토리 · 전이 메서드 · `attempt` · `version` |
| 5 | `db/migration/V4__add_candidate_attempt_and_version.sql` | — | **신규** | `attempt` · `version` 컬럼 |
| 6 | `candidate/domain/CandidateStatusTest.java` | test | 신규 | 전이 전수 대조 |
| 7 | `candidate/domain/ContributionCandidateTest.java` | test | 신규 | 불변식 · S-6 경로 |

### 설계 ① — 전이 규칙은 `CandidateStatus` 가 소유한다

```java
public boolean isTerminal();                          // 허용 집합이 비었는가
public boolean canTransitionTo(CandidateStatus next);
```

🔴 **종단 판정을 하드코딩 목록으로 하지 않는다.** `isTerminal()` 은 「허용 집합이 비었는가」로
정의한다. 목록을 따로 두면 전이를 추가하면서 갱신을 잊는 순간 **종단에서 나가는 길이 조용히
열린다** — 불변식 ①이 무너지는 가장 현실적인 경로다.

### 설계 ② — 전이 메서드는 의도를 이름으로 드러낸다

`setStatus(status)` 를 만들지 않는다. **범용 setter 가 있으면 모든 불변식이 우회 가능**해진다(Q-7).
**모든 전이 메서드가 `Clock` 을 받고 `StatusTransition` 을 반환한다**(FR-6 · FR-10).

| 메서드 | 전이 | 주체 |
|---|---|---|
| `discover(issueId, Clock)` *(static 팩토리)* | — → `DISCOVERED` | 스캐너 |
| `startAnalysis(Clock)` | `DISCOVERED → ANALYZING` | 시스템 |
| `completeAnalysis(Clock)` | `ANALYZING → ANALYZED` | 시스템 |
| **`failAnalysis(Clock)`** | **`ANALYZING → FAILED`** 🆕 | 시스템 |
| `rejectAsInfeasible(Clock)` | `ANALYZED → REJECTED` | 시스템 |
| **`selectByHuman(Clock)`** | `ANALYZED → SELECTED` | **사람** |
| **`cancelSelection(Clock)`** | `SELECTED → REJECTED` | **사람** |
| `startImplementing(int maxAttempts, Clock)` | `SELECTED → IMPLEMENTING` (`attempt = 1`) | 사람이 트리거 |
| `startTesting(Clock)` | `IMPLEMENTING → TESTING` | 시스템 |
| `startReview(Clock)` | `TESTING → REVIEWING` | 시스템 |
| `retryImplementation(int maxAttempts, Clock)` | `TESTING·REVIEWING → IMPLEMENTING` (`attempt++`) | 시스템 |
| `markReadyForPr(Clock)` | `REVIEWING → READY_FOR_PR` | 시스템 |
| `markPrCreated(Clock)` | `READY_FOR_PR → PR_CREATED` — **`pullRequest != null` 요구** | 사람이 트리거 |
| `fail(Clock)` | `IMPLEMENTING·TESTING·REVIEWING → FAILED` | 시스템 |

🔴 **`selectByHuman` 만이 `selectedAt` 을 채운다.** 이름에 `ByHuman` 을 박은 것은
**호출부 리뷰에서 눈에 띄게** 하기 위해서다.

🆕 **FR-9** — `ANALYZING` 은 종단이 아닌데 rev 1 에서는 나가는 길이 `ANALYZED` 하나뿐이었다.
LLM 분석이 실패하면 후보가 **영구히 박힌다.** 재분석도 `FAILED` 도 불가능해
「상한 소진은 사람에게 넘기는 신호」가 이 경로에서 발생하지 않는다.
Q-6 확정문의 「`ANALYZE`·`PLAN` — 실패는 즉시 `FAILED`」가 이것이다.

### 설계 ③ — 재시도 상한: 도메인이 **절대 상한**을 소유한다 (rev 2 정정)

rev 1 의 `maxAttempts < 1` 검사는 **반대 방향을 막고 있었다.** `0` 은 무한이 아니라 최강 제약
(`attempt >= 0` 은 항상 참 → 즉시 `FAILED`)이고, 정작 위험한 `maxAttempts = 10000` 은
아무 저항 없이 통과했다. **방어가 있다고 표기했으나 실물이 없었다.**

```java
/** PRD §17 · Q-6. 설정으로 이 값을 넘을 수 없다 — 불변식 ⑧ */
static final int MAX_ALLOWED_ATTEMPTS = 3;

private void guardAttemptBudget(int maxAttempts) {
    if (maxAttempts < 1 || maxAttempts > MAX_ALLOWED_ATTEMPTS) {
        throw new IllegalArgumentException(...);
    }
}
```

**「도메인이 설정을 읽지 않는다」와 충돌하지 않는다** — 읽는 것이 아니라 **넘겨받은 값을
검증**하는 것이다. 상한을 올리려면 **도메인 코드를 고쳐야 하고, 그것은 리뷰에 보인다.**
이것이 「코드에서 무한으로 바꾸지 않는다」(S-6)의 실제 구현이다.

⚠️ `attempt` 는 **`CODE→VERIFY→REVIEW` 한 바퀴**를 센다(Q-6). `IMPLEMENTING` 진입마다 +1 이고
`TESTING`·`REVIEWING` 은 같은 바퀴라 올리지 않는다.

### 설계 ④ — `updatedAt` 과 낙관적 락 (rev 2 신설)

**(a) `updatedAt`** — 컬럼이 `NOT NULL` 인데 이 프로젝트에 JPA Auditing 도 `@PreUpdate` 도 **없다**.
rev 1 은 `selectByHuman` 에만 `Clock` 을 줘서 나머지 전이가 `updatedAt` 을 갱신할 수단이 없었다.
「마지막으로 상태가 움직인 시각」이 DB 에서 거짓이 된다 — 파이프라인 관측의 기본 축이다.
**모든 전이가 `Clock` 을 받는다.**

**(b) `@Version`** — 트랜잭션 둘이 동시에 `SELECTED` 를 읽으면 **둘 다 전이에 성공**하고
last-write-wins 로 `IMPLEMENTING` 이 된다 → **같은 후보에 구현 사이클 2개**
(30분 샌드박스 ×2, LLM 과금 ×2, 브랜치 2개).

체크리스트 6 의 「같은 전이 두 번은 예외」는 **한 인스턴스 안에서만** 참이다.
상태머신이 이 제품의 유일한 중복 실행 방어인데 `@Version` 없는 read-modify-write 는
그 방어를 제공하지 못한다. 엔티티 필드이므로 이 PR 범위 안이고 컬럼도 V4 에 함께 넣는다.

### 데이터 모델

| 테이블 | 컬럼 | 타입 | 제약 |
|---|---|---|---|
| `contribution_candidate` | `attempt` | `INTEGER` | `NOT NULL DEFAULT 0` |
| `contribution_candidate` | `version` | `BIGINT` | `NOT NULL DEFAULT 0` |

`attempt = 0` 은 「아직 구현에 착수하지 않음」. 첫 `IMPLEMENTING` 진입에서 `1`.

⚠️ `DEFAULT` 가 필요한 이유는 **`ALTER TABLE ... ADD COLUMN NOT NULL` 이 기존 행을 채워야 하기
때문**이다(`ddl-auto: validate` 는 nullability 만 보고 DEFAULT 를 요구하지 않는다).
지금은 행이 없지만 운영 DB 를 쌓기 시작하면 필수다.

⚠️ H2·PostgreSQL 공통 문법만(Q-2). `ALTER TABLE ... ADD COLUMN ... NOT NULL DEFAULT 0` 은 양쪽에서 동작한다.

⚠️ **V4 는 `contribution_candidate` 만 건드린다.** #7 이 `repository_policy` 로 V5 를 쓴다 — 확인함.

### 체크리스트 답변

| # | 항목 | 답 |
|---|------|---|
| 1 | 소유 도메인 | `candidate` — 상태머신의 주체가 이 애그리거트 루트 |
| 2 | 레이어 배치 | **domain** — 결정 트리 Q1 「자기 애그리거트 데이터만으로 판단·전이 가능한가」 = YES |
| 3 | 능력 인터페이스 | **불필요** — 대외 의존 없음 |
| 4 | 🔴 트랜잭션 안 대외 호출 없음 | ✅ **대외 호출 자체가 없다** |
| 5 | 상태 전이 영향 | ✅ 이 PR 이 전이 규칙 그 자체. 종단 3개 공집합을 테스트로 고정 |
| 6 | 멱등성 | 같은 전이 2회 → 두 번째는 예외. **인스턴스 간 동시성은 `@Version`** — 설계 ④(b) |
| 7 | `Clock` 주입 | ✅ **모든 전이 메서드** — 설계 ④(a) |
| 8 | 🔴 안전 경계 | §2 — S-6 본체 · **S-5 🟡 인접(#24 승계)** |

## 5. 구현 순서

### 실행 모드: sequential

**판정 근거** — Stage 2 가 Stage 1 의 `canTransitionTo` 를 호출하고 Stage 3 테스트가 둘 다
import 한다. `contract-and-impl` 유형이라 병렬 조건을 만족하지 못한다.

| Stage | 내용 | 선행 조건 | 파일 |
|-------|------|----------|------|
| 1 | `CandidateStatus` 전이 규칙 · `StatusTransition` · 예외 | 없음 | 1, 2, 3 |
| 2 | `ContributionCandidate` 팩토리·전이·`attempt`·`version` · 마이그레이션 | Stage 1 | 4, 5 |
| 3 | 테스트 | Stage 2 | 6, 7 |
| **4** | **문서 동기화** (§9) | Stage 3 | 🆕 rev 2 |

## 6. 테스트 계획

| # | 레벨 | 대상 | 검증 내용 |
|---|------|------|----------|
| 1 | 유닛 | `CandidateStatus` | **전이 전수 대조** — 11×11 조합을 표와 대조. 허용 외 전부 거부 |
| 2 | 유닛 | 종단 | `종단_상태에서는_어떤_전이도_일어나지_않는다_S6()` — 종단 3개 × 모든 목적지 |
| 3 | 유닛 | `selectByHuman` | `SELECTED_는_사람_행위로만_도달한다_S6()` |
| 4 | 유닛 | 선택 취소 | `사람은_선택을_취소할_수_있다_S6()` |
| 5 | 유닛 | 상한 소진 | `재시도_상한을_소진하면_FAILED_다_S6()` |
| **5b** | 유닛 | **절대 상한** | `상한을_무한으로_만들_수_없다_S6()` — **큰 값 거부가 본체**. `0`·음수 거부는 부수 |
| 6 | 유닛 | `attempt` 증가 | 한 바퀴에 1. `TESTING`·`REVIEWING` 은 올리지 않음 |
| **7** | 유닛 | **분석 실패** | `분석에_실패하면_FAILED_로_간다_S6()` — FR-9 |
| **8** | 유닛 | **PR 전제** | `PR_행_없이_PR_CREATED_로_갈_수_없다_S2()` — 불변식 ③ |
| 9 | 유닛 | 멱등성 | 같은 전이 2회 → 두 번째 예외 |
| **10** | 유닛 | `updatedAt` | 모든 전이가 갱신 — 고정 `Clock` 으로 단언 |
| 11 | 통합 | Flyway | `SchemaMigrationTest` 가 V4 적용 + `validate` 를 **양쪽 DB**에서 확인 |

**테스트 진입 경로** — 모든 테스트는 `discover()` 팩토리에서 시작해 **합법 전이만으로** 목표
상태에 도달한다(FR-11). 리플렉션으로 상태를 주입하지 않는다. 그렇게 하면
**「종단까지 가는 경로 자체」도 함께 검증**된다.

**대외 호출 대체** — **해당 없음.** GitHub·LLM·샌드박스 호출이 없어 Q-9 의 3계층 중 어느 층도
필요하지 않다.

**전수 대조를 택한 이유** — 「허용된 것이 되는가」만 보면 **허용되지 않아야 할 것이 되는지**는
검증되지 않는다. S-6 는 후자가 깨질 때 무너진다.

## 7. 리스크

| # | 리스크 | 영향 | 대응 |
|---|--------|------|------|
| 1 | 종단 판정을 별도 목록으로 두면 갱신을 잊는다 | 불변식 ① 붕괴 | `isTerminal()` = 허용 집합 공집합 |
| 2 | `max-retries` 이름과 attempts 의미 불일치 | 미래의 off-by-one 「수정」이 Q-6 예산을 무효화 | §2 가정 1 에 기록 · 개명은 #21 |
| 3 | `attempt` 를 `AgentRun.attempt` 와 혼동 | 비용 집계 왜곡 | **`CODE`·`VERIFY`·`REVIEW` 행에 한해 같은 값**. `ANALYZE`·`PLAN` 행은 항상 1 이고 그때 루트는 0 이다 — javadoc 에 명시 |
| 4 | V4 가 H2·PostgreSQL 에서 갈라짐 | 기동 실패 | 공통 문법 · `SchemaMigrationTest` 양쪽 확인 |
| 5 | **S-5 게이트가 #24 로 미뤄진다** | 규약 미확인 저장소에 구현 착수 | javadoc 의무 명시 + #24 승계를 PR 본문에 · §2 S-5 |
| 6 | `@Version` 추가로 기존 통합 테스트가 깨질 수 있다 | 빌드 적색 | 현재 후보를 쓰는 테스트가 없다(조회 API 는 빈 배열 고정) |

**대외 호출 실패 시나리오** — **해당 없음.**

## 8. 복잡도

| Stage | 파일 수 | 복잡도 |
|-------|--------|--------|
| 1 | 3 | 낮음 |
| 2 | 2 | **높음** — 전이 14개 · 절대 상한 · `@Version` · `updatedAt` |
| 3 | 2 | 중간 — 전수 대조라 양이 많다 |
| 4 | 3 | 낮음 |

## 9. 문서 동기화 대상

| 대상 | 필요 | 이유 |
|---|---|---|
| `codemaps/architecture.md` | — | 도메인·경계 변경 없음 |
| `codemaps/data.md` | ✅ | `attempt` · `version` 컬럼 |
| `codemaps/domain.md` | ✅ | **①** 전이 표에 `ANALYZING → FAILED` 추가 **②** 224줄의 「3회의 단위가 미정 → Q-6」 경고 제거(Q-6 은 #36 에서 닫혔는데 이 문단이 남아 있다) **③** 불변식 ⑧ 판정 필드 확정 |
| `rules/context/open-questions.md` | ✅ | Q-6 의 「남은 것」(불변식 ⑧ 필드 형태)이 닫힌다 + 프로퍼티 개명을 #21 로 넘긴 기록 |
| `.env.example` · `README.md` · `glossary.md` | — | 새 환경변수·구조 변경·새 용어 없음 |
| `rules/conventions/architecture.md` | — | **엔티티 로깅을 철회해 규율 ① 개정이 불필요해졌다**(rev 1 이면 필요했다) |

## 10. 변경 이력

| 일자 | 작성자 | 변경 내용 |
|------|--------|----------|
| 2026-09-25 | smileboy0014 | 초안 — Q-5·Q-6 확정 직후 |
| 2026-09-25 | smileboy0014 | **rev 2** — 격리 검토 반영. 🔴 `ANALYZING→FAILED` 누락(FR-9) · 🔴 상한 검사가 반대 방향(설계 ③). 🟡 엔티티 로깅 철회(FR-6) · S-5 「미접촉」→「🟡 인접」 · 생성 팩토리(FR-11) · `Clock`·`@Version`(FR-10) · 불변식 10개 전수 회계 · 프로퍼티 개명을 #21 로 · 문서 Stage 신설 |
