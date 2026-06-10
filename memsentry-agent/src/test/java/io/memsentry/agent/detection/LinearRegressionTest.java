package io.memsentry.agent.detection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinearRegressionTest {

    @Test
    void perfectLine() {
        double[] xs = {0, 1, 2, 3, 4};
        double[] ys = {0, 2, 4, 6, 8}; // y = 2x
        LinearRegression r = LinearRegression.fit(xs, ys);
        assertEquals(2.0, r.slope(), 1e-9);
        assertEquals(0.0, r.intercept(), 1e-9);
        assertEquals(1.0, r.rSquared(), 1e-9);
    }

    @Test
    void interceptCaptured() {
        double[] xs = {0, 1, 2, 3};
        double[] ys = {10, 13, 16, 19}; // y = 3x + 10
        LinearRegression r = LinearRegression.fit(xs, ys);
        assertEquals(3.0, r.slope(), 1e-9);
        assertEquals(10.0, r.intercept(), 1e-9);
        assertEquals(1.0, r.rSquared(), 1e-9);
    }

    @Test
    void flatSeriesHasZeroSlopeAndPerfectFit() {
        double[] xs = {0, 1, 2, 3};
        double[] ys = {5, 5, 5, 5};
        LinearRegression r = LinearRegression.fit(xs, ys);
        assertEquals(0.0, r.slope(), 1e-9);
        assertEquals(1.0, r.rSquared(), 1e-9);
    }

    @Test
    void noisyDataHasLowerRSquared() {
        double[] xs = {0, 1, 2, 3, 4, 5};
        double[] ys = {0, 5, 1, 6, 2, 7}; // zig-zag with slight upward drift
        LinearRegression r = LinearRegression.fit(xs, ys);
        assertTrue(r.rSquared() < 0.6, "expected weak fit, got " + r.rSquared());
    }

    @Test
    void singlePointIsDegenerate() {
        LinearRegression r = LinearRegression.fit(new double[]{3}, new double[]{9});
        assertEquals(0.0, r.slope(), 1e-9);
        assertEquals(0.0, r.rSquared(), 1e-9);
    }
}
