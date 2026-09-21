# 넘으면 안 되는 선 — 되돌릴 수 없는 규칙

> 기준일 **2026-09-18**. 근거는 [PRD](../../../docs/ai-oss-contributor-agent-prd.md) §3 Non-Goals · §16 Docker Sandbox · §18 GitHub Contribution Flow · §20 Human-in-the-loop · §25 Security Architecture.

이 프로젝트가 만드는 것은 **남의 저장소에 코드를 밀어 넣을 수 있는 자동화**다.
평범한 버그는 우리 서비스가 죽지만, 여기서의 사고는 **외부 OSS 커뮤니티에 직접 나간다.**
한 번 나간 push·PR·코멘트는 지워도 메일 알림·포크·캐시에 남고, 메인테이너의 신뢰는 복구되지 않는다.

아래 6개는 **코드 리뷰 무조건 블로킹**이다. 기능이 급해도 예외를 두지 않는다.

---

## S-1. 원본 저장소에 쓰지 않는다 🔴

쓰기 대상은 **사용자 Fork 뿐**이다. 원본(upstream)으로 가는 경로는 **읽기만** 존재한다.

```java
// ❌ 반려 — upstream 좌표로 push
git.push().setRemote(repository.getUrl()).call();

// ✅ Fork 좌표로만
git.push().setRemote(fork.getPushUrl()).call();   // fork.owner == GITHUB_FORK_OWNER
```

- 🔴 push 직전 **원격 URL 의 owner 가 Fork owner 와 일치하는지 단언**한다. 어설션 없는 push 경로는 반려
- 브랜치 삭제·force push 도 Fork 안에서만

### ⚠️ 권한으로는 막을 수 없다 — 어설션이 유일한 방어다 (2026-09-21 개정)

이 문서는 원래 「토큰 권한부터 막는다」를 1차 방어로 적어 두었다. **그 방어는 실현 불가능하다.**

