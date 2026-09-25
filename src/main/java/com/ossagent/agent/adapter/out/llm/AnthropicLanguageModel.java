package com.ossagent.agent.adapter.out.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.ossagent.agent.domain.AgentRunContext;
import com.ossagent.agent.domain.LanguageModel;
import com.ossagent.agent.domain.LlmException;
import com.ossagent.agent.domain.LlmFailureReason;
import com.ossagent.agent.domain.LlmPermanentException;
import com.ossagent.agent.domain.LlmRequest;
import com.ossagent.agent.domain.LlmResponse;
import com.ossagent.agent.domain.LlmTransientException;
import com.ossagent.agent.domain.LlmUsage;
import com.ossagent.agent.domain.PromptScrubber;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Anthropic API 로 LLM 을 호출한다. <b>SDK 타입은 이 클래스 밖으로 나가지 않는다.</b>
 *
 * <p>응답 파싱·에러 번역이 여기서 끝난다. 원시 SDK 타입이 application 으로 올라가면 도메인이
 * 외부 스키마에 묶인다 — {@code architecture.md} 배치 규칙.
 *
 * <h2>지키는 것</h2>
 * <ol>
 *   <li><b>송신 직전 스크럽</b> — {@code system} 과 {@code userPrompt} 양쪽. 우회 경로 없음 (S-4)
 *   <li><b>SDK 내장 재시도를 끄고 직접 돈다</b> — 아래
 *   <li><b>상한 절단·거부는 예외</b> — 잘린 응답을 성공으로 돌려주지 않는다
 *   <li><b>예외 원문을 들고 다니지 않는다</b> — 상태코드는 로그에만 (S-4)
 * </ol>
 *
 * <h2>왜 SDK 재시도를 끄나</h2>
 *
 * <p>SDK 에 맡기면 몇 번 재전송했는지, 각 시도가 얼마나 걸렸는지 <b>관측할 수 없다.</b>
 * 그런데 {@code logging.md} 는 재시도에 대해 「몇 번째인지 · 직전 실패 사유」를 요구하고,
 * 이 이슈의 존재 이유가 「재시도 루프가 조용히 돈을 태운다」이다.
 * <b>관측 불가능한 재시도는 이 이슈가 없애려는 바로 그 상태다.</b>
 */
