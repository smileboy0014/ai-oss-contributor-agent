# PLAN-22: Fork 확보 · 브랜치 · commit · push — owner 어설션이 유일한 방어

**이슈**: [#22](https://github.com/smileboy0014/ai-oss-contributor-agent/issues/22)
**type**: feature
**작성일**: 2026-09-27
**작성자**: smileboy0014

---

## 1. 요구사항

### 배경

검증을 통과한 변경분을 **사용자 Fork 에** 올린다. 원본(upstream)으로 가는 경로는 읽기만 존재한다.

```
원본 저장소 ──fork──▶ 사용자 Fork ──▶ 브랜치 ──▶ commit ──▶ push
   (읽기만)              (유일한 쓰기 대상)
```

**이 이슈는 S-1 의 실행체다.** 지금까지 S-1 은 문서에만 있었다 — 이 저장소에 **쓰기 경로가
아예 없었기 때문**이다(`GitHubApiClient` 의 공개 메서드는 `get(GitHubRequest)` 하나뿐이다).
이 PR 이 그 표면을 여는 첫 PR 이고, 그래서 **여는 방식 자체가 이 이슈의 본체**다.

### 🔴 이슈 완료 조건 하나를 폐기한다 — 근거가 이미 뒤집혔다

> - [ ] 토큰 권한에 원본 write 가 없는지 기동 시 확인 (**권한 미부여가 1차 방어**)

**이 줄은 구현하지 않는다.** 이슈 본문이 Q-1 확정(2026-09-21) **이전**에 쓰였고,
그 확정이 이 전제를 정면으로 뒤집었다.

| 방어 | 상태 | 근거 |
|---|---|---|
| ~~토큰 권한 미부여~~ | ❌ **불가능** | classic PAT `public_repo` 는 **저장소별 권한 제한이 불가능**하다. 「원본에는 write 를 주지 않는 토큰」이 GitHub 에 존재하지 않는다 |
| 읽기 전용 코드 표면 | 🔵 **보조일 뿐** | `spring-boot-starter-web` 이 `RestClient.Builder` 를 자동설정 빈으로 올린다 — 아무 컴포넌트나 `builder.build().post(...)` 를 할 수 있다 |
| **쓰기 직전 owner 어설션** | ✅ **유일한 방어** | `safety-boundaries.md` S-1 (2026-09-21 개정) |

⚠️ 「기동 시 토큰 스코프를 조회해 원본 write 가 없음을 확인」은 **원리적으로 성립하지 않는다.**
`public_repo` 스코프는 *모든* 공개 저장소에 write 를 준다 — 대상 저장소에 우리가 collaborator 가
아니면 실제 권한이 없을 뿐이고, **그것은 상대의 설정이지 우리 토큰의 성질이 아니다.**
「없는 권한」에 기대는 방어를 코드로 적으면 **거짓 안전감만 남는다.**

🔴 그리고 **잔여 위험이 실재한다** — 사용자가 **collaborator 인 공개 저장소**를 대상으로
등록하면 토큰은 그 저장소에 진짜로 write 권한을 갖는다. 그때 어설션이 없으면 **실제로
upstream 에 push 된다.** 이 PR 의 어설션은 「있으면 좋은 것」이 아니라 **없으면 반려**다.

### 기능 요구사항 (FR)

| # | 요구사항 | 근거 |
|---|---------|------|
| FR-1 | Fork 확보 — 없으면 생성, 🔴 **있으면 「정말 이 upstream 의 fork 인지」 확인 후** 재사용 | 이슈 · PRD §18 · D-4a |
| FR-2 | Fork 의 대상 브랜치를 upstream 과 동기화 | 이슈 |
| FR-3 | 브랜치명 `oss-agent/issue-{issueNumber}-{short-description}` | PRD §14 · `codemaps/domain.md` |
| FR-4 | 커밋 메시지는 **대상 저장소 규약**을 따른다 — sign-off · 이슈 참조를 `ContributionConstraints` 에서 | 이슈 · **S-5** |
| FR-5 | 🔴 **쓰기 직전 owner 어설션** — Fork owner 와 다르면 예외로 중단 | 이슈 · **S-1** |
| FR-6 | force 갱신 · 브랜치 삭제도 Fork 안에서만 | 이슈 · S-1 |
| FR-7 | 재시도(같은 후보 재publish)가 **중복 브랜치·중복 커밋을 만들지 않는다** | 불변식 ⑨ · `data.md` 멱등키 |
| ~~FR-8~~ | ~~토큰 권한 기동 검증~~ | ❌ **폐기** — 위 |

### 비기능 요구사항 (NFR)

| # | 항목 | 기준 |
|---|------|------|
| NFR-1 | 레이트리밋 | 🔴 **곱이 걸리는 구간과 안 걸리는 구간을 갈라 센다** — D-4d. 아래 |
| NFR-2 | 타임아웃 | 기존 `github.connect-timeout: 5s` · `read-timeout: 10s` 를 그대로 쓴다. ⚠️ **Fork 생성은 비동기다** — 아래 R-2 |
| NFR-3 | 비용 | LLM 호출 **없음**. 이 단계는 GitHub API 만 탄다 |
| NFR-4 | 트랜잭션 | 대외 호출이 전부 트랜잭션 **밖**이다 — 이 PR 은 트랜잭션을 열지 않는다(영속화 호출자 몫) |

#### NFR-1 상세 — 후보 1건을 Fork 에 올리는 데 드는 논리 호출

`max-planned-files: 8`(실측 — `application.yml:202`) 기준. 전송 재시도는 `github.max-retries: 2`
이므로 **곱은 3**(첫 시도 + 재시도 2)이고, **D-4d 가 허용한 구간에만 걸린다.**

| 구간 | 호출 | 곱 | 소계 |
|---|---|---|---|
| Fork 확보 조회 | `GET /repos/{fork}/{name}` 1 | ×3 | 3 |
| publish 읽기 | `GET git/ref` 1 · `GET git/commits` 1 | ×3 | 6 |
| publish 쓰기 (`SAFE`) | `POST git/blobs` 8 · `git/trees` 1 · `git/commits` 1 | ×3 | 30 |
| 🔴 쓰기 (`UNSAFE`) | `POST/PATCH git/refs` 1 · `POST forks` 1 · `POST merge-upstream` 1 | **×1** | 3 |
| **합계** | | | **≈ 42** |

⚠️ Fork 를 처음 만들 때의 **준비 폴링**은 별도다 — `github.fork.ready-timeout` 이 상한이고
그 안에서 `GET` 을 반복한다. 무한이 아니라는 것이 요점이고, 정확한 횟수는 간격 설정에 달렸다.

🔴 **한 축만 보고 세지 않는다.** 이 42 앞에 #15 의 저장소 분석(최대 26 논리 호출 × 3)과
#16 의 계획 LLM 호출(최대 6)이 붙는다 — `architecture.md` §4 의 「세 구간의 합」이다.

---

## 2. 게이트 판정

### 안전 경계 (S-1 ~ S-6)

| 조항 | 접촉 | 어떻게 지키는가 |
|---|---|---|
| **S-1** 원본 저장소 쓰기 금지 | 🔴 **이 이슈가 실행체다** | **두 겹.** ① `ForkRef` 값 타입 — 생성 시점에 `owner == github.fork-owner` 단언, 쓰기 API 는 이 타입으로만 호출 가능 ② 🔴 **`GitHubWriteClient` 가 전체 path 를 받지 않는다** — `(owner, name, subPath)` 로 받아 **매 호출 직전 owner 를 다시 단언**하고 자기가 조립한다. ①을 우회해 경로를 직접 조립해도 ②에서 걸린다 |
| **S-2** 항상 draft · 자동 머지 금지 | ✅ **부재로** | 이 PR 은 **PR 을 만들지 않는다.** `pull-request` 엔드포인트를 열지 않고 `PR_CREATED` 전이를 하지 않는다. `ForkPublishArchitectureTest` 가 「`pulls`·`merge`·`ready_for_review` 경로를 부르는 타입이 없다」를 고정한다 |
| **S-3** 샌드박스 밖 실행 금지 | ✅ **해당 없음** | 🔴 **이 PR 은 대상 저장소 코드를 실행하지 않는다.** git 바이너리도, JGit 도, 워크스페이스도 만지지 않는다 — 변경분을 **값으로 받는다**(§4 D-2). `ProcessBuilder`·`Runtime.exec` 없음 |
| **S-4** 시크릿 유출 금지 | 🔴 **접촉 + 잔여 위험** | ① 토큰은 기존 `GitHubCredentials` 경로만 탄다 — **워크스페이스 파일(`.git/config`)에 앉지 않는다**(§4 D-1) ② 파일 **내용을 로그·예외 메시지에 싣지 않는다** — 경로와 바이트 수만 ③ 커밋 author 는 설정값이고 토큰이 아니다.<br>🔴 **그러나 이 경로가 하는 일은 파일 내용을 공개 Fork 에 영구 게시하는 것이다.** 「로그에 안 찍는다」보다 이쪽이 훨씬 무겁다. **내용 검사(`SecretFilePolicy`·`TokenRedactor`)는 이 PR 에 없다** — D-2 가 「같은 방어를 두 벌 두지 않는다」로 #18 에 맡겼기 때문이고, 그 결과 **이 경로의 내용 검사는 0** 이다. §7 위험 7 에 올린다 |
| **S-5** 대상 저장소 규약 우선 | ✅ 접촉 | 커밋 메시지를 `ContributionConstraints`(`signoffRequired`·`issueReferenceRequired`)에서 조립한다. **우리 커밋 컨벤션을 쓰지 않는다** — `CommitMessageTest` 가 「우리 `type(scope):` 형식이 나오지 않는다」를 고정 |
| **S-6** 승인 지점 우회 금지 | ✅ **부재로** | 상태 전이 **없음** · 엔드포인트 **없음** · 스케줄러 진입점 **없음**. 🔴 **`publish()` 에 호출자가 없다** — 배선은 #23 이 `POST /api/candidates/{id}/pull-request` **뒤에** 놓는다. #16 의 `build()` 와 같은 처리 |

### 미결 대조 (Q-1 ~ Q-11)

| 항목 | 걸리는가 | 처리 |
|---|---|---|
| **Q-1** GitHub 연동 방식 | ✅ | **확정분을 그대로 쓴다** — classic PAT + `RestClient` 직접 구현. 그 귀결이 위 FR-8 폐기다 |
| **Q-11** Q-1 을 일반화하지 않는다 | ✅ | **경계를 다시 봤다** — §4 D-1. 결론은 「직접 구현 유지」인데 **근거가 Q-1 과 다르다** |
| **Q-9** 테스트 대역 | ✅ | 3계층 — 페이크(`FakeForkPublisher`) · `MockRestServiceServer`(어댑터 매핑) · WireMock(전송 계약은 `GitHubApiClient` 가 이미 덮는다 — **추가하지 않는다**, 근거 §6) |
| Q-3 실행 프로필 · Q-4 샌드박스 · Q-6 재시도 축 · Q-8 규약 판정 | — | 해당 없음 — 이 PR 은 프로필·샌드박스·재시도 카운터·규약 판정을 건드리지 않는다 |
| Q-2/2b 스키마 | — | **마이그레이션 없음.** `pull_request` 테이블은 V2 에 이미 있고 이 PR 은 영속화하지 않는다 |

**가정**

1. **`ContributionConstraints` 에 이슈 참조 *형식* 이 없다.** `issueReferenceRequired` 는 boolean 뿐이라
   우리는 `#{issueNumber}` 한 줄만 붙인다. `spring-*` 이 쓰는 `Fixes gh-NNNN` 같은 형식은 표현할 수 없다.
   🔴 **틀리면 고칠 곳은 `RepositoryPolicy`(형식 필드 추가) + `#7` 의 LLM 추출**이고, 이 PR 의
   `CommitMessage` 는 그 값을 받도록 한 줄만 바뀐다.
   ⚠️ **자동 닫기 키워드(`Fixes`·`Closes`)를 우리가 임의로 붙이지 않는다** — 머지 시 남의 이슈를
   닫는 부수효과이고, 규약이 요구하지 않는 한 우리가 정할 일이 아니다.
2. **변경분 내용은 호출자가 값으로 준다.** 워크스페이스를 읽는 주체는 #18 이다(§4 D-2).
   틀리면 고칠 곳은 `PublishRequest` 하나다.
3. 🔴 **#18 이 JGit 을 들여오고 push 금지 ArchUnit 을 건다**(§4 D-1 근거 2·3).
   근거가 **머지되지 않은 브랜치의 계획서**라 바뀔 수 있다. 틀려도 D-1 의 결론은
   **근거 1만으로 선다** — 고칠 곳은 없고, D-1 의 근거 2·3 줄을 지우면 된다.

---

## 3. 스코프

| 도메인 | 변경 | 소유 판정 근거 |
|---|---|---|
| `pullrequest` | **신규** | 「Fork·브랜치·PR 메타데이터」가 이 도메인의 한 줄 정의다. 🔴 `architecture.md` 대로 **엔티티를 갖지 않는다** — `PullRequest` 엔티티는 후보 애그리거트 멤버로 `candidate` 에 남는다 |
| `support/github` | 수정 | **쓰기 클라이언트**를 연다. 읽기 클라이언트(`GitHubApiClient`)와 같은 자리이고, owner 단언이 여기 산다 |
| `repository` | — | `ContributionConstraints` 를 **이미 있는 값 그대로** 받는다. 새 계약 없음 |
| `candidate` | — | 🔴 **건드리지 않는다.** 상태 전이·엔티티·`PullRequest` 영속화는 #23 몫 |

### 🔴 도메인 간 계약 — 능력 인터페이스

```java
// com.ossagent.pullrequest.domain.ForkPublisher
public interface ForkPublisher {

    /**
     * Fork 를 확보한다 — 없으면 만들고, 있으면 <b>그것이 정말 이 upstream 의 fork 인지 확인한 뒤</b>
     * 재사용한다. upstream 은 읽기만 한다(fork 생성 요청 하나만 예외 — D-3).
     */
    ForkRef ensureFork(RepositoryCoordinates upstream);

    /**
     * Fork 의 기준 브랜치를 upstream 과 맞춘다.
     * 🔴 실패를 <b>값으로</b> 돌려준다 — 「치명적이지 않아서」가 아니라 <b>진행 판단의 주체가
     * PR 을 만드는 쪽(#23)이기 때문</b>이다. 근거는 D-4b.
     */
    SyncedFork syncWithUpstream(ForkRef fork, String baseBranch);

    /**
     * 🔴 commit + 브랜치 갱신. 쓰기 직전 owner 어설션이 여기 걸린다.
     * 요청이 {@code SyncedFork} 를 들고 있어 <b>동기화를 보지 않고 부르는 것이 불가능</b>하다 — D-4b.
     */
    PublishedBranch publish(PublishRequest request);

    /** Fork 안의 브랜치를 지운다. 🔴 ForkRef 를 요구하므로 upstream 을 지목할 수 없다. */
    void deleteBranch(ForkRef fork, String branchName);
}
```

- 선언: `com.ossagent.pullrequest.domain.ForkPublisher` (**능력 이름**)
- 구현: `com.ossagent.pullrequest.adapter.out.github.GitHubForkPublisher` (**기술 이름**)

⚠️ **`DraftPrPublisher` 와 합치지 않는다.** 그것은 #23 의 능력이고 **S-2 의 실행체**다.
합치면 「브랜치를 올리고 싶어 부른 호출이 PR 생성을 부산물로」 쥐게 되고, 그 순간
세 번째 승인 게이트가 사라진다 — #16 이 `PolicyClearance` 와 `ContributionConstraints` 를
가른 것과 **같은 논리**다.

---

## 4. 기술 설계

### D-1. 🔴 push 수단 — **GitHub Git Data API** (Q-11 재도출)

Q-1 은 「GitHub 은 직접 구현」이라고 결론냈지만, **그 근거 3개를 여기에 다시 대 본다.**

| Q-1 의 근거 | #22 에서 | |
|---|---|---|
| ETag 조건부 요청 + 커서 직접 제어 | 쓰기에는 해당 없음 | — |
| 2차 레이트리밋이 403 으로 온다 | ✅ **그대로 성립** — 쓰기가 2차 리밋에 더 잘 걸린다 | 유지 |
| `hub4j` 가 1년 넘게 RC | ✅ 그대로 | 유지 |

**그래서 직접 구현을 유지한다. 다만 그것이 「JGit 으로 push 한다」를 뜻하지 않는다.**

| 안 | 판정 |
|---|---|
| 호스트 `ProcessBuilder("git","push")` | ❌ `safety-boundary-check.sh` S-3 이 막는다. S-1 실행체를 만들며 S-3 가드를 우회하는 것은 앞뒤가 맞지 않는다 |
| **JGit push** | ❌ **채택하지 않는다** — 아래 근거 1개 + 가정 2개 |
| **Git Data API (blob→tree→commit→ref)** | ✅ **채택** |

**JGit 을 채택하지 않는 근거 — 순서에 의미가 있다. 1번만 이 저장소 안에서 검증된다.**

1. ✅ **어설션 지점이 달라진다 — 이것이 주근거다.** JGit 은 push 대상이 **원격 URL 문자열**이라
   어설션이 URL 파싱이 된다(스킴·`user@`·포트·`.git` 접미사·대소문자). Git Data API 는
   owner 가 **인자**라 어설션이 **문자열 비교 한 줄**이다. S-1 의 유일한 방어를 정규식 위에
   세우지 않는다. **이 근거는 다른 문서에 기대지 않는다.**
2. 🟡 **#18 이 JGit 을 들여오면서 「push 계열을 부르는 타입이 없다」를 ArchUnit 으로 고정할
   예정이다** — `PLAN-18.md` §5 검증 7. #22 가 JGit 으로 push 하면 **그 규칙이 #22 를 잡고**,
   각 브랜치는 초록인데 **합쳐진 뒤에만 적색**이 된다 — `#61` 이 기록한 사고 모양 그대로다.
3. 🟡 **토큰이 워크스페이스에 앉는다.** #18 D-9 가 **익명 clone** 을 택한 이유가
   「`.git/config` 에 토큰이 남고 그 워크스페이스가 샌드박스에 **RW 로** 바인드된다」는 것이다.
   push 는 인증이 필요하므로 그 전제를 다시 열게 된다.

🔴 **2·3 은 이 저장소에서 검증할 수 없다 — 가정이다.** 근거 문서 `PLAN-18.md` 는
**머지되지 않은 브랜치**(`origin/feature/18_coding-agent`, `2abc24e` 시점)에만 있고 `main` 에 없다.
#18 이 설계를 바꾸면 **2·3 은 조용히 무효가 된다.** 그래서 순서를 뒤집어 1을 주근거로 두었다 —
**2·3 이 전부 틀려도 이 결정은 서 있어야 한다.** §2 가정 3 에 올린다.

⚠️ **대가를 적는다.** `safety-boundaries.md` S-1 의 예시 코드와 `safety-boundary-check.sh` 의
S-1 패턴 둘 다 **JGit 모양**(`setRemote(...)`·`.push()`)이다. 이 PR 이후 **그 정적 탐지는
#22 의 경로를 전혀 보지 못한다** — 패턴이 매칭될 코드가 없기 때문이다.
🔴 **그러므로 훅에 S-1 패턴을 하나 더 넣는다**(§4 D-6). 넣지 않으면 「S-1 정적 탐지 부분 있음」이
**#22 에 대해서는 거짓**이 된다.

### D-2. 🔴 워크스페이스를 읽지 않는다 — 변경분을 **값으로** 받는다

`PublishRequest` 가 파일 내용을 들고 온다. #18 이 워크스페이스를 소유하고, #22 는 전송만 한다.

**왜 그렇게 가르나**

- #22 가 워크스페이스를 읽으면 `SandboxWorkspace` 검증·`SecretFilePolicy` 배선을 **한 벌 더** 갖게 된다.
  같은 방어가 두 곳에 있으면 한쪽이 느슨해질 때 드러나지 않는다
- 능력의 단위가 「전송」으로 좁아져 **페이크가 실패 모드를 전부 재현**할 수 있다 (Q-9)
- S-3 접촉이 **0** 이 된다 — 대상 저장소 코드에 닿지 않는다

⚠️ **대가** — 변경분이 메모리에 통째로 올라온다. 계획 상한이 8파일이라 지금은 문제가 아니고,
`PublishRequest` 생성자가 **파일 수·총 바이트 상한**을 단언해 그것을 고정한다.

### D-3. 🔴 어설션을 두 겹으로 둔다 — 값 타입은 **의도 표기**, 어댑터가 **방어**

```java
// ① 값 타입 — 생성 경로가 하나다
public record ForkRef(RepositoryCoordinates coordinates) {
    public static ForkRef of(RepositoryCoordinates c, String forkOwner) {
        if (forkOwner == null || forkOwner.isBlank()) {
            throw new UpstreamWriteAttemptException("GITHUB_FORK_OWNER 가 비어 있다 — 어설션이 무력해진다");
        }
        if (!forkOwner.equals(c.owner())) {
            throw new UpstreamWriteAttemptException(...);   // owner 불일치
        }
        return new ForkRef(c);
    }
}

// ② 🔴 어댑터 — 쓰기 직전. path 를 통째로 받지 않는다
class GitHubWriteClient {
    GitHubResponse post(String owner, String name, String subPath, Object body) {
        assertForkOwner(owner);                    // ← 유일한 방어
        return exchange(POST, "/repos/" + owner + "/" + name + "/" + subPath, body);
    }
}
```

🔴 **②가 본체다.** ①만 두면 「`ForkRef` 를 안 쓰고 경로를 직접 조립하는 새 코드」가
조용히 지나간다 — S-1 이 **「좁은 표면은 강제력이 아니다」**라고 못 박은 그 자리다.
②는 **owner 를 인자로만 받고 자기가 조립**하므로 경로 조립으로 우회할 수 없다.

⚠️ **①을 지우지 않는 이유** — 없으면 호출자가 `upstream.owner()` 를 그대로 넘기고 **런타임에야**
걸린다. ①은 그것을 **호출부에서** 드러낸다. 「막는 것」이 아니라 「일찍 드러내는 것」이 ①의 값이다.

#### 🔴 단 하나의 예외 — Fork 생성은 upstream 경로로 POST 한다

`POST /repos/{upstream_owner}/{upstream_repo}/forks` 다. **owner 어설션을 통과할 수 없다.**

| 물음 | 답 |
|---|---|
| upstream 의 히스토리를 바꾸나 | ❌ **아니다.** 내 계정 아래에 저장소를 만든다. upstream 은 fork 카운트만 는다 |
| 우회 경로가 되나 | ❌ **전용 메서드** `createFork(owner, name)` 이고 `subPath` 가 **리터럴 `"forks"`** 다. 파라미터가 아니라서 다른 경로를 만들 수 없다 |
| 되돌릴 수 있나 | ✅ 내 Fork 를 지우면 된다 |

`ForkCreationIsTheOnlyUpstreamWriteTest` 가 **「`GitHubWriteClient` 에서 owner 어설션을
거치지 않는 메서드가 `createFork` 하나뿐」**을 고정한다. 늘어나면 빨개진다.

### D-4a. 🔴 `ensureFork` — 「있으면 재사용」의 판정 기준이 **존재 여부가 아니다**

```
1. GET /repos/{forkOwner}/{upstreamName}
   ├─ 404          → 2 로 (만든다)
   ├─ 200 이고  fork == true  &&  parent.full_name ≡ upstream.fullName()  → ✅ 재사용
   └─ 200 인데 위 조건 불일치 → 3 으로   (같은 이름의 무관한 내 저장소다)
2. POST /repos/{upstreamOwner}/{upstreamName}/forks        ← 🔴 유일한 upstream 쓰기 (D-3 예외)
3. 🔴 응답 본문의 full_name 으로 ForkRef 를 만든다 — 이름을 우리가 조립하지 않는다
4. 그 좌표로 준비될 때까지 폴링 (github.fork.ready-timeout 상한)
```

🔴 **3번이 「이름 충돌」을 닫는다.** GitHub 은 `forkOwner` 아래 같은 이름이 이미 있으면
fork 를 **`{name}-1` 로 만든다.** 우리가 `{upstreamName}` 을 조립해 폴링하면 영원히 못 찾고,
「200 인데 fork 아님 → 중단」으로 처리하면 **그 대상 저장소는 영구히 기여 불가**가 된다 —
해소 수단이 「사용자가 자기 저장소 이름을 바꾼다」뿐인 막다른 길이다.
`POST /forks` 응답이 실제 좌표를 주므로 **그것을 쓴다.**

⚠️ 어설션은 그대로 선다 — `ForkRef.of` 는 **owner 만** 보고, 응답의 owner 는 `forkOwner` 다.
이름이 `{name}-1` 이어도 S-1 은 영향받지 않는다.

🔴 **1번의 세 번째 분기를 빠뜨리면 owner 어설션이 원리적으로 못 잡는 구멍이 난다.**
사용자가 `spring-kafka` 라는 **무관한 자기 저장소**를 이미 갖고 있으면 그것을 Fork 로 오인해
거기에 commit·ref 를 민다. **owner 어설션은 통과한다** — owner 가 실제로 우리이기 때문이다.
S-1 위반은 아니지만 **남의 것이 아닌 내 것을 망가뜨리는** 경로이고, 드물지도 않다 —
GitHub 은 이름이 충돌하면 fork 를 `name-1` 로 만들기 때문에 **충돌 자체가 정상 경로**다.

⚠️ `parent.full_name` 비교는 **대소문자를 무시**한다. GitHub 소유자·저장소명은 대소문자를 보존하되
비교는 무시한다.

### D-4b. `syncWithUpstream` — `POST /repos/{fork}/merge-upstream`

```
POST /repos/{forkOwner}/{name}/merge-upstream   {"branch": "{baseBranch}"}   ← ② 어설션
```

Fork 경로이므로 owner 어설션을 정상 통과한다. Git Data API 로 손수 맞추는 것(upstream ref 읽기 →
Fork ref force 갱신)보다 **호출이 1회**이고, 충돌 시 GitHub 이 `409` 로 알려 준다.

🔴 **판정은 상태코드가 아니라 응답 본문의 `merge_type` 이다.**

| 응답 | `SyncOutcome` | 이 PR 의 처리 |
|---|---|---|
| 200 · `merge_type: fast-forward` \| `merge` | `MERGED` | 진행 |
| 200 · `merge_type: none` | `ALREADY_UP_TO_DATE` | 진행 |
| 409 | `CONFLICT` — Fork 가 **갈라졌다**(과거 force push 잔재) | 🔴 값으로 올린다 |
| 422 | `UNMERGEABLE` | 〃 |

⚠️ **초안은 「이미 최신 = 204」로 적었다가 고쳤다.** `merge-upstream` 은 성공 시 **200 + 본문**이고
「이미 최신」은 별도 코드가 아니라 **`merge_type: none`** 이다. 상태코드로 판정하게 두면
`MockRestServiceServer` 스텁이 **실제와 다른 응답을 흉내내 초록**이 되고, 어댑터 매핑 층이
잡아야 할 오류를 **테스트가 같은 오류를 갖고 있어서** 못 잡는다.
🔴 **이 PR 에서 응답 스키마를 새로 다루는 유일한 엔드포인트라, 구현 전에 REST 문서로 한 번 확인한다.**

#### 🔴 「호출자가 알아서 본다」를 장치 없이 적지 않는다 — `SyncedFork` 통행증

초안은 「`SyncOutcome` 이 열거형이라 `switch` 가 누락을 드러낸다」고 적었다. **그것은 거짓이다.**
enum 의 exhaustive 검사는 **호출자가 `switch` 를 쓸 때만** 작동하고, 실제 위험은
`publisher.syncWithUpstream(fork, base);` **한 줄로 반환값을 버리는 것**이다. 컴파일러도
ArchUnit 도 그것을 보지 않는다. 그러면 `CONFLICT` 위에 커밋이 쌓이고 #23 이
**머지 불가능한 PR 을 남의 저장소에 연다.**

**이 저장소에 이미 선례가 있다** — `PolicyClearance` 를 `startImplementing` 이 **인자로 요구**해
S-5 의무를 javadoc 에서 컴파일러로 옮긴 수법(#24). 같은 것을 쓴다.

```java
public record SyncedFork(ForkRef fork, SyncOutcome outcome) { … }   // 발급처는 sync 하나뿐

SyncedFork syncWithUpstream(ForkRef fork, String baseBranch);
PublishRequest(SyncedFork fork, …)     // 🔴 ForkRef 를 직접 받지 않는다
```

**「동기화를 보지 않고 publish 한다」가 표현 불가능해진다.**

⚠️ **이것이 강제하는 것은 「호출」이지 「판단」이 아니다.** `CONFLICT` 여도 publish 는 가능하다 —
진행 여부는 PR 을 만드는 주체가 정할 일이고, 이 PR 은 PR 을 만들지 않는다. 다만
**보지 않고 지나가는 것은 불가능**해지고, 그 차이가 이 절의 전부다.

### D-4c. publish 절차 — Git Data API

```
0. ForkRef.of(coordinates, forkOwner)                      ← ① 어설션
1. GET  /repos/{fork}/git/ref/heads/{base}                 → baseCommitSha   (읽기)
2. GET  /repos/{fork}/git/commits/{baseCommitSha}          → baseTreeSha     (읽기)
3. POST /repos/{fork}/git/blobs            × N             → blobSha         ← ② 어설션
4. POST /repos/{fork}/git/trees   (base_tree = baseTreeSha) → treeSha        ← ② 어설션
5. POST /repos/{fork}/git/commits (message, tree, parents) → commitSha       ← ② 어설션
6. POST   /repos/{fork}/git/refs  (첫 publish)                               ← ② 어설션
   PATCH  /repos/{fork}/git/refs/heads/{branch} (force, 재시도)              ← ② 어설션
```

- 🔴 **`force: true` 는 재시도 경로에만.** 첫 publish 는 `POST /git/refs` 라 이미 있으면 422 로 실패하고,
  그것이 **FR-7(멱등)의 신호**다 — 같은 브랜치에 두 번 올리는 것을 조용히 덮지 않는다

#### 🔴 삭제된 파일 — `sha: null` 이 **JSON 에 실제로 실려야** 한다

`base_tree` 를 준 상태에서 tree 항목의 `sha` 를 `null` 로 두면 그 경로가 삭제된다.
`path`·`mode`·`type` 은 함께 보내야 한다.

⚠️ **규약이 맞는 것과 직렬화가 맞는 것은 다른 문제다.** Jackson 이 `NON_NULL` 로 설정돼
있거나 DTO 가 `Optional` 이면 **필드가 통째로 빠지고**, GitHub 은 그것을 「이 항목을
건드리지 않음」으로 읽어 **삭제가 조용히 누락**된다. 실패가 예외가 아니라 **「diff 가 조용히
다르다」**로 나타나므로 테스트가 요청 **본문 문자열**을 보지 않으면 못 잡는다.

- `GitDataPayloads` 의 tree 항목은 **null 을 생략하지 않는다**(`@JsonInclude(ALWAYS)` 명시)
- §6 테스트 6 은 **요청 JSON 에 `"sha":null` 이 실재하는지**를 문자열로 단언한다

### D-4d. 🔴 쓰기에 전송 재시도를 어떻게 거는가 — 단계마다 다르다

기존 `GitHubRetryPolicy` 는 `GitHubTransientException`(5xx · **응답 없는 타임아웃**)을 재시도한다.
🔴 **읽기 타임아웃은 요청이 서버에 도달했는지 알 수 없는 실패**라, 그대로 쓰기에 붙이면
「성공했는데 응답만 못 받은」 호출을 다시 보낸다.

| 단계 | 재시도 | 왜 |
|---|---|---|
| `POST /git/blobs` · `/git/trees` · `/git/commits` | ✅ **안전** | 🔴 근거를 **우리가 통제하는 사실**로 적는다 — **결과 sha 를 쓰는 것은 마지막 응답 하나뿐이고, 중간에 만들어진 객체는 어떤 ref 도 가리키지 않는다.** 즉 우리 쪽에 부작용이 없다 |
| `POST` · `PATCH` `/git/refs` | 🔴 **하지 않는다** | ref 갱신은 **상태 변경**이다. 재시도가 첫 요청을 덮거나 422 를 만든다 |
| `POST /repos/{upstream}/forks` | 🔴 **하지 않는다** | 중복 fork 요청. 게다가 **유일한 upstream 쓰기**라 재시도 대상으로 두지 않는다 |
| `POST /merge-upstream` | 🔴 **하지 않는다** | 머지는 상태 변경이다 |

`GitHubWriteClient` 가 **호출마다 재시도 허용 여부를 인자로 받는다**(`Idempotency.SAFE` / `UNSAFE`).
기본값을 두지 않는다 — 기본값이 있으면 새 쓰기가 조용히 안전한 쪽으로 분류된다.

⚠️ **NFR-1 의 계산은 이 구분 위에서 한 것이다.** 곱이 걸리는 것은 `SAFE` 구간뿐이고
`UNSAFE` 는 1회씩이라 합계가 **≈ 42** 다 — 각주로 때우지 않고 §1 NFR-1 상세 표에서 다시 셌다.

🔴 **「내용 주소라 재전송이 무해하다」는 commit 에 대해 조건부다.** commit 객체의 해시에는
`author.date`·`committer.date` 가 들어가므로, 재시도마다 `clock.instant()` 를 다시 부르면
**매번 다른 sha** 가 나온다. 최종 ref 가 마지막 것만 가리켜 실해는 없지만 「같은 내용 = 같은 sha」가
깨진다. **커밋 날짜는 publish 진입 시 한 번 읽어 재시도 내내 재사용한다** — 그러면 commit 도
진짜로 멱등이다.

### D-5. 커밋 메시지 — S-5

```java
// com.ossagent.pullrequest.domain.CommitMessage
public static CommitMessage from(String subject, String body,
                                 ContributionConstraints constraints,
                                 int issueNumber, CommitIdentity identity)
```

| 규약 | 결과 |
|---|---|
| `issueReferenceRequired` | 본문 끝에 `#{issueNumber}` 한 줄 |
| `signoffRequired` | `Signed-off-by: {name} <{email}>` — 🔴 identity 가 없으면 **예외**. 「sign-off 가 필요한데 서명자가 없다」는 조용히 넘길 일이 아니다 |
| 둘 다 아님 | subject + body 만 |

🔴 **우리 커밋 컨벤션(`type(scope): subject`)을 쓰지 않는다** — S-5. `CommitMessageTest` 가
「`feat(`·`fix(` 같은 우리 형식이 산출물에 나타나지 않는다」를 단언한다.

### D-6. 🔴 훅에 S-1 패턴을 한 줄 더한다 — 아니면 정적 탐지가 #22 를 못 본다

`safety-boundary-check.sh` 의 S-1 패턴 둘은 **JGit 모양만** 본다. Git Data API 경로에는
`setRemote` 도 `.push()` 도 없다.

```bash
# 🔴 쓰기 메서드 호출에 upstream 좌표가 실린 모양
check "$f" "S-1" \
  '(post|patch|put|delete)\([^)]*(upstream|Upstream|UPSTREAM)' \
  "..." "ForkRef 를 통해서만 쓰기 대상을 만드세요. owner 어설션이 유일한 방어입니다."
```

⚠️ **이것이 충분하다고 적지 않는다. 한계를 세 줄로 적는다 — 새는 방향부터.**

1. 🔴 **변수명이 `upstream` 이 아니면 안 걸린다.** **거부목록**이고,
   `testing-philosophy.md` 가 「거부목록으로 방어하지 않는다」고 적어 둔 그 방식이다
2. 🔴 **스크립트는 `src/**/*.java` 의 문자열만 본다.** 이 계획의 실제 위험 표면은
   **호출 그래프**라 파일 범위와 무관하게 안 잡힌다
3. 무고하게 빨개지는 방향 — `// safety-ok:` 로 뗄 수 있다

🔴 **실질 방어는 D-3 ②의 런타임 어설션과 §6 테스트 8 ③의 ArchUnit 여집합**이고,
훅은 **「JGit 모양만 보던 탐지가 새 모양을 전혀 못 보는 상태」를 면하려는 보조**다.
그 한계를 **스크립트 주석에 적는다** — 적지 않으면 다음 사람이 이 패턴을 방어로 센다.

### 변경 파일

| # | 경로 | 레이어 | 신규/수정 | 내용 |
|---|------|--------|----------|------|
| 1 | `pullrequest/domain/ForkRef.java` | domain | 신규 | 🔴 S-1 값 타입 — 생성 시 owner 단언 |
| 2 | `pullrequest/domain/UpstreamWriteAttemptException.java` | domain | 신규 | S-1 위반 시 중단 |
| 3 | `pullrequest/domain/ForkPublisher.java` | domain | 신규 | 능력 인터페이스 |
| 4 | `pullrequest/domain/PublishRequest.java` | domain | 신규 | 변경분 값 + 파일 수·바이트 상한 단언 |
| 5 | `pullrequest/domain/FileChange.java` | domain | 신규 | 경로·내용·삭제 여부 |
| 6 | `pullrequest/domain/PublishedBranch.java` | domain | 신규 | 산출물 — 브랜치명·commit sha |
| 7 | `pullrequest/domain/BranchName.java` | domain | 신규 | `oss-agent/issue-{n}-{slug}` · slug 정규화 (FR-3) |
| 8 | `pullrequest/domain/CommitMessage.java` | domain | 신규 | S-5 조립 |
| 9 | `pullrequest/domain/CommitIdentity.java` | domain | 신규 | sign-off 서명자 |
| 10 | `pullrequest/domain/SyncOutcome.java` | domain | 신규 | 동기화 결과 — `merge_type` 기반 (D-4b) |
| 10b | `pullrequest/domain/SyncedFork.java` | domain | 신규 | 🔴 **통행증** — 발급처는 `syncWithUpstream` 하나. `PublishRequest` 가 인자로 요구 |
| 11 | `pullrequest/domain/ForkPublishException.java` | domain | 신규 | 전송 외 실패 |
| 12 | `pullrequest/adapter/out/github/GitHubForkPublisher.java` | adapter/out | 신규 | 🔴 **`@Component @ExternalAdapter`** · D-4a~d 절차 |
| 13 | `pullrequest/adapter/out/github/GitDataPayloads.java` | adapter/out | 신규 | 요청·응답 DTO. 🔴 tree 항목은 **`@JsonInclude(ALWAYS)`** |
| 14 | `support/github/GitHubWriteClient.java` | adapter | 신규 | 🔴 **owner 어설션** · path 조립 · `Idempotency` 인자. **`@ExternalAdapter`** |
| 15 | `support/github/GitHubProperties.java` | — | 수정 | `forkOwner` · `commitAuthor` · `fork.readyTimeout` **추가만** |
| 16 | `config/ForkPublishConfig.java` | config | 신규 | 🔴 **`@Configuration @ExternalAdapter`** · **기존 `RestClient` 빈을 재사용한다** — 새 전송 스택을 만들지 않는다 |
| 17 | `src/main/resources/application.yml` | — | 수정 | 🔴 `github.fork-owner: ${GITHUB_FORK_OWNER:}` — **지금 바인딩이 없다** · `github.commit-author.*` · `github.fork.ready-timeout` |
| 18 | `.env.example` | — | 수정 | `GITHUB_COMMIT_AUTHOR_NAME` · `GITHUB_COMMIT_AUTHOR_EMAIL` 추가. ⚠️ `GITHUB_FORK_OWNER` 는 **이미 있다**(30행) — 없던 것은 `application.yml` 쪽 바인딩이다 |
| 19 | `.claude/scripts/safety-boundary-check.sh` | — | 수정 | D-6 패턴 |
| 20 | `src/test/.../SafetyBoundaryCheckScriptTest.java` | test | 수정 | 🔴 D-6 패턴의 **위반 표본 + 통과 표본** — 물림 단언 |

🔴 **14·16 의 프로필 표기가 왜 필요한가** — `ExternalAdapters` 의 **신호 2** 는
「필드로 네트워크 클라이언트를 전이적으로 보유」이고 `NETWORK_CLIENTS` 에 `RestClient` 가 있다.
`GitHubWriteClient` 는 패키지가 `support/github` 여도 **대외 어댑터로 판정**되므로,
표기가 없으면 `fakes` 프로필에서도 빈이 올라와 `ExternalAdapterIsolationTest` 가 **통합 테스트
전부를 적색**으로 만든다.

⚠️ **「클래스에 붙였으니 됐다」가 아니다.** `@ExternalAdapter` 는 `@Profile("!fakes")` 이므로
**빈 정의에 붙어야** 효과가 있다. `GitHubWriteClient` 를 `ForkPublishConfig` 의 `@Bean` 메서드로
만들면 **클래스 애노테이션은 읽히지 않는다** — 선례로 든 `GitHubApiClient` 자신이 클래스에는
아무 표기 없이 `GitHubClientConfig` 의 `@ExternalAdapter` 로만 빠진다.

🔴 **이 PR 의 선택: `@Bean` 조립.** 따라서 **프로필을 실제로 거는 것은 파일 16** 이고,
파일 14 의 클래스 애노테이션은 **의도 표기**다. `@Component` 스캔으로 바꾸면 이 판단이
뒤집히므로 그때 이 문단을 함께 고친다.

### 체크리스트 답변

| # | 항목 | 답 |
|---|------|---|
| 1 | 소유 도메인 | `pullrequest` — 「Fork·브랜치·PR 메타데이터」. 🔴 **엔티티는 갖지 않는다** |
| 2 | 레이어 배치 | 능력·값 = domain · 전송 = adapter/out · owner 단언 = `support/github`(쓰기 클라이언트) |
| 3 | 능력 인터페이스 | **필요** — `ForkPublisher`. 구현 `GitHubForkPublisher` |
| 4 | 🔴 트랜잭션 안 대외 호출 없음 | ✅ **이 PR 은 트랜잭션을 열지 않는다.** `@Transactional` 이 한 곳도 없음 — `ForkPublishArchitectureTest` 가 고정 |
| 5 | 상태 전이 영향 | **없음.** `CandidateStatus` 를 건드리지 않는다 — #23 몫 |
| 6 | 멱등성 | 키 = **(fork, branchName)**. 첫 publish 는 `POST /git/refs` 라 이미 있으면 422 로 드러나고, 재시도는 **명시적 force** 로만 덮는다 (FR-7) |
| 7 | `Clock` 주입 | ✅ — 커밋 author/committer `date` 에 쓴다. `Instant.now()` 직접 호출 없음 |
| 8 | 🔴 안전 경계 | §2 |

### 데이터 모델

**해당 없음 — 마이그레이션 없다.** `pull_request` 테이블은 V2 에 이미 있고, 이 PR 은
**영속화하지 않는다**(#23 이 한다). 엔티티를 건드리지 않으므로 `ddl-auto: validate` 와 무관하다.

### API 계약

**해당 없음 — 엔드포인트를 열지 않는다.** S-6 게이트 `POST /api/candidates/{id}/pull-request` 는
#23 이 **PR 생성기와 같은 PR 에서** 연다. 지금 열면 PR 없이 종단 `PR_CREATED` 가 된다.
`CandidateApprovalApiTest.착수와_PR생성_엔드포인트는_없다_S6` 를 **그대로 둔다.**

---

## 5. 구현 순서

### 실행 모드: sequential

**판정 근거** — `contract-and-impl` 유형이다. Stage 2·3 이 Stage 1 의 값 타입을 import 하고,
Stage 4 가 Stage 2 의 클라이언트를 쓴다. 파일이 겹치지는 않으나 **import 의존이 한 방향으로 직렬**이다.

| Stage | 내용 | 선행 | 파일 |
|-------|------|------|------|
| 1 | 값·능력 — `ForkRef` · `BranchName` · `CommitMessage` · `PublishRequest` 외 | 없음 | 1~11 |
| 2 | 🔴 `GitHubWriteClient` + owner 어설션 + `createFork` 예외 | 1 | 14, 15 |
| 3 | `GitHubForkPublisher` — D-4 절차 | 1, 2 | 12, 13 |
| 4 | 조립 · 설정 · 훅 · 문서 | 3 | 16~19 |

---

## 6. 테스트 계획

| # | 레벨 | 대상 | 검증 내용 |
|---|------|------|----------|
| 1 | 유닛 | `ForkRef` | 🔴 `push_대상이_Fork가_아니면_중단한다_S1()` · **`forkOwner` 가 빈 값이면 중단** · 대소문자 다른 owner 도 중단 |
| 2 | 유닛 | `GitHubWriteClient` | 🔴 **어설션 우회 시도** — upstream owner 로 `post` 를 직접 부르면 중단. `createFork` 만 통과 |
| 3 | 유닛 | `BranchName` | `oss-agent/issue-{n}-{slug}` · slug 정규화 · 길이 상한 · `refs/heads` 주입 차단 |
| 4 | 유닛 | `CommitMessage` | sign-off 유무 · 이슈 참조 유무 · 🔴 **우리 커밋 컨벤션이 나오지 않는다**(S-5) · identity 없이 sign-off 요구 시 예외 |
| 5 | 유닛 | `PublishRequest` | 파일 수·총 바이트 상한 · 빈 변경분 거부 |
| 6 | 어댑터 매핑 | `GitHubForkPublisher` | `MockRestServiceServer` — D-4 의 6단계 요청 조립 · 응답 파싱 · **삭제 파일의 `sha: null`** · 422(브랜치 중복) 처리 |
| 7 | 어댑터 매핑 | 〃 | 🔴 **Fork 확보 재사용** — 이미 있으면 `POST /forks` 를 **부르지 않는다** |
| 7b | 어댑터 매핑 | 〃 | 🔴 **같은 이름의 무관한 저장소를 Fork 로 재사용하지 않는다** — `fork:false` 또는 `parent` 불일치면 중단 (D-4a) |
| 7c | 어댑터 매핑 | 〃 | `syncWithUpstream` — 204/409 를 `SyncOutcome` 값으로 (D-4b) |
| 8 | 아키텍처 | `ForkPublishArchitectureTest` | ① `pulls`·`merge`·`ready_for_review` 경로 부재(S-2) ② `@Transactional` 부재 ③ 🔴 **여집합** — 아래 |
| 9 | 능력 대역 | `FakeForkPublisher` | 실패 모드 재현 — 레이트리밋 지연 · 422 · 권한 403 · 409 · upstream 지목 시 중단 |
| 10 | 게이트 스크립트 | `SafetyBoundaryCheckScriptTest` | 🔴 D-6 의 새 S-1 패턴이 **위반 표본을 문다** + 무고한 표본은 통과 |
| 11 | 격리 | `ExternalAdapterIsolationTest` | 새 빈 2종(`GitHubWriteClient`·`ForkPublishConfig`)을 보고도 초록 |

#### 🔴 테스트 8 ③ — 열거가 아니라 **여집합**으로 쓴다

처음에 「`GitHubWriteClient` 안에서 어설션을 안 거치는 메서드가 `createFork` 하나뿐」으로
적었는데, 그것은 **한 타입 안만 세는 열거**다. 그런데 S-1 이 못 박은 위협 모형은 정확히 그
**바깥**이다 — 「`spring-boot-starter-web` 이 `RestClient.Builder` 를 자동설정 빈으로 올려
**아무 컴포넌트나** `builder.build().post(...)` 를 할 수 있다」. §1 에서 그 문장을 인용해 놓고
가드가 그 입력 공간을 안 보면 `testing-philosophy.md` **요구 4(입력 도달)** 에 걸린다.

```
③  com.ossagent 전체에서 「쓰기 HTTP 호출」을 하는 타입은 GitHubWriteClient 뿐이다
③b GitHubWriteClient 안에서 assertForkOwner 를 거치지 않는 쓰기 메서드는 createFork 뿐이다
```

③이 **덮개**, ③b 가 **그 안의 예외**다. 둘 다 있어야 한다 — ③만 두면 `createFork` 예외가
넓어지는 것을 못 보고, ③b 만 두면 클라이언트 밖의 `post` 를 못 본다.

#### 🔴 ③은 아직 열거다 — 두 가지를 더 해야 닫힌다

**① 모수 단언 (요구 1).** 「쓰기 호출」을 `post()`·`patch()`·`put()`·`delete()` **메서드 이름**으로만
판정하면, 구현이 `restClient.method(HttpMethod.POST)` 나 `.exchange(...)` 로 가는 순간
**대상이 0건**이 되고 규칙은 조용히 초록이다. 그 상태에서는 **검사하려던 본체를 못 찾았다는
사실조차 드러나지 않는다.**

> ③은 「찾아낸 쓰기 호출 타입이 **정확히 1개**이고 그것이 `GitHubWriteClient` 다」를 단언한다.
> **0건이면 실패**다.

**② 판정 축을 넓힌다.** 메서드 이름 ∪ **`HttpMethod.POST/PUT/PATCH/DELETE` 상수 참조**.
대상 클라이언트 목록은 🔴 **`ExternalAdapters.NETWORK_CLIENTS` 와 같은 출처를 쓴다** —
따로 적으면 둘이 갈라지고, 그것은 `SecretPatternDriftTest` 가 막으려던 어긋남과 같은 모양이다.
(`NETWORK_CLIENTS` 에는 `java.net.http.HttpClient` 도 있다 — 손으로 적었으면 빠뜨렸을 것이다.)

⚠️ **물림을 미끼로 고정한다.** 위반이 0건이면 규칙이 항상 `true` 를 돌려줘도 초록이다.
`src/test/.../probe/` 에 이미 같은 수법의 표본이 있으므로 그 관행을 따른다.

**대외 호출 대체** (Q-9)

| 층 | 대역 | 근거 |
|---|---|---|
| 능력 소비자 | `FakeForkPublisher` + `@FakeAdapter` | 소비자는 #23 이라 **지금은 대역만 둔다** |
| 어댑터 매핑 | `MockRestServiceServer` | 요청 조립·응답 파싱·422/403 구분 |
| 전송 계약 | ❌ **추가하지 않는다** | 🔴 **조건부 근거다** — `ForkPublishConfig` 가 **새 `RestClient` 를 만들지 않고 기존 빈을 주입받을 때만** 성립한다(§4 파일 16). 그러면 타임아웃·`Redirect.NEVER` 가 `GitHubApiClient` 와 같은 빈이고 `GitHubTransportContractTest` 가 이미 덮는다. ⚠️ **구현 중 새 `RestClient` 를 조립하게 되면 이 근거가 깨지므로 그때 전송 계약 층을 추가한다** |

### 🔴 돌연변이 검증 (S-1 이라 필수)

| 제거할 것 | 기대 |
|---|---|
| `ForkRef.of` 의 owner 비교 | 빨강 — 건수를 PR 본문에 적는다 |
| 🔴 `GitHubWriteClient.assertForkOwner` 호출 | 빨강 — **이쪽이 본체**라 건수가 더 커야 한다. 작으면 ②가 실제로는 안 걸려 있다는 뜻이다 |
| `CommitMessage` 의 sign-off 분기 | 빨강 |

⚠️ **제거 지점이 측정 대상보다 「위」면 아무것도 재지 못한다**(#64). `ForkRef` 를 통째로
지우면 컴파일이 깨져 **전부 빨강**이 되는데 그것은 ②가 산다는 증거가 아니다.
**호출 한 줄만** 지운다.

---

## 7. 리스크

| # | 리스크 | 영향 | 대응 |
|---|--------|------|------|
| 1 | 🔴 **#18 과 같은 시점에 머지** | `GitHubProperties`·`application.yml`·`.env.example` 3파일이 겹칠 수 있다 | 셋 다 **추가만** 한다(기존 줄 수정 없음). #18 은 JGit·워크스페이스라 **패키지가 안 겹친다** |
| 2 | 🔴 **Fork 생성은 비동기다** | `POST /forks` 가 202 를 주고 저장소가 **아직 없을 수 있다.** 바로 push 하면 404 | `ensureFork` 가 **폴링**한다 — 상한(`github.fork.ready-timeout`) 안에 안 되면 `ForkPublishException`. 무한 대기를 만들지 않는다 |
| 3 | **`GITHUB_FORK_OWNER` 미설정** | 어설션이 무력해진다 | 🔴 **빈 값이면 `ForkRef.of` 가 던진다.** 「비었으니 통과」가 되지 않게 명시적으로 막는다 |
| 4 | `safety-boundary-check.sh` 의 S-1 패턴이 새 모양을 못 본다 | 정적 탐지가 #22 에 대해 공백 | D-6 에서 한 줄 더하되 **한계를 함께 적는다.** 실질 방어는 런타임 어설션 |
| 5 | 대용량 변경분 | 메모리 | `PublishRequest` 가 파일 수·바이트 상한을 단언 |
| 6 | **이 PR 에 호출자가 없다** | 죽은 코드처럼 보인다 | 🔴 **의도다** — #16 과 같다. PR 본문에 「배선은 #23」을 명시 |
| 7 | 🔴 **내용 검사 없이 공개 Fork 에 게시한다** | 대상 저장소가 커밋해 둔 시크릿·LLM 이 넣은 문자열이 **공개 저장소에 영구 게시**된다 | **이 PR 에 방어를 두지 않는다**(D-2 — 같은 방어 두 벌 금지). 🔴 **대신 `FileChange.content` 를 `@ExternalText` 로 표시하고 `ExternalTextScrubRegistryTest` 에 `미구현 #18` 로 등록한다** — 아래 |
| 8 | 같은 이름의 무관한 저장소를 Fork 로 오인 | 내 저장소를 망가뜨린다. **owner 어설션은 통과한다** | D-4a 의 `fork==true && parent 일치` 단언 |
| 9 | 비멱등 쓰기에 전송 재시도 | 커밋·ref 중복 | D-4d — 단계별 `Idempotency` 인자. 기본값을 두지 않는다 |

#### 🔴 위험 7 의 인계를 **PR 본문이 아니라 테스트**에 남긴다

초안은 대응을 「PR 본문에 인계로 남긴다」로 적었다. **머지 후 아무도 안 읽는다.**

이 저장소는 정확히 이 상황을 위한 관행을 이미 갖고 있다 — `ExternalTextScrubRegistryTest` 가
강제하는 것은 「스크럽했다」가 아니라 **「스크럽을 어떻게 할지 누군가 정했다」**이고,
등록값에 **`미구현` + 담당 이슈 번호**를 허용한다.

```java
Map.entry("FileChange.content", new Decision(Mechanism.PENDING,
        "#22 — 공개 Fork 에 게시되는 내용. 내용 검사는 #18 이 세운다(D-2)"))
```

⚠️ **이것은 방어가 아니라 기록의 강제다.** D-2 의 「같은 방어를 두 벌 두지 않는다」와 충돌하지
않는다 — 비용은 등록 한 줄이고, 얻는 것은 **#18 이 채우지 않으면 그 사실이 테스트 목록에
계속 보이는 것**이다.

**대외 호출 실패 시나리오**

| 시나리오 | 기대 동작 |
|---|---|
| 1차 레이트리밋 | **지연**이지 실패가 아니다 — `GitHubRateLimitException(PRIMARY)` 가 그대로 올라간다. 재시도로 태우지 않는다 |
| 2차 레이트리밋(403) | 🔴 **쓰기가 더 잘 걸린다.** 기존 `GitHubErrorTranslator` 가 권한 403 과 구분한다 — 그 경로를 **재사용**하고 새로 만들지 않는다 |
| 권한 403 | 실패. 재시도하지 않는다 |
| 422 (브랜치 이미 존재) | **실패로 드러낸다.** 조용히 force 로 덮지 않는다 (FR-7) |
| Fork 아직 준비 안 됨 (404) | 상한 내 폴링 후 `ForkPublishException` |

---

## 8. 복잡도

| Stage | 파일 수 | 복잡도 |
|-------|--------|--------|
| 1 값·능력 | 11 | 중간 |
| 2 쓰기 클라이언트 + 어설션 | 2 | 🔴 **높음** — S-1 본체 |
| 3 어댑터 | 2 | 높음 — 6단계 절차 · 실패 분류 |
| 4 조립·설정·문서 | 4 | 낮음 |

---

## 9. 문서 동기화 대상

| 대상 | 필요 | 이유 |
|---|---|---|
| `codemaps/architecture.md` | ✅ | `pullrequest` 패키지가 실재하게 된다 |
| `codemaps/data.md` | — | 스키마 변경 없음 |
| `codemaps/domain.md` | ✅ | 능력표에 `ForkPublisher` 추가 |
| `.env.example` | ✅ | `GITHUB_COMMIT_AUTHOR_NAME` · `_EMAIL` |
| `README.md` | ✅ | 구조 절에 `pullrequest` |
| `rules/context/glossary.md` | ✅ | `ForkPublisher` · `ForkRef` · 「Fork 확보」 |
| `rules/context/safety-boundaries.md` | ✅ | 🔴 S-1 요약표의 「정적 탐지: 부분」이 **무엇을 보고 무엇을 못 보는지** — JGit 모양만 보던 것에 새 모양이 생겼다 |
| `rules/context/external-deps.md` | ✅ | GitHub API 용도 줄에 **쓰기 표면이 열렸다**는 사실 |
| `rules/context/open-questions.md` | — | 🔴 **Q-11 을 닫지 않는다.** 이 PR 은 Q-11 의 **적용 사례**이지 결론이 아니다. 다음 경계에서 또 다시 본다 |

---

## 10. 변경 이력

| 일자 | 작성자 | 변경 내용 |
|------|--------|----------|
| 2026-09-27 | smileboy0014 | 초안 — push 수단을 Git Data API 로 확정(Q-11 재도출) · 이슈 완료조건 FR-8 폐기 근거 · 어설션 두 겹 설계 |
| 2026-09-27 | smileboy0014 | rev.3 — 재검토 반영 9건. 🔴 **`SyncedFork` 통행증 신설**(「switch 가 드러낸다」가 반환값 무시를 못 잡는다는 지적 — `PolicyClearance` 선례) · ArchUnit ③에 **모수 단언 + 판정 축 확대**(`NETWORK_CLIENTS` 공유) · NFR-1 을 `SAFE`/`UNSAFE` 로 **다시 계산(≈42)** · `merge-upstream` 판정을 **상태코드에서 `merge_type` 으로** · fork 이름 충돌을 **응답 `full_name`** 으로 해소 · 위험 7 인계를 **`ExternalTextScrubRegistry` 등록**으로 · GC 주장 제거 + **커밋 날짜 고정** · `@Bean` 조립임을 명시 · 잔재 2곳 |
| 2026-09-27 | smileboy0014 | rev.2 — 격리 검토 반영 9건. **D-4a**(fork 재사용 판정) · **D-4b**(동기화 설계) · **D-4d**(비멱등 쓰기 재시도) 신설 · 테스트 8 ③을 **여집합**으로 · `@ExternalAdapter` 누락 · `fork-owner` 바인딩 부재 · `sha:null` 직렬화 함정 · S-4 잔여 위험 명시 · D-1 근거 순서를 **문서 의존 없는 것부터**로 |
