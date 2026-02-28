package com.agentplatform.orchestrator.ai;

import com.agentplatform.common.model.MarketRegime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Regime-aware model selector — maps the current {@link MarketRegime} to the
 * most cost-effective Claude model for that market condition.
 *
 * <p><strong>Strategy:</strong>
 * <ul>
 *   <li>{@code VOLATILE} → cheap/fast model (Haiku) — high-frequency re-evaluation,
 *       decisions are short-lived, cost discipline is critical.</li>
 *   <li>All other regimes → strong model (Sonnet) — deeper reasoning is worth the
 *       latency and cost when the decision interval is longer.</li>
 * </ul>
 *
 * <p>This is a pure static utility with no Spring dependencies — easily testable
 * and deterministic. Model names are constants, not configurable, to avoid
 * accidental misconfiguration in production.
 */
public final class ModelSelector {

    private static final Logger log = LoggerFactory.getLogger(ModelSelector.class);

    /** Cost-optimised model for high-frequency volatile regimes. */
    public static final String CHEAP_MODEL  = "claude-haiku-4-5-20251001";

    /** Full-strength model for regimes where deeper reasoning justifies the cost. */
    public static final String STRONG_MODEL = "claude-sonnet-4-6";

    /** Human-readable label for the regime's model — used in observability/UI, not in API calls. */
    public static final String CHEAP_LABEL  = "haiku-fast";
    /** Human-readable label for the regime's model — used in observability/UI, not in API calls. */
    public static final String STRONG_LABEL = "sonnet-deep";

    private ModelSelector() { /* utility class */ }

    /** Human-readable label for peak-mode fast path. */
    public static final String PEAK_LABEL = "haiku-peak";

    /**
     * Selects the appropriate Claude model based on the detected market regime.
     *
     * @param regime the current market regime (never null)
     * @return the Claude model identifier to use for the AI strategist call
     */
    public static String selectModel(MarketRegime regime) {
        return selectModel(regime, false);
    }

    /**
     * Phase-44: Peak-mode aware model selection.
     * Peak mode forces Haiku regardless of regime — speed over depth.
     *
     * @param regime   the current market regime (never null)
     * @param peakMode true when all peak conditions are met
     * @return the Claude model identifier to use for the AI strategist call
     */
    public static String selectModel(MarketRegime regime, boolean peakMode) {
        if (peakMode) {
            log.info("AI_MODEL_SELECTED regime={} peakMode=true model={} (peak-mode forced)", regime, CHEAP_MODEL);
            return CHEAP_MODEL;
        }
        String selected = (regime == MarketRegime.VOLATILE) ? CHEAP_MODEL : STRONG_MODEL;
        log.info("AI_MODEL_SELECTED regime={} model={}", regime, selected);
        return selected;
    }

    /**
     * Returns the human-readable label for the model selected for the given regime.
     * Pure read-only — no logging, no side effects.
     */
    public static String resolveLabel(MarketRegime regime) {
        return resolveLabel(regime, false);
    }

    /**
     * Phase-44: Peak-mode aware label resolution.
     */
    public static String resolveLabel(MarketRegime regime, boolean peakMode) {
        if (peakMode) return PEAK_LABEL;
        return (regime == MarketRegime.VOLATILE) ? CHEAP_LABEL : STRONG_LABEL;
    }
}
