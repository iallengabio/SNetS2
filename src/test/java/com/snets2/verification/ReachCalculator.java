package com.snets2.verification;

import com.snets2.config.ConfigLoader;
import com.snets2.config.ModulationConfig;
import com.snets2.config.PhysicalLayerConfig;
import com.snets2.config.ScenarioSetup;
import com.snets2.config.TrafficConfig;
import com.snets2.metrics.PhysicalLayerModel;
import com.snets2.model.*;
import com.snets2.rmsca.modulation.SlotCalculator;
import com.snets2.rmsca.routing.Path;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Transparent reach of each modulation format computed with the SNetS2 physical layer model, so that the
 * {@code maxRange} of the formats describes the same system (power, FEC, grid, amplifiers) as their SNR
 * thresholds (issue #16; procedure in docs/formal_description/07_physical_layer_models.md, §6.2).
 *
 * <p><b>Reference load.</b> One directed link of length {@code L} (booster + line amplifiers + pre-amplifier,
 * i.e. one transparent hop) with a single core of {@code totalSlots} slots, completely filled with channels
 * equal to the channel under test: {@code floor(totalSlots / n)} contiguous allocations of {@code n} slots
 * ({@link SlotCalculator}, guard band included). The channel under test is the central one, which suffers the
 * largest cross-channel NLI; it is evaluated with {@link PhysicalLayerModel#predictSNR} as a candidate while
 * all the others are established. Inter-core crosstalk is not part of the reach: it has its own threshold
 * ({@code XT}) and depends on the core layout, not on the length alone.</p>
 *
 * <p><b>Reach.</b> Largest length, multiple of {@code step} km (default 10 km), whose SNR is at least the
 * threshold of the format. The SNR is non-increasing in {@code L}, so the search is a bisection over the
 * multiples of {@code step}. With {@code spanLength} a multiple of {@code step}, the span boundaries, where the
 * NLI jumps, are part of the grid.</p>
 *
 * <p><b>Reference bit rate.</b> By default the worst case over the bit rates of {@code traffic.bitRates}: the
 * resulting {@code maxRange} guarantees that any configured request whose path is a single hop not longer than
 * {@code maxRange} meets the SNR threshold with a fully loaded core. A fixed bit rate can be given instead.</p>
 *
 * <pre>
 * scripts/compute_reach.sh experiments/experiment01/setup.json [--bitRate worst|&lt;Gbps&gt;] [--step &lt;km&gt;]
 * </pre>
 */
public final class ReachCalculator {

    /** Upper bound of the search (km); a format still feasible there is reported with this reach. */
    static final double MAX_SEARCH_KM = 100_000;

    private ReachCalculator() {}

    /** Reach of one format at one bit rate. {@code channels} is the number of equal channels in the core. */
    record Reach(String modulation, double bitRate, int slots, int channels, double reachKm, double snrAtReachDb) {}

    /**
     * SNR (linear) of the central channel of a core fully loaded with channels equal to it, on one link of
     * {@code lengthKm}. Returns {@link Double#POSITIVE_INFINITY} when the grid does not fit one channel.
     */
    static double referenceSnr(PhysicalLayerConfig cfg, int totalSlots, ModulationFormat mod, double bitRate, double lengthKm) {
        Node a = new Node("A", Integer.MAX_VALUE, Integer.MAX_VALUE, 0);
        Node b = new Node("B", Integer.MAX_VALUE, Integer.MAX_VALUE, 0);
        Link link = new Link("A", "B", lengthKm, List.of(new Core(0, List.of(), totalSlots)), List.of());
        NetworkTopology topology = new NetworkTopology(List.of(a, b), List.of(link), List.of(mod));
        ControlPlane cp = new ControlPlane(topology, null, cfg.bvtSpectralWidth(), cfg.guardBand(), cfg);

        int n = SlotCalculator.requiredSlots(bitRate, mod, cp);
        int channels = totalSlots / n;
        if (channels == 0) return Double.POSITIVE_INFINITY;
        int victim = channels / 2;
        for (int k = 0; k < channels; k++) {
            if (k == victim) continue;
            cp.establishCircuit(new Circuit("c" + k, a, b, List.of(link), List.of(0), k * n, k * n + n - 1, mod, bitRate));
        }
        return PhysicalLayerModel.predictSNR(cp, new Path(List.of(link)), 0, victim * n, victim * n + n - 1, mod, bitRate);
    }

    /** Transparent reach (km, multiple of {@code stepKm}) of {@code mod} at {@code bitRate} under the reference load. */
    static Reach reach(PhysicalLayerConfig cfg, int totalSlots, ModulationFormat mod, double bitRate, double stepKm) {
        int slots = SlotCalculator.requiredSlots(bitRate, mod, cfg.bvtSpectralWidth(), cfg.guardBand(),
                cfg.polarizationModes(), cfg.rateOfFEC());
        int channels = totalSlots / slots;
        double threshold = mod.getSnrThresholdLinear();
        long lo = 0;                                              // feasible (0 km: always)
        long hi = (long) Math.floor(MAX_SEARCH_KM / stepKm) + 1;  // infeasible, or beyond the search range
        if (referenceSnr(cfg, totalSlots, mod, bitRate, (hi - 1) * stepKm) >= threshold) {
            lo = hi - 1;
        } else {
            while (hi - lo > 1) {
                long mid = (lo + hi) >>> 1;
                if (referenceSnr(cfg, totalSlots, mod, bitRate, mid * stepKm) >= threshold) lo = mid;
                else hi = mid;
            }
        }
        double reachKm = lo * stepKm;
        double snrDb = lo == 0 ? Double.NaN : 10 * Math.log10(referenceSnr(cfg, totalSlots, mod, bitRate, reachKm));
        return new Reach(mod.name(), bitRate, slots, channels, reachKm, snrDb);
    }

    /** Formats of the setup as model objects (maxRange is not used by the computation). */
    static List<ModulationFormat> formats(ScenarioSetup setup) {
        List<ModulationFormat> list = new ArrayList<>();
        for (ModulationConfig m : setup.networkTopology().modulations()) {
            list.add(new ModulationFormat(m.name(), m.maxRange(), m.M(), m.SNR(), m.XT(), 32.0, 0.1));
        }
        return list;
    }

    /**
     * Reach of every format of the setup: one entry per format and reference bit rate. With
     * {@code bitRate == null} every bit rate of {@code traffic.bitRates} is evaluated.
     */
    static List<List<Reach>> compute(ScenarioSetup setup, Double bitRate, double stepKm) {
        List<Double> rates = new ArrayList<>();
        if (bitRate != null) {
            rates.add(bitRate);
        } else {
            for (TrafficConfig.BitRateConfig r : setup.traffic().bitRates()) rates.add(r.value());
        }
        List<List<Reach>> table = new ArrayList<>();
        for (ModulationFormat mod : formats(setup)) {
            List<Reach> row = new ArrayList<>();
            for (double r : rates) {
                row.add(reach(setup.physicalLayer(), setup.simulation().totalSlots(), mod, r, stepKm));
            }
            table.add(row);
        }
        return table;
    }

    /** Worst (shortest) reach of a row, i.e. the maxRange guaranteed for every bit rate of the row. */
    static Reach worst(List<Reach> row) {
        Reach w = row.get(0);
        for (Reach r : row) if (r.reachKm() < w.reachKm()) w = r;
        return w;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: ReachCalculator <setup.json> [--bitRate worst|<Gbps>] [--step <km>]");
            System.exit(2);
        }
        Double bitRate = null;
        double step = 10.0;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--bitRate" -> {
                    String v = args[++i];
                    bitRate = v.equalsIgnoreCase("worst") ? null : Double.valueOf(v);
                }
                case "--step" -> step = Double.parseDouble(args[++i]);
                default -> throw new IllegalArgumentException("unknown option: " + args[i]);
            }
        }
        ScenarioSetup setup = ConfigLoader.load(new File(args[0])).getBaseScenario();
        PhysicalLayerConfig p = setup.physicalLayer();
        List<List<Reach>> table = compute(setup, bitRate, step);

        Locale l = Locale.ROOT;
        System.out.printf(l, "Reference load: 1 link, %d slots of %.1f GHz fully filled with equal channels (central one tested)%n",
                setup.simulation().totalSlots(), p.bvtSpectralWidth() / 1E9);
        System.out.printf(l, "power = %.2f dBm (%s PSD), rateOfFEC = %.2f, polarizationModes = %.0f, guardBand = %d, spanLength = %.0f km,%n",
                p.power(), p.fixedPowerSpectralDensity() ? "fixed" : "variable", p.rateOfFEC(), p.polarizationModes(), p.guardBand(), p.spanLength());
        System.out.printf(l, "ASE = %b, NLI = %b, typeOfAmplifierGain = %d, step = %.0f km%n%n", p.activeASE(), p.activeNLI(), p.typeOfAmplifierGain(), step);

        System.out.printf(l, "%-8s %8s %8s", "format", "SNR(dB)", "maxRange");
        for (Reach r : table.get(0)) System.out.printf(l, " %16s", String.format(l, "%.0fG (n, ch)", r.bitRate()));
        System.out.printf(l, " %10s%n", "reach(km)");
        List<ModulationFormat> formats = formats(setup);
        for (int i = 0; i < table.size(); i++) {
            ModulationFormat mod = formats.get(i);
            System.out.printf(l, "%-8s %8.2f %8.0f", mod.name(), mod.snrThreshold(), mod.maxReach());
            for (Reach r : table.get(i)) {
                System.out.printf(l, " %16s", String.format(l, "%.0f (%d, %d)", r.reachKm(), r.slots(), r.channels()));
            }
            Reach w = worst(table.get(i));
            System.out.printf(l, " %10.0f  (%.0f Gbps, SNR %.2f dB)%n", w.reachKm(), w.bitRate(), w.snrAtReachDb());
        }

        System.out.println();
        System.out.println("\"modulations\": [");
        for (int i = 0; i < formats.size(); i++) {
            ModulationFormat mod = formats.get(i);
            System.out.printf(l, "  {\"name\": \"%s\", \"maxRange\": %.1f, \"M\": %.1f, \"SNR\": %s, \"XT\": %s}%s%n",
                    mod.name(), worst(table.get(i)).reachKm(), mod.m(), mod.snrThreshold(), mod.crosstalkThreshold(),
                    i + 1 < formats.size() ? "," : "");
        }
        System.out.println("]");
    }
}
