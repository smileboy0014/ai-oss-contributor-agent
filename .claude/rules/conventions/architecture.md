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
| ④ **도메인 간 호출은 application 을 통해서만** | 남의 domain 엔티티·Spring Data 인터페이스를 직접 import 하지 않는다. 필요한 것은 상대 도메인의 UseCase 또는 값 타입 |

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

### 재시도 상한이 두 축인 이유 (2026-09-22 개정 · #6)

원래 이 표는 「상한은 `agent.execution.max-retries`」 하나로 적혀 있었다. 그 문장으로는
**전송 계층 실패**를 다룰 수 없다.

| 축 | 설정 키 | 무엇을 세나 | 소진하면 |
|---|---|---|---|
| **파이프라인** | `agent.execution.max-retries` (3) | PRD §17 의 「구현→테스트」 루프. `AgentRun.attempt` 에 기록 | 후보가 `FAILED` — **사람에게 넘기는 신호** (S-6) |
| **전송 계층** | `github.max-retries` (2) | HTTP 5xx·연결 실패·타임아웃 | 그 호출 1회가 실패. 파이프라인 카운터는 그대로 |

가르지 않으면 **HTTP 5xx 한 번이 파이프라인 카운터를 태운다.** 후보가 코드 문제 없이
`FAILED` 로 떨어지고, 사람은 「AI 가 못 고쳤다」로 읽는다.

⚠️ 두 예산은 **곱해진다.** 3 × 2 = 논리적 1회 시도당 대외 호출 최대 6회다.
전송 상한을 올릴 때는 이 곱셈을 먼저 계산한다.

⚠️ 무엇을 몇 번 세는지(`AgentRun.attempt` 의 의미)는 **여전히 미결이다** —
[`../context/open-questions.md`](../context/open-questions.md) Q-6. 위 구분은 Q-6 의 답이 아니라,
**어느 답을 택하더라도 전송 계층은 별개**라는 것까지만 정한 것이다.

🔴 **레이트리밋은 재시도 대상이 아니다.** 지연이다. 즉시 다시 걸면 남은 예산만 더 태우고
2차 리밋에서는 차단이 길어진다 — [`../context/external-deps.md`](../context/external-deps.md).

## 체크리스트

- [ ] domain 의 import 에 Spring·HTTP·GitHub·LLM 이 없는가 (JPA 어노테이션만 예외)
- [ ] 트랜잭션 경계가 UseCase 에 있는가 · **트랜잭션 안에 대외 호출이 없는가**
- [ ] 진입점이 유형별(web·scheduler·event)로 분리돼 있는가
- [ ] 능력 인터페이스가 domain 에 **능력 이름**으로 있는가 · 기술 이름은 adapter/out 에만 있는가
- [ ] 남의 도메인 엔티티·Repository 를 직접 import 하지 않는가
- [ ] `Instant.now()` 대신 `Clock` 을 쓰는가
