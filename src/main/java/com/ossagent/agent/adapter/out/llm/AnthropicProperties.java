package com.ossagent.agent.adapter.out.llm;

import com.ossagent.agent.domain.LlmPricing;
import com.ossagent.support.secret.TokenRedactor;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 연동 설정.
 *
 * <p>🔴 <b>{@code toString()} 을 재정의했다.</b> record 의 기본 구현은 모든 구성요소를 찍으므로
 * 설정 객체 하나를 로그에 남기는 순간 API 키가 통째로 나간다 — S-4.
 * {@code GitHubProperties} 가 같은 이유로 같은 처리를 한다.
 *
 * <p>🔴 <b>키 유무는 {@link #hasApiKey()} 로 판정한다.</b> 환경변수를 코드에서 직접 읽지 않는다 —
 * 그러면 개발자 머신에 {@code ANTHROPIC_API_KEY} 가 export 돼 있을 때 {@code @SpringBootTest} 가
 * 실제 어댑터를 올리고, 테스트 결과가 머신마다 달라진다. 프로퍼티를 거치면 테스트가
 * {@code application.yml} 로 덮을 수 있다.
 *
 * @param baseUrl         API 기준 URL. 환경변수로 노출하지 않는다 — 송신 대상이 바뀌면 안 된다(S-4)
 * @param apiKey          Anthropic API 키. 비어 있으면 {@code DisabledLanguageModel} 이 주입된다
 * @param model           모델 ID. 기본값은 {@code external-deps.md} 가 정했다
 * @param maxOutputTokens 출력 상한. 여기서 잘리면 <b>성공이 아니라 TRUNCATED</b> 다
 * @param timeout         호출 타임아웃
 * @param maxRetries      <b>전송 계층</b> 재시도 상한. {@code agent.execution.max-retries} 와 다른 축이다
 * @param retryBackoff    재시도 간 기본 대기. 시도마다 배수로 늘어난다
 * @param pricing         모델별 단가표 — 키는 모델 ID. <b>비어 있으면 비용 미터를 만들지 않는다</b>(#71)
 */
@ConfigurationProperties("agent.llm")
public record AnthropicProperties(
        String baseUrl,
        String apiKey,
        String model,
        int maxOutputTokens,
        Duration timeout,
        int maxRetries,
        Duration retryBackoff,
        Map<String, LlmPricing> pricing) {

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String DEFAULT_MODEL = "claude-sonnet-5";
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 16000;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
    private static final int DEFAULT_MAX_RETRIES = 2;
    /** 파이프라인 상한(3)과 곱해진다 — 5 면 후보 1건당 18회다. */
    private static final int MAX_ALLOWED_RETRIES = 5;
    private static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofMillis(500);

    public AnthropicProperties {
        baseUrl = blankToDefault(baseUrl, DEFAULT_BASE_URL);
        apiKey = apiKey == null ? "" : apiKey.trim();
        model = blankToDefault(model, DEFAULT_MODEL);
        maxOutputTokens = maxOutputTokens <= 0 ? DEFAULT_MAX_OUTPUT_TOKENS : maxOutputTokens;
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        retryBackoff = retryBackoff == null ? DEFAULT_RETRY_BACKOFF : retryBackoff;
        // 🔴 기본값이 「빈 표」다. 여기에 기본 단가를 채우지 않는다 —
        //    모델은 환경변수로 바뀌는데 단가는 시점·계약에 따라 다르다 (LlmPricing javadoc)
        pricing = pricing == null ? Map.of() : Map.copyOf(pricing);
        if (maxRetries < 0 || maxRetries > MAX_ALLOWED_RETRIES) {
            // 상한을 두는 이유 — 파이프라인 재시도(3)와 곱해진다. 전송 5 면 후보 1건당
            // 대외 호출 18회다. 「조용히 돈을 태우는」 경로를 설정으로도 만들 수 없게 한다 (S-6)
            throw new IllegalArgumentException(
                    "agent.llm.max-retries 는 0~%d 이어야 합니다: %d"
                            .formatted(MAX_ALLOWED_RETRIES, maxRetries));
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("agent.llm.timeout 은 0 보다 커야 합니다");
        }
        if (retryBackoff.isNegative()) {
            throw new IllegalArgumentException("agent.llm.retry-backoff 는 음수일 수 없습니다");
        }
    }

    /** 기본값으로만 채운 설정. 테스트와 기본 조립에서 쓴다. */
    public static AnthropicProperties defaults() {
        return new AnthropicProperties(null, null, null, DEFAULT_MAX_OUTPUT_TOKENS, null,
                DEFAULT_MAX_RETRIES, null, null);
    }

    public boolean hasApiKey() {
        return !apiKey.isBlank();
    }

    /**
     * 지금 쓰는 모델의 단가. <b>없으면 비어 있다</b> — 호출자는 비용 미터를 만들지 않는다.
     *
     * <p>⚠️ <b>대소문자를 무시해 찾는다.</b> 설정 바인딩이 키를 어떻게 정규화하든 같은
     * 결과가 나와야 하고, 무엇보다 <b>키 오타의 증상이 「비용이 조용히 안 찍힌다」</b>라서
     * 알아차리기 어렵다. 그래서 {@code LanguageModelConfig} 가 기동 시 단가표 키를
     * 함께 남긴다 — 「표는 있는데 이 모델이 없다」가 눈에 보이게.
     */
    public Optional<LlmPricing> pricingForModel() {
        String wanted = normalize(model);
        return pricing.entrySet().stream()
                .filter(entry -> normalize(entry.getKey()).equals(wanted))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private static String normalize(String modelId) {
        return modelId == null ? "" : modelId.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 🔴 키를 찍지 않는다. record 기본 구현을 그대로 두면 설정 로깅 한 줄로 키가 유출된다 —
     * {@code .claude/rules/conventions/logging.md}.
     */
    @Override
    public String toString() {
        return ("AnthropicProperties[baseUrl=%s, apiKey=%s, model=%s, maxOutputTokens=%d, "
                + "timeout=%s, maxRetries=%d, retryBackoff=%s, pricedModels=%s]")
                .formatted(baseUrl, TokenRedactor.mask(apiKey), model, maxOutputTokens, timeout,
                        maxRetries, retryBackoff, pricing.keySet());
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
