# 아키텍처 — 헥사고날 라이트

> 구조 전체 그림은 [`codemaps/architecture.md`](../../codemaps/architecture.md).
> 넘으면 안 되는 선은 [`safety-boundaries.md`](../context/safety-boundaries.md) 가 상위 제약이다.

## 0. 단일 Gradle 프로젝트 + 패키지 경계

```
ai-oss-contributor-agent (jar 1개)
└── src/main/java/com/ossagent/
    ├── {도메인}/              repository · issue · candidate (애그리거트 3개)
    │                      agent · pullrequest (능력·어댑터 — 엔티티 없음)
    ├── support/              도메인 없는 공통 (web · github 클라이언트 …)
    └── config/               조립 전용. 비즈니스 코드 금지
```

기준 프로젝트(`torder-membership-crm`)는 `modules/{모듈}/{api,core}` Gradle 멀티모듈로 **컴파일러가 경계를 강제**한다.
여기는 그렇게 하지 않았다 — 도메인이 독립 업무가 아니라 **한 워크플로우의 연속된 단계**고, DB 스키마도 하나다.
지금 12개 Gradle 프로젝트를 만들면 강제력보다 배선 비용이 크다.

### 모듈 승격 기준

아래 중 **둘 이상**이 관찰되면 `modules/{도메인}/{api,core}` 멀티모듈로 승격한다. 그때는 컴파일러에 강제를 넘긴다.

- [ ] 도메인 간 직접 import 위반이 리뷰에서 3회 이상 반복
- [ ] 한 도메인의 변경이 다른 도메인 테스트를 반복적으로 깨뜨림
- [ ] `worker` 프로필이 분리되어 일부 도메인만 배포해야 함 (Q-3)
- [ ] 스키마를 도메인별로 가르기로 결정

승격 전까지 경계는 **리뷰와 [`safety-boundary-check.sh`](../../scripts/safety-boundary-check.sh) 가 지킨다.**

## 1. 도메인 내부 — 헥사고날 라이트

패키지 뎁스 3개: **domain / application / adapter**. 본질은 층 수가 아니라 **의존의 방향**이다.

```
adapter/in                          adapter/out
(web · scheduler · event)           (persistence · github · llm · sandbox)
        ↘                          ↙
              application            ← UseCase = 트랜잭션 경계
                   ↓
                domain               ← 중심 · 바깥을 모른다
```

```
com.ossagent.{도메인}
├── domain/          모델(=JPA 엔티티) · 상태머신 · 불변식 · 능력 인터페이스 · 도메인 예외
├── application/     XxxUseCase — 업무 흐름 조율 · 트랜잭션 경계
└── adapter/
    ├── in/{web·scheduler·event}/    진입점 유형별 분리 (+ dto/)
    └── out/{persistence·github·llm·sandbox}/
```

### 규율 4줄 (필수)

| 규율 | 내용 |
|---|---|
| ① **의존은 안쪽으로** | adapter → application → domain. **domain 의 import 에 기술이 없어야 한다** (JPA 어노테이션만 예외 — 완화 ①). Spring·HTTP·GitHub·LLM 타입이 domain 에 들어오면 반려 |
| ② **진입점 유형별 분리** | web · scheduler · event. `worker` 프로필 분리(Q-3)가 `@Profile` 로 걸리는 자리다 |
| ③ **능력은 domain 이 선언, 기술은 adapter 가 구현** | 인터페이스는 **능력 이름**으로 domain 에(`IssueSource` · `CodeSandbox` · `DraftPrPublisher`), 구현체는 **기술 이름**으로 adapter/out 에(`GitHubIssueSource` · `DockerCodeSandbox`). `client`·`port` 라는 패키지명은 쓰지 않는다 |
| ④ **애그리거트를 넘는 호출은 application 을 통해서만** | 남의 애그리거트 엔티티·Spring Data 인터페이스를 직접 import 하지 않는다. 필요한 것은 상대 도메인의 UseCase 또는 값 타입. **애그리거트 안에서는 제한하지 않는다** — 아래 참조 |

#### 규율 ④의 경계 — 엔티티 참조

