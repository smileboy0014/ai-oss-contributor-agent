package com.ossagent.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * {@link ExternalText} 를 단 필드마다 <b>스크럽 수단이 정해져 있는지</b> 검사한다 —
 * S-4 · 이슈 #28.
 *
 * <h2>🔴 이 테스트가 강제하는 것은 「스크럽했다」가 아니다</h2>
 * <b>「스크럽을 어떻게 할지 누군가 정했다」</b>이다. 그 차이를 흐리지 않는다.
 *
 * <p>「이 대입이 스크럽을 거쳤는가」는 <b>데이터 흐름 분석</b>이고 리플렉션으로는 알 수 없다.
 * 할 수 없는 것을 할 수 있는 척하면 거짓 안전감만 남는다 — #4·#43 에서 반복해 나온 실패다.
 * 그래서 판정을 자동화하는 대신 <b>등록을 강제</b>한다. 마커를 단 새 필드가 표에 없으면
 * 빌드가 깨지고, 표를 채우려면 사람이 그 필드의 쓰기 경로를 한 번은 들여다봐야 한다.
 *
 * <h2>이 저장소가 시크릿을 막는 방식</h2>
 * 「잊지 않고 {@code redact} 를 부른다」가 아니라 <b>「부를 수밖에 없는 자리에 둔다」</b>이다.
 * 실제로 막혀 있는 필드는 전부 {@link Mechanism#FORCED_POINT} 아니면
 * {@link Mechanism#VALUE_TYPE} 이고, 유일하게 그 밖에 있던 {@code Issue.body} 가
 * <b>원문 그대로 저장되고 있었다</b>(#28 에서 닫았다).
 *
 * <h2>🕳 한계 — 숨기지 않는다</h2>
 * <ul>
 *   <li>{@link Mechanism#PENDING} 행이 실제로 지켜지는지는 <b>그 이슈의 테스트</b>가 본다.
 *       여기서는 「아직 없다」를 기록만 한다</li>
 *   <li>기존 필드에 <b>스크럽 없는 쓰기 경로가 새로 생기는 것</b>은 잡지 못한다.
 *       새 필드가 결정 없이 추가되는 것만 잡는다</li>
 *   <li>{@link ExternalText} 자체가 빠진 필드는 {@link ExternalTextMarkerTest} 의 몫이다.
 *       둘이 함께여야 「새 TEXT 컬럼은 마커와 결정을 모두 갖는다」가 된다</li>
 * </ul>
 */
class ExternalTextScrubRegistryTest {

    private static final String BASE_PACKAGE = "com.ossagent";

    /** 그 필드에 스크럽되지 않은 값이 들어갈 수 없게 만드는 수단. */
    private enum Mechanism {
        /** 대입 경로가 하나이고 거기서 스크럽한다. */
        FORCED_POINT,
        /** 스크럽된 값 타입 없이는 대입이 불가능하다. */
        VALUE_TYPE,
        /** 🔴 쓰는 코드가 아직 없다. 해당 이슈가 위 둘 중 하나를 세워야 한다. */
        PENDING,
    }

    private record Decision(Mechanism mechanism, String where) {
    }

    /**
     * {@code 클래스.필드} → 스크럽 결정.
     *
     * <p>⚠️ 행을 더할 때 {@link Mechanism#PENDING} 을 기본값처럼 쓰지 않는다.
     * 쓰는 코드를 <b>같은 PR 에서</b> 만든다면 그때 강제 지점도 함께 세운다.
     */
    private static final Map<String, Decision> REGISTRY = Map.ofEntries(
            Map.entry("Issue.body", new Decision(Mechanism.FORCED_POINT,
                    "IssueSnapshot compact 생성자 — 모든 이슈 본문이 통과하는 유일한 문 (#28)")),
            Map.entry("Issue.filterReason", new Decision(Mechanism.PENDING,
                    "#9 — RejectionReason enum 의 name() 만 들어간다. 자유 텍스트 경로를"
                            + " 만들지 않는 것이 그쪽 계약이다")),
            Map.entry("AgentRun.errorMessage", new Decision(Mechanism.FORCED_POINT,
                    "AgentRun.fail(...) — 이 필드에 대입하는 유일한 지점이고 거기서 redact (#6)")),
            Map.entry("RepositoryPolicy.contributionRules", new Decision(Mechanism.VALUE_TYPE,
                    "ScrubbedRules — 값 타입을 거치지 않으면 대입이 불가능하다 (#7)")),
            Map.entry("ScrubbedRules.value", new Decision(Mechanism.VALUE_TYPE,
                    "ScrubbedRules.of(...) 가 redact 한다. 값 타입 자신이 강제 지점이다 (#7)")),
            Map.entry("FetchedDocument.content", new Decision(Mechanism.FORCED_POINT,
                    "DB 에 저장되지 않는다. LLM 송신은 PromptScrubber 를 거치고,"
                            + " 어댑터 생성자가 scrubber == null 을 거부한다 (#10)")),
            Map.entry("ContributionCandidate.analysis", new Decision(Mechanism.PENDING,
                    "#13 — LLM 응답 원문이 들어온다. ScrubbedRules 같은 값 타입이 필요하다")),
            Map.entry("GeneratedChange.diff", new Decision(Mechanism.PENDING,
                    "#18 — 대상 저장소 코드 조각이 그대로 담긴다. 저장소가 시크릿을"
                            + " 커밋해 뒀으면 diff 에 실려 온다")),
            Map.entry("GeneratedChange.testResult", new Decision(Mechanism.PENDING,
                    "#18 — 빌드·테스트 출력. 환경변수를 찍는 빌드 스크립트가 흔하다")),
            Map.entry("GeneratedChange.reviewResult", new Decision(Mechanism.PENDING,
                    "#19 — LLM 리뷰 원문. 리뷰가 diff 를 인용하면 위 위험이 복제된다")));

    @Test
    @DisplayName("외부 텍스트 필드는 전부 스크럽 결정이 등록돼 있다")
    void 외부_텍스트_필드는_전부_등록돼_있다_S4() {
        List<String> unregistered = new ArrayList<>();

        for (String key : externalTextFields()) {
            if (!REGISTRY.containsKey(key)) {
                unregistered.add(key);
            }
        }

        assertThat(unregistered)
                .as("""
                        @ExternalText 를 달았는데 스크럽을 어떻게 할지 정하지 않은 필드가 있다 — S-4.

                        REGISTRY 에 행을 추가한다. 셋 중 하나를 고른다.
                          FORCED_POINT — 대입 경로를 하나로 모으고 거기서 TokenRedactor.redact
                          VALUE_TYPE   — ScrubbedRules 처럼 스크럽된 값 타입 없이는 대입 불가
                          PENDING      — 쓰는 코드가 아직 없다. 어느 이슈가 세울지 적는다

                        ⚠ 이 검사는 「스크럽했다」를 증명하지 않는다. 「누군가 정했다」를 강제할 뿐이다.
                        PENDING 을 기본값처럼 쓰면 이 표가 알리바이가 된다.""")
                .isEmpty();
    }

    @Test
    @DisplayName("등록표에 사라진 필드가 남아 있지 않다")
    void 등록표에_유령_행이_없다() {
        List<String> ghosts = new ArrayList<>(REGISTRY.keySet());
        ghosts.removeAll(externalTextFields());

        assertThat(ghosts)
                .as("""
                        필드가 사라졌거나 이름이 바뀌었는데 등록표가 그대로다.
                        낡은 행이 쌓이면 표를 아무도 믿지 않게 되고, 그때부터 위 검사도 무의미해진다.""")
                .isEmpty();
    }

    @Test
    @DisplayName("PENDING 행에는 담당 이슈가 적혀 있다")
    void PENDING_행에는_담당_이슈가_있다_S4() {
        List<String> orphaned = REGISTRY.entrySet().stream()
                .filter(entry -> entry.getValue().mechanism() == Mechanism.PENDING)
                .filter(entry -> !entry.getValue().where().contains("#"))
                .map(Map.Entry::getKey)
                .toList();

        assertThat(orphaned)
                .as("""
                        「아직 안 했다」만 적힌 행은 누구의 일도 아니게 된다.
                        어느 이슈가 강제 지점을 세울지 적는다 — 그 이슈의 리뷰가 이 행을 본다.""")
                .isEmpty();
    }

    @Test
    @DisplayName("스캔이 대상을 실제로 찾았다")
    void 스캔이_대상을_실제로_찾았다() {
        // 스캔이 0건이면 첫 검사가 조용히 통과한다. 검증하지 않은 것을 통과라고 하지 않는다
        assertThat(externalTextFields())
                .as("@ExternalText 필드를 하나도 찾지 못했다 — 스캔 경로가 잘못됐을 수 있다")
                .hasSizeGreaterThan(1)
                .contains("AgentRun.errorMessage");
    }

    /** {@code 클래스.필드} 키로 {@link ExternalText} 가 붙은 필드를 모은다. */
    private static List<String> externalTextFields() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));   // 전부 훑는다

        List<String> fields = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            String name = definition.getBeanClassName();
            if (name == null) {
                continue;
            }
            Class<?> type = ClassUtils.resolveClassName(name, null);
            for (Field field : type.getDeclaredFields()) {
                // record 컴포넌트에 단 애노테이션도 백킹 필드로 내려온다
                if (field.isAnnotationPresent(ExternalText.class)) {
                    fields.add(type.getSimpleName() + "." + field.getName());
                }
            }
        }
        return fields;
    }
}
