# 아키텍처 코드맵

> 기준 — [PRD](../../docs/ai-oss-contributor-agent-prd.md) §6 System Architecture · §21 Redis Job Architecture · §25 Security Architecture (v1.1 Draft).
> 내부 레이어링 규율은 [`../rules/conventions/architecture.md`](../rules/conventions/architecture.md), 넘으면 안 되는 선은 [`../rules/context/safety-boundaries.md`](../rules/context/safety-boundaries.md).
> ⚠️ **대부분 비어 있다.** 아래 구조는 설계이고, 지금 존재하는 코드는 § 「구현 현황」의 ✅ 뿐이다.

## 시스템 구성 — 모듈러 모놀리스 · 인스턴스 1개

```
┌──────────────────────────── 진입 ─────────────────────────────┐
│   개발자 (HTTP)                      Scheduler (예정)          │
│   POST /api/repositories/{id}/scan   일 1회 스캔 트리거         │
│   POST /api/candidates/{id}/...                               │
└───────────────┬───────────────────────────┬───────────────────┘
                ▼                           ▼
┌──────────────────────────────────────────────────────────────┐
│          ai-oss-contributor-agent  (jar 1개 · Spring Boot)     │
│                                                              │
│   ┌──────────┐  ┌───────┐  ┌───────────┐  ┌───────┐  ┌─────┐ │
│   │repository│─▶│ issue │─▶│ candidate │─▶│ agent │─▶│ pr  │ │
│   │ 등록·규약  │  │수집·필터│  │후보·상태머신│  │LLM·샌박│  │Draft│ │
│   └──────────┘  └───────┘  └───────────┘  └───────┘  └─────┘ │
│        │            │            │            │         │    │
│        └────────────┴─── support (web · github) ───┴─────┘    │
│                          config (조립 전용)                    │
│                                                              │
│   실행 프로필:  web (API)  /  worker (스캐너·코딩·검증)  ← 미분리 │
└────┬──────────────┬──────────────┬───────────────┬───────────┘
     │              │              │               │
     ▼              ▼              ▼               ▼
┌─────────┐   ┌──────────┐   ┌──────────┐   ┌────────────┐
│ GitHub  │   │  LLM API │   │  Docker  │   │ PostgreSQL │
│   API   │   │(Anthropic│   │ Sandbox  │   │  + Redis   │
│ 읽기·Fork│   │    )     │   │ 격리 실행 │   │            │
└─────────┘   └──────────┘   └──────────┘   └────────────┘
```

**참조는 한 방향이다** — `repository → issue → candidate → agent → pullrequest`.
역방향 호출을 만들지 않는다. 뒷 단계가 앞 단계를 호출하면 파이프라인이 사이클이 되고, 재시도 경로에서 무한 루프가 생긴다.

## 도메인 5 + 공통 2

| 패키지 | 소유 | 소유하지 않음 |
|---|---|---|
| `repository` | 대상 저장소 등록 정보 · **기여 규약**(`RepositoryPolicy`) · 스캔 커서 | 이슈 본문 · 후보 판정 |
| `issue` | 이슈 스냅샷 · 수집 커서 · **필터 판정 이력** | 기여 가능성 판정(LLM) |
| `candidate` | 기여 후보 · **상태머신** · 난이도·신뢰도 분석 결과 · **실행 이력**(`AgentRun`) · 생성 변경분(`GeneratedChange`) | LLM 호출 자체 · PR 생성 |
| `agent` | LLM 호출 · 프롬프트 스크럽 · 샌드박스 실행 · **전송 재시도** | **실행 이력 엔티티** — `candidate` 소유다 |
| `pullrequest` | Fork 좌표 · 브랜치 · **Draft PR** 메타데이터 | 코드 생성·검증 |
| `support` | HTTP 예외 매핑 · 공통 GitHub 클라이언트(토큰·레이트리밋) | 도메인 규칙 일체 |
| `config` | 빈 조립 (`ClockConfig` 등) | **비즈니스 코드 금지** |

## 도메인 내부 — 헥사고날 라이트

```
com.ossagent.{도메인}
├── domain/          엔티티 · 상태머신 · 불변식 · 능력 인터페이스 · 도메인 예외
├── application/     XxxUseCase — 트랜잭션 경계
└── adapter/
    ├── in/{web · scheduler · event}/     (+ dto/)
    └── out/{persistence · github · llm · sandbox}/
```

의존은 안쪽으로만. `adapter → application → domain`.
**domain 의 import 에 기술이 없어야 한다** — Spring·HTTP·GitHub·LLM 타입이 들어오면 반려다.
어기면 도메인 규칙을 외부 스키마 변경 없이는 테스트할 수 없게 된다. 규율 전문은 [`../rules/conventions/architecture.md`](../rules/conventions/architecture.md).

## 파이프라인 — 단계별 소유

