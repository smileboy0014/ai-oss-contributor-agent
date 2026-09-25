# .claude/ — Claude Code 설정

`ai-oss-contributor-agent` 의 프로젝트 레벨 설정이다.
프로젝트 개요·도메인은 루트 [`CLAUDE.md`](../CLAUDE.md) 를 먼저 읽는다.

---

## 먼저 읽을 것 2개

| 문서 | 왜 먼저인가 |
|---|---|
| [`rules/context/safety-boundaries.md`](rules/context/safety-boundaries.md) | **넘으면 안 되는 선 6개.** 이 프로젝트의 사고는 외부 OSS 커뮤니티로 직접 나간다. 리뷰 무조건 블로킹 |
| [`rules/context/open-questions.md`](rules/context/open-questions.md) | **미결 대장.** 정하지 않고 코드를 쓰면 되돌리기 어려운 것 10건 |

---

## 구성

```
.claude/
├── rules/context/        도메인 컨텍스트 — 무엇을 만드나 · 용어 · 외부 의존 · 안전 경계 · 미결
├── rules/conventions/    코드 컨벤션 — 아키텍처 · 커밋 · git · PR · 테스트 · 로깅
├── codemaps/             구조 지도 — 아키텍처 · 데이터 · 도메인
├── skills/               워크플로우 11개
├── commands/             슬래시 진입점 11개 (스킬로 위임하는 얇은 포인터)
├── agents/               서브에이전트 6개 (+ _shared/preflight)
├── scripts/              훅 스크립트 8개
└── docs/                 이 디렉토리 사용법
```

---

## 가이드 문서

| 문서 | 내용 |
|---|---|
| [docs/rules.md](docs/rules.md) | **컨텍스트 & 컨벤션 & 코드맵 — 먼저 읽는다** |
| [docs/setup.md](docs/setup.md) | 초기 셋업 — 크리덴셜 분리, 빌드 환경 |
| [docs/structure.md](docs/structure.md) | 패키지 구조와 헥사고날 라이트 |
| [docs/hooks.md](docs/hooks.md) | 훅 시스템 — 하네스 레벨 자동화 |
| [docs/skills.md](docs/skills.md) | 스킬 11개 |
| [docs/commands.md](docs/commands.md) | 슬래시 커맨드 11개 |
| [docs/agents.md](docs/agents.md) | 서브에이전트 6개 |
| [docs/contributing.md](docs/contributing.md) | 규칙·스킬·에이전트·훅 추가 방법 |

---

## 빠른 시작

```bash
# 1. 크리덴셜 분리 — settings.local.json 은 .gitignore 대상이다
cp .claude/settings.json.example .claude/settings.local.json

# 2. 토큰 입력 — <REPLACE_WITH_SECRET_MANAGER> 자리를 채운다
#    실제 값을 settings.json 이나 소스에 넣지 않는다

# 3. 빌드 확인
./gradlew build

# 4. Claude Code 실행 → /mcp 로 연결 확인
```

---

## 훅이 무엇을 막나

`git commit` 시점에 셋이 순서대로 돈다. **스킵하지 않는다.**

| 스크립트 | 막는 것 |
|---|---|
| [`scripts/secret-scan.sh`](scripts/secret-scan.sh) | 토큰 패턴(`ghp_`·`AKIA`·`sk-ant-`…) · `.env` 실값 |
| [`scripts/safety-boundary-check.sh`](scripts/safety-boundary-check.sh) | S-1~S-4 의 정적 탐지 가능분 |

둘 다 **git 훅**([`.githooks/pre-commit`](../.githooks/pre-commit))과 **CI** 양쪽에서 돈다.
등록은 `git config core.hooksPath .githooks` — 클론 후 1회.
`./gradlew build` 는 훅이 아니라 **CI 가 게이트**다 ([`open-questions.md`](rules/context/open-questions.md) Q-10).

⚠️ **훅 통과가 합격이 아니다.** 훅은 문자열만 본다. 호출 그래프를 따라가야 아는 위반은
[`agents/safety-reviewer.md`](agents/safety-reviewer.md) 와 사람이 본다.

---

## 루트 `docs/`

`.claude/` 밖이라 **세션에 자동으로 실리지 않는다.** 길고 가끔 필요한 산출물을 둔다.

| 파일 | 내용 |
|---|---|
| [`../docs/ai-oss-contributor-agent-prd.md`](../docs/ai-oss-contributor-agent-prd.md) | PRD v1.1 (Draft) — 836줄 |

새 문서를 넣으면 **`rules/context/` 쪽에 포인터를 같이 건다.** 안 걸면 다음 세션이 찾지 못한다.

---

## 출처

구조·스킬·훅은 `torder-membership-crm` 의 `.claude/` 를 기준으로 이식했다 (2026-09-18).
**Jira·Confluence 스킬은 제외**했고, 「돈이 오가는 경로」 자리는 전부
[`안전 경계 6조`](rules/context/safety-boundaries.md) 로 치환했다.

기준 프로젝트는 Gradle 멀티모듈(`modules/{모듈}/{api,core}`)로 경계를 컴파일러에 강제하지만,
여기는 단일 프로젝트 + 패키지 규율이다. 승격 조건은
[`rules/conventions/architecture.md`](rules/conventions/architecture.md) 「모듈 승격 기준」.
