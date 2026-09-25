# 테스트 철학

## 기본 원칙

1. **Why 기반 테스트** — 「이 기능이 왜 필요한가」를 보호한다. 구현 세부가 아니라 **규칙**을 잡는다
2. **가짜로 통과시키지 않는다** — 테스트를 통과시키려고 로직을 조작하지 말고, 테스트가 표현하는 의도에 구현을 맞춘다
3. **실패 메시지가 원인을 말하게** — assertion 메시지로 원인을 추적할 수 있어야 한다
4. **빠른 피드백** — 유닛 → 통합 순으로 점진적

## 이 프로젝트의 특수 사정

**대외 의존이 셋(GitHub API · LLM API · Docker 샌드박스)이고 전부 느리거나 비결정적이다.**
이걸 그대로 타는 테스트를 만들면 테스트 스위트가 죽는다.

| 대상 | 테스트에서 | 왜 |
|---|---|---|
| GitHub API | **대체한다** — 능력 인터페이스의 페이크 구현 | 레이트리밋을 테스트가 태운다. 응답이 시점마다 다르다 |
| LLM API | **대체한다** — 고정 응답 페이크 | 비결정적이고 비싸다. 같은 입력에 같은 출력이 나오지 않는다 |
| Docker 샌드박스 | **대체한다** — 실행 결과만 주는 페이크 | 한 번에 수 분. CI 에서 Docker 를 가정할 수 없다 |
| PostgreSQL | 통합 테스트에서 실제로 (Testcontainers) | 트랜잭션 경계·동시성은 실 DB 가 아니면 검증되지 않는다 |

### 대역은 3계층이다 — Q-9 확정 (2026-09-25 · #4)

「WireMock 이냐 페이크냐」가 아니다. **층마다 보는 것이 다르다.** 하나로 통일하려 들면 어느 한쪽이 못 본다.

| 층 | 대역 | 무엇을 보나 | 어디에 |
|---|---|---|---|
| 능력 소비자 (UseCase) | **자체 페이크** | 계약이 실제로 대체 가능한가 · 업무 흐름 | 능력 인터페이스와 **같은 패키지**의 `src/test` |
| 어댑터 매핑 | **`MockRestServiceServer`** | 요청 조립 · 응답 파싱 · 오류 변환 | 어댑터와 같은 패키지 |
| **전송 계약** | **WireMock** | 읽기 타임아웃 · 리다이렉트 거부 · 연결 실패 | 어댑터와 같은 패키지 |

능력 인터페이스가 domain 에 있어 페이크를 만들기 쉬운 것이
[`architecture.md`](./architecture.md) 규율 ③(능력은 domain 이 선언)의 실질적 이득이다.
이름은 **`Fake{능력이름}`** — `FakeRepositorySource` · `FakeIssueSource`.

🔴 **전송 계약은 WireMock 이 아니면 검증되지 않는다.** `MockRestServiceServer` 는
`ClientHttpRequestFactory` 를 통째로 갈아끼우므로 **JDK `HttpClient` 가 아예 돌지 않는다.**
`read-timeout`·`Redirect.NEVER` 같은 설정이 「프로퍼티에 값이 있다」까지만 확인되고
**실제로 걸리는지는 알 수 없는 상태**가 된다 — [`external-deps.md`](../context/external-deps.md) 가
「기본값에 맡기면 무한 대기가 생긴다」를 규율로 둔 이유가 여기서 무력화된다.

소켓이 필요한 것만 WireMock 으로 올린다. **클래스당 1~2건**이면 충분하고, 세 도구가
서로 다른 것을 보므로 중복이 아니다.

| 대상 | 적용 |
|---|---|
| GitHub API | 3계층 전부 |
| LLM API | 3계층 전부 — #10 |
| **Docker 샌드박스** | **페이크만.** HTTP 가 아니라 전송 계약 층이 성립하지 않는다 |

❌ **녹화 응답(VCR)은 쓰지 않는다.** 녹화하려면 토큰으로 실 API 를 한 번은 타야 하고,
녹음본에 토큰·PII 가 섞이면 **저장소에 그대로 커밋된다** — S-4.

## 테스트 피라미드

```
       /\
      /통합\          적당히 (Testcontainers + 페이크 대외 의존)
     /------\
    /  유닛  \        많이 (상태머신 · 필터 규칙 · 정책 파싱 · 프롬프트 조립)
   /----------\
```

E2E(실제 GitHub·실제 LLM)는 **자동 스위트에 넣지 않는다.** 수동 검증 시나리오로 따로 둔다.