public class AnthropicLanguageModel implements LanguageModel {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLanguageModel.class);

    private final AnthropicClient client;
    private final AnthropicProperties properties;
    private final PromptScrubber scrubber;
    private final LlmRetryPolicy retryPolicy;

    /**
     * @param scrubber <b>필수다.</b> {@code null} 을 허용하면 S-4 본류 방어가 조용히 0 이 된다
     */
    public AnthropicLanguageModel(AnthropicClient client, AnthropicProperties properties,
            PromptScrubber scrubber, LlmRetryPolicy retryPolicy) {
        if (scrubber == null) {
            throw new IllegalArgumentException(
                    "PromptScrubber 는 필수다 — 없으면 프롬프트가 스크럽 없이 모델 제공자로 나간다 (S-4)");
        }
        this.client = client;
        this.properties = properties;
        this.scrubber = scrubber;
        this.retryPolicy = retryPolicy;
    }

    @Override
    public LlmResponse complete(AgentRunContext ctx, LlmRequest request) {
        MessageCreateParams params = buildParams(scrub(request));

        int completedRetries = 0;
        while (true) {
            long startedAt = System.nanoTime();
            try {
                Message message = client.messages().create(params);
                LlmResponse response = toResponse(message, ctx);
                // logging.md 가 LLM 호출에 「모델 · 입출력 토큰 수 · 소요 시간」을 남기라고
                // 요구하는 바로 그 줄이다 — 비용이 안 보이면 재시도 루프가 조용히 돈을 태운다
                // safety-ok: inputTokens·outputTokens 는 시크릿이 아니라 int 개수다. 프롬프트·응답 본문은 넣지 않는다
                log.info("LLM 호출 성공 callSite={} model={} inputTokens={} outputTokens={} "
                                + "elapsedMs={} transportAttempts={}",
                        ctx.callSite(), properties.model(), response.usage().inputTokens(),
                        response.usage().outputTokens(), elapsedMs(startedAt), completedRetries + 1);
                return response;
            } catch (LlmException e) {
                // toResponse 가 던진 절단·거부. 재전송해도 같으므로 정책이 걸러낸다
                if (!retryPolicy.shouldRetry(e, completedRetries)) {
                    throw e;
                }
                completedRetries = sleepAndAdvance(e, completedRetries, ctx, startedAt);
            } catch (RuntimeException e) {
                LlmException translated = translate(e, ctx, startedAt);
                if (!retryPolicy.shouldRetry(translated, completedRetries)) {
                    throw translated;
                }
                completedRetries = sleepAndAdvance(translated, completedRetries, ctx, startedAt);
            }
        }
    }

    private int sleepAndAdvance(LlmException failure, int completedRetries, AgentRunContext ctx,
            long startedAt) {
        var backoff = retryPolicy.backoffFor(completedRetries);
        log.warn("LLM 호출 실패 — 재시도 callSite={} reason={} attempt={} of={} elapsedMs={} backoffMs={}",
                ctx.callSite(), failure.reason(), completedRetries + 1, retryPolicy.maxRetries() + 1,
                elapsedMs(startedAt), backoff.toMillis());
        try {
            Thread.sleep(backoff.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw failure;
        }
        return completedRetries + 1;
    }

    /** 🔴 S-4 본류. {@code system} 과 {@code userPrompt} <b>양쪽</b> 모두 지난다. */
    private LlmRequest scrub(LlmRequest request) {
        return request.withScrubbed(
                request.hasSystem() ? scrubber.scrub(request.system()) : null,
                scrubber.scrub(request.userPrompt()));
    }

    private MessageCreateParams buildParams(LlmRequest request) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                // .model(String) 오버로드를 쓴다 — 타입 상수는 모델 출시보다 늦게 따라온다
                .model(properties.model())
                .maxTokens(cappedMaxTokens(request))
                .addUserMessage(request.userPrompt());
        if (request.hasSystem()) {
            builder.system(request.system());
        }
        return builder.build();
    }

    /**
     * 호출자가 요청한 출력 예산을 <b>설정된 상한으로 깎는다.</b>
     *
     * <p>{@code agent.llm.max-output-tokens} 는 기본값이 아니라 <b>비용 가드레일</b>이다.
     * 호출자가 요구하는 대로 다 내주면 프롬프트 조립 버그 하나가 곧바로 청구서가 된다.
     * 출력 토큰이 입력보다 몇 배 비싸므로 상한이 없는 쪽이 위험하다.
     *
     * <p>호출 지점마다 다른 예산이 필요해지면({@code CODE} 의 diff 가 가장 길다) 그때
     * {@code LlmCallSite} 별로 가른다 — 실측 전에는 하나로 둔다.
     */
    private long cappedMaxTokens(LlmRequest request) {
        int capped = Math.min(request.maxOutputTokens(), properties.maxOutputTokens());
        if (capped < request.maxOutputTokens()) {
            log.warn("출력 예산을 설정 상한으로 깎았다 callSite=요청={} 상한={}",
                    request.maxOutputTokens(), properties.maxOutputTokens());
        }
        return capped;
    }

    /**
     * SDK 응답을 우리 값으로 옮긴다. <b>여기서 파싱이 끝난다.</b>
     *
     * <p>{@code MAX_TOKENS}·{@code REFUSAL} 은 성공으로 돌려주지 않는다 — 잘린 JSON 을 소비자가
     * 진실로 파싱하는 것을 구조적으로 막는다.
     */
    private LlmResponse toResponse(Message message, AgentRunContext ctx) {
        LlmUsage usage = new LlmUsage(
                Math.toIntExact(message.usage().inputTokens()),
                Math.toIntExact(message.usage().outputTokens()));

        StopReason.Known stopReason = message.stopReason()
                .map(StopReason::known)
                .orElse(StopReason.Known.END_TURN);

        if (stopReason == StopReason.Known.MAX_TOKENS) {
            // usage 를 실어 올린다 — 호출자가 상한을 얼마나 올려야 하는지 판단할 유일한 근거다
            throw new LlmPermanentException(LlmFailureReason.TRUNCATED, ctx.callSite(), usage);
        }
        if (stopReason == StopReason.Known.REFUSAL) {
            throw new LlmPermanentException(LlmFailureReason.REJECTED, ctx.callSite(), usage);
        }

        String text = message.content().stream()
                .map(ContentBlock::text)
                .filter(java.util.Optional::isPresent)
                .map(block -> block.get().text())
                .collect(Collectors.joining());

        return new LlmResponse(text, usage);
    }

    /**
     * SDK 예외를 우리 어휘로 옮긴다.
     *
     * <p>⚠️ <b>원문을 옮기지 않는다.</b> 예외 본문에 요청 URL·헤더가 담기고, 거기 토큰이 붙어
     * 있으면 로그와 {@code AgentRun.errorMessage} 로 그대로 나간다 (S-4).
     * 진단에 필요한 상태코드는 <b>여기 로그에만</b> 남기고, 그것도 메시지 본문은 뺀다.
     */
    private LlmException translate(RuntimeException e, AgentRunContext ctx, long startedAt) {
        if (e instanceof AnthropicServiceException service) {
            int status = service.statusCode();
            log.error("LLM 호출 실패 callSite={} status={} elapsedMs={}",
                    ctx.callSite(), status, elapsedMs(startedAt));
            return forStatus(status, ctx);
        }
        if (e instanceof AnthropicIoException) {
            // 연결 실패와 읽기 타임아웃이 같은 타입으로 온다. 둘 다 재전송 대상이다
            log.error("LLM 호출 실패 callSite={} reason=IO elapsedMs={}",
                    ctx.callSite(), elapsedMs(startedAt));
            return new LlmTransientException(LlmFailureReason.TIMEOUT, ctx.callSite());
        }
        log.error("LLM 호출 실패 callSite={} reason=UNEXPECTED type={} elapsedMs={}",
                ctx.callSite(), e.getClass().getSimpleName(), elapsedMs(startedAt));
        return new LlmPermanentException(LlmFailureReason.INVALID_REQUEST, ctx.callSite());
    }

    private LlmException forStatus(int status, AgentRunContext ctx) {
        if (status == 429) {
            return new LlmTransientException(LlmFailureReason.RATE_LIMITED, ctx.callSite());
        }
        if (status >= 500) {
            return new LlmTransientException(LlmFailureReason.UNAVAILABLE, ctx.callSite());
        }
        // 400 · 401 · 403 · 404 — 재시도하면 무한 루프가 된다
        return new LlmPermanentException(LlmFailureReason.INVALID_REQUEST, ctx.callSite());
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
