---
description: PR 코드 변경사항과 코멘트를 리뷰합니다.
---

# PR Review

PR 의 코드 변경사항을 리뷰하거나, PR 에 달린 코멘트를 분석하여 대응 방안을 제시합니다.

## 사용 시점

- PR 생성 후 코드 리뷰 진행
- 동료 또는 AI review bot 의 PR 코멘트 검토
- PR 코멘트에 대한 대응 방안 수립

## 사용법

```
/pr-review                        # 현재 브랜치 PR 코드 리뷰 (기본)
/pr-review --comments             # PR 코멘트 수집 및 대응 방안 제시
/pr-review <PR번호>                # 특정 PR 리뷰
/pr-review <PR번호> --comments     # 특정 PR 코멘트 검토
```

## 워크플로우

```
기본 모드:    PR 조회 → 변경사항 분석 → 코드 리뷰 → 결과 출력
코멘트 모드:  PR 조회 → 코멘트 수집 → 분류 → 대응 방안 → 결과 출력
```

## 동작 방식

### 사전 검증

#### 1. GitHub CLI 인증 확인

```bash
gh auth status
```

**미인증 시** → 중단하고 안내:

> GitHub CLI 인증이 필요합니다. `gh auth login` 을 실행해주세요.

#### 2. PR 존재 확인

```bash
gh pr view <PR번호> --json number,title,state,url,isDraft
gh pr view --json number,title,state,url,isDraft      # 현재 브랜치 기반
```

**PR 이 없는 경우** → 중단하고 안내:

> 현재 브랜치에 연결된 PR 이 없습니다. `/pr` 로 PR 을 먼저 생성해주세요.

---

### 기본 모드: 코드 리뷰

#### 1단계: 변경사항 수집

```bash
gh pr diff <PR번호> --name-only
gh pr diff <PR번호>
```

#### 2단계: 스코프 판단

변경 파일 경로로 리뷰 초점을 결정한다.

| 스코프 | 기준 | 적용 체크리스트 |
|---|---|---|
| `repository` | `com/ossagent/repository/**` | 공통 + **S-5**(기여 규약 파싱) |
| `issue` | `com/ossagent/issue/**` | 공통 + 레이트리밋 · 멱등 수집 |
| `candidate` | `com/ossagent/candidate/**` | 공통 + **S-6**(상태머신·승인 지점) |
| `agent` | `com/ossagent/agent/**` | 공통 + **S-3**(샌드박스) · **S-4**(프롬프트) · 재시도·비용 |
| `pullrequest` | `com/ossagent/pullrequest/**` | 공통 + **S-1**(push 대상) · **S-2**(draft 고정) |
| `support`·`config` | `com/ossagent/support/**`·`config/**` | 공통 |
| 하네스·문서 | `.claude/`, `docs/`, 루트 설정 | 공통(문서 정합성)만 |

#### 3단계: 코드 리뷰 수행

##### A. 🔴 안전 경계 — 무조건 블로킹

**나머지가 아무리 좋아도 통과시키지 않는다.** 여기서의 사고는 외부 OSS 커뮤니티에 직접 나가고, 지워도 메일 알림·포크·캐시에 남는다.

> ⚠️ **인터페이스만 보고 판정하지 않는다.** 능력 인터페이스는 깨끗한데 `adapter/out` 구현체에서 upstream 에 push 하는 식이다.
> 아래 항목은 **구현체 파일을 반드시 열어** 확인한다.

