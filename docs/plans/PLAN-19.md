# PLAN-19 — 검증 파이프라인 (compile → test → format → diff 검사)

> 타입 `feature` · PRD §15. **이 게이트가 제품의 본질이다**(PRD §30) — 비결정적 LLM 출력을
> 결정적 게이트로 거르는 것이 제품이고, 코드 생성은 그 안의 한 단계다.

---

## 0. 스코프 판정

| 항목 | 답 |
|---|---|
| 소유 도메인 | **`agent`** — 실행과 비용의 도메인이다. `agent/application` 을 **신설**한다(지금 `adapter`·`domain` 뿐) |
| 레이어 | 파이프라인 조율 = `application`. 판정 규칙 = `domain` 값 타입 |
| 능력 인터페이스 | **새로 만들지 않는다** — `CodeSandbox`(#17)를 그대로 쓴다 |
| 🔴 대외 호출 위치 | 샌드박스 실행은 **최대 30분**이다. **트랜잭션 밖**에서 돌리고 결과 영속화만 짧은 트랜잭션 |
| 상태 전이 | **만들지 않는다** — `TESTING` 전이는 #18·#21 의 몫이다. 여기서 흘려보내지 않는다 |
| 멱등성 | 같은 후보를 두 번 검증해도 `AgentRun` 이 append-only 로 두 행이 될 뿐 부작용 없음 |
| `Clock` | 주입. 단계별 소요를 기록한다 |

---

## 1. 🔴 「종료 코드 함정」을 이슈보다 한 걸음 더 간다

이슈 완료 조건은 이렇다.

> 파이프 종료 코드 함정 주의 — 출력을 잘라 보고 성공 판정하지 않는다.
> **종료 코드와 성공 문자열을 둘 다 확인**

**그것으로 부족하다.** #73 이 같은 축에서 한 칸 더 갔고, 여기에 그대로 적용된다 —
**「판정에 쓰는 신호가 대상에 닿는가」**를 먼저 물어야 한다.

`SandboxResult` 가 이미 그 신호를 들고 있다.

```java
record SandboxResult(int exitCode, String output, boolean truncated,
                     boolean timedOut, Duration duration, boolean cleanedUp)
    boolean succeeded()        // !timedOut && exitCode == 0
    boolean outputIsComplete() // !truncated
```

| 상황 | 순진한 판정 | 🔴 실제 |
|---|---|---|
| `timedOut` | `exitCode` 를 본다 | **exit code 가 의미 없다.** `succeeded()` 가 이미 막는다 |
| `truncated` **이고 성공 문자열 없음** | 「실패」 | 🔴 **「판정 불가」다.** 잘린 뒷부분에 있었을 수 있다 — **측정이 대상에 안 닿았다** |
| `cleanedUp == false` | 무시 | 컨테이너가 남았다. 판정과 별개로 **반드시 보고**한다 (S-3) |

### 판정 불가를 어느 쪽으로 넘기나 — 되돌릴 수 없는 쪽을 피한다

`external-deps.md` 의 기준을 그대로 쓴다. 「보수적으로」가 아니라 **실패의 방향**이다.

| | 판정 불가를 「통과」로 | 판정 불가를 「실패」로 |
|---|---|---|
| 틀렸을 때 | 🔴 **검증 안 된 코드가 Draft PR 로 나간다** — S-2·S-5 에 닿고 되돌릴 수 없다 | 후보 하나가 불필요하게 재시도·`FAILED` 로 간다. **되돌릴 수 있다** |

→ **판정 불가는 실패다.** 다만 **실패 사유를 「검증 실패」와 구분해 기록**한다 —
#21 의 에러 분석이 「테스트가 깨졌다」와 「출력이 잘려 모른다」를 같게 다루면 엉뚱한 재시도를 한다.

⚠️ **이것이 요구 4(입력 도달)의 적용이다.** 성공 문자열 검사는 **열거**이고,
`truncated` 는 그 검사가 **도달하지 못한 입력**이다.

---

## 2. 구조

```
agent/application/
  VerifyChangeUseCase          파이프라인 조율 · 트랜잭션 경계 밖에서 샌드박스 호출
  VerificationRecorder         결과 영속화 (짧은 트랜잭션) — @Transactional 은 여기만

agent/domain/
  VerificationStage            enum — COMPILE · UNIT_TEST · INTEGRATION_TEST · FORMAT · DIFF
  StageOutcome                 enum — PASSED · FAILED · UNDETERMINED · SKIPPED
  StageResult                  값 — stage · outcome · exitCode · duration · 스크럽된 요약
  VerificationReport           값 — 단계별 StageResult 목록 + 전체 판정
  VerificationCommands         Gradle argv 조립 (BuildTool 별)
  DiffInspection               diff 검사 규칙 (계획 범위 밖 · 디버그 잔재 · 대용량 바이너리)
```

⚠️ **`agent/application` 은 지금 없다.** 신설이므로 `codemaps/architecture.md` 갱신 대상이다.

### 2.1 단계별 결과 기록 — 「어디서 떨어졌는지」

🔴 **첫 실패에서 멈추되 그때까지의 단계를 전부 남긴다.** 끝까지 돌리지 않는 이유는
컴파일이 깨졌는데 테스트를 30분 돌릴 이유가 없어서다. 남기지 않으면
**#21 의 에러 분석이 「무엇이 통과했는지」를 모른다.**

이후 단계는 `SKIPPED` 로 명시한다 — **기록이 없는 것과 건너뛴 것을 구분**한다.
(`/work` 빌드 판정이 「실행 안 됨」과 「통과」를 가른 것과 같은 축이다.)

### 2.2 🔴 `SandboxResult.output` 의 소비자가 여기서 처음 생긴다

`ExternalTextScrubRegistryTest` 가 그렇게 적어 뒀다.

```
SandboxResult.output        → PENDING  "#18·#19 — 소비자가 아직 없다. DB 에 앉는 자리는
                                        GeneratedChange.testResult 이고 그쪽도 PENDING"
GeneratedChange.testResult  → PENDING  "#18 — 빌드·테스트 출력"
```

**이 PR 이 그 소비자다.** 둘을 `PENDING` → **`FORCED_POINT`** 로 바꾼다.

- `StageResult` compact 생성자가 요약을 **`TokenRedactor.redact`** 한다 (`ScrubbedRules` 수법)
- `GeneratedChange` 에 `recordVerification(...)` 을 더하고 **거기가 유일한 대입 지점**

⚠️ 등록표의 `GeneratedChange.testResult` 담당이 **「#18」로 적혀 있다 — #19 로 정정**한다.
`diff` 는 #18 그대로다. (peer 세션에 통지함)

🔴 **빌드 출력은 시크릿 위험이 특히 높다** — 환경변수를 찍는 빌드 스크립트가 흔하고,
그 저장소는 **신뢰할 수 없는 코드**다(S-3). 스크럽은 「잊지 않고 부른다」가 아니라
**「부를 수밖에 없는 자리」**여야 한다 (`safety-boundaries.md` S-4).

### 2.3 `GeneratedChange` 소유권 — #18 과 겹친다

| 컬럼 | 쓰는 이슈 |
|---|---|
| `branchName` · `commitSha` · `diff` | #18 |
| **`testResult`** | **#19 (이 PR)** |
| `reviewResult` | #20 |

🔴 지금 그 엔티티에 **팩토리도 쓰기 메서드도 없다.** 둘 다 팩토리를 만들면
**생성 경로가 둘**이 되고, Q-7 이 `@Builder` 를 금지한 이유에 정면으로 걸린다.

**이 PR 은 생성 팩토리를 만들지 않는다** — 기존 행에 기록하는 `recordVerification` 만 더한다.
⚠️ #18 이 아직 push 전이라 **#18 이 먼저 머지되면 그쪽 팩토리에 맞춘다.**

---

## 3. `verify` 엔드포인트 — 🔴 **열지 않는다**

#24 가 이관한 판단이다. 이슈가 「열지 말지 **먼저 판단**하라」고 요구했다.

**결론: 열지 않는다.**

| 근거 | |
|---|---|
| S-6 이 세는 승인 지점은 **셋**이다 | 선정 · 착수 · PR 생성. `verify` 는 그 목록에 없다 |
| 🔴 열면 게이트가 느는 게 아니라 **줄어든다** | 「검증을 사람이 **건너뛸 수 있는가**」라는 질문이 생긴다. 수동 트리거는 **안 누를 수도 있다**는 뜻이다 |
| 제품 정의에 반한다 | PRD §30 — 품질의 축은 「나쁜 결과를 **걸러내는가**」다. **게이트를 느슨하게 만드는 변경은 기능 추가가 아니라 제품 훼손**이다 |
| PRD §23 에 있다는 것은 근거가 못 된다 | 같은 PRD 의 §24 가 이미 S-6 와 충돌해 **틀린 것으로 판정**됐다(#30). §23 도 검토 대상이다 |

**검증은 착수(`implement`)에 딸린 자동 단계**이지 사람이 부르는 문이 아니다.

⚠️ **「빠뜨렸다」와 「안 열기로 했다」를 구분**해야 하므로 근거를
`codemaps/domain.md` 의 승인 게이트 절에 남긴다 — 이슈가 명시적으로 요구한 조건이다.

---

## 4. 안전 경계

| 조항 | 접촉 | 어떻게 지키나 |
|---|---|---|
| 🔴 **S-3** | **접촉** | 모든 실행이 `CodeSandbox.run(ExecuteCommand)` 경유. `ProcessBuilder`·`Runtime.exec` 를 쓰지 않는다(`HostExecutionAbsenceTest` 가 이미 고정). **타임아웃은 `SandboxLimits`**, `cleanedUp == false` 를 **보고**한다 |
| 🔴 **S-4** | **접촉 · 이 PR 의 핵심** | 빌드 출력이 DB 로 앉는 **첫 경로**다. `StageResult` compact 생성자가 스크럽을 **강제**하고 등록표를 `FORCED_POINT` 로 바꾼다 |
| **S-6** | **접촉 — 게이트를 늘리지 않는 쪽으로** | `verify` 엔드포인트를 **열지 않는다**(§3). 상태 전이를 만들지 않는다 |
| S-5 | 간접 | 검증 실패가 Draft PR 을 막는 게이트다. 판정 불가를 통과로 다루지 않는 것이 그 실질(§1) |
| S-1 · S-2 | 미접촉 | push·PR 경로를 건드리지 않는다 |

## 5. 미결 대조

| 항목 | 처리 |
|---|---|
| 🔴 **Q-4** (샌드박스 네트워크 · **Gradle 한정 부분 확정**) | **접촉.** 검증 명령이 Gradle 전제다. `BuildTool.requireSupported()` 가 Maven 을 이미 거부하므로 **조용히 네트워크를 여는 경로가 없다**. ⚠️ Q-4 의 종결 조건이 「`spring-kafka` 로 워밍→씨딩→오프라인 실행 한 번 통과」인데 **그건 #18 이 워크스페이스를 채워야 가능**하다 — 이 PR 은 Q-4 를 닫지 않는다 |
| **Q-6** (재시도 단위) | **접촉.** 이 PR 은 `VERIFY` 한 단계다. **재시도 카운터를 건드리지 않는다** — `attempt` 증가와 `FAILED` 전이는 #21 의 몫이다. `AgentRun.stage=VERIFY` 로만 기록 |
| Q-9 | 대역은 `FakeCodeSandbox`(이미 존재). **실제 컨테이너를 띄우지 않는다** |
| Q-1·Q-2·Q-3·Q-5·Q-7·Q-8·Q-10·Q-11 | 미접촉 |

⚠️ **미결을 임의로 닫지 않는다.** Q-4 는 열린 채 두고, 이 PR 이 그 종결 조건에
해당하지 않는 이유를 PR 본문에 적는다.

## 6. 검증

| 무엇 | 어떻게 |
|---|---|
| 단계별 판정 | `FakeCodeSandbox` 로 각 단계 실패를 재현 — **첫 실패에서 멈추고 이후는 `SKIPPED`** |
| 🔴 **판정 불가** | `truncated=true` + 성공 문자열 없음 → **`UNDETERMINED`**, 그리고 **전체 판정은 실패**. 「통과」로 새지 않는 것을 단언 |
| 🔴 **타임아웃** | `timedOut=true` 면 `exitCode` 와 무관하게 실패 |
| 🔴 **스크럽** | 빌드 출력에 토큰을 심고 `GeneratedChange.testResult` **DB 재조회**로 없음을 확인 (반환값 아님) |
| `cleanedUp=false` | 판정과 별개로 **보고에 남는지** |
| 🔴 **돌연변이** | `StageResult` 의 `redact` 를 빼고 몇 건이 빨개지는지 **숫자로** PR 에 적는다 |
| 전체 | `./gradlew build` — ⚠️ **`> Task :test` 접미사와 요약 줄을 함께 본다**(#73) |

## 7. 범위 밖 — 명시적으로 남긴다

- **`implement` 배선** — #18. 이 PR 은 워크스페이스를 **받기만** 한다
- **재시도·`FAILED` 전이** — #21. 카운터를 건드리지 않는다
- **AI diff 리뷰** — #20. `reviewResult` 를 안 쓴다
- **Q-4 종결(실측)** — #18 이 워크스페이스를 채워야 가능
- ⚠️ **`GeneratedChange` 생성 팩토리** — #18 소유. 이 PR 은 기록 메서드만
