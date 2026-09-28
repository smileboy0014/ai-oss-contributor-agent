package com.ossagent.agent.adapter.out.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ThinkingConfigDisabled;
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
import java.time.Duration;
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
        RequestOptions options = requestOptions(params.maxTokens());

        int completedRetries = 0;
        while (true) {
            long startedAt = System.nanoTime();
            try {
                Message message = client.messages().create(params, options);
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
        var backoff = retryPolicy.backoffFor(failure, completedRetries);
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
                // 🔴 thinking 을 명시적으로 끈다 (#105). Sonnet 5 는 미지정이면 adaptive thinking 이
                //    켜지고 그 토큰이 max_tokens 에 산입된다 — POLICY 2,000·ANALYZE 1,500 예산이면
                //    생각만 하다 잘려 TRUNCATED 가 되고, 규약은 영구 보류·이슈는 FAILED 로 갔다.
                //    우리 네 지점은 전부 짧은 JSON 을 요구하는 자리라 확장 사고가 필요 없다
                .thinking(ThinkingConfigDisabled.builder().build())
                .addUserMessage(request.userPrompt());
        if (request.hasSystem()) {
            builder.system(request.system());
        }
        return builder.build();
    }

    /**
     * 🔴 호출 하나의 시간 상한을 <b>출력 예산에 비례</b>해 준다 (#104).
     *
     * <p>{@code agent.llm.timeout} 은 SDK 의 <b>전체 호출</b>(callTimeout — 본문 수신 포함) 상한이다.
     * 120초를 16,000 토큰 비스트리밍 코딩 응답에 그대로 대면 매번 타임아웃이고, 같은 파라미터로
     * 두 번 더 재전송해 입력 토큰만 3배로 태운 뒤 후보가 {@code FAILED} 로 갔다.
     * 설정값은 <b>2,000 토큰까지의 상한</b>이고(분석 1,500 · 규약 2,000 이 여기 든다), 그 위는
     * 1,000 토큰당 50초를 더한다 — 16,000 → 120 + 700 = 약 14분. 짧은 호출의 상한은 설정값 그대로라
     * 전송 계약 테스트가 재는 「설정값이 실제로 걸리는가」도 그대로 성립한다.
     */
    private RequestOptions requestOptions(long maxTokens) {
        long excess = Math.max(0, maxTokens - SCALE_FREE_TOKENS);
        Duration effective = properties.timeout()
                .plusSeconds(excess * SECONDS_PER_THOUSAND_TOKENS / 1000);
        return RequestOptions.builder().timeout(effective).build();
    }

    /** 이 예산까지는 설정값이 그대로 상한이다 — 규약 판정 2,000 · 이슈 분석 1,500 이 여기 든다. */
    private static final long SCALE_FREE_TOKENS = 2000;
    /** 그 위 1,000 토큰마다 더하는 초. Sonnet 계열 50~80 tok/s 에 여유를 둔 값이다. */
    private static final long SECONDS_PER_THOUSAND_TOKENS = 50;

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
            log.warn("출력 예산을 설정 상한으로 깎았다 requested={} cap={}",
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
                .map(AnthropicLanguageModel::knownStopReason)
                .orElse(StopReason.Known.END_TURN);

        // 🔴 절단은 둘이다 (#104). MODEL_CONTEXT_WINDOW_EXCEEDED 도 출력이 잘린 것인데 초안은
        //    성공으로 돌려 잘린 JSON 이 「파싱 실패」로 보고됐다 — 상한을 올려야 할 자리를 못 봤다
        if (stopReason == StopReason.Known.MAX_TOKENS
                || stopReason == StopReason.Known.MODEL_CONTEXT_WINDOW_EXCEEDED) {
            // usage 를 실어 올린다 — 호출자가 상한을 얼마나 올려야 하는지 판단할 유일한 근거다
            throw new LlmPermanentException(LlmFailureReason.TRUNCATED, ctx.callSite(), usage);
        }
        if (stopReason == StopReason.Known.REFUSAL) {
            throw new LlmPermanentException(LlmFailureReason.REJECTED, ctx.callSite(), usage);
        }
        if (stopReason != StopReason.Known.END_TURN) {
            // PAUSE_TURN · TOOL_USE · STOP_SEQUENCE — 우리는 도구도 정지열도 안 쓴다. 텍스트는 온전하므로
            // 실패로 접지 않되 남긴다: 나오면 요청 조립이 바뀐 것이다
            log.warn("예상 밖 stop_reason callSite={} stopReason={}", ctx.callSite(), stopReason);
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
    /**
     * SDK 가 모르는 새 stop_reason 이면 {@code known()} 이 던진다. 그것을 「잘못된 요청」(영구)으로 접으면
     * 모델 쪽 어휘 추가 하나에 모든 호출이 죽는다 — 텍스트가 왔으면 END_TURN 으로 읽고 로그로 남긴다.
     */
    private static StopReason.Known knownStopReason(StopReason reason) {
        try {
            return reason.known();
        } catch (RuntimeException unknown) {
            log.warn("SDK 가 모르는 stop_reason 이다 — END_TURN 으로 읽는다 value={}", reason.asString());
            return StopReason.Known.END_TURN;
        }
    }

    private LlmException translate(RuntimeException e, AgentRunContext ctx, long startedAt) {
        if (e instanceof AnthropicServiceException service) {
            int status = service.statusCode();
            log.error("LLM 호출 실패 callSite={} status={} elapsedMs={}",
                    ctx.callSite(), status, elapsedMs(startedAt));
            if (status == 429) {
                // 🔴 Retry-After 를 싣는다 (#104). 버리면 0.5초·1초 뒤 재전송 — 세 번이 1.5초 안에 끝난다
                return new LlmTransientException(LlmFailureReason.RATE_LIMITED, ctx.callSite(),
                        retryAfterOf(service));
            }
            return forStatus(status, ctx);
        }
        if (e instanceof AnthropicRetryableException) {
            // SDK 가 스스로 「다시 보낼 만하다」고 표시한 것 — 영구 실패로 접지 않는다 (#104)
            log.error("LLM 호출 실패 callSite={} reason=RETRYABLE elapsedMs={}",
                    ctx.callSite(), elapsedMs(startedAt));
            return new LlmTransientException(LlmFailureReason.UNAVAILABLE, ctx.callSite());
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

    /** {@code Retry-After} — 초 단위 정수만 받는다. 날짜 형식이나 없음은 {@code null}(우리 백오프). */
    private static Duration retryAfterOf(AnthropicServiceException service) {
        try {
            java.util.List<String> values = service.headers().values("retry-after");
            if (values.isEmpty()) {
                return null;
            }
            long seconds = Long.parseLong(values.get(0).trim());
            return seconds > 0 ? Duration.ofSeconds(seconds) : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