**경계는 패키지가 아니라 애그리거트다.** 「누가 만들어내는가」가 아니라
**「무엇과 같은 트랜잭션에서 일관성을 지켜야 하는가」**로 가른다.

| 애그리거트 | 루트 | 멤버 |
|---|---|---|
| 저장소 | `OssRepository` | `RepositoryPolicy` |
| 이슈 | `Issue` | — |
| 후보 | `ContributionCandidate` | `PullRequest` |
| 실행 기록 | `AgentRun` | — |
| 생성 변경분 | `GeneratedChange` | — |

후보가 `PullRequest` 를 품는 이유는 [`codemaps/domain.md`](../../codemaps/domain.md) 의 불변식이다 —
「PR 은 항상 draft」·「후보당 PR 1건」이 후보 상태와 **함께 서야 한다**(①③⑨).

**`AgentRun`·`GeneratedChange` 는 멤버가 아니다.** 재시도마다 무한정 쌓이는 append-only
기록이고 `diff` 는 행마다 수십 KB 다. **애그리거트는 작게 유지한다** — 무한정 자라는 것을
멤버로 넣으면 루트를 읽을 때마다 전체를 끌고 오게 된다.

⚠️ 「컬렉션으로 들기엔 너무 크다」는 **애그리거트가 아니라는 신호**다. 성능을 이유로
멤버인데 매핑만 빼면 「이름만 애그리거트」가 된다. 경계를 다시 긋는다.

⚠️ 그래서 불변식 ⑧(재시도 상한)은 **컬렉션을 세어 판정하지 않는다.** 후보 루트가 자기
상태로 들고 있어야 한다. `attempt` 의 의미는 확정됐고(Q-6 — 아래 §4),
**남은 것은 후보 루트가 그것을 어떤 필드로 들 것인가**다 — #21 · #12.

⚠️ `agent`·`pullrequest` **패키지**는 능력·어댑터(LLM 호출·샌드박스 실행·Fork push·PR 생성)를
담고, **엔티티를 갖지 않는다.** PRD §6.2 의 모듈 목록은 파이프라인 단계별 기능 분해이지
애그리거트 분해가 아니다. 그대로 엔티티 소유로 옮기면 애그리거트가 쪼개진다.

④가 막는 것은 **애그리거트를 넘는** 참조다. 같은 애그리거트 안에서는 JPA 연관관계를 정상적으로 쓴다.

| | JPA 연관관계 | 물리 FK | 왜 |
|---|---|---|---|
| **애그리거트 안** | ✅ `@OneToOne`·`@ManyToOne` | ✅ 건다 | 함께 일관성을 지켜야 하는 것들이다. 값으로 들고 있으면 불변식을 코드로 표현할 수 없다 |
| **애그리거트 넘음** | ❌ `Long` 값만 | ❌ 걸지 않는다 | 아래 두 이유가 **서로 다르다** |

**연관관계를 막는 이유는 컴파일 결합**이다. `@ManyToOne` 을 걸면 `agent` 가 `candidate` 의 엔티티 클래스를 import 하게 되고, 떼어내는 순간 컴파일이 안 된다. 값 참조는 고칠 것이 없다.

**물리 FK 를 막는 이유는 DB 결합**이다. 테이블을 다른 DB 로 옮기면 제약이 깨지고, 삭제 순서가 DB 에 묶인다.

⚠️ 둘을 한 덩어리로 묶어 생각하지 않는다. 「`@ManyToOne` 은 쓰되 물리 FK 만 끄면 분리가 쉬워진다」는 **틀렸다** — 컴파일 결합이 그대로 남는다.

⚠️ **`@ForeignKey(ConstraintMode.NO_CONSTRAINT)` 는 이 프로젝트에서 아무 일도 하지 않는다.** 그 애노테이션은 Hibernate 가 DDL 을 생성할 때만 참조되는데, 우리는 `ddl-auto: validate` 에 스키마 정본이 Flyway SQL 이다. **물리 FK 존재 여부는 마이그레이션이 100% 결정한다.** 의도 표기로 붙이는 것은 무방하나, 그것만 믿고 SQL 을 확인하지 않으면 안 된다.

경계를 넘는 참조는 **참조 무결성을 애플리케이션과 테스트가 책임진다.** DB 가 고아 행을 막아주지 않는다.

