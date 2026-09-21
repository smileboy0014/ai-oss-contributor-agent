---
description: 변경사항을 분석하여 PR을 생성합니다.
---

# PR 생성

변경사항을 분석하여 이 저장소의 컨벤션에 맞는 PR 을 생성합니다.

> ⚠️ **이 저장소의 PR** 이다. 에이전트가 대상 저장소에 만드는 Draft PR 과는 완전히 다른 것이다 —
> 그쪽은 [`safety-boundaries.md`](../../rules/context/safety-boundaries.md) S-2·S-5 를 따르고, 본문은 대상 저장소 템플릿을 쓴다.

## 입력

- `$ARGUMENTS`: `--draft` (초안 PR 생성)

## 프로세스

### 1단계: 검증

1. 현재 브랜치 확인 (`main` 이 아닌지) — `main` 에서는 PR 생성 불가
2. **빌드·정적검사 실행**

   ```bash
   ./gradlew check
   ```

   - 실패 시 중단
   - `./gradlew` 가 없으면 **「통과」라고 하지 않는다.** 사실을 보고하고 사용자 판단을 받는다
3. 미커밋 변경사항 확인 (있으면 커밋 안내)
4. 기존 PR 여부 확인 — `gh pr view --json number,url,state`
5. 리모트 브랜치 동기화 상태 확인 — `git push -u origin {branch}`

### 2단계: 변경사항 분석

1. `git log main..HEAD` 로 전체 커밋 분석
2. `git diff main...HEAD` 로 변경 범위 파악
3. 변경 유형 분류 (`feat`/`fix`/`refactor`/`test`/`docs`/`chore`/`perf`/`style`/`ci`)
4. **scope 결정** — [`commit-convention.md`](../../rules/conventions/commit-convention.md) 참조
   - 도메인: `repository` · `issue` · `candidate` · `agent` · `pr` · `support`
   - 공통: `build` · `infra` · `docs` · `claude`
   - 여러 도메인이 섞였으면 PR 분할 권유 (도메인 간 인터페이스 변경은 예외)

### 3단계: 안전 경계 판정 (필수)

diff 를 훑어 [`safety-boundaries.md`](../../rules/context/safety-boundaries.md) S-1~S-6 중 닿는 조항을 판정한다.

| 신호 | 조항 |
|---|---|
| push·remote·Fork 좌표 | S-1 |
| PR 생성·머지·ready·리뷰어·코멘트 API | S-2 |
| `ProcessBuilder`·`Runtime.exec`·docker·대상 저장소 빌드 | S-3 |
| 토큰·키·로그·LLM 프롬프트 구성 | S-4 |
| `RepositoryPolicy`·`CONTRIBUTING`·`AGENTS.md` 파싱 | S-5 |
| 상태 전이·스케줄러·재시도 상한 | S-6 |

**닿으면 PR 본문에 「안전 경계」 절을 반드시 만든다.** 조항 코드와 「어떻게 지켰는가」를 적는다.
닿지 않으면 그 절을 생략한다 — 빈 절을 남기면 다음 PR 에서도 관성으로 비워 둔다.

### 4단계: 계약 표면 판정

diff 에 아래가 있으면 본문 Summary 바로 아래에 「계약 표면 변경」 절을 만든다.

- **도메인 간 인터페이스** — 능력 인터페이스 시그니처, 다른 도메인이 쓰는 UseCase 공개 메서드
- **DB 스키마** — 엔티티 추가·컬럼 변경·마이그레이션

리뷰가 계약부터 보도록 만드는 장치다. **PR 을 나누지는 않는다** — 단일 저장소·1인 개발에서는 과하다.

### 5단계: 의사결정 기록 수집

코드 변경사항을 분석하여 **리뷰어가 "왜 이렇게 했는가"를 이해할 수 있도록** 의사결정 사항을 수집한다.

**수집 대상:**
- 여러 선택지 중 특정 방법을 고른 이유
- 기존 패턴과 다른 접근을 한 이유
- 테스트 시나리오의 설계 의도

**포함 기준:**
- "왜 A 대신 B 를 선택했는가"가 코드만으로는 파악이 어려운 경우 → 포함
- 단순 버그 수정이나 자명한 변경 → 생략 (섹션 자체를 제외)
- 🔴 **안전 경계에 닿는 변경은 항상 포함** — 판단 근거가 남지 않으면 다음 사람이 같은 자리를 다시 느슨하게 만든다
- **미결(Q-1~Q-10)을 가정으로 채운 경우 항상 포함** — 가정과 「틀리면 어디를 고쳐야 하는가」를 함께

### 6단계: PR 제목 & 본문

**제목 형식** ([`pr-convention.md`](../../rules/conventions/pr-convention.md) 필수 준수):

```
[#{issue}] <type>(<scope>): <subject>
```

- 이슈가 없으면 `[#…]` 를 생략한다
- 70자 이내 (GitHub UI 잘림 방지)
- `<subject>`: 한글 OK, 명령형 현재 시제, 마침표 없음

**예시:**
```
[#12] feat(issue): GitHub open 이슈 증분 수집
[#34] fix(agent): 샌드박스 타임아웃 시 컨테이너 누수 수정
chore(build): Maven → Gradle 전환
```

**본문 형식:**

```markdown
## Summary
<1-3줄 요약 — "왜 필요한지"를 맨 앞에>

## 계약 표면 변경 (해당 시)
- <능력 인터페이스 / UseCase 공개 메서드 / 스키마가 어떻게 바뀌는가>

## Changes
- <변경점 1>
- <변경점 2>

## Test Plan
- [ ] `./gradlew build` 통과
- [ ] <검증 1>

## 안전 경계 (해당 시)
- [S-?] <어느 조항에 닿는가 · 어떻게 지켰는가>

## 의사결정 기록 (해당 시)

### {결정 제목}
{대안 비교 및 채택 이유. 표 형식 권장}

### 가정 (미결을 채운 경우)
| 가정 | 근거 | 틀리면 고칠 곳 |
|---|---|---|

## Related
- Issue: #{issue}
- Plan: `docs/plans/PLAN-{issue}.md` (해당 시)
```

### 7단계: PR 생성

- **base 브랜치**: `main` (고정)
- 기본: `gh pr create --base main`
- `--draft` 옵션: `gh pr create --base main --draft`
- 제목과 본문은 HEREDOC 으로 전달 (개행 안전)

## 브랜치 이름에서 이슈 번호 추출

```
feature/12_github-issue-scanner   → 12
fix/34_sandbox-container-leak     → 34
chore/_gradle-migration           → (이슈 없음)
```

## 필수 규칙

- **PR 제목은 컨벤션 필수 준수** (`[#{issue}] <type>(<scope>): <subject>`)
- **한 PR 에 여러 도메인을 섞지 않음** (도메인 간 인터페이스 변경은 예외)
- `main` 브랜치에서는 PR 생성 불가
- 미커밋 변경사항이 있으면 먼저 커밋 안내
- 🔴 **안전 경계에 닿는 변경은 본문에 조항과 근거 필수**
- 새 환경변수가 있으면 `.env.example` 반영 여부 확인
- 디렉토리 구조가 바뀌었으면 `README.md` 갱신 여부 확인
- 도메인 구조·상태머신·스키마가 바뀌었으면 `.claude/codemaps/` 갱신 여부 확인

## 연계 커맨드

- `/plan` — PR 전 구현 계획
- `/commit` — 커밋 메시지 생성
- `/pr-review` — PR 생성 후 리뷰
- `/work` — Phase 5 에서 이 스킬에 위임
