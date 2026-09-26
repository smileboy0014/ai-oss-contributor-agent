# PLAN-25: Observability — 파이프라인 메트릭 수집

- 이슈: [#25](https://github.com/smileboy0014/ai-oss-contributor-agent/issues/25)
- 브랜치: `feature/25_observability`
- 근거: PRD §26 · `.claude/rules/conventions/logging.md`
- 선행: **없음** (이슈에 선행 항목이 비어 있다)

---

## 1. 요구사항

### 배경

PRD §26 이 요구하는 것은 「파이프라인이 **어디서 얼마나 떨어지는지** 보이게 한다」다.
지금은 `actuator` 가 있고 `health,info` 만 노출한다. **메트릭이 하나도 없다.**

특히 급한 것 하나 — **LLM 토큰·비용이 보이지 않는다.** `external-deps.md` 가
「비용이 보이지 않으면 재시도 루프가 조용히 돈을 태운다」고 적어 뒀다.

⚠️ 「#14 가 자동 경로를 만들었으니 급하다」로 과장하지 않는다 — 그 스케줄러는
**기본이 꺼짐**(`scan.schedule.enabled: false`)이다. 지금 토큰을 태우는 것은 사람이
부르는 API 뿐이다. 그래도 필요한 이유는 **켜기 전에 계측이 있어야** 하기 때문이다.
켜고 나서 만들면 첫 청구서를 보고 만들게 된다.

### 🔴 착수하며 확인한 것 — MDC 가 로그에 실리지 않는다

`logging.md` 는 이렇게 요구한다.

> 로그 포맷에 `%X{candidateId} %X{stage} %X{attempt}` 를 포함시킨다.

그런데 **`logback-spring.xml` 도 `logging.pattern` 설정도 없다.** 코드는 MDC 를 성실히
채우고 있는데(`RecordingLanguageModel` · `AnalyzeIssuesUseCase` · `ScanExecutor`)
**출력 포맷이 그것을 버린다.** 「한 후보가 여러 단계·여러 재시도를 거치므로 식별자 없이는
로그를 이어붙일 수 없다」는 바로 그 상황이 지금이다.

### ⚠️ 이슈 완료조건의 절반은 지금 재려 할 수 없다

PRD §26 의 지표 목록 중 **측정 대상 자체가 없는 것**이 있다.

| 지표 | 지금 | 근거 |
|---|---|---|
| `candidate_count` | ✅ 잰다 | 상태별 집계 — 설계 ⑤ |
| `analysis_success_rate` | ✅ 잰다 | 🔴 **단계 타이머로는 안 나온다** — 아래 설계 ④-b |
| `llm_token_usage` · `llm_cost` | ✅ 잰다 | `AgentRun` 에 있다 |
| `retry_count` | 🟡 **조건부** | `AgentRunContext.attempt` 를 센다. ⚠️ 그것이 Q-6 의 「사이클」과 같아지는 것은 **#21 이후**다 — 아래 |
| `execution_time` (파이프라인) | ✅ 잰다 | 4단계(#14) |
| `execution_time` (샌드박스) | ❌ **이번엔 안 잰다** | ⚠️ **코드는 이미 있다**(#17 `DockerCodeSandbox`). 없는 것은 <b>호출부</b>(#18)라 실행이 0건이다 — 아래 |
| 안전 게이트 카운터 | ✅ **S-5 만** | 조항별 사유가 다르다 — §2 ③ |
| `implementation_success_rate` · `test_pass_rate` | ❌ | #18 · #19 가 없다 |
| `review_pass_rate` | ❌ | #20 이 없다 |
| `pr_created_count` | ❌ | #23 이 없다 |
| `pr_merged_count` | ❌ **그리고 만들지 않는다** | 아래 |

⚠️ **`retry_count` 를 지금 내보내는 것의 위험** — `V2` 마이그레이션이 `agent_run.attempt` 에
「의미가 아직 미정 — Q-6. 해석은 **#21 에서 확정**」이라 적어 뒀다. Q-6 자체는 닫혔지만
그 결론을 코드에 반영하는 것은 #21 이다. 지금 세는 것은 **LLM 호출의
`AgentRunContext.attempt`**(ANALYZE 는 항상 1)이고, #21 이 사이클 카운터를 넣으면
**같은 이름의 메트릭이 조용히 다른 것을 세게 된다.**
그래서 이름을 `llm_call_attempt` 로 좁혀 두고, `retry_count` 라는 이름은 #21 에게 남긴다.

**없는 단계를 위해 빈 카운터를 미리 만들지 않는다.** 0 으로 고정된 게이지는
「재고 있는데 0 이다」와 「아직 없다」를 구분하지 못해, 대시보드를 보는 사람을
**적극적으로 오도한다.** 그 단계를 만드는 이슈가 자기 메트릭을 함께 넣는다.

`pr_merged_count` 는 이슈 본문이 직접 경고한다 — 「메인테이너 사정에 좌우되므로
**제품 지표로 쓰지 않는다**」. 만들지 않는다.

### 기능 요구사항 (FR)

| # | 요구 | 출처 |
|---|---|---|
| FR-1 | Micrometer 메트릭을 actuator 로 노출한다 | 이슈 완료조건 |
| FR-2 | 🔴 **LLM 토큰·비용 누적** — 재시도 루프가 조용히 돈을 태우는 것을 막는 유일한 수단 | 이슈 완료조건 |
| FR-3 | 파이프라인 **단계별 소요 시간·결과** | 이슈 완료조건 |
| FR-4 | 🔴 **안전 게이트 통과/차단 카운터** — 막은 횟수 <b>와</b> 통과 횟수 | 이슈 완료조건 · S-5 |
| FR-5 | 🔴 **MDC 를 로그 포맷에 싣는다** | 이슈 완료조건 · `logging.md` |
| FR-6 | 후보 상태 분포 (`candidate_count`) | PRD §26 |
| FR-7 | **분석 건당 결과** (`analysis_success_rate` 의 분모·분자) | PRD §26 |

### 비기능 요구사항 (NFR)

| # | 요구 | 왜 |
|---|---|---|
| NFR-1 | 🔴 **태그 카디널리티가 유한하다** | 아래 S-4 |
| NFR-2 | 계측이 실패해도 **업무 흐름을 죽이지 않는다** | 메트릭 때문에 스캔이 죽으면 본말전도다 |
| NFR-3 | 새 대외 의존 없음 | 스크레이퍼가 없다. ⚠️ 그 대가는 **재기동 시 소실**이다 — R-7 |

---

## 2. 게이트 판정

### 안전 경계 (S-1 ~ S-6)

| 조항 | 접촉 | 어떻게 지키나 |
|---|---|---|
| **S-1** push 대상 | ❌ | push·fork 코드가 **저장소에 아직 없다**(#22). 셀 것이 없다 |
| **S-2** draft 고정 | ❌ | ⚠️ 코드가 없는 것이 아니라 **게이트가 DB `CHECK` 에 있다**(`PullRequest.Status` 단일값). 애플리케이션 카운터로 셀 수 있는 형태가 아니다 |
| **S-3** 샌드박스 | ❌ | ⚠️ **코드는 이미 있다**(#17 `DockerCodeSandbox` 외 6파일). 없는 것은 **호출부**(#18)라 실행이 0건이다 — 계측은 호출부를 만드는 이슈가 함께 넣는다 |
| **S-4** 시크릿 | 🔴 **접촉** | 아래 ① |
| **S-5** 규약 | 🟡 **관측만** | 판정을 바꾸지 않는다. **몇 번 막았는지 세기만** 한다 |
| **S-6** 승인 지점 | ❌ | 상태 전이를 부르지 않는다. 읽기만 |

#### ① 🔴 S-4 — 메트릭 태그가 새 유출면이다

이슈가 「메트릭 라벨에 토큰·저장소 파일 내용이 섞이지 않게」라고 적었는데,
**그 위험이 어떻게 생기는지**를 분명히 해 둔다.

| 태그로 쓰면 안 되는 것 | 왜 |
|---|---|
| 이슈 제목·본문·LLM 응답 | 대상 저장소 텍스트다. 토큰이 섞여 있을 수 있다 |
| 예외 **메시지** | 요청 URL·모델 응답이 실려 온다 — #14 에서 같은 이유로 막았다 |
| `candidateId` · `issueId` | 시크릿은 아니나 **카디널리티가 무한**이다 |

🔴 **카디널리티가 S-4 와 같은 방향이다.** 무한 카디널리티 태그는 메트릭 저장소를
터뜨릴 뿐 아니라, **식별자를 외부 모니터링 시스템으로 계속 밀어낸다.**
둘 다 「우리가 통제하는 유한한 어휘만 태그로 쓴다」로 막힌다.

**태그는 enum 과 boolean 에서만 온다** — `stage`·`callSite`·`outcome`·`reason`·`status`.
문자열을 받는 태그 설정 지점을 만들지 않는다.

⚠️ `repositoryId` 도 태그로 쓰지 않는다. Phase 1 은 저장소 1개지만 Phase 3 은 7개이고,
그 뒤는 「동적 등록」(PRD)이라 상한이 없다.

#### ② 🔴 S-4 — **엔드포인트 노출면**. 태그만 보는 것으로는 부족하다

계획 초안이 「태그에 무엇이 들어가는가」만 보고 **「누가 이 엔드포인트를 읽을 수 있는가」**를
한 줄도 다루지 않았다. 사실부터 적는다.

| 사실 | 확인 |
|---|---|
| `spring-boot-starter-security` 가 **없다** | `./gradlew dependencies` — 0건 |
| 따라서 `/actuator/**` 는 **인증 없이** 열린다 | 이미 `health,info` 가 그렇다 |
| 관리 포트가 분리돼 있지 않다 | `management.server.port` 미설정 → 앱 포트와 같다 |

🔴 **`metrics` 를 켜면 우리 미터만 노출되는 것이 아니다.** Boot 기본 미터
(`jvm.*`·`hikaricp.*`·`process.*`·**`http.server.requests`**)가 함께 나오고,
그중 `http.server.requests` 의 `uri` 태그는 **우리가 통제하지 않는다.**
설계 ①의 「태그를 만드는 유일한 지점」이라는 전제가 **기본 미터에는 적용되지 않는다.**

**판단** — 그래도 노출한다. 이유는 셋이다.
① 이미 `health,info` 가 같은 조건으로 열려 있어 **이 PR 이 노출면을 새로 여는 것이 아니다**
② 우리 미터의 태그는 enum 뿐이라 시크릿이 없다
③ 계측을 만들고 안 보여주면 이 이슈가 하는 일이 없다

**대신 두 가지를 남긴다.**
- 🔴 `management.server.port` 분리와 인증은 **배포 시 결정**이라고 문서에 못 박는다.
  `scan.schedule.enabled` 와 같은 논리다 — 「켜는 것은 배포 결정」
- ⚠️ 가드 테스트의 **범위를 우리 미터로 한정**한다. 「등록된 모든 미터」를 훑으면
  Boot 기본 미터 때문에 **첫 실행에서 무너지거나, 예외 목록을 두느라 가드가 헐거워진다.**
  기본 미터는 별도로 「우리 식별자를 태그로 갖지 않는다」만 확인한다

#### ③ S-5 — 판정을 **보기만** 한다

게이트 자체(`assertContributionAllowed`)를 건드리지 않는다.

🔴 **통과도 센다.** `logging.md` 가 「안전 게이트 … **통과한 것도 남긴다.** 사고 후
「막았는가」를 증명할 수 있어야 한다」고 요구하고, 이슈 완료조건도 「**통과/차단**」이다.
초안은 차단만 세도록 설계했는데 그러면 **분모가 없어 「막힌 비율」을 계산할 수 없고**,
계측이 죽었을 때(NFR-2 가 삼킬 때) **「0건 차단」과 「계측 고장」이 구분되지 않는다.**

게이트가 한 곳(`assertContributionAllowed`)이라 통과 경로를 세는 비용이 사실상 0이다.

### 미결 대조 (Q-1 ~ Q-11)

| 항목 | 걸리나 | 처리 |
|---|---|---|
| **Q-3** 프로필 분리 | 🟡 스침 | 메트릭 레지스트리는 프로필과 직교한다. 다만 **인스턴스가 늘면 카운터가 인스턴스별로 갈린다** — 지금 단일 프로세스라 문제없고, 리스크로 남긴다 |
| 나머지 | ❌ | 접촉 없음 |

### ⚠️ 가정 — `llm_cost` 를 어떻게 재나

이슈가 `llm_cost` 를 요구하는데 **단가가 어디에도 없다.** `.env.example` 에도
`application.yml` 에도 모델 가격이 없고, 모델은 설정으로 바뀐다(`ANTHROPIC_MODEL`).

**A-1 — 비용을 「원화/달러」로 계산하지 않는다. 토큰을 입·출력으로 나눠 센다.**

- 단가는 모델·시점·계약에 따라 다르고, 코드에 박으면 **틀린 숫자를 자신 있게 보여준다**
- 입력/출력 토큰이 분리돼 있으면 **단가표만 있으면 언제든 곱할 수 있다** — 대시보드나
  스프레드시트에서
- 단가를 설정으로 받는 것도 가능하나, 지금 넣으면 **아무도 채우지 않는 설정**이 하나 는다

즉 `llm_cost` 는 **`llm_tokens_total{direction=input|output}` 으로 대체**한다.

🔴 **다만 이것을 「가정」으로 처리하고 넘어가지 않는다.** 완료조건에 명시된 지표를
에이전트가 단독으로 줄이는 것이기 때문이다. **후속 이슈를 이 PR 에서 연다** —
「`llm_cost` — 모델 단가표와 비용 환산」. 그래야 PRD §26 이 `llm_cost` 를 쓴 이유
(「재시도 루프가 조용히 돈을 태운다」)가 **절반만 답해진 채 잊히지 않는다.**

토큰이 입·출력으로 나뉘어 있으면 그 후속 이슈는 **곱셈 한 번**이면 된다.

---

## 3. 스코프

| 포함 | 제외 |
|---|---|
| Micrometer 계측 + actuator 노출 | **Prometheus 레지스트리** — 스크레이퍼가 없다 (NFR-3) |
| LLM 토큰(입·출력) · 호출 결과 | **비용 환산** — A-1. 🔴 **후속 이슈를 이 PR 에서 연다** |
| 파이프라인 단계 타이머 + **분석 건당 결과** | **구현·테스트·리뷰·PR·샌드박스 지표** — 호출부를 만드는 이슈가 각자 넣는다 |
| S-5 게이트 **통과/차단** 카운터 | **S-1·S-2·S-3 카운터** — 조항별 사유는 §2 |
| 후보 상태 분포 게이지 | `pr_merged_count` — 이슈가 쓰지 말라고 적었다 |
| 🔴 MDC 로그 포맷 | 알림·임계 — #26 |

**마이그레이션 없음 · 새 의존 없음 · 새 환경변수 없음.**

---

## 4. 기술 설계

### 변경 파일

| 파일 | 구분 | 내용 |
|---|---|---|
| `support/observability/PipelineMetrics.java` | 신규 | 🔴 **계측 어휘를 한 곳에** — 태그가 여기서만 만들어진다 |
| `support/observability/MetricNames.java` | 신규 | 이름 상수 |
| `agent/adapter/out/llm/RecordingLanguageModel.java` | 수정 | 토큰·결과 계측 (유일한 길목) |
| `repository/application/ScanPipelineUseCase.java` | 수정 | 단계 타이머·결과 |
| `repository/application/AnalyzeRepositoryPolicyUseCase.java` | 수정 | S-5 **통과/차단** 카운터 |
| `candidate/application/AnalyzeIssuesUseCase.java` | 수정 | 🔴 분석 **건당** 결과 — 설계 ④-b |
| `candidate/adapter/out/persistence/ContributionCandidateRepository.java` | 수정 | 상태별 집계 쿼리 |
| `candidate/application/CandidateStatusGauge.java` | 신규 | 상태 분포 게이지 |
| `src/main/resources/logback-spring.xml` | 신규 | 🔴 **MDC 포맷** |
| `application.yml` | 수정 | `management.endpoints` 에 `metrics` |

### 설계 ① — 🔴 계측 어휘를 **한 클래스에 가둔다**

```java
// support/observability — 태그를 만드는 유일한 지점
public void llmCall(LlmCallSite site, LlmOutcome outcome, LlmUsage usage) { … }
public void pipelineStage(Stage stage, StageOutcome outcome, Duration took) { … }
public void safetyGate(SafetyClause clause, GateOutcome outcome,
                       ContributionNotAllowedException.Reason reason) { … }
```

**왜 한 곳인가** — 태그 값이 코드 여기저기서 만들어지면 「이번엔 저장소 이름을
태그로 넣자」가 언젠가 들어온다. `Timer.builder(...).tag(...)` 를 호출부에 흩으면
**S-4 와 카디널리티를 리뷰가 매번 다시 봐야 한다.**

`ScrubbedRules`·`IssueAnalysis` 가 스크럽을 값 타입으로 강제한 것과 같은 수법이다 —
**시그니처가 enum 만 받으면 문자열을 넣을 방법이 없다.**

⚠️ 초안은 사유를 `String` 으로 받고 「enum 상수 이름인지 테스트로 검증」하려 했다.
**그럴 필요가 없다** — 지금 게이트 사유 enum 은 `ContributionNotAllowedException.Reason`
하나뿐이고(S-1·S-2 게이트가 이번 범위에 없다), 「조항마다 달라 공통 타입이 없다」는 상황이
**아직 오지 않았다.** `String` 을 미리 열고 테스트로 지키는 것보다 **컴파일러가 막게
하는 것**이 설계 ①의 취지와 일관된다. 타입이 늘 때 봉인 인터페이스로 올린다.

⚠️ 규율 ④ — `support` 가 `repository.domain` 의 enum 을 import 한다. 값 타입이므로
허용 범위이나, 조항이 늘어 이 import 가 여럿이 되면 `support` 에 얇은 enum 을 두고
매핑한다.

### 설계 ② — NFR-2: 계측이 업무를 죽이지 않는다

```java
try {
    metrics.llmCall(...);
} catch (RuntimeException e) {
    log.warn("메트릭 기록 실패 — 업무는 계속한다", e);
}
```

메트릭 하나 때문에 스캔이 죽으면 본말전도다. **다만 삼키고 조용히 넘어가지는 않는다** —
계측이 죽은 것을 모르면 「지표가 0 이니 아무 일도 없었다」로 읽는다.

⚠️ 이 방어를 `PipelineMetrics` **안**에 둔다. 호출부마다 `try/catch` 를 쓰면 빠뜨린다.

### 설계 ③ — LLM 계측은 `RecordingLanguageModel` 에 얹는다

토큰이 지나는 **유일한 길목**이다(`succeeded`/`failed` 두 지점). 여기 얹으면
호출 지점(ANALYZE·POLICY·…)이 늘어도 자동으로 계측된다.

⚠️ **데코레이터를 하나 더 만들지 않는다.** `MeteringLanguageModel` 을 새로 끼우면
「기록 → 계측 → 실제」 3겹이 되고, `LanguageModelConfig` 의 조립 순서가 곧 계약이 된다.
이미 기록을 강제하는 데코레이터가 있으니 **그 안에서** 잰다.

### 설계 ④ — 단계 타이머는 `ScanPipelineUseCase` 에

`POLICY`·`SCAN`·`FILTER`·`ANALYZE` 네 단계의 소요 시간과 결과를 잰다.
⚠️ `Clock` 이 아니라 Micrometer `Timer.Sample` 을 쓴다 — 벽시계가 아니라
**경과 시간**이고, 고정 `Clock` 테스트에서 0 이 되면 안 된다.

### 설계 ④-b — 🔴 `analysis_success_rate` 는 단계 타이머로 **나오지 않는다**

초안이 이것을 「✅ 잰다」로 분류해 놓고 **계측 지점을 설계에 넣지 않았다.**

| 재는 것 | 어디서 | 무엇을 답하나 |
|---|---|---|
| 단계 타이머(설계 ④) | `ScanPipelineUseCase` | 「ANALYZE **단계**가 예외 없이 끝났는가」 |
| 🔴 **건당 결과** | `AnalyzeIssuesUseCase` | 「이슈 N건 중 **몇 건이** 분석에 성공했는가」 |

`analysis_success_rate` 가 묻는 것은 후자이고, 그 데이터는 이미
`AnalyzeIssuesUseCase` 의 카운터(`analyzed`·`rejected`·`failed`·`skipped`)에 있다.
배치가 끝날 때 한 번에 올린다 — 건마다 올리면 호출이 N배가 되고 값은 같다.

`analysis_outcome_total{outcome=ANALYZED|REJECTED|FAILED|SKIPPED}` 하나로
분모(합)와 분자(ANALYZED)가 모두 나온다.

⚠️ `REJECTED` 를 실패로 세지 않는다 — 임계에 걸러진 것은 **정상 동작**이다.
성공률을 `ANALYZED / (ANALYZED + REJECTED + FAILED)` 로 볼지
`(ANALYZED + REJECTED) / 전체` 로 볼지는 **대시보드가 정한다.** 우리는 네 값을 그대로 준다.

### 설계 ⑤ — 상태 분포는 **게이지**다

`candidate_count{status=ANALYZED}` 는 누적이 아니라 **현재 값**이다.
`MultiGauge` 로 스크레이프 시점에 `GROUP BY status` 를 한 번 친다.

⚠️ **스크레이프마다 쿼리가 나간다.** 후보가 수만 건이 되면 부담이지만 인덱스가 있고
(`idx_contribution_candidate_status`), 지금은 0건이다. 커지면 캐시를 붙인다 — R-3.

### 설계 ⑥ — 🔴 MDC 로그 포맷 (FR-5)

`logback-spring.xml` 을 만들고 Boot 기본 패턴에 MDC 를 더한다.

```
… %5p [%X{repositoryId:-} %X{candidateId:-} %X{stage:-} %X{attempt:-}] %logger{36} : %m%n
```

🔴 **MDC 에 외부 텍스트를 넣지 않는다.** 지금 들어가는 것은 전부 식별자·enum 이다
(`repositoryId`·`candidateId`·`stage`·`attempt`). 그것을 **테스트로 고정**한다 —
로그 포맷은 모든 로그 줄에 붙으므로 여기가 오염되면 전부 오염된다.

⚠️ **`repositoryId` 는 이슈가 지정한 3키에 없다.** 실제 MDC 에 존재하므로 넣지만,
이슈 범위를 넘는 추가임을 밝혀 둔다.

#### 🔴 포맷만 깔면 대괄호가 대부분 비어 있다

`stage` MDC 를 채우는 곳은 **`RecordingLanguageModel` 하나뿐**이고 값은 `LlmCallSite` 다.
파이프라인 단계(`ScanExecutionState.Stage` — POLICY·SCAN·FILTER·ANALYZE)는
**MDC 를 채우지 않는다.** 즉 포맷에 `%X{stage}` 를 넣어도 **LLM 호출 구간 외에는 빈다.**

「한 후보가 여러 단계를 거치므로 식별자 없이는 로그를 이어붙일 수 없다」는 목적이
**포맷만으로는 달성되지 않는다.** 그래서 `ScanPipelineUseCase` 가 단계 진입 시
`MDC.put("stage", …)` 를 함께 넣는다.

⚠️ 두 `stage` 의 어휘가 다르다(`LlmCallSite` vs 파이프라인 `Stage`). 같은 MDC 키에
다른 어휘가 섞이는 것을 감수한다 — 로그를 읽는 사람에게는 「지금 어느 단계인가」가
하나의 질문이고, 중첩되면 **안쪽(LLM)이 바깥(파이프라인)을 덮었다가 되돌아온다.**
그 사실을 `logging.md` 에 적는다.

### 체크리스트 답변

| # | 항목 | 답 |
|---|---|---|
| 1 | 소유 도메인 | 계측 어휘는 **`support`**(도메인 없는 공통). 호출은 각 도메인 |
| 2 | 레이어 배치 | `support/observability` · 계측 호출은 application·adapter/out |
| 3 | 능력 인터페이스 | **불필요** — Micrometer 는 라이브러리이지 대외 시스템이 아니다 |
| 4 | 🔴 트랜잭션 안 대외 호출 | 없다. 메트릭은 메모리다 |
| 5 | 상태 전이 영향 | **없다** — 읽기만 |
| 6 | 멱등 | 카운터는 단조 증가가 정상이다 |
| 7 | `Clock` 주입 | 타이머는 `Timer.Sample`(경과 시간) — 설계 ④ |
| 8 | 🔴 안전 경계 | S-4 — §2 ① |

---

## 5. 구현 순서

### 실행 모드: sequential

| 단계 | 내용 | 선행 |
|---|---|---|
| 1 | `MetricNames` · `PipelineMetrics` + 단위 테스트(태그 어휘) | — |
| 2 | LLM 계측 (`RecordingLanguageModel`) | 1 |
| 3 | 파이프라인 단계 타이머 | 1 |
| 4 | S-5 게이트 카운터 | 1 |
| 5 | 상태 분포 게이지 + 집계 쿼리 | 1 |
| 6 | 🔴 `logback-spring.xml` + MDC 테스트 | — |
| 7 | actuator 노출 · 통합 테스트 · 문서 | 2~6 |

---

## 6. 테스트 계획

### 🔴 안전 경계

| 테스트 | 무엇을 고정하나 |
|---|---|
| `메트릭_태그에_외부_텍스트가_없다_S4` | ⚠️ **우리 미터로 한정**(`MetricNames` 선언분). 태그 값이 enum 상수·boolean·숫자뿐 |
| `기본_미터가_우리_식별자를_담지_않는다_S4` | Boot 기본 미터(`http.server.requests` 등)에 `candidateId`·`repositoryId` 태그 키가 없다 |
| `메트릭_태그가_식별자를_담지_않는다_S4` | `candidateId`·`issueId`·`repositoryId` 라는 **태그 키가 없다** |
| `MDC_에_외부_텍스트를_넣지_않는다_S4` | 코드가 `MDC.put` 하는 키가 허용 목록 안 |
| `게이트_차단이_집계된다_S5` | 금지·보류·미분석이 각각 세어진다 |
| 🔴 `게이트_통과가_집계된다_S5` | 분모가 생긴다 — 「0건 차단」과 「계측 고장」을 가른다 |

### 유닛

| 대상 | 케이스 |
|---|---|
| `PipelineMetrics` | 토큰 누적 · 결과별 카운터 분리 · **계측 실패가 예외를 전파하지 않는다**(NFR-2) |
| 로그 패턴 | MDC 값이 실제 출력에 실린다 (`ListAppender` 가 아니라 **패턴 렌더링**을 본다) |

### 통합 (`@AgentIntegrationTest`)

| 케이스 | 단언 |
|---|---|
| 파이프라인 1회 | 단계 타이머 4개 · LLM 카운터 · 상태 게이지가 **레지스트리에 실재** |
| 금지 저장소 | 게이트 차단 카운터가 오른다 · 단계 타이머는 `SCAN` 이후가 없다 |
| actuator | `/actuator/metrics` 에 우리 미터 이름이 보인다 |

---

## 7. 리스크

| # | 리스크 | 완화 |
|---|---|---|
| R-1 | 🔴 태그 카디널리티 폭발 | 설계 ① — 시그니처가 enum 만 받는다 + 테스트 |
| R-2 | 계측 실패가 업무를 죽인다 | 설계 ② — `PipelineMetrics` 안에서 흡수 |
| R-3 | 게이지 쿼리가 스크레이프마다 나간다 | 지금 0건. 커지면 캐시 — 후속 |
| R-4 | 인스턴스가 늘면 카운터가 갈린다 | R-7 과 **같은 조건**에서 함께 푼다 |
| R-7 | 🔴 **메트릭이 프로세스 메모리에만 있다 — 재기동하면 전부 사라진다** | 아래 |
| R-5 | **없는 단계 지표를 기대하고 대시보드를 짠다** | 이슈·PR 에 「무엇이 아직 없는지」를 표로 남긴다 |
| R-6 | `logback-spring.xml` 이 Boot 기본 로깅 설정을 덮는다 | Boot 의 `defaults.xml` 을 include 해 기본값을 유지하고 패턴만 바꾼다 |
| R-8 | 가드 테스트가 Boot 기본 미터 때문에 무너진다 | 범위를 **우리 미터로 한정** — §2 ② |
| R-9 | `/actuator/metrics` 가 인증 없이 열린다 | 이미 `health,info` 가 같은 조건. 포트 분리·인증은 **배포 결정**으로 문서화 — §2 ② |

#### R-7 — 소실과 인스턴스 분산은 **같은 문제**다

초안이 두 곳에서 **다른 조건으로** 미뤘다 — NFR-3 은 「배포 대상이 생길 때」, R-4 는
「Q-3 이 닫힐 때, 집계는 스크레이퍼 몫」. **스크레이퍼가 없다는 이유로 레지스트리를 빼
놓고, 분산 리스크는 그 없는 스크레이퍼에 떠넘긴 셈**이라 논리가 닫히지 않는다.

하나로 합친다 — **외부 레지스트리(Prometheus 등)를 붙이는 시점에 둘 다 풀린다.**
그 전까지 메트릭은 `SimpleMeterRegistry` 메모리에만 있고 재기동하면 사라진다.

⚠️ **완화가 하나 있다** — 누적 토큰만큼은 `agent_run` 테이블에 **영속돼 있다.**
메트릭이 사라져도 `SELECT SUM(input_tokens), SUM(output_tokens) FROM agent_run` 으로
읽을 수 있다. 「비용이 보이지 않는다」는 위험의 핵심은 그 쿼리로 막힌다 —
메트릭은 **실시간 관측**이지 유일한 장부가 아니다.

---

## 8. 복잡도

| 단계 | 파일 수 | 복잡도 |
|---|---|---|
| 1 계측 어휘 | 2 | **중간** — 태그 설계가 본체 |
| 2~4 계측 삽입 | 3 | 낮음 |
| 5 게이지 | 2 | 중간 |
| 6 로그 포맷 | 1 | **중간** — Boot 기본을 깨기 쉽다 |
| 7 테스트·문서 | ~6 | 중간 |

---

## 9. 문서 동기화 대상

| 문서 | 왜 |
|---|---|
| `rules/conventions/logging.md` | MDC 포맷이 **실제로 적용됐다** — 「포함시킨다」가 요구에서 사실로 |
| `rules/context/project-overview.md` | 성공 지표를 **관측할 수단이 생겼다** |
| `codemaps/architecture.md` | `support/observability` 신설 |
| `.env.example` | ❌ 변경 없음 |

---

## 10. 변경 이력

| 일자 | 작성자 | 변경 내용 |
|------|--------|----------|
| 2026-09-26 | smileboy0014 | 초안 — 측정 대상이 없는 지표를 범위에서 가르고, 비용 대신 토큰으로(A-1) |
| 2026-09-26 | smileboy0014 | rev 2 — `gap-analyzer` 검토 반영. 🔴 게이트 **통과**도 센다 · 🔴 **엔드포인트 노출면** 절 신설(security 없음·Boot 기본 미터). 🟡 `analysis_success_rate` 계측 지점 추가(설계 ④-b) · S-3 「코드 없음」이 **사실과 달라** 정정(#17 이 이미 있다) · MDC `stage` 가 LLM 구간에만 차는 간극 · A-1 을 후속 이슈로 승격 · R-7(소실)로 NFR-3·R-4 논리 통합 · `retry_count` 를 #21 에 양보 · 게이트 사유를 `String` 대신 enum 으로 |