#### 관계별 매핑 판정

| 관계 | 매핑 | 왜 |
|---|---|---|
| `repository` → `policy` | ✅ `@OneToOne` | 애그리거트 안 · 1:1 |
| `candidate` → `pullRequest` | ✅ `@OneToOne` | 애그리거트 안 · 불변식 ①③⑨ 를 코드로 표현해야 한다 |
| `candidate` → `agentRun` | ❌ ID 참조 | **애그리거트가 다르다** (무한 증가) |
| `candidate` → `generatedChange` | ❌ ID 참조 | **애그리거트가 다르다** (수십 KB × N) |
| `issue` → `candidate` | ❌ ID 참조 | 〃 |
| `repository` → `issue` | ❌ ID 참조 | 〃 (이슈는 수천 개) |

**애그리거트 안이면 매핑하고, 넘으면 ID 참조다.** 예외를 두지 않는다 —
「멤버인데 무거워서 매핑만 뺀다」가 생기는 순간 경계가 이름뿐인 것이 된다.

### 의도적 완화 2개 — 근거: 1인 개발

| 완화 | 내용 |
|---|---|
| ① 모델 = JPA 엔티티 | 도메인 모델과 영속 엔티티를 분리하지 않는다. 분리하면 애그리거트당 파일 ×3 |
| ② 자기 Repository 직접 주입 | 자기 도메인의 Spring Data 인터페이스는 application 이 직접 쓴다. 격리 규율은 **경계를 넘는 곳**(대외 능력·도메인 간)에만 적용 |

## 2. 계층별 책임

| 계층 | 주요 책임 | Repository 접근 | 트랜잭션 |
|---|---|---|---|
| **adapter/in** | DTO 변환 · UseCase 위임. **로직 없음** | ❌ | ❌ |
| **application (UseCase)** | 업무 흐름 조율 · 상태 전이 지시 · 실행 이력 적재 | ✅ (자기 도메인) | ✅ **기본 경계** |
| **domain** | 비즈니스 규칙 · 상태머신 · 불변식 · 팩토리 | ❌ | ❌ |
| **adapter/out** | 영속화 · 대외 호출(타임아웃·재시도 명시) | (구현) | ❌ |

- **트랜잭션 경계는 UseCase 다.**
- 🔴 **트랜잭션 안에서 대외 호출을 하지 않는다.** GitHub·LLM·샌드박스는 전부 트랜잭션 밖이다.
  이 규칙이 특히 중요한 이유 — **샌드박스 실행은 최대 30분**(`timeout-seconds: 1800`)이다. 트랜잭션 안에 들어가면 커넥션이 30분 잡힌다
- 대외 호출 결과의 영속화는 **호출이 끝난 뒤 짧은 트랜잭션**으로 분리한다
- 도메인 서비스는 기본 생략. rich entity + UseCase 로 시작하고, 여러 애그리거트에 걸친 규칙이 커지면 그때 추출한다

## 3. 로직을 어디에 두나

```
Q1. 자기 애그리거트 데이터만으로 판단·전이 가능한가?
    └─ YES → ✅ Entity (domain) — 상태머신·불변식·팩토리
Q2. Repository·능력 인터페이스가 필요한가?
    └─ YES → ✅ UseCase (application)
Q3. 여러 애그리거트에 걸친 도메인 규칙이 반복되는가?
    └─ YES → ✅ 도메인 서비스로 추출 (domain — 이때만 만든다)
Q4. HTTP·스케줄·이벤트 입출력인가?
    └─ YES → ✅ adapter/in — 변환·위임만
Q5. DB·GitHub·LLM·Docker 기술인가?
    └─ YES → ✅ adapter/out — domain 능력 인터페이스의 구현
```

## 4. 이 프로젝트 고유의 배치 규칙

| 규칙 | 이유 |
|---|---|
| **HTTP 상태 매핑은 `support/web` 한 곳** | 도메인 예외에 `@ResponseStatus` 를 달면 domain 이 HTTP 를 알게 되고, 같은 예외를 스케줄러가 던질 때 의미가 없어진다 |
| **시각은 `Clock` 주입** | 만료·타임아웃·재시도 경계를 테스트로 고정해야 한다. `Instant.now()` 직접 호출 금지 |
| **LLM·GitHub 응답 파싱은 adapter/out 에서 끝낸다** | 원시 JSON·모델 원문이 application 으로 올라오면 도메인이 외부 스키마에 묶인다 |
| **재시도·타임아웃은 adapter/out 에 명시** | 기본값에 맡기면 무한 대기가 생긴다. **상한은 두 축이다** — 아래 |

