---
description: 변경사항을 분석하여 컨벤션에 맞는 커밋 메시지를 생성합니다.
---

# 커밋 메시지 생성

**포맷의 단일 진실 원천은 [`rules/conventions/commit-convention.md`](../../rules/conventions/commit-convention.md) 다.** 이 스킬은 그 규칙을 실행하는 절차만 정의한다.

> ⚠️ 이 규칙은 **이 저장소 안에서만** 유효하다. 대상 저장소에 나가는 커밋은 그쪽 `CONTRIBUTING.md` 를 따른다 —
> [`safety-boundaries.md`](../../rules/context/safety-boundaries.md) S-5. 우리 형식을 남의 저장소에 강요하는 코드가 있으면 반려다.

## 포맷

```
<type>(<scope>): <subject>

<body>

<footer>
```

### type

`feat` · `fix` · `refactor` · `perf` · `test` · `docs` · `chore` · `style` · `ci` · `revert`

### scope — 도메인 패키지 이름

경로에서 바로 판별된다.

| 대상 | scope |
|---|---|
| `com.ossagent.repository` | `repository` |
| `com.ossagent.issue` | `issue` |
| `com.ossagent.candidate` | `candidate` |
| `com.ossagent.agent` | `agent` |
| `com.ossagent.pullrequest` | `pr` |
| `com.ossagent.support` · `config` | `support` |
| 빌드·의존성 (`build.gradle.kts` · `gradle/`) | `build` |
| 인프라 (`docker-compose.yml` · `application.yml`) | `infra` |
| 문서 (`README` · `docs/`) | `docs` |
| Claude 하네스 (`.claude/`) | `claude` |

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

## 안전 경계 조항 인용

[`safety-boundaries.md`](../../rules/context/safety-boundaries.md) 의 조항에 근거한 변경은 **코드를 subject 또는 body 에 남긴다.**
조항이 개정돼도 어디를 다시 볼지 코드 하나로 특정된다.

```
feat(pr): push 직전 Fork owner 단언 추가 [S-1]

원격 URL 의 owner 가 GITHUB_FORK_OWNER 와 일치하지 않으면 push 하지 않고
IllegalStateException 으로 중단한다. 설정 오타 하나로 upstream 에 push 를
시도하는 경로가 있었다.

Refs: #12
```

## 프로세스

1. `git status` + `git diff --cached` 로 변경 범위 확인
2. **scope 결정** — 변경된 패키지 경로에서 도메인을 판별
3. 여러 scope 가 섞였으면 **커밋 분리를 제안**한다 (도메인 간 인터페이스 변경은 예외)
4. 리팩토링 + 기능 추가가 섞였으면 분리
5. type 결정 → subject 작성 → body 에 「왜」
6. 안전 경계 근거가 있으면 조항 코드 인용
7. 사용자 확인 후 커밋

## 커밋 시 도는 훅 3개

`git commit` 은 [`settings.json`](../../settings.json) 의 `PreToolUse` 훅 세 개를 순서대로 통과해야 한다.

| 순서 | 스크립트 | 보는 것 | 실패 시 |
|---|---|---|---|
| 1 | [`secret-scan.sh`](../../scripts/secret-scan.sh) | 토큰 패턴(`ghp_`·`AKIA`·`xox`·`sk-ant-`·PRIVATE KEY) · `.env` 실값 | 커밋 차단 |
| 2 | [`safety-boundary-check.sh`](../../scripts/safety-boundary-check.sh) | S-1~S-4 의 정적 탐지 가능분 | 커밋 차단 |

[`.githooks/pre-commit`](../../../.githooks/pre-commit) 이 부른다. `./gradlew check` 는 **훅에 없다** —
CI 가 게이트다(Q-10). 로컬 테스트는 Stop 훅 `impl-test-loop.sh` 가 돌린다.

⚠️ 훅이 한 줄도 출력하지 않았다면 **등록이 안 된 것**이다 — `git config --get core.hooksPath` 확인.

- **훅을 스킵하지 않는다.** 실패하면 수정 후 새 커밋으로 재시도한다
- 안전 경계 훅의 의도된 예외는 **사유와 함께** 남긴다. 위반 라인 또는 바로 윗줄에 달면 통과한다

```java
// safety-ok: 테스트 픽스처 — 실제 push 경로 아님 (#34)
git.push().setRemote(fixtureRemote).call();
```

  이유 없는 예외는 허용하지 않는다. `safety-ok` 만 적고 사유가 없으면 반려한다
- 훅이 **한 건도 검증하지 않았으면 「통과」라고 하지 않는다.** `./gradlew` 가 없거나 대상 변경이 없으면 사유를 보고한다

## 인자

| 인자 | 동작 |
|---|---|
| (없음) | 스테이징된 변경으로 메시지 생성 |
| `--step` | body 에 `[N/총단계]` 표기 추가 (단계별 커밋용) |

## 예시

```
feat(issue): GitHub open 이슈 증분 수집

updated_at 커서와 ETag 조건부 요청으로 매 스캔 전량 조회를 피한다.
전량 조회는 Search API 레이트리밋(30 req/min)을 먼저 태운다.
X-RateLimit-Remaining 이 임계 미만이면 실패시키지 않고 지연한다 —
리밋 소진은 장애가 아니라 정상 운영 상황이다.

Refs: #12
```

```
fix(agent): 샌드박스 타임아웃 시 컨테이너가 남는 문제 수정 [S-3]

타임아웃 경로에서 컨테이너 제거를 하지 않아 실행마다 컨테이너가 쌓였다.
남은 컨테이너의 작업 디렉토리가 다음 실행에 재사용되면 앞 실행의
빌드 산출물이 다음 판정을 오염시킨다.
finally 에서 제거하고, 제거 실패는 경고 로그로 남긴다.

Refs: #34
```

## 금지

- **scope 생략** — 어느 파이프라인 단계가 움직였는지 추적이 안 된다
- `Feat:` 같은 대문자 type — 컨벤션은 소문자다
- 한 커밋에 여러 도메인 섞기
- `--no-verify` 로 훅 건너뛰기 (사용자 명시 요청 시만) — 훅이 시크릿·안전 경계를 본다
- **시크릿 값을 커밋 메시지에 적기** — 메시지도 히스토리에 영구히 남는다

## 연계

- `/pr` — PR 제목은 `[#{issue}] <type>(<scope>): <subject>` 로 같은 포맷을 쓴다
- `/work` — Phase 2 의 단계별 커밋에서 이 스킬을 호출
