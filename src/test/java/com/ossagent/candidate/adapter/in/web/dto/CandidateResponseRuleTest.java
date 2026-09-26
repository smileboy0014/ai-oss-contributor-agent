package com.ossagent.candidate.adapter.in.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.ossagent.support.ExternalText;
import jakarta.persistence.Entity;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * 응답 DTO 가 외부 텍스트 <b>본문</b>을 담지 않는지 검사한다 — S-4.
 *
 * <p><b>고정 목록으로 검사하지 않는다.</b> 「지금 `diff` 가 없는가」를 보면 내일 누가
 * {@code reviewResult} 를 추가할 때 아무것도 빨개지지 않는다. 대신 <b>규칙</b>을 검사한다 —
 * 엔티티에서 {@link ExternalText} 가 붙은 필드와 <b>같은 이름의 컴포넌트가 응답 record 에
 * 존재하지 않아야 한다.</b>
 *
 * <p>{@code ExternalTextMarkerTest} 가 적재 쪽에서 「마커가 빠지지 않았는가」를 보는 것과 짝이다.
 * 이쪽은 <b>마커가 붙은 것이 응답으로 새지 않는가</b>를 본다.
 *
 * <p>🔵 <b>예외는 둘뿐이다</b> — {@code analysis}·{@code errorMessage}. 이슈 완료조건이 「상세 —
 * 분석 결과 · {@code AgentRun} 이력」을 요구하므로 노출하되 {@code TokenRedactor} 를 거친다.
 * 그 통과 여부는 {@code FindCandidatesUseCaseTest} 가 토큰을 심어 확인한다 — 여기서는 구조만 본다.
 * 목록에는 둘 다 싣지 않는다.
 *
 * <p>리플렉션으로 record 컴포넌트를 훑는 것은 {@code testing-philosophy.md} 의 「구현 내부 필드에
 * 의존하지 않는다」에 대한 <b>의도적 예외</b>다. 검사 대상이 「응답에 그 이름이 있는가」 자체라
 * 다른 방법이 없다 — {@code ExternalTextMarkerTest} 가 같은 이유로 둔 예외를 따른다.
 */
class CandidateResponseRuleTest {

    private static final String ENTITY_PACKAGE = "com.ossagent";

    /**
     * 노출이 요구되는 필드. <b>대신 스크럽을 거친다.</b>
     *
     * <p>🔴 <b>여기 올리는 것은 예외를 만드는 행위다.</b> 추가하려면 세 가지를 함께 해야 한다 —
     * ① 왜 노출이 필요한지 ② UseCase 의 어디서 {@code TokenRedactor} 를 거치는지
     * ③ 그 통과를 확인하는 테스트. 셋 없이 올리면 이 테스트는 무력해진다.
     *
     * <ul>
     *   <li>{@code analysis} — 이슈 완료조건 「상세: 분석 결과」. {@code FindCandidatesUseCase#findDetail}</li>
     *   <li>{@code errorMessage} — 이슈 완료조건 「상세: {@code AgentRun} 이력」.
     *       왜 실패했는지 없이 이력은 의미가 없다. {@code FindCandidatesUseCase#toRunView}.
     *       적재 측({@code AgentRun.fail})도 거르므로 <b>이중 방어</b>다</li>
     *   <li>{@code category} — 목록·상세의 분류 표시. LLM 자유 문자열이라 {@code @ExternalText}
     *       를 붙였고({@code IssueAnalysis} 가 적재 시 스크럽한다), 노출은 요구사항이다.
     *       ⚠️ 표시하지 않고 두면 이 가드가 필드의 존재를 <b>아예 모른다</b> — 허용 목록에
     *       올리는 것이 「몰래 통과」보다 낫다</li>
     * </ul>
     */
    private static final Set<String> ALLOWED_WITH_SCRUB =
            Set.of("analysis", "errorMessage", "category");

    /**
     * 검사 대상 패키지. <b>목록이 아니라 패키지다</b> — 새 응답 타입이 자동으로 포함된다.
     *
     * <p>{@code application} 을 함께 보는 이유 — 본문이 실제로 실릴 수 있는 지점은 UseCase 가
     * 만드는 뷰({@code CandidateDetailView.ChangeView})다. web DTO 만 보면 <b>한 계층 하류만</b>
     * 지키게 된다.
     */
    private static final List<String> RESPONSE_PACKAGES = List.of(
            "com.ossagent.candidate.adapter.in.web.dto",
            "com.ossagent.candidate.application");

