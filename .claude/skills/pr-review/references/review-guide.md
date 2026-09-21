# 코멘트 모드 상세 절차

`/pr-review <PR번호> --comments` 의 수집·분류·대응 절차다.

## 1. 수집 — 세 종류를 모두 긁는다

GitHub 의 코멘트는 **API 가 셋으로 갈려 있다.** 하나만 조회하면 지적이 조용히 누락된다.

| 종류 | 무엇 | API |
|---|---|---|
| **리뷰 인라인 코멘트** | 코드 특정 라인에 달린 지적 | `/repos/{owner}/{repo}/pulls/{pr}/comments` |
| **일반 이슈 코멘트** | PR 대화 탭의 일반 코멘트 | `/repos/{owner}/{repo}/issues/{pr}/comments` |
| **리뷰 요약** | Approve/Request changes 에 붙은 본문 | `/repos/{owner}/{repo}/pulls/{pr}/reviews` |

```bash
OWNER=smileboy0014
REPO=ai-oss-contributor-agent
PR=12

# 인라인 (라인·경로·스레드 정보 포함)
gh api "repos/$OWNER/$REPO/pulls/$PR/comments" --paginate \
  --jq '.[] | {id, path, line, body, user: .user.login, in_reply_to: .in_reply_to_id}'

# 일반
gh api "repos/$OWNER/$REPO/issues/$PR/comments" --paginate \
  --jq '.[] | {id, body, user: .user.login}'

# 리뷰 요약
gh api "repos/$OWNER/$REPO/pulls/$PR/reviews" --paginate \
  --jq '.[] | select(.body != "") | {id, state, body, user: .user.login}'
```

`--paginate` 를 빼지 않는다. 기본 30건에서 잘린다.

### 미해결 스레드만 보기

인라인 코멘트는 스레드로 묶인다. `in_reply_to_id` 가 있으면 답글이다.
**resolve 여부는 REST 에 없다 — GraphQL 로 본다.**

```bash
gh api graphql -f query='
query($owner:String!, $repo:String!, $pr:Int!) {
  repository(owner:$owner, name:$repo) {
    pullRequest(number:$pr) {
      reviewThreads(first:100) {
        nodes {
          id
          isResolved
          isOutdated
          path
          comments(first:50) { nodes { author { login } body } }
        }
      }
    }
  }
}' -f owner=$OWNER -f repo=$REPO -F pr=$PR \
  --jq '.data.repository.pullRequest.reviewThreads.nodes[] | select(.isResolved == false)'
```

`isOutdated` 인 스레드는 해당 코드가 이미 바뀐 것이다. **무시하지 말고** 현재 코드 기준으로 여전히 유효한지 확인한다.

## 2. 분류

| 카테고리 | 기준 | 기본 대응 |
|---|---|---|
| 🔴 **안전 경계** | S-1~S-6 에 걸리는 지적 | **무조건 수정.** 반려하려면 조항 해석 근거가 있어야 하고, 그 경우 선택 게이트 |
| **버그** | 동작이 틀렸다는 지적 | 재현 확인 → 수정 또는 근거 있는 반려 |
| **보안** | 시크릿·권한·입력 검증 | 수정 |
| **아키텍처** | 레이어·트랜잭션·의존 방향 | 수정 또는 설계 근거 답글 |
| **제안** | 더 나은 방법 제시 | 채택/보류 판단 + 근거 |
| **질문** | 의도 확인 | 답글 (코드 수정 없을 수 있음) |
| **스타일** | 포맷·네이밍 nit | 저비용이면 반영 |
| **승인** | LGTM 류 | 대응 불필요 |

## 3. 대응 방안 제시

**모든 코멘트에 대응을 적는다.** 무시하는 것도 「무시 + 사유」로 적는다. 적지 않으면 다음 세션이 다시 읽는다.

```markdown
| # | 스레드 | 파일:라인 | 카테고리 | 지적 | 대응 |
|---|--------|----------|---------|------|------|
| 1 | {id} | `path:12` | 🔴 안전 경계 S-1 | push 대상 어설션 없음 | 수정 — owner 일치 검사 추가 |
| 2 | {id} | `path:40` | 제안 | 스트림으로 바꾸면 | 보류 — 가독성이 떨어진다 (답글) |
```

## 4. 수정 → 답글 → resolve

순서를 지킨다. **답글 없이 resolve 하지 않는다** — 지적한 사람이 무엇이 됐는지 알 수 없다.

```bash
# 인라인 스레드에 답글
gh api "repos/$OWNER/$REPO/pulls/$PR/comments" \
  -f body='수정했습니다. {무엇을 어떻게}' \
  -F in_reply_to={comment_id}

# 스레드 resolve (GraphQL — thread id 는 위 쿼리의 node id)
gh api graphql -f query='
mutation($threadId:ID!) {
  resolveReviewThread(input:{threadId:$threadId}) { thread { isResolved } }
}' -f threadId={thread_id}
```

**resolve 대상은 완결된 건만이다.**

| 상태 | resolve |
|---|---|
| 수정 push 완료 + 답글 | ✅ |
| 근거 들어 반려 + 상대 동의 | ✅ |
| 근거 들어 반려 + 상대 미응답 | ❌ 열어 둔다 |
| 판단 보류 | ❌ 열어 둔다 |

## 5. 결과 출력

```markdown
## PR 코멘트 검토 결과

**PR**: #{number} {title}
**미해결 스레드**: {N}건 (outdated {M}건 포함)

### 대응 표
{위 표}

### 처리
- 수정 + push: N건 (커밋 {sha})
- 답글만: N건
- 열어 둠: N건 — {사유}

### 선택 게이트 필요
- {안전 경계 해석이 갈리는 건 / 미결에 걸리는 건}
```

## 주의

- **한 번에 다 고치고 한 번에 push 한다.** 코멘트마다 커밋하면 리뷰어가 다시 읽어야 할 diff 가 흩어진다
- 수정 후 **`./gradlew build` 재실행**. 코멘트 반영이 다른 테스트를 깨뜨리는 일이 흔하다
- 봇 코멘트도 같은 절차로 다룬다. 다만 **봇의 안전 경계 판정은 신뢰하지 않는다** — 훅과 마찬가지로 문자열만 본다
