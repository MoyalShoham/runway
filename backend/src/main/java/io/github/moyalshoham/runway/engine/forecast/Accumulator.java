package io.github.moyalshoham.runway.engine.forecast;

import io.github.moyalshoham.runway.engine.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collects simulated flows: per-path daily totals, per-component sums and per-item statistics. */
final class Accumulator {

    private final LocalDate asOf;
    private final int horizon;
    private final BigDecimal[][] componentSums;
    private final Map<String, Item> items = new LinkedHashMap<>();
    private BigDecimal[] flows;
    private int path = -1;

    Accumulator(LocalDate asOf, int horizon) {
        this.asOf = asOf;
        this.horizon = horizon;
        this.componentSums = new BigDecimal[Component.values().length][];
        for (int c = 0; c < componentSums.length; c++) {
            componentSums[c] = zeros(horizon);
        }
    }

    void startPath(int p) {
        path = p;
        flows = zeros(horizon);
    }

    BigDecimal[] flows() {
        return flows;
    }

    /** Records a flow; flows dated outside (asOf, asOf + horizon] are ignored. */
    void add(Component component, String key, String label, LocalDate date, BigDecimal amount) {
        long h = ChronoUnit.DAYS.between(asOf, date);
        if (h < 1 || h > horizon || amount.signum() == 0) {
            return;
        }
        int i = (int) h - 1;
        flows[i] = flows[i].add(amount);
        componentSums[component.ordinal()][i] = componentSums[component.ordinal()][i].add(amount);
        items.computeIfAbsent(key, k -> new Item(component, label)).record(path, (int) h, amount);
    }

    Map<Component, BigDecimal> componentTotals(int paths) {
        Map<Component, BigDecimal> totals = new EnumMap<>(Component.class);
        for (Component c : Component.values()) {
            BigDecimal sum = Money.sum(Arrays.asList(componentSums[c.ordinal()]));
            totals.put(c, sum.divide(BigDecimal.valueOf(paths), Money.SCALE, Money.ROUNDING));
        }
        return totals;
    }

    List<ScheduleItem> schedule(int paths) {
        List<ScheduleItem> schedule = new ArrayList<>();
        items.forEach((key, item) -> {
            int n = item.days.size();
            int[] days = item.days.stream().mapToInt(Integer::intValue).sorted().toArray();
            BigDecimal mean = Money.sum(item.amounts).divide(BigDecimal.valueOf(n), Money.SCALE, Money.ROUNDING);
            schedule.add(new ScheduleItem(
                    key, item.component, item.label, asOf.plusDays(days[(n - 1) / 2]), mean, n / (double) paths));
        });
        return schedule;
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        Arrays.fill(a, Money.ZERO);
        return a;
    }

    private static final class Item {
        final Component component;
        final String label;
        final List<Integer> days = new ArrayList<>();
        final List<BigDecimal> amounts = new ArrayList<>();
        int lastPath = -1;

        Item(Component component, String label) {
            this.component = component;
            this.label = label;
        }

        void record(int path, int day, BigDecimal amount) {
            if (path != lastPath) {
                lastPath = path;
                days.add(day);
                amounts.add(amount);
            } else {
                int last = amounts.size() - 1;
                amounts.set(last, amounts.get(last).add(amount));
            }
        }
    }
}
