package com.snets2.rmsca;

import com.snets2.config.PhysicalLayerConfig;
import com.snets2.metrics.PhysicalLayerModel;
import com.snets2.model.ControlPlane;
import com.snets2.model.Link;
import com.snets2.model.ModulationFormat;
import com.snets2.model.Node;
import com.snets2.rmsca.routing.Path;
import com.snets2.rmsca.spectrum.SpectrumInterval;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * KSPXT (port of {@code KSPXT} of SNetS v1, id {@code kspxt}): among <b>all</b> the feasible candidates (every path of
 * the routing, every format of the modulation policy, every (core, interval) of the core and spectrum assignment),
 * the one of lowest cost
 *
 * <pre>  cost = alpha1 * XT / XT_th(m) + alpha2 * u</pre>
 *
 * <p>where {@code XT} is the crosstalk ratio of the new circuit (linear, 0 without crosstalk), {@code XT_th(m)} the
 * threshold of its format (linear) and {@code u} the mean utilization (occupied / total slots) of the chosen core on the
 * links of the path, before the allocation. Parameters {@code alpha1} and {@code alpha2} (default 0.5 each, as v1) in
 * {@code simulation.algorithmParameters}; the number of paths is the {@code k} of the {@code ksp} routing.</p>
 *
 * <p>Differences from v1: v1 normalizes the XT by the <b>largest XT observed so far</b> in the simulation, so the cost of
 * a decision depends on the history; here it is normalized by the threshold of the format, which keeps the cost in
 * [0, 1] for feasible candidates and makes it independent of the history. v1 takes one interval per core with the
 * spectrum rule of ABNE (central core medium fit, even cores Last-Fit, odd cores First-Fit), in decreasing core order:
 * the {@code colourfit} core and spectrum assignment reproduces it. The format order and the reach filter come from the
 * modulation policy. With regenerators, the XT is the worst over the transparent segments, and the cost comparison is
 * done within each pass (a transparent solution is still preferred).</p>
 */
public class KspXtIntegratedRMSCA extends StandardIntegratedRMSCA implements Configurable {

    public static final String ALPHA1 = "alpha1";
    public static final String ALPHA2 = "alpha2";

    private double alpha1 = 0.5;
    private double alpha2 = 0.5;

    @Override
    public Set<String> parameterNames() {
        return Set.of(ALPHA1, ALPHA2);
    }

    @Override
    public void configure(Map<String, Object> parameters) {
        double a1 = Configurable.doubleParameter(parameters, ALPHA1, 0.5);
        double a2 = Configurable.doubleParameter(parameters, ALPHA2, 0.5);
        if (!(a1 >= 0) || !(a2 >= 0) || Double.isInfinite(a1) || Double.isInfinite(a2)) {
            throw new IllegalArgumentException("simulation.algorithmParameters.alpha1 and alpha2 must be finite values >= 0, got "
                    + a1 + " and " + a2);
        }
        this.alpha1 = a1;
        this.alpha2 = a2;
    }

    public double getAlpha1() { return alpha1; }
    public double getAlpha2() { return alpha2; }

    @Override
    protected boolean selectsByCost() {
        return true;
    }

    @Override
    protected double candidateCost(ControlPlane cp, Path path, List<Node> regens, int coreId, SpectrumInterval slots,
                                   ModulationFormat mod) {
        PhysicalLayerConfig config = cp.getPhysicalLayerConfig();
        double xt = 0;
        if (config != null && config.activeQoT() && config.activeXT()) {
            xt = PhysicalLayerModel.predictXtRatio(cp, path, regens, coreId, slots.start(), slots.end())
                    / mod.getCrosstalkThresholdLinear();
        }
        return alpha1 * xt + alpha2 * utilization(path, coreId);
    }

    /** Mean fraction of occupied slots of {@code coreId} on the links of the path. */
    static double utilization(Path path, int coreId) {
        double sum = 0;
        for (Link link : path.links()) {
            var spectrum = link.getCore(coreId).getSpectrum();
            sum += spectrum.getSlots().cardinality() / (double) spectrum.getNumSlots();
        }
        return path.links().isEmpty() ? 0 : sum / path.links().size();
    }
}
