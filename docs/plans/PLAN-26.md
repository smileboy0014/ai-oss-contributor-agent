# PLAN-26: 스케줄러 자동화 — 저장소별 주기 · 분산 락 · 후보 알림

**이슈**: [#26](https://github.com/smileboy0014/ai-oss-contributor-agent/issues/26)
**type**: feature
**작성일**: 2026-09-27
**작성자**: smileboy0014

## 1. 요구사항

### 배경

#14 가 스캔 파이프라인과 `@Scheduled` 진입점을 이미 만들었다. 이 이슈가 남긴 것은
**「사람이 매번 트리거하지 않아도 후보가 쌓이게」** 하는 나머지 조각이다.

착수 전 완료 조건 5개의 실제 상태를 실측했다. **통째로 끝낼 수 없다.**

| # | 완료 조건 | 실측 |
|---|---|---|
| 1 | 정기 스캔 스케줄 (**저장소별** 주기) | 🟡 스케줄은 #14 에서 됐다. 저장소별 주기는 **없다** — `scan.schedule.fixed-delay` 전역 하나 |
| 2 | 새 후보 발생 시 **알림 경로** | 🔴 코드·설정 흔적 0건. PRD 도 §27 다이어그램에 `Notification` 상자 하나뿐 |
| 3 | `@Scheduled` 진입점 분리 · `worker` 대비 | ✅ **#14 에서 끝났다** — `ScanScheduler` 가 `adapter/in/scheduler` 에 있다 |
| 4 | **다중 인스턴스 잠금** | ❌ 미구현. 이음매는 있다 — `ScanExecutionRegistry` 가 인터페이스고 javadoc 이 「주인은 #26」이라고 지목 |
| 5 | 자동 구현 옵트인 | 🔴 **선행 미충족** — #18(코딩 에이전트)·#23(Draft PR) 둘 다 OPEN |

**이번 범위는 1·2·4 다.** 5 는 실행기가 없어 열면 후보가 `IMPLEMENTING` 에 갇힌다 —
#24 가 `implement`·`pull-request` 를 일부러 404 로 막아 둔 것과 같은 이유다.
이슈는 **열어 둔 채** 체크박스만 채운다.

무게중심은 **4** 다. 지금 `InMemoryScanExecutionRegistry` 는 `ConcurrentHashMap` 이라
인스턴스가 둘이 되는 순간 **각자 자기 맵만 보고 같은 저장소를 동시에 스캔한다.**
사라지는 것은 화면이 아니라 **중복 방어(FR-4) 그 자체**다.

### 기능 요구사항 (FR)

| # | 요구사항 | 근거 |
|---|---------|------|
| FR-1 | 저장소마다 스캔 주기를 따로 설정할 수 있다. 미설정이면 전역 기본값 | 이슈 완료조건 1 |
| FR-2 | 스케줄러는 **주기가 된 저장소만** 기동한다 | 〃 |
| FR-3 | 중복 스캔 차단이 **프로세스 경계를 넘어** 성립한다 | 이슈 완료조건 4 · #14 FR-4 |
| FR-4 | 🔴 인스턴스가 비정상 종료해도 그 저장소가 **영구히 잠기지 않는다** | 아래 §4 리스 판단 |
| FR-5 | 새 후보가 `ANALYZED` 로 적재되면 알림 경로가 발화한다 | 이슈 완료조건 2 |
| FR-6 | 🔴 알림 실패가 스캔을 실패시키지 않는다 | 알림은 관찰이다. 관찰이 대상을 죽이면 안 된다 |

### 비기능 요구사항 (NFR)

| # | 항목 | 기준 |
|---|------|------|
| NFR-1 | 레이트리밋 | **줄어든다.** 주기가 안 된 저장소를 건너뛰므로 GitHub 호출이 오히려 감소한다. 새 대외 호출 없음 |
| NFR-2 | 락 획득 비용 | 저장소당 `UPDATE` 1회. 스케줄러 1주기에 저장소 수만큼 |
| NFR-3 | 비용 | **LLM 토큰 증가 없음** — 이 변경은 스캔을 더 돌리지 않는다. 오히려 주기 필터가 줄인다 |
| NFR-4 | 알림 지연 | 동기. 로그·메트릭이라 I/O 가 없다. 외부 전송이 생기면 그때 비동기를 판단한다 |

## 2. 게이트 판정

### 안전 경계 (S-1 ~ S-6)

| 조항 | 접촉 | 어떻게 지키는가 |
|---|---|---|
| S-1 원본 저장소 쓰기 금지 | — | push·remote·Fork 좌표를 다루지 않는다. diff 에 `pullrequest` 도메인이 없다 |
| S-2 항상 draft · 자동 머지 금지 | — | PR·리뷰·코멘트 API 를 부르지 않는다 |
| S-3 샌드박스 밖 실행 금지 | — | 대상 저장소 코드를 실행하지 않는다 |
| **S-4 시크릿 유출 금지** | ✅ | 알림 값 타입이 **식별자와 우리 어휘만** 싣는다. 이슈 제목·본문·분석 사유·예외 메시지를 넣는 자리를 **타입에 만들지 않는다** — `ScanPipelineResult` 가 이미 쓰는 수법이다. 구현이 로그로 나가므로 `logging.md` 의 「대상 저장소에서 읽은 임의 텍스트를 포맷 문자열로 쓰지 않는다」도 함께 본다 |
| S-5 대상 저장소 규약 우선 | — | 규약 판정 경로를 건드리지 않는다. 파이프라인이 `PolicyClearance` 를 요구하는 구조는 그대로다 |
| **S-6 승인 지점 우회 금지** | ✅ | 아래 |

#### S-6 — 이 이슈의 본질이 여기 있다

스케줄러 자동화는 「사람의 승인 지점을 코드로 우회하지 않는다」에 **정면으로** 닿는다.
지키는 방법 셋:

1. **자동 경로는 `ANALYZED` 에서 멈춘다.** 이 변경은 파이프라인의 **종점을 옮기지 않는다** —
   `ScanScheduler` → `LaunchScanUseCase` 경로를 그대로 두고 **누구를 부를지**만 주기로 거른다.
2. **완료조건 5(자동 구현 옵트인)를 범위에서 뺀다.** 실행기가 없는데 여는 것이 위험하다는
   판단은 #24 가 이미 내렸다.
3. 🔴 **알림은 관찰이지 행위가 아니다.** `CandidateNotifier` 구현이 승인 게이트
   (`selectByHuman`·`startImplementing`)를 부르는 경로를 만들지 않는다.

   `ApprovalGateArchitectureTest` 규칙 ①이 이것을 이미 잡는다 — 그 규칙은 「스케줄러
   패키지를 막는다」는 **거부목록이 아니라** 「승인 게이트는 **web 어댑터만** 부른다」는
   **허용목록**이다(#73 에서 뒤집혔다). 따라서 새 패키지(`adapter/out/notification`)도
   **규칙을 고치지 않고 자동으로 덮인다.**

   ⚠️ **「덮인다」를 믿지 않고 측정한다** — Phase 2 에서 알림 구현이 게이트를 부르는
   돌연변이를 심어 그 규칙이 **실제로 빨개지는지** 확인하고 결과를 PR 에 적는다
   (`testing-philosophy.md` 요구 4 — 입력 도달).

### 미결 대조 (Q-1 ~ Q-11)

| 항목 | 걸리는가 | 처리 |
|---|---|---|
| **Q-3** 실행 프로필 분리 시점 | ✅ | **닫지 않는다.** 아래 |
| **Q-2b-1** 벤더 고유 문법 금지 | ✅ | 마이그레이션 V10 은 H2·PostgreSQL 공통 문법만. `ON CONFLICT`·`MERGE`·`FOR UPDATE SKIP LOCKED` 를 쓰지 않는다 — 아래 설계가 그것을 **필요로 하지 않게** 짜였다 |
| **Q-11** Q-1 을 일반화하지 않는다 | ✅ | ShedLock 채택 여부를 이 기준으로 판정했다 — 아래 |
| Q-9 테스트 대역 3계층 | ✅ | DB 는 Testcontainers(Q-9 의 범위가 아니다 — 저쪽은 GitHub·LLM 한정). 락 동시성은 실 DB 가 아니면 검증되지 않는다 |
| Q-6 재시도 상한 | — | 재시도 축을 건드리지 않는다 |

#### Q-3 — 세 조각 중 **하나만** 채운다. 닫지 않는다

`open-questions.md` Q-3 이 「닫는 일은 **큐 도입 + 분산 락**」이라고 적고, 세 경로의 비용을
표로 남겨 뒀다. 이번에 채우는 것은 그중 **분산 락 하나**다.

| 경로 | 이번에 | 남는 것 |
|---|---|---|
| 스케줄러 → 파이프라인 | — | `@Profile("worker")` 한 줄 |
| API → 파이프라인 | — | 🔴 **큐가 필요하다** (PRD §21 Redis Streams) |
| **중복 방어** | ✅ **이번에 닫는다** — DB 락 + 마이그레이션 | — |

Q-3 절을 갱신해 「중복 방어 조각은 #26 에서 해결됐다」를 적되, **항목은 열어 둔다.**
판단 기준 둘(동시 실행 2건 필요 / API 응답 지연 관측)이 여전히 충족되지 않았다.

#### Q-11 로 판정한 것 — 🔴 **ShedLock 을 쓰지 않는다**

이슈 문구는 「ShedLock **등**」이라 특정 라이브러리를 강제하지 않는다. 검토 결과 **채택하지 않는다.**
근거는 「편해서/불편해서」가 아니라 **락의 축이 우리가 막아야 하는 것과 다르다**는 것이다.

| | ShedLock | 우리가 필요한 것 |
|---|---|---|
| 잠그는 단위 | `@Scheduled` **메서드** 1개 | 🔴 **저장소** 1건 (FR-3) |
| 인스턴스 A 가 저장소 1, B 가 저장소 2 | ❌ **직렬화된다** — 막을 이유가 없는데 막는다 | ✅ 동시에 돌아야 한다 |
| **API 트리거**(`POST /scan`) | ❌ **닿지 않는다** — 스케줄러를 안 거친다 | ✅ 같은 락을 타야 한다 |
| 스키마 | 공식 DDL 이 **벤더별**이다 | Q-2b-1 이 단일 SQL 한 벌을 요구한다 |

**결정적인 것은 세 번째 줄이다.** `POST /api/repositories/{id}/scan` 은 컨트롤러가
`LaunchScanUseCase` 를 직접 부른다 — 스케줄러를 경유하지 않는다. ShedLock 을 달면
**스케줄러와 API 가 같은 저장소를 동시에 스캔하는 것을 못 막는다.** 지금 그것을 막고
있는 것이 `ScanExecutionRegistry.tryStart` 이고, **락은 이미 올바른 자리에 있다 —
저장 매체가 프로세스 메모리인 것만이 문제다.**

그래서 **의존성을 더하지 않고 기존 이음매의 구현을 갈아끼운다.** `ScanExecutionRegistry`
javadoc 이 「갈아끼울 이음매를 여기 남긴다」고 적어 둔 그대로다.

**가정** — 미결을 가정으로 채운 것

1. **다중 인스턴스는 아직 없다.** 이 변경은 「그때가 와도 깨지지 않게」 하는 것이지
   지금 관측된 고장을 고치는 것이 아니다. 틀리면(이미 다중 인스턴스로 떠 있다면)
   이 PR 이 **회귀 수정**이 되고 우선순위가 올라간다.
2. **스캔 1회는 리스 기간 안에 끝난다.** 틀리면 `scan.lease-duration` 을 올린다 —
   설정 한 줄이고 아래 §4 가 그 값을 유일한 조정 지점으로 둔다.

## 3. 스코프

| 도메인 | 변경 | 소유 판정 근거 |
|---|---|---|
| `repository` | 수정 | 스캔 주기·스캔 실행 상태는 **저장소의 성질**이다. `last_scanned_at` 이 이미 `oss_repository` 에 산다 |
| `candidate` | 신규 | 「새 후보가 생겼다」는 후보 도메인의 사건이다 |

**도메인 간 계약** — 알림 능력

```java
// com.ossagent.candidate.domain.CandidateNotifier
public interface CandidateNotifier {
    void notifyAnalyzed(CandidateNotification notification);
}
```

- 선언: `com.ossagent.candidate.domain.CandidateNotifier` (능력 이름 — 규율 ③)
- 구현: `com.ossagent.candidate.adapter.out.notification.LoggingCandidateNotifier` (기술 이름)
- 🔴 **`Slack`·`Webhook` 같은 기술 이름이 domain 에 나타나지 않는다.** 외부 전송이
  필요해지면 어댑터 한 장을 더한다 — Q-11 이 말하는 「갈아끼울 수 있는 이음매」다

## 4. 기술 설계

### 🔴 핵심 판단 ① — 리스(lease)가 없으면 **방어가 스스로를 잠근다**

메모리 구현에는 **아무도 적어 두지 않은 안전장치**가 있었다. **재기동이 상태를 지운다.**
`InMemoryScanExecutionRegistry` javadoc 의 「재기동 외에 복구 수단이 없다」는 경고는
뒤집으면 **「재기동하면 복구된다」**는 뜻이다.

**DB 로 옮기는 순간 그 장치가 사라진다.** 인스턴스가 `kill -9` 되면 `RUNNING` 행이 남고,
그 저장소는 **영원히 409** 가 된다. 재기동해도 안 풀린다.

이것은 `external-deps.md` 에 사고로 기록된 그 모양 그대로다 —
「`resetAt` 을 모를 때 갱신할 응답이 영영 오지 않아 재기동 전까지 모든 GitHub 호출을
실패시켰다. **방어가 스스로를 잠그는 구조**였다.」

**그래서 활성 판정에 리스 만료를 넣는다.** 방향은 같은 문서의 기준으로 가른다.

| 리스를 | 틀리면 | 되돌릴 수 있나 |
|---|---|---|
| **둔다** (만료되면 뺏을 수 있다) | 아직 도는 스캔을 뺏어 **중복 스캔 1회** | ✅ 이슈 수집은 멱등(upsert)이고 다음 주기가 이어받는다 |
| 두지 않는다 | 저장소가 **영구히 잠긴다** | 🔴 **없다** — 사람이 DB 를 직접 고쳐야 한다 |

가르는 것은 보수성의 정도가 아니라 **실패의 방향이 되돌릴 수 있는가**다. 리스를 둔다.

⚠️ **heartbeat 갱신은 하지 않는다.** 스레드를 하나 더 만들고, 그 스레드가 죽으면
같은 문제가 한 겹 더 생긴다. 스캔 상한보다 넉넉한 **고정 리스**면 충분하다.

⚠️ 🔴 **「DB 를 고치세요」를 거부 메시지에 적지 않는다** — `safety-boundaries.md` 의
「게이트의 거부 메시지가 우회법을 가르치지 않는다」(#66)에 걸린다. 그 문서가 든 실제
사례가 하필 `RepositoryPolicy.resolvePending` 의 「사람이 **DB 를 고쳐야** 한다」였다.
409 응답은 **리스가 언제 풀리는지**를 말한다. 그것이 이 경우의 **통과하는 법**이다.

### 🔴 핵심 판단 ② — 원자성을 **조건부 UPDATE 한 방**으로 얻는다

`ConcurrentHashMap.compute` 가 주던 키 단위 원자성을 DB 에서 무엇이 대신하는가.

```sql
UPDATE scan_execution
   SET phase = 'QUEUED', started_at = :now, lease_expires_at = :leaseUntil,
       owner_token = :token, finished_at = NULL, failure_stage = NULL, failure_type = NULL
 WHERE repository_id = :id
   AND (phase NOT IN ('QUEUED','RUNNING') OR lease_expires_at <= :now)
```

`affectedRows == 1` 이면 **내가 잡았다.** 0 이면 남이 잡고 있다.

🔴 **`SELECT` 후 `UPDATE` 로 짜지 않는다.** 두 인스턴스가 그 사이를 통과해 **둘 다
스캔을 시작한다** — 기존 javadoc 이 `containsKey` 후 `put` 에 대해 경고한 것과 **같은
실수의 DB 판**이다. 그 경고를 메모리 구현에서 DB 구현으로 옮겨 적는다.

**행이 없을 때를 upsert 로 풀지 않는다** — `ON CONFLICT`(PostgreSQL)·`MERGE`(H2)가
**벤더 고유 문법**이라 Q-2b-1 에 걸린다. 대신 **행이 항상 존재하게** 만든다.

| 언제 | 어떻게 |
|---|---|
| 기존 저장소 | V10 이 `INSERT INTO scan_execution (repository_id, phase) SELECT id, 'IDLE' FROM oss_repository` |
| 신규 등록 | `RegisterRepositoryUseCase` 가 저장소와 **같은 트랜잭션에서** 만든다 |

⚠️ 그래서 「행이 없다」는 **정상 상태가 아니다.** `tryStart` 가 0행을 받으면 그것이
「남이 잡았다」인지 「행이 없다」인지 구분해야 한다 — 존재 확인을 분리해 **행이 없으면
예외**로 드러낸다. 조용히 「잠겨 있다」로 번역하면 등록 버그가 영구 409 로 위장된다.

⚠️ **`FOR UPDATE SKIP LOCKED` 도 쓰지 않는다** — H2 지원이 갈리고, 위 조건부 UPDATE 가
같은 것을 벤더 중립으로 준다.

### 🔴 핵심 판단 ③ — 왜 `oss_repository` 컬럼이 아니라 별도 테이블인가

`last_scanned_at` 이 이미 `oss_repository` 에 있어 컬럼 추가가 자연스러워 보인다. **아니다.**

`ScanExecutionState` 는 `lastResult`(`ScanPipelineResult` — 수치 6개 + 플래그 3개)를 들고
있다. 이것을 루트 테이블에 펴면 **컬럼이 열 개 넘게 붙고**, 저장소를 읽을 때마다 따라온다.

`repository` 애그리거트 **안**이므로 규율 ④ 위반은 아니다(FK 를 건다). 가르는 것은
경계가 아니라 **루트를 작게 유지하는가**다 — `architecture.md` 의 「애그리거트는 작게
유지한다」.

⚠️ 다만 `scan_execution` 은 **1:1 이고 덮어쓴다**(저장소당 1행). 그래서
`AgentRun`·`GeneratedChange` 와 달리 **애그리거트 멤버가 맞다** — 무한정 자라지 않는다.
「컬렉션으로 들기엔 너무 크다」는 신호가 여기엔 없다.

### 🔴 핵심 판단 ④ — 주기 판정은 **엔티티**에 둔다

`architecture.md` §3 결정 트리 Q1 — 「자기 애그리거트 데이터만으로 판단 가능한가」.
`lastScannedAt` 과 `scanIntervalMinutes` 둘 다 `OssRepository` 안에 있다. → **엔티티.**

```java
public boolean isDueForScan(Instant now, Duration defaultInterval) { … }
```

⚠️ **`last_scanned_at` 은 「요청 시각」이라 실패해도 전진한다** —
`InMemoryScanExecutionRegistry` javadoc 이 경고한 그대로다. 그래서 **실패한 스캔도
주기를 소모한다.** 이것을 결함이 아니라 **의도**로 적어 둔다 — 계속 실패하는 저장소가
매 주기마다 GitHub·LLM 을 태우는 쪽이 더 나쁘다. 「조용히 스캔 안 됨」은
`scan_execution.phase = FAILED` 와 메트릭에 드러난다.

### 변경 파일

| # | 경로 | 레이어 | 신규/수정 | 내용 |
|---|------|--------|----------|------|
| 1 | `db/migration/V10__scan_execution_and_interval.sql` | — | 신규 | `scan_execution` 테이블 · `oss_repository.scan_interval_minutes` · 기존 행 백필 |
| 2 | `repository/domain/OssRepository.java` | domain | 수정 | `scanIntervalMinutes` 필드 · `isDueForScan(Instant, Duration)` |
| 3 | `repository/domain/ScanExecution.java` | domain | 신규 | 실행 상태 엔티티 (애그리거트 멤버) |
| 4 | `repository/adapter/out/persistence/ScanExecutionJpaRepository.java` | adapter/out | 신규 | 🔴 조건부 UPDATE(`@Modifying`) — 원자적 획득 |
| 5 | `repository/application/JdbcScanExecutionRegistry.java` | application | 신규 | `ScanExecutionRegistry` DB 구현 |
| 6 | `repository/application/InMemoryScanExecutionRegistry.java` | application | **삭제** | 두 구현을 남기면 어느 것이 도는지 배포 설정이 정한다 — 아래 |
| 7 | `repository/adapter/in/scheduler/ScanScheduler.java` | adapter/in | 수정 | `isDueForScan` 으로 거른다 |
| 8 | `candidate/domain/CandidateNotifier.java` | domain | 신규 | 능력 인터페이스 |
| 9 | `candidate/domain/CandidateNotification.java` | domain | 신규 | 🔴 값 타입 — 식별자·우리 어휘만 (S-4) |
| 10 | `candidate/adapter/out/notification/LoggingCandidateNotifier.java` | adapter/out | 신규 | 구조화 로그 + 메트릭 |
| 11 | `candidate/application/AnalyzeIssuesUseCase.java` | application | 수정 | `ANALYZED` 적재 후 알림 (🔴 트랜잭션 밖) |
| 12 | `repository/application/RegisterRepositoryUseCase.java` | application | 수정 | 등록 시 `scan_execution` 행 생성 |
| 13 | `application.yml` · `.env.example` | — | 수정 | `scan.lease-duration` · `scan.default-interval` |

🔴 **#6 — `InMemoryScanExecutionRegistry` 를 남기지 않는다.** 남기면 둘 중 어느 것이
뜨는지를 **배포 설정 한 줄이 정하게** 되고, 그것은 S-2 의 「draft 플래그를 두면 언젠가
켜진다」·S-3 의 「네트워크는 설정 키가 아니다」와 같은 문제다. 테스트는 Testcontainers 로
실 DB 를 쓴다 — 락의 원자성은 **실 DB 가 아니면 검증되지 않는다.**

### 체크리스트 답변

| # | 항목 | 답 |
|---|------|---|
| 1 | 소유 도메인 | 주기·실행 상태 → `repository` / 알림 → `candidate` |
| 2 | 레이어 배치 | 주기 판정 = domain(Q1) · 락 획득 = adapter/out 질의 + application 조율 · 알림 = 능력(domain) + 구현(adapter/out) |
| 3 | 능력 인터페이스 | **필요** — `CandidateNotifier` 신규. `ScanExecutionRegistry` 는 **이미 있다**(구현만 교체) |
| 4 | 🔴 트랜잭션 안 대외 호출 없음 | ✅ 새 대외 호출이 **없다.** 알림은 로그·메트릭이라 I/O 가 없지만, 그래도 `ANALYZED` 커밋 **이후**에 부른다 — 외부 전송 어댑터가 나중에 붙어도 규율이 이미 서 있게 |
| 5 | 상태 전이 영향 | **없다.** `CandidateStatus` 전이표를 건드리지 않는다. `ScanExecutionState.Phase` 는 후보 상태머신과 **다른 축**이다 |
| 6 | 멱등성 | ✅ 스캔 재실행 멱등은 #14 가 이미 보장(이슈 upsert). 리스 만료로 인한 중복 스캔도 그 멱등성 위에 선다 — **리스를 둘 수 있는 근거가 이것이다** |
| 7 | `Clock` 주입 | ✅ 주기 판정·리스 만료 둘 다 시각 판단이다. `Instant.now()` 직접 호출 금지 |
| 8 | 🔴 안전 경계 | 위 §2 |

### 데이터 모델

**`scan_execution`** (신규 · `repository` 애그리거트 멤버)

| 컬럼 | 타입 | 제약 |
|---|---|---|
| `repository_id` | `BIGINT` | **PK** · FK → `oss_repository(id)` (애그리거트 안이라 건다) |
| `phase` | `VARCHAR(20)` | NOT NULL — `IDLE`·`QUEUED`·`RUNNING`·`SUCCEEDED`·`SKIPPED`·`FAILED` |
| `started_at` · `finished_at` | `TIMESTAMP(6) WITH TIME ZONE` | |
| 🔴 `lease_expires_at` | `TIMESTAMP(6) WITH TIME ZONE` | 활성 판정이 **이것과 함께** 본다 |
| `owner_token` | `VARCHAR(64)` | 누가 잡았나 — 진단용. **판정에 쓰지 않는다** |
| `failure_stage` · `failure_type` | `VARCHAR(40)` · `VARCHAR(255)` | 🔴 예외 **클래스 이름**만 (S-4) |
| `issues_saved` … `candidates_skipped` | `INTEGER` | `ScanPipelineResult` 6개 수치 |
| `has_more` | `BOOLEAN` | |
| `delayed_until` | `TIMESTAMP(6) WITH TIME ZONE` | 레이트리밋 — 🔴 실패가 아니라 지연 |
| `skip_reason` | `VARCHAR(40)` | |

**`oss_repository`** (수정)

| 컬럼 | 타입 | 제약 |
|---|---|---|
| `scan_interval_minutes` | `INTEGER` | NULL 허용 — **NULL 이면 전역 기본값** |

```sql
CREATE INDEX idx_scan_execution_phase_lease ON scan_execution (phase, lease_expires_at);
```

⚠️ **H2·PostgreSQL 공통 문법만** (Q-2b-1). `ON CONFLICT`·`MERGE`·`JSONB`·
`SKIP LOCKED` 를 쓰지 않는다. `TIMESTAMP(6) WITH TIME ZONE` 은 V1·V6 이 이미 쓰는 형태다.

### API 계약

**변경 없음.** `GET /api/repositories/{id}/scan` 의 응답 형태는 그대로다 —
`ScanExecutionState` 가 같은 record 이고 저장 매체만 바뀐다.

⚠️ 409 응답 본문에 **리스 만료 시각**을 더할지는 구현 중 판단한다. 더한다면 그것이
「통과하는 법」을 말하는 정당한 안내다(#66 판별 표의 「분석을 먼저 돌리세요」 줄).

## 5. 구현 순서

### 실행 모드: sequential

**판정 근거** — Stage 2 가 Stage 1 의 스키마를 전제하고, Stage 3 이 Stage 1 의 엔티티
필드를 전제한다. Stage 2·3 이 **같은 파일(`OssRepository`)을 수정**하지는 않으나
(2 는 `ScanExecution`, 3 은 `OssRepository`), Stage 4(알림)만 완전히 독립적이다.
**병렬 이득이 작고 순서 의존이 한 줄**이라 sequential 로 간다.

| Stage | 내용 | 선행 | 파일 |
|-------|------|------|------|
| 1 | 스키마 — V10 + 엔티티 매핑 | 없음 | 1·2·3 |
| 2 | 🔴 분산 락 — 조건부 UPDATE + 리스 | Stage 1 | 4·5·6·12 |
| 3 | 저장소별 주기 — `isDueForScan` + 스케줄러 | Stage 1 | 2·7 |
| 4 | 알림 — 능력 + 로그·메트릭 구현 | 없음 | 8·9·10·11 |
| 5 | 설정·문서 | Stage 1–4 | 13 + §9 |

## 6. 테스트 계획

| # | 레벨 | 대상 | 검증 내용 |
|---|------|------|----------|
| 1 | 유닛 | `OssRepository.isDueForScan` | 고정 `Clock` — 주기 전/정각/후 · `scanIntervalMinutes` NULL 이면 기본값 · `lastScannedAt` NULL 이면 즉시 대상 |
| 2 | 유닛 | `CandidateNotification` | 🔴 **S-4** — 이슈 본문·제목을 받을 자리가 **타입에 없다** |
| 3 | 유닛 | `LoggingCandidateNotifier` | FR-6 — 메트릭 기록이 예외를 던져도 **전파되지 않는다** |
| 4 | **통합(Testcontainers)** | 🔴 `tryStart` 동시성 | **동시 N 스레드가 동시에 불러도 정확히 1개만 `true`** — 실 DB 가 아니면 검증되지 않는다 |
| 5 | **통합(Testcontainers)** | 🔴 **리스 만료** (FR-4) | `RUNNING` 인 채 리스가 지난 행을 **다음 `tryStart` 가 잡는다.** 리스가 **안 지났으면 못 잡는다** — 양방향 |
| 6 | 통합 | 행이 없는 저장소 | 「잠겨 있다」가 아니라 **예외**로 드러난다 |
| 7 | 통합 | `ScanScheduler` | 주기가 안 된 저장소를 **기동하지 않는다** |
| 8 | 유닛(ArchUnit) | 🔴 **S-6** | `ApprovalGateArchitectureTest` 가 새 `adapter/out/notification` 을 **모수에 포함**하는지 |

### 🔴 가드 물림 — 「있다」가 아니라 「문다」를 확인한다

`testing-philosophy.md` 가 요구하는 것을 이 PR 에서 실제로 한다.

| 돌연변이 | 빨개져야 하는 것 |
|---|---|
| 조건부 UPDATE 의 `lease_expires_at <= :now` 를 뺀다 | 테스트 5 |
| 조건부 UPDATE 의 `phase NOT IN (…)` 을 뺀다 | 테스트 4 |
| `tryStart` 를 `SELECT` 후 `UPDATE` 로 바꾼다 | 테스트 4 (동시성) |
| 🔴 `LoggingCandidateNotifier` 가 `selectByHuman` 을 부르게 한다 | 테스트 8 — **S-6 허용목록이 새 패키지에 닿는가** |

**「무엇을 빼니 몇 건이 빨개졌다」를 PR 본문에 숫자로 적는다.**

⚠️ **테스트 4 가 실제로 동시성을 재는지 의심한다.** 스레드를 띄워도 커넥션 풀이 1이면
직렬화되어 **초록이 공허해진다.** 풀 크기와 실제 동시 진입을 함께 단언한다
(요구 3 — 샘플의 대표성).

⚠️ 🔴 **측정을 위해 띄운 스레드·executor 는 `finally` 에서 거둔다** — 마지막 줄의
정리는 그 줄에 도달했을 때만 돈다(#19 의 고아 프로세스 사고).

**대외 호출 대체** — 이 PR 은 **새 대외 호출을 만들지 않는다.** GitHub·LLM 은 기존
페이크 그대로다. DB 는 Testcontainers PostgreSQL — `testing-philosophy.md` 가
「트랜잭션 경계·동시성은 실 DB 가 아니면 검증되지 않는다」고 정했다.

## 7. 리스크

| # | 리스크 | 영향 | 대응 |
|---|--------|------|------|
| 1 | 🔴 리스가 짧아 **도는 스캔을 뺏는다** | 중복 스캔 1회 · LLM 토큰 중복 | 기본값을 스캔 상한보다 넉넉히. 멱등성이 받쳐 준다(체크리스트 6). **되돌릴 수 있는 쪽**이라 이쪽을 택했다 |
| 2 | 리스가 길어 **죽은 인스턴스의 자리가 오래 남는다** | 그 저장소가 리스 기간 동안 안 돌아간다 | 다음 주기가 이어받는다. 영구 잠금보다 낫다 |
| 3 | V10 백필이 **행을 다 못 만든다** | 그 저장소가 「행 없음」 예외 | 백필이 `SELECT id FROM oss_repository` 전량이라 누락이 구조적으로 불가능. 신규 등록 경로는 같은 트랜잭션 |
| 4 | 🔴 H2 에서 됐는데 PostgreSQL 에서 안 된다 | 기동 실패 | Q-2b-1 의 알려진 위험. `SchemaMigrationTest`(Testcontainers)가 양쪽을 본다 |
| 5 | 주기 필터가 **모든 저장소를 걸러** 스캔이 영영 안 돈다 | 조용한 정지 | `lastScannedAt` NULL 이면 즉시 대상(테스트 1). 기동 로그에 대상 수를 남긴다 |
| 6 | 알림이 **조용히 아무 데도 안 간다** | 완료조건 2 가 이름만 채워진다 | 로그 + 메트릭 **둘 다**. 「이름만 있는 칸을 두지 않는다」 |

**대외 호출 실패 시나리오** — 이 PR 이 **새로 만드는 대외 호출이 없다.**
기존 경로의 동작은 그대로다.

| 시나리오 | 기대 동작 |
|---|---|
| GitHub 레이트리밋 소진 | 변경 없음 — **지연**. `delayed_until` 이 이제 DB 에 남아 재기동 후에도 보인다(부수 이득) |
| LLM 타임아웃 | 변경 없음 |
| 🔴 **DB 가 죽는다** | **새로 생긴 실패 모드다.** 지금까지 락은 메모리라 DB 와 무관했다. DB 가 죽으면 스캔을 **시작하지 못한다** — 다만 파이프라인이 어차피 DB 를 쓰므로 실질 악화는 없다 |

## 8. 복잡도

| Stage | 파일 수 | 복잡도 |
|-------|--------|--------|
| 1 스키마 | 3 | 중간 — 벤더 중립 DDL |
| 2 🔴 분산 락 | 4 | **높음** — 원자성·리스·동시성 테스트 |
| 3 주기 | 2 | 낮음 |
| 4 알림 | 4 | 낮음 |
| 5 설정·문서 | 7+ | 낮음 |

## 9. 문서 동기화 대상

| 대상 | 필요 | 이유 |
|---|---|---|
| `codemaps/data.md` | ✅ | `scan_execution` 테이블 · `oss_repository` 컬럼 |
| `codemaps/architecture.md` | ✅ | `CandidateNotifier` 능력 · `adapter/out/notification` 패키지 |
| `codemaps/domain.md` | — | 후보 상태머신·전이표를 건드리지 않는다 |
| `.env.example` | ✅ | `scan.lease-duration` · `scan.default-interval` |
| `README.md` | ✅ | 디렉토리 구조에 `adapter/out/notification` |
| `rules/context/glossary.md` | ✅ | **리스**·**저장소별 주기**·`CandidateNotifier`. 🔴 「진행 상태(scan execution)」 항목의 「⚠️ **프로세스 메모리다. 다중 인스턴스에서는 중복 방어가 깨진다 — #26**」을 **정정해야 한다** |
| `rules/context/open-questions.md` | ✅ | Q-3 의 「중복 방어」 줄. **항목은 열어 둔다** |
| `rules/context/external-deps.md` | — | 새 대외 의존이 없다 |

## 10. 변경 이력

| 일자 | 작성자 | 변경 내용 |
|------|--------|----------|
| 2026-09-27 | smileboy0014 | 초안 — 범위 1·2·4 확정 · ShedLock 기각 · 리스 설계 |
| 2026-09-27 | smileboy0014 | 구현 — 계획과 **달라진 것 5가지**를 아래에 기록 |

---

## 11. 구현하면서 계획과 달라진 것

계획을 고치는 대신 **왜 달라졌는지**를 남긴다. 지우면 「계획대로 했다」로 읽힌다.

| # | 계획 | 실제 | 왜 |
|---|---|---|---|
| 1 | 실행 행은 `RegisterRepositoryUseCase` 가 등록 트랜잭션에서 만든다 | 🔴 **`OssRepository` 생성자가 만든다**(`cascade = {PERSIST, REMOVE}`) | **호출자가 기억하는 구조였다.** 착수해 보니 이 저장소의 테스트 세 곳이 `repositories.save(new OssRepository(...))` 로 루트만 만들고 있었다 — 운영 경로가 하나여서 안 드러났을 뿐이다. 대가는 루트를 읽을 때 질의 하나(`RepositoryPolicy` 가 이미 같은 비용을 치른다) |
| 2 | 파일 목록에 `ScanExecutionState.Phase` 이동이 없었다 | **`ScanPhase`·`ScanStage`·`ScanSkipReason` 을 domain 으로 내렸다** | 엔티티가 그 어휘를 써야 하는데 셋 다 `application` 에 있었다 — **domain 이 application 을 import 하면 의존 방향이 뒤집힌다**(규율 ①). 문자열로 들면 저장·복원이 `valueOf` 가 되어 어휘가 바뀌는 날 **읽는 쪽에서** 터진다 |
| 3 | 구현 이름이 `JdbcScanExecutionRegistry` | **`DatabaseScanExecutionRegistry`** | JPA 로 구현했다. `Jdbc` 라고 적으면 **이름이 거짓**이 된다 |
| 4 | §9 가 `.env.example` 반영을 요구 | **반영 없음** | `scan.*` 설정은 `application.yml` 에 값이 직접 적혀 있고 **환경변수 플레이스홀더를 쓰지 않는다.** 없는 변수를 만들어 넣으면 「설정할 수 있다」는 거짓 인상만 남는다 |
| 5 | `scan.schedule.fixed-delay: 1h` 그대로 | **`10m` 으로 줄였다** | 훑는 주기와 저장소 주기가 **같으면** 「주기가 됐는데 아무도 안 훑어서」 실질 주기가 두 배가 된다. 훑는 것 자체는 DB 조회 1회라 대외 호출이 아니다 |

### 초안이 틀렸던 곳 — 테스트가 잡았다

| 무엇 | 왜 틀렸나 |
|---|---|
| 「`cascade` 는 `PERSIST` 뿐. `REMOVE` 를 안 거는 것은 `policy` 와 같은 이유다」 | 🔴 **FK 가 저장소 삭제를 막는다.** `repositories.deleteAll()` 이 실패해 `ScanIssuesUseCaseTest` 11건이 적색이 됐다.<br>둘은 성격이 다르다 — `policy` 는 **판정의 기록**이라 남아야 하고, `scan_execution` 은 **실행 상태**라 저장소 없이는 뜻이 없다. 「같은 이유」로 묶은 것이 오류였다 |

### 돌연변이 검증 — 「무엇을 빼니 몇 건이 빨개졌다」

| 무엇을 제거 | 결과 |
|---|---|
| 조건부 UPDATE 의 `lease_expires_at <= :now` (+ `IS NULL`) | 🔴 **1건 적색** — 「리스가 지난 RUNNING 행은 뺏을 수 있다」 |
| 조건부 UPDATE 의 `phase NOT IN :activePhases` | 🔴 **3건 적색** |
| `tryStart` 를 `SELECT` 후 `UPDATE` 로 교체 | 🔴 **2건 적색** — 동시성 테스트 포함 |
| `LoggingCandidateNotifier` 가 `SelectCandidateUseCase` 를 참조 | 🔴 **1건 적색** — `ApprovalGateArchitectureTest` 규칙 ①(S-6). 새 패키지가 **규칙을 고치지 않고** 덮인다는 것을 실제로 확인했다 |

전부 되돌린 뒤 초록을 다시 확인했고, `grep MUTANT` 로 잔재 0건을 확인했다.

### 🕳 닫지 못한 것

| | |
|---|---|
| **완료조건 5 (자동 구현 옵트인)** | 범위 밖이다. #18·#23 이 OPEN 이라 지금 열면 후보가 `IMPLEMENTING` 에 갇힌다 — 이슈는 열어 둔다 |
| **리스 만료가 진행 조회에 반영되지 않는다** | 죽은 인스턴스의 행은 `tryStart` 가 뺏을 수 있지만, `GET …/scan` 은 여전히 `RUNNING` 으로 보고한다. 다음 `tryStart` 가 덮어쓸 때까지다 — 판정은 옳고 **표시만 낡는다** |
| **진행 조회는 「행 없음」을 드러내지 않는다** | 행이 항상 존재하므로 「한 번도 안 돌았다」는 `IDLE` + `has_result = false` 로 표현된다. 그래서 컨트롤러의 `orElseGet(idle)` 이 남는 경우는 **행이 실제로 없을 때**뿐인데, 그때 조회는 조용히 `IDLE` 을 돌려준다 — 같은 상태를 `tryStart` 는 예외로 드러낸다. **읽기에서 소리를 내지 않는 것은 의도**(조회가 등록 고장을 500 으로 만들 이유가 없다)지만, 둘의 판정이 다르다는 사실은 적어 둔다 |
