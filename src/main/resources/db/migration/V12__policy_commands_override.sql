-- #102 — 빌드·테스트 명령을 사람이 직접 넣을 수 있게 한다.
--
-- 🔴 build_command · test_command 의 유일한 출처가 LLM 의 규약 문서 추출이었다. spring-kafka 의
--    CONTRIBUTING.md 에는 그런 내용이 없어(Q-8 실측) null → 검증을 시작조차 못 하고(S-5)
--    모든 후보가 FAILED 였다. 사람이 넣는 경로가 없었다.
--
-- commands_overridden_at 이 NULL 이 아니면 재분석이 세 값(java_version · build_command ·
-- test_command)을 덮어쓰지 않는다 — 사람의 판단을 자동이 다시 쓰지 않는다(Q-8 해소와 같은 방향).
ALTER TABLE repository_policy
    ADD COLUMN commands_overridden_at TIMESTAMP(6) WITH TIME ZONE;
