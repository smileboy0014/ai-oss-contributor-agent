# PLAN-23: Draft PR 생성 + PR 템플릿 적용 — 세 번째 승인 게이트

**이슈**: [#23](https://github.com/smileboy0014/ai-oss-contributor-agent/issues/23)
**type**: feature
**작성일**: 2026-09-27
**작성자**: smileboy0014

## 1. 요구사항

### 배경

#22 가 Fork 확보·동기화·commit·push 를 끝내 `PublishedBranch`(fork + 브랜치 + 커밋 sha)까지
만들었다. 그 값을 받아 **upstream 에 draft PR 을 여는 것**이 남았고, **여기서 자동화가 끝난다.**

세 가지가 한 PR 에 함께 들어가야 하는 이유가 있다.

| 함께 넣는 것 | 따로 넣으면 |
|---|---|
| `DraftPrPublisher` 능력 + GitHub 어댑터 | 호출자 없는 인터페이스가 또 하나 뜬다 |
| `POST /api/candidates/{id}/pull-request` (S-6 세 번째 게이트) | **PR 없이 종단 `PR_CREATED`** 가 만들어진다 — #24 가 열지 않은 이유 |
| `ContributionCandidate.markPrCreated(PullRequest, Clock)` | 불변식 ③(「`PR_CREATED` 인데 PR 행이 없다」)을 막을 자리가 없다. 엔티티 javadoc 이 **「#23 이 만든다」**고 예약해 뒀다 |

🔴 **`READY_FOR_PR` 에 도달시키는 실행기는 이 PR 의 범위가 아니다**(#18·#21).
이 PR 이 여는 문은 「이미 `READY_FOR_PR` 인 후보를 사람이 PR 로 내보내는」 문이고,
그 앞 상태가 아직 없다는 것이 **갇힘을 만들지는 않는다** — `implement` 와 다른 점이다.
그쪽은 `IMPLEMENTING` 에서 나갈 트리거가 없어 후보가 갇힌다. **그래서 `implement` 는 여전히 404 다.**

### 기능 요구사항 (FR)

| # | 요구사항 | 근거 |
|---|---------|------|
| FR-1 | `draft: true` **고정**. 설정·파라미터로 끌 수 없다 | 이슈 · S-2 |
| FR-2 | `ready_for_review`·리뷰어 지정·머지 호출 경로가 **존재하지 않는다** | S-2 |
| FR-3 | 대상 저장소의 `.github/PULL_REQUEST_TEMPLATE.md` 를 읽어 본문에 적용 | 이슈 · S-5 |
| FR-4 | PR 본문 — 변경 요약 · 이슈 참조 · 테스트 결과 | 이슈 · PRD §19 |
| FR-5 | **AI 가 생성했다는 사실을 본문에 밝힌다** | 이슈 |
| FR-6 | 대상 저장소에 글을 남기는 **다른 경로를 만들지 않는다**(이슈 코멘트 등) | S-2 |
| FR-7 | `pull_request` 영속화 · `READY_FOR_PR → PR_CREATED`(종단) | 이슈 |
| FR-8 | `POST /api/candidates/{id}/pull-request` 를 **이 PR 에서 연다.** `CandidateApprovalApiTest` 의 404 회귀를 함께 고친다 | 이슈(#24 이관) |
| FR-9 | 전이는 **PR 이 실제로 만들어진 뒤**에만 커밋된다 | 이슈(#24 이관) |
| FR-10 | 게이트 통과를 **커밋 확정 후** 로그로 남긴다. 거부는 예외를 던지기 전에 직접 남긴다 | 이슈(#24 이관) · `logging.md` |
| FR-11 | 불법 전이는 **409**. 「이미 그 상태니 성공」으로 200 을 돌려주지 않는다 | 이슈(#24 이관) |
| FR-12 | **활성 PR 이 이미 있으면 두 번째를 만들지 않는다** | `glossary.md` 「활성 PR 존재 → #11 입구 + #23」 · S-2 |
| FR-13 | PR 생성 직전 `RepositoryPolicy` 재확인 여부를 **판단해 적는다** | 이슈(#18 이관) · S-5 |

### 비기능 요구사항 (NFR)

| # | 항목 | 기준 |
|---|------|------|
| NFR-1 | GitHub 호출 | 후보 1건당 **최대 7 논리 호출** — Fork 조회 1 · 메타데이터 1 · 템플릿 후보 **3** · 활성 PR 조회 1 · PR 생성 1. 전송 재시도(`github.max-retries` 2)는 별개 축이다.<br>⚠️ 초안은 「5」였고 템플릿 후보를 2로 셌다 — 실제 `PolicyDocumentPath` 에는 **3개**다. 그리고 Fork 조회를 세지 않았다(아래 2-1) |
| NFR-2 | 트랜잭션 | 대외 호출은 **전부 트랜잭션 밖**. 읽기 트랜잭션 → 대외 → **짧은** 쓰기 트랜잭션 |
| NFR-3 | 비용 | **LLM 을 부르지 않는다.** 본문은 이미 있는 값의 조립이다 |
| NFR-4 | 멱등 | 같은 후보에 두 번 눌러도 PR 은 하나 — 전이표(409) + 활성 PR 조회 + `UNIQUE(candidate_id)` 3중 |
| NFR-5 | 설정 | **새 설정 키를 만들지 않는다.** draft 는 고정이고 기준 브랜치는 upstream 메타데이터에서 온다 |

## 2. 게이트 판정

### 안전 경계 (S-1 ~ S-6)

| 조항 | 접촉 | 어떻게 지키는가 |
|---|---|---|
| **S-1** 원본 저장소 쓰기 금지 | ✅ | 🔴 **이 PR 이 owner 어설션 면제를 하나 더 만든다.** `POST /repos/{upstream}/pulls` 는 원리적으로 upstream 좌표로 간다. `createFork` 와 같은 형태로 **`send` 를 직접 부르는 두 번째 메서드**가 되고, 근거를 §4 에 같은 형식의 표로 적는다. `subPath` 는 **리터럴** `pulls` 이고 파라미터가 아니다. Fork 쪽 쓰기는 **하나도 더하지 않는다** |
| **S-2** 항상 draft · 자동 머지 금지 | ✅ | `draft` 를 **필드가 아니라 리터럴 `true` 를 돌려주는 접근자**로 둔다. 「draft 아닌 PR」이 **표현 불가능**하다 — `PullRequest.Status` 단일값과 같은 수법. `ready_for_review`·리뷰어·머지 호출은 만들지 않고 `ForkPublishArchitectureTest.PR_호출이_없다_S2` 가 그대로 지킨다. 활성 PR 이 있으면 두 번째를 열지 않는다(FR-12) |
| **S-3** 샌드박스 밖 실행 금지 | — | 대상 저장소 코드를 실행하지 않는다. 검증 결과는 #19 가 만든 `GeneratedChange.testResult` 를 **읽기만** 한다 |
| **S-4** 시크릿 유출 금지 | ✅ | PR 본문은 **밖으로 나가는 텍스트**다. `PrBody` compact 생성자가 `TokenRedactor` 를 강제한다(`ScrubbedRules`·`SelectedFile` 선례). 재료 셋이 전부 위험하다 — 대상 저장소 템플릿 · 빌드 출력(`testResult`) · LLM 리뷰 텍스트(`reviewResult`). 로그에는 PR **번호·URL** 만 남기고 본문을 남기지 않는다 |
| **S-5** 대상 저장소 규약 우선 | ✅ | ① 템플릿을 읽어 본문 골격으로 쓴다 ② `ContributionConstraints.issueReferenceRequired` 가 참이면 이슈 참조를 **반드시** 넣는다 ③ **정책 재확인** — 아래 |
| **S-6** 승인 지점 우회 금지 | ✅ | 세 번째 게이트를 연다. `ApprovalGateArchitectureTest` 규칙 ①(허용목록: web 어댑터만)에 `CreateDraftPrUseCase` 를 **등록**하고 미끼로 물림을 확인한다. `PR_CREATED` 는 종단이고 나가는 전이를 만들지 않는다 |

#### FR-13 판단 — **PR 생성 직전에 정책을 다시 읽는다. 재분석은 하지 않는다**

#18 이 재시도 루프에서 통행증을 다시 받지 않기로 한 근거는 **비용**(바퀴마다 3배)이었고,
그 대가를 「#22·#23 이 막는다」로 적었다가 **그 방어가 존재하지 않는다**고 검토에서 잡힌 자리다.
이 계획은 그 빈자리를 실제로 채운다.

여기서는 비용 논거가 서지 않는다 — **PR 생성은 후보당 정확히 한 번**이라 재확인도 한 번이다.
그리고 되돌릴 수 없는 쪽이 명확하다: 규약 위반 PR 이 나가면 회수되지 않는다(S-5).

**그래서 `assertContributionAllowed(repositoryId)` 를 대외 호출 직전에 한 번 부른다.**

🔴 **재분석을 돌리지 않는다.** 저장된 판정을 다시 읽을 뿐이다 — #68 이 넣은 문서 지문 강등
(`TRUE` → `NULL`)이 그 사이에 일어났다면 여기서 걸린다. 재분석까지 하면 GitHub 호출이
후보 문서 9종만큼 늘고, **이 게이트가 질 이유가 없는 비용**이다.

### 미결 대조 (Q-1 ~ Q-11)

| 항목 | 걸리는가 | 처리 |
|---|---|---|
| Q-1 GitHub 연동 (classic PAT · `RestClient` 직접) | ✅ | 확정을 그대로 따른다. `GitHubWriteClient` 재사용 — 새 클라이언트를 만들지 않는다(`쓰기_표면은_한_곳이다_S1` 이 그것을 강제한다) |
| Q-2 Flyway | ✅ | `pull_request` 테이블은 **V2 에 이미 있다.** 새 마이그레이션 없음 |
| Q-5 승인 지점 | ✅ | 셋 중 **세 번째**를 연다. `implement`(#18)는 여전히 404 |
| Q-6 재시도 상한 | — | PR 생성은 루프 밖이다. `attempt` 를 건드리지 않는다 |
| Q-8 규약 판정 | ✅ | 위 FR-13. 「보류(`NULL`)는 허용이 아니다」가 여기서도 참이다 |
| Q-9 테스트 대역 | ✅ | 능력 페이크 / `MockRestServiceServer` 2층. 전송 계약 층은 **더하지 않는다** — 근거는 §6 |
| Q-11 경계마다 근거를 다시 본다 | — | 새 라이브러리를 들이지 않는다 |

**가정**

1. **템플릿 적용은 「골격을 이어 붙이는」 수준이다.** 템플릿의 체크박스를 우리가 채우지
   않는다 — 모르는 항목을 채우면 **거짓 진술**이 남의 저장소에 나간다. 틀리면 고칠 곳:
   `PrBody.compose`.
2. **기준 브랜치는 upstream 의 `default_branch`** 다(`RepositoryMetadata.defaultBranch`).
   #22 가 같은 값을 쓴다. 틀리면 고칠 곳: `CreateDraftPrUseCase`.

## 3. 스코프

| 도메인 | 변경 | 소유 판정 근거 |
|---|---|---|
| `pullrequest` | 신규 | Fork·브랜치·**Draft PR 메타데이터**가 이 도메인의 정의다(`project-overview.md`). 능력과 어댑터를 담고 **엔티티는 갖지 않는다** |
| `candidate` | 수정 | 상태머신의 주체이고 `PullRequest` 엔티티는 **후보 애그리거트의 멤버**다(`architecture.md` 규율 ④의 표) |
| `repository` | 수정(소폭) | 대상 저장소 **문서**를 읽는 주체다. 템플릿 조회를 여기에 둔다 — `candidate` 가 `RepositorySource` 를 직접 부르면 규율 ④ 위반 |
| `support`/`config` | 수정 | 쓰기 클라이언트 메서드 추가 · 빈 조립 |

### 도메인 간 계약

```java
// com.ossagent.pullrequest.domain
public interface DraftPrPublisher {

    /** 이미 열려 있는 PR 이 있는가 — S-2 · FR-12. 없으면 empty. */
    Optional<OpenedPullRequest> findOpen(RepositoryCoordinates upstream, PublishedBranch head);

    /** 🔴 draft 로만 연다. 인자에 draft 가 없다. */
    OpenedPullRequest openDraft(DraftPrRequest request);
}
```

```java
// com.ossagent.repository.application — AnalyzeRepositoryPolicyUseCase
/** 🔴 「없다」(404)와 「못 읽었다」를 가른다. 못 읽으면 예외다 — S-5. */
Optional<String> findPullRequestTemplate(Long repositoryId);
```

- 선언: `com.ossagent.pullrequest.domain.DraftPrPublisher`
- 구현: `com.ossagent.pullrequest.adapter.out.github.GitHubDraftPrPublisher`

🔴 **`openDraft` 는 「PR 을 만든다」만 한다.** 상태 전이도 영속화도 하지 않는다 —
`ForkPublisher` 가 PR 을 만들지 않은 것과 같은 이유로, 능력을 합치면 게이트가 부산물이 된다.

## 4. 기술 설계

### 변경 파일

| # | 경로 | 레이어 | 신규/수정 | 내용 |
|---|------|--------|----------|------|
| 1 | `pullrequest/domain/DraftPrPublisher.java` | domain | 신규 | 능력 선언 |
| 2 | `pullrequest/domain/DraftPrRequest.java` | domain | 신규 | 값. **`draft` 파라미터가 없다** |
| 3 | `pullrequest/domain/OpenedPullRequest.java` | domain | 신규 | 결과 값 — 번호 · URL · headRef |
| 4 | `pullrequest/domain/PrTitle.java` | domain | 신규 | 값. 길이 상한 + 스크럽 |
| 5 | `pullrequest/domain/PrBody.java` | domain | 신규 | 🔴 compact 생성자가 **스크럽 강제**(S-4) + `compose(...)` 조립 |
| 6 | `pullrequest/domain/DraftPrException.java` | domain | 신규 | 생성 실패 |
| 7 | `pullrequest/adapter/out/github/GitHubDraftPrPublisher.java` | adapter/out | 신규 | 읽기=`GitHubApiClient` · 쓰기=`GitHubWriteClient` |
| 8 | `pullrequest/adapter/out/github/GitDataPayloads.java` | adapter/out | 수정 | `DraftPullRequestPayload` — `draft()` 가 **리터럴 `true`** |
| 9 | `pullrequest/adapter/out/github/GitHubWriteClient.java` | adapter/out | 수정 | `createDraftPullRequest(owner, name, payload)` — 어설션 면제 **2번째** |
| 10 | `candidate/domain/PullRequest.java` | domain | 수정 | `draftFor(...)` 정적 팩토리 = **유일한 생성 경로** |
| 11 | `candidate/domain/ContributionCandidate.java` | domain | 수정 | `markPrCreated(PullRequest, Clock)` — 양방향 attach + 불변식 ③ |
| 12 | `candidate/application/CreateDraftPrUseCase.java` | application | 신규 | 🔴 **세 번째 게이트.** 트랜잭션 3단 분할 |
| 13 | `candidate/adapter/out/persistence/PullRequestRepository.java` | adapter/out | 신규 | `findByCandidateId` |
| 14 | `candidate/adapter/in/web/CandidateController.java` | adapter/in | 수정 | `POST /{id}/pull-request` |
| 15 | `candidate/adapter/in/web/dto/DraftPrResponse.java` | adapter/in | 신규 | 응답 |
| 16 | `repository/application/AnalyzeRepositoryPolicyUseCase.java` | application | 수정 | `findPullRequestTemplate(repositoryId)` |
| 17 | `config/ForkPublishConfig.java` | config | 수정 | `DraftPrPublisher` 빈 |

### 🔴 S-1 어설션 면제를 하나 더 만드는 근거

`createFork` 의 판정표와 **같은 형식**으로 적는다. 셋 중 하나라도 다르면 면제를 주지 않는다.

| 물음 | `createFork` | **`createDraftPullRequest`** |
|---|---|---|
| upstream 히스토리를 바꾸나 | ❌ 내 계정에 저장소를 만든다 | ❌ **커밋·브랜치·태그를 하나도 건드리지 않는다.** PR 은 「내 Fork 브랜치를 봐 달라」는 제안이고 머지는 메인테이너가 한다 |
| 우회 경로가 되나 | ❌ `subPath` 가 리터럴 | ❌ `subPath` 가 리터럴 `pulls` 이고, payload 타입이 `draft` 를 **상수로** 든다 |
| 되돌릴 수 있나 | ✅ Fork 를 지운다 | 🔴 **부분적으로만.** PR 은 닫을 수 있으나 **메일 알림은 회수되지 않는다** |

세 번째가 다르다. 그래서 이 메서드는 **사람 승인 게이트 뒤에만** 놓이고,
그 사실을 `ApprovalGateArchitectureTest` 가 구조로 고정한다. **면제보다 게이트가 본체다.**

🔴 **시그니처는 `String owner, String name` 이 아니라 `RepositoryCoordinates` 를 받는다.**
`createFork` 가 자유 문자열 둘을 받는 것을 그대로 따라 하면 「아무 좌표나 받는 공개 쓰기
메서드」가 하나 더 생긴다. 값 타입은 owner·name 을 `[A-Za-z0-9._-]+` 로 제한하므로
경로 조립 면이 함께 좁아진다.

#### 🔴 `ForkPublishArchitectureTest` 는 **세 곳**이 바뀐다 — 초안은 하나만 봤다

| 가드 | 초안 | 실제 |
|---|---|---|
| `어설션_우회는_fork_생성뿐이다_S1` | ✅ 봤다 | 목록이 둘로 — `createFork`·`createDraftPullRequest` |
| **`upstream_쓰기는_forks_뿐이다_S1`** | ❌ **못 봤다** | 🔴 **이것도 반드시 깨진다.** 「`ForkRef` 를 받지 않고 `send` 를 부르는 public 메서드」를 세는데 새 메서드가 정확히 그 모양이다. **이 집합이 둘이 되는 것이 S-1 약화의 본체**이고, 초안은 그것을 언급조차 하지 않았다 |
| **`쓰기_엔드포인트가_화이트리스트_안이다_S2`** | 「그대로 둔다」 | 🔴 **주장은 맞았지만 이유가 틀렸고 결론이 위험하다** — 아래 |
| `쓰기_표면은_한_곳이다_S1` | — | 바뀌지 않는다. 새 어댑터는 `GitHubWriteClient` 를 통해서만 쓴다 |

🔴 **화이트리스트 가드가 새 어댑터 파일을 아예 보지 않는다 — 요구 4(입력 도달).**
그 가드는 정규식 이전에 **파일 경로가 하드코딩**돼 있다
(`Path.of(".../GitHubForkPublisher.java")`). 새로 만드는 `GitHubDraftPrPublisher.java` 는
**가드의 입력 공간 밖**이고, 거기에 `post(fork, "issues/12/comments", …)` 를 써도
**보지 않는다.** 그것이 FR-6(「대상 저장소에 글을 남기는 다른 경로를 만들지 않는다」)의
유일한 잠재 방어였다.

**그래서 경로를 파일 하나가 아니라 `pullrequest/adapter/out/github/` **디렉터리 전체**로
넓힌다.** 그러면 새 어댑터도, 앞으로 생길 어댑터도 기본이 검사 대상이다.
⚠️ 넓히면서 **모수 단언을 함께 올린다** — 훑은 파일 수가 0이면 경로가 틀린 것이다.

⚠️ `ALLOWED_SUB_PATHS` 에 `pulls` 를 넣지 않는 것은 맞다(내부 상수라 정규식에 안 잡힌다).
**다만 그 유비가 참이라는 것은 「어설션 면제 경로는 이 가드에 원리적으로 안 잡힌다」는
뜻이기도 하다** — `forks` 는 이미 화이트리스트의 죽은 줄이다. 그 사실을 테스트 주석에 적는다.

⚠️ **두 테스트의 이름이 거짓이 된다.** `어설션_우회는_fork_생성뿐이다_S1` ·
`upstream_쓰기는_forks_뿐이다_S1` — 이름을 함께 고친다. 단언 메시지만 고치면 다음 사람의
판단 근거가 오염된다.

#### 🔴 `ApprovalGateArchitectureTest` 는 **규칙 ①만으로 부족하다**

| 규칙 | 해야 할 일 |
|---|---|
| ① 허용목록(web 만) | `CreateDraftPrUseCase` 등록 + 🔴 **새 미끼**. 기존 양성 대조는 `AutoSelectProbe` 문자열에 고정돼 있어 **새 타입을 빠뜨려도 초록**이다 |
| **①b 전이 메서드 직접 호출 금지** | 🔴 **초안에 없었다.** `사람이_부르는_전이` 에 **`markPrCreated` 를 더한다.** 없으면 자동 실행자가 후보를 직접 꺼내 전이시키는 경로가 무방비다 — ①은 **타입**을 지목하므로 그것을 못 잡는다 |
| ② 증거 필드 | PR 게이트의 증거는 `selectedAt` 이 아니라 **`PullRequest` 행**이다. `PullRequest.draftFor` 를 부르는 것이 `CandidatePrWriter` 하나임을 같은 형식으로 고정한다 |
| 모수 | `CreateDraftPrUseCase` 를 모수 단언에 더한다 |

### 본문 조립 — `PrBody.compose`

```
{대상 저장소 PULL_REQUEST_TEMPLATE.md 가 있으면 그대로}      ← 체크박스를 채우지 않는다

### Summary
{이슈 제목 · 무엇을 바꿨나}

### Related issue
{issueReferenceRequired 면 반드시 · 자동 닫기 키워드는 붙이지 않는다 — 대상 저장소 규약이 정한다}

### Verification
{GeneratedChange.testResult 요약 — 단계별 PASSED/FAILED/UNDETERMINED}

### AI-generated
This pull request was prepared by an automated agent and reviewed by a human before
submission. See the verification section above for what was actually run.
```

🔴 **전체가 `TokenRedactor` 를 통과한 뒤에야 `PrBody` 값이 된다.**
⚠️ 자동 닫기 키워드(`Fixes`·`Closes`)를 임의로 붙이지 않는다 — #22 가 커밋 메시지에서
같은 판단을 했다. 메인테이너가 닫을지는 그쪽이 정한다.

### 체크리스트 답변

| # | 항목 | 답 |
|---|------|---|
| 1 | 소유 도메인 | 능력·어댑터 = `pullrequest` · 엔티티·상태 = `candidate` · 대상 저장소 문서 = `repository` |
| 2 | 레이어 배치 | 결정 트리 Q2(Repository·능력 필요) → UseCase · Q5(GitHub) → adapter/out |
| 3 | 능력 인터페이스 | 필요 — `DraftPrPublisher` (`architecture.md` 규율 ③ 이 이름으로 예시를 들어 둔 그것) |
| 4 | 🔴 트랜잭션 안 대외 호출 없음 | ✅ 3단 분할 — ①읽기 tx ②대외(tx 없음) ③쓰기 tx. `@Transactional` 을 UseCase 진입 메서드에 걸지 않는다 |
| 5 | 상태 전이 영향 | `READY_FOR_PR → PR_CREATED` 만. **새 전이를 추가하지 않는다.** 종단에서 나가는 전이 없음 |
| 6 | 멱등성 | 3중 — 전이표(두 번째 호출은 409) · 활성 PR 조회 · `UNIQUE(candidate_id)` |
| 7 | `Clock` 주입 | ✅ 전이·`createdAt` 전부 |
| 8 | 🔴 안전 경계 | §2 |

### 데이터 모델

**새 마이그레이션 없음.** `pull_request` 는 `V2__create_pipeline_tables.sql` 에 이미 있고
`CHECK (status = 'DRAFT')` 도 걸려 있다. 엔티티에 **컬럼을 더하지 않는다.**

### API 계약

```http
POST /api/candidates/{id}/pull-request
```

요청 바디 **없음** — `select` 와 같은 이유다. 바디를 받기 시작하면 「어떤 제목으로」 같은 것이
흘러들어와 게이트가 파라미터화된다.

| | 필드 | 타입 | 설명 |
|---|---|---|---|
| 응답 | `candidateId` | number | |
| | `prNumber` | number | upstream PR 번호 |
| | `prUrl` | string | |
| | `from` / `to` | string | `READY_FOR_PR` / `PR_CREATED` |
| | `terminal` | boolean | 항상 `true` |

| 상태 | 조건 |
|---|---|
| 200 | 생성 성공 · **또는 이미 열려 있던 PR 을 붙였다**(FR-12 — 아래 경계) |
| 404 | 없는 후보 (`CandidateNotFoundException`) |
| **403** | AI 기여 금지·보류 (`ContributionNotAllowedException`) — S-5 |
| 409 | `READY_FOR_PR` 이 아니다 (`CandidateTransitionException`) · 낙관적 잠금 충돌 |
| 500 | GitHub 호출 실패 |

⚠️ **403·500 은 「이 PR 이 정하는 것」이 아니라 이미 있는 매핑이다.** 초안은 S-5 거부를
409, GitHub 실패를 502 로 적었는데 **둘 다 틀렸다** — `ApiExceptionHandler` 가
`ContributionNotAllowedException` 을 **403** 으로 매핑하고 javadoc 이 「404 도 409 도 아니다」를
명시적으로 배제해 뒀으며, GitHub 예외 핸들러는 **없다**(기본 500).
🔴 **이 PR 에서 그 매핑을 바꾸지 않는다** — 공유 예외라 `select`·`scan` 의 동작까지 함께 변한다.

#### FR-12 의 200 과 「승인은 멱등이 아니다」의 경계

둘은 겹치지 않는다. **무엇이 이미 있느냐가 다르다.**

| 상황 | 후보 상태 | 결과 |
|---|---|---|
| 두 번째 승인 (사람이 또 눌렀다) | `PR_CREATED` (종단) | 🔴 **409** — 전이표에 그 전이가 없다. 「사람이 한 번 승인했다」는 그대로 한 번이다 |
| 첫 승인인데 upstream 에 PR 이 이미 있다 | `READY_FOR_PR` | ✅ 200 — **새로 만들지 않고 그것을 붙인다**(리스크 5 복구). 남의 저장소에 두 번째 PR 을 열지 않는 것이 S-2 다 |

## 5. 구현 순서

### 실행 모드: sequential

**판정 근거** — Stage 2 가 Stage 1 의 값 타입을 import 하고 Stage 5 가 1~4 를 모두 쓴다.
수정 파일도 겹친다(`GitHubWriteClient` ↔ `ForkPublishArchitectureTest`).

| Stage | 내용 | 선행 | 파일 |
|-------|------|------|------|
| 1 | `pullrequest` 값·능력 | 없음 | 1~6 |
| 2 | GitHub 어댑터 + 쓰기 클라이언트 면제 + 아키텍처 테스트 갱신 | 1 | 7~9 |
| 3 | `candidate` 엔티티 — `PullRequest.draftFor` · `markPrCreated(PullRequest, Clock)` | 없음 | 10~11 |
| 4 | `repository` 템플릿 조회 | 없음 | 16 |
| 5 | `CreateDraftPrUseCase` + 영속 어댑터 | 1~4 | 12~13 |
| 6 | web 표면 + 승인 게이트 아키텍처 테스트 + 404 회귀 교체 | 5 | 14~15 · 테스트 |
| 7 | 빈 조립 · 문서 동기화 | 6 | 17 · codemaps · glossary · safety-boundaries |

## 6. 테스트 계획

| # | 레벨 | 대상 | 검증 내용 |
|---|------|------|----------|
| T-1 | 유닛 | `PrBody` | 🔴 토큰·PEM 이 **본문에 남지 않는다**(S-4). 템플릿·테스트출력·리뷰텍스트 **세 재료 모두** |
| T-2 | 유닛 | `DraftPullRequestPayload` | 🔴 **직렬화 결과에 `"draft":true` 가 있고, false 로 만들 생성 경로가 없다**(S-2) |
| T-3 | 유닛 | `ContributionCandidate.markPrCreated` | PR 없이 부르면 거부 · 두 번째 호출은 전이 예외 · 종단에서 나가는 전이 없음 |
| T-4 | 유닛 | `PullRequest.draftFor` | `status` 가 `DRAFT` 하나뿐 · 후보 역방향이 함께 채워진다 |
| T-5 | 유닛 | `CreateDraftPrUseCase` (페이크) | `READY_FOR_PR` 아니면 전이 예외 · **AI 기여 보류면 중단**(S-5) · 활성 PR 있으면 **새로 만들지 않는다**(FR-12) · **대외 실패 시 전이가 커밋되지 않는다**(FR-9) |
| T-6 | 어댑터 | `GitHubDraftPrPublisher` + `MockRestServiceServer` | 요청 본문에 `draft:true` · `head` 가 `owner:branch` · 오류 변환 |
| T-7 | 통합 | `CandidateApprovalApiTest` | `pull-request` 200/409/404 · 🔴 **`implement` 는 여전히 404** |
| T-8 | 아키텍처 | `ForkPublishArchitectureTest` | 면제 목록이 **정확히 둘** · 화이트리스트 스캔이 **디렉터리 전체**를 훑는다(🔴 모수: 훑은 파일 ≥ 2) · **`draft` 를 false 로 만들 생성자·세터가 없다** — 🔴 **모수(「`draft` 접근자를 실제로 찾았다」)와 물림(「`draft` 파라미터를 가진 표본을 문다」)을 함께 단언** |
| T-9 | 아키텍처 | `ApprovalGateArchitectureTest` | `CreateDraftPrUseCase` 를 **web 어댑터만** 부른다 + 🔴 **새 미끼**(`AutoPrProbe`) · ①b 에 `markPrCreated` · `draftFor` 호출자 고정 · 모수 |
| T-10 | 유닛 | `CandidatePrWriter` 로깅 | FR-10 — 통과는 **커밋 뒤**에, 거부는 **예외 전**에 남는가 |
| T-11 | 어댑터 | `GitHubDraftPrPublisher` | FR-6 — 대상 저장소에 <b>PR 말고 다른 것을 쓰는 경로가 없다</b>(위 화이트리스트가 이 파일을 훑는다) |

**대외 호출 대체** (Q-9)

| 층 | 대역 |
|---|---|
| 능력 소비자(UseCase) | `FakeDraftPrPublisher` + `@FakeAdapter` — 실패 모드(이미 열림 · 생성 실패)를 재현 |
| 어댑터 매핑 | `MockRestServiceServer` |
| 전송 계약 | **더하지 않는다** — 같은 `RestClient`·같은 `GitHubWriteClient` 전송 경로를 #22 의 WireMock 테스트가 이미 덮는다. 새 층이 아니라 **같은 층을 두 번 재는 것**이라 근거를 여기 남긴다 |

🔴 **돌연변이 검증 대상**(안전 경계 가드) — T-2 · T-8 · T-9.
「무엇을 빼니 몇 건이 빨개졌다」를 PR 본문에 숫자로 적는다.

## 7. 리스크

| # | 리스크 | 영향 | 대응 |
|---|--------|------|------|
| 1 | 🔴 **면제가 둘로 늘면서 S-1 가드가 약해진다** | upstream 쓰기 경로가 또 생긴다 | 면제 근거표를 javadoc 에 박고, T-8 이 **정확히 둘**을 단언. 셋이 되면 빨개진다 |
| 2 | 🔴 Jackson 이 record 의 **비컴포넌트 접근자**를 직렬화하지 않으면 `draft` 가 누락돼 **non-draft PR** 이 된다 | S-2 정면 위반 | T-2 가 **직렬화 결과 문자열**을 단언한다. 「될 것이다」로 두지 않는다 |
| 3 | 템플릿을 못 읽었는데 「없다」로 처리 | 규약 위반 본문 | `fetchFile` 계약 그대로 — `Optional.empty()` 는 **404 하나뿐**. 예외는 예외로 올라오고, 템플릿 조회 실패는 **PR 생성을 중단**시킨다(S-5) |
| 4 | 전이는 커밋됐는데 PR 생성이 실패 | PR 없는 종단 `PR_CREATED` | 순서를 뒤집는다 — **대외 호출 성공 후에만** 쓰기 트랜잭션을 연다(FR-9) |
| 5 | PR 은 만들어졌는데 DB 쓰기가 실패 | PR 은 있고 기록이 없다 | 🔴 **되돌리지 않는다**(PR 을 닫지 않는다 — 알림은 회수 불가). `ERROR` 로 번호·URL 을 남겨 사람이 잇게 한다. 다음 호출은 FR-12 가 **같은 PR 을 찾아 붙인다** |

**대외 호출 실패 시나리오**

| 시나리오 | 기대 동작 |
|---|---|
| 레이트리밋 임계 미만 | `GitHubRateLimitException` — **지연**이지 실패가 아니다. 전이하지 않는다 |
| 403(2차 리밋) | `GitHubErrorTranslator` 가 이미 가른다. 권한 오류로 처리하지 않는다 |
| PR 생성 422 (`head` 없음 · 이미 PR 있음) | 🔴 **FR-12 조회로 먼저 가른다.** 그래도 422 면 전이하지 않고 실패 |
| 읽기 타임아웃 | `UNSAFE` 라 **재전송하지 않는다**(#22 결정). `CancellationException` 도 번역된다 |

## 8. 복잡도

| Stage | 파일 수 | 복잡도 |
|-------|--------|--------|
| 1 | 6 | 중간 |
| 2 | 3 | **높음** — S-1 면제 |
| 3 | 2 | 중간 |
| 4 | 1 | 낮음 |
| 5 | 2 | **높음** — 트랜잭션 3단 분할 |
| 6 | 2 + 테스트 | 중간 |
| 7 | 1 + 문서 | 낮음 |

## 9. 문서 동기화 대상

| 대상 | 필요 | 이유 |
|---|---|---|
| `codemaps/architecture.md` | ✅ | `DraftPrPublisher` 능력 추가 |
| `codemaps/data.md` | ✅ | `pull_request` 가 **처음으로 쓰인다** — 「미사용」 표기가 있으면 고친다 |
| `codemaps/domain.md` | ✅ | 세 번째 게이트 · `markPrCreated` 시그니처 · 불변식 ③ |
| `.env.example` | — | 새 환경변수 없음 (NFR-5) |
| `README.md` | — | 디렉토리 구조 무변경 |
| `rules/context/glossary.md` | ✅ | `DraftPrPublisher`·`OpenedPullRequest`·`PrBody` 등록 |
| `rules/context/safety-boundaries.md` | ✅ | S-6 표의 「PR 생성 ⬜ #23」 → ✅ · S-1 에 면제 2번째 명시 |
| `rules/context/external-deps.md` | ✅ | 「유일한 예외 `createFork`」가 둘이 된다 |
| `rules/context/open-questions.md` | ✅ | Q-5 의 「셋 중 하나만 열었다」 갱신 |

## 10. 계획 검토에서 고친 것 (2026-09-27)

격리 서브에이전트 검토에서 **중대 7 · 보통 12** 가 나왔다. 위 절에 이미 반영했고,
여기에는 **왜 그렇게 고쳤는가**만 남긴다. 고치지 않기로 한 것도 적는다.

| # | 지적 | 처리 |
|---|---|---|
| 2-1 🔴 | `PublishedBranch` 의 **fork 좌표를 어디서 얻는지**가 계획에 없었다. `GeneratedChange` 에는 `branchName`·`commitSha` 만 있고, 이름을 조립하면 GitHub 이 만든 `{name}-1` 을 영원히 못 찾는다 | **`ForkPublisher.ensureFork(upstream)` 로 얻는다.** #22 가 그 함정을 이미 처리했고 「같은 이름의 무관한 저장소」까지 가른다. 호출 1회가 NFR-1 에 더해졌다 |
| 2-2 🔴 | S-5 거부를 409 로 적었으나 실제 매핑은 **403** | API 표를 고쳤다. 🔴 **공유 예외라 매핑을 바꾸지 않는다** |
| 4b-1 🔴 | `upstream_쓰기는_forks_뿐이다_S1` 이 함께 깨지는 것을 못 봤다 | §4 에 표로 명시. 시그니처를 `RepositoryCoordinates` 로 좁혀 **좌표 면**도 함께 줄였다 |
| 4b-2 🔴 | 화이트리스트 가드가 **파일 경로 하드코딩**이라 새 어댑터를 안 본다 | **디렉터리 전체 스캔 + 모수 단언.** 이 지적이 이번 검토의 최대 수확이다 — 「그대로 둔다」가 맞는 문장이었는데 **이유가 틀렸고 결론이 위험**했다 |
| 4c-1 🔴 | 규칙 ① 양성 대조가 `AutoSelectProbe` 문자열에 고정 — 새 타입을 빠뜨려도 초록 | **새 미끼** 추가 |
| 4c-2 🔴 | 규칙 ①b 에 `markPrCreated` 가 없다 | 추가. ①이 타입을 지목하므로 엔티티 직접 호출을 못 잡는다 |
| 4d-2 🔴 | T-8·T-2 의 「…가 없다」가 **0건 부재 검사** | 모수·물림 단언을 요구로 못 박았다. 돌연변이는 1회성이라 **회귀의 대체가 아니다** |
| 1-1 | FR-6 에 검증 수단이 없었다 | 4b-2 수정이 그대로 방어가 된다(T-11) |
| 1-2 | 「설정을 타지 않는지」의 뒷절반이 회귀로 없다 | T-8 이 **`draft` 이름의 파라미터·필드·설정 키 부재**를 함께 본다 |
| 1-3 | FR-10 로깅에 테스트가 없다 | T-10 신설 |
| 1-4 | `@ExternalText` 등록이 §9 에 없다 | 추가 — `PrTitle.value`·`PrBody.value`·`PrBodyMaterials` 3필드 |
| 1-5 | 안전 게이트 **메트릭**이 없다 | `SafetyClause.S2` 를 추가하고 게이트에 계측한다 |
| 2-3 | 502 매핑은 **존재하지 않는다** | 표를 500 으로 정정. 없는 것을 있다고 적지 않는다 |
| 2-4 | 템플릿 후보는 2가 아니라 **3**이고, 규약 분석이 이미 같은 셋을 수집한다 | NFR-1 정정. 🔴 **중복 조회를 합치지 않는다** — 규약 분석은 저장소 등록 시점 1회이고 그 내용을 영속화하지 않는다. 합치려면 스키마가 필요하고 그것은 이 PR 의 범위 밖이다. **비용 +3 호출을 알고 치른다** |
| 2-5 | FR-12 의 200 과 「승인은 멱등이 아니다」의 경계 | API 절에 표로 그었다 — 두 번째 **승인**은 여전히 409 |
| 3-2 | Q-4 대조가 빠졌다. `testResult` 를 「검증 결과」로 남의 PR 에 싣는 것은 미결을 밖으로 내보내는 것이다 | **싣되 무엇을 돌렸는지로만 쓴다.** 「테스트가 통과했다」가 아니라 「이 명령을 이 환경에서 돌렸고 결과가 이것이다」로 적는다 — 아래 가정 3 |
| 4a-2 | 시그니처가 `String` 둘이라 면제 면이 넓다 | `RepositoryCoordinates` 로 좁혔다 |
| 4c-3 | PR 게이트의 **증거 필드** 가드가 없다 | `draftFor` 호출자를 `CandidatePrWriter` 하나로 고정(규칙 ② 형식) |
| 4c-4 | 모수 단언 누락 | 추가 |
| 4d-4 | 「#22 의 WireMock 이 같은 경로를 덮는다」가 **고정돼 있지 않다** | `어설션_우회…` 테스트가 이미 「`send` 를 부른다」를 단언하므로 그것이 고정이다. 주석에 명시 |
| 4d-5 | 텍스트로 소스를 읽는 가드는 주석 변경에서 `UP-TO-DATE` 로 건너뛴다 | `build.gradle.kts` 의 `Test` 입력에 해당 소스 디렉터리를 선언한다 |

**가정 3 추가** — PR 본문의 `### Verification` 은 **판정이 아니라 실행 기록**이다.
Q-4 가 「실행 단계 `network=none` 이라 정상 코드인데 테스트가 실패할 수 있고 그 판정이
옳지 않다」를 열어 둔 상태이므로, 그 값을 「통과/실패」로 단정해 남의 저장소에 적지 않는다.
틀리면 고칠 곳: `PrBody.compose` 의 `### Verification` 절.

## 11. 변경 이력

| 일자 | 작성자 | 변경 내용 |
|------|--------|----------|
| 2026-09-27 | smileboy0014 | 초안 생성 |
| 2026-09-27 | smileboy0014 | 계획 검토 반영 — 가드 3곳이 함께 바뀐다는 것과 화이트리스트 입력 도달 구멍을 §4·§6·§10 에 반영 |