## 유닛 — JUnit 5 + AssertJ + Mockito

순수 판정 로직을 우선한다. 이 프로젝트에서 가장 가치 있는 유닛 테스트는 **판정과 전이**다.

```java
@Test
void 종단_상태에서는_어떤_전이도_일어나지_않는다() {
    var candidate = candidateAt(CandidateStatus.PR_CREATED);

    assertThatThrownBy(() -> candidate.startImplementing())
            .isInstanceOf(IllegalStateException.class);
}

@Test
void 활성_PR이_있는_이슈는_후보에서_제외한다() {
    var issue = issueWith(openPullRequests(1));

    assertThat(issueFilter.accepts(issue)).isFalse();
}
```

## 통합 — Testcontainers

```java
@SpringBootTest
@Testcontainers
class ScanIssuesIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    // GitHub·LLM·샌드박스는 페이크 빈으로 주입한다 — @TestConfiguration
}
```

`@SpringBootTest` 가 붙은 테스트는 **대외 의존을 실제로 타지 않는지** 먼저 확인한다.
컨텍스트에 실제 어댑터가 올라오면 테스트가 네트워크를 탄다.

## 반드시 테스트로 보호할 것

깨지면 **외부 커뮤니티에 사고가 나간다.** [`safety-boundaries.md`](../context/safety-boundaries.md) 와 1:1 대응한다.

| # | 시나리오 | 깨지면 |
|---|---|---|
| S-1 | push 대상이 Fork 가 아니면 **중단**한다 | 남의 저장소 히스토리 오염 |
| S-2 | PR 생성은 **항상 draft** 다 · 머지/ready 경로가 존재하지 않는다 | 검증 안 된 코드가 메인테이너 큐로 |
| S-3 | 대상 저장소 실행은 **전부 샌드박스 경유** · 타임아웃 시 컨테이너가 정리된다 | 호스트 장악 · 컨테이너 누수 |
| S-4 | LLM 프롬프트 조립 시 시크릿 파일 배제 + 토큰 패턴 스크럽 | 토큰이 모델 제공자 로그로 |
| S-5 | 기여 규약 파싱 실패는 **「보류」**이지 「허용」이 아니다 | 규약 위반 PR |
| S-6 | 재시도 상한 소진은 `FAILED` · 종단 상태에서 나가는 전이 없음 | 승인 지점 붕괴 |
| — | 스캔 재실행이 **중복 후보를 만들지 않는다**(멱등) | 같은 이슈에 PR 이 두 번 |
| — | GitHub 403(2차 레이트리밋)을 권한 오류와 **구분**한다 | 무한 재시도 |

**테스트 이름에 조항 코드를 넣는다.**

```java
@Test
void push_대상이_Fork가_아니면_중단한다_S1() { ... }
```

## 커버리지 목표 (초기)

| 영역 | 목표 |
|--------|------|
| 상태머신 · 필터 규칙 · 정책 파싱 | 80% |
| UseCase | 60% |
| adapter (페이크로 검증) | 50% |
| **안전 경계 6조 관련 경로** | **100% — 예외 없음** |

## 테스트 금지

- 구현 내부 필드에 의존
- 단순 getter/setter 테스트
- **실제 GitHub·LLM·Docker 를 타는 자동 테스트**
- 시크릿을 픽스처에 하드코딩 (`secret-scan.sh` 가 차단한다)

## 실행

```bash
./gradlew test                                   # 전체
./gradlew test --tests "CandidateStatusTest"     # 단건
./gradlew check                                  # 테스트 + 검증 태스크
./gradlew build                                  # check + 패키징 — 커밋 전 게이트
```

## Hook 자동 실행

[`impl-test-loop.sh`](../../scripts/impl-test-loop.sh) 가 Stop 시점에 변경분이 있으면 테스트를 돌린다.

- 연속 3회 실패 시 수동 개입 유도
- **한 건도 실행하지 않았으면 「통과」라고 하지 않는다** (`src/test` 부재·`gradlew` 부재는 사유를 표시하고 건너뜀)

**게이트는 CI 다** — Q-10 확정. `./gradlew build` 는 GitHub Actions 에서 돌고, git `pre-commit` 훅에는
초 단위 검사(`secret-scan.sh` · `safety-boundary-check.sh`)만 남는다.
Stop 훅 `impl-test-loop.sh` 는 **로컬 피드백** 담당으로 그대로 유지한다 —
[`commit-convention.md`](./commit-convention.md).
