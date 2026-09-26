# PLAN-24: 승인 지점 API — `SELECTED` 전이를 사람 행위로

**이슈**: [#24](https://github.com/smileboy0014/ai-oss-contributor-agent/issues/24)
**타입**: feature
**작성일**: 2026-09-26 (rev.3 — 재검토 반영. rev.2 는 1차 검토의 중대 7건 반영)

---

## 0. 이 이슈가 무엇인가

**S-6 의 API 측 실행체다.** #12 가 상태머신으로 「자동으로 도달할 수 없다」를 만들었고,
이 PR 이 **「사람이 부르는 문」**을 만든다. 둘이 짝이다.

### 계약 표면

```
candidate/adapter/in/web
  CandidateController  + POST /api/candidates/{id}/select
                       + POST /api/candidates/{id}/reject     ← 정본 표기 (codemaps)
        ▲
candidate/application
  SelectCandidateUseCase      선정 · 취소. 트랜잭션 경계 + 게이트 기록
        ▲
candidate/domain
  ContributionCandidate  .selectByHuman / .cancelSelection   ← #12 가 이미 만들었다
  🔴 startImplementing(PolicyClearance, int, Clock)          ← 시그니처 변경 + null 가드

repository/domain
  🔴 PolicyClearance          final class · 패키지 가시성 생성자
  🔴 RepositoryPolicy.clearance()   발급하는 유일한 곳
  RepositoryPolicy.resolvePending() 보류 해소 (reanalyze 와 다른 문)

repository/application
  AnalyzeRepositoryPolicyUseCase.clearanceFor(repositoryId)  ← 바깥으로 나가는 유일한 경로
  ResolvePolicyPendingUseCase

db/migration/V8__policy_resolution.sql     ← 해소 흔적
```

---

## 1. 요구사항

| # | 완료조건 | 이 PR |
|---|---|---|
| FR-1 | Q-5 결론 + `open-questions.md` 갱신 | ✅ **이미 끝났다** — #47 |
| FR-2 | `SELECTED` 로 가는 전이가 **API 호출로만** | ✅ |
| FR-3 | `implement`·`verify`·`pull-request` 도 명시적 호출로 | ⚠️ **엔드포인트는 만들지 않는다** — §1.1 |
| FR-4 | **승인 게이트 통과 기록** | ⚠️ **부분** — §3.5 |
| FR-5 | 테스트 — 자동 전이 경로가 없음을 검증 | ✅ §4.4 |
| FR-6 | (Q-5 코멘트) **Q-8 보류 해소 경로** | ✅ §3 |
| FR-7 | (#12 에스컬레이션) **S-5 게이트를 컴파일러에 넘긴다** | ✅ §2.2 |

### 1.1 🔴 FR-3 — 엔드포인트를 만들지 않는 이유 (사용자 결정 · 2026-09-26)

만들면 **빠져나올 수 없는 상태**가 생긴다.

| 엔드포인트 | 보내는 곳 | 나가는 길 | 결과 |
|---|---|---|---|
| `pull-request` | `PR_CREATED` | **없다 (종단)** | 🔴 PR 이 없는데 `PR_CREATED`. 되돌릴 수 없다 |
| `implement` | `IMPLEMENTING` | `TESTING`·`FAILED` — **둘 다 부르는 코드가 없다** | 후보가 박힌다 |
| `verify` | — | — | **S-6 게이트 3개에 없다.** 실행체는 #19 |

근거를 셋으로 적는다.

1. **실패의 방향이 갈린다.** 만드는 쪽의 실패는 불변식 ①(종단에서 나가는 전이 없음)에 막혀
   **사람도 못 되돌린다.** 안 만드는 쪽의 실패는 「나중에 추가」다 —
   `external-deps.md` 의 「모르면 되돌릴 수 없는 쪽을 피한다」.
2. **S-6 이 막는 것은 엔드포인트 부재가 아니라 자동 흐름이다.** 지금 그쪽으로 가는 경로는
   API 도 스케줄러도 없다. 열어 두지 않은 것이 게이트를 약화시키지 않는다.
3. 🔴 **`implement` 는 이 PR 에서 올바르게 구현할 수가 없다.** `startImplementing` 이
   `PolicyClearance` 를 요구하는데 **후보 → 이슈 → `repositoryId` 단건 조회가 없다**
   (`FindAnalyzableIssuesUseCase` 는 페이지 조회뿐). 미룬 것이 아니라 재료가 없다.

⚠️ `markPrCreated` 의 javadoc 이 이미 「`PR_CREATED` 인데 PR 행이 없다를 여기서 막지 않는다 —
가드는 #23」이라고 적어 뒀다. 엔드포인트를 지금 만들면 **아무 가드도 없이** 종단에 꽂힌다.

> 🔴 **이월은 계획서가 아니라 이슈 본문에 한다.** 코멘트는 #24 가 닫히면 증발한다 —
> **#18·#23 의 수용조건으로 옮겨 적는다**(Stage 6).

---

## 2. 게이트 판정

### 2.1 🔴 S-6 — 이 PR 이 조항의 API 측이다

| S-6 요구 | 이 PR |
|---|---|
| `SELECTED` 전이가 **명시적 행위** | `POST /select` 하나 |
| 스케줄러가 끝까지 흘려보내지 않는다 | 다음 단계로 가는 API 자체가 없다 |
| 종단에서 나가는 전이 금지 | 전이 판정은 전부 #12 의 `CandidateStatus` 다. **여기서 새로 열지 않는다** |
| 재시도 상한 | 미접촉 — `attempt` 를 건드리지 않는다 |

#### 선택 취소가 종단으로 간다 — 의도다

Q-5 확정 ②. 근거는 **「번복이 가벼우면 승인이 가벼워진다」**.

⚠️ **경로는 `POST /candidates/{id}/reject` 다** — `codemaps/domain.md` 전이표가 정본이다.
rev.1 은 `DELETE /select` 로 적었는데 **정본을 말없이 바꾼 것**이었고, 게다가 `DELETE` 는
관용적으로 「되돌리기·멱등 정리」로 읽혀 **종단으로 간다는 사실을 가린다.**

### 2.2 🔴 S-5 — #12 에서 넘어온 에스컬레이션 조건

> **#24 가 정책 확인 없이 `startImplementing` 을 부르면 그 PR 에서 🔴 블로킹이다.**

이 PR 은 `startImplementing` 을 **부르지 않는다**(엔드포인트가 없다). 즉 조건의 전건이
성립하지 않아 **공허하게 참**이다. 거기 머물지 않고 **시그니처를 바꿔 호출을 구조적으로
강제**한다.

#### rev.1 의 설계는 Java 문법상 성립하지 않았다

```java
// ❌ rev.1 — public record 는 정본 생성자를 숨길 수 없다.
//    record 의 정본 생성자는 record 자신의 접근 수준 이상이어야 한다.
//    즉 new PolicyClearance(42L) 이 어디서든 컴파일된다
public record PolicyClearance(Long repositoryId) { … }
```

**「컴파일러가 막는다」가 선언 형태에서 이미 무너져 있었다.**

```java
// ✅ rev.2 — repository/domain
public final class PolicyClearance {
    private final Long repositoryId;

    // 🔴 패키지 가시성. 같은 패키지의 RepositoryPolicy 만 만들 수 있다
    PolicyClearance(Long repositoryId) { … }
}

// repository/domain — 발급하는 유일한 곳
public class RepositoryPolicy {
    /** @throws ContributionNotAllowedException 보류·금지 */
    public PolicyClearance clearance() {
        if (!allowsContribution()) { throw …; }
        return new PolicyClearance(repository.getId());
    }
}
```

**왜 `RepositoryPolicy` 가 발급하는가** — rev.1 은 `PolicyClearance.of(RepositoryPolicy)` 를
공개 팩토리로 뒀는데, 그러면 **`candidate` 가 그것을 부르려고 `RepositoryPolicy` 를
import 하게 된다** — 규율 ④ 가 막는 바로 그 의존이다. 엔티티가 자기 통행증을 발급하면
바깥은 `PolicyClearance` 만 보면 된다.

**발급 경로가 하나로 줄어든 부수 효과** — `clearanceFor(repositoryId)` 로 받은 clearance 의
`repositoryId` 는 **항상 그 id** 다. rev.1 이 걱정한 「다른 저장소의 clearance」가
**`repository.domain` 패키지 밖에서는 만들 수 없게** 되고, UseCase 의 검증 의무도 사라진다.

⚠️ **「설계상 발생 불가」라고 적지 않는다**(rev.2 정정). 패키지 가시성은 봉인이 아니다 —
`com.ossagent.repository.domain` 에 클래스를 하나 추가하면 누구나 생성자를 부른다(JPMS 없음).
막을 필요는 없지만(테스트 픽스처에 오히려 유용하다) **과장된 안전 표현은 다음 사람이 확인을
건너뛰게 한다** — S-1 이 「쓰기 메서드를 만들지 않았으니 안전하다」를 방어로 세지 않기로 한
것과 같은 이유다.

#### 🔴 `clearanceFor` 의 계약 — `Optional` 을 쓰지 않는다

```java
/** @throws ContributionNotAllowedException 행 없음(NOT_ANALYZED) · 보류 · 금지 */
@Transactional(readOnly = true)
public PolicyClearance clearanceFor(Long repositoryId)
```

🔴 `Optional<PolicyClearance>` 로 두면 **무시할 수 있는 게이트**가 된다 —
`.orElse(null)` 한 줄이면 끝이다. 바로 옆 `assertContributionAllowed` 의 javadoc 이
「`boolean` 이 아니라 예외인 것이 설계다. 반환값은 무시할 수 있지만 예외는 무시하기 어렵다」로
못 박아 둔 그 함정이고, **같은 클래스에서 두 문이 다른 기준을 쓰지 않는다.**

⚠️ **정책 행이 없는 경우는 `clearance()` 가 판정할 수 없다** — 엔티티가 없으니 부를 대상이
없다. `clearanceFor` 만이 `NOT_ANALYZED` 를 던질 수 있다. §3.1 이 해소 API 쪽에서 가린
상태를 **착수 게이트 쪽에서도** 가려야 한다.

⚠️ `@Transactional(readOnly = true)` 가 필수다. `RepositoryPolicy.repository` 가 LAZY 라
트랜잭션 밖에서 `clearance()` 를 부르면 `LazyInitializationException` 이 난다.
**게이트가 그런 이유로 죽으면 호출자가 그것을 `catch` 해 넘길 위험이 생긴다** —
`assertContributionAllowed` javadoc 과 같은 문장을 단다.

#### ⚠️ S-5 게이트의 문이 둘이 된다

`assertContributionAllowed`(#7·#11 이 쓴다)와 `clearanceFor`(#18 이 쓸 것) 둘이 같은 클래스에
공존한다. **각자 진화하면 한쪽에만 `resolvedAt` 의미가 들어가는 식으로 갈라진다.**

> **`assertContributionAllowed` 를 `clearanceFor` 위에 얹는다** — 반환을 버리는 한 줄.
> 판정 로직이 한 자리에 남는다.

#### 🔴 그래도 `null` 은 컴파일된다

`startImplementing(null, 3, clock)` 은 정상 컴파일이다. **타입 게이트는 런타임 null 가드가
없으면 게이트가 아니다.**

- `startImplementing` 이 `clearance == null` 을 거부한다
- ⚠️ 테스트를 **「시그니처 단언」으로 쓰지 않는다.** 리플렉션으로 시그니처를 확인하는 테스트는
  **아무것도 막지 못하면서 초록**이고, 「손수 짠 리플렉션은 쓰지 않는다」는 이 저장소의 기록된
  교훈에 걸린다. **`null` 을 넣으면 예외가 나는가**를 단언한다

#### 🔴 `PolicyClearance` 는 `adapter/in` 경계를 넘지 않는다

컨트롤러 파라미터·요청 바디로 받는 순간 **외부가 clearance 를 주입할 수 있고 게이트가
껍데기가 된다.** DTO 에 넣지 않는다.

⚠️ clearance 는 **스냅샷**이다. 발급과 사용 사이에 정책이 바뀔 수 있다(TOCTOU).
같은 UseCase·같은 트랜잭션 안에서 쓰는 것을 전제로 하고, 그 전제를 javadoc 에 적는다.

### 2.3 S-4 — 해소 사유가 자유 텍스트다

사람이 판단 근거를 적는 행위라 본문에 텍스트가 들어온다.

| 위험 | 막는 법 |
|---|---|
| 사유에 토큰이 섞인다 | **대입 지점이 하나**(`resolvePending`)이고 거기서 `TokenRedactor.redact` |
| 로그 인젝션 | 포맷 문자열로 쓰지 않고 **인자로만**. CR/LF 를 공백으로 접는다 |
| 길이 | **1000자 상한** — 넘으면 400 |

⚠️ `@ExternalText` 를 달지 않는다. `VARCHAR` 이고 대입 지점에서 스크럽하므로
`pendingReason`(#7)과 같은 취급이다 — 따라서 `ExternalTextScrubRegistry` 행도 필요 없다.

### 2.4 다른 조항

| 조항 | 판정 |
|---|---|
| S-1 · S-3 | **미접촉** |
| S-2 | 🔵 **인접** — `pull-request` 를 만들지 않는 근거가 S-2 다(§1.1) |

### 2.5 미결 대조

| Q | 판정 |
|---|---|
| **Q-5** | ✅ 이미 확정(#47) — 구현만 한다 |
| **Q-8** | 🔴 **부분 해소** — 해소 경로는 생기고 **임계 정책은 그대로 미정** |
| Q-6 · Q-3 | 🔵 인접 — 여기서 정하지 않는다 |

---

## 3. 🔴 Q-8 보류 해소

### 3.1 네 상태를 전부 가른다

| 현재 | 해소 가능? | 왜 |
|---|---|---|
| `NULL` (보류) | ✅ **양방향** | 이 API 의 대상 |
| `FALSE` (금지) | ❌ | 🔴 API 로 뒤집으면 **FR-2(금지 저장소 제외)가 호출 한 번으로 풀린다.** `reanalyze` 가 이미 같은 이유로 거부한다 |
| `TRUE` (허용) | ⚠️ **`FALSE` 방향만** | rev.2 는 「해소할 것이 없다」로 전부 막았다 — **되돌릴 길을 없앴다.** §3.3 |
| 🔴 **정책 행 없음** | ❌ **404** | 분석이 5xx·레이트리밋으로 중단되면 **행이 아예 없다.** 여기서 `TRUE` 를 만들어 주면 **「읽지 않고 허용」** 이 되어 S-5 가 정면으로 뚫린다 |

### 3.2 해소는 `TRUE`·`FALSE` 양쪽으로 간다

사람이 읽고 「이 저장소는 AI 기여 금지다」라고 판단하는 것이 **보류 해소의 정상적인 결과**다.
`TRUE` 전용이면 이름이 「해소」인데 실제로는 「허용」이고, 금지 판정을 내리려면 DB 를 손으로
고쳐야 한다 — 그쪽이 더 위험하다.

⚠️ Q-8 이 방향을 정한 적이 없으므로 **`open-questions.md` 갱신에 포함해야 확정이 된다.**

### 3.3 🔴 보호는 **비대칭**이다 — rev.2 가 양방향을 잠갔다

rev.2 는 `resolvedAt != null` 이면 `reanalyze` 를 **전부** 거부했다. 「사람 판단을 자동이
덮지 않는다」는 의도는 맞지만 **방향을 가르지 않은 것이 틀렸다.**

#### 거짓 대칭이었다

Q-8 의 「보류는 자동으로 풀리지 않는다」는 **방향성 규칙**이다. 막는 것은 `NULL → TRUE`,
즉 **느슨해지는 쪽**뿐이다. 기존 `reanalyze` 가 정확히 그렇게 되어 있다 — 보류·금지에서
거부하고(느슨해짐 차단) **`TRUE → FALSE` 는 통과시킨다**(조여짐 허용).

rev.2 의 가드는 조여지는 쪽까지 막았고, 귀결이 S-5 정면이다.

> 사람이 보류를 `TRUE` 로 해소한다 → 반년 뒤 대상 저장소가 `CONTRIBUTING.md` 에
> **AI 기여 금지**를 명시한다 → 재분석이 거부된다 → 우리는 **금지된 저장소에 계속
> Draft PR 을 만든다.**

그리고 **되돌릴 코드 경로가 하나도 없었다** — `reanalyze` 는 `resolvedAt` 이 막고,
`resolvePending` 은 「`TRUE` 는 해소할 것이 없다」가 막는다. DB 직접 수정만 남는다.

🔴 **이것은 「승인을 무겁게」가 아니라 되돌릴 수 없는 방향을 고정한 것**이고,
`external-deps.md` 의 「모르면 되돌릴 수 없는 쪽을 피한다」에 어긋난다 —
**§1.1 이 `pull-request` 엔드포인트를 거부하며 쓴 바로 그 논리다.** 자기 원칙을 자기가 어겼다.

#### 비대칭 표

| 상황 | rev.2 | rev.3 |
|---|---|---|
| `resolvedAt != null` · 새 판정 `TRUE` (유지·완화) | 거부 | **거부** — 사람 판단을 자동이 재확인할 이유가 없다 |
| `resolvedAt != null` · 새 판정 **`FALSE`** (조여짐) | 거부 | 🔴 **허용** — 실패 방향이 안전하고, 막으면 S-5 가 깨진다 |
| 해소된 정책을 사람이 다시 판단 | 경로 없음 | **`resolvePending` 을 `FALSE` 방향으로만 재호출 허용** |

> **한 문장으로** — 금지로 **조이는 것은 언제든** 가능하고, 허용으로 **푸는 것은 보류
> 상태에서 한 번만** 가능하다. Q-8 의 방향성과 정확히 맞는다.

### 3.4 해소 흔적을 DB 에 남긴다

rev.1 은 「사유를 로그로만 남기고 컬럼은 #25 가」로 갔다. **두 개념을 바꿔치기한 것이었다.**
「승인 기록 **테이블**(#25)」과 「정책 행에 해소 흔적 **한 컬럼**」은 다른 물건이다.

로그로만 두면 `aiContributionAllowed = TRUE` 가 **LLM 판정인지 사람 해소인지 DB 에서
구분되지 않는다.** 하류(#18·#23)가 둘을 똑같이 취급하고 **S-5 판정의 출처가 사라진다.**

```sql
-- V8. ⚠️ 머지 순서: V7(#9) → V8(여기). baseline-on-migrate 가 false 라 순서가 역전되면
--     Flyway 가 out-of-order 로 보고 validate 에서 기동을 막는다. H2 는 매번 새로 떠서
--     드러나지 않고 PostgreSQL 개발 DB 에서만 터진다 — Q-2b
ALTER TABLE repository_policy ADD COLUMN resolved_at     TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE repository_policy ADD COLUMN resolution_note VARCHAR(1024);
```

#### 🔴 `pendingReason` 을 비우지 않는다

rev.2 는 「비우되 해소 사유가 그 자리를 대신한다」고 적었다. **다른 정보다** —
`resolution_note` 는 **사람이 쓴 판단 근거**이고 `pendingReason` 은 **기계가 기록한 보류 원인**
(어느 경로를 왜 못 읽었는가)이다. 비우면 「왜 보류였는지조차 사라진다」는 rev.1 비판의
후반부가 그대로 남는다.

`resolvedAt` 이 이미 「보류 아님」을 말해 주므로 **보존한다.** `pendingReason` 필드 javadoc 의
불변식을 「보류일 때 채워지고 **해소 후에도 보존된다**」로 고친다. 비용 0, 출처 사슬이 완성된다.

⚠️ `resolution_note` 도 `pendingReason` 과 같이 **`truncate`** 한다 — `TokenRedactor` 가
마스킹하며 길이를 늘릴 수 있다. 입력 상한 1000자, 컬럼 1024자.

### 3.5 ⚠️ 그래도 FR-4 를 다 못 채운다 — 「누가」가 없다

이 앱에는 **인증이 없다.** 「사람이 눌렀다」의 주체를 기록할 수단 자체가 없으므로
`resolved_at` 은 **언제**만 답하고 **누가**는 비어 있다.

> **범위 밖으로 선언한다.** 인증 도입은 이 이슈가 아니고, 1인 개발·미배포 단계라 지금
> 만들면 추측으로 설계하게 된다. **다만 FR-4 의 미충족분이므로 PR 본문에 싣는다.**

---

## 4. 기술 설계

### 4.1 승인 게이트 통과 기록

> 「**통과한 것도 남긴다.** 사고 후 「막았는가」를 증명할 수 있어야 한다」 — `logging.md`.

| 시점 | 남기는 것 | 어디서 |
|---|---|---|
| 선정 성공 | `INFO` — `candidateId` · `ANALYZED → SELECTED` | **커밋 후** |
| 선택 취소 | `INFO` — `SELECTED → REJECTED`. **되돌릴 수 없음을 메시지에** | 커밋 후 |
| 보류 해소 | `INFO` — `repositoryId` · `NULL → TRUE|FALSE` · 스크럽된 사유 | 커밋 후 |
| **거부** (상태 불일치·금지 뒤집기 시도) | `WARN` | 🔴 **커밋 후가 아니다** — 아래 |

#### 🔴 커밋 확정 후에 남긴다 — 단 성공만

#12 가 전이 메서드를 `StatusTransition` **반환만** 하게 만든 이유다 —
**엔티티가 직접 로깅하면 롤백됐을 때 로그가 거짓말을 한다.**

`TransactionSynchronizationManager.registerSynchronization(afterCommit)` 를 쓴다.

⚠️ **거부 로그를 여기 두면 안 된다.** 예외 경로는 롤백이라 `afterCommit` 이 **돌지 않는다.**
rev.1 은 둘을 같은 표에 적었다 — 거부는 예외를 던지는 자리에서 직접 남긴다.

⚠️ `registerSynchronization` 은 활성 트랜잭션이 없으면 예외다. UseCase 의 `@Transactional`
이 전제임을 javadoc 에 적는다.

⚠️ `afterCommit` 안의 예외는 **이미 커밋된 승인을 되돌리지 않는다.** 삼키고, 삼켰다는 사실을 남긴다.

⚠️ MDC 는 `candidateId`·`repositoryId` 만. `stage`·`attempt` 는 선정·취소·해소에 의미가 없다.

### 4.2 HTTP 매핑

| 예외 | 상태 | 근거 |
|---|---|---|
| `CandidateTransitionException` | **409** | 불법 전이. `IllegalStateException` 상속이고 도메인에 `@ResponseStatus` 를 달지 않는다(규율 ④) |
| 🔴 `ObjectOptimisticLockingFailureException` | **409** | `ContributionCandidate` 에 `@Version` 이 있다(V4). 동시 `select` 2건이면 **500 이 된다** — rev.1 이 빠뜨렸다 |
| `ContributionNotAllowedException` | **403** | 「없는 것」도 「충돌」도 아니고 **정책이 금지한 것**. 보류·금지를 같은 코드로 보내되 **detail 로 가른다** — 호출자가 「사람이 해소하면 되는가」를 알아야 한다 |
| `PolicyResolutionRejectedException` | **409** | 보류가 아닌 상태의 해소 시도(금지 · 이미 허용인데 완화 방향) |
| `RepositoryPolicyNotFoundException` | **404** | 🔴 **정책 행 자체가 없다.** 해소할 대상이 없는 것이므로 「충돌」이 아니라 「없음」이다 |

⚠️ **403 은 이 PR 에서 도달 불가다** — `clearanceFor` 를 부르는 웹 경로가 없다.
그래도 매핑을 넣는 이유: **매핑은 API 표면이 아니라 방어**다. 없으면 #18 이 처음 던질 때
500 이 나가고, 그때는 원인이 「서버 오류」로 보인다. 「소비자 없는 API 를 만들지 않는다」는
§1.1 의 기준은 **호출 표면**에 대한 것이지 예외 매핑에 대한 것이 아니다.

### 4.3 교차 애그리거트 — 이 PR 은 배선하지 않는다

```
ContributionCandidate(issueId) → [이슈 단건 조회: 없다] → repositoryId → PolicyClearance
```

🔴 **`startImplementing` 을 부르는 곳이 없으므로 이 경로를 만들지 않는다.**
`FindAnalyzableIssuesUseCase` 에 단건 조회가 없고, 여기서 만들면 **소비자 없는 API** 가 된다.
필요해지는 것은 #18 이고, 그 사실을 #18 에 남긴다 —
**「연결했다」와 「연결할 수 있게 해 뒀다」를 섞어 적지 않는다.**

### 4.4 🔴 FR-5 — 「자동 전이 경로가 없다」를 무엇으로 증명하나

rev.1 은 「소스 스캔?」으로 남겨 뒀다. **소스 텍스트 스캔은 쓰지 않는다** — 주석·문자열·
리네임에 뚫리고, 뚫린 줄 모른 채 초록이다. 손수 짠 리플렉션도 같은 이유로 제외다.

주장을 **세 조각**으로 가르고 각각 다른 도구를 쓴다.

| 조각 | 증명 | 도구 |
|---|---|---|
| ① 전이 규칙 | `SELECTED` 로 가는 길이 `ANALYZED` 에서만 | `CandidateStatus` 전수 파라미터화 — **의존성 불필요** |
| ② 🔴 **필드** | `selectedAt` 을 쓰는 메서드가 `selectByHuman` **하나** | **ArchUnit** 필드 접근 규칙 |
| ③ 호출자 | `selectByHuman` 이 `adapter.in.scheduler`·`event` 에서 도달 불가 | **ArchUnit** 호출자 규칙 |

**②가 본체다.** 불변식 ②의 실질은 「`SELECTED` 인데 `selectedAt` 이 없는 상태가 없다」이고
`guardHumanSelection` 이 그 위에 선다. 쓰기 경로를 하나로 고정하면 ①이 나중에 바뀌어도
방어가 산다.

> **결정 — ArchUnit 을 test 의존성으로 추가한다.** ②③ 은 다른 수단으로 정직하게 쓸 수 없다.
> 손으로 메서드를 열거하면 **새 메서드가 추가될 때 조용히 빠진다** — 이 저장소가 반복해서
> 당한 실패다(`ExternalTextMarkerTest` 가 고정 목록 대신 규칙 검사로 간 것과 같은 판단).

🔴 **양성 대조가 반드시 붙는다.** 메서드 이름 하나만 바뀌어도 ArchUnit 규칙은
**0건을 검사하고 초록**이 된다. `IntegrationTestProfileTest` 의 probe 패턴을 그대로 쓴다 —
규칙을 위반하는 표본을 test 전용 패키지에 두고 **규칙이 그것을 무는지** 단언하고,
모수(검사한 필드·메서드 수 ≠ 0)도 함께 단언한다.

#### ⚠️ 구현 함정 둘 — 적어 두지 않으면 헤맨다

| # | 함정 |
|---|---|
| A-1 | 🔴 **probe 를 진짜 규칙의 스캔 범위에서 빼야 한다.** 규칙을 위반하는 표본을 두는 순간 **같은 클래스 집합을 훑는 진짜 규칙이 그것을 물어 빨개진다.** 그러면 다음 사람이 probe 를 지운다 — **0건 검사 방지 장치가 가장 먼저 사라지는 경로**다. probe 를 별도 패키지에 두고 진짜 규칙의 `ImportOption` 에서 제외하며, 양성 대조는 probe 패키지만 따로 `importPackages` 해서 돌린다 |
| A-2 | `selectedAt` 은 Lombok `@Getter` 가 만드는 **읽기** 접근이 여럿이다(`isNotSelectedByHuman` 포함). 규칙을 「접근」으로 쓰면 **항상 실패**한다 — `JavaFieldAccess.AccessType.SET` 으로 좁혀 **쓰기 원점**을 단언한다 |

#### 🔴 규율 ④ 를 함께 강제한다 — 거의 공짜다

§2.2 의 발급 경로 축소는 **지금 리뷰가 지킨다.** `RepositoryPolicyRepository` 가 `public` 이라
`candidate.application` 이 그것을 주입받아 `findByRepositoryId(...).get().clearance()` 를 하면
**컴파일된다.** 규율 ④ 위반이지만 `safety-boundary-check.sh` 는 문자열만 본다.

ArchUnit 을 이미 들이므로 규칙 하나면 **FR-7 이 리뷰 의존에서 강제로 올라선다.**

> `repository.domain..` · `repository.adapter.out.persistence..` 는 `repository..` 밖에서
> 접근 불가. **단 값 타입은 예외** — `PolicyClearance` · `RepositoryCoordinates` 는 남이
> 쓰라고 만든 것이다(규율 ④ 가 허용하는 「상대 도메인의 값 타입」).

### 4.5 생성·수정 파일

**생성**
```
repository/domain/           PolicyClearance · PolicyResolutionRejectedException
repository/application/      ResolvePolicyPendingUseCase
repository/adapter/in/web/dto/  PolicyResolutionRequest · PolicyResolutionResponse
candidate/application/       SelectCandidateUseCase
candidate/adapter/in/web/dto/ SelectionResponse
db/migration/                V8__policy_resolution.sql
```

**수정**

| 파일 | 변경 |
|---|---|
| `ContributionCandidate.startImplementing` | `PolicyClearance` 인자 + **null 가드** |
| `RepositoryPolicy` | `clearance()` · `resolvePending()` · `reanalyze` 에 **해소 보호** |
| `AnalyzeRepositoryPolicyUseCase` | `clearanceFor(repositoryId)` |
| `CandidateController` | `select`·`reject`. ⚠️ 「쓰기 엔드포인트를 만들지 않는다」 javadoc 정정 |
| `RepositoryController` | `POST /{id}/policy/resolution` |
| `ApiExceptionHandler` | 409 ×3 · 403 |
| `build.gradle.kts`·`libs.versions.toml` | **ArchUnit** (test) |
| `codemaps/domain.md` | 게이트 표에 **「구현 1/3」** · 착수 게이트의 S-5 배선 검증은 #18 |
| `codemaps/data.md` | V8 컬럼 |
| `open-questions.md` | **Q-8 부분 해소** — 해소 방향(TRUE·FALSE)·경로·본문 스키마 · 임계 정책은 미정 유지 |

### 4.6 테스트

| 테스트 | 무엇을 잡나 |
|---|---|
| `SELECTED_로_가는_전이는_ANALYZED_에서만_열린다_S6()` | FR-5 ① |
| `selectedAt_을_쓰는_메서드가_하나뿐이다_S6()` (ArchUnit) | FR-5 ② — **본체** |
| `스케줄러에서_selectByHuman_에_도달할_수_없다_S6()` (ArchUnit) | FR-5 ③ |
| `ArchUnit_규칙이_실제로_문다()` | 🔴 **양성 대조** — 0건 검사 방지 |
| `분석되지_않은_후보는_선정할_수_없다_S6()` | |
| `선정하면_selectedAt_이_남는다_S6()` | **DB 재조회로** 단언 |
| `선택_취소는_되돌릴_수_없다()` · `취소된_후보를_다시_선정할_수_없다_S6()` | |
| `승인_게이트_통과를_로그로_남긴다_S6()` | FR-4 |
| `롤백되면_게이트_통과_로그가_남지_않는다()` | 🔴 로그가 거짓말하지 않는가 |
| `거부는_롤백_경로에서도_기록된다()` | `afterCommit` 이 안 도는 자리 |
| `clearance_없이_startImplementing_을_부르면_거부한다_S5()` | 🔴 **null 가드** (시그니처 단언 아님) |
| `보류_정책은_clearance_를_발급하지_못한다_S5()` · `금지_정책도_마찬가지다_S5()` | |
| `금지_정책은_해소로_뒤집을_수_없다_Q8()` | FR-2 가 호출 한 번으로 풀리지 않는가 |
| `정책_행이_없으면_해소할_수_없다_S5()` | 🔴 「읽지 않고 허용」 차단 |
| `정책_행이_없으면_clearance_도_발급되지_않는다_S5()` | 🔴 착수 게이트 쪽의 같은 구멍 |
| `clearanceFor_는_Optional_을_돌려주지_않는다_S5()` | 무시할 수 있는 게이트가 아닌가 |
| `해소는_허용과_금지_양쪽으로_갈_수_있다_Q8()` | 「해소」≠「허용」 |
| 🔴 `해소된_정책도_금지로는_조일_수_있다_S5()` | **비대칭 보호** — 막으면 규약이 바뀌어도 못 따라간다 |
| `해소된_정책을_재분석이_허용으로_되돌리지_못한다_Q8()` | 느슨해지는 쪽만 막는다 |
| `해소_후에도_pendingReason_이_남는다()` | 출처 사슬 |
| `해소_사유가_스크럽된다_S4()` · `사유_길이_상한을_넘으면_400()` | |
| `불법_전이는_409()` · `동시_선정은_409()` · `정책_금지는_403()` · `정책_행_없음은_404()` | HTTP 매핑 |
| `repository_내부를_밖에서_접근할_수_없다()` (ArchUnit) | 규율 ④ — 발급 경로 우회 차단 |

---

## 5. 구현 순서

| Stage | 내용 | 복잡도 |
|---|---|---|
| 0 | ArchUnit 의존성 해석 확인 · **V8 번호 선점 통지** | 낮음 |
| 1 | `PolicyClearance` + `RepositoryPolicy.clearance()` + `startImplementing` 가드 | 중간 |
| 2 | V8 + `resolvePending` + `reanalyze` 해소 보호 | **높음** |
| 3 | `SelectCandidateUseCase` + 커밋 후 게이트 기록 | **높음** |
| 4 | 엔드포인트 3개 + DTO + HTTP 매핑 4종 | 중간 |
| 5 | 테스트 — 특히 ArchUnit + 양성 대조 | **높음** |
| 6 | 문서 · **#18·#23 이슈 본문 수용조건 이관** | 중간 |

---

## 6. 리스크

| 리스크 | 대응 |
|---|---|
| 🔴 `pull-request` 게이트가 되돌릴 수 없는 상태를 만든다 | 만들지 않는다 |
| 🔴 `record` 로는 생성자를 막을 수 없다 | `final class` + 패키지 가시성 |
| 🔴 `null` clearance | 런타임 가드 + **동작 테스트** |
| 🔴 규율 ④ 우회 (`candidate` 가 `RepositoryPolicy` import) | 엔티티가 자기 통행증을 발급 |
| 🔴 정책 행이 없는데 해소 | 명시적 거부 |
| 🔴 사람 해소를 재분석이 **허용 방향으로** 덮어쓴다 | `resolvedAt` 보호 — **느슨해지는 쪽만** |
| 🔴 **해소 후 규약이 금지로 바뀌어도 못 따라간다** | rev.2 가 만든 구멍. **조여지는 쪽은 열어 둔다**(§3.3) |
| `candidate` 가 정책 리포지토리를 직접 써서 발급 경로를 우회 | ArchUnit 규율 ④ 규칙 |
| ArchUnit probe 가 진짜 규칙을 빨갛게 만들어 지워진다 | probe 패키지 격리 (A-1) |
| 🔴 해소 출처가 DB 에 안 남는다 | V8 |
| 🔴 동시 선정이 500 | 낙관적 락 → 409 |
| 🔴 롤백된 승인이 로그에 남는다 | `afterCommit` (성공만) |
| 🔴 FR-5 테스트가 0건 검사로 초록 | 양성 대조 probe |
| **「누가」가 기록되지 않는다** | 인증 부재. **범위 밖 선언 + PR 본문에 명시** |
| V8 번호 충돌 | 선점 통지 (#14·#15·#58 세션) |
| clearance TOCTOU | 같은 트랜잭션 전제를 javadoc 에 |

---

## 7. 범위 밖 — 명시적으로 남긴다

- 🔴 **`implement`·`pull-request` 엔드포인트** — #18·#23. **그쪽 이슈 수용조건으로 이관**
- 🔴 **#12 에스컬레이션의 실제 판정** — clearance 배선은 #18 에서만 검증 가능.
  **#18 본문에 옮겨 적는다.** 계획서 안의 한 줄은 이월이 아니다
- **후보 → 이슈 단건 조회** — #18
- **`verify` 엔드포인트** — #19
- **승인 기록 테이블 · 인증(「누가」)** — #25 · 별건
- **Q-8 임계 정책** — Phase 2 실측 후

### ⚠️ 머지 시점의 실효 범위 — PR 본문에 싣는다

| 게이트 | 상태 |
|---|---|
| 선정 (`select`) · 취소 (`reject`) | ✅ **동작** |
| Q-8 보류 해소 | ✅ 동작. ⚠️ **「누가」는 비어 있다** |
| 착수 (`implement`) | ❌ 엔드포인트 없음 — `PolicyClearance` 만 세운다 (#18) |
| PR 생성 (`pull-request`) | ❌ 엔드포인트 없음 (#23) |

**S-6 의 게이트 3개 중 1개가 동작한다.** 나머지 둘은 실행체가 생길 때 같이 열린다.