| # | 단계 | 도메인 | 하는 일 | 대외 호출 |
|---|---|---|---|---|
| 1 | Register | `repository` | 대상 저장소 등록 · 중복 차단 | — |
| 2 | Policy Analysis | `repository` | `CONTRIBUTING.md`·`AGENTS.md`·빌드 설정 수집 → `RepositoryPolicy` | GitHub · LLM |
| 3 | Scan | `issue` | open 이슈 증분 수집 (`updated_at` 커서 · ETag) | GitHub |
| 4 | Filter | `issue` | 규칙 배제 — 종료됨 · 활성 PR · 요구 불명확 · 대규모 변경 | — |
| 5 | Analysis | `candidate` ← `agent` | 기여 가능성 판정 → `DISCOVERED` → `ANALYZED` | LLM |
| 6 | **Selection** | `candidate` | **사람이** 고른다 → `SELECTED` | — |
| 7 | Repo Analysis | `agent` | 키워드 → 코드·테스트 검색으로 컨텍스트 축소 | GitHub |
| 8 | Planning | `agent` | 구현 계획 수립 + 계획 검증 | LLM |
| 9 | Coding | `agent` | Fork·브랜치·코드 수정·테스트 작성 | LLM · Sandbox |
| 10 | Verification | `agent` | 컴파일 → 유닛 → 통합 → 포맷 → diff | **Sandbox** |
| 11 | AI Review | `agent` | diff 리뷰. 실패 시 9로 회귀 | LLM |
| 12 | Draft PR | `pullrequest` | Fork 에 push → **draft** PR 생성 | GitHub |
| 13 | Human Review | — | 자동화 범위 밖 | — |

6번과 13번이 **사람의 자리**다. 코드로 우회하지 않는다 — S-6.

## 외부 경계 4개 — 능력 인터페이스

기술 이름은 `adapter/out` 에만 둔다. domain 에는 **능력 이름**으로 선언한다 (규율 ③).

