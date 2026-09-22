---
description: 프로젝트의 소스코드 구조를 분석하여 .claude/codemaps/ 하위 Codemap 문서를 생성하거나 최신화합니다.
---

# Update Codemap

프로젝트의 소스코드 구조를 분석하여 Codemap 문서를 생성하거나 최신화합니다.

> ⚠️ **이 저장소는 아직 API 경계만 있는 골격이다.**
> 이슈 수집·LLM 호출·샌드박스 실행·PR 생성은 **구현이 0**이고, ERD 7테이블은 전부 실재하지만(V1~V3) **읽고 쓰는 코드가 없다.**
> 따라서 현재 codemap 은 **소스가 아니라 [PRD](../../../docs/ai-oss-contributor-agent-prd.md) 기반**으로 작성돼 있다.
> 갱신의 주된 경로는 두 가지다 — ① 새로 들어온 소스를 반영 ② PRD·미결 결정이 바뀐 것을 반영.
> **"설계상 있음"과 "실제로 있음"을 문서에서 반드시 구분해서 유지한다.** 이 구분이 무너지면 다음 세션이 없는 코드를 있다고 믿고 작업한다.

## 사용 시점

- **최초 실행**: 프로젝트에 codemaps가 없을 때
- **전체 갱신**: 대규모 구조 변경 후
- **수동 갱신**: codemaps가 오래되었을 때
- **미결 종결 시**: [`open-questions.md`](../../rules/context/open-questions.md)의 항목이 닫혀 설계가 확정됐을 때

## 대상 파일

```
.claude/codemaps/
├── architecture.md   # 도메인 5개 + support/config · 헥사고날 라이트 · 외부 경계 4개 · 실행 프로필
├── data.md           # ERD 7테이블 · 인덱스 · 멱등키 · 시크릿 금지 컬럼
└── domain.md         # 후보 상태머신 · 이슈 필터 · 재시도 전략 · 불변식
```

기준 프로젝트에 있던 `screens.md`는 **이 프로젝트에 없다** — UI가 없다. 만들지 않는다.

## 업데이트 절차

### 1. 기존 codemaps 확인

```bash
ls .claude/codemaps/
```

- **있으면**: 기존 파일 읽고 갱신
- **없으면**: 신규 생성

### 2. 변경 감지

각 codemap 파일의 최종 업데이트 날짜(하단 변경 이력) 기준으로 변경사항을 감지합니다.

```bash
git log --since="<최종업데이트날짜>" --name-only --pretty=format: -- src/ docs/ .claude/rules/ | sort -u
```

변경 없으면 업데이트 종료.

### 3. 프로젝트 구조 스캔

변경이 감지된 영역만 스캔합니다.

```bash
# 도메인 패키지 트리
find src/main/java/com/ossagent -type d | sort

# 도메인별 레이어 구성 — domain / application / adapter 가 다 있는지
find src/main/java/com/ossagent -type d -name 'domain' -o -type d -name 'application' -o -type d -name 'adapter' | sort

# 엔티티 (실재하는 테이블 판별)
grep -rl '@Entity' src/main/java/com/ossagent

# 능력 인터페이스 후보 — domain 에 선언된 인터페이스
grep -rl 'interface' src/main/java/com/ossagent/*/domain/

# 상태·enum
grep -rl 'enum ' src/main/java/com/ossagent
```

**해당 영역의 소스가 아직 없으면 이 단계를 건너뛰고 PRD·미결 대장 대조로 대체한다.**
그리고 문서에 **"설계만 있음"** 표시를 유지한다.

### 4. 문서별 업데이트 체크리스트

#### architecture.md

- [ ] 도메인이 추가·삭제·개명되었는가 (`repository`·`issue`·`candidate`·`agent`·`pullrequest`·`support`)
- [ ] **능력 인터페이스**가 추가·삭제·시그니처 변경되었는가 (`IssueSource`·`CodeSandbox`·`DraftPrPublisher` 등)
- [ ] 능력 인터페이스의 **구현체(adapter/out)** 가 새로 생겼는가 — 「설계만」에서 「실재」로 넘어간 항목
- [ ] 외부 경계 4개(GitHub API·LLM API·Docker 샌드박스·PostgreSQL/Redis)의 사용 방식이 바뀌었는가
- [ ] **실행 프로필(`web`/`worker`)이 분리되었는가** — Q-3. 분리되면 진입점 `@Profile` 배치도 함께 기록
- [ ] 도메인 간 호출 방향이 바뀌었는가 (application 경유 원칙 유지 여부)
- [ ] **모듈 승격 기준**([`architecture.md`](../../rules/conventions/architecture.md))의 체크 항목이 충족되었는가

