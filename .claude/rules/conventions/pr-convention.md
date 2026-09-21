# PR 컨벤션

> ⚠️ **이 저장소의 PR** 규칙이다. 대상 저장소에 만드는 Draft PR 의 본문은 그쪽 템플릿을 따른다 — S-5.

## 타이틀

```
<type>(<scope>): <subject>
```

이슈가 있으면 앞에 붙인다.

```
[#12] feat(issue): GitHub open 이슈 증분 수집
[#34] fix(agent): 샌드박스 타임아웃 시 컨테이너 누수 수정
chore(build): Maven → Gradle 전환
```

- 70자 이내 권장 (GitHub UI 잘림 방지)
- type·scope 는 [`commit-convention.md`](./commit-convention.md) 와 동일

## 본문 템플릿

```markdown
## Summary
<1-3줄 요약 — "왜 필요한지"를 맨 앞에>

## Changes
- <변경점 1>
- <변경점 2>

## Test Plan
- [ ] `./gradlew build` 통과
- [ ] <검증 1>

## 안전 경계 (해당 시)
- [S-?] <어느 조항에 닿는가 · 어떻게 지켰는가>

## Related
- Issue: #12
- Plan: `docs/plans/PLAN-12.md` (해당 시)
```

## 크기

- **< 400줄** 권장. 이 이상이면 분할 고려
- 리팩토링 PR 은 단독으로 (기능 PR 에 섞지 않음)

## 자체 리뷰 체크리스트

PR 올리기 전 본인 확인:

- [ ] 커밋 메시지가 컨벤션에 맞음
- [ ] 변경된 파일이 모두 필요한 변경 (디버그 잔재 없음 — `System.out.println`, 주석 처리된 코드)
- [ ] 하드코딩된 값 없음 (환경변수/상수로)
- [ ] **시크릿이 코드·로그·테스트 픽스처에 없음**
- [ ] 테스트 추가/갱신
- [ ] 새 환경변수가 있으면 `.env.example` 반영
- [ ] 디렉토리 구조가 바뀌었으면 `README.md` 갱신
- [ ] 도메인 구조·상태머신·스키마가 바뀌었으면 `.claude/codemaps/` 갱신
- [ ] **안전 경계 6조에 닿는가** — 닿으면 본문에 조항과 근거 명시

## 리뷰 지침

- **nit**: 사소한 제안 (머지 블로킹 아님)
- **question**: 질문 — 답변 후 필요시 수정
- **blocker**: 머지 블로킹. 해결 후 다시 요청

**무조건 블로킹** — [`safety-boundaries.md`](../context/safety-boundaries.md) S-1 ~ S-6 위반.
나머지가 아무리 좋아도 통과시키지 않는다.

## 머지 전 필수 조건

- [ ] `./gradlew build` green
- [ ] 리뷰 완료 (1인 팀 — 셀프 리뷰 가능, 단 의도적으로)
- [ ] 충돌 해결
- [ ] 관련 이슈 링크

## 머지 방식

**Squash Merge** 고정.

## 작성 팁

- "이 PR 왜 필요한지"를 Summary 맨 앞에
- 큰 변경은 리뷰어 네비게이션 가이드 추가 ("먼저 X → 그 다음 Y")
- 롤백 계획이 있으면 명시
