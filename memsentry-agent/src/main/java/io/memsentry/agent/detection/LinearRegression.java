package io.memsentry.agent.detection;

/**
 * Ordinary least-squares fit of {@code y = slope*x + intercept}, plus the coefficient
 * of determination (R²). MemSentry uses the slope to measure how fast a class's retained
 * bytes grow over time, and R² to gauge how confidently that growth is a sustained linear
 * trend rather than noise — the signature of a real leak.
 *
 * @param slope     change in y per unit x (bytes per second, in our usage)
 * @param intercept y value at x = 0
 * @param rSquared  goodness of fit in [0, 1]; 1.0 is a perfect line
 */
public record LinearRegression(double slope, double intercept, double rSquared) {

    /** Fits the line over paired samples. {@code xs} and {@code ys} must be equal length. */
    public static LinearRegression fit(double[] xs, double[] ys) {
        if (xs.length != ys.length) {
            throw new IllegalArgumentException("xs and ys length mismatch");
        }
        int n = xs.length;
        if (n < 2) {
            return new LinearRegression(0, n == 1 ? ys[0] : 0, 0);
        }

        double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
        for (int i = 0; i < n; i++) {
            sx += xs[i];
            sy += ys[i];
            sxx += xs[i] * xs[i];
            syy += ys[i] * ys[i];
            sxy += xs[i] * ys[i];
        }

        double denomX = n * sxx - sx * sx;
        if (denomX == 0.0) {
            // All x equal — cannot fit a line.
            return new LinearRegression(0, sy / n, 0);
        }

        double slope = (n * sxy - sx * sy) / denomX;
        double intercept = (sy - slope * sx) / n;

        double denomY = n * syy - sy * sy;
        double rSquared;
        if (denomY == 0.0) {
            // y is constant: a flat line is a perfect fit, but there is no growth.
            rSquared = (slope == 0.0) ? 1.0 : 0.0;
        } else {
            double r = (n * sxy - sx * sy) / Math.sqrt(denomX * denomY);
            rSquared = r * r;
        }
        return new LinearRegression(slope, intercept, rSquared);
    }
}