    @Test
    void 응답_DTO_는_외부텍스트_본문을_담지_않는다_S4() {
        Set<String> externalTextFields = externalTextFieldNames();
        List<String> leaked = new ArrayList<>();

        for (Class<?> response : responseRecords()) {
            for (RecordComponent component : response.getRecordComponents()) {
                String name = component.getName();
                if (externalTextFields.contains(name) && !ALLOWED_WITH_SCRUB.contains(name)) {
                    leaked.add(response.getSimpleName() + "." + name);
                }
            }
        }

        assertThat(leaked)
                .as("""
                        응답 DTO 가 @ExternalText 필드 본문을 담고 있다 — S-4.
                        diff·testResult·reviewResult 는 대상 저장소 파일·빌드 출력·LLM 응답이고
                        토큰이 섞여 들어온다. 크기·해시·존재 여부만 주고 본문은 담지 않는다.
                        노출이 꼭 필요하면 ALLOWED_WITH_SCRUB 에 올리고 UseCase 에서 redact 를 거친다.""")
                .isEmpty();
    }

    @Test
    void 목록_응답은_외부텍스트를_하나도_담지_않는다_S4() {
        Set<String> externalTextFields = externalTextFieldNames();

        List<String> inSummary = Arrays.stream(CandidateSummary.class.getRecordComponents())
                .map(RecordComponent::getName)
                .filter(externalTextFields::contains)
                .toList();

        assertThat(inSummary)
                .as("""
                        목록에는 analysis 조차 싣지 않는다 — 유출면이 N건으로 넓어진다.
                        TEXT 컬럼을 읽지 않는다는 성능 규율과도 같은 방향이다.""")
                .isEmpty();
    }

    @Test
    void 검사_대상을_실제로_찾았다() {
        // 스캔이 0건이면 위 두 테스트가 조용히 통과한다. 검증하지 않은 것을 통과라고 하지 않는다
        assertThat(externalTextFieldNames())
                .as("@ExternalText 필드를 하나도 못 찾았다 — 스캔이 깨진 것이다")
                .isNotEmpty()
                .contains("diff", "analysis");

        List<Class<?>> scanned = responseRecords();
        assertThat(scanned)
                .as("응답 record 를 하나도 못 찾았다 — 패키지 스캔이 깨진 것이다")
                .isNotEmpty();
        assertThat(scanned).extracting(Class::getSimpleName)
                .as("중첩 record 까지 훑어야 한다 — ChangeView 가 본문이 실릴 수 있는 지점이다")
                .contains("CandidateSummary", "CandidateDetail", "ChangeSummary",
                        "CandidateDetailView", "ChangeView");
    }

    /**
     * 응답 패키지의 record 전부 — <b>중첩 record 까지 재귀로</b> 모은다.
     *
     * <p>하드코딩 목록이면 내일 누가 {@code CandidateDetail.VerificationView} 를 추가할 때
     * 아무것도 빨개지지 않는다. 그러면 이 클래스의 「고정 목록으로 검사하지 않는다」가 거짓이 된다.
     */
    private static List<Class<?>> responseRecords() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((metadataReader, factory) -> true);

        List<Class<?>> records = new ArrayList<>();
        for (String pkg : RESPONSE_PACKAGES) {
            for (BeanDefinition definition : scanner.findCandidateComponents(pkg)) {
                collectRecords(ClassUtils.resolveClassName(definition.getBeanClassName(), null),
                        records);
            }
        }
        return records;
    }

    private static void collectRecords(Class<?> type, List<Class<?>> sink) {
        if (type.isRecord()) {
            sink.add(type);
        }
        for (Class<?> nested : type.getDeclaredClasses()) {
            collectRecords(nested, sink);
        }
    }

    /** 엔티티에서 {@link ExternalText} 가 붙은 필드 이름 전부. */
    private static Set<String> externalTextFieldNames() {
        Set<String> names = new LinkedHashSet<>();
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        for (BeanDefinition definition : scanner.findCandidateComponents(ENTITY_PACKAGE)) {
            Class<?> entity = ClassUtils.resolveClassName(definition.getBeanClassName(), null);
            for (Field field : entity.getDeclaredFields()) {
                if (field.isAnnotationPresent(ExternalText.class)) {
                    names.add(field.getName());
                }
            }
        }
        return names;
    }
}
