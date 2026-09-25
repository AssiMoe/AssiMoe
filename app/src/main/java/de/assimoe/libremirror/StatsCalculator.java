package de.assimoe.libremirror;

import java.util.Calendar;
import java.util.List;

public final class StatsCalculator {
    private StatsCalculator() {}

    public static Summary summarize(List<HistoryDatabase.Point> points, double low, double high) {
        if (points == null || points.isEmpty()) return Summary.empty();

        double sum = 0.0;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        int inRange = 0;
        int lowCount = 0;
        int highCount = 0;

        for (HistoryDatabase.Point p : points) {
            sum += p.mgdl;
            min = Math.min(min, p.mgdl);
            max = Math.max(max, p.mgdl);
            if (p.mgdl < low) lowCount++;
            else if (p.mgdl > high) highCount++;
            else inRange++;
        }

        return new Summary(
                points.size(),
                sum / points.size(),
                min,
                max,
                100.0 * inRange / points.size(),
                lowCount,
                highCount
        );
    }

    public static long startOfDay(long whenMs, int dayOffset) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(whenMs);
        c.add(Calendar.DAY_OF_YEAR, dayOffset);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static final class Summary {
        public final int samples;
        public final double average;
        public final double min;
        public final double max;
        public final double timeInRangePercent;
        public final int lowSamples;
        public final int highSamples;

        Summary(
                int samples,
                double average,
                double min,
                double max,
                double timeInRangePercent,
                int lowSamples,
                int highSamples
        ) {
            this.samples = samples;
            this.average = average;
            this.min = min;
            this.max = max;
            this.timeInRangePercent = timeInRangePercent;
            this.lowSamples = lowSamples;
            this.highSamples = highSamples;
        }

        public static Summary empty() {
            return new Summary(0, 0, 0, 0, 0, 0, 0);
        }
    }
}