Q-1 결론(#2)에 따라 인증은 **classic PAT** 이다. 다른 선택지가 없다 — fine-grained PAT 과
GitHub App 설치 토큰은 **우리가 멤버가 아닌 upstream 에 PR 을 만들지 못한다.**
그런데 classic PAT 의 `public_repo` 스코프는 **저장소별 권한 제한이 불가능**하다.
「원본에는 write 를 주지 않는 토큰」이라는 것이 GitHub 에 존재하지 않는다.

| 방어 | 상태 |
|---|---|
| ~~토큰 권한 미부여~~ | ❌ **불가능** — classic PAT 은 all-or-nothing |
| **push 직전 owner 어설션** | ✅ **유일한 방어** |

**잔여 위험** — 사용자가 **collaborator 인 공개 저장소**를 대상으로 등록하면, 토큰은 그 저장소에
실제로 write 권한을 갖는다. 이때 어설션이 없으면 **진짜로 upstream 에 push 된다.**
대부분의 대상(`spring-projects/*` 등)은 collaborator 가 아니라 권한이 없지만, **없는 권한에 기대지 않는다.**

그래서 어설션은 「있으면 좋은 것」이 아니라 **없으면 반려**다. 테스트 커버리지 100% 대상이고,
`push_대상이_Fork가_아니면_중단한다_S1()` 이 그 증거다.

**어기면** — 남의 저장소 히스토리를 오염시킨다. 권한이 있었다면 되돌릴 수 없는 사고고, 없었다면 인증 실패 로그가 상대 감사 로그에 남는다.

## S-2. PR 은 항상 draft 로 만들고, 자동 머지하지 않는다 🔴

사람이 승인하기 전까지 **메인테이너에게 리뷰 요청이 가서는 안 된다.**

- 생성은 `draft: true` 고정. 설정으로도 끌 수 없게 한다 — 플래그를 두면 언젠가 켜진다
- `ready_for_review` 전환 · 리뷰어 지정 · 머지 API 호출 코드는 **존재 자체가 반려**
- 이슈 코멘트·리뷰 코멘트 등 **대상 저장소에 글을 남기는 모든 경로**도 같은 취급. 사람 승인 없이 쓰지 않는다

**어기면** — 검증되지 않은 AI 코드가 메인테이너의 리뷰 큐를 점유한다. OSS 커뮤니티에서 이 행동은 스팸으로 취급되고 계정이 차단된다.

## S-3. 대상 저장소 코드는 샌드박스 밖에서 실행하지 않는다 🔴

우리가 clone·build·test 하는 것은 **신뢰할 수 없는 코드**다. Gradle/Maven 빌드 스크립트는 임의 코드 실행이다.

| 제한 | 값 (`.env.example`) |
|---|---|
| 네트워크 | `SANDBOX_NETWORK=none` — 의존성 사전 워밍 후 차단 |
| CPU | `SANDBOX_CPU_LIMIT` |
| 메모리 | `SANDBOX_MEMORY_LIMIT` |
| 실행 시간 | `SANDBOX_TIMEOUT_SECONDS` |
| 파일시스템 | 작업 디렉토리만. 호스트 볼륨·소켓 마운트 금지 |

- `ProcessBuilder` · `Runtime.exec` 로 호스트에서 대상 저장소 빌드를 돌리는 경로는 반려
- **Docker 소켓(`/var/run/docker.sock`)을 샌드박스에 마운트하지 않는다** — 컨테이너 탈출 경로다
- 우리 애플리케이션의 시크릿이 담긴 환경변수를 샌드박스에 전달하지 않는다

**어기면** — 악의적 저장소 하나로 개발자 머신 또는 운영 호스트가 장악된다.

## S-4. 시크릿은 코드·로그·LLM 프롬프트 어디에도 넣지 않는다 🔴

- 하드코딩 금지. 값은 환경변수 또는 Secret Manager 에서만 — 새 변수는 [`.env.example`](../../../.env.example) 에 같은 커밋으로 반영
- **LLM 프롬프트가 가장 놓치기 쉬운 유출 경로다.** 저장소 컨텍스트를 모델에 넘기기 전 `.env`·`*.pem`·`*.key`·`credentials` 류를 배제하고, 토큰 패턴(`ghp_` · `gho_` · `github_pat_` · `AKIA` · `xox`)을 스크럽한다
- 로그·`AgentRun.errorMessage`·PR 본문에 토큰이 섞이지 않게 마스킹한다 — [`logging.md`](../conventions/logging.md)
- 커밋 시점 차단: [`../../scripts/secret-scan.sh`](../../scripts/secret-scan.sh)

**어기면** — 토큰이 공개 저장소·모델 제공자 로그로 나간다. 회수는 폐기·재발급뿐이다.

## S-5. 대상 저장소의 기여 규약을 우리 규약보다 우선한다 🔴

우리 커밋·PR 컨벤션은 **이 저장소 안에서만** 유효하다. 대상 저장소에 나가는 산출물은 그쪽 규약을 따른다.

| 수집 대상 | 나오는 제약 |
|---|---|
| `CONTRIBUTING.md` | DCO sign-off · CLA · 커밋 메시지 형식 · 이슈 참조 필수 여부 |
| `.github/PULL_REQUEST_TEMPLATE.md` | PR 본문 형식 |
| `AGENTS.md` · `CLAUDE.md` | **AI 기여 허용 여부** — 금지하는 저장소가 있다 |
| 빌드 설정 | Java 버전 · 빌드/테스트 명령 · 포맷터 |

- `RepositoryPolicy` 없이 구현 단계로 넘어가지 않는다
- **AI 생성 기여를 금지하는 저장소는 후보에서 제외**한다. 정책 파싱 실패는 「허용」이 아니라 「보류」다

**어기면** — 규약 위반 PR 은 읽히지 않고 닫히며, 반복되면 저장소 차원에서 차단된다.

## S-6. 사람의 승인 지점을 코드로 우회하지 않는다 🔴

PRD §20 이 정한 승인 지점은 **Draft PR 이후 사람의 검토**다.

- 후보 선정(`SELECTED`) · 구현 착수 · PR 생성은 API 호출로 트리거되는 **명시적 행위**다. 스케줄러가 끝까지 자동으로 흘려보내지 않는다
- 재시도 상한(`agent.execution.max-retries`)을 코드에서 무한으로 바꾸지 않는다 — 상한 소진은 `FAILED` 이고, 그 자체가 사람에게 넘기는 신호다
- 상태머신의 종단 상태(`PR_CREATED`·`REJECTED`·`FAILED`)에서 나가는 전이를 만들지 않는다

**어기면** — 「사람이 최종 승인한다」는 제품 정의가 무너진다. 이 제품의 존재 이유가 사라진다.

---

## 요약 — 리뷰에서 이것만은 본다

| # | 한 줄 | 정적 탐지 |
|---|---|---|
| S-1 | push 대상이 Fork 인가 (어설션 있는가) | 부분 — [`safety-boundary-check.sh`](../../scripts/safety-boundary-check.sh) |
| S-2 | PR 이 draft 고정인가 · 머지/ready 호출이 없는가 | 부분 — 위 훅 |
| S-3 | 대상 저장소 실행이 샌드박스 경유인가 | 부분 — 위 훅 |
| S-4 | 시크릿이 코드·로그·프롬프트에 없는가 | 부분 — [`secret-scan.sh`](../../scripts/secret-scan.sh) |
| S-5 | `RepositoryPolicy` 를 읽고 따르는가 | ❌ 리뷰 전용 |
| S-6 | 승인 지점·재시도 상한이 살아 있는가 | ❌ 리뷰 전용 |

정적 탐지가 「부분」인 것은 **훅이 문자열만 본다**는 뜻이다. 호출 그래프를 따라가야 아는 위반은 잡히지 않으므로,
훅 통과가 곧 합격이 아니다. 판단은 [`pr-review`](../../skills/pr-review/SKILL.md) 와 사람이 한다.