| 경계 | domain 의 능력 인터페이스 | adapter/out 구현체 | 소유 도메인 | 상태 |
|---|---|---|---|---|
| GitHub — 저장소·파일 | `RepositorySource` | `GitHubRepositorySource` | `repository` | ✅ **존재** (#6) |
| GitHub — 이슈 | `IssueSource` | `GitHubIssueSource` | `issue` | ✅ **존재** (#6) |
| GitHub — 규약 문서 수집 | `PolicyDocumentSource` | `GitHubPolicyDocumentSource` | `repository` | ✅ **존재** (#7) — `RepositorySource` 위에 얹고 **예외를 `UnreadableReason` 으로 번역**한다 |
| LLM — 규약 판정 | `ContributionRuleInterpreter` | `LlmContributionRuleInterpreter` | `repository` | ✅ **존재** (#7) — `LanguageModel` 위에 얹는다 |
| GitHub — Fork·PR | `ForkRegistry` · `DraftPrPublisher` (제안) | `GitHubDraftPrPublisher` | `pullrequest` | ❌ #22 · #23 |
| LLM — 전송 (1층) | `LanguageModel` · `PromptScrubber` · `AgentRunRecorder` | `AnthropicLanguageModel`(+`RecordingLanguageModel` 데코레이터) · `TokenRedactingPromptScrubber` · `RecordAgentRunUseCase`(candidate) | `agent` | ✅ **존재** (#10) |
| LLM — 도메인 능력 (2층) | `IssueAnalyst` · `ImplementationPlanner` · `CodingAgent` · `DiffReviewer` (제안) | — | `agent` | ❌ 소비자 이슈 |
| Docker | `CodeSandbox` (제안) | `DockerCodeSandbox` | `agent` | ❌ #17 |
| PostgreSQL | Spring Data 인터페이스 (완화 ②로 직접 주입) | `adapter/out/persistence` | 각 도메인 | 부분 |

### GitHub 접근의 읽기/쓰기 분리 — S-1 을 구조로 지킨다

```
support/github/GitHubApiClient       ← 공개 메서드는 get(...) 하나. 쓰기 동사가 존재하지 않는다
        │                              RestClient 는 빈으로 노출하지 않는다 (post() 우회 차단)
        ├── repository/adapter/out/github/GitHubRepositorySource   읽기
        └── issue/adapter/out/github/GitHubIssueSource             읽기

(#22 · #23 에서 추가될 쓰기 경로는 별도 타입이고, push 직전 Fork owner 어설션을 갖는다)
```

classic PAT 은 저장소별 권한 제한이 불가능해 토큰 권한으로 원본 write 를 막을 수 없다(Q-1).
그래서 **코드 표면이 방어선**이다 — 읽기 클라이언트에 쓰기 메서드를 더하지 않는다.

자격증명은 값이 아니라 **공급자**(`GitHubCredentials`)로 주입한다. 다중 사용자 확장 경로인
GitHub App user-to-server 토큰은 단수명이라 요청마다 갱신되어야 하기 때문이다 — Q-1 「남은 것」.

**대외 호출은 전부 트랜잭션 밖이다.** 샌드박스 실행은 최대 30분(`timeout-seconds: 1800`)이라,
트랜잭션 안에 들어가면 DB 커넥션이 30분 잡힌다. 상세는 [`../rules/context/external-deps.md`](../rules/context/external-deps.md).

## 실행 프로필 — 아직 안 갈랐다

| 프로필 | 담당 | 현재 |
|---|---|---|
| `web` | REST API · 조회 | **단일 프로세스에 합쳐져 있다** |
| `worker` | 스캐너 · 코딩 · 검증 (장시간 작업) | 〃 |

가르지 않은 대가 — 코딩 1건이 API 스레드를 최대 30분 점유한다.
분리 시점 판단은 미결이다 → [`../rules/context/open-questions.md`](../rules/context/open-questions.md) **Q-3**.

잡 큐도 같은 이유로 보류다. PRD §21 은 「MVP 는 Scheduler + DB 로 시작하고, Worker 분리가 필요해지면 Redis Streams」라고 정했다.
지금 Redis 의존을 넣는 것은 이 결정을 앞서간다.

## 구현 현황

| 영역 | 상태 | 비고 |
|---|---|---|
| `repository` 등록·조회 API | ✅ | `RegisterRepositoryUseCase` |
| `repository` 스캔 요청 접수 | ⚠️ 경계만 | **요청 사실만 기록**한다. 실제 수집 없음 |
| `candidate` 상태 enum | ✅ | `CandidateStatus` 11종 |
| `candidate` 조회 API | ⚠️ 경계만 | **빈 배열 고정** |
| HTTP 예외 매핑 | ✅ | `support/web/ApiExceptionHandler` |
| `Clock` 주입 | ✅ | `config/ClockConfig` |
| **GitHub 읽기 클라이언트** | ✅ | `support/github` — 타임아웃·재시도 명시 · **403 을 권한/1차/2차 리밋으로 구분** · 레이트리밋 헤더 노출 · 자격증명 공급자 이음매. **쓰기 메서드 없음(S-1)** |
| **GitHub 능력 인터페이스** | ✅ | `RepositorySource`(repository) · `IssueSource`(issue) + 어댑터 2 + 테스트 페이크 2 |
| 시크릿 스크럽 | ⚠️ 부분 | `support/secret/TokenRedactor` — 토큰 패턴 치환만. LLM 프롬프트 단위 배제는 #28 |
| **`RepositoryPolicy` 수집·판정** | ✅ | `AnalyzeRepositoryPolicyUseCase` — 「읽었는가」 3분류(READ·ABSENT·UNREADABLE) · 확장자 변종 8경로 · 일시적 실패는 **기록 없이 중단** · 보류·금지는 **엔티티가 재분석을 거부** · `assertContributionAllowed` 단언 (#7) |
| `issue` 수집 UseCase | ❌ | 능력(`IssueSource`)은 있다. 커서·지연·멱등 저장이 없다 — #8 |
| **LLM 능력·어댑터** | ✅ | `LanguageModel`(agent/domain) + `AnthropicLanguageModel` — 송신 전 스크럽 필수(S-4) · 타임아웃·전송 재시도 명시 · 절단·거부는 예외 · **노출 빈은 기록 데코레이터 하나뿐** (#10) |
| **LLM 토큰·비용 기록** | ✅ | `AgentRunRecorder`(agent/domain) ← `RecordAgentRunUseCase`(candidate/application). 실패도 남긴다 |
| `agent` 샌드박스 | ❌ | 아직 없다 — #17 |
| `pullrequest` 도메인 | ❌ | 〃 — **쓰기 경로는 여기 생긴다** (#22 · #23). 어설션 없는 push 는 반려 |
| Scheduler | ❌ | 없음 |
| Redis 사용 | ❌ | `docker-compose.yml` 에만 존재 |
| 스키마 마이그레이션 | ✅ | **Flyway** · `ddl-auto: validate` · `db/migration/V1` (테이블 1개) |
| CI | ❌ | 유일한 게이트는 로컬 `./gradlew build` — **Q-10** |

**「경계만」을 「구현됨」으로 읽지 않는다.** 스캔 API 가 200 을 돌려준다고 이슈가 수집된 것이 아니다.

## 모듈 승격 기준

지금은 단일 Gradle 프로젝트다. 도메인이 한 워크플로우의 연속된 단계이고 스키마도 하나라,
12개 Gradle 프로젝트를 만들면 강제력보다 배선 비용이 크다.

아래 중 **둘 이상**이면 `modules/{도메인}/{api,core}` 로 승격하고 컴파일러에 강제를 넘긴다.

- [ ] 도메인 간 직접 import 위반이 리뷰에서 3회 이상 반복
- [ ] 한 도메인 변경이 다른 도메인 테스트를 반복적으로 깨뜨림
- [ ] `worker` 프로필 분리로 일부 도메인만 배포해야 함 (Q-3)
- [ ] 스키마를 도메인별로 가르기로 결정

## 변경 이력

| 일자 | 작성자 | 변경 내용 |
|------|--------|----------|
| 2026-09-18 | smileboy0014 | 초안 생성 — PRD v1.1 · 헥사고날 라이트 기준 |
