rootProject.name = "ai-oss-contributor-agent"

// 단일 Gradle 프로젝트다. 도메인 경계는 패키지 규율로 지킨다 —
// `.claude/rules/conventions/architecture.md` 「모듈 승격 기준」 참조.
// 경계 위반이 반복되면 modules/{도메인}/{api,core} 멀티모듈로 승격해 컴파일러에 강제를 넘긴다.
