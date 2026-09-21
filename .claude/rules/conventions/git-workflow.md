# Git 워크플로우

> ⚠️ 여기서 말하는 브랜치·PR 은 전부 **이 저장소**의 것이다.
> 대상 저장소에 만드는 브랜치·PR 규칙은 [`safety-boundaries.md`](../context/safety-boundaries.md) S-1·S-2·S-5 를 따른다. 섞지 않는다.

## 브랜치 전략

| 브랜치 | 용도 |
|--------|------|
| `main` | 정본. **PR 로만 도달한다** |
| `feature/{issue}_{slug}` | 기능 개발 |
| `fix/{issue}_{slug}` | 버그 수정 |
| `refactor/{issue}_{slug}` | 동작 보존 리팩토링 |
| `chore/{issue}_{slug}` | 설정 · 빌드 · 의존성 |
| `docs/{issue}_{slug}` | 문서만 |

**베이스는 `main` 단일이다.** `develop`·`release/*` 를 두지 않았다 — 1인 개발이고 배포 대상이 없어,
승격 사다리를 만들면 관리 비용만 생긴다. 배포가 생기면 그때 `develop` 을 도입하고 이 절을 갱신한다.

### 이슈 키

GitHub 이슈 번호를 쓴다. 이슈가 없으면 슬러그만 쓴다.

```
feature/12_github-issue-scanner
fix/34_sandbox-container-leak
chore/_gradle-migration          ← 이슈 없음
```

## 커밋 단위

- **하나의 논리적 변경 = 하나의 커밋**
- 리팩토링 + 기능 추가는 **분리**
- 한 커밋에 여러 도메인을 섞지 않는다 (인터페이스 계약 변경은 예외)
- "WIP" 커밋은 PR 전에 `git rebase -i` 로 정리

## 머지 전략

- **작업 브랜치 → `main`**: Squash Merge (PR 단위 1커밋, 클린 히스토리)
- 머지 후 원격 브랜치 삭제

## 금지

- `main` 직접 커밋 — 지금 히스토리에 직접 커밋 2건이 있지만, 여기서부터는 PR 로만 간다
- `git push --force` → **`--force-with-lease` 사용**
- 리뷰 없는 머지 (1인이라도 셀프 리뷰 후 머지. 리뷰를 건너뛰지는 않는다)
- 커밋 훅 스킵 (`--no-verify`) — 시크릿·안전 경계 검사가 거기 걸려 있다

## 대상 저장소에 대한 git 작업 — 별개의 규칙

우리 코드가 런타임에 수행하는 git 작업은 위 규칙과 무관하며, 아래가 전부다.

| 작업 | 허용 |
|---|---|
| upstream clone / fetch | ✅ 읽기 |
| Fork 생성 | ✅ |
| **Fork 에 브랜치 push** | ✅ 유일한 쓰기 |
| **upstream 에 push** | ❌ S-1 |
| Draft PR 생성 | ✅ (항상 draft) |
| PR 머지 · ready 전환 · 리뷰어 지정 | ❌ S-2 |

대상 저장소 브랜치 이름은 PRD §14 가 정했다 — `oss-agent/issue-{issueNumber}-{short-description}`.