### 재시도 상한이 두 축인 이유 (2026-09-22 신설 · #6 / 2026-09-25 Q-6 확정 반영 · #36)

원래 이 표는 「상한은 `agent.execution.max-retries`」 하나로 적혀 있었다. 그 문장으로는
**전송 계층 실패**를 다룰 수 없다.

| 축 | 설정 키 | 무엇을 세나 | 소진하면 |
|---|---|---|---|
| **파이프라인** | `agent.execution.max-retries` (3) | `CODE`→`VERIFY`→`REVIEW` **한 바퀴**. `AgentRun.attempt` 에 기록 | 후보가 `FAILED` — **사람에게 넘기는 신호** (S-6) |
| **전송 계층** | `github.max-retries` (2) | HTTP 5xx·연결 실패·타임아웃 | 그 호출 1회가 실패. 파이프라인 카운터는 그대로 |

가르지 않으면 **HTTP 5xx 한 번이 파이프라인 카운터를 태운다.** 후보가 코드 문제 없이
`FAILED` 로 떨어지고, 사람은 「AI 가 못 고쳤다」로 읽는다.

⚠️ 두 예산은 **곱해진다.** 3 × (1 + 2) = 논리적 1회 시도당 대외 호출 최대 **9회**다
(첫 시도 + 재시도 2). 전송 상한을 올릴 때는 이 곱을 먼저 계산한다.

#### 파이프라인 축이 세는 것 — Q-6 확정 (2026-09-25 · #36)

**`CODE` → `VERIFY` → `REVIEW` 한 바퀴가 `attempt` 1**이다. 근거는 PRD §17 다이어그램이다.

| 물음 | 답 |
|---|---|
| 테스트 실패 3회인가, 리뷰 실패까지 합산인가 | **합산** — 리뷰 실패가 테스트 실패와 같은 Error Analyzer 로 들어가고 게이트가 하나뿐이다 |
| 단계별 독립인가 루프 통합인가 | **루프 통합** — 카운터의 주체는 stage 가 아니라 사이클이다 |
| `ANALYZE`·`PLAN` 은 | **카운터 밖.** 파이프라인 재시도 없이 실패 시 `FAILED` |

같은 사이클에서 만들어진 `AgentRun` 3행은 **같은 `attempt` 값을 갖는다.**

⚠️ `ANALYZE`·`PLAN` 을 재시도하지 않는 것이 「한 번에 성공해야 한다」는 뜻은 아니다.
5xx·타임아웃은 **전송 계층 축이 이미 흡수**하고 파이프라인 카운터를 태우지 않는다.

상세와 기각 사유는 [`../context/open-questions.md`](../context/open-questions.md) Q-6.

🔴 **레이트리밋은 재시도 대상이 아니다.** 지연이다. 즉시 다시 걸면 남은 예산만 더 태우고
2차 리밋에서는 차단이 길어진다 — [`../context/external-deps.md`](../context/external-deps.md).

## 체크리스트

- [ ] domain 의 import 에 Spring·HTTP·GitHub·LLM 이 없는가 (JPA 어노테이션만 예외)
- [ ] 트랜잭션 경계가 UseCase 에 있는가 · **트랜잭션 안에 대외 호출이 없는가**
- [ ] 진입점이 유형별(web·scheduler·event)로 분리돼 있는가
- [ ] 능력 인터페이스가 domain 에 **능력 이름**으로 있는가 · 기술 이름은 adapter/out 에만 있는가
- [ ] 남의 **애그리거트** 엔티티·Repository 를 직접 import 하지 않는가
- [ ] 엔티티가 **일관성 경계**를 따라 배치됐는가 — 「누가 만드는가」가 아니라 「무엇과 함께 서야 하는가」
- [ ] `Instant.now()` 대신 `Clock` 을 쓰는가
