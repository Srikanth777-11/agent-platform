package com.agentplatform.common.risk;

/**
 * Phase-45: Bayesian edge confidence estimator.
 *
 * <p>Replaces the static 52% win-rate threshold used in Phase-38b with a
 * statistically honest posterior probability. A win rate of 0.63 on 25
 * samples is more reliable than 0.55 on 100 samples — this estimator
 * captures that distinction.
 *
 * <h3>Model</h3>
 * <pre>
 *   Prior:     Beta(1, 1)  — uninformative (no initial assumption)
 *   Posterior: Beta(winCount + 1, lossCount + 1)  — conjugate update
 *   Output:    P(true win rate > threshold | observed data)
 * </pre>
 *
 * <h3>Decision rule (Phase-38b gate)</h3>
 * <pre>
 *   posteriorProbability(winCount, lossCount, 0.52) < 0.70 → WATCH
 *   posteriorProbability(winCount, lossCount, 0.52) ≥ 0.70 → ALLOW
 * </pre>
 *
 * <p>Implementation uses the regularized incomplete beta function via
 * modified Lentz's continued fraction (Numerical Recipes algorithm),
 * with Lanczos log-gamma for numerical stability. Accurate to ≤ 1e-10
 * for all valid inputs.
 *
 * <p>Pure static utility — no Spring dependencies, no state.
 */
public final class BayesianEdgeEstimator {

    private BayesianEdgeEstimator() {}

    /**
     * Returns P(true win rate > threshold | observed wins and losses).
     *
     * <p>Uses a Beta(1,1) uninformative prior updated with observed data.
     * The posterior is Beta(winCount+1, lossCount+1).
     *
     * @param winCount  number of winning trades observed (≥ 0)
     * @param lossCount number of losing trades observed (≥ 0)
     * @param threshold the minimum win rate to test against (e.g. 0.52)
     * @return posterior probability in [0.0, 1.0]
     */
    public static double posteriorProbability(int winCount, int lossCount, double threshold) {
        if (winCount < 0 || lossCount < 0) return 0.0;
        if (threshold <= 0.0) return 1.0;
        if (threshold >= 1.0) return 0.0;

        double a = winCount + 1.0;  // posterior alpha
        double b = lossCount + 1.0; // posterior beta

        // P(theta > threshold) = 1 - I_threshold(a, b)
        return 1.0 - regularizedIncompleteBeta(threshold, a, b);
    }

    // ── Regularized incomplete beta I_x(a, b) ──────────────────────────────

    /**
     * Regularized incomplete beta function I_x(a, b) = P(X ≤ x) where X ~ Beta(a, b).
     * Uses symmetry transformation and modified Lentz's continued fraction.
     */
    static double regularizedIncompleteBeta(double x, double a, double b) {
        if (x <= 0.0) return 0.0;
        if (x >= 1.0) return 1.0;

        // Symmetry: use I_x(a,b) = 1 - I_{1-x}(b,a) for x > (a+1)/(a+b+2)
        // (improves continued fraction convergence in the upper half)
        if (x > (a + 1.0) / (a + b + 2.0)) {
            return 1.0 - regularizedIncompleteBeta(1.0 - x, b, a);
        }

        // Factor: x^a * (1-x)^b / (a * B(a,b))
        double logFactor = lnGamma(a + b) - lnGamma(a) - lnGamma(b)
            + a * Math.log(x) + b * Math.log(1.0 - x) - Math.log(a);

        return Math.exp(logFactor) * continuedFractionBeta(x, a, b);
    }

    /**
     * Modified Lentz's continued fraction expansion for the incomplete beta function.
     * Converges for all x in (0, (a+1)/(a+b+2)).
     */
    private static double continuedFractionBeta(double x, double a, double b) {
        final int    MAX_ITER = 200;
        final double EPSILON  = 1e-10;
        final double TINY     = 1e-300;

        double qab = a + b;
        double qap = a + 1.0;
        double qam = a - 1.0;

        double c = 1.0;
        double d = 1.0 - qab * x / qap;
        if (Math.abs(d) < TINY) d = TINY;
        d = 1.0 / d;
        double h = d;

        for (int m = 1; m <= MAX_ITER; m++) {
            int m2 = 2 * m;

            // Even step: d_2m
            double aa = (double) m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1.0 + aa * d;
            c = 1.0 + aa / c;
            if (Math.abs(d) < TINY) d = TINY;
            if (Math.abs(c) < TINY) c = TINY;
            d = 1.0 / d;
            h *= d * c;

            // Odd step: d_{2m+1}
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1.0 + aa * d;
            c = 1.0 + aa / c;
            if (Math.abs(d) < TINY) d = TINY;
            if (Math.abs(c) < TINY) c = TINY;
            d = 1.0 / d;
            double del = d * c;
            h *= del;

            if (Math.abs(del - 1.0) < EPSILON) break;
        }
        return h;
    }

    /**
     * Natural log of the gamma function using Lanczos approximation.
     * Accurate to 15 significant figures for x > 0.
     */
    private static double lnGamma(double x) {
        // Lanczos coefficients (g=5, n=6)
        double[] c = {
             76.18009172947146,
            -86.50532032941677,
             24.01409824083091,
             -1.231739572450155,
              0.001208650973866179,
             -0.000005395239384953
        };
        double y   = x;
        double tmp = x + 5.5;
        tmp -= (x + 0.5) * Math.log(tmp);
        double ser = 1.000000000190015;
        for (double coeff : c) {
            ser += coeff / ++y;
        }
        return -tmp + Math.log(2.5066282746310005 * ser / x);
    }
}
