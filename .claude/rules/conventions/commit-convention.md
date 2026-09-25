# 커밋 컨벤션

> ⚠️ 이 규칙은 **이 저장소 안에서만** 유효하다.
> 대상 저장소(대상 OSS)에 나가는 커밋은 그쪽 `CONTRIBUTING.md` 를 따른다 — [`safety-boundaries.md`](../context/safety-boundaries.md) S-5.
> 우리 형식을 남의 저장소에 강요하는 코드가 있으면 반려다.

## 포맷

```
<type>(<scope>): <subject>

<body>

<footer>
```

### type

- `feat` — 새 기능
- `fix` — 버그 수정
- `refactor` — 기능 변경 없는 코드 개선
- `perf` — 성능 개선
- `test` — 테스트 추가/수정
- `docs` — 문서만
- `chore` — 빌드/설정/의존성
- `style` — 포맷팅
- `ci` — CI 설정
- `revert` — 이전 커밋 되돌림

### scope

도메인 패키지 이름을 쓴다. 경로에서 바로 판별된다.

| 대상 | scope |
|---|---|
| `com.ossagent.repository` | `repository` |
| `com.ossagent.issue` | `issue` |
| `com.ossagent.candidate` | `candidate` |
| `com.ossagent.agent` | `agent` |
| `com.ossagent.pullrequest` | `pr` |
| `com.ossagent.support` · `config` | `support` |
| 빌드·의존성 | `build` |
| 인프라 (`docker-compose` · `application.yml`) | `infra` |
| 문서 (`README`·`docs/`) | `docs` |
| Claude 하네스 (`.claude/`) | `claude` |

예시:
```
feat(issue): GitHub open 이슈 증분 수집
fix(agent): 샌드박스 타임아웃 시 컨테이너가 남는 문제 수정
chore(build): Maven → Gradle 전환
docs(claude): 안전 경계 6조 신설
```

### subject

- 50자 이내 · 한글 OK · 마침표 없음
- 명령형 현재 시제 — 「추가한다」가 아니라 「추가」

### body

- 「왜」와 「무엇」. 72자 줄바꿈
- 영향 범위·리스크가 있으면 반드시 언급
- **안전 경계에 닿는 변경**(push 대상 · draft 고정 · 샌드박스 · 시크릿 · 규약 준수 · 승인 지점)은 판단 근거를 남긴다

### footer

```
Refs: #12
BREAKING CHANGE: ...
```

## 안전 경계 인용

[`safety-boundaries.md`](../context/safety-boundaries.md) 의 조항에 걸리는 변경은 **코드를 남긴다.** 조항이 개정돼도 어디를 다시 볼지 특정된다.

```
feat(pr): Draft PR 생성 시 Fork owner 단언 추가 [S-1]

push 직전 원격 URL 의 owner 가 GITHUB_FORK_OWNER 와 일치하는지 확인하고,
불일치면 IllegalStateException 으로 중단한다. 설정 오타 하나로 upstream 에
push 를 시도하는 경로가 있었다.

Refs: #12
```

## 프로세스

1. `git status` + `git diff --cached` 로 변경 범위 확인
2. **scope 결정** — 변경된 패키지 경로에서 판별
3. 여러 scope 가 섞였으면 **커밋 분리를 제안**한다 (인터페이스 계약 변경은 예외)
4. 리팩토링 + 기능 추가가 섞였으면 분리
5. type 결정 → subject 작성 → body 에 「왜」
6. 안전 경계 근거가 있으면 조항 코드 인용
7. 사용자 확인 후 커밋

## 금지

- **scope 생략** — 어느 파이프라인 단계가 움직였는지 추적이 안 된다
- `Feat:` 같은 대문자 type
- 한 커밋에 여러 도메인 섞기
- `--no-verify` 로 훅 건너뛰기 (사용자 명시 요청 시만) — 훅이 시크릿·안전 경계를 본다. 우회해도 **CI 에서 다시 걸린다**

## pre-commit 훅 — **git 훅이 정본이다** (2026-09-25 개정 · #27)

`git commit` 시점에 둘이 순서대로 돈다. 싼 것이 먼저다.

| 스크립트 | 보는 것 |
|---|---|
| [`secret-scan.sh`](../../scripts/secret-scan.sh) | 토큰 패턴 · `.env` 실값 스테이징 |
| [`safety-boundary-check.sh`](../../scripts/safety-boundary-check.sh) | S-1~S-4 의 정적 탐지 가능분 |

`./gradlew check`([`pre-commit-check.sh`](../../scripts/pre-commit-check.sh))는 **훅에서 뺐다.**
CI 가 같은 일을 하고(Q-10), 커밋마다 스위트 전체를 기다릴 이유가 없다.
로컬 테스트 피드백은 Stop 훅 [`impl-test-loop.sh`](../../scripts/impl-test-loop.sh) 가 준다.

### 왜 git 훅인가 — Claude 훅만으로는 안 돌았다

원래 이 셋은 `.claude/settings.json` 의 `PreToolUse` `matcher: "Bash(git commit:*)"` 에만 걸려 있었고,
그래서 **두 경로로 새고 있었다.**

| 구멍 | 결과 |
|---|---|
| 사람이 IDE·터미널에서 직접 커밋 | Claude 를 거치지 않으므로 **아무것도 돌지 않는다** |
| `cd <path> && git commit …` · `git -C … commit` | 매처가 접두사 매칭이라 **빗나간다** (worktree 작업에서 실제로 발생) |

`#35` 리뷰에서 「훅이 실행된 흔적이 없다」로 드러났다.
[`hooks.md`](../../docs/hooks.md) 가 적어 둔 그대로다 — **훅이 조용히 죽는 경우가 가장 나쁘다.**

그래서 등록을 git 으로 옮긴다. 스크립트는 stdin 을 읽지 않고 `git diff --cached` 만 보므로
**수정 없이 그대로 git 훅이 된다.** 사람·Claude·IDE·worktree 가 전부 같은 게이트를 지난다.

```bash
git config core.hooksPath .githooks   # 클론·worktree 추가 후 1회
```

⚠️ `core.hooksPath` 는 **커밋되지 않는 로컬 설정**이다. 새 클론에서는 위 명령을 다시 쳐야 한다.

### 스킵

훅을 스킵하지 않는다. 실패하면 **수정 후 새 커밋**으로 재시도한다.
의도된 예외는 사유와 함께 남긴다 — 위반 라인 또는 바로 윗줄에 `// safety-ok: <사유>`. 사유 없는 예외는 반려.

`--no-verify` 로는 git 훅을 우회할 수 있다. **그 우회를 잡는 것이 CI 다** — CI 가 같은 스캔 2종을 다시 돌린다.
