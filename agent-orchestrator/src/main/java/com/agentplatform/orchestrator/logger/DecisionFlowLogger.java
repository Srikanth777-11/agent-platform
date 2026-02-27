package com.agentplatform.orchestrator.logger;

import com.agentplatform.common.model.DecisionContext;
import com.agentplatform.common.trace.TraceContextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Signal;

import java.util.function.Consumer;

/**
 * Observability component for the decision lifecycle inside the reactive orchestration pipeline.
 *
 * <p>Logs each stage of a {@code FinalDecision}'s journey without introducing any business logic
 * or modifying pipeline behavior. All methods are pure side-effects.
 *
 * <p>Lifecycle stages (in order):
 * <ol>
 *   <li>{@link #TRIGGER_RECEIVED}       — orchestration triggered by scheduler/API</li>
 *   <li>{@link #MARKET_DATA_FETCHED}    — market-data-service responded successfully</li>
 *   <li>{@link #AGENTS_COMPLETED}       — analysis-engine returned all agent results</li>
 *   <li>{@link #AI_MODEL_SELECTED}      — ModelSelector chose regime-appropriate Claude model</li>
 *   <li>{@link #AI_STRATEGY_EVALUATED}  — AIStrategistService returned primary recommendation</li>
 *   <li>{@link #FINAL_DECISION_CREATED} — consensus guardrail run, decision object assembled</li>
 *   <li>{@link #EVENTS_DISPATCHED}      — decision published and history save initiated</li>
 * </ol>
 *
 * <p>Usage with {@code doOnEach} (reads traceId from Reactor Context):
 * <pre>
 *     .doOnEach(decisionFlowLogger.stage(DecisionFlowLogger.MARKET_DATA_FETCHED))
 * </pre>
 *
 * <p>Usage inside existing {@code doOnNext} (traceId available from domain object):
 * <pre>
 *     decisionFlowLogger.logWithTraceId(DecisionFlowLogger.FINAL_DECISION_CREATED, decision.traceId());
 * </pre>
 */
@Component
public class DecisionFlowLogger {

    private static final Logger log = LoggerFactory.getLogger(DecisionFlowLogger.class);

    public static final String TRIGGER_RECEIVED        = "TRIGGER_RECEIVED";
    public static final String MARKET_DATA_FETCHED     = "MARKET_DATA_FETCHED";
    public static final String AGENTS_COMPLETED        = "AGENTS_COMPLETED";
    public static final String AI_MODEL_SELECTED        = "AI_MODEL_SELECTED";
    public static final String AI_STRATEGY_EVALUATED   = "AI_STRATEGY_EVALUATED";
    public static final String FINAL_DECISION_CREATED  = "FINAL_DECISION_CREATED";
    public static final String DECISION_CONTEXT_ASSEMBLED = "DECISION_CONTEXT_ASSEMBLED";
    public static final String EVENTS_DISPATCHED       = "EVENTS_DISPATCHED";

    /**
     * Returns a {@code doOnEach} consumer that logs the lifecycle stage.
     *
     * <p>Reads traceId from the Reactor Context embedded in the {@link Signal} —
     * never from MDC. Bridges Context → MDC only for the duration of the log call.
     * Only fires on {@code onNext} signals; silently ignores errors and completion.
     *
     * @param stageName one of the stage constants defined in this class
     * @param <T>       the upstream element type (not used in logging)
     * @return a consumer suitable for {@code .doOnEach(...)}
     */
    public <T> Consumer<Signal<T>> stage(String stageName) {
        return signal -> {
            if (!signal.isOnNext()) return;
            String traceId = TraceContextUtil.getTraceId(signal.getContextView());
            TraceContextUtil.withMdc(traceId, () ->
                log.info("[DecisionFlow] stage={} traceId={}", stageName, traceId)
            );
        };
    }

    /**
     * Logs a lifecycle stage when the traceId is already available from a domain object
     * inside an existing {@code doOnNext} handler.
     *
     * <p>Bridges the provided traceId → MDC only for the duration of the log call.
     * Use this only when {@link #stage(String)} cannot be applied (e.g. inside a
     * {@code doOnNext} block that was already present in the pipeline).
     *
     * @param stageName one of the stage constants defined in this class
     * @param traceId   the traceId extracted from the domain object (e.g. {@code decision.traceId()})
     */
    public void logWithTraceId(String stageName, String traceId) {
        TraceContextUtil.withMdc(traceId, () ->
            log.info("[DecisionFlow] stage={} traceId={}", stageName, traceId)
        );
    }

    /**
     * Logs a compact summary of an enriched {@link DecisionContext} — the unified
     * intelligence snapshot after AI evaluation and consensus guardrail.
     *
     * <p>Fields logged: symbol, regime, divergenceFlag, modelLabel, aiSignal.
     * Called once per orchestration cycle, after the context is fully enriched.
     *
     * @param ctx     the enriched (post-AI) DecisionContext
     * @param traceId the traceId for MDC bridging
     */
    public void logDecisionContext(DecisionContext ctx, String traceId) {
        TraceContextUtil.withMdc(traceId, () ->
            log.info("[DecisionFlow] stage={} symbol={} regime={} divergenceFlag={} "
                     + "modelLabel={} aiSignal={} traceId={}",
                     DECISION_CONTEXT_ASSEMBLED,
                     ctx.symbol(), ctx.regime(), ctx.divergenceFlag(),
                     ctx.modelLabel(),
                     ctx.aiDecision() != null ? ctx.aiDecision().finalSignal() : "N/A",
                     traceId)
        );
    }
}
