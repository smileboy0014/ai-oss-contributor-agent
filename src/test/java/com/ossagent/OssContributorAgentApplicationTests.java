package com.ossagent;

import com.ossagent.support.testing.AgentIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * 컨텍스트가 뜨는지만 본다.
 *
 * <p>{@code @SpringBootTest} 대신 {@link AgentIntegrationTest} 를 쓴다 — 통합 테스트가
 * 실제 GitHub·LLM·샌드박스를 타지 않게 하는 표준 진입점이다(Q-9 · #4).
 */
@AgentIntegrationTest
class OssContributorAgentApplicationTests {

    @Test
    void contextLoads() {
    }
}
