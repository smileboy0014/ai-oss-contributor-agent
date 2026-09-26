# PLAN-14: 스캔 트리거 연결 — POST /scan 실제 동작 + 스케줄러

- 이슈: [#14](https://github.com/smileboy0014/ai-oss-contributor-agent/issues/14)
- 브랜치: `feature/14_scan-trigger`
- 근거: PRD §7 · §21 · `open-questions.md` Q-3
- 선행: #8 · #9 · #11 — **전부 머지됨** (마지막 #11 이 `8e4ee68`)

> ⚠️ **착수 시점 `main` 이 적색이다.** #28(가드 추가)이 #11·#17 **뒤에** 머지되며
> `@ExternalText` 등록 5행이 빠졌다(`ExternalTextScrubRegistryTest` 실패).
> **PR #60 이 그 복구를 맡고 있어 이 PR 은 손대지 않는다.** 빌드 게이트는 그때까지
> 「알려진 선행 실패 1건 외 실패 0」을 기준으로 보고, PR 전에 rebase 해 완전 green 을 확인한다.

---

## 1. 요구사항

### 배경

부품은 다 있는데 **아무도 이어 붙이지 않았다.**

| 단계 | UseCase | 호출자 |
|---|---|---|
| 수집 | `ScanIssuesUseCase.scan(id, coordinates)` (#8) | **없다** |
| 필터 | `FilterIssuesUseCase.filter(id)` (#9) | **없다** |
| 분석 | `AnalyzeIssuesUseCase.analyze(id)` (#11) | **없다** |

`POST /api/repositories/{id}/scan` 은 `last_scanned_at` 만 찍고 끝난다.
이 이슈가 그 넷을 잇고 `@Scheduled` 진입점을 만든다.

### 🔴 착수하며 발견한 것 — 정책 분석에 트리거가 **아예 없다**

`AnalyzeRepositoryPolicyUseCase.analyze()` 의 **호출자가 저장소 전체에 0곳**이다
(`assertContributionAllowed` 만 #11 이 부른다).

그 결과가 무엇인지 분명히 해 두면:

```
저장소 등록  →  정책 없음(NOT_ANALYZED)  →  #11 의 S-5 게이트가 항상 차단
                                          →  후보가 단 한 건도 생기지 않는다
```

**지금 이 저장소에는 End-to-End 가 성립하는 경로가 없다.** #7 이 이것을 예상하고 있었던
흔적도 있다 — `analyze()` 의 ③ 분기 주석이 「기록 없이 중단, **다음 스캔에서 재시도**」다.
**「다음 스캔」을 만드는 것이 이 이슈**이므로, 정책 보장을 파이프라인 0단계로 넣는다 (FR-0).

### 기능 요구사항 (FR)

| # | 요구 | 출처 |
|---|---|---|
| FR-0 | 🔴 **정책이 없으면 먼저 분석**한다 — 없으면 파이프라인이 영원히 막힌다 | 위 발견 |
| FR-1 | `scan` 요청 → 수집(#8) → 필터(#9) → 분석(#11) 파이프라인 기동 | 이슈 완료조건 |
| FR-2 | 🔴 **대외 호출을 트랜잭션 밖에서** | 이슈 완료조건 |
| FR-3 | 응답은 **`202 Accepted`** + 진행 조회 수단 | 이슈 완료조건 |
| FR-4 | **중복 스캔 요청 방어** — 진행 중이면 거절 | 이슈 완료조건 |
| FR-5 | `@Scheduled` 정기 스캔 — 진입점은 `adapter/in/scheduler` 에 분리 | 이슈 완료조건 |
| FR-6 | 실패 시 **재시도 경로** — 설계 ⑥ | 이슈 완료조건 |
| FR-7 | `hasMore` 를 결과·진행 조회에 싣는다 | 세 UseCase 가 전부 돌려준다 |

### 비기능 요구사항 (NFR)

| # | 요구 | 왜 |
|---|---|---|
| NFR-1 | 🔴 **API 스레드를 점유하지 않는다** | 스캔 1회가 GitHub 10페이지 + LLM N회다. 분 단위 |
| NFR-2 | **동시 실행 1건** | Q-3 이 미분리라 단일 프로세스다. 무제한 병렬은 레이트리밋·토큰을 동시에 태운다 |
| NFR-3 | 한 저장소의 실패가 **다른 저장소를 멈추지 않는다** | 스케줄러가 여러 저장소를 돈다 |
| NFR-4 | 파이프라인 한 단계의 실패가 **앞 단계 성과를 버리지 않는다** | 수집은 됐는데 분석이 죽었다고 수집분을 잃으면 안 된다 |

---

## 2. 게이트 판정

### 안전 경계 (S-1 ~ S-6)

| 조항 | 접촉 | 어떻게 지키나 |
|---|---|---|
| **S-1** push 대상 | ❌ | push·remote·Fork 좌표를 다루지 않는다 |
| **S-2** draft 고정 | ❌ | PR·코멘트 API 를 호출하지 않는다 |
| **S-3** 샌드박스 | ❌ | 대상 저장소 코드를 실행하지 않는다 |
| **S-4** 시크릿 | 🟡 **스침** | 아래 ② |
| **S-5** 대상 저장소 규약 | 🔴 **접촉** | 아래 ① |
| **S-6** 승인 지점 | 🔴 **접촉** | 아래 ③ |

#### ① S-5 — 정책을 **만드는** 쪽이 되므로 닿는다

FR-0 이 규약 분석을 자동 경로에 넣는다. **판정 로직은 건드리지 않는다** — #7 의
`analyze()` 를 그대로 부른다. 이 PR 이 추가하는 것은 **언제 부르는가**뿐이다.

🔴 **「정책이 없을 때만」 부른다.** `analyze()` 는 보류·금지면 비용 없이 즉시 반환하지만
**허용이면 재분석**한다(`blocksReanalysis()` 가 false). 스케줄러가 매 주기 부르면
규약이 바뀌지도 않았는데 저장소마다 LLM 1회씩을 영구히 태운다.

⚠️ **보류·금지를 자동으로 풀지 않는다.** Q-8 확정 ②가 「보류는 사람이 명시적으로 푼다」이고
`analyze()` 가 이미 그렇게 막고 있다. 이 PR 이 그 위에 재시도를 얹으면 **「기다리면 통과」**가
되어 게이트가 무너진다 — 파이프라인은 `ContributionNotAllowedException` 을 **정상 종료 사유**로
다루고 다음 저장소로 넘어간다.

🔴 **그리고 금지·보류면 수집조차 하지 않는다.** S-5 게이트는 `AnalyzeIssuesUseCase` 안,
즉 4단계 중 **마지막**에 있다. 그대로 두면 `FORBIDDEN` 저장소도 매 주기 GitHub 이슈
페이지를 다 읽고 필터까지 돌린 뒤에야 skip 된다. #7 이 「**반영할 수 없는 판정에 토큰을
쓰지 않는다**」로 세운 원칙과 어긋나고, 레이트리밋 예산을 남의 몫까지 태운다.

**FR-0 직후 정책을 보고 끊는다.** 이것은 S-5 위반을 막는 것이 아니라(그건 게이트가 한다)
**위반 방지의 일관성과 비용** 문제다. 게이트는 그대로 남겨 둔다 — 이 조기 차단이
빠지거나 틀려도 마지막 게이트가 여전히 막는다.

#### ② S-4 — 새 유출면을 만들지 않는다

이 PR 은 프롬프트를 조립하지도 외부 텍스트를 적재하지도 않는다. **위임만 한다.**
다만 **진행 조회 응답과 로그**에 실패 사유가 실리므로, 예외 원문을 그대로 넣지 않는다 —
우리 어휘(예외 타입·단계 이름)와 수치만 노출한다.

#### ③ S-6 — 🔴 **자동 경로가 `ANALYZED` 에서 멈추는 것이 이 PR 의 핵심 불변식이다**

「스케줄러가 끝까지 자동으로 흘려보내지 않는다」가 S-6 의 문면이고, **이 PR 이 바로 그
스케줄러를 만든다.** 지금까지는 스케줄러가 없어서 지켜졌다.

| 파이프라인이 부르는 것 | 부르지 않는 것 |
|---|---|
| `analyze()`(정책) · `scan()` · `filter()` · `analyze()`(이슈) | `selectByHuman` · `startImplementing` · PR 생성 |

**테스트로 고정한다** — 파이프라인을 끝까지 돌린 뒤 `SELECTED` 이상 상태가 0건이고
`selectedAt` 이 전부 null 인 것을 단언한다.

⚠️ **구조로도 좁힌다.** 행위 테스트만으로는 다음 사람이 의존을 하나 더 주입하는 것을
막지 못한다. `ScanPipelineUseCase` 의 생성자 의존을 **네 UseCase 로 못 박고**, javadoc 에
금지 목록(`selectByHuman`·`startImplementing`·PR 생성)을 적는다 — #8 이
`ScanIssuesUseCase` javadoc 에서 「`candidate` 도메인을 import 하지 않는 것이 그 준수다」로
한 방식과 같다.

🔴 **`scan.schedule.enabled` 는 S-6 방어가 아니다.** 그것은 **비용·레이트리밋 방어**다.
S-6 은 플래그와 **무관하게** 성립해야 한다 — 스케줄러를 켜든 끄든 파이프라인은
`ANALYZED` 에서 멈춘다. 둘을 섞어 적으면 나중에 「운영에서 어차피 켤 거니 의미 없다」로
**둘 다 약해진다.**

### 미결 대조 (Q-1 ~ Q-11)

| 항목 | 걸리나 | 처리 |
|---|---|---|
| **Q-3** 프로필 분리 시점 | 🔴 **정면** | **선택 게이트 → 「비동기로 흡수하고 Q-3 은 열어 둔다」로 결정** (2026-09-26, 사용자 확인). 아래 |
| **Q-8** 규약 판정 | ✅ 확정분 사용 | 판정은 #7 그대로. 이 PR 은 호출 시점만 정한다 |
| **Q-6** 재시도 단위 | ✅ 확정분 사용 | 파이프라인 카운터를 건드리지 않는다. 스캔 재실행은 `attempt` 와 무관하다 |
| **Q-4** 샌드박스 | ❌ | 이 단계에 샌드박스가 없다 |
| 나머지 | ❌ | 접촉 없음 |

#### Q-3 — 열어 두는 근거

Q-3 이 스스로 적어 둔 **판단 기준 후보 둘이 아직 충족되지 않았다.**

| 기준 | 현재 |
|---|---|
| 동시 실행 2건 이상이 필요해질 때 | NFR-2 로 **1건 고정**이다. 필요해지지 않았다 |
| API 응답 지연이 관측될 때 | 이 PR 이 **202 + 비동기**로 없앤다 |

PRD §21 도 「MVP 는 Scheduler + DB 로 시작」이다. 지금 프로필을 가르면 **배포 대상이 없어
분리가 실제로 도는지 검증할 수단이 없고**, #42 가 Q-3 에 묶어 둔 런타임 가드
(「운영에서 `fakes` 가 켜지면 기동 실패」)까지 함께 판단해야 해 범위가 폭발한다.

**대신 가르기 쉬운 모양으로 만든다** — 진입점을 `adapter/in/scheduler` 로 분리하고
(규율 ②가 「`@Profile` 이 걸리는 자리」라고 지목한 그곳), 실행기를 빈 하나로 모은다.

⚠️ **다만 「`@Profile("worker")` 한 줄」은 스케줄러 경로에만 참이다.** 열어 두는 대가를
싸게 적으면 나중 판단이 왜곡되므로 정확히 남긴다.

| 경로 | Q-3 을 닫을 때 드는 비용 |
|---|---|
| 스케줄러 → 파이프라인 | `@Profile("worker")` **한 줄** |
| 🔴 **API → 파이프라인** | **큐가 필요하다.** 지금은 컨트롤러(web)가 `LaunchScanUseCase`(worker)를 **같은 JVM 에서 직접 호출**한다. 프로필을 가르면 web 에 그 빈이 없어 깨진다 — PRD §21 이 말한 Redis Streams 가 그 자리다 |

즉 Q-3 을 열어 두는 진짜 대가는 **「나중에 큐를 하나 도입해야 한다」**이다.

---

## 3. 스코프

| 포함 | 제외 |
|---|---|
| 파이프라인 조율 (정책→수집→필터→분석) | **알림** · **다중 인스턴스 잠금** · **저장소별 주기** — 전부 **#26** |
| `202` + 진행 조회 | **정책 갱신 주기(TTL)** — 아래 |
| 중복 방어 · 동시 1건 | **web/worker 프로필 분리** — Q-3 |
| `@Scheduled` 진입점 | **후보 선택·구현 트리거** — #24 · S-6 |
| | **스캔 이력 테이블** — 마이그레이션을 만들지 않는다 |

**마이그레이션 없음.** 진행 상태는 **in-memory** 로 둔다 — 아래 설계 ④.

#### #26 인계의 근거 — 확인했다

「알림은 Phase 4 몫」이 막연한 떠넘기기가 아닌지 이슈를 직접 열어 확인했다.
**#26 「스케줄러 자동화 — 정기 스캔 + 알림」(Phase 4)** 의 완료조건이 정확히 이것들이다.

| #26 완료조건 | 이 PR 과의 관계 |
|---|---|
| 「새 후보 발생 시 **알림 경로** (수단 미정 — 결정 필요)」 | 수단이 미정이라 여기서 정할 수 없다 |
| 「다중 인스턴스 대비 **잠금**(ShedLock 등) — 중복 스캔 방지」 | 설계 ④ 의 in-memory 레지스트리를 대체할 주인 |
| 「정기 스캔 스케줄 (**저장소별** 주기 설정)」 | 이 PR 은 전역 주기 하나 |

그리고 **#26 의 선행이 `#14, #23`** 이다 — 인계 대상이 실재하고, 이 이슈를 기다리고 있다.

⚠️ 🔵 `PLAN-17.md` 가 #26 을 「캐시 볼륨·누수 컨테이너 정리 정책」으로 참조하는데
**그 저장소 번호가 틀렸다**(#26 은 스케줄러·알림이다). 이 PR 에서 고치지는 않는다 —
남의 계획서이고, PR 본문에 관찰로 남긴다.

**정책 갱신 주기를 만들지 않는다.** 「한 번 허용이면 영원히 허용」이 되는데, 대상 저장소가
나중에 `AGENTS.md` 로 AI 기여를 금지하면 우리가 모른다. **실제 위험이지만 이 PR 에서
풀지 않는다** — TTL 을 정하려면 「규약이 얼마나 자주 바뀌는가」 데이터가 필요하고 지금은 0건이다.
R-4 에 남기고 별도 이슈로 올린다.

---

## 4. 기술 설계

### 변경 파일

| 파일 | 구분 | 내용 |
|---|---|---|
| `repository/application/ScanPipelineUseCase.java` | 신규 | 4단계 조율. **트랜잭션 없음** |
| `repository/application/ScanPipelineResult.java` | 신규 | 단계별 집계 |
| `repository/application/ScanExecutionRegistry.java` | 신규 | **인터페이스** — #26 이 DB 락으로 갈아끼운다 |
| `repository/application/InMemoryScanExecutionRegistry.java` | 신규 | 진행 상태 + **중복 방어** |
| `repository/application/ScanExecutionState.java` | 신규 | 진행 조회 값 |
| `repository/application/LaunchScanUseCase.java` | 신규 | `@Async` 기동 |
| `repository/application/AnalyzeRepositoryPolicyUseCase.java` | 수정 | ⚠️ **계약 표면** — `analyzeIfAbsent` 추가 + `assertNoTransaction` |
| `repository/application/ScanTarget.java` | 신규 | 좌표 + 정책 판정을 함께 나르는 값 |
| `repository/application/ScanProperties.java` | 신규 | 스케줄·풀 설정 |
| `repository/adapter/in/scheduler/ScanScheduler.java` | 신규 | 🔴 `@Scheduled` — 규율 ② |
| `repository/adapter/in/web/RepositoryController.java` | 수정 | `202` · 진행 조회 |
| `repository/adapter/in/web/dto/ScanAcceptedResponse.java` | 신규 | `202` 본문 |
| `repository/adapter/in/web/dto/ScanProgressResponse.java` | 신규 | 진행 조회 본문 |
| `repository/adapter/in/web/dto/ScanRequestedResponse.java` | 삭제 | `ScanAcceptedResponse` 로 대체 |
| `support/web/ApiExceptionHandler.java` | 수정 | `ScanAlreadyRunningException` → **409** |
| `repository/domain/ScanAlreadyRunningException.java` | 신규 | 중복 요청 |
| `config/AsyncConfig.java` | 신규 | `@EnableAsync` + 전용 풀(1) |
| `config/SchedulingConfig.java` | 신규 | `@EnableScheduling` |
| `application.yml` | 수정 | `scan.*` |

### 설계 ① — 파이프라인은 **저장소 스코프**이고 `repository` 가 소유한다

참조 방향이 `repository → issue → candidate` 이고(codemaps/architecture),
엔드포인트가 `/api/repositories/{id}/scan` 이며, S-5 게이트의 단위가 저장소다.
셋이 같은 곳을 가리킨다.

```java
// repository/application — 🔴 트랜잭션 없음. 안에서 GitHub·LLM 을 부른다
public ScanPipelineResult run(Long repositoryId) {
    // FR-0 — 정책이 없을 때만 분석한다. 좌표를 함께 돌려받아 DB 왕복을 아낀다
    ScanTarget target = policy.analyzeIfAbsent(repositoryId);

    // 🔴 금지·보류면 수집 전에 끊는다 — 반영할 수 없는 판정에 레이트리밋을 쓰지 않는다
    if (!target.contributionAllowed()) {
        return ScanPipelineResult.skipped(target.skipReason());
    }
    ScanResult scan = scanIssues.scan(repositoryId, target.coordinates());
    FilterResult filter = filterIssues.filter(repositoryId);
    AnalysisResult analysis = analyzeIssues.analyze(repositoryId);  // 게이트는 그대로 남는다
    return ScanPipelineResult.of(scan, filter, analysis);
}
```

**`ScanTarget`(신규 값)이 좌표와 정책 판정을 함께 돌려준다.** `scan()` 이
`RepositoryCoordinates` 를 요구하는데 그것을 따로 조회하면 DB 왕복이 하나 더 는다.

🔴 **`analyzeIfAbsent` 에 `@Transactional` 을 붙이지 않는다.** 붙이면 그 안의
`analyze()` 가 GitHub·LLM 을 **트랜잭션 안에서** 부른다. 존재 확인 조회만 짧은 트랜잭션
메서드로 분리한다. ⚠️ **`AnalyzeIssuesUseCase.assertNoTransaction()` 이 이 단계를 잡아주지
못한다** — 그 가드는 4단계에 있고 그때는 트랜잭션이 이미 닫혀 통과한다.
그래서 `AnalyzeRepositoryPolicyUseCase` **에도 같은 단언을 넣는다.**

🔴 **`@Transactional` 을 붙이지 않는다** (FR-2). 세 UseCase 가 각자 짧은 트랜잭션을 열고,
`AnalyzeIssuesUseCase` 는 `assertNoTransaction()` 으로 **감싸는 것을 거부**한다 — 실수하면
기동이 아니라 호출 시점에 터지므로 테스트가 잡는다.

### 설계 ② — 단계 실패를 **삼키되 버리지 않는다** (NFR-4)

| 실패 | 처리 |
|---|---|
| 정책 **일시 실패** (`Optional.empty`) | 다음 단계로 **가지 않는다**. 분석이 어차피 막힌다. 다음 주기가 재시도 |
| 🔴 **보관된 저장소**(archived) — 이것도 `Optional.empty` 다 | `Optional.empty` 의 **두 번째 의미**다(`analyze()` ②분기). 영구 상태인데 「다음 주기 재시도」로 다루면 **매 주기 GitHub 을 영원히 두드린다.** 사유를 갈라 `ARCHIVED` 로 집계하고 진행 조회에 드러낸다 |
| 🔴 **POLICY 단계의 `GitHubRateLimitException`** | `analyze()` 의 catch 는 `LlmTransientException` 뿐이라 **맨몸으로 올라온다**. 수집 단계처럼 값으로 오지 않는다 — 파이프라인이 잡아 **지연**으로 집계한다. 실패로 세면 glossary 의 「리밋을 실패라 쓰지 않는다」에 정면으로 걸린다 |
| `ContributionNotAllowedException` | 🔴 **실패가 아니다.** S-5 가 정상 작동한 것이다. `skipped` 로 집계하고 정상 종료 |
| `GitHubApiException`(권한 등) | 단계 실패로 기록하고 중단. 다음 단계를 돌려도 입력이 없다 |
| 레이트리밋 | `ScanResult.delayedUntil` 로 **이미 값으로 온다**(#8). 실패가 아니라 지연 — 그대로 실어 나른다 |
| 분석 중 개별 이슈 실패 | #11 이 이미 흡수한다 |

⚠️ **「수집 0건」과 「수집 실패」를 가른다.** 304(변경 없음)는 정상이고 흔하다.

### 설계 ③ — 🔴 비동기 · 동시 1건 (NFR-1 · NFR-2 · FR-4)

```
POST /{id}/scan
   │
   ├─ registry.tryStart(id)  ── 실패 ──▶ 409 ScanAlreadyRunningException
   │        (진행 중인 저장소면 거절)
   ├─ requestScan(id)          last_scanned_at 기록 (짧은 트랜잭션)
   ├─ @Async 로 파이프라인 던짐  ← 전용 풀. corePoolSize=1
   └─ 202 Accepted + statusUrl
```

**「거절」을 택한 이유** — 이슈가 「거절 또는 병합」을 허용했다. 병합(진행 중인 실행에
합류)은 호출자에게 **남의 실행 결과**를 돌려주게 되고, 그 실행이 언제 시작했는지에 따라
새 이슈가 반영될 수도 안 될 수도 있다. **거절은 상태가 하나**다.

⚠️ **`@Async` 는 self-invocation 이면 동작하지 않는다.** 컨트롤러가 직접 `@Async` 메서드를
갖지 않고 별도 빈(`LaunchScanUseCase`)에 둔다 — #11 의 `CandidateAnalysisWriter` 와 같은 이유.

⚠️ **`@Async` 메서드 밖으로 나간 예외는 사라진다.** 안에서 전부 잡아 레지스트리에 기록한다.
잡지 않으면 「202 를 받았는데 아무 일도 안 일어난 것처럼 보이는」 상태가 된다.

**전용 풀을 쓴다** — 기본 `SimpleAsyncTaskExecutor` 는 요청마다 스레드를 새로 만든다.
`corePoolSize=1` · `maxPoolSize=1` · 작은 큐 + `AbortPolicy` 로 **동시 1건**을 강제한다.
큐가 차면 거절되고 그것도 409 로 나간다.

🔴 **큐 거절 시 레지스트리를 반드시 되돌린다 — 안 하면 영구 `RUNNING` 이 된다.**

순서가 `tryStart` → `@Async` 제출인데, 제출 거절(`RejectedExecutionException`)은
**호출 스레드에서** 난다. 그때 `tryStart` 를 되돌리지 않으면 그 저장소는 **영원히
「진행 중」**이고 이후 모든 요청이 409 로 막힌다. 상태가 in-memory 라 손으로 고칠 곳도
없어 **재기동 외에 복구 수단이 없다.**

```java
boolean started = registry.tryStart(id);       // 실패 → 409
try {
    launcher.launch(id);                       // @Async 제출
} catch (RejectedExecutionException e) {
    registry.release(id);                      // 🔴 되돌린다
    throw new ScanAlreadyRunningException(id, QUEUE_FULL);
}
```

`큐가_차서_거절되면_다음_요청이_다시_받아들여진다` 를 테스트로 고정한다.

**진행 상태에 `QUEUED` 를 둔다.** 풀이 core=1 이므로 저장소 B 는 실제로는 **큐에서 대기**
중인데, 상태 어휘에 `QUEUED` 가 없으면 `RUNNING` 으로 보인다. NFR-2(동시 1건)가 실제로
지켜지는지 사람이 확인할 수 있는 유일한 창이다.

### 설계 ④ — 진행 조회는 **in-memory** 다 (FR-3)

```
GET /api/repositories/{id}/scan
  → { state: IDLE|QUEUED|RUNNING|SUCCEEDED|FAILED|SKIPPED,
      startedAt, finishedAt,
      lastResult: {수집·필터·분석 수치, hasMore},   ← FR-7
      lastFailureStage, lastFailureType }
```

**마이그레이션을 만들지 않는 이유** — 스캔 이력 테이블(`scan_run`)은 그 자체로 설계가
필요하고(보관 기간·인덱스·조회 API), 지금 필요한 것은 「방금 던진 요청이 어떻게 됐나」뿐이다.
`oss_repository.last_scanned_at` 이라는 **영속 흔적은 이미 있다.**

⚠️ 🔴 **알려진 한계 — 이것은 「조회가 안 보인다」가 아니라 「방어가 사라진다」다.**
같은 레지스트리가 **FR-4(중복 방어)의 유일한 구현체**다(설계 ③ `tryStart`).
인스턴스가 둘이 되는 순간 사라지는 것은 화면이 아니라 **중복 스캔 차단**이고, 그때
필요한 것은 「함께 본다」가 아니라 **DB 락 + 마이그레이션**이다.

**주인이 있다** — #26 완료조건에 「다중 인스턴스 대비 **잠금(ShedLock 등) — 중복 스캔 방지**」가
명시돼 있고 #26 의 선행이 **#14**(이 이슈)다. 그쪽으로 넘긴다.

**갈아끼울 이음매를 남긴다** — 레지스트리를 인터페이스로 두고 in-memory 구현을 주입한다.
§3 이 「마이그레이션 없음」을 못 박았으므로 더더욱 이음매가 필요하다.

재기동하면 상태가 사라지는 것도 같은 한계다 — R-3.

⚠️ 🔴 **실패 사유에 예외 원문을 싣지 않는다** (S-4 ②). `lastFailureType` 은 **예외 클래스 이름**,
`lastFailureStage` 는 **우리 단계 어휘**(`POLICY`·`SCAN`·`FILTER`·`ANALYZE`)다.

### 설계 ⑤ — `@Scheduled` 는 `adapter/in/scheduler` 에 (FR-5 · 규율 ②)

```java
@Component
// 🔴 이 한 줄이 「기본 비활성」의 전부다. 없으면 enabled 값은 아무 일도 하지 않는다
@ConditionalOnProperty(name = "scan.schedule.enabled", havingValue = "true")
public class ScanScheduler {
    @Scheduled(fixedDelayString = "${scan.schedule.fixed-delay}",
               initialDelayString = "${scan.schedule.initial-delay}")
    public void scanAll() { ... }   // 등록된 저장소를 순회하며 launch
}
```

🔴 **초안에는 이 `@ConditionalOnProperty` 가 없었다.** `@Scheduled` 만 달아 두고
「기본 비활성」이라 적으면 **설정값을 읽는 코드가 어디에도 없어** 플래그가 장식이 된다.
R-1 의 완화책 전체가 이 한 줄에 걸려 있으므로 **「비활성이면 스케줄러 빈이 없다」를
테스트로 고정**한다.

- 🔴 **한 저장소의 실패가 순회를 멈추지 않는다** (NFR-3) — 저장소마다 try/catch
- 진행 중인 저장소는 레지스트리가 걸러낸다(FR-4 재사용)
- **기본 비활성** (`scan.schedule.enabled: false`). 켜는 것은 배포 결정이고, 끄고 시작하는 편이
  안전하다 — 테스트·로컬 기동이 GitHub·LLM 을 자동으로 타면 안 된다
- ⚠️ `@EnableScheduling` 을 별도 `@Configuration` 으로 둔다. Q-3 을 닫을 때
  **여기에 `@Profile("worker")` 한 줄**이 붙는다

### 설계 ⑥ — 🔴 재시도 주체는 **다음 주기**다 (FR-6)

**루프를 새로 만들지 않는다.** 이 파이프라인의 네 단계가 이미 전부 「실패하면 그대로 두고
다음 실행이 이어받는다」로 설계돼 있다.

| 단계 | 이어받는 근거 |
|---|---|
| 정책 | `analyze()` ③분기 — 「기록 없이 중단, **다음 스캔에서 재시도**」 |
| 수집 | 커서를 전진시키고 `delayedUntil`·`hasMore` 를 남긴다 (#8) |
| 필터 | 「배치 상한에 걸리면 남은 것을 두고 끝낸다 — **다음 실행이 이어받는다**」 (#9) |
| 분석 | 후보가 `ANALYZING` 에 남거나 `FAILED` 종단. 이슈 1건 실패가 배치를 죽이지 않는다 (#11) |

**즉 재시도 = 다음 스케줄 주기**이고, 이 PR 이 할 일은 **그 주기를 만드는 것**이다.

⚠️ 그래서 두 가지를 분명히 적어 둔다.

1. 🔴 **`scan.schedule.enabled: false` 이면 재시도 주체가 존재하지 않는다.**
   API 로만 트리거하는 동안에는 사람이 다시 부르는 것이 유일한 재시도다.
   기본값이 꺼짐이므로 **이것이 기본 상태**다 — R-7.
2. **재시도 상한이 없다.** 이것은 S-6 의 「재시도 상한을 무한으로 바꾸지 않는다」와
   **다른 축**이다. 그 조항은 `agent.execution.max-retries`(후보의 구현 루프)를 말하고,
   여기는 **스캔 주기**다. 후보를 `FAILED` 로 떨어뜨리는 카운터를 건드리지 않는다(Q-6).
   다만 「영원히 실패하는 저장소」가 조용히 매 주기 비용을 태우는 것은 실제 위험이라 R-8 에 남긴다.

### 설계 ⑦ — `hasMore` 를 버리지 않는다 (FR-7)

세 UseCase 가 전부 `hasMore` 를 돌려주는데 초안이 그것을 읽지 않았다.
**「한 번 돌렸다」와 「다 돌았다」는 다르다** — 이슈 수천 건이면 상한에 걸려 남는 것이 정상이다.

- `ScanPipelineResult` 가 세 단계의 `hasMore` 를 **OR** 로 모아 싣는다
- 진행 조회에 노출한다 — 사람이 「아직 남았다」를 볼 수 있어야 한다
- 🔴 **자동으로 이어 달리지 않는다.** 한 실행 안에서 루프를 돌면 NFR-2(동시 1건)가
  한 저장소에 무한정 점유당하고, 레이트리밋을 한 번에 태운다.
  **다음 주기가 이어받는다**(설계 ⑥과 같은 원리). 대신 그 대가를 적어 둔다 —
  `fixed-delay: 1h` 이면 남은 것을 비우는 데 **N 시간**이 걸린다 (R-9).

### 체크리스트 답변

| # | 항목 | 답 |
|---|---|---|
| 1 | 소유 도메인 | **`repository`** — 저장소 스코프 조율. 참조 방향·엔드포인트·S-5 단위가 일치 |
| 2 | 레이어 배치 | 조율 → `application` / `@Scheduled`·HTTP → `adapter/in` / 조립 → `config` |
| 3 | 능력 인터페이스 | **불필요** — 새 대외 의존이 없다. 기존 UseCase 를 부를 뿐이다 |
| 4 | 🔴 트랜잭션 안 대외 호출 | **없다** — 설계 ① |
| 5 | 상태 전이 영향 | **없다** — 전이는 #11 이 한다. 이 PR 은 부르기만 한다 |
| 6 | 멱등 | 스캔은 커서로(#8), 후보는 `UNIQUE(issue_id)`로(#11) 이미 멱등이다. 이 PR 은 **중복 실행**만 막는다 |
| 7 | `Clock` 주입 | ✅ 레지스트리의 `startedAt`·`finishedAt` |
| 8 | 🔴 안전 경계 | S-5 · S-6 — §2 |

### 설정

```yaml
scan:
  schedule:
    enabled: false        # 🔴 기본 꺼짐 — 켜는 것은 배포 결정이다
    fixed-delay: 1h
    initial-delay: 5m
  pool:
    queue-capacity: 10    # 넘치면 409. 무한 큐는 「쌓이는 줄 모르는」 상태를 만든다
```

**새 환경변수 없음** → `.env.example` 변경 없음.

---

## 5. 구현 순서

### 실행 모드: sequential

| 단계 | 내용 | 선행 |
|---|---|---|
| 1 | `ensureAnalyzed` + 단위 테스트 (FR-0 — 이것이 없으면 나머지가 무의미) | — |
| 2 | `ScanExecutionRegistry` · `ScanExecutionState` · `ScanPipelineResult` | — |
| 3 | `ScanPipelineUseCase` + 단계 실패 처리 | 1·2 |
| 4 | `LaunchScanUseCase`(`@Async`) + `AsyncConfig` | 3 |
| 5 | 컨트롤러 `202`·진행 조회 + 409 매핑 | 4 |
| 6 | `ScanScheduler` + `SchedulingConfig` | 4 |
| 7 | 통합 테스트 · 문서 동기화 | 5·6 |

---

## 6. 테스트 계획

### 🔴 안전 경계 — 커버리지 100% 대상

| 테스트 | 무엇을 고정하나 |
|---|---|
| `파이프라인은_ANALYZED_까지만_간다_S6` | 끝까지 돌린 뒤 `SELECTED` 이상 0건 · `selectedAt` 전부 null |
| `스케줄러는_사람의_승인_지점을_부르지_않는다_S6` | 스케줄러 경로에서도 같다 |
| `규약이_보류면_분석하지_않고_정상_종료한다_S5` | `ContributionNotAllowedException` 을 **실패가 아니라 skip** 으로 · 다음 저장소로 진행 |
| `보류는_자동으로_풀리지_않는다_S5` | 스캔을 두 번 돌려도 보류가 유지된다 — 「기다리면 통과」 금지 |
| `진행_조회에_예외_원문이_실리지_않는다_S4` | 실패 응답에 스택·메시지 원문이 없다 |
| 🔴 `스케줄이_비활성이면_스케줄러_빈이_없다` | `@ConditionalOnProperty` 가 **실제로 배선됐는지** — 이것이 없으면 R-1 완화책 전체가 장식이다 |
| `금지_저장소는_수집도_하지_않는다_S5` | 조기 차단 — GitHub 호출 0회 · 필터 0회 |

### 유닛

| 대상 | 케이스 |
|---|---|
| `ensureAnalyzed` | 정책 없음 → 분석함 / 정책 있음 → **부르지 않음**(토큰 0) |
| `ScanExecutionRegistry` | 동시 시작 거절 · 종료 후 재시작 허용 · 저장소별 독립 |
| `ScanPipelineUseCase` | 단계별 실패에서 앞 단계 결과가 보존되는가 · 레이트리밋 지연이 값으로 전달되는가 · `hasMore` 가 OR 로 모이는가 · 보관된 저장소가 `ARCHIVED` 로 갈리는가 |
| `analyzeIfAbsent` | 🔴 **트랜잭션 안에서 부르면 거부**한다 (`assertNoTransaction`) |

### 통합 (`@AgentIntegrationTest`)

| 케이스 | 단언 |
|---|---|
| 정상 파이프라인 | 이슈 수집 → 필터 판정 → 후보 `ANALYZED` 가 **DB 에** 남는다 |
| `POST /scan` | **202** · `Location`/`statusUrl` · 즉시 반환(블로킹하지 않는다) |
| 중복 요청 | **409** · 두 번째가 파이프라인을 **돌리지 않는다** |
| 🔴 `큐가_차서_거절되면_다음_요청이_다시_받아들여진다` | 레지스트리 누수 회귀 — 안 되면 재기동 전까지 그 저장소를 스캔할 수 없다 |
| 스케줄러 | 저장소 2개 중 하나가 실패해도 나머지가 돈다 (NFR-3) |
| 진행 조회 | `RUNNING` → `SUCCEEDED` 전이가 보인다 |

⚠️ 비동기 테스트는 `Awaitility` 로 기다린다. `Thread.sleep` 은 느리거나 깨진다.
대역(`FakeIssueSource`·`FakeIssueAnalyst` 등)은 싱글턴이므로 `@BeforeEach` 초기화.

---

## 7. 리스크

| # | 리스크 | 완화 |
|---|---|---|
| R-1 | 🔴 **스케줄러가 켜진 채 배포되면 GitHub·LLM 을 자동으로 탄다** | `enabled: false` 기본값 + 기동 로그에 상태 명시 |
| R-2 | `@Async` 예외가 삼켜져 「202 만 받고 아무 일도 없음」 | 메서드 안에서 전부 잡아 레지스트리에 기록 · 테스트로 고정 |
| R-3 | 진행 상태가 재기동·다중 인스턴스에서 깨진다 | 알려진 한계로 명시. Q-3 을 닫을 때 함께 본다 |
| R-4 | **정책 갱신 주기가 없다** — 대상이 나중에 AI 기여를 금지해도 모른다 | 실제 위험. 별도 이슈로 올린다(아래) |
| R-5 | 동시 1건이라 저장소가 늘면 순회가 길어진다 | Q-3 의 판단 기준 ①이 충족되는 순간이다. 그때 가른다 |
| R-6 | 다른 세션이 `application.yml`·`ApiExceptionHandler` 를 동시에 고친다 | 착수 시점 열린 worktree 3개(#15 · `fix/_external-text-registry-rows` · `fix/58`) 확인 후 rebase |
| R-7 | 🔴 **기본값(`enabled: false`)에서는 재시도 주체가 없다** | 설계 ⑥. 사람이 다시 부르는 것이 유일한 재시도다. PR 본문에 명시 |
| R-8 | 영원히 실패하는 저장소가 조용히 매 주기 비용을 태운다 | 진행 조회에 마지막 실패가 남는다. 임계·알림은 #26 |
| R-9 | `hasMore` 를 자동으로 비우지 않아 `fixed-delay × N` 이 걸린다 | 설계 ⑦. 의도적 선택이고 진행 조회로 보인다 |
| R-10 | `last_scanned_at` 은 **요청 시각**이라 실패해도 전진한다 | 재기동 후에는 성공·실패가 DB 상 구분되지 않는다. 커서와는 분리돼 있어 glossary 의 겹쳐쓰기 금지에는 걸리지 않는다. 이력 테이블은 #26 |

---

## 8. 복잡도

| 단계 | 파일 수 | 복잡도 |
|---|---|---|
| 1 `ensureAnalyzed` | 1 | 낮음 |
| 2 레지스트리 | 3 | **중간** — 동시성 |
| 3 파이프라인 | 2 | **높음** — 단계 실패 처리 |
| 4 비동기 조립 | 2 | 중간 |
| 5 웹 | 5 | 낮음 |
| 6 스케줄러 | 2 | 낮음 |
| 7 테스트·문서 | ~8 | **높음** — 비동기 검증 |

---

## 9. 문서 동기화 대상

| 문서 | 왜 |
|---|---|
| `codemaps/architecture.md` | `adapter/in/scheduler` 신설 · 「스캔은 요청 사실만 기록」 정정 |
| `rules/context/project-overview.md` | 「`POST /scan` 은 요청 사실만 기록」이 **거짓이 된다** |
| `rules/context/glossary.md` | 파이프라인 단계 표에 「스캔 실행」·「진행 상태」 |
| `rules/context/open-questions.md` | **Q-3 에 판단 기록을 남긴다** — 닫지는 않는다 |
| `.env.example` | ❌ 변경 없음 |

---

## 10. 변경 이력

| 일자 | 작성자 | 변경 내용 |
|------|--------|----------|
| 2026-09-26 | smileboy0014 | 초안 — 정책 트리거 부재(FR-0) 발견 반영 |
| 2026-09-26 | smileboy0014 | rev 2 — `gap-analyzer` 검토 반영. 🔴 `enabled` 배선 누락 · `analyzeIfAbsent` 트랜잭션 · 레지스트리 누수 · FR-6 설계 부재. 🟡 `hasMore`(FR-7) · 금지 저장소 조기 차단 · `Optional.empty` 두 의미 · POLICY 레이트리밋 · Q-3 대가 정정(API 경로는 큐) · R-3 을 「FR-4 가 깨진다」로 · #26 인계 근거 확인 |
