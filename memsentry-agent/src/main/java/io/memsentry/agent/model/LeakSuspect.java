package io.memsentry.agent.model;

import java.util.List;

/**
 * A class whose retained-byte footprint is growing on a sustained linear trend.
 *
 * @param className           the (possibly array/inner) class name from the histogram
 * @param currentBytes        retained bytes in the most recent sample
 * @param currentInstances    live instance count in the most recent sample
 * @param growthBytesPerSec   regression slope of bytes over time
 * @param rSquared            confidence the growth is linear (0..1)
 * @param samples             number of samples backing the fit
 * @param leak                true if it crossed the configured leak thresholds
 * @param seriesBytes         recent byte history (oldest→newest) for the sparkline
 * @param allocationSite      top allocation frame from JFR, if attributed; may be null
 */
public record LeakSuspect(
        String className,
        long currentBytes,
        long currentInstances,
        double growthBytesPerSec,
        double rSquared,
        int samples,
        boolean leak,
        List<Long> seriesBytes,
        String allocationSite) {

    /** Growth expressed in MB/min, the unit shown in the UI. */
    public double growthMbPerMin() {
        return growthBytesPerSec * 60.0 / (1024.0 * 1024.0);
    }
}
