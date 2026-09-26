-- #13 조회 API 통합 테스트 픽스처.
--
-- ⚠ EntityManager.persist 로는 못 만든다. ContributionCandidate 는 analysis·confidence 를 채울
--   공개 경로가 없고(discover() 만 존재) GeneratedChange 는 팩토리도 setter 도 없다.
--   ReflectionTestUtils 로 필드를 찌르는 것은 「구현 내부 필드에 의존」 금지에 걸리므로 SQL 로 넣는다.
--   후보를 넣는 경로(#11)가 들어오면 그쪽 팩토리로 갈아탄다.
--
-- 🔴 토큰 유사 문자열을 여기 두지 않는다 — secret-scan.sh 가 커밋을 막고, 애초에 소스에 패턴
--   리터럴을 남기지 않는 것이 이 저장소의 규약이다. 스크럽 검증은 FindCandidatesUseCaseTest 가
--   런타임 조립한 토큰으로 한다. 여기서는 구조(본문이 응답에 없는가)만 본다.

DELETE FROM generated_change;
DELETE FROM agent_run;
DELETE FROM pull_request;
DELETE FROM contribution_candidate;

-- 신뢰도·난이도·상태를 흩뜨려 필터 조합을 경계까지 본다.
INSERT INTO contribution_candidate
(id, issue_id, category, difficulty, estimated_files, estimated_loc, implementation_feasible,
 breaking_change, confidence, analysis, status, attempt, version, selected_at, created_at, updated_at)
VALUES
(101, 1001, 'bug',           'EASY',   2,  40,  TRUE,  FALSE, 0.90,
 'NPE 재현 조건과 수정 방향',      'ANALYZED',   0, 0, NULL,
 TIMESTAMP '2026-09-20 00:00:00+00', TIMESTAMP '2026-09-20 00:00:00+00'),
(102, 1002, 'enhancement',   'MEDIUM', 5, 200,  TRUE,  FALSE, 0.60,
 '중간 난이도',                    'ANALYZED',   0, 0, NULL,
 TIMESTAMP '2026-09-20 01:00:00+00', TIMESTAMP '2026-09-20 01:00:00+00'),
(103, 1003, 'documentation', 'EASY',   1,  10,  TRUE,  FALSE, 0.75,
 '문서 수정',                      'SELECTED',   0, 0,
 TIMESTAMP '2026-09-21 00:00:00+00',
 TIMESTAMP '2026-09-20 02:00:00+00', TIMESTAMP '2026-09-21 00:00:00+00'),
-- confidence 가 NULL 인 후보 — discover() 직후 상태다.
-- DTO 가 primitive double 이었다면 여기서 목록이 500 이 난다
(104, 1004, NULL,            NULL,    NULL, NULL, NULL,  NULL,  NULL,
 NULL,                             'DISCOVERED', 0, 0, NULL,
 TIMESTAMP '2026-09-20 03:00:00+00', TIMESTAMP '2026-09-20 03:00:00+00');

INSERT INTO agent_run
(id, candidate_id, stage, attempt, input_tokens, output_tokens, status, error_message, started_at, finished_at)
VALUES
(201, 101, 'ANALYZE', 1, 1200, 300, 'SUCCEEDED', NULL,
 TIMESTAMP '2026-09-20 00:10:00+00', TIMESTAMP '2026-09-20 00:11:00+00'),
(202, 101, 'CODE',    1, 3000, 900, 'FAILED',    '빌드 실패 — 컴파일 오류',
 TIMESTAMP '2026-09-20 00:20:00+00', TIMESTAMP '2026-09-20 00:25:00+00');

-- 재시도마다 쌓인다. 상세는 최신 1건(302)만 요약하고 건수 2 를 함께 준다
INSERT INTO generated_change
(id, candidate_id, branch_name, commit_sha, diff, test_result, review_result, created_at)
VALUES
(301, 101, 'oss-agent/issue-1-a', 'aaa111', '+ 이전 시도 diff', 'FAILED', NULL,
 TIMESTAMP '2026-09-20 00:30:00+00'),
(302, 101, 'oss-agent/issue-1-b', 'bbb222', '+ 최신 diff 본문', 'BUILD SUCCESSFUL', NULL,
 TIMESTAMP '2026-09-20 00:40:00+00');
