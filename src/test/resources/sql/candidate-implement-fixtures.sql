-- #18 착수 게이트 전용 픽스처. candidate-fixtures.sql **뒤에** 함께 로드한다.
--
-- 🔴 왜 파일을 가르나 — 공유 픽스처에 후보를 더했더니 CandidateQueryIntegrationTest 의
--    개수·페이지 경계 단언 4건이 깨졌다. 조회 테스트는 「전체가 몇 건인가」를 보고
--    이 테스트는 「게이트가 막는가」를 본다. 같은 파일에 두면 한쪽을 고칠 때마다
--    다른 쪽이 깨지고, 그 압력이 결국 단언을 느슨하게 만든다.

-- ── #18 착수 게이트용 — 저장소·정책·이슈 ────────────────────────────────
--
-- 🔴 후보만으로는 착수를 검증할 수 없다. 착수는 「후보 → 이슈 → 저장소 → 정책」을
--    타고 통행증을 발급받으므로(S-5), 그 사슬이 전부 있어야 게이트가 실제로 돈다.
--    사슬이 없으면 테스트가 500 으로 죽고 「게이트가 막았다」와 구분되지 않는다.
--
-- ⚠ 정책을 세 가지로 둔다 — 허용·보류(NULL)·금지(FALSE).
--   허용 하나만 두면 「막는다」를 한 번도 확인하지 못한 채 초록이 된다.

DELETE FROM issue WHERE id IN (1001, 1002, 1003, 1004);
DELETE FROM repository_policy WHERE repository_id IN (9001, 9002, 9003);
DELETE FROM oss_repository WHERE id IN (9001, 9002, 9003);

INSERT INTO oss_repository (id, owner, name, url, enabled)
VALUES (9001, 'spring-projects', 'spring-kafka',
        'https://github.com/spring-projects/spring-kafka', TRUE),
       (9002, 'acme', 'pending-policy',
        'https://github.com/acme/pending-policy', TRUE),
       (9003, 'acme', 'forbids-ai',
        'https://github.com/acme/forbids-ai', TRUE);

INSERT INTO repository_policy
(id, repository_id, java_version, build_command, test_command,
 issue_reference_required, signoff_required, tests_required,
 ai_contribution_allowed, contribution_rules, analyzed_at, created_at, updated_at, version)
VALUES
-- 허용
(9101, 9001, '17', './gradlew build', './gradlew test', FALSE, FALSE, TRUE,
 TRUE, '기여 규약 요약', TIMESTAMP '2026-09-20 00:00:00+00',
 TIMESTAMP '2026-09-20 00:00:00+00', TIMESTAMP '2026-09-20 00:00:00+00', 0),
-- 🔴 보류 — NULL 이다. 「허용」이 아니다 (S-5 · Q-8)
(9102, 9002, NULL, NULL, NULL, FALSE, FALSE, FALSE,
 NULL, NULL, TIMESTAMP '2026-09-20 00:00:00+00',
 TIMESTAMP '2026-09-20 00:00:00+00', TIMESTAMP '2026-09-20 00:00:00+00', 0),
-- 금지
(9103, 9003, '21', './gradlew build', './gradlew test', FALSE, FALSE, FALSE,
 FALSE, 'AI 생성 기여를 받지 않습니다', TIMESTAMP '2026-09-20 00:00:00+00',
 TIMESTAMP '2026-09-20 00:00:00+00', TIMESTAMP '2026-09-20 00:00:00+00', 0);

-- 후보 101~104 가 가리키는 이슈. 103 만 착수 대상(SELECTED)이라 허용 저장소에 둔다.
INSERT INTO issue
(id, repository_id, github_issue_number, title, body, state, url, labels,
 filter_result, filter_reason, github_created_at, github_updated_at, created_at, updated_at)
VALUES
(1001, 9001, 11, 'NPE on consumer rebalance', NULL, 'open', NULL, NULL,
 'PASSED', NULL, NULL, NULL,
 TIMESTAMP '2026-09-20 00:00:00+00', TIMESTAMP '2026-09-20 00:00:00+00'),
(1002, 9001, 12, 'Improve docs', NULL, 'open', NULL, NULL,
 'PASSED', NULL, NULL, NULL,
 TIMESTAMP '2026-09-20 00:00:00+00', TIMESTAMP '2026-09-20 00:00:00+00'),
(1003, 9001, 13, 'Fix typo in javadoc', NULL, 'open', NULL, NULL,
 'PASSED', NULL, NULL, NULL,
 TIMESTAMP '2026-09-20 00:00:00+00', TIMESTAMP '2026-09-20 00:00:00+00'),
(1004, 9001, 14, 'Discovered only', NULL, 'open', NULL, NULL,
 NULL, NULL, NULL, NULL,
 TIMESTAMP '2026-09-20 00:00:00+00', TIMESTAMP '2026-09-20 00:00:00+00');

-- 🔴 보류·금지 저장소의 후보 — S-5 게이트가 **실제로 막는지** 보려면 이것이 있어야 한다.
--    허용 저장소만 두면 「막는다」를 한 번도 확인하지 못한 채 초록이 된다.
DELETE FROM issue WHERE id IN (1005, 1006);
INSERT INTO issue
(id, repository_id, github_issue_number, title, body, state, url, labels,
 filter_result, filter_reason, github_created_at, github_updated_at, created_at, updated_at)
VALUES
(1005, 9002, 21, '보류 저장소의 이슈', NULL, 'open', NULL, NULL,
 'PASSED', NULL, NULL, NULL,
 TIMESTAMP '2026-09-20 00:00:00+00', TIMESTAMP '2026-09-20 00:00:00+00'),
(1006, 9003, 31, '금지 저장소의 이슈', NULL, 'open', NULL, NULL,
 'PASSED', NULL, NULL, NULL,
 TIMESTAMP '2026-09-20 00:00:00+00', TIMESTAMP '2026-09-20 00:00:00+00');

INSERT INTO contribution_candidate
(id, issue_id, category, difficulty, estimated_files, estimated_loc, implementation_feasible,
 breaking_change, confidence, analysis, status, attempt, version, selected_at, created_at, updated_at)
VALUES
-- 사람이 골랐다(SELECTED). 그래도 정책 때문에 착수는 막혀야 한다 —
-- 「사람이 골랐으니 통과」가 되면 S-5 가 S-6 에 먹힌다
(105, 1005, 'bug', 'EASY', 1, 10, TRUE, FALSE, 0.80, '보류 저장소', 'SELECTED', 0, 0,
 TIMESTAMP '2026-09-21 00:00:00+00',
 TIMESTAMP '2026-09-20 04:00:00+00', TIMESTAMP '2026-09-21 00:00:00+00'),
(106, 1006, 'bug', 'EASY', 1, 10, TRUE, FALSE, 0.80, '금지 저장소', 'SELECTED', 0, 0,
 TIMESTAMP '2026-09-21 00:00:00+00',
 TIMESTAMP '2026-09-20 05:00:00+00', TIMESTAMP '2026-09-21 00:00:00+00');
