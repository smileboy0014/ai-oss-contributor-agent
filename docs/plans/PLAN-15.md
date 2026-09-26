# PLAN-15: 저장소 분석 — 이슈 키워드로 관련 소스·테스트 좁히기

**이슈**: [#15](https://github.com/smileboy0014/ai-oss-contributor-agent/issues/15)
**type**: feature
**작성일**: 2026-09-26
**작성자**: smileboy0014

---

## 0. 이 이슈가 무엇인가

**PRD §12 의 실행체다.** 「저장소 전체를 LLM 에 넘기지 않는다」를 코드로 만든다.

`spring-kafka` 는 소스가 수천 파일이다. 통째로 프롬프트에 넣으면 비용이 성립하지 않고,
넣을 수 있다 해도 정확도가 떨어진다. 그래서 **이슈 텍스트에서 좁힌다.**

산출물은 `RepositoryContext` — **#16(구현 계획 수립)이 LLM 에 넘길 입력**이다.
이 단계 자체는 **LLM 을 부르지 않는다** (§4 결정 D-3).

---

## 1. 요구사항

### 배경

이슈 본문에는 이미 강한 신호가 들어 있다 — 클래스 이름, 스택트레이스 프레임, 파일 경로.
Java/Spring 이슈에서 이 신호는 규칙적이라 **결정적 추출로 충분**하다.
관련 파일을 고르는 일에 LLM 을 한 번 더 부르는 것은 비용과 비결정성을 둘 다 늘린다.

### 기능 요구사항 (FR)

| # | 요구사항 | 근거 | 증거 |
|---|---------|------|---|
| FR-1 | 이슈 제목·본문에서 **키워드·식별자 추출** (클래스명 · 스택트레이스 프레임 · 파일 경로 · 패키지) | 이슈 완료조건 1 | Stage 2 유닛 |
| FR-2 | 대상 저장소의 **파일 트리를 1회 조회**하고 경로를 점수화해 후보를 고른다 | 이슈 완료조건 2 · D-1 | Stage 1·3 |
| FR-3 | **관련 테스트를 짝으로 끌어온다** — `Foo.java` 를 골랐으면 `FooTests.java` 도 | 이슈 흐름 「테스트 검색」 | Stage 3 유닛 |
| FR-4 | **컨텍스트 크기 상한** — 파일 수 · 총 문자 수 · 파일당 문자 수. 넘으면 **절단했음을 표시** | 이슈 완료조건 3 | Stage 3 유닛 |
| FR-5 | **선별 근거를 기록** — 파일마다 왜 골랐는지(사유 · 점수)를 값에 담고 로그로 남긴다 | 이슈 완료조건 4 · D-2 | Stage 3·4 |
| FR-6 | 🔴 **`SecretFilePolicy` 를 배선한다** — 선별 경로를 **읽기 전에** 배제한다 | S-4 · `SecretFilePolicy` javadoc 이 #15 를 지목 | Stage 6 유닛(물림) |
| FR-7 | **1-hop 의존성 확장** — 고른 파일의 import 중 같은 저장소 안의 것을 남은 예산만큼 채운다 | 이슈 흐름 「의존성 분석」 | Stage 3 유닛 |
| FR-8 | 페이크로 테스트 — 실패 모드(레이트리밋 · 404 · 트리 절단) 재현 | 이슈 완료조건 5 · Q-9 | Stage 6 |

### 비기능 요구사항 (NFR)

| # | 항목 | 기준 |
|---|------|------|
| NFR-1 | 레이트리밋 | 후보 1건당 **Core 예산 2 + `max-fetch-attempts`** — 메타데이터 1 + 트리 1 + 파일 읽기 시도 24 = **논리 호출 최대 26회**.<br>🔴 **전송 재시도와 곱해진다** — `github.max-retries: 2` 라 실제 상한 **26 × (1+2) = 78회**.<br>⚠️ 같은 토큰 예산을 규약 수집(#7)·이슈 스캔(#8)이 **공유**한다. `github.rate-limit-threshold` 는 호출자를 구분하지 않는다.<br>**Search API(30/min)는 쓰지 않는다** — D-1 |
| NFR-2 | 타임아웃 | 신규 없음. `github.connect-timeout`·`read-timeout` 을 그대로 탄다 |
| NFR-3 | 비용 | **LLM 토큰 0** — 이 단계는 모델을 부르지 않는다(D-3). 절감 대상은 하류(#16) 프롬프트 크기이고, 그것이 `max-total-chars` 로 상한이 선다 |
| NFR-4 | 결정성 | 같은 트리·같은 이슈면 **같은 선별 결과**가 나온다. 점수 동률은 경로 사전순으로 깬다 |

---

## 2. 게이트 판정

### 안전 경계 (S-1 ~ S-6)

| 조항 | 접촉 | 어떻게 지키는가 |
|---|---|---|
| S-1 원본 저장소 쓰기 금지 | — | **읽기만** 한다. 신설 능력 `fetchTree` 도 GET 하나다. push·fork·remote 를 다루지 않는다 |
| S-2 항상 draft · 자동 머지 금지 | — | PR·리뷰·코멘트 API 를 호출하지 않는다 |
| S-3 샌드박스 밖 실행 금지 | ✅ **접촉(결정을 요구한다)** | 이슈가 「clone 과 검색이 샌드박스 안인지 밖인지 결정해야 한다」고 물었으므로 **판정은 접촉**이다. 답은 **「둘 다 하지 않는다」** — D-1 에서 clone 안을 기각했으므로 `SandboxCommand` sealed 집합을 건드리지 않고, 호스트 실행 경로도 만들지 않는다. **이 PR 은 S-3 표면을 넓히지 않는다** (§4 체크리스트도 같은 말로 적는다) |
| S-4 시크릿 유출 금지 | 🔴 ✅ | **순서가 다른 두 방어를 둘 다 세운다** — 아래 표. 덧붙여 로그에 **경로·점수·크기만** 남기고 내용을 찍지 않는다 |
| S-5 대상 저장소 규약 우선 | 🔴 ✅ | **게이트를 세운다** — 아래. 그와 별개로 **개별 파일**의 404·배제는 건너뛰고 기록한다(fail-closed 가 아니다) |
| S-6 승인 지점 우회 금지 | — | 상태 전이를 만들지 않는다. `CandidateStatus` 를 읽지도 쓰지도 않는다. 재시도 카운터를 태우지 않는다 |

🔴 **S-4 의 배선은 이 PR 의 핵심 산출물 중 하나다.** `SecretFilePolicy` 는 #28 이 만들어 두고
**소비자가 없는 상태**였고, 그 javadoc 이 「계약은 #15 가 확정한다」라고 적어 두었다.

#### S-4 를 두 겹으로 세운다 — 하나가 다른 하나를 대신하지 않는다

⚠️ **초안은 이 둘을 뭉갰다.** 「경로를 배제하니 내용은 안전하다」로 적었는데 **틀렸다.**
`SecretFilePolicy` javadoc 이 스스로 「**겹치는 방어가 아니라 순서가 다른 방어**」라고 적어 뒀고,
S-4 조문도 「`.env`·`*.pem` 류를 배제하**고**, 토큰 패턴을 스크럽한다」로 둘을 함께 요구한다.

| 순서 | 수단 | 막는 것 | **못 막는 것** |
|---|---|---|---|
| ① 열기 전 | `SecretFilePolicy.isSecretPath` | 키 파일·`.env` 를 **애초에 열지 않는다** | 🔴 `src/main/java/SomeConfig.java` 에 **하드코딩된 토큰** — 경로 정책을 정상 통과한다 |
| ② 값이 될 때 | `SelectedFile` compact 생성자의 `TokenRedactor.redact` | 열어 버린 파일 **내용의 알려진 패턴** | 알려지지 않은 형식의 자격증명 |

그래서 `ExternalTextScrubRegistryTest` 등록은 **`VALUE_TYPE`** 이다 — `IssueAnalysis.summary` ·
`ScrubbedRules.value` 와 같은 수법으로, `String` 을 그대로 받는 생성 경로를 열지 않는다.

⚠️ **등록표 `where` 문구에 과대 주장을 적지 않는다.** `VALUE_TYPE` 이 보장하는 것은
**「스크럽을 거치지 않은 값이 들어갈 수 없다」**이지 「내용이 깨끗하다」가 아니다 —
`TokenRedactor` 는 알려진 패턴만 가린다. `ScrubbedRules.value` 행 문구가 그 선을 지킨다.

#### S-5 게이트를 이 UseCase 가 직접 세운다 (검토 중대 지적 반영)

⚠️ 초안은 「규약 판정은 #7 이 끝냈다」로 넘겼는데 **불충분했다.** #7 이 판정을 *만드는* 것과
이 단계가 그 판정을 *지키는* 것은 다른 일이다. 바로 앞 단계인 `AnalyzeIssuesUseCase`(#11)가
배치 진입부에서 `assertContributionAllowed(repositoryId)` 를 **다시 부른다.**

이 단계는 #11 보다 더 나아가 **대상 저장소의 실제 코드를 LLM 입력으로 만들기 시작**하므로
면제될 이유가 없다. `BuildRepositoryContextUseCase.build` 의 **첫 줄**이 그 단언이다.

| | |
|---|---|
| 게이트 위치 | `BuildRepositoryContextUseCase.build` 진입부 |
| 무엇을 막나 | 금지(`FALSE`) **그리고 보류(`NULL`)** — 「아직 판정 안 됨이니 일단 해 두자」로 풀면 게이트가 무의미해진다 |
| 증거 | `기여가_허용되지_않은_저장소는_파일을_한_건도_읽지_않는다_S5()` — 트리 조회조차 없음을 단언 |

같은 도메인(`repository/application`)의 UseCase 를 부르는 것이라 규율 ④에도 걸리지 않는다.

#### 🔴 대상 저장소 문자열이 URL 로 들어가는 첫 자리다 (검토 지적 반영)

트리가 준 경로가 `fetchFile` 의 URL 경로로 그대로 들어간다. #7 은 **우리가 정한 고정 경로**만
읽어 이 문제가 없었고, `GitHubRequest` 의 검증은 「`/` 로 시작하는가」뿐이다.

트리를 준 것이 GitHub 자신이라 위험은 낮다. 그러나 **「낮다」에 기대지 않는다** —
S-1 이 「없는 권한에 기대지 않는다」로 세워 둔 것과 같은 태도다.
`RepositoryPathPolicy.isSafe` 가 세그먼트 단위로 `..`·빈 세그먼트·절대경로·스킴·제어문자를 막는다.

⚠️ 문자열 검사(`path.contains("..")`)가 아니라 **세그먼트 검사**다 — 문자열 검사는
`foo..bar` 를 무고하게 막고 인코딩된 형태를 놓친다.

### 미결 대조 (Q-1 ~ Q-11)

| 항목 | 걸리는가 | 처리 |
|---|---|---|
| Q-1 GitHub 연동 방식 | — | 확정됐다. 기존 `GitHubApiClient`(`RestClient`)를 그대로 쓴다. 새 클라이언트를 만들지 않는다 |
| Q-2 마이그레이션 | — | **스키마를 건드리지 않는다**(D-2). 마이그레이션 파일 없음 |
| Q-3 실행 프로필 | — | UseCase 하나를 더하는 것뿐이다. 스케줄러·워커 진입점을 만들지 않는다 |
| Q-4 샌드박스 네트워크 | — | **D-1 에서 clone 안을 기각해 이 항목을 건드리지 않게 됐다.** 기각하지 않았다면 정면으로 걸렸다 |
| Q-6 재시도 상한 | — | 파이프라인 카운터(`attempt`)를 태우지 않는다. 전송 재시도는 `github.max-retries` 가 이미 흡수한다 |
| Q-5 승인 지점 UI | — | 엔드포인트를 만들지 않는다. 호출자는 #16(프로그램 내부)이다 |
| Q-7 Lombok | — | 엔티티를 추가·변경하지 않는다. 신규 타입은 전부 `record` 다 |
| Q-8 AI 기여 금지 판정 | ✅ | 판정 자체는 #7 이 끝냈다. 이 PR 은 그 판정을 **게이트로 쓴다**(§2 S-5) — `UNDETERMINED`(보류)도 금지와 같이 막는다 |
| Q-9 테스트 대역 | ✅ | 3계층 중 **두 층**을 쓴다 — 능력 소비자는 `FakeRepositorySource`(확장), 어댑터 매핑은 `MockRestServiceServer`. **전송 계약(WireMock)은 추가하지 않는다** — 새 전송 설정이 없고 같은 `GitHubApiClient` 를 타므로 이미 덮여 있다 |
| Q-10 CI | — | CI 구성을 바꾸지 않는다. `./gradlew build` 가 그대로 게이트다 |
| Q-11 SDK vs 직접 구현 | — | 새 대외 의존을 도입하지 않는다 |

**가정** — 틀리면 어디를 고치는가.

1. **이슈 텍스트의 식별자 신호가 Java/Spring 이슈에서 충분히 규칙적이다.** 틀리면 `ContextKeywords` 의
   추출 규칙을 넓히거나, D-3 을 뒤집어 키워드 추출에 LLM 을 얹는다(`LlmCallSite` 추가가 필요하다).
2. **기본 상한(파일 12 · 총 120,000자)이 `spring-kafka` 급 저장소에 충분하다.** 실측 0건에서 정한
   추정값이라 전부 설정(`agent.context.*`)으로 뺐다. 틀리면 값만 바꾼다 — 코드를 고치지 않는다.
3. **기본 브랜치 이름으로 트리와 파일을 읽어도 된다**(커밋 SHA 로 고정하지 않는다). §7 R-2 참조.

---

## 3. 스코프

| 도메인 | 변경 | 소유 판정 근거 |
|---|---|---|
| `repository` | **신규 + 능력 확장** | 「대상 저장소의 파일이 무엇이고 그중 무엇이 관련 있는가」는 **대상 저장소에 대한 지식**이다. 읽는 능력(`RepositorySource`)이 이미 여기 있고, 트리 조회는 그 능력의 연장이다 |
| `issue` | — | 입력으로 `AnalyzableIssue`(값)를 **import 만** 한다. 엔티티·Spring Data 를 건드리지 않는다 — 규율 ④ |
| `candidate` | — | **이 PR 에서는 변경 없다.** 소비자 배선은 #16 의 몫이다 |
| `agent` | — | LLM·샌드박스를 부르지 않는다 (D-3 · S-3) |

⚠️ 이슈 라벨은 `domain:candidate`·`domain:agent` 지만 **소유는 `repository`** 로 판정했다.
라벨은 「파이프라인 어느 단계인가」를 가리키고, 소유는 **「무엇에 대한 지식인가」**로 가른다 —
`architecture.md` 규율 ④의 「누가 만드는가가 아니라 무엇과 함께 서야 하는가」.
#16(candidate)은 `repository` 의 **UseCase 를 호출**하므로 규율 ④를 지킨다.

### 🔴 계약 표면 변경 — `RepositorySource` 에 메서드 1개 추가

```java
// com.ossagent.repository.domain.RepositorySource  (기존 인터페이스)
RepositoryTree fetchTree(RepositoryCoordinates coordinates, String ref);
```

- **구현체 2개가 함께 바뀐다** — `GitHubRepositorySource`(운영) · `FakeRepositorySource`(테스트)
- 별도 능력(`RepositoryTreeSource`)으로 가르지 않은 이유 — `RepositorySource` javadoc 이
  `fetchFile` 의 `ref` 파라미터를 두면서 **「이슈 #15 저장소 분석이 이 시그니처를 다시 열지
  않도록 지금 둔다」**고 적어 뒀다. 같은 능력의 연장이라고 이미 판단해 둔 자리다
- **실패 계약은 `fetchFile` 과 같다** — 404 는 예외(트리가 없는 저장소는 없다), 권한·레이트리밋은 전파

---

## 4. 기술 설계

### 결정 3건

#### D-1. 검색은 **Git Trees API + Contents** 다 (사용자 확정 · 2026-09-26)

이슈가 「방식 결정 필요」로 남긴 항목이다. 세 안을 비교했다.

| 안 | 예산 | S-3 표면 | 선행 순환 | 판정 |
|---|---|---|---|---|
| **Trees + Contents** | Core 5,000/h 에서 1+N | 넓히지 않음 | 없음 | ✅ **채택** |
| Code Search API 병용 | **Search 30/min 별도 버킷** | 넓히지 않음 | 없음 | 기각 — 버킷별 리밋 판정을 새로 만들어야 하고, 인덱싱 지연·기본 브랜치 한정 제약이 붙는다 |
| 샌드박스 clone 후 grep | — | 🔴 **sealed 집합 확장** | 🔴 **있다** | 기각 — 아래 |

clone 안을 기각한 결정적 근거는 **순환**이다. 워크스페이스를 채우는 주체는 **#18**(코딩)이고
#18 → #16 → #15 다. #15 가 clone 에 기대면 선행 관계가 뒤집힌다.
덧붙여 `SandboxCommand` 는 sealed 3종이고 `WarmCommand.argv()` 는
`["./gradlew","--no-daemon","testClasses"]` 로 **고정**이라 clone 을 표현할 수 없다 —
넣으려면 「네트워크 개방 + 새 명령」을 sealed 집합에 더해야 하고 그것이 정확히 S-3 이 막는 모양이다.
호스트 clone 은 `safety-boundary-check.sh` 의 `ProcessBuilder`·`Runtime.exec` 검사에 걸린다.

⚠️ **잃는 것을 적어 둔다** — 경로·식별자 매칭이지 **내용 매칭이 아니다.** 이슈가 클래스 이름도
경로도 스택트레이스도 적지 않았다면(순수 산문 기능 요청) 후보가 빈약해진다.
그때의 보강 경로는 **Code Search 병용**이고, 버킷 분리가 그 PR 의 일이다.

#### D-2. 선별 근거는 **값 + 로그**에 남긴다. 스키마를 건드리지 않는다 (사용자 확정)

`RepositoryContext` 가 파일마다 사유·점수를 들고 #16 으로 넘어가고, `INFO` 로그에
**경로·점수·절단 여부**를 남긴다. 마이그레이션이 없어 PR 이 작고, 소비자(#16)가 생길 때
실제 필요를 보고 컬럼을 정할 수 있다.

⚠️ 로그에 **내용을 싣지 않는다** — 대상 저장소가 시크릿을 커밋해 뒀을 수 있다(`logging.md`).

#### D-3. 이 단계는 **LLM 을 부르지 않는다**

키워드 추출을 결정적으로 한다. 근거 셋 —

1. Java 이슈의 식별자 신호(CamelCase · 스택트레이스 · 경로)가 규칙적이라 정규식으로 잡힌다
2. 제품의 품질 축은 「나쁜 결과를 걸러내는가」(PRD §30)이고, **결정적 단계는 테스트로 고정된다**
3. `LlmCallSite` 는 PRD §6.1 이 정한 4개(+`POLICY`)이고 **저장소 분석은 거기 없다** — 지금
   6번째를 만드는 것은 PRD 를 앞서간다

### 변경 파일

| # | 경로 | 레이어 | 신규/수정 | 내용 |
|---|------|--------|----------|------|
| 1 | `repository/domain/RepositorySource.java` | domain | **수정** | `fetchTree` 추가 — 🔴 계약 표면 |
| 2 | `repository/domain/RepositoryTree.java` | domain | 신규 | `sha` · `entries` · `truncated`(GitHub 이 트리를 잘랐는가) |
| 3 | `repository/domain/RepositoryTreeEntry.java` | domain | 신규 | `path` · `type` · `size`. 🔴 종류가 **3개**다 — `BLOB`·`TREE`·`OTHER`. 서브모듈(`commit`)과 **심볼릭링크**를 blob 으로 세면 읽을 수 없는 경로가 후보에 올라 예산을 태운다. ⚠️ 심볼릭링크는 `type` 이 `blob` 이라 **`mode`(`120000`)로만 갈린다** |
| 4 | `repository/domain/ContextKeyword.java` | domain | 신규 | `value` · `kind`(PATH_LITERAL·TYPE_NAME·PACKAGE·TERM) |
| 5 | `repository/domain/ContextKeywords.java` | domain | 신규 | **순수** — 이슈 텍스트 → 키워드 집합. 종류별 가중치 |
| 6 | `repository/domain/SelectionReason.java` | domain | 신규 | enum — `PATH_LITERAL`·`TYPE_NAME`·`PACKAGE`·`TERM`·`TEST_PAIR`·`IMPORT_NEIGHBOR` |
| 7 | `repository/domain/RelevanceScorer.java` | domain | 신규 | **순수** — (엔트리 × 키워드) → 점수 + 사유 |
| 8 | `repository/domain/SelectedFile.java` | domain | 신규 | `path`·`reason`·`score`·`content`. 🔴 `@ExternalText(TARGET_REPOSITORY)` |
| 9 | `repository/domain/ContextBudget.java` | domain | 신규 | 상한·사용량·절단 여부. **넘으면 조용히 버리지 않고 표시한다** |
| 10 | `repository/domain/ExcludedPathReason.java` | domain | 신규 | enum — `SECRET_PATH`·`NOT_SOURCE`·`TOO_LARGE`·`NOT_FOUND`·**`UNREADABLE`**·`BUDGET_EXHAUSTED`.<br>🔴 `NOT_FOUND`(없다)와 `UNREADABLE`(있는데 못 읽었다)을 가른다 — 1MB 초과·디렉터리·심볼릭링크가 후자다 |
| 10b | `repository/domain/RepositoryPathPolicy.java` | domain | 신규 | 🔴 대상 저장소 경로가 우리 URL 에 들어가도 되는가 + 소스인가. 계획에 없던 것을 **검토 지적으로 추가**했다 |
| 11 | `repository/domain/RepositoryContext.java` | domain | 신규 | 산출물. `coordinates`·`ref`·`treeSha`·`files`·`budget`·`treeTruncated`·`skipped` |
| 12 | `repository/application/RepositoryContextProperties.java` | application | 신규 | `agent.context.*` |
| 13 | `repository/application/BuildRepositoryContextUseCase.java` | application | 신규 | 조율. 🔴 **`@Transactional` 없음** — 전부 대외 호출 |
| 14 | `repository/adapter/out/github/GitHubRepositorySource.java` | adapter/out | **수정** | `fetchTree` 구현 — `/repos/{o}/{r}/git/trees/{ref}?recursive=1` 파싱 |
| 14b | `config/RepositoryContextConfig.java` | config | 신규 | `agent.context.*` 바인딩. ⚠️ `@ExternalAdapter` 를 붙이지 **않는다** — 설정 레코드뿐이고 대역 컨텍스트에서도 상한이 필요하다 |
| 15 | `src/main/resources/application.yml` | 설정 | 수정 | `agent.context.*` 기본값 |
| 16 | `src/test/.../repository/domain/FakeRepositorySource.java` | test | **수정** | 트리 대역 + **경로별** 실패 주입(`failFileWith`) + `reset()`.<br>전역 `failWith` 로는 「파일 하나만 실패」를 재현할 수 없다 |
| 17 | `src/test/.../support/ExternalTextScrubRegistryTest.java` | test | 수정 | `SelectedFile.content` 를 **`VALUE_TYPE`** 으로 등록 |
| 18 | `src/test/.../GitHubPolicyDocumentSourceTest.java` | test | 수정 | `PerPathSource` 스텁에 `fetchTree` 추가 — 🔴 빈 트리가 아니라 `UnsupportedOperationException` 이다. 규약 수집이 트리를 부르기 시작하면 **드러나야** 한다 |
| 19 | `.claude/codemaps/architecture.md` · `rules/context/glossary.md` | 문서 | 수정 | 능력 표에 `fetchTree` · 용어에 「저장소 컨텍스트」 계열 추가 |

### 체크리스트 답변

| # | 항목 | 답 |
|---|------|---|
| 1 | 소유 도메인 | `repository` — §3 판정 근거 |
| 2 | 레이어 배치 | 순수 판정(키워드·점수·예산)은 **domain**, 대외 호출 조율은 **application**, 트리 파싱은 **adapter/out**. Q1→domain / Q2→UseCase / Q5→adapter |
| 3 | 능력 인터페이스 | **기존 `RepositorySource` 를 확장**한다. 새 능력을 만들지 않는다 — §3 |
| 4 | 🔴 트랜잭션 안 대외 호출 없음 | ✅ UseCase 에 `@Transactional` 을 **달지 않는다.** DB 를 아예 읽고 쓰지 않는다(D-2) — 트랜잭션이 없으므로 섞일 자리가 없다 |
| 5 | 상태 전이 영향 | **없다.** `CandidateStatus` 를 읽지도 쓰지도 않는다 |
| 6 | 멱등성 | ✅ 순수 읽기다. 부작용이 없고, 같은 트리면 같은 결과다(NFR-4 — 동률은 경로 사전순) |
| 7 | `Clock` 주입 | **해당 없음** — 시각으로 판단하는 것이 없다. 대외 호출 레이턴시는 `GitHubApiClient` 가 이미 남긴다 |
| 8 | 🔴 안전 경계 | §2 — S-3 미접촉(표면 안 넓힘) · **S-4 접촉(배선이 산출물)** |

### 데이터 모델

**해당 없음 — 마이그레이션 없음.** D-2 로 영속화하지 않기로 했다.
`RepositoryContext` 는 프로세스 안의 값이고 #16 으로 전달된다.

### API 계약

**해당 없음** — 이 PR 은 HTTP 진입점을 만들지 않는다. 호출자는 #16(프로그램 내부)이다.

### 설정 (`agent.context.*`)

| 키 | 기본 | 근거 |
|---|---|---|
| `max-files` | 12 | 계획 단계가 볼 파일 수. 실측 0건의 추정값 |
| `max-fetch-attempts` | 24 | 🔴 **`max-files` 와 다른 축이다.** 파일 예산은 **성공한 선별만** 센다 — 실패한 읽기(404·1MB 초과·심볼릭링크)는 예산을 쓰지 않으므로, 이 상한이 없으면 실패가 계속될 때 **후보 전량을 두드린다.** 안전 리뷰가 잡아낸 구멍이고, 내가 NFR-1 에 「최대 14회」라고 적은 것이 그래서 **틀렸었다** |
| `max-total-chars` | 120000 | 하류(#16) 프롬프트 예산의 대부분을 여기 쓴다 |
| `max-file-chars` | 40000 | 파일 하나가 예산을 독식하지 않게. 넘으면 **파일을 통째로 버린다** — 잘린 소스는 모델을 헷갈리게 한다 |
| `max-keywords` | 40 | 키워드가 수백 개가 되면 점수가 평평해져 변별력이 사라진다 |
| `import-expansion` | true | FR-7 토글 |

⚠️ **환경변수로 빼지 않는다** — `agent.analysis.*` 와 같은 결이다. `.env.example` 변경 없음.

---

## 5. 구현 순서

### 실행 모드: **sequential**

**판정 근거** — Stage 1 이 만드는 계약(`RepositoryTree`)을 Stage 3~5 가 전부 import 한다.
유형은 `contract-and-impl` 이다. 파일 겹침이 아니라 **import 의존 방향** 때문에 병렬이 성립하지 않는다.

| Stage | 내용 | 선행 | 파일 |
|-------|------|------|------|
| 1 | 트리 계약 — `RepositoryTree`·`RepositoryTreeEntry` + `RepositorySource.fetchTree` | 없음 | 1·2·3 |
| 2 | 키워드 추출 — `ContextKeyword(s)` (순수) | 없음 | 4·5 |
| 3 | 선별 — `SelectionReason`·`RelevanceScorer`·`SelectedFile`·`ContextBudget`·`ExcludedPathReason`·`RepositoryContext` | 1·2 | 6~11 |
| 4 | 조율 — `BuildRepositoryContextUseCase` + 설정 | 3 | 12·13·15 |
| 5 | 어댑터 — `GitHubRepositorySource.fetchTree` 구현 | 1 | 14 |
| 6 | 대역·가드 — `FakeRepositorySource` 확장 · 스크럽 등록 · 테스트 전량 | 1~5 | 16·17 + 테스트 |

---

## 6. 테스트 계획

| # | 레벨 | 대상 | 검증 내용 |
|---|------|------|----------|
| 1 | 유닛 | `ContextKeywords` | 스택트레이스 프레임 · 경로 리터럴 · CamelCase 타입명 · 백틱 토큰을 뽑는가 · 불용어를 버리는가 · `max-keywords` 절단 |
| 2 | 유닛 | `RelevanceScorer` | 경로 리터럴 > 타입명 > 패키지 > 낱말 순서 · **동률은 경로 사전순**(NFR-4) |
| 3 | 유닛 | 테스트 짝 | `Foo.java` 를 고르면 `FooTests.java`·`FooTest.java` 가 따라온다 (FR-3) |
| 4 | 유닛 | `ContextBudget` | 상한 초과가 **조용히 버려지지 않고** `truncated` 로 드러난다 (FR-4) |
| 5 | 🔴 유닛 | **S-4 배선** | 트리에 `.env`·`id_rsa`·`credentials.json` 을 심어 두고 **fetch 자체가 일어나지 않음**을 `FakeRepositorySource` 의 호출 기록으로 단언 + `skipped[SECRET_PATH]` 계수 |
| 6 | 유닛 | 트리 절단 | GitHub 이 `truncated: true` 를 주면 **컨텍스트에 표시되고 WARN 이 뜨되 실패하지 않는다** (§2 S-5 의 방향 판정) |
| 7 | 유닛 | 개별 파일 404 | 건너뛰고 `skipped[NOT_FOUND]` 로 기록. **전체를 실패시키지 않는다** |
| 8 | 유닛 | 레이트리밋 | `GitHubRateLimitException` 은 **그대로 전파**한다(삼키지 않는다) — 호출자가 지연으로 다룬다 |
| 9 | 유닛 | FR-7 | import 이웃이 트리에 있으면 남은 예산만큼 채우고, 예산이 없으면 채우지 않는다 |
| 10 | 어댑터 | `GitHubRepositorySource.fetchTree` | `MockRestServiceServer` — `recursive=1` 쿼리 조립 · blob/tree 구분 · `truncated` 파싱 |
| 11 | 통합 | — | **추가하지 않는다.** 새 빈이 컨텍스트에 올라오는 것은 기존 `ExternalAdapterIsolationTest`·`IntegrationTestProfileTest` 가 이미 본다 |

**대외 호출 대체** (Q-9) — 능력 소비자 층은 `FakeRepositorySource`(확장), 어댑터 매핑 층은
`MockRestServiceServer`. **전송 계약 층(WireMock)은 추가하지 않는다** — 새 전송 설정이 없고
같은 `GitHubApiClient` 를 타므로 기존 WireMock 테스트가 그 층을 이미 덮는다.

### 🔴 가드는 「있다」가 아니라 「문다」를 확인한다

`testing-philosophy.md` 가 신설한 조항이다. 이 PR 의 S-4 가드(테스트 5)에 대해
**돌연변이 검증**을 돌리고 **「무엇을 빼니 몇 건이 빨개졌다」를 PR 본문에 숫자로 적는다.**

**실행 결과 (2026-09-26)** — 「돌려 봤다」가 아니라 숫자를 적는다.

| 제거한 것 | 결과 |
|---|---|
| `BuildRepositoryContextUseCase` 의 `SecretFilePolicy.isSecretPath` 호출 | **14건 중 1건 빨강** (`시크릿_경로는_읽지_않고_배제한다_S4`) |
| `SelectedFile` compact 생성자의 `TokenRedactor.redact` | **14건 중 1건 빨강** (`선별된_파일의_내용은_스크럽을_거친다_S4`) |
| 둘 다 복원 후 | 14건 초록 |

🔴 **두 가드가 서로를 대신하지 않는다는 것이 이 표의 요점이다.** 하나를 빼면 다른 하나가
메워 줄 것 같지만, **각각 다른 테스트 1건씩**이 빨개진다 — 막는 것이 다르기 때문이다.

**샘플의 대표성**도 함께 봤다 — 경로 배제 샘플은 `SecretFilePolicy` 에 **실제로 물리는 모양**
(`.env` · `id_rsa` · `credentials.json`)이고, 내용 스크럽 샘플은 **경로 정책을 정상 통과하는**
소스 파일(`.../KafkaMessageListenerContainer.java`) 안에 토큰을 심었다 —
두 번째가 특히 중요하다. 경로 정책에 걸리는 파일에 토큰을 심었다면 첫 번째 가드가 가려 버려
**스크럽 가드가 아무것도 증명하지 못했을 것**이다.

---

### 🕳 이 PR 이 **막지 못하는** 것 — 우회를 먼저 적는다

`testing-philosophy.md` 는 「한계는 오탐이 아니라 **우회**를 적는다」고 못 박는다.
조용히 새는 쪽을 목록 맨 앞에 둔다.

| # | 조용히 통과하는 것 | 왜 |
|---|---|---|
| 1 | 🔴 **`repository` 패키지의 새 로그에 본문이 실려도 자동으로 잡히지 않는다** | `PromptBoundaryTest` 의 검사 대상이 `MAIN.resolve("agent")` 로 **한정**돼 있다. 이 PR 의 INFO 로그는 `repository/application` 에 생기므로 그 사각지대다 |
| 2 | `TokenRedactor` 가 **모르는 형식**의 자격증명 | 알려진 패턴만 가린다. `SelectedFile` 이 보장하는 것은 「스크럽을 거쳤다」이지 「깨끗하다」가 아니다 |
| 3 | `SecretFilePolicy` **목록에 없는** 이름의 키 파일 | 경로 목록 방어의 원리적 한계 |
| 4 | 트리가 잘렸을 때 **못 본 경로** | 표시는 하지만 못 본 것은 못 본 것이다 |

**1번을 이 PR 에서 고치지 않은 이유** — `PromptBoundaryTest` 의 javadoc 이 「오탐이 잦다고
이 테스트를 지우지 않는다 — **대상을 더 좁힌다**」로 설계 의도를 명시해 뒀다. 그 의도와
반대로 범위를 넓히는 것은 그 테스트를 소유한 이슈(#28)의 판단 영역이고, 여기서 넓혔다가
오탐이 나면 「#15 때문에 가드가 느슨해졌다」가 된다.
**대신 이 PR 의 로그는 전부 스칼라다** — 경로·개수·문자 수·불리언만 찍고 내용은 찍지 않으며,
`SelectedFile.toString()`·`RepositoryContext.toString()`·`RepositoryTree.toString()` 이
각각 본문·경로 목록을 뺀다. 그 세 개는 테스트가 있다. **자동 가드가 아니라 리뷰가 보는 자리**다.

### 🔴 레이트리밋을 「지연」으로 번역할 주체가 이 PR 에 없다

`GitHubRateLimitException` 을 **전파**하는 것까지가 이 PR 이고, 그것을 「실패」가 아니라
**「지연」**으로 다루는 것은 호출자(#16)의 몫이다. 용어 사전이 「리밋에 걸렸다를 실패라고
쓰지 않는다」고 못 박았으므로, 첫 소비자가 이것을 그냥 `FAILED` 로 받으면 **그 번역이 위반**이다.

이 PR 에 소비자가 없어 여기서 강제할 수 없다. **#16 에 인계한다** — §9 에 적었다.

---

## 7. 리스크

| # | 리스크 | 영향 | 대응 |
|---|--------|------|------|
| R-1 | **산문형 이슈에서 후보가 빈약하다** — 경로·식별자 매칭이라 신호가 없으면 못 좁힌다 | 계획(#16) 품질 저하 | D-1 의 「잃는 것」에 명시. `RepositoryContext` 가 **선별 0건임을 값으로 드러내**므로 #16 이 그 사실을 보고 판단할 수 있다 |
| R-2 | **트리와 파일 사이에 기본 브랜치가 움직인다** | 고른 파일이 트리에서 본 것과 미세하게 다르다 | 커밋 SHA 고정은 호출 1회(`/commits/{branch}`)를 더 쓴다. 레이스 창이 좁고 결과가 되돌릴 수 있는 종류라 **지금 고정하지 않는다.** `treeSha` 를 컨텍스트에 남겨 추적은 가능하게 한다. 정확한 고정이 실제로 필요해지는 곳은 clone 하는 **#18** 이다 |
| R-3 | **거대 저장소에서 트리가 잘린다**(GitHub `truncated`) | 관련 파일을 놓칠 수 있다 | 실패시키지 않고 **표시 + WARN**(테스트 6). 방향 판정 근거는 §2 S-5 |
| R-4 | 계약 표면 변경이 구현체 2개를 건드린다 | 컴파일 깨짐 | 인터페이스에 메서드를 더하는 것이라 **컴파일러가 전부 잡는다.** 조용히 새는 종류가 아니다 |
| R-5 | 상한 기본값이 실측 0건의 추정이다 | 프롬프트가 크거나 작다 | 전부 설정으로 뺐다(가정 2). 코드 변경 없이 조정된다 |

**대외 호출 실패 시나리오**

| 상황 | 동작 |
|---|---|
| 1차 레이트리밋(`Remaining` < 임계) | `GitHubRateLimitException` 전파 → 호출자가 **지연**으로 다룬다. 이 UseCase 는 삼키지 않는다 |
| 2차 레이트리밋(403 + `Retry-After`) | 같음. 구분은 `GitHubErrorTranslator` 가 이미 한다 |
| 권한 오류(신호 없는 403) | 전파 — 진짜 실패다 |
| 트리 조회 404 | **전파한다.** 트리가 없는 저장소는 없으므로 「없음」이 아니라 이상 상황이다 |
| 개별 파일 404 | 건너뛰고 기록(테스트 7) |
| 읽기 타임아웃 | `GitHubApiClient` 의 기존 번역·재시도(`github.max-retries`)를 탄다. **`CancellationException` 번역도 그쪽에 이미 있다** |

**비용** — LLM 호출 0. 하류 프롬프트 크기가 `max-total-chars` 로 상한이 선다.

---

## 8. 복잡도

| Stage | 파일 수 | 복잡도 |
|-------|--------|--------|
| 1 트리 계약 | 3 | 낮음 |
| 2 키워드 추출 | 2 | **중간** — 정규식과 불용어 |
| 3 선별 | 6 | **높음** — 점수·예산·배제가 겹친다. 이 PR 의 본체 |
| 4 조율 | 3 | 중간 |
| 5 어댑터 | 1 | 낮음 |
| 6 대역·가드 | 2 + 테스트 | 중간 |

---

## 9. 이 PR 이 **하지 않는** 것

경계를 적어 두지 않으면 다음 세션이 「왜 안 했지」로 되돌아온다.

- **#16 배선** — `RepositoryContext` 를 프롬프트로 만드는 것은 #16 이다
- **영속화** — D-2. 컬럼도 테이블도 만들지 않는다
- **Code Search API** — D-1 에서 기각. 필요해지면 버킷 분리와 함께 별도 이슈
- **clone · 샌드박스** — D-1. `SandboxCommand` sealed 집합을 건드리지 않는다
- **LLM 키워드 추출** — D-3