| 조항 | 코드에서 무엇을 보는가 |
|---|---|
| **S-1** 원본 저장소 쓰기 금지 | ⓐ push 호출(`setRemote`·`push`·`git push`)의 **remote 좌표가 어디서 오는가** — `repository.getUrl()`(upstream) 이면 블로킹, Fork 좌표여야 한다 ⓑ push 직전 **owner 일치 어설션이 존재하는가** — 없으면 블로킹 ⓒ 브랜치 삭제·force push 도 Fork 범위인가 ⓓ 토큰에 원본 write 스코프를 요구하는 설정이 있는가 |
| **S-2** 항상 draft · 자동 머지 금지 | ⓐ PR 생성에 `draft: true` 가 **고정**인가 — 설정·플래그로 끌 수 있으면 블로킹 ⓑ `merge`·`mergePullRequest`·`readyForReview`·`requestReviewers` 호출이 **존재하는가** — 존재 자체가 블로킹 ⓒ 이슈·리뷰 코멘트 등 대상 저장소에 글을 남기는 경로가 사람 승인 없이 열려 있는가 |
| **S-3** 샌드박스 밖 실행 금지 | ⓐ `Runtime.exec`·`ProcessBuilder` 로 **호스트에서** 대상 저장소 빌드를 돌리는가 ⓑ 컨테이너에 네트워크·CPU·메모리·타임아웃 제한이 **실제로 걸리는가**(설정값을 읽기만 하고 안 쓰는 경우 포함) ⓒ `/var/run/docker.sock` 마운트 ⓓ 우리 시크릿 환경변수가 샌드박스로 전달되는가 ⓔ 타임아웃·실패 경로에서 **컨테이너가 정리되는가** |
| **S-4** 시크릿 유출 금지 | ⓐ 하드코딩된 토큰·키 ⓑ **LLM 프롬프트 구성 지점에서 스크럽이 있는가** — `.env`·`*.pem`·`*.key`·`credentials` 배제 + 토큰 패턴 제거. 가장 놓치기 쉬운 유출구다 ⓒ 로그·`AgentRun.errorMessage`·PR 본문에 토큰이 섞이는가 ⓓ 예외 메시지에 원문이 실려 나가는가 |
| **S-5** 대상 저장소 규약 우선 | ⓐ `RepositoryPolicy` 없이 구현 단계로 넘어가는 경로가 있는가 ⓑ 우리 커밋·PR 형식을 대상 저장소 산출물에 강요하는가 ⓒ **AI 기여 금지 저장소 판정** — 파싱 실패를 「허용」으로 처리하면 블로킹(「보류」여야 한다) |
| **S-6** 승인 지점 우회 금지 | ⓐ `SELECTED` 전이가 **사람 행위**로만 일어나는가 — 스케줄러가 끝까지 자동으로 흘려보내는 경로가 있으면 블로킹 ⓑ 재시도 상한(`agent.execution.max-retries`)이 살아 있는가 ⓒ 종단 상태(`PR_CREATED`·`REJECTED`·`FAILED`)에서 나가는 전이를 만들었는가 |

**정적 훅([`safety-boundary-check.sh`](../../scripts/safety-boundary-check.sh))이 통과했다고 합격이 아니다.** 훅은 문자열만 본다. 호출 그래프를 따라가야 아는 위반은 여기서 잡는다.

##### B. 공통 규칙 준수 검토

1. **아키텍처 규율** — [`architecture.md`](../../rules/conventions/architecture.md) 규율 4줄
   - 🔴 `domain` 패키지의 import 에 **Spring·HTTP·GitHub·LLM 타입**이 있는가 (JPA 어노테이션만 예외)
   - 진입점이 유형별(web·scheduler·event)로 분리돼 있는가
   - 능력 인터페이스가 domain 에 **능력 이름**으로 있는가 · 기술 이름(`GitHub~`·`Docker~`)이 domain 에 있으면 반려
   - 남의 도메인 엔티티·Spring Data 인터페이스를 직접 import 하는가
2. 🔴 **트랜잭션 경계와 대외 호출 위치**
   - `@Transactional` 이 UseCase 에 있는가
   - **트랜잭션 안에 GitHub·LLM·샌드박스 호출이 있는가** — 샌드박스는 최대 30분이다. 커넥션이 30분 잡힌다
   - 대외 호출 결과의 영속화가 짧은 트랜잭션으로 분리돼 있는가
