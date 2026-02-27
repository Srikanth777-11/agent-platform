package com.agentplatform.common.trace;

import org.slf4j.MDC;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * Lightweight reactive tracing utility.
 *
 * <p>Reactor Context is the single source of truth for traceId inside reactive pipelines.
 * MDC is only ever written as a temporary bridge during a log statement — never as a
 * persistent ThreadLocal store.
 *
 * <p>Usage pattern in reactive chains:
 * <pre>
 *     return TraceContextUtil.withTraceId(pipeline, event.traceId());
 * </pre>
 *
 * <p>Usage pattern inside doOnEach:
 * <pre>
 *     signal -> TraceContextUtil.getTraceId(signal.getContextView())
 * </pre>
 *
 * <p>TODO: When distributed tracing (e.g. Micrometer Tracing / Brave) is introduced,
 *       replace the MDC bridge in {@link #withMdc} with the tracer's span-scoping API.
 *       The {@link #withTraceId} and {@link #getTraceId} contracts remain unchanged.
 */
public final class TraceContextUtil {

    public static final String TRACE_ID_KEY = "traceId";

    private TraceContextUtil() {}

    /**
     * Stores {@code traceId} in the Reactor Context so every operator upstream of
     * {@code contextWrite} can read it via {@link #getTraceId(ContextView)}.
     *
     * <p>{@code contextWrite} propagates backwards (upstream) during subscription,
     * so call this at the end of the pipeline assembly.
     *
     * @param mono    the reactive pipeline to enrich
     * @param traceId the trace identifier to propagate
     * @param <T>     pipeline element type
     * @return the same pipeline with traceId stored in its Reactor Context
     */
    public static <T> Mono<T> withTraceId(Mono<T> mono, String traceId) {
        return mono.contextWrite(ctx -> ctx.put(TRACE_ID_KEY, traceId));
    }

    /**
     * Retrieves traceId from the Reactor {@link ContextView}.
     * Returns {@code "unknown"} if not present — never {@code null}.
     *
     * @param ctx the Reactor ContextView obtained from {@code Signal.getContextView()}
     * @return the traceId or {@code "unknown"}
     */
    public static String getTraceId(ContextView ctx) {
        return ctx.getOrDefault(TRACE_ID_KEY, "unknown");
    }

    /**
     * Temporarily bridges {@code traceId} → MDC for the duration of {@code logAction},
     * then removes the MDC entry. ONLY use this inside logging side-effects.
     *
     * <p>This is the only permitted use of ThreadLocal (MDC) in the platform.
     *
     * @param traceId   the traceId to bridge into MDC
     * @param logAction the log statement to execute with MDC populated
     */
    public static void withMdc(String traceId, Runnable logAction) {
        MDC.put(TRACE_ID_KEY, traceId);
        try {
            logAction.run();
        } finally {
            MDC.remove(TRACE_ID_KEY);
        }
    }
}
