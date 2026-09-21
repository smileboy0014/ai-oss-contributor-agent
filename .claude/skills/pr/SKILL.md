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

### 8단계: 이슈 메타데이터 승계 (이슈가 있을 때 필수)

**PR 은 이슈의 라벨·마일스톤·프로젝트를 그대로 물려받는다.** 사람이 나중에 붙이는 것에 기대지 않는다.

```bash
ISSUE=5                      # 브랜치명에서 추출한 번호
PR=$(gh pr view --json number --jq .number)

LABELS=$(gh issue view "$ISSUE" --json labels --jq '[.labels[].name] | join(",")')
MILESTONE=$(gh issue view "$ISSUE" --json milestone --jq '.milestone.title // empty')

[ -n "$LABELS" ]    && gh pr edit "$PR" --add-label "$LABELS"
[ -n "$MILESTONE" ] && gh pr edit "$PR" --milestone "$MILESTONE"

# 프로젝트 보드 — 이슈가 올라가 있는 보드에 PR 도 올린다
gh issue view "$ISSUE" --json projectItems --jq '.projectItems[].title'
gh project item-add <번호> --owner <소유자> --url "$(gh pr view "$PR" --json url --jq .url)"
```

**왜 자동인가** — 라벨이 없으면 PR 목록에서 「이게 무슨 작업인지」가 제목에만 남는다.
마일스톤이 없으면 Phase 진척이 이슈로만 집계되고 실제 산출물인 PR 은 빠진다.
사람이 매번 붙이는 규칙은 **반드시 빠진다.**

**승계 규칙**

| 항목 | 처리 |
|---|---|
| 라벨 | 이슈 것을 **전부** 가져온다. `decision`·`safety` 도 포함 — PR 에서도 같은 무게다 |
| 마일스톤 | 이슈 것을 그대로 |
| 프로젝트 | 이슈가 속한 보드에 PR 도 추가 (보드 자동화가 이미 넣었으면 중복 추가하지 않는다) |
| 이슈가 없는 작업 | 건너뛴다. 근거 1줄을 결과 보고에 남긴다 |

⚠️ **붙였는지 확인까지 한다.** `gh pr edit` 은 라벨이 저장소에 없으면 조용히 실패한다.

```bash
gh pr view "$PR" --json labels,milestone --jq \
  '"labels=[\(.labels|map(.name)|join(","))] milestone=\(.milestone.title // "없음")"'
```

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
- **이슈가 있으면 라벨·마일스톤·프로젝트를 승계하고, 붙었는지 확인까지 한다** (8단계)

## 연계 커맨드

- `/plan` — PR 전 구현 계획
- `/commit` — 커밋 메시지 생성
- `/pr-review` — PR 생성 후 리뷰
- `/work` — Phase 5 에서 이 스킬에 위임