3. **시각 처리** — `Instant.now()` 직접 호출이 있는가. `Clock` 주입이어야 한다
4. **대외 호출 방어** — 타임아웃·재시도가 adapter/out 에 **명시**돼 있는가. GitHub 2차 레이트리밋이 403 으로 온다는 점을 처리하는가
5. **멱등성** — 스캔 재실행·재시도·이벤트 재수신에서 중복 후보·중복 PR 이 생기지 않는가
6. **예외 처리** — HTTP 매핑이 `support/web` 에 있는가(도메인 예외에 `@ResponseStatus` 금지) · 예외를 삼키지 않는가 · 스택트레이스를 잃지 않는가
7. **로깅** — [`logging.md`](../../rules/conventions/logging.md) 의 마스킹 규칙. `System.out.println` 금지
8. **테스트 커버** — 추가 로직에 대응하는 테스트가 있는가. 안전 경계에 닿는 변경은 **테스트가 필수**다
9. **네이밍** — `glossary.md` 의 용어 구분(`이 저장소` vs `대상 저장소`, `OssRepository` vs `OssRepositoryRepository`)을 지키는가

##### C. 개선 리뷰

10. **비즈니스 로직 정합성** — PRD/PLAN 대비 구현 일치, 엣지 케이스 누락
11. **성능·비용** — N+1 쿼리 · 불필요한 LLM 재호출 · 저장소 전체를 프롬프트에 넣는 패턴
12. **가독성** — 복잡한 조건문, 매직 넘버, 네이밍
13. **일관성** — 여러 파일에 걸친 패턴 통일, 중복 코드
14. **문서 동기화** — 도메인·스키마·상태가 바뀌었는데 `codemaps/` 가 그대로인가 · 새 환경변수가 `.env.example` 에 없는가

#### 4단계: 결과 출력

```markdown
## PR Review 결과

**PR**: #{number} {title}
**URL**: {url}
**스코프**: {도메인}
**깊이**: low / medium / high — {판정 근거 1줄}

### 🔴 안전 경계

- **[블로킹]** [S-?] `{경로}` L{n}: {지적 내용} → {권장 조치}
- 또는: 접촉 없음 ({근거})

### 규칙 준수 검토

#### 파일: {경로}
- **[블로킹]** L{n}: {지적 내용} → {권장 조치}
- **[권장]** L{n}: {지적 내용}

### 개선 리뷰

#### 비즈니스 로직 정합성
- **[권장]** {경로} L{n}: {지적 내용}

#### 성능·비용
- **[정보]** {경로} L{n}: {지적 내용}

### 요약
- 🔴 안전 경계: N건 (**반드시 수정 — 머지 차단**)
- 블로킹: N건
- 권장: N건
- 정보: N건
```

---

### 코멘트 모드: PR 코멘트 검토

`--comments` 옵션으로 실행. 상세 절차는 [`references/review-guide.md`](./references/review-guide.md) 참조.

#### 요약

1. PR 리뷰/일반/리뷰요약 코멘트 수집 (gh api)
2. 카테고리 분류 (안전 경계/버그/보안/제안/질문/스타일/승인)
3. 각 코멘트에 대응 방안 (수정/답변/무시) 제시
4. 사용자 승인 후 수정 진행

## 필수 규칙

1. **gh CLI 의존** — 모든 GitHub 데이터는 `gh` CLI 로 조회
2. **코멘트 누락 방지** — 리뷰 코멘트 + 일반 코멘트 + 리뷰 요약 모두 수집
3. **대응 방안 필수** — 모든 코멘트에 대응 방안 제시 (무시 가능 포함)
4. **수정 전 승인** — 코멘트 모드에서 남이 단 지적을 반영하는 코드 수정은 사용자 승인 후 진행
   (`/work` lifecycle 안에서 **자기 PR** 의 findings 를 반영하는 것은 승인 대상이 아니다)
5. 🔴 **무조건 블로킹** — 안전 경계 S-1~S-6 위반. 예외 없다
6. **구현체를 연다** — 안전 경계 판정에서 인터페이스만 보고 통과시키지 않는다
7. **훅 통과 ≠ 합격** — 정적 훅은 문자열만 본다

## 연계 커맨드

- `/pr` — PR 생성 (PR Review 전 단계)
- `/commit` — 리뷰 반영 후 커밋
- `/work` — Phase 5.5 에서 이 스킬에 위임
