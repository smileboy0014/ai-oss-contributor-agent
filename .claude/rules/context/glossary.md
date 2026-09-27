# 용어 사전

> 같은 단어가 두 가지를 가리키는 자리가 많다. 문서·코드·커밋에서 아래 표기를 따른다.

## 가장 헷갈리는 둘

| 용어 | 뜻 | 코드에서 |
|---|---|---|
| **이 저장소** | `ai-oss-contributor-agent` 자체 | — |
| **대상 저장소** (target repo) | 에이전트가 기여하는 외부 OSS | `OssRepository` 엔티티 |

`repository` 라는 말이 세 곳에서 다른 뜻이다. 반드시 구분해 쓴다.

| 표기 | 뜻 |
|---|---|
| `com.ossagent.repository` | **도메인 패키지** — 대상 저장소 관리 |
| `OssRepository` | **엔티티** — 등록된 대상 저장소 1건 |
| `OssRepositoryRepository` | **Spring Data JPA 인터페이스** — 영속 어댑터 |

DB 접근 인터페이스를 도메인 이름으로 줄여 쓰지 않는다(`RepositoryRepository` 같은 이름이 생긴다).

## 파이프라인 단계

| 용어 | 뜻 |
|---|---|
| **Policy Analysis** | 대상 저장소의 기여 규약을 수집·판정해 `RepositoryPolicy` 로 고정. **후보보다 먼저**이고 저장소 단위다 |
| **보류** (pending) | 규약을 **읽지 못해** 판정이 서지 않은 상태. `aiContributionAllowed = NULL`. 🔴 **「허용」이 아니다**(S-5) — 자동으로 풀리지 않고 사람이 해소한다(#24) |
| **해소** (resolution) | 사람이 보류를 푼 행위. `POST /repositories/{id}/policy/resolution` · `resolved_at` 이 증거다. 🔴 **「허용」과 같은 말이 아니다** — 읽고 **금지로 닫는 것도 해소**다(#24) |
| **통행증** (`PolicyClearance`) | 「규약을 읽었고 AI 기여가 허용이었다」는 **값**. `RepositoryPolicy.clearance()` 만 발급하고 `startImplementing` 이 **인자로 요구**한다 — S-5 의 의무가 javadoc 에서 컴파일러로 옮겨간 자리(#24) |
| **Scan** | 대상 저장소의 open 이슈를 수집해 저장 |
| **스캔 파이프라인** | 저장소 하나에 대해 `Policy Analysis → Scan → Filter → Analysis` 를 잇는 실행(#14). 🔴 **`ANALYZED` 에서 멈춘다** — 선택·구현은 사람이 트리거한다 |
| **진행 상태** (scan execution) | 파이프라인 1회의 국면 — `IDLE`·`QUEUED`·`RUNNING`·`SUCCEEDED`·`SKIPPED`·`FAILED`(`ScanPhase`). ✅ **DB 에 있다**(`scan_execution` · V10 · #26) — 프로세스 메모리이던 시절의 「다중 인스턴스에서 중복 방어가 깨진다」는 해소됐다 |
| **리스** (lease) | 스캔 자리를 붙들 수 있는 시한(`lease_expires_at` · `scan.lease-duration`). 🔴 **활성 판정이 국면과 함께 본다** — 없으면 `kill -9` 된 인스턴스의 `RUNNING` 행이 남아 그 저장소가 **영구히 409** 가 된다. 메모리 구현에 있던 「재기동이 상태를 지운다」는 안전장치를 대신하는 자리다.<br>⚠️ 뺏김의 최악은 **중복 스캔 1회**이고 수집이 멱등이라 되돌릴 수 있다 — 가르는 것은 보수성이 아니라 **실패의 방향** |
| **저장소별 주기** | `oss_repository.scan_interval_minutes`. 🔴 **NULL 이면 전역 기본값**(`scan.default-interval`).<br>⚠️ `scan.schedule.fixed-delay` 와 **다른 축**이다 — 저쪽은 「얼마나 자주 **훑는가**」이고 이쪽은 「한 저장소를 얼마나 자주 **도는가**」다. 훑는 주기가 더 촘촘해야 한다 |
| **건너뜀** (skipped) | 🔴 **실패가 아니다.** 규약이 막았거나(금지·보류) 읽지 못했거나 레이트리밋이다 |
| **Filter** | 규칙 기반 1차 배제 — 요구사항 불명확 · 대규모 아키텍처 변경 · ~~종료됨~~. **대외 호출을 하지 않는다** (#9).<br>⚠️ 「종료됨」은 구현돼 있으나 **발화하지 않는다** — 수집이 `state=open` 고정이라 닫힌 이슈가 데이터에 없다 (#14).<br>「활성 PR 존재」는 여기가 아니라 **#11 입구 + #23** 다 — S-2 방어의 이전 |
| **Analysis** | LLM 기반 기여 가능성 판정. 산출물은 category · difficulty · feasible · confidence 등 |
| **Candidate** | 분석을 통과해 기여 대상이 된 이슈. 상태머신의 주체 |
| **Repository Analysis** | 이슈 키워드로 대상 저장소의 관련 소스·테스트를 좁혀 찾는 단계. 저장소 전체를 LLM 에 넣지 않는다 |
| **Implementation Plan** | 수정할 파일과 방법. 검증(Validate)을 거쳐야 코딩으로 넘어간다 |
| **Verification** | 🔴 **컴파일 → 테스트 → diff 검사** 셋. **샌드박스 안에서** 수행(#19).<br>⚠️ 원래 「유닛 → 통합 → 포맷」까지 다섯이었다. **둘은 입력이 없어 뺐다** — 포맷 명령은 `RepositoryPolicy` 에 필드가 없어 하드코딩하면 대상 저장소 규약을 우리 어휘로 대체하는 것이고(S-5), 통합 테스트는 `testCommand` 가 하나뿐인데다 실행 단계가 `network=none` 이라 **정상 코드가 실패**한다.<br>**이름만 있는 칸을 두지 않는다** — 다음 사람이 채우려다 더 나쁜 것을 만든다 |
| **판정 불가** (`UNDETERMINED`) | 🔴 검증 한 단계를 **판정할 근거가 없는** 상태(#19). 「통과」도 「실패」도 아니다 — 출력이 `sandbox.max-output-chars` 에서 잘려 「위반 없음」을 말할 수 없거나, 규약에서 **테스트 명령을 읽지 못했을 때**.<br>⚠️ **재시도하지 않는다** — 같은 입력에 같은 결과라 Q-6 예산만 태운다. `VerificationReport.hasUndetermined()` 가 그 분기점이다.<br>⚠️ **`SKIPPED` 와 다르다** — 그쪽은 「앞이 멈춰서 안 돌렸다」이고 앞의 실패가 이미 설명한다. 이것은 **아무도 설명하지 않는 공백**이라 사람이 본다 |
| **AI Review** | 생성된 diff 에 대한 LLM 리뷰 — 빌드·테스트가 못 잡는 것(요구 충족·범위·관습·테스트 적절성)을 본다. 🔴 **판정은 셋**이다(`PASS`·`CHANGES_REQUESTED`·`UNDETERMINED`) — 「판정 불가」를 실패와 한 칸에 넣으면 고칠 수 없는 것에 재시도 예산을 태운다. 되돌릴지는 #21 이 정한다 |
| **Draft PR** | 사용자 Fork 에서 원본으로 여는 **draft** 상태 PR. 여기서 자동화가 끝난다 |

## 도메인 객체

| 용어 | 뜻 |
|---|---|
| `RepositoryPolicy` | 대상 저장소의 **기여 규약** — java 버전 · 빌드/테스트 명령 · 이슈 참조 필수 · sign-off 필수 · 테스트 필수 |
| `AgentRun` | 파이프라인 한 단계의 **1회 실행 기록**. stage · attempt · 토큰 · 상태 · 에러 |
| `GeneratedChange` | AI 가 만든 변경분 — 브랜치 · 커밋 SHA · diff · 테스트 결과 · 리뷰 결과 |
| `PullRequest` | Fork URL · 브랜치 · PR 번호 · PR URL · 상태 |

## 능력 인터페이스 — domain 이 선언하고 adapter 가 구현한다

「능력 이름」과 「기술 이름」을 바꿔 쓰지 않는다. 기술 이름이 domain 에 나타나면 규율 ③ 위반이다.

| 능력 (domain) | 구현 (adapter/out) | 뜻 |
|---|---|---|
| `RepositorySource` | `GitHubRepositorySource` | 대상 저장소의 메타데이터·파일을 **읽는다**. 쓰기 없음 |
| `IssueSource` | `GitHubIssueSource` | 대상 저장소의 open 이슈를 **읽는다**. 코멘트 경로 없음(S-2) |
| `GitHubCredentials` | `StaticTokenCredentials` | 호출마다 자격증명을 공급한다. 단수명 토큰으로 갈아끼울 이음매 — Q-1 |
| `LanguageModel` | `AnthropicLanguageModel` | LLM 호출. **4개 지점이 공유하는 1층 능력** — 그 위에 `IssueAnalyst` 등 2층이 얹힌다 |
| `PolicyDocumentSource` | `GitHubPolicyDocumentSource` | 규약 후보 문서 수집. **실패를 예외가 아니라 값으로** 돌려준다 — 무엇을 못 읽었는지가 곧 보류 사유다 |
| `ContributionRuleInterpreter` | `LlmContributionRuleInterpreter` | 규약 판정. `LanguageModel` 위에 얹히는 2층 |
| `RecordingLanguageModel` | — | 기록 강제 **데코레이터**. 노출되는 `LanguageModel` 빈은 이것뿐이라 기록을 건너뛸 경로가 없다 |
| `PromptScrubber` | `TokenRedactingPromptScrubber` | 송신 **직전** 프롬프트 시크릿 제거. S-4 에서 「밖으로 나가는 것」을 막는 유일한 방어 |
| `CodeSandbox` | `DockerCodeSandbox` | 대상 저장소 코드를 **격리 컨테이너 안에서만** 실행. S-3 의 실행체. 🔴 **한 번에 한 명령**이고 순서는 모른다 — 그것은 `SandboxPipeline` 이 세운다 |
| `TargetWorkspaceSource` | `JGitWorkspaceSource` | 대상 저장소를 **호스트에 체크아웃**하고 diff 를 뜬다. 🔴 **push 슬롯이 없다** — 능력에 자리가 없으면 어댑터가 만들 수 없다 (S-1) |
| `CodingAgent` | `LlmCodingAgent` | 계획대로 코드를 만든다. `LanguageModel` 위 **2층**. 계획 밖 경로는 그 자리에서 거부한다 |
| `ChangeVerifier` | `SandboxChangeVerifier` | 생성된 변경분을 샌드박스에서 검증. `CodeSandbox` 위에 얹히는 **2층**(#19). 🔴 **빌드 실패로 예외를 던지지 않는다** — 그것은 게이트가 작동한 모습이고 예외로 내보내면 호출자가 재시도 루프에서 삼킨다 |
| `VerificationReport` | — | 검증 한 바퀴의 결과 **값**. 🔴 `passed()` 는 **모든 단계가 `PASSED`** 일 때만 참이다 — 「실패가 없으면 통과」로 적으면 「판정 불가」가 조용히 접힌다 |
| `StageResult` | — | 단계 하나의 결과 **값**. compact 생성자가 빌드 출력 **스크럽을 강제**한다 (S-4) |
| `LlmPricing` | — | 모델 하나의 **단가** 값 — 100만 토큰당 USD. 설정(`agent.llm.pricing.<model>`)에서만 온다.<br>🔴 **없으면 비용 미터를 만들지 않는다**(#71) — 0 은 「공짜」로 읽히고 그것은 「모른다」와 다른 말이다. 한쪽만 적힌 단가는 **기동에서 거부**한다 |
| `ForkPublisher` | `GitHubForkPublisher` | 변경분을 **사용자 Fork 에** 올린다 — Fork 확보·동기화·commit·push·브랜치 삭제. **S-1 의 실행체**. 🔴 PR 을 만들지 않는다 — 그것은 `DraftPrPublisher` 이고 그 앞에 세 번째 승인 게이트가 있다 |
| `DraftPrPublisher` | `GitHubDraftPrPublisher` | upstream 에 **Draft PR** 을 연다 — **S-2 의 실행체**(#23). 🔴 이 제품이 대상 저장소에 남기는 **유일한 글**이다. `markReadyForReview`·`requestReviewers`·`merge` 는 **없는 것이 방어**다.<br>⚠️ 상태 전이도 영속화도 하지 않는다 — 능력을 합치면 게이트가 부산물이 된다 |
| `AgentRunRecorder` | `RecordAgentRunUseCase` (candidate) | 실행 이력 기록. `AgentRun` 이 남의 애그리거트라 능력으로 뒤집었다 |
| `CandidateNotifier` | `LoggingCandidateNotifier` | 새 후보가 `ANALYZED` 로 쌓였음을 알린다(#26). 🔴 **관찰이지 행위가 아니다** — 승인 게이트를 부르지 않는다(S-6).<br>🔴 **지금 나가는 곳은 로그와 메트릭뿐이다** — Slack·Webhook 이 아니다. 「알림 경로가 있다」로만 적으면 다음 사람이 외부 전송이 있다고 믿는다 |
| `CandidateNotification` | — | 알림 **값**. 🔴 **이슈 제목·본문이 들어올 자리가 타입에 없다**(S-4) — 「나중에 필요하면」으로 자리를 비워 두지 않았다. 식별자와 숫자뿐이다 |
| `ScanExecutionRegistry` | `DatabaseScanExecutionRegistry` | 스캔 진행 상태 + **중복 방어**. 🔴 자리 잡기는 **조건부 UPDATE 한 방**이다 — `SELECT` 후 `UPDATE` 로 짜면 두 인스턴스가 그 사이를 통과한다.<br>⚠️ ShedLock 을 쓰지 않는 이유는 잠그는 단위가 `@Scheduled` 메서드라는 것과, **`POST /scan` 이 스케줄러를 경유하지 않아 닿지 않는다**는 것이다 |
| `IssueAnalyst` | `LlmIssueAnalyst` | 이슈의 기여 가능성 판정. `LanguageModel` 위에 얹히는 **2층**. 🔴 **관찰값만 돌려준다** — `REJECTED` 판정은 UseCase 몫이다 |
| `RepositoryCoordinates` | — | `owner/name` 값 타입. `repository` 가 소유하고 다른 도메인이 import 한다 |
| `PolicyClearance` | — | 구현 단계 **통행증** 값 타입. 〃 — 규율 ④의 값 타입 예외다. 🔴 `adapter/in` 경계를 넘지 않는다(외부가 주입하면 게이트가 껍데기가 된다) |
| `IssueSnapshot` | — | 수집 시점의 이슈 원본 **값**. 영속 엔티티 `Issue` 와 다르다 |
| `AnalyzableIssue` | — | 분석 단계로 넘기는 이슈 **값**. `issue` 가 소유하고 `candidate` 가 import 한다 — 규율 ④ |
| `SandboxPipeline` | — | 워밍 → 씨딩 → 실행의 **순서를 세우는 것**. 능력이 아니라 `agent/application` 의 조율자다. 🔴 **저장소당 워밍 1회**(프로세스 내 락 — 다중 인스턴스에서는 성립하지 않는다) |
| `TargetCommandLine` | — | 대상 저장소의 빌드 명령 **문자열 → argv**. 🔴 **화이트리스트**다 — 거부목록이 아니다. 모르는 런처·Maven 은 거부(Q-4) |
| `WorkspaceDiff` | — | 워크스페이스의 변경분 — **본문 + 바뀐 경로 집합**. 경로 집합이 「계획 밖 파일을 건드렸는가」의 판정 근거다 |
| `RepositoryTree` | — | 대상 저장소의 **경로 목록**. 내용이 없다. `truncated` 는 「못 본 것이 있다」이지 「없다」가 아니다 (#15) |
| `RepositoryContext` | — | 저장소 분석의 산출물 — **고른 파일 + 왜 골랐나**. `repository` 가 소유하고 #16 이 받는다. 🔴 **영속화하지 않는다** |
| `SelectedFile` | — | 컨텍스트에 실린 파일 1건. compact 생성자가 **스크럽을 강제**한다 (S-4) |
| `IssueAnalysis` | — | 분석 결과 **값**. 생성자가 스키마와 **스크럽을 함께 강제**한다 (`ScrubbedRules` 와 같은 수법) |
| `DiffReviewer` | `LlmDiffReviewer` | 생성된 diff 리뷰. `LanguageModel` 위에 얹히는 **2층**. 🔴 **관찰값만 돌려준다** — 임계는 `RetryPolicy` 가 갖는다(#21) |
| `RetryPolicy` | — | 한 바퀴의 결과를 `RetryDecision` 으로 옮기는 **순수 판정**(#21). 🔴 **재시도가 화이트리스트다** — 「무엇이 재시도 불가인가」가 아니라 **「무엇이 재시도 가능인가」**를 센다. 거부목록이면 새 실패 종류가 조용히 재시도로 떨어져 비용이 3배가 된다 |
| `RetryDecision` | — | 바퀴가 끝난 뒤 무엇을 할 것인가 — `Proceed`·`Retry`·`Stop` **셋뿐**(sealed). `Stop` 이 **어느 단계에서 멈췄는지를 값으로** 든다 — 사유 문자열에서 되짚으면 문구를 바꾸는 순간 어긋난다 |
| `CodingFeedback` | — | 직전 바퀴가 **왜 실패했는가** — 다음 바퀴 프롬프트에 되먹이는 값(#21). 🔴 **「Error Analyzer」는 LLM 호출이 아니다** — 호출로 만들면 곱셈 예산이 9회 → 18회가 된다. 담는 것은 **이미 스크럽이 강제된 값**(`StageResult.summary`·`DiffReview.findings`)뿐이다 |
| `FailureFingerprint` | — | 「같은 실패가 반복되는가」의 판정 값(#21). **해시만 보관**한다(S-4).<br>🕳 **발화하지 않을 수 있다** — 빌드 출력에 타임스탬프·경로가 섞이면 같은 오류라도 지문이 갈린다. 실측 전에는 정규화하지 않는다(거부목록이 된다). 물지 못해도 **상한 3바퀴가 뒤를 받친다** |
| `DiffReview` | — | 리뷰 결과 **값**. 🔴 **S-4 의 수신 쪽 방어**다 — 리뷰가 diff 를 인용하면 시크릿이 우리 DB 로 복제되므로 생성자가 스크럽을 강제한다 |
| `ForkRef` | — | **쓰기가 허용된** 저장소 좌표. 생성 시 owner 를 단언한다. ⚠️ **방어가 아니라 「일찍 드러내는 것」**이다 — 유일한 방어는 `GitHubWriteClient` 의 쓰기 직전 어설션이고, 둘 중 지워야 한다면 이쪽이다 (#22) |
| `SyncedFork` | — | 「upstream 과 맞춰 보았고 결과가 이것이다」는 **통행증**. `PublishRequest` 가 인자로 요구해 **동기화를 보지 않고 publish 하는 것을 표현 불가능**하게 한다 — `PolicyClearance` 와 같은 수법.<br>⚠️ 강제하는 것은 **호출**이지 판단이 아니다 (#22) |
| `BaseBranch` | — | Fork 의 **기준 브랜치**. 경로에 조립되므로 `RepositoryCoordinates` 와 같은 제한을 받는다 — 초안에서 이 값만 규율에서 빠져 있었다 (#22) |
| `OpenedPullRequest` | — | 열린 Draft PR 의 **관측값** — 번호 · URL · headRef · **forkUrl**. 🔴 `draft` 플래그가 **없다**: 값으로 들면 `false` 인 인스턴스가 표현 가능해지고 소비자가 분기를 쓴다. 확인은 어댑터가 **값으로 바꾸기 전에** 한다 (#23) |
| `PrBody` | — | Draft PR 본문. 🔴 **상류에 강제 지점이 없는 값 셋이 여기 모인다** — 대상 저장소 템플릿 · 빌드 출력 · LLM 리뷰. 밖으로 나가기 직전이라 **마지막 그물**이고 compact 생성자가 스크럽을 강제한다 (S-4) |
| `PrBodyMaterials` | — | `PrBody` 의 재료. **스크럽 「전」인 것이 정체다** — 유일한 소비자가 `PrBody.compose` 라 나가는 길이 스크럽을 통과하는 길 하나뿐이다. 🔴 getter 말고 다른 출구를 만들지 않는다 |
| `PullRequestTarget` | — | PR 을 열기 위해 대상 저장소에서 읽어야 하는 것 전부 — 좌표 · 기준 브랜치 · 템플릿. 🔴 `template == null` 은 **「없다」**이고 「못 읽었다」가 아니다(그쪽은 예외로 끊긴다 — S-5) |
| `FileChange` | — | Fork 에 올릴 파일 1건. 🔴 **내용 검사가 이 경로에 없다** — 워크스페이스를 읽는 #18 이 거른다. `@ExternalText` 등록표에 `PENDING #18` 로 남겨 그 사실이 계속 보이게 했다 (#22) |

## 증분 수집 — 「언제 돌렸나」와 「어디까지 봤나」는 다르다 (#8)

| 용어 | 뜻 | 코드에서 |
|---|---|---|
| **스캔 시각** | 우리가 **언제 돌렸나** | `oss_repository.last_scanned_at` |
| **증분 커서** | 데이터를 **어디까지 봤나** — 다음 조회의 `since` | `oss_repository.issue_cursor_updated_at` |
| **조건부 요청** | `If-None-Match` 로 「안 바뀌었으면 304 만 달라」 | `issue_cursor_etag` → `IssueQuery.etag` |
| **워터마크** | 이번에 본 것 중 가장 늦은 `updated_at` | 커서의 다음 값 |

⚠️ 둘을 **겹쳐 쓰지 않는다.** 스캔 시각을 커서로 재사용하면 **스캔이 실패해도 커서가
전진해 이슈를 영구히 건너뛴다.**

⚠️ **ETag 는 `since`·`page` 와 짝이다.** URL 이 바뀌면 이전 ETag 는 다른 리소스의 것이다.
커서가 전진하면 버린다 — 우연히 매치되어 304 를 받으면 **그 페이지를 통째로 건너뛴다.**

⚠️ 「지연」과 「실패」를 바꿔 쓰지 않는다. **레이트리밋은 지연**이고, 지연은
`ScanResult.delayedUntil` 로 나온다. 예외로 나가는 것은 **권한 오류 같은 진짜 실패**뿐이다.

## 레이트리밋 — 1차와 2차를 바꿔 쓰지 않는다

둘 다 **403 으로 온다.** 구분하지 못하면 한쪽은 영구 실패가 되고 다른 쪽은 무한 재시도가 된다.

| 용어 | 신호 | 뜻 | 대응 |
|---|---|---|---|
| **1차 레이트리밋** | `X-RateLimit-Remaining: 0` | 시간당 할당량 소진 (인증 5,000/h · Search 30/min) | `resetAt` 까지 **지연** |
| **2차 레이트리밋** | `Retry-After` · 본문의 `secondary`·`abuse` · 429 | abuse detection. 단시간 집중 호출에 걸린다 | `Retry-After` 만큼 **지연** |
| **권한 오류** | 위 신호가 **하나도 없는** 403 | 토큰 스코프 부족 · 접근 불가 저장소 | 재시도하지 않고 실패 |

⚠️ 「리밋에 걸렸다」를 **실패**라고 쓰지 않는다. 정상 운영 상황이고 대응은 **지연**이다.
재시도로 다루면 남은 예산을 더 태운다.

## 상태

### 필터 판정 — 후보 상태와 다른 축이다 (#9)

`Issue.filterResult` 의 어휘이고, 아래 `CandidateStatus` 와 **섞어 쓰지 않는다.**
「배제」가 두 곳에 있어 헷갈리는 자리다 — 이쪽은 규칙이 이슈를 거른 것이고,
`REJECTED`(후보)는 LLM 분석이 후보 자격을 부정한 것이다.

| 용어 | 뜻 | 후보가 되나 |
|---|---|---|
| `NULL` | **아직 판정하지 않았다** | — |
| `PASSED` | 어떤 규칙에도 걸리지 않았다 | ✅ |
| **`UNDECIDED`** | **규칙으로 가를 수 없다 — LLM 이 본다**(#11). 「판정하지 않았다」를 「통과」로 적지 않기 위해 존재한다 | ✅ |
| `REJECTED` | 규칙이 배제했다 | ❌ |

⚠️ 「필터를 **통과한** 이슈」라고 쓰지 않는다. `UNDECIDED` 도 후보가 되므로
**「배제되지 않은 이슈」**가 맞다. 표현이 흐려지면 하류가 보류를 통과로 뭉갠다.

| 용어 | 뜻 |
|---|---|
| `DISCOVERED` → `ANALYZED` | 수집됨 → 분석 완료 |
| `SELECTED` | **사람이** 기여하기로 고른 상태. 자동으로 여기 도달하지 않는다 |
| `IMPLEMENTING` / `TESTING` / `REVIEWING` | 구현 / 검증 / AI 리뷰 진행 중 |
| `READY_FOR_PR` → `PR_CREATED` | PR 생성 대기 → Draft PR 생성 완료 (**종단**) |
| `REJECTED` | 후보 자격 미달 (**종단**) |
| `FAILED` | 재시도 상한 소진 (**종단**) |

## 외부 주체

| 용어 | 뜻 |
|---|---|
| **Upstream** | 원본 저장소. **읽기 전용** |
| **Fork** | 사용자 계정의 포크. **유일한 쓰기 대상** |
| **Maintainer** | 대상 저장소의 관리자. 우리가 만든 Draft PR 을 사람이 제출한 뒤에야 마주한다 |
| **Sandbox** | 대상 저장소 빌드·테스트를 격리 실행하는 Docker 컨테이너 |

## 샌드박스 3단계 — 섞어 부르지 않는다 (#17)

네트워크가 열리는 단계가 하나뿐이라는 것이 S-3 의 실질이다. 「샌드박스 실행」으로 뭉뚱그리면
그 구분이 사라진다.

| 용어 | 네트워크 | 명령 | 캐시 볼륨 |
|---|---|---|---|
| **워밍** (warm) | 전용 네트워크 | **우리 것** | 🔴 **마운트 안 함** |
| **씨딩** (seed) | 없음 | **우리 `cp`** | RW |
| **실행** (execute) | 없음 | 대상 저장소 것 | **RO** |

⚠️ 「워밍에서 캐시를 채운다」고 말하지 않는다. 워밍은 **워크스페이스**를 채우고, 볼륨으로
옮기는 것은 씨딩이다. 뭉뚱그리면 「워밍이 볼륨에 쓴다」로 읽혀, 그 오염 경로를 막은 이유가
사라진다.

## 토큰 — 이름이 비슷해서 바꿔 끼우기 쉽다

| 용어 | 뜻 | 이 프로젝트에서 |
|---|---|---|
| **classic PAT** | 스코프 단위 개인 토큰 (`public_repo` 등) | ✅ **우리가 쓰는 것** (Q-1) |
| **fine-grained PAT** | 저장소별·권한별 세분화 토큰 | ❌ **쓸 수 없다** — 멤버가 아닌 upstream 에 PR 생성 불가(403) |
| **설치 토큰** (installation token) | GitHub App 이 설치된 저장소에서 쓰는 토큰 | ❌ upstream 에 설치될 리 없다 |
| **사용자 대행 토큰** (user-to-server) | GitHub App 이 OAuth 로 사용자를 대행 | 다중 사용자 확장 시의 경로 |

⚠️ 「fine-grained 가 더 안전하니 바꾸자」는 판단이 반복해서 나올 자리다.
바꾸면 **PR 생성이 403 으로 죽는다.** 근거는 [`open-questions.md`](./open-questions.md) Q-1.

## 테스트 대역 — 층마다 다른 것을 본다 (Q-9)

| 용어 | 뜻 | 코드에서 |
|---|---|---|
| **능력 대역** (페이크) | 능력 인터페이스의 테스트 구현. 호출을 기록하고 **실패 모드를 재현**한다 | `Fake{능력이름}` + `@FakeAdapter` — 능력과 같은 패키지의 `src/test` |
| **`@ExternalAdapter`** | 「대외 시스템을 실제로 타는 빈」 표시. 테스트에서 **빠진다** | `com.ossagent.support` (src/main) |
| **`@FakeAdapter`** | 대역 표시. 테스트에서만 **뜨고**, 컴포넌트 스캔으로 **자동 등록**된다 | `com.ossagent.support.testing` (src/test) |
| **값 픽스처** | 값 객체를 만드는 static factory | `{타입}Fixtures` |
| **양성 대조** | 가드가 **0건을 검사하고 초록**이 되는 것을 막는 단언 | 「실제 빈을 찾았고 허용으로 판정했다」 |
| **물림** (bite) | 가드가 위반 표본을 **실제로 잡는가.** 「초록이다」는 「막혔다」가 아니라 「지금 위반이 없다」다 | 미끼 · 상시 양성 표본 |
| **돌연변이 검증** | 가드가 막는다는 것을 **일부러 제거해** 빨개지는지 보는 것. 안전 경계 가드는 필수 | 「무엇을 빼니 몇 건이 빨개졌다」를 PR 에 적는다 |
| **샘플의 대표성** | 그 표본이 **정말 그 규칙에 걸리는 모양인가.** 아니면 물림 단언이 있어도 공허하다 | `SecretPatternDriftTest` 의 「샘플이 스크립트 정규식에 물린다」 |
| **입력 도달** | 가드가 보는 입력 공간이 **실제 입력 공간과 같은가.** 대표성과 다르다 — 저쪽은 **표본**, 이쪽은 **덮개**다. 열거로 만든 가드는 열거에 없는 형태로 샌다 (#73) | `ApprovalGateArchitectureTest` 의 모수 단언 |
| **통합 테스트 진입점** | `@SpringBootTest` 대신 쓰는 합성 애노테이션 | `@AgentIntegrationTest` |

⚠ 이름에 **`Mock`·`Stub` 을 쓰지 않는다** — Mockito 의 mock 과 섞여 「무엇이 검증 대상인지」가 흐려진다.

⚠ 「페이크로 대체한다」가 **모든 층에 같은 뜻이 아니다.** 능력 층은 자체 페이크, 어댑터 매핑 층은
`MockRestServiceServer`, 전송 계약 층은 WireMock 이다. 한 단어로 뭉쳐 부르면 층이 하나 빠진다.

## 혼동 주의

| 쓰지 말 것 | 쓸 것 | 왜 |
|---|---|---|
| 「PR 을 올린다」 | 「Draft PR 을 만든다」 | 제출은 사람이 한다. 표현이 흐려지면 코드도 흐려진다 |
| 「저장소에 push」 | 「Fork 에 push」 | S-1 위반이 문장에서 시작된다 |
| 「테스트를 돌린다」 | 「샌드박스에서 테스트를 돌린다」 | S-3 |
| 「보류를 해소했다 = 허용했다」 | 「해소했다」 / 「허용으로 풀었다」 | 금지로 닫는 것도 해소다. 뭉개면 「해소 API」가 「허용 API」로 구현된다 (#24) |
| 「필터를 통과한 이슈」 | 「배제되지 않은 이슈」 | `UNDECIDED` 도 후보가 된다. 「통과」로 뭉개면 판정이 흐려진다 (#9) |
| 「에이전트」 단독 | 「코딩 에이전트」 / 「이 제품」 | 제품 전체와 내부 LLM 실행자가 같은 말이 된다 |
| 「시크릿을 막았다」 | 「**경로를 배제했다**」 / 「**내용을 스크럽했다**」 | 🔴 **순서가 다른 두 방어**이고 서로를 대신하지 않는다 (#15·#28). 경로 배제(`SecretFilePolicy`)는 키 파일을 **안 여는** 것이고, 스크럽(`TokenRedactor`)은 **연 파일**의 알려진 패턴을 가리는 것이다. 소스에 하드코딩된 토큰은 경로 정책을 **정상 통과**한다 |