#### data.md

- [ ] 테이블·컬럼이 추가·삭제되었는가 (ERD 7테이블 대비)
- [ ] **실재하는 테이블 목록**이 바뀌었는가 — `@Entity` 개수와 문서의 「실재」 표시가 일치하는가
- [ ] 인덱스가 추가·변경되었는가 (조회 패턴과 함께)
- [ ] **멱등키**가 추가·변경되었는가 (스캔 재실행 · 후보 중복 생성 방지)
- [ ] **마이그레이션 파일이 추가되었는가** — `db/migration/V*.sql`. 엔티티 변경과 짝이 맞는지 확인한다
- [ ] 마이그레이션에 **벤더 고유 문법이 들어갔는가** — H2·PostgreSQL 공통이어야 한다 (Q-2 · Q-2b)
- [ ] 대용량 텍스트 컬럼(diff·프롬프트·분석 원문)의 취급이 바뀌었는가
- [ ] **시크릿이 절대 들어가면 안 되는 컬럼** 지정이 유효한가 [S-4]

#### domain.md

- [ ] 후보 상태(`CandidateStatus`)가 추가·삭제되었는가
- [ ] 상태 **전이**가 추가·삭제되었는가 — 특히 🔴 **종단 상태(`PR_CREATED`·`REJECTED`·`FAILED`)에서 나가는 전이가 생기지 않았는가** [S-6]
- [ ] 이슈 필터 규칙(종료됨·활성 PR·요구사항 불명확·대규모 변경)이 바뀌었는가
- [ ] 우선 탐색 라벨 목록이 바뀌었는가
- [ ] 이슈 분석 산출물 스키마(category·difficulty·confidence 등)가 바뀌었는가
- [ ] **재시도 전략**이 바뀌었는가 — `max-retries` 값·카운터 단위(Q-6)
- [ ] 검증 파이프라인 순서(컴파일→유닛→통합→포맷→diff→AI리뷰)가 바뀌었는가
- [ ] **불변식 목록**이 추가·삭제되었는가 — 안전 경계와의 연결이 유지되는가

### 5. 파일 트리 생성 방법

```bash
# 도메인별 레이어 트리 (build 산출물 제외)
find src/main/java/com/ossagent -type d -not -path '*/build/*' | sed 's|src/main/java/com/ossagent|.|' | sort

# 도메인별 파일 수 — 「비어 있음」 판별용
for d in repository issue candidate agent pullrequest support config; do
  printf "%-14s %s\n" "$d" "$(find src/main/java/com/ossagent/$d -name '*.java' 2>/dev/null | wc -l)"
done
```

`package-info.java` 하나뿐인 도메인은 **비어 있는 것**이다. 문서에 그렇게 적는다.

### 6. 변경 이력 구성

각 codemap 파일 하단에 변경 이력을 테이블로 기록합니다.

```markdown
## 변경 이력

| 일자 | 작성자 | 변경 내용 |
|------|--------|----------|
| 2026-09-18 | {GitHub username} | 초안 생성 |
```

#### 규칙

- 파일 최하단에 `## 변경 이력` 테이블로 기록
- 작성자: GitHub username 사용
- 신규 항목은 테이블 최상단에 추가

### 7. 업데이트 후 필수 작업

1. **「설계만」 / 「실재」 표시 재확인** — 이번 갱신으로 넘어온 항목이 정확히 반영됐는가
2. **상호 참조 확인** — 문서 간 링크가 유효한지 (`../rules/context/*`, `../rules/conventions/*`, `../../docs/*`)
3. **CLAUDE.md 동기화** — 인덱스 경로가 실제 파일과 일치하는지
4. **README.md 동기화** — 디렉토리 구조 섹션이 실제와 일치하는지

## 주의사항

- **주석은 간결하게**: 파일 역할만 명시 (상세 설명 불필요)
- **중복 제거**: 여러 파일에 같은 정보 반복 금지. 안전 경계 본문은 [`safety-boundaries.md`](../../rules/context/safety-boundaries.md)에 두고 codemap은 참조만 한다
- **파일 트리 형식 유지**: 들여쓰기와 트리 문자 일관성 유지
- **추측으로 채우지 않는다**: 소스에 없는 것을 있는 것처럼 쓰지 않는다. 설계 단계면 그렇게 표시한다

## 연계 커맨드

- `/work` — Phase 4(문서 동기화)에서 자동 호출
- `/plan` — 계획 수립 시 codemap을 입력으로 읽음
