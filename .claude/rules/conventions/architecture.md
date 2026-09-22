# 아키텍처 — 헥사고날 라이트

> 구조 전체 그림은 [`codemaps/architecture.md`](../../codemaps/architecture.md).
> 넘으면 안 되는 선은 [`safety-boundaries.md`](../context/safety-boundaries.md) 가 상위 제약이다.

## 0. 단일 Gradle 프로젝트 + 패키지 경계

```
ai-oss-contributor-agent (jar 1개)
└── src/main/java/com/ossagent/
    ├── {도메인}/              repository · issue · candidate · agent · pullrequest
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
| ④ **도메인 간 호출은 application 을 통해서만** | 남의 domain 엔티티·Spring Data 인터페이스를 직접 import 하지 않는다. 필요한 것은 상대 도메인의 UseCase 또는 값 타입. **도메인 안에서는 제한하지 않는다** — 아래 참조 |

#### 규율 ④의 경계 — 엔티티 참조

④가 막는 것은 **도메인을 넘는** 참조다. 같은 도메인 안에서는 JPA 연관관계를 정상적으로 쓴다.

| | JPA 연관관계 | 물리 FK | 왜 |
|---|---|---|---|
| **도메인 안** | ✅ `@OneToOne`·`@ManyToOne` | ✅ 건다 | 함께 움직이는 것들이라 분리 대상이 아니다. 값으로 들고 있으면 매번 두 번 조회한다 |
| **도메인 넘음** | ❌ `Long` 값만 | ❌ 걸지 않는다 | 아래 두 이유가 **서로 다르다** |

**연관관계를 막는 이유는 컴파일 결합**이다. `@ManyToOne` 을 걸면 `agent` 가 `candidate` 의 엔티티 클래스를 import 하게 되고, 떼어내는 순간 컴파일이 안 된다. 값 참조는 고칠 것이 없다.

**물리 FK 를 막는 이유는 DB 결합**이다. 테이블을 다른 DB 로 옮기면 제약이 깨지고, 삭제 순서가 DB 에 묶인다.

⚠️ 둘을 한 덩어리로 묶어 생각하지 않는다. 「`@ManyToOne` 은 쓰되 물리 FK 만 끄면 분리가 쉬워진다」는 **틀렸다** — 컴파일 결합이 그대로 남는다.

⚠️ **`@ForeignKey(ConstraintMode.NO_CONSTRAINT)` 는 이 프로젝트에서 아무 일도 하지 않는다.** 그 애노테이션은 Hibernate 가 DDL 을 생성할 때만 참조되는데, 우리는 `ddl-auto: validate` 에 스키마 정본이 Flyway SQL 이다. **물리 FK 존재 여부는 마이그레이션이 100% 결정한다.** 의도 표기로 붙이는 것은 무방하나, 그것만 믿고 SQL 을 확인하지 않으면 안 된다.

경계를 넘는 참조는 **참조 무결성을 애플리케이션과 테스트가 책임진다.** DB 가 고아 행을 막아주지 않는다.

#### 대가 — 컬렉션 탐색이 사실상 없다

ERD 의 관계 6개 중 **컬렉션이 되는 5개가 전부 경계를 넘는다.** 즉 `@OneToMany` 를 쓸 수 있는 자리가 구조적으로 0이고, `candidate.getAgentRuns()` 같은 탐색은 없다. **JPA 의 대표적 이점 하나를 포기한 것**이므로, 모르고 지나가지 않게 적어 둔다.

| 관계 | 잃은 것의 성격 |
|---|---|
| `oss_repository` → `issue` | 어차피 안 매핑했을 것 — 대상 저장소 이슈는 **수천 개**다 |
| `contribution_candidate` → `agent_run` | 어차피 안 매핑했을 것 — 재시도마다 쌓이는 **append-only 로그** |
| `contribution_candidate` → `generated_change` | 어차피 안 매핑했을 것 — 행마다 **수십 KB diff**. 후보 하나에 메가바이트가 딸려온다 |
| `issue` → `contribution_candidate` | **진짜 손실.** 작은 1:0..1 인데 못 쓴다 |
| `contribution_candidate` → `pull_request` | **진짜 손실.** 〃 |

앞의 셋은 JPA 를 쓰든 안 쓰든 `@OneToMany` 로 매핑하면 안 되는 자리라 실질 손해가 없다.
뒤의 둘은 UseCase 가 따로 조회해야 한다 — 그 비용을 감수하고 분리 가능성을 택한 것이다.

**도메인 안에서는 양방향을 만든다.** `oss_repository` ↔ `repository_policy` 가 그 예다
(`mappedBy` 로 읽기 전용 역방향, `cascade` 없음 — 종단 기록을 지우지 않는다는 원칙 때문).

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
| **재시도·타임아웃은 adapter/out 에 명시** | 기본값에 맡기면 무한 대기가 생긴다. 상한은 `agent.execution.max-retries` |

## 체크리스트

- [ ] domain 의 import 에 Spring·HTTP·GitHub·LLM 이 없는가 (JPA 어노테이션만 예외)
- [ ] 트랜잭션 경계가 UseCase 에 있는가 · **트랜잭션 안에 대외 호출이 없는가**
- [ ] 진입점이 유형별(web·scheduler·event)로 분리돼 있는가
- [ ] 능력 인터페이스가 domain 에 **능력 이름**으로 있는가 · 기술 이름은 adapter/out 에만 있는가
- [ ] 남의 도메인 엔티티·Repository 를 직접 import 하지 않는가
- [ ] `Instant.now()` 대신 `Clock` 을 쓰는가
