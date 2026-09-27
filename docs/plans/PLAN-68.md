# PLAN-68: 규약 문서 변경 탐지 — 낡은 판정이 굳지 않게 한다

> 이슈: [#68](../../issues/68) · 라벨 `domain:repository` · `safety`
> 선행: #7 (규약 판정) · #24 (해소 경로 · 재분석 방향)
> 안전 경계: 🔴 **S-5** — 「파싱 실패는 「허용」이 아니라 「보류」」의 **시간축 확장**

---

## 0. 이 이슈가 무엇인가

한 번 선 판정이 **낡는다.** 대상 저장소가 나중에 AI 기여를 금지해도, 그 문서를 못 읽으면
우리는 계속 허용으로 알고 Draft PR 을 만든다. 지금은 **「못 읽었다」와 「읽었는데 그대로다」가
구분되지 않는다** — 둘 다 판정이 안 바뀐다.

### 0.1 🔴 착수 전에 확인한 것 — 구멍이 이슈 본문보다 하나 더 깊다

이슈는 「재분석이 돈다 → 못 읽는다 → `reanalyze` 가 거부한다」로 5단계를 그린다.
**3단계가 실제로는 일어나지 않는다.**

```
$ grep -rn "analyzeIfAbsent\|\.analyze(" src/main --include='*.java'
ScanPipelineUseCase.java:107:   target = repositoryPolicy.analyzeIfAbsent(repositoryId);
```

`analyze()` 의 **운영 호출자는 `analyzeIfAbsent` 하나**이고, 그것은 이름 그대로
**정책 행이 없을 때만** 분석한다. 즉 판정이 한 번 서고 나면 **규약 문서를 다시 읽는 코드
경로가 존재하지 않는다.**

| | 이슈가 그린 것 | 실제 |
|---|---|---|
| 재분석 | 돈다. 못 읽어서 판정이 안 바뀐다 | **돌지 않는다.** 확인조차 하지 않는다 |
| 증상 | 낡은 판정이 굳는다 | 같다 — 원인이 한 겹 더 앞이다 |

이것은 내가 PLAN-14 R-4 에 「대가는 **한 번 허용이면 영원히 허용**이다. 갱신 주기(TTL)는
「규약이 얼마나 자주 바뀌는가」 데이터가 없어 지금 정하지 않는다」로 남긴 바로 그 한계다.

🔴 **그래서 지문만 기록하면 죽은 코드가 된다.** 비교할 두 번째 관측이 영영 생기지 않는다.
이 PR 은 **탐지와 그 탐지를 실제로 수행하는 경로를 함께** 만든다.

#### 그리고 지문이 TTL 문제를 없앤다

TTL 을 정하지 못한 이유는 「규약이 얼마나 자주 바뀌는지 모른다」였다. **지문 비교는 그것을
알 필요가 없다** — 바뀌었는지 **보면** 된다. 비싼 것은 GitHub 조회가 아니라 **LLM 판정**이고,
지문이 같으면 그것을 건너뛴다.

### 0.2 계약 표면 변경

| 표면 | 변경 | 영향 |
|---|---|---|
| **DB 스키마** | `repository_policy` 에 컬럼 2개 (**V9**) | 마이그레이션 |
| `AnalyzeRepositoryPolicyUseCase.analyzeIfAbsent` | → **`ensurePolicy`** 로 개명 + 동작 변경 | 호출자 1곳 (`ScanPipelineUseCase`) |
| `FetchedDocument` | 필드 `fingerprint` 추가 (record 컴포넌트) | 생성 지점 = 어댑터 + 페이크 |
| `RepositoryPolicy` | 메서드 `markUnverifiable`·`markVerified` 추가 | 신규 |
| `PipelineMetrics` | 메서드 `policyDocuments(PolicyChangeOutcome)` 추가 | 신규 |

⚠️ **V9 번호 충돌** — 착수 시점 `origin/main` 의 최신 마이그레이션은 `V8__policy_resolution.sql`
(#24)이고 방금 머지된 #16(PR #69)은 마이그레이션을 추가하지 않았다. **V9 가 비어 있다.**
다른 세션이 먼저 V9 를 가져가면 `baseline-on-migrate: false` 라 out-of-order 로 기동이 막힌다 —
머지 직전에 다시 확인한다.

---

## 1. 요구사항

### FR

| # | 요구 | 근거 |
|---|---|---|
| FR-1 | 규약 문서 수집 시 **후보 경로별 지문**을 기록한다 | 완료 조건 1 |
| FR-2 | 「문서가 바뀌었는가」와 「판정이 섰는가」를 **분리해서** 판정한다 | 완료 조건 2 |
| FR-3 | **바뀌었는데 못 읽으면** 조용히 넘기지 않는다 — 처리 확정 + Q-8 기록 | 완료 조건 3 |
| FR-4 | 5단계 시나리오가 **탐지되는지** 테스트한다 — 「아무도 모르는 채로 남지 않는다」 | 완료 조건 4 |
| FR-5 | 지문이 **1MB 초과·5xx 에서도 의미를 갖는지** 확정하고 적는다 | 완료 조건 5 |
| FR-6 | 지문 비교를 **실제로 수행하는 경로**를 만든다 — 없으면 FR-1~5 가 죽은 코드다 | §0.1 |

### NFR

| # | 요구 |
|---|---|
| NFR-1 | 지문이 같으면 **LLM 을 부르지 않는다.** 재확인 비용이 GitHub 조회로 한정된다 |
| NFR-2 | 대외 호출은 트랜잭션 밖 — 기존 규율 유지 |
| NFR-3 | 지문에 **대상 저장소 원문이 섞이지 않는다** (S-4). 해시는 단방향이다 |
| NFR-4 | 기존 정책 행(지문 없음)이 배포 순간 **일괄 보류로 떨어지지 않는다** |

---

## 2. 게이트 판정

### 2.1 🔴 S-5 — 이 PR 이 조항의 시간축이다

S-5 는 「규약을 **읽고** 따른다」를 요구하고, Q-8 이 「가르는 선은 **읽었는가**다」로 좁혔다.
둘 다 **한 시점**을 말한다. 이 PR 이 더하는 것은 **「언제 읽은 것인가」**다.

| 지킬 것 | 어떻게 |
|---|---|
| 못 읽음을 허용으로 번역하지 않는다 | 기존 `DocumentFetchOutcome` 3분기를 **그대로 둔다.** 지문은 그 위에 얹힌다 |
| 바뀐 것을 관측했는데 판정이 안 서면 통과시키지 않는다 | §3 의 **보류 강등** |
| 게이트 자체를 건드리지 않는다 | `clearanceFor` · `clearance()` 는 **무변경**. 강등은 판정 필드를 바꿀 뿐 게이트 로직을 바꾸지 않는다 |

### 2.2 🔴 이슈가 먼저 적으라고 한 판단 — 방향이 레이트리밋과 반대다

[`external-deps.md`](../../.claude/rules/context/external-deps.md) 는 「모르면 보수적으로」가
규칙이 **아니라**고 못 박고, 가르는 것은 **실패의 방향이 되돌릴 수 있는가**라고 한다.

| 판단 | 모를 때 | 틀리면 |
|---|---|---|
| 레이트리밋 차단 (#8) | **막지 않는다** | 우리 호출이 실패할 뿐 — 되돌릴 수 있다 |
| **문서가 바뀐 것을 관측했는데 판정 불가 (이 PR)** | 🔴 **막는다** | 규약 위반 PR 이 남의 저장소로 나간다 — **되돌릴 수 없다** |

**같은 「모름」이 아니다.** 레이트리밋 사례에서 방어가 위험했던 이유는 「방어가 스스로를
잠그는 구조」였기 때문이다 — `resetAt` 을 갱신할 응답이 영영 오지 않았다.
여기는 **잠기지 않는다**: 강등의 출구가 이미 있다.

#### 🔴 #7 시점과 조건이 달라졌다 — 그래서 결론도 다르다

#7 이 「일시적 실패로 보류를 만들지 않는다」를 택한 근거는 명시적으로
**「되돌릴 수단이 없다(#24 미구현)」**였다(`AnalyzeRepositoryPolicyUseCase` ③의 주석).

**#24 는 머지됐다.** `POST /api/repositories/{id}/policy/resolution` 이 있고
`RepositoryPolicy.resolvePending` 이 보류(`NULL`)에서 **양방향**을 연다. 보류는 이제
사람이 풀 수 있는 상태이지 막다른 길이 아니다.

⚠️ 그렇다고 #7 의 규칙을 뒤집는 것은 **아니다.** 일시적 실패는 여전히 아무것도 쓰지 않는다
(§3.2). 바뀌는 것은 **「바뀐 것을 양성으로 관측한」** 좁은 경우 하나뿐이다.

### 2.3 S-4 — 지문에 원문이 섞이지 않는가

`document_fingerprints` 컬럼에는 **경로 + SHA-256 hex** 만 들어간다. 해시는 단방향이라
원문 복원이 불가능하고, 경로는 `PolicyDocumentPath` 의 **우리 상수 목록**이라 외부 입력이 아니다.
→ `@ExternalText` 마커도, `ExternalTextScrubRegistryTest` 등록 행도 **필요 없다**
(`pendingReason`·`resolutionNote` 와 같은 취급).

`pendingReason` 에 들어갈 강등 사유도 **경로 + 우리 어휘 코드**뿐이다.

### 2.4 S-6 — 승인 지점을 우회하지 않는가

강등(`TRUE → NULL`)은 **게이트를 조이는** 방향이므로 승인 지점을 늘리지 줄이지 않는다.
🔴 **반대 방향은 만들지 않는다** — 지문이 「돌아왔다」고 해서 보류가 자동으로 풀리는 경로는
없다. Q-8 확정 ②(「보류는 자동으로 풀리지 않는다」)를 그대로 지킨다.

`markUnverifiable` 의 가드가 그것을 코드로 강제한다 — **허용(`TRUE`)에서만** 부를 수 있다.

### 2.5 다른 조항

| | |
|---|---|
| S-1 · S-2 · S-3 | 미접촉 — push·PR·샌드박스 경로를 건드리지 않는다 |

### 2.6 미결 대조

| 항목 | 접촉 | 처리 |
|---|---|---|
| **Q-8** | 🔴 **닫는다** — 「남은 구멍 하나」가 이 이슈다 | §3 의 결론을 `open-questions.md` 에 기록 |
| Q-2 / Q-2b-1 | 마이그레이션 | 벤더 고유 문법 없음. `TEXT` 는 Q-2b 가 양쪽에서 검증한 타입 |
| Q-9 | 테스트 대역 | `FakePolicyDocumentSource` 에 지문을 실을 수 있어야 한다 |
| PLAN-14 R-4 | 🔴 **닫는다** — TTL 없이 재확인이 가능해진다 | §0.1 |

---

## 3. 🔴 확정 — 바뀌었는데 못 읽으면 어떻게 하나

### 3.1 선택지

| | 동작 | 판정 |
|---|---|---|
| A | **보류로 강등** (`TRUE → NULL`) | ✅ **채택** |
| B | 판정 유지 + 플래그·로그만 | ❌ |
| C | 금지로 강등 (`TRUE → FALSE`) | ❌ |

**C 를 버리는 이유** — `FALSE` 는 `resolvePending` 이 409 로 거부하고 `reanalyze` 도 거부한다.
설계상 **되돌릴 수 없는 종단**이다. 오탐 하나가 저장소를 영구히 죽인다 —
「방어가 스스로를 잠그는 구조」 그 자체다.

**B 를 버리는 이유** — 「신호를 준다」가 사람에게 실제로 닿지 않으면 **#68 이 지적한 상태에
이름만 붙인 것**이다. 아무도 조회하지 않는 플래그 옆에서 Draft PR 은 계속 나간다.
이슈가 「🔴 **「판정이 안 바뀐다」가 아니라 「아무도 모르는 채로 남지 않는다」를 단언한다**」고
쓴 것이 정확히 이 구분이다.

### 3.2 🔴 강등 조건 — 네 개를 **전부** 만족할 때만

좁게 여는 것이 핵심이다. 넓히면 #7 이 막은 「5xx 한 번에 파이프라인이 멈춘다」로 되돌아간다.

| # | 조건 | 빠뜨리면 |
|---|---|---|
| 1 | 현재 판정이 **허용(`TRUE`)** 이다 | 보류·금지는 이미 막혀 있다. 건드릴 이유가 없다 |
| 2 | **저장된 지문이 있다** — 비교 기준이 실재한다 | 🔴 배포 직후 모든 기존 행이 일괄 강등된다 (NFR-4) |
| 3 | 그 **필수 경로**에서 **「판정 근거를 확인할 수 없다」**가 성립한다 — 아래 둘 중 하나 | 「못 읽었다」만으로 강등하면 #7 회귀 |

조건 3 의 두 형태 — **이슈 5단계가 든 원인이 둘이기 때문에 둘 다 세야 한다.**

| 형태 | 언제 | 예 |
|---|---|---|
| ⓐ 지문이 **달라졌는데** 못 읽는다 | 응답은 받았다 | 우리 상한(40,000자) 초과 = `TRUNCATED` |
| ⓑ 전에는 읽었는데 이번엔 **지문조차** 못 구했다 | 응답에 본문이 없다 | **1MB 초과** · 디렉터리 · 권한 |

🔴 **ⓑ 를 빼면 이슈가 든 「1MB 초과」 형태가 조용히 통과한다.** 「양쪽에 지문이 있을 때만
바뀌었다고 한다」는 규칙 자체는 옳은데(조건 2), 그것만으로는 **정작 이슈의 절반을 놓친다** —
지문이 사라지는 쪽이 비교에서 빠지기 때문이다. `lostRequiredPaths` 가 그 자리를 메운다.
| 4 | 그 경로를 **영구적으로** 못 읽는다 | 일시적이면 다음 스캔이 푼다 — 사람을 부를 일이 아니다 |

셋 중 하나라도 어긋나면 **기존 동작 그대로**다.

### 3.3 강등의 출구

| | |
|---|---|
| 상태 | `aiContributionAllowed = NULL` · `pendingReason = "CHANGED_UNREADABLE <path>=<REASON>"` |
| 푸는 법 | `POST /api/repositories/{id}/policy/resolution` — 사람이 문서를 보고 판단 (#24) |
| 자동 해제 | **없다** — Q-8 확정 ② |

🔴 **`resolvedAt` 은 비운다.** 사람이 허용으로 풀었던 행이 강등되면, 그 판단은
**새 증거로 무효가 된 것**이다. 남겨 두면 `isHumanResolved()` 가 「지금 판정이 사람 것」이라고
거짓말한다 — 안전 판정에 쓰이는 술어라 거짓말을 남길 수 없다.

⚠️ **이력은 잃지 않는다** — `resolutionNote` 는 **보존**한다. #24 가 「해소해도
`pendingReason` 을 비우지 않는다」고 정한 것과 **대칭**이다.

### 3.4 🔴 FR-5 — 지문이 언제 의미를 갖는가 (실측 확인)

이슈의 ⚠️ 가 정확히 짚었다 — **본문을 못 받으면 해시도 못 구한다.** 경우를 갈라 본다.

| 상황 | 응답 | 지문 | 근거 |
|---|---|---|---|
| `READ` | 200 + 본문 | ✅ **SHA-256(content)** | |
| `ABSENT` (404) | 404 | ✅ **상수 `absent`** | 「없다」도 **안정적인 관측**이다. 없던 파일이 생기면 지문이 변한다 |
| `UNREADABLE / TRUNCATED` | 200 + 본문 | ✅ **SHA-256(content)** | 🔴 **내용을 받긴 했다.** 우리 상한(40,000자)을 넘어 **판정에 쓰지 않을** 뿐이다 |
| `UNREADABLE / UNKNOWN`(1MB 초과 · 디렉터리 · 권한) | 200 (내용 없음) / 4xx | ⚠️ **없음** → 🔴 **「사라졌다」로 탐지** | GitHub 은 이 응답에도 blob `sha` 를 싣지만 `fetchFile` 이 **그 전에 예외로 끊어** 잃는다. 지문은 못 구해도 **기준에 있던 것이 사라진 사실**은 안다 — 조건 3ⓑ |
| `UNREADABLE / RATE_LIMITED`·`SERVER_ERROR` | **응답 없음** | ❌ **원리적으로 불가능** | 서버에 닿지 못했다. 바뀌었는지 **알 방법이 없다** |

⚠️ 표의 세 번째 줄 주의 — **`UnreadableReason.TOO_LARGE` 는 실제로 발생하지 않는다.**
`GitHubPolicyDocumentSource.translate()` 가 `GitHubUnreadableContentException` 을
`UNKNOWN` 으로 내보내기 때문이다(그 열거값은 선언만 살아 있다).
그래서 **조건 3 은 사유 코드를 보지 않는다** — 보는 것은 `isTransientFailure()` 뿐이다.
사유로 분기했다면 1MB 형태를 영영 못 잡았을 자리다.

**결론 세 줄.**

1. ✅ **`TRUNCATED`(우리 상한 초과)는 지문으로 잡힌다** — 내용을 받았다. 상한은 40,000자라
   `CONTRIBUTING.md` 가 커지면 실제로 걸린다.
2. ✅ **1MB 초과도 잡힌다 — 단 「달라졌다」가 아니라 「사라졌다」로.** 지문을 못 구하므로
   변경 비교에는 끼지 못하지만, **기준에 있던 지문이 이번엔 없다**는 사실이 남는다(조건 3ⓑ).
3. ❌ **5xx·레이트리밋은 닫을 수 없다.** 응답 자체가 없어 **지문도 ETag 도** 못 받는다 —
   완료 조건 5 가 물은 「ETag 는 헤더라 사정이 다를 수 있다」의 답이 이것이다.
   ETag 가 도움이 되는 것은 1MB 형태뿐이고, 거기는 이미 3ⓑ 로 닫았다.
   대신 **「마지막으로 확인한 시각」(`documents_checked_at`)** 을 남겨
   *「모르는 상태가 얼마나 오래됐는가」*가 보이게 한다.

⚠️ 그래서 **ETag 는 이번에 쓰지 않는다.** 남은 이득은 정확도가 아니라 **비용**뿐이고
(304 는 레이트리밋을 소모하지 않는다), 쓰려면 `RepositorySource.fetchFile` 시그니처와
`GitHubResponse` 헤더 접근을 함께 열어야 한다 — **능력 인터페이스 계약 변경**이다. §7.

---

## 4. 기술 설계

### 4.1 필수 체크리스트

| # | 항목 | 답 |
|---|---|---|
| 1 | 소유 도메인 | **`repository`** — 규약 문서의 지문은 `RepositoryPolicy` 의 상태다 |
| 2 | 레이어 | 값 타입·엔티티 메서드 = `domain` / 흐름 = `application` / 해시 계산 = **`domain`**(순수 함수) · 수집 = `adapter/out` |
| 3 | 능력 인터페이스 | **새로 만들지 않는다.** `PolicyDocumentSource.collect` 시그니처 불변 — 지문은 `FetchedDocument` 에 실려 온다 |
| 4 | 🔴 트랜잭션 밖 대외 호출 | 유지. 수집·LLM 은 `analyze()` 의 트랜잭션 밖 구간. 쓰기만 `RepositoryPolicyWriter` |
| 5 | 상태 전이 | `CandidateStatus` 무관. `RepositoryPolicy` 의 판정 필드만 |
| 6 | 멱등성 | 🔴 **지문이 같으면 아무 쓰기도 하지 않는다**(`documents_checked_at` 갱신 제외). 재실행이 판정을 흔들지 않는다 |
| 7 | `Clock` 주입 | `markVerified`·`markUnverifiable` 둘 다 `Clock` 인자 |
| 8 | 안전 경계 | §2 |

### 4.2 지문 값 타입 — `PolicyDocumentFingerprints`

별도 테이블이 아니라 **컬럼 하나 + 도메인 값 타입**으로 간다.

| | 이유 |
|---|---|
| 자식 테이블 | 13행 고정이라 「무한정 자란다」에 해당하지 않지만, JPA 컬렉션 + 고아 제거 배선이 붙는다 |
| **컬럼 1개 + 값 타입** | ✅ **채택** — `pendingReason`(`path=REASON; path=REASON`)이 이미 같은 형식을 쓰는 선례다. 파싱·직렬화가 순수 함수라 유닛 테스트로 전부 덮인다 |

```
AGENTS.md=3b1f…;CONTRIBUTING.md=absent;README.md=9c4a…
```

- 경로는 `PolicyDocumentPath` 의 **상수 목록**에서만 온다 — 구분자 주입이 불가능하다.
  그래도 **파싱 시 검증**한다(알 수 없는 경로는 무시, 형식 불일치는 예외).
- 지문은 `absent` 또는 소문자 hex 64자. **그 밖은 거부**한다.
- 크기: 13 × (~40 + 1 + 64 + 1) ≈ **1.4KB** → `TEXT` (Q-2b 가 양쪽에서 검증한 타입)

핵심 메서드

| 메서드 | 뜻 |
|---|---|
| `of(RepositoryDocuments)` | 수집 결과에서 지문을 뽑는다. **지문이 없는 문서는 담기지 않는다** |
| `serialize()` / `parse(String)` | 컬럼 왕복 |
| `changedRequiredPathsAgainst(previous)` | 🔴 **필수 경로 중 「양쪽에 지문이 있고 서로 다른」 것만.** 한쪽이 없으면 「바뀌었다」고 주장하지 않는다 (§3.2 조건 2) |
| `isEmpty()` | 비교 기준이 없다 |

🔴 **「없음」과 「달라짐」을 섞지 않는 것이 이 타입의 존재 이유다.** 두 상태를 한 `boolean` 으로
뭉치는 순간 §3.2 의 조건 2·3 이 같은 조건이 되고, NFR-4 가 깨진다.

### 4.3 `FetchedDocument` 에 지문을 싣는다

```java
public record FetchedDocument(
        PolicyDocumentPath path,
        DocumentFetchOutcome outcome,
        @ExternalText(Source.TARGET_REPOSITORY) String content,
        UnreadableReason reason,
        String fingerprint) {          // ← 추가. null 이면 「지문을 구하지 못했다」
```

불변식 추가

| 규칙 | 왜 |
|---|---|
| `READ` 면 `fingerprint` 가 **반드시 있다** | 내용을 받았는데 지문이 없을 수 없다 |
| `ABSENT` 면 `fingerprint == ABSENT_FINGERPRINT` | 「없음」이라는 관측을 값으로 고정한다 |
| `UNREADABLE` 은 **있어도 되고 없어도 된다** | §3.4 의 표가 갈린다 |

팩토리 `read`·`absent` 는 지문을 스스로 채우고, `unreadable` 은 **2개 오버로드**를 둔다
(`unreadable(path, reason)` / `unreadable(path, reason, fingerprint)`).

⚠️ `toString()` 에 지문을 넣지 않는다 — 크기만 남기는 현재 규칙을 유지한다.

### 4.4 해시 — 어디서 계산하나

**`domain` 의 순수 함수** `DocumentFingerprint.of(String content)` 로 둔다.

- 어댑터에 두면 페이크(`FakePolicyDocumentSource`)가 **다른 계산**을 하게 되고,
  그러면 테스트가 검증하는 지문과 운영 지문이 다른 물건이 된다.
- 기술 이름(`Sha256…`)을 domain 에 쓰지 않는다 — 규율 ③. `MessageDigest` 는 JDK 표준이라
  「기술 의존」에 해당하지 않는다(`Clock`·`Base64` 와 같은 급).

### 4.5 엔티티 메서드 2개

```java
/** 문서를 확인했고 바뀐 것이 없다 — 판정은 건드리지 않는다. */
public void markVerified(PolicyDocumentFingerprints prints, Clock clock)

/** 🔴 바뀐 것을 관측했는데 판정이 서지 않는다 — TRUE → NULL (S-5 · #68). */
public void markUnverifiable(String reason, PolicyDocumentFingerprints prints, Clock clock)
```

`markUnverifiable` 가드 — **허용에서만** 부를 수 있다.

| 현재 | 결과 |
|---|---|
| `TRUE` | ✅ `NULL` 로 강등 · `pendingReason` 기록 · `resolvedAt` 비움 · `resolutionNote` 보존 |
| `NULL` | ⛔ `IllegalStateException` — 이미 보류다. 사유를 덮어쓰면 원래 보류 원인이 사라진다 |
| `FALSE` | ⛔ `IllegalStateException` — 🔴 금지에서 보류로 **푸는** 방향이다. 열면 FR-2 가 뚫린다 |

🔴 **`reanalyze` 를 재사용하지 않는다.** 저쪽은 「판정이 선 결과로 갱신」이 계약이고
(`isUndetermined()` 면 거부), 이쪽은 **판정이 서지 않았다는 사실 자체를 기록**한다.
같은 메서드에 넣으면 그 계약이 무너진다.

### 4.6 흐름 — `analyzeIfAbsent` → `ensurePolicy`

```
ensurePolicy(repositoryId):                       [트랜잭션 없음 · assertNoTransaction]
  snapshot = load
  ├─ 정책 없음 → analyze()  (기존 그대로)
  └─ 정책 있음 → verify()   ← 신설

verify(snapshot):
  ① 보류·금지면 즉시 반환            (기존 blocksReanalysis — 호출 0회)
  ② 보관된 저장소면 SKIP             (기존)
  ③ documents = collect(...)         [대외 · 13회]
  ④ prints = PolicyDocumentFingerprints.of(documents)
  ⑤ 필수 경로에 일시적 실패 → 🔴 아무것도 쓰지 않고 중단 (기존 규칙 유지)
  ⑥ changed = stored.changedRequiredPathsAgainst(prints)      ← FR-2: 「바뀌었는가」
  ⑦ 판정 가능한가 = !documents.hasPermanentlyUnreadableRequired()  ← FR-2: 「판정이 서는가」

     ┌ 확인할 수 없음(변경∩영구실패) ─→ 🔴 markUnverifiable   ← #68 이 닫는 구멍
     ├ 변경 없음 + 기준 완비 ─────────→ markVerified  · LLM 호출 없음 (NFR-1)
     ├ 사람이 해소했고 재판정도 허용 ─→ markVerified  ← ⑧ 아래
     └ 그 밖 ───────────────────────→ LLM → reanalyze / analyzed
```

#### 🔴 ⑧ — 사람이 해소한 판정과 부딪히는 자리

`RepositoryPolicy.reanalyze` 는 **사람이 해소한 정책을 허용 판정으로 갱신하는 것을
예외로 거부**한다(#24). 문서가 바뀌었고 재판정이 여전히 허용이면 정확히 그 조합이 된다.

⚠️ 지금까지 이 경로는 **도달 불가**였다 — `analyze()` 가 불리지 않았기 때문이다.
**이 PR 이 그 문을 연다.** 그대로 두면 #24 의 규칙이 「거부」가 아니라
**「스캔 POLICY 단계 전체 실패」**로 나타난다(`ScanPipelineUseCase` 가 `FAILED` 로 올린다).

그래서 UseCase 가 먼저 가른다 — **판정은 그대로 두고 지문만 갱신**한다.
🔴 엔티티 가드는 **지우지 않는다.** 최종 방어는 거기고, UseCase 의 분기는 그 앞의 예의다.
⚠️ 지문을 갱신하지 않으면 매 스캔 같은 변경을 다시 발견해 LLM 을 영원히 태운다.

⑥과 ⑦이 **서로 다른 입력을 보는 두 개의 판정**이라는 것이 FR-2 그 자체다.

⚠️ **`analyze()`(최초 분석)는 흐름을 바꾸지 않는다.** 지문을 함께 저장하는 것만 더한다.
기존 6분기(①~⑥)를 건드리면 #7 의 회귀 위험이 커진다.

### 4.7 마이그레이션 V9

```sql
ALTER TABLE repository_policy ADD COLUMN document_fingerprints TEXT;
ALTER TABLE repository_policy ADD COLUMN documents_checked_at TIMESTAMP(6) WITH TIME ZONE;
```

| 컬럼 | 왜 |
|---|---|
| `document_fingerprints` | 비교 기준. 🔴 **NULL 허용** — 기존 행은 기준이 없고, 그것이 「바뀌지 않았다」로 읽히면 안 된다 (NFR-4) |
| `documents_checked_at` | 🔴 **「판정」이 아니라 「확인」의 시각.** `analyzed_at` 을 재사용하면 LLM 을 안 불렀는데 「분석했다」가 되어 거짓말이 된다. 5xx 가 계속되면 이 값이 **멈춰 있고**, 그것이 「모르는 상태가 오래됐다」의 유일한 증거다 (§3.4 결론 2) |

벤더 고유 문법 없음. `TIMESTAMP(6) WITH TIME ZONE` 은 V8 이 이미 쓴 형태다.

### 4.8 계측

`PipelineMetrics.policyDocuments(PolicyChangeOutcome outcome)` + 신규 enum.

| 값 | 뜻 |
|---|---|
| `BASELINE_RECORDED` | 비교 기준을 처음 기록했다 |
| `UNCHANGED` | 바뀐 것 없음 — LLM 을 아꼈다 |
| `CHANGED_REANALYZED` | 바뀌었고 다시 판정했다 |
| `CHANGED_UNVERIFIABLE` | 🔴 **바뀌었는데 판정 불가 → 강등.** 이 카운터가 0 이 아니면 사람이 봐야 한다 |
| `INDETERMINATE` | 일시적 실패 — 이번에는 확인하지 못했다 |

🔴 기존 규율대로 **시그니처가 enum 만 받는다** — 경로 문자열은 태그로 나가지 않는다
(경로는 우리 상수지만 카디널리티를 열 이유가 없다).

### 4.9 생성·수정 파일

**생성**

| 파일 | |
|---|---|
| `db/migration/V9__policy_document_fingerprints.sql` | |
| `repository/domain/DocumentFingerprint.java` | 해시 계산 · `ABSENT` 상수 |
| `repository/domain/PolicyDocumentFingerprints.java` | 값 타입 (직렬화·비교) |
| `support/observability/PolicyChangeOutcome.java` | |

**수정**

| 파일 | |
|---|---|
| `repository/domain/FetchedDocument.java` | `fingerprint` 컴포넌트 + 불변식 |
| `repository/domain/RepositoryDocuments.java` | `permanentlyUnreadableRequiredPaths()` 추가 |
| `repository/domain/RepositoryPolicy.java` | 필드 2 + `markVerified`·`markUnverifiable` |
| `repository/application/AnalyzeRepositoryPolicyUseCase.java` | `ensurePolicy` · `verify` |
| `repository/application/RepositoryPolicyWriter.java` | `saveVerified`·`saveUnverifiable` · `saveAnalyzed(+prints)` |
| `repository/application/ScanPipelineUseCase.java` | 호출 개명 |
| `repository/adapter/out/github/GitHubPolicyDocumentSource.java` | 지문 부착 (`TRUNCATED` 포함) |
| `support/observability/PipelineMetrics.java`·`MetricNames.java` | 계측 |
| `.claude/rules/context/open-questions.md` | 🔴 Q-8 「남은 구멍」 → 확정 |
| `.claude/codemaps/data.md` | 컬럼 2개 |

### 4.10 테스트

🔴 **완료 조건 4 가 요구하는 것은 「판정이 안 바뀐다」가 아니라 「아무도 모르는 채로 남지
않는다」다.** 그래서 단언은 *판정이 바뀌었다* + *사유가 남았다* + *계측이 올라갔다* 셋이다.

| # | 테스트 | 무엇을 막나 |
|---|---|---|
| 1 | `규약이_바뀌었는데_못_읽으면_보류로_강등한다_S5()` | 🔴 이슈 5단계 중 **`TRUNCATED` 형태**. FR-4 |
| 1b | `읽던_문서를_이제_못_읽으면_보류로_강등한다_S5()` | 🔴 이슈 5단계 중 **1MB 형태**(조건 3ⓑ). 이것이 없으면 완료 조건 4 를 충족했다고 할 수 없다 |
| 1c | `사람이_해소한_판정은_…_스캔을_깨뜨리지도_않는다()` | ⑧ — #24 가드가 스캔 전체 실패로 나타나는 것 |
| 1d | `사람이_해소하면_같은_문서로_다시_강등되지_않는다_Q8()` | 해소가 무력화되고 보류가 영구 루프가 되는 것 |
| 2 | `강등되면_사유에_어느_경로가_바뀌었는지_남는다()` | 「아무도 모르는 채로」의 반대편 |
| 3 | `지문이_같으면_LLM_을_부르지_않는다()` | NFR-1 · FR-2(두 판정의 분리) |
| 4 | `저장된_지문이_없으면_바뀌었다고_주장하지_않는다()` | 🔴 NFR-4 — 배포 직후 일괄 강등 |
| 5 | `일시적으로_못_읽으면_아무것도_쓰지_않는다()` | #7 회귀 |
| 6 | `바뀌었고_읽을_수_있으면_금지로_조인다()` | #24 비대칭 가드와의 합성 |
| 7 | `금지_판정은_변경_탐지로도_풀리지_않는다()` | 🔴 S-5 · FR-2 |
| 8 | `보류_상태에서는_강등을_거부한다()` | 원래 보류 사유 보존 |
| 9 | `강등하면_사람의_해소_흔적이_판정_출처로_남지_않는다()` | §3.3 — `isHumanResolved()` 가 거짓말하지 않는다 |
| 10 | `절단된_문서도_지문을_남긴다()` | 🔴 FR-5 — 가장 현실적인 경우가 실제로 잡히는지 |
| 11 | `응답을_못_받으면_지문이_없다()` | FR-5 — 없는 것을 있다고 하지 않는다 |
| 12 | `지문_직렬화_왕복()` · `형식이_깨진_지문은_거부한다()` | 값 타입 |
| 13 | `SchemaMigrationTest` (기존) | V9 가 PostgreSQL 에서 도는가 |

**자기 규율** — 새 가드마다 한 번씩 부러뜨려 적색을 확인하고 되돌린다.
특히 4·7·9 는 통과해도 무의미해지기 쉬운 자리다.

---

## 5. 구현 순서

| 단계 | 내용 | 선행 |
|---|---|---|
| 1 | `DocumentFingerprint` · `PolicyDocumentFingerprints` + 유닛 테스트 | — |
| 2 | `FetchedDocument` 확장 · 어댑터·페이크 지문 부착 | 1 |
| 3 | V9 + `RepositoryPolicy` 필드·메서드 2개 + 엔티티 테스트 | 1 |
| 4 | `RepositoryPolicyWriter` · `ensurePolicy`/`verify` | 2·3 |
| 5 | 계측 | 4 |
| 6 | 통합 테스트 (5단계 시나리오) | 4 |
| 7 | 문서 — Q-8 확정 · `data.md` | 6 |

**순차**다. 2가 1의 타입을 쓰고 4가 2·3의 산출물을 쓴다 — 병렬 조건이 서지 않는다.

---

## 6. 리스크

| # | 리스크 | 대응 |
|---|---|---|
| R-1 | 🔴 **오탐 강등** — 지문은 바뀌었지만 금지 문구와 무관한 수정(오타 고침)인데 마침 문서가 상한을 넘음 | 출구가 있다(#24). 조건 4개를 전부 요구해 좁힌다. 오탐 비용 = 저장소 1곳 일시 정지 |
| R-2 | 🔴 **배포 직후 일괄 강등** | 조건 2(저장된 지문 존재)가 막는다. 테스트 4가 회귀 고정 |
| R-3 | 스캔마다 GitHub 13회 + **LLM 이 0회에서 늘어난다** | 아래 |
| R-4 | ⚠️ **5xx 가 계속되면 여전히 모른다** | 🔴 **이 PR 이 닫지 못한다.** `documents_checked_at` 이 멈추는 것으로 드러내고, 임계 알림은 §7 |
| R-5 | V9 번호 충돌 (다른 세션) | 머지 직전 재확인 |
| R-6 | `FetchedDocument` 컴포넌트 추가가 테스트 전반을 깨뜨린다 | 팩토리를 그대로 두어 대부분의 호출부가 무변경 |
| R-7 | 강등되면 **진행 중인 후보가 즉시 막힌다** — `AnalyzeIssuesUseCase`·`BuildRepositoryContextUseCase` 의 S-5 게이트가 `UNDETERMINED` 로 거부한다 | **의도된 동작이다.** 규약이 바뀐 것을 관측한 이상 그 저장소로 나가는 산출물을 계속 만들 이유가 없다. 사람이 풀면 재개된다 |

#### 🔴 R-3 — 비용 주장을 정정한다

초안은 「비싼 LLM 은 오히려 **줄어든다**」고 적었다. **거짓이다.** §0.1 이 확립한 현재
기준선은 정책 행이 있으면 **GitHub 0회 · LLM 0회**다 — 재확인 경로가 아예 없기 때문이다.

| | 지금(main) | 이 PR |
|---|---|---|
| 정책 행이 있을 때 스캔 1회당 GitHub | **0** | **+13** (메타데이터 1 + 문서 13 중 조건부) |
| 같은 조건에서 LLM | **0** | **0** — 지문이 같으면 부르지 않는다 |
| 규약이 실제로 바뀐 스캔에서 LLM | **0** (영영 모른다) | **1** |

즉 **늘어나는 것은 GitHub 조회이고, LLM 은 「바뀐 그 한 번」만 늘어난다.**
비교 대상은 「매 스캔 재분석」이라는 존재하지 않는 기준선이 아니라 **0** 이다.

**그래도 감수하는 이유** — 늘어나는 0회→13회는 5,000/h 예산에서 Phase 1 기준
시간당 0.26% 다. 반대편에 있는 것은 **S-5 위반이 진행 중인데 아무도 모르는 상태**다.

⚠️ **재확인 주기를 설정값으로 빼지 않는다.** 「몇 시간마다가 적절한가」는 PLAN-14 R-4 가
데이터 없음을 이유로 미룬 바로 그 질문이고, 지금 정하면 똑같이 데이터 없이 정하는 것이다.
**「매 스캔」은 주기를 정한 것이 아니라 정하지 않은 것이다** — 스캔 주기가 곧 재확인 주기이고,
스캔 주기는 이미 사람이 정한다(`scan.schedule.enabled` 는 기본 꺼져 있다).

---

## 7. 범위 밖 — 명시적으로 남긴다

🔴 **새 이슈를 만들지 않는다.** 아래는 전부 **이 PR 본문과
[`open-questions.md`](../../.claude/rules/context/open-questions.md) Q-8 에 한계로 기록**한다.
「빠뜨렸다」와 「안 하기로 했다」가 구분되지 않으면 다음 사람이 그냥 한다.

| | 왜 밖인가 | 어디에 남기나 |
|---|---|---|
| **ETag 조건부 요청** | 이득이 정확도가 아니라 **비용**이다(304 는 레이트리밋을 소모하지 않는다). `RepositorySource.fetchFile` 시그니처와 `GitHubResponse` 헤더 접근을 함께 열어야 하는 **능력 인터페이스 계약 변경**이고, Phase 1(저장소 1곳 × 13경로)에서 비용이 문제되는 구간이 아니다 | Q-8 · PR 본문 |
| **1MB 초과 응답의 blob `sha` 확보** | GitHub 은 그 응답에도 `sha` 를 싣지만 `fetchFile` 이 **그 전에 예외로 끊어** 잃는다. `support/github` 예외 타입에 사유·sha 를 싣는 별개 작업이다 | Q-8 의 §3.4 표 |
| **`documents_checked_at` 노후 알림** | 「며칠이면 늙은 것인가」는 데이터가 없다. 컬럼과 계측까지 두고 임계는 운영 데이터를 본 뒤 정한다 — 지금 정하면 데이터 없이 정하는 것이다(Q-8 의 임계 정책과 같은 논리) | Q-8 |
| **보류 자동 해제** | 🔴 만들지 않는다 — Q-8 확정 ②. 시간·횟수로 풀리는 경로는 게이트가 아니다 | 이미 확정 |
