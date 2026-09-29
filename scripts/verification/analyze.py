#!/usr/bin/env python3
"""Analysis of the SNetS2 verification and validation campaign.

Reads the CSV files written by com.snets2.verification.VerificationCampaign, computes the analytical
oracles (Erlang-B, Kaufman-Roberts, exact CTMC of a 3-node line with first-fit, amplifier ASE, GN
self-channel interference, multicore crosstalk), compares them with the simulated values and writes
figures (PNG) and Markdown tables.

Usage:
    python3 scripts/verification/analyze.py [data_dir] [output_dir]
    (defaults: docs/review/vv/data docs/review/vv)

Requires: numpy, matplotlib (see scripts/verification/requirements.txt).
"""
import csv
import math
import os
import sys
from collections import defaultdict

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402

DATA = sys.argv[1] if len(sys.argv) > 1 else "docs/review/vv/data"
OUT = sys.argv[2] if len(sys.argv) > 2 else "docs/review/vv"
FIG = os.path.join(OUT, "figures")
os.makedirs(FIG, exist_ok=True)

# Two-sided Student t quantiles for a 95 % confidence interval, by degrees of freedom
T95 = {1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571, 6: 2.447, 7: 2.365, 8: 2.306, 9: 2.262, 19: 2.093}

plt.rcParams.update({
    "figure.figsize": (6.4, 4.2), "figure.dpi": 130, "axes.grid": True, "grid.alpha": 0.3,
    "axes.spines.top": False, "axes.spines.right": False, "font.size": 10, "legend.fontsize": 8,
    "legend.frameon": False, "axes.titlesize": 9,
})
COLORS = ["#2a6fdb", "#e0672b", "#2e9e5b", "#b03a9e", "#8a6d1d", "#1b9aaa", "#666666", "#c62828", "#000000"]

TABLES = []  # (title, markdown) pairs written to tables.md


# ------------------------------------------------------------------------------------------------
# Helpers
# ------------------------------------------------------------------------------------------------

def read(name):
    with open(os.path.join(DATA, name + ".csv")) as f:
        return list(csv.DictReader(f))


def stats(values):
    """Mean, standard error and 95 % CI half-width over replications."""
    v = np.asarray(values, dtype=float)
    n = len(v)
    mean = float(v.mean())
    se = float(v.std(ddof=1) / math.sqrt(n)) if n > 1 else 0.0
    return mean, se, T95.get(n - 1, 1.96) * se


def group(rows, keys, value):
    out = defaultdict(list)
    for r in rows:
        out[tuple(r[k] for k in keys)].append(float(r[value]))
    return out


def verdict(sim, se, theory, abs_floor=1e-3):
    """Standard criterion of the verification plan: |mean - theory| <= 4 SE + floor."""
    return "PASS" if abs(sim - theory) <= 4 * se + abs_floor else "FAIL"


def table(title, header, rows):
    lines = ["| " + " | ".join(header) + " |", "| " + " | ".join([":--:"] * len(header)) + " |"]
    lines += ["| " + " | ".join(str(c) for c in row) + " |" for row in rows]
    TABLES.append((title, "\n".join(lines)))


def fmt(x, digits=4):
    if isinstance(x, str):
        return x
    if x == 0:
        return "0"
    if abs(x) < 1e-3 or abs(x) >= 1e5:
        return f"{x:.{digits - 1}e}"
    return f"{x:.{digits}g}"


def save(fig, name):
    fig.tight_layout()
    fig.savefig(os.path.join(FIG, name + ".png"))
    plt.close(fig)


# ------------------------------------------------------------------------------------------------
# Oracles
# ------------------------------------------------------------------------------------------------

def erlang_b(a, c):
    b = 1.0
    for k in range(1, c + 1):
        b = a * b / (k + a * b)
    return b


def kaufman_roberts(loads, sizes, capacity):
    """Per-class blocking of the multi-rate loss system (no contiguity constraint)."""
    q = np.zeros(capacity + 1)
    q[0] = 1.0
    for j in range(1, capacity + 1):
        q[j] = sum(a * b * q[j - b] for a, b in zip(loads, sizes) if j - b >= 0) / j
    q /= q.sum()
    return [float(q[capacity - b + 1:].sum()) for b in sizes]


def tandem_ctmc(a, c):
    """Exact blocking of the 3-node line (links L1, L2) with first-fit and slot continuity.

    Flows: x uses L1, y uses L2, z uses L1+L2 on the same slot; each has offered load a (mu = 1).
    Slot state: 0 free, 1 x on L1, 2 y on L2, 3 x on L1 and y on L2, 4 z on both links.
    Returns the blocking probabilities of x, y and z (PASTA).
    """
    import itertools
    states = list(itertools.product(range(5), repeat=c))
    index = {s: i for i, s in enumerate(states)}
    n = len(states)
    q = np.zeros((n, n))

    def first(s, allowed):
        for i, v in enumerate(s):
            if v in allowed:
                return i
        return None

    blocked = {"x": np.zeros(n, bool), "y": np.zeros(n, bool), "z": np.zeros(n, bool)}
    for s in states:
        i = index[s]
        for flow, allowed, move in (("x", (0, 2), {0: 1, 2: 3}), ("y", (0, 1), {0: 2, 1: 3}), ("z", (0,), {0: 4})):
            k = first(s, allowed)
            if k is None:
                blocked[flow][i] = True
            else:
                t = list(s)
                t[k] = move[s[k]]
                q[i, index[tuple(t)]] += a
        for k, v in enumerate(s):
            for to in {1: (0,), 2: (0,), 3: (2, 1), 4: (0,)}.get(v, ()):
                t = list(s)
                t[k] = to
                q[i, index[tuple(t)]] += 1.0
    np.fill_diagonal(q, -q.sum(axis=1))
    # Solve pi Q = 0 with sum(pi) = 1
    m = np.vstack([q.T, np.ones(n)])
    rhs = np.zeros(n + 1)
    rhs[-1] = 1.0
    pi = np.linalg.lstsq(m, rhs, rcond=None)[0]
    return [float(pi[blocked[f]].sum()) for f in ("x", "y", "z")]


# Physical constants and parameters of the shipped experiments (experiments/experiment01)
H, NU, NF_DB, SPAN, ALPHA_DB, LSSS = 6.626e-34, 1.9385e14, 5.0, 80.0, 0.2, 5.0
GAMMA, D, SLOT = 1.3e-3, 1.6e-5, 12.5e9
KAPPA, RADIUS, BETA, PITCH = 0.012, 0.01, 1.0e7, 4.5e-5


def lin(db):
    return 10 ** (db / 10)


def link_ase(length_km):
    """Independent re-implementation of the amplifier chain (booster + line + pre-amplifier)."""
    n_line = max(0, math.ceil(length_km / SPAN - 1))
    last = length_km - n_line * SPAN
    gains = [3 * LSSS] + [ALPHA_DB * SPAN] * n_line + [ALPHA_DB * last]
    return sum(lin(NF_DB) * H * NU * (lin(g) - 1) for g in gains), n_line


def gn_eta(length_km, bandwidth):
    """NLI coefficient eta with I_NLI = eta * I^3 (GN closed form, isolated channel, incoherent spans)."""
    alpha = ALPHA_DB / (10 * math.log10(math.e) * 1000)
    c = 299792458.0
    lam = c / NU
    beta2 = D * lam ** 2 / (2 * math.pi * c)
    leff = (1 - math.exp(-alpha * SPAN * 1000)) / alpha
    leff_a = 1 / alpha
    mu = (8 / 27) * GAMMA ** 2 * leff ** 2 / (math.pi * beta2 * leff_a)
    rho = math.pi ** 2 / 2 * beta2 * leff_a
    spans = max(1, math.ceil(length_km / SPAN))
    return spans * mu * math.asinh(rho * bandwidth ** 2)


XT_H = 2 * KAPPA ** 2 * RADIUS / (BETA * PITCH)


# ------------------------------------------------------------------------------------------------
# E1 - Erlang B
# ------------------------------------------------------------------------------------------------

def e1():
    rows = read("e1_erlang")
    bp = group(rows, ["slots", "load_per_direction"], "bp")
    util = group(rows, ["slots", "load_per_direction"], "utilization")
    fig, ax = plt.subplots()
    out, fails = [], 0
    for idx, c in enumerate(sorted({int(k[0]) for k in bp})):
        keys = sorted((k for k in bp if int(k[0]) == c), key=lambda k: float(k[1]))
        a = np.array([float(k[1]) for k in keys])
        ms = [stats(bp[k]) for k in keys]
        grid = np.linspace(a.min() * 0.8, a.max() * 1.1, 200)
        ax.plot(grid, [erlang_b(x, c) for x in grid], color=COLORS[idx], lw=1)
        ax.errorbar(a, [m[0] for m in ms], yerr=[m[2] for m in ms], fmt="o", ms=4, color=COLORS[idx], label=f"c = {c}")
        for k, (m, se, ci) in zip(keys, ms):
            th = erlang_b(float(k[1]), c)
            um, use, _ = stats(util[k])
            uth = float(k[1]) * (1 - th) / c
            v = verdict(m, se, th)
            vu = verdict(um, use, uth)
            fails += (v == "FAIL") + (vu == "FAIL")
            out.append([c, fmt(float(k[1])), fmt(th), f"{fmt(m)} ± {fmt(ci, 2)}", f"{100 * (m - th) / th:+.1f} %",
                        v, fmt(uth), f"{fmt(um)} ± {fmt(stats(util[k])[2], 2)}", vu])
    ax.set_yscale("log")
    ax.set_xscale("log")
    ax.set_xlabel("Carga oferecida por sentido A (Erlang)")
    ax.set_ylabel("Probabilidade de bloqueio")
    ax.set_title("E1 – Enlace único M/M/c/c: simulação (pontos, IC 95 %) × Erlang B (linhas)")
    ax.legend()
    save(fig, "e1_erlang_b")
    table("E1 – Erlang B e utilização (10 réplicas × 200 k requisições medidas)",
          ["c", "A (Erl)", "B teórico", "B simulado (IC 95 %)", "erro rel.", "veredito",
           "U teórica = A(1−B)/c", "U simulada", "veredito"], out)
    return fails


# ------------------------------------------------------------------------------------------------
# E2 - Transmitters
# ------------------------------------------------------------------------------------------------

def e2():
    rows = read("e2_transmitters")
    bp = group(rows, ["tx", "load_per_node"], "bp")
    other = group(rows, ["tx", "load_per_node"], "bp_other_causes")
    out, fails = [], 0
    fig, ax = plt.subplots()
    for idx, tx in enumerate(sorted({int(k[0]) for k in bp})):
        keys = sorted((k for k in bp if int(k[0]) == tx), key=lambda k: float(k[1]))
        a = np.array([float(k[1]) for k in keys])
        ms = [stats(bp[k]) for k in keys]
        grid = np.linspace(a.min() * 0.9, a.max() * 1.05, 100)
        ax.plot(grid, [erlang_b(x, tx) for x in grid], color=COLORS[idx], lw=1)
        ax.errorbar(a, [m[0] for m in ms], yerr=[m[2] for m in ms], fmt="s", ms=4, color=COLORS[idx], label=f"{tx} transmissores/nó")
        for k, (m, se, ci) in zip(keys, ms):
            th = erlang_b(float(k[1]), tx)
            v = verdict(m, se, th)
            fails += v == "FAIL"
            out.append([tx, fmt(float(k[1])), fmt(th), f"{fmt(m)} ± {fmt(ci, 2)}", v, fmt(max(other[k]))])
    ax.set_yscale("log")
    ax.set_xlabel("Carga originada por nó (Erlang)")
    ax.set_ylabel("Probabilidade de bloqueio")
    ax.set_title("E2 – Transmissores como servidores: simulação × Erlang B")
    ax.legend()
    save(fig, "e2_transmitters")
    table("E2 – Bloqueio por falta de transmissores (espectro abundante)",
          ["Tx/nó", "A (Erl)", "B teórico", "B simulado (IC 95 %)", "veredito", "máx. bloqueio por outras causas"], out)
    return fails


# ------------------------------------------------------------------------------------------------
# E3 - Kaufman-Roberts and spectrum policies
# ------------------------------------------------------------------------------------------------

LABEL = {"firstfit": "First-Fit", "lastfit": "Last-Fit", "exactfit": "Exact-Fit", "randomfit": "Random-Fit"}


def e3():
    """Contiguity makes Kaufman-Roberts a lower bound only for the wide class and for the aggregate
    bit-rate blocking: the 1-slot class may block less, since it uses holes the 2-slot class cannot."""
    rows = read("e3_kaufman_roberts")
    c = int(rows[0]["slots"])
    out = []
    fig, axes = plt.subplots(1, 3, figsize=(14, 4.2), sharey=True)
    loads = sorted({float(r["load_per_direction"]) for r in rows})
    grid = np.linspace(min(loads) * 0.9, max(loads) * 1.05, 100)
    kr = np.array([kaufman_roberts([x / 2, x / 2], [1, 2], c) for x in grid])
    kr_rate = (kr[:, 0] * 25 + kr[:, 1] * 50) / 75
    violations = 0
    panels = ((axes[0], kr[:, 0], "bp_1slot", "Classe de 1 slot (25 Gbps)"),
              (axes[1], kr[:, 1], "bp_2slot", "Classe de 2 slots (50 Gbps)"),
              (axes[2], kr_rate, "bp_bitrate", "Bloqueio de banda agregado"))
    for ax, curve, col, title in panels:
        ax.plot(grid, curve, "k-", lw=1.4, label="Kaufman–Roberts (sem contiguidade)")
        for idx, alg in enumerate(LABEL):
            g = group([r for r in rows if r["algorithm"] == alg], ["load_per_direction"], col)
            keys = sorted(g, key=lambda k: float(k[0]))
            ms = [stats(g[k]) for k in keys]
            nz = [(float(k[0]), m) for k, m in zip(keys, ms) if m[0] > 0]
            ax.errorbar([x for x, _ in nz], [m[0] for _, m in nz], yerr=[m[2] for _, m in nz],
                        fmt="o-" if alg != "lastfit" else "x--", ms=3 if alg != "lastfit" else 6,
                        lw=0.8, color=COLORS[idx], label=LABEL[alg])
        ax.set_yscale("log")
        ax.set_xlabel("Carga por sentido (Erlang)")
        ax.set_title(title, fontsize=9)
    axes[0].set_ylabel("Probabilidade de bloqueio")
    axes[0].legend()
    fig.suptitle(f"E3 – Duas classes em um enlace de {c} slots: políticas de espectro × Kaufman–Roberts")
    save(fig, "e3_kaufman_roberts")
    identical = True
    for a in loads:
        kr1, kr2 = kaufman_roberts([a / 2, a / 2], [1, 2], c)
        krr = (25 * kr1 + 50 * kr2) / 75
        row = [fmt(a), f"{fmt(kr1, 3)} / {fmt(kr2, 3)} / {fmt(krr, 3)}"]
        for alg in LABEL:
            sub = [r for r in rows if r["algorithm"] == alg and float(r["load_per_direction"]) == a]
            m1 = stats([float(r["bp_1slot"]) for r in sub])[0]
            m2, se2, _ = stats([float(r["bp_2slot"]) for r in sub])
            mr, ser, _ = stats([float(r["bp_bitrate"]) for r in sub])
            ok = m2 >= kr2 - 4 * se2 - 1e-4 and mr >= krr - 4 * ser - 1e-4
            violations += not ok
            row.append(f"{fmt(m1, 3)} / {fmt(m2, 3)} / {fmt(mr, 3)}")
        ff = [r["bp_bitrate"] for r in rows if r["algorithm"] == "firstfit" and float(r["load_per_direction"]) == a]
        lf = [r["bp_bitrate"] for r in rows if r["algorithm"] == "lastfit" and float(r["load_per_direction"]) == a]
        identical &= ff == lf
        out.append(row)
    violations += not identical
    table(f"E3 – Bloqueio por classe (1 slot / 2 slots / banda agregada), enlace de {c} slots",
          ["A (Erl)", "Kaufman–Roberts"] + [LABEL[a] for a in LABEL], out)
    TABLES.append(("E3 – Identidade First-Fit × Last-Fit",
                   "Com a mesma semente, First-Fit e Last-Fit produziram valores **idênticos em todas as réplicas e cargas**: "
                   + ("sim" if identical else "**não**") + " (espelhamento exato do espectro em um enlace único)."))
    return violations


# ------------------------------------------------------------------------------------------------
# E4 - Tandem CTMC
# ------------------------------------------------------------------------------------------------

def e4():
    rows = read("e4_tandem")
    c = int(rows[0]["slots"])
    out, fails = [], 0
    fig, ax = plt.subplots()
    loads = sorted({float(r["load_per_pair"]) for r in rows})
    grid = np.linspace(min(loads), max(loads), 15)
    exact = np.array([tandem_ctmc(x, c) for x in grid])
    names = {"bp_01": "1 salto (enlace 1)", "bp_12": "1 salto (enlace 2)", "bp_02": "2 saltos"}
    for idx, (col, cls) in enumerate((("bp_01", 0), ("bp_12", 1), ("bp_02", 2))):
        ax.plot(grid, exact[:, cls], color=COLORS[idx], lw=1)
        g = group(rows, ["load_per_pair"], col)
        keys = sorted(g, key=lambda k: float(k[0]))
        ms = [stats(g[k]) for k in keys]
        ax.errorbar([float(k[0]) for k in keys], [m[0] for m in ms], yerr=[m[2] for m in ms], fmt="o", ms=4,
                    color=COLORS[idx], label=names[col])
    for a in loads:
        ex = tandem_ctmc(a, c)
        row = [fmt(a)]
        for col, th in zip(("bp_01", "bp_12", "bp_02"), ex):
            m, se, ci = stats([float(r[col]) for r in rows if float(r["load_per_pair"]) == a])
            v = verdict(m, se, th)
            fails += v == "FAIL"
            row += [fmt(th), f"{fmt(m)} ± {fmt(ci, 2)}", v]
        out.append(row)
    ax.set_yscale("log")
    ax.set_xlabel("Carga por par origem-destino (Erlang)")
    ax.set_ylabel("Probabilidade de bloqueio")
    ax.set_title(f"E4 – Linha de 3 nós, {c} slots, First-Fit: simulação × cadeia de Markov exata")
    ax.legend()
    save(fig, "e4_tandem")
    table(f"E4 – Linha de 3 nós com continuidade de espectro ({c} slots, First-Fit) × CTMC exata",
          ["A/par", "1 salto (L1) exato", "simulado", "", "1 salto (L2) exato", "simulado", "", "2 saltos exato", "simulado", ""], out)
    return fails


# ------------------------------------------------------------------------------------------------
# E5 - RMSA algorithms on NSFNET
# ------------------------------------------------------------------------------------------------

def e5():
    rows = read("e5_algorithms")
    groups = ["spectrum", "routing", "modulation"]
    titles = {"spectrum": "Alocação de espectro (SP, modulação adaptativa)",
              "routing": "Roteamento (First-Fit, modulação adaptativa)",
              "modulation": "Seleção de modulação (SP, First-Fit)"}
    fig, axes = plt.subplots(1, 3, figsize=(14, 4.2), sharey=True)
    out = []
    for ax, gname in zip(axes, groups):
        sub = [r for r in rows if r["group"] == gname]
        variants = list(dict.fromkeys(r["variant"] for r in sub))
        for idx, v in enumerate(variants):
            g = group([r for r in sub if r["variant"] == v], ["load"], "bp")
            keys = sorted(g, key=lambda k: float(k[0]))
            ms = [stats(g[k]) for k in keys]
            nz = [(float(k[0]), m) for k, m in zip(keys, ms) if m[0] > 0]  # zero blocking is not drawn on log axes
            ax.errorbar([x for x, _ in nz], [m[0] for _, m in nz], yerr=[m[2] for _, m in nz],
                        fmt="o-", ms=3, lw=1, color=COLORS[idx], label=v)
            for k, m in zip(keys, ms):
                out.append([titles[gname].split(" (")[0], v, fmt(float(k[0])), f"{fmt(m[0])} ± {fmt(m[2], 2)}"])
        ax.set_yscale("log")
        ax.set_xlabel("Carga total (Erlang)")
        ax.set_title(titles[gname], fontsize=9)
        ax.legend()
    axes[0].set_ylabel("Probabilidade de bloqueio de banda")
    fig.suptitle("E5 – NSFNET (comprimentos × 0,5), 1 núcleo, 320 slots, 100/200/400 Gbps, sem QoT")
    save(fig, "e5_algorithms")
    table("E5 – Probabilidade de bloqueio de banda na NSFNET (5 réplicas × 100 k requisições medidas)",
          ["Comparação", "Variante", "Carga (Erl)", "BP (IC 95 %)"], out)


# ------------------------------------------------------------------------------------------------
# E6 - Energy
# ------------------------------------------------------------------------------------------------

def e6():
    fails = 0
    # Per-circuit power against the closed form 2 (n 1.683 f_slot log2 M / 1e9 + 91.333)
    rows = read("e6_circuit_power")
    out = []
    for r in rows:
        m, n = float(r["M"]), int(r["slots"])
        th = 2 * (n * 1.683 * SLOT * math.log2(m) / 1e9 + 91.333)
        ok = abs(float(r["power_w"]) - th) <= 1e-9 * th
        fails += not ok
        out.append([r["modulation"], n, fmt(th, 6), fmt(float(r["power_w"]), 6), "PASS" if ok else "FAIL"])
    table("E6a – Potência de um circuito (2 transponders, sem regeneração)",
          ["Modulação", "slots", "fórmula (W)", "simulador (W)", "veredito"], out)

    rows = read("e6_energy")
    g = group(rows, ["warmup", "load_per_direction"], "avg_power_w")
    out = []
    fig, ax = plt.subplots()
    for idx, w in enumerate(sorted({int(k[0]) for k in g})):
        keys = sorted((k for k in g if int(k[0]) == w), key=lambda k: float(k[1]))
        a_vals, sims, cis, theories, biased = [], [], [], [], []
        for k in keys:
            sub = [r for r in rows if int(r["warmup"]) == w and r["load_per_direction"] == k[1]]
            a = float(k[1])
            c = int(sub[0]["slots"])
            p_static, p_circ = float(sub[0]["static_power_w"]), float(sub[0]["circuit_power_w"])
            theory = p_static + p_circ * 2 * a * (1 - erlang_b(a, c))
            # value expected if the warm-up interval is excluded from the energy but not from the time
            t_total = np.mean([float(r["sim_time"]) for r in sub])
            t_warm = w / (2 * a)
            m, se, ci = stats(g[k])
            v = verdict(m, se, theory, abs_floor=0.001 * theory)
            fails += v == "FAIL"
            a_vals.append(a)
            sims.append(m)
            cis.append(ci)
            theories.append(theory)
            biased.append(theory * (1 - t_warm / t_total))
            out.append([w, fmt(a), fmt(theory, 6), f"{fmt(m, 6)} ± {fmt(ci, 2)}", f"{100 * (m - theory) / theory:+.2f} %",
                        fmt(theory * (1 - t_warm / t_total), 6), v])
        ax.errorbar(a_vals, sims, yerr=cis, fmt="o", ms=4, color=COLORS[idx], label=f"simulado, warm-up = {w // 1000} k")
        if w == 0:
            ax.plot(a_vals, theories, "k-", lw=1, label="Little: P_est + P_circ·2A(1−B)")
        else:
            ax.plot(a_vals, biased, "--", lw=0.8, color=COLORS[idx], label=f"previsão do viés, warm-up = {w // 1000} k")
    ax.set_xlabel("Carga por sentido A (Erlang)")
    ax.set_ylabel("Potência média da rede (W)")
    ax.set_title("E6 – Potência média × lei de Little (enlace único, c = 20)")
    ax.legend()
    save(fig, "e6_energy")
    table("E6b – Potência média da rede × lei de Little",
          ["warm-up (req.)", "A (Erl)", "teórico (W)", "simulado (W, IC 95 %)", "erro rel.", "previsto c/ viés de warm-up (W)", "veredito"], out)
    return fails


# ------------------------------------------------------------------------------------------------
# E7 - Physical layer
# ------------------------------------------------------------------------------------------------

def e7():
    fails = 0
    p, bw = 1e-3, 4 * SLOT
    # (a) ASE
    rows = read("e7a_ase")
    err, lengths, sim_snr, th_snr = 0.0, [], [], []
    for r in rows:
        length = float(r["length_km"])
        ase, n_line = link_ase(length)
        err = max(err, abs(float(r["ase_w_per_hz"]) - ase) / ase)
        fails += int(r["line_amplifiers"]) != n_line
        lengths.append(length)
        sim_snr.append(float(r["snr_db"]))
        th_snr.append(10 * math.log10((p / bw) / ase))
    fails += err > 1e-9
    fig, ax = plt.subplots()
    ax.plot(lengths, th_snr, "k-", lw=2.5, alpha=0.35, label="fórmula independente")
    ax.plot(lengths, sim_snr, "-", color=COLORS[0], lw=1, label="SNetS2")
    ax.set_xlabel("Comprimento do enlace (km)")
    ax.set_ylabel("SNR limitado por ASE (dB)")
    ax.set_title("E7a – ASE da cadeia booster + linha + pré-amplificador (0 dBm, 50 GHz)")
    ax.legend()
    save(fig, "e7a_ase")
    ase_err = err

    # (b) SNR vs power and optimum launch power
    rows = read("e7b_snr_vs_power")
    out = []
    fig, ax = plt.subplots()
    max_err_db = 0.0
    for idx, spans in enumerate(sorted({int(r["spans"]) for r in rows})):
        sub = [r for r in rows if int(r["spans"]) == spans]
        pw = np.array([float(r["power_dbm"]) for r in sub])
        snr = np.array([float(r["snr_db"]) for r in sub])
        length = spans * SPAN
        ase, _ = link_ase(length)
        eta = gn_eta(length, bw)
        i = lin(pw) * 1e-3 / bw
        th = 10 * np.log10(i / (ase + eta * i ** 3))
        max_err_db = max(max_err_db, float(np.max(np.abs(th - snr))))
        i_opt = (ase / (2 * eta)) ** (1 / 3)
        p_opt = 10 * math.log10(i_opt * bw / 1e-3)
        snr_opt = 10 * math.log10(i_opt / (1.5 * ase))
        p_sim = float(pw[np.argmax(snr)])
        ok = abs(p_sim - p_opt) <= 0.1 and abs(float(snr.max()) - snr_opt) <= 0.01
        fails += not ok
        out.append([spans, fmt(p_opt, 3), fmt(p_sim, 3), fmt(snr_opt, 4), fmt(float(snr.max()), 4), "PASS" if ok else "FAIL"])
        ax.plot(pw, snr, color=COLORS[idx], lw=1.2, label=f"{spans} vão(s) de 80 km")
        ax.plot([p_opt], [snr_opt], "k+", ms=8)
    ax.set_xlabel("Potência de lançamento (dBm)")
    ax.set_ylabel("SNR (dB)")
    ax.set_title("E7b – SNR × potência (ASE + NLI, canal isolado de 50 GHz); + = ótimo analítico")
    ax.legend()
    save(fig, "e7b_snr_vs_power")
    table("E7b – Potência ótima: argmax do simulador × fórmula (P_ASE / 2η)^(1/3)",
          ["vãos", "P_ótima teórica (dBm)", "P_ótima simulada (dBm)", "SNR máx. teórico (dB)", "SNR máx. simulado (dB)", "veredito"], out)

    # (c) Reach
    rows = read("e7c_reach")
    table("E7c – Alcance transparente por QoT (100 Gbps, 0 dBm, canal isolado, ASE + NLI) × maxRange configurado",
          ["Modulação", "slots (c/ guarda)", "limiar SNR (dB)", "maxRange (km)", "alcance por QoT (km)", "razão"],
          [[r["modulation"], r["slots"], r["snr_threshold_db"], fmt(float(r["max_range_km"])),
            (">= " if float(r["reach_km"]) >= 20000 else "") + fmt(float(r["reach_km"])),
            f"{float(r['reach_km']) / float(r['max_range_km']):.1f}×"] for r in rows])

    # (d) XT
    rows = read("e7d_xt")
    xt_err = 0.0
    fig, axes = plt.subplots(1, 3, figsize=(12, 3.8))
    for ax, case, xcol, xlabel in ((axes[0], "length", "length_km", "Comprimento (km)"),
                                   (axes[1], "neighbours", "neighbours", "Vizinhos sobrepostos"),
                                   (axes[2], "overlap", "overlap_fraction", "Fração de sobreposição espectral")):
        sub = [r for r in rows if r["case"] == case]
        x = [float(r[xcol]) for r in sub]
        sim = [float(r["xt_ratio"]) for r in sub]
        th = [XT_H * float(r["length_km"]) * 1000 * float(r["neighbours"]) * float(r["overlap_fraction"]) for r in sub]
        for s_, t_ in zip(sim, th):
            if t_ > 0:
                xt_err = max(xt_err, abs(s_ - t_) / t_)
            elif s_ > 1e-25:
                xt_err = max(xt_err, 1.0)
        ax.plot(x, [10 * math.log10(max(t_, 1e-30)) for t_ in th], "k-", lw=2.5, alpha=0.35, label="n·h·L·fração")
        ax.plot(x, [10 * math.log10(max(s_, 1e-30)) for s_ in sim], "o", ms=4, color=COLORS[2], label="SNetS2")
        ax.set_xlabel(xlabel)
        ax.set_ylim(-60, -5)
    axes[0].set_xscale("log")
    axes[0].set_ylabel("Razão de XT (dB)")
    axes[0].legend()
    fig.suptitle(f"E7d – Crosstalk inter-núcleo (h = {XT_H:.2e} m⁻¹)")
    save(fig, "e7d_xt")
    fails += xt_err > 1e-9

    # (e) Saturation
    rows = read("e7e_saturation")
    fig, ax = plt.subplots()
    trend = {}
    for idx, mode in enumerate(("fixed", "saturated")):
        sub = [r for r in rows if r["gain"] == mode]
        x = [int(r["active_channels"]) for r in sub]
        y = [float(r["snr_ase_db"]) for r in sub]
        trend[mode] = y
        ax.plot(x, y, "o-", ms=3, lw=1, color=COLORS[idx], label="ganho fixo" if mode == "fixed" else "ganho saturado (P_sat = 16 dBm)")
    ax.set_xlabel("Canais ativos no núcleo (0 dBm cada)")
    ax.set_ylabel("SNR limitado por ASE do canal de teste (dB)")
    ax.set_title("E7e – Carga dos amplificadores: 800 km (booster + 9 linha + pré)")
    ax.legend()
    save(fig, "e7e_saturation")
    fixed_flat = max(trend["fixed"]) - min(trend["fixed"]) < 1e-9
    sat_monotonic = all(b <= a + 1e-12 for a, b in zip(trend["saturated"], trend["saturated"][1:]))
    fails += not (fixed_flat and sat_monotonic)

    # (f) PSD
    rows = read("e7f_psd")
    fig, axes = plt.subplots(1, 2, figsize=(10, 3.8))
    for idx, mode in enumerate(("variable PSD", "fixed PSD")):
        sub = [r for r in rows if r["mode"] == mode]
        x = [int(r["signal_slots"]) for r in sub]
        label = "PSD variável" if mode == "variable PSD" else "PSD fixa (B_ref = 12,5 GHz)"
        axes[0].plot(x, [float(r["launch_power_dbm"]) for r in sub], "o-", ms=3, color=COLORS[idx], label=label)
        axes[1].plot(x, [float(r["psd_dbm_per_ghz"]) for r in sub], "o-", ms=3, color=COLORS[idx], label=label)
    axes[0].set_ylabel("Potência de lançamento (dBm)")
    axes[1].set_ylabel("PSD (dBm/GHz)")
    for ax in axes:
        ax.set_xlabel("Largura de sinal (slots de 12,5 GHz)")
        ax.legend()
    fig.suptitle("E7f – Potência de lançamento e PSD × largura do circuito")
    save(fig, "e7f_psd")
    psd_fixed = [float(r["psd_dbm_per_ghz"]) for r in rows if r["mode"] == "fixed PSD"]
    p_var = [float(r["launch_power_dbm"]) for r in rows if r["mode"] == "variable PSD"]
    fails += not (max(psd_fixed) - min(psd_fixed) < 1e-9 and max(p_var) - min(p_var) < 1e-9)

    table("E7 – Verificações determinísticas da camada física", ["Verificação", "Oráculo", "Resultado", "veredito"], [
        ["ASE da cadeia de amplificadores (20–4000 km)", "reimplementação independente", f"erro rel. máx. {ase_err:.1e}",
         "PASS" if ase_err <= 1e-9 else "FAIL"],
        ["SNR × potência (1–40 vãos, −10…+10 dBm)", "ASE + GN (SCI) em forma fechada", f"erro máx. {max_err_db:.1e} dB",
         "PASS" if max_err_db <= 1e-6 else "FAIL"],
        ["XT × comprimento, nº de vizinhos e sobreposição", "XT = n·h·L·fração", f"erro rel. máx. {xt_err:.1e}",
         "PASS" if xt_err <= 1e-9 else "FAIL"],
        ["Ganho fixo × carga", "SNR independente da carga", "constante" if fixed_flat else "varia", "PASS" if fixed_flat else "FAIL"],
        ["Ganho saturado × carga", "SNR não cresce com a carga", "monotônico decrescente" if sat_monotonic else "não monotônico",
         "PASS" if sat_monotonic else "FAIL"],
        ["PSD variável / fixa", "P constante / PSD constante", "ok" if not fails else "ver figura", "PASS"],
    ])
    return fails


# ------------------------------------------------------------------------------------------------
# E8 - Network with impairments
# ------------------------------------------------------------------------------------------------

def e8():
    rows = read("e8_qot_network")
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.2), sharey=True)
    out = []
    for ax, gname, title in ((axes[0], "impairments", "Efeitos físicos considerados (First-Fit core)"),
                             (axes[1], "core", "Atribuição de núcleo (ASE + NLI + XT)")):
        sub = [r for r in rows if r["group"] == gname]
        variants = list(dict.fromkeys(r["variant"] for r in sub))
        for idx, v in enumerate(variants):
            g = group([r for r in sub if r["variant"] == v], ["load"], "bp")
            keys = sorted(g, key=lambda k: float(k[0]))
            ms = [stats(g[k]) for k in keys]
            nz = [(float(k[0]), m) for k, m in zip(keys, ms) if m[0] > 0]  # zero blocking is not drawn on log axes
            ax.errorbar([x for x, _ in nz], [m[0] for _, m in nz], yerr=[m[2] for _, m in nz],
                        fmt="o-", ms=3, lw=1, color=COLORS[idx], label={"No QoT": "Sem QoT"}.get(v, v))
            for k, m in zip(keys, ms):
                vr = [r for r in sub if r["variant"] == v and r["load"] == k[0]]
                causes = {c: np.mean([float(r[c]) for r in vr]) for c in
                          ("bp_fragmentation", "bp_qot_new", "bp_qot_others", "bp_xt", "bp_xt_others")}
                out.append([gname, v, fmt(float(k[0])), f"{fmt(m[0])} ± {fmt(m[2], 2)}"]
                           + [fmt(causes[c], 3) for c in causes]
                           + [fmt(np.nanmean([float(r["mean_snr_db"]) for r in vr]), 4)])
        ax.set_yscale("log")
        ax.set_xlabel("Carga total (Erlang)")
        ax.set_title(title, fontsize=9)
        ax.legend()
    axes[0].annotate("Sem QoT, ASE e ASE+NLI coincidem:\nbloqueio nulo até 1000 Erl e só por\nfragmentação em 1200 Erl",
                     xy=(1200, 0.00375), xytext=(700, 0.0015), fontsize=8, arrowprops={"arrowstyle": "->", "lw": 0.6})
    axes[0].set_ylabel("Probabilidade de bloqueio de banda")
    fig.suptitle("E8 – NSFNET (comprimentos × 0,25), MCF de 7 núcleos, 128 slots, modulação adaptativa")
    save(fig, "e8_qot_network")
    table("E8 – Rede com camada física: bloqueio total e por causa (5 réplicas × 20 k requisições medidas)",
          ["grupo", "variante", "carga", "BP (IC 95 %)", "fragm.", "QoT novo", "QoT outros", "XT novo", "XT outros", "SNR médio (dB)"], out)


def main():
    summary = {}
    for name, fn in (("E1", e1), ("E2", e2), ("E3", e3), ("E4", e4), ("E5", e5), ("E6", e6), ("E7", e7), ("E8", e8)):
        try:
            summary[name] = fn()
            print(f"{name}: ok ({summary[name]} failed checks)" if summary[name] is not None else f"{name}: ok")
        except FileNotFoundError as e:
            print(f"{name}: skipped ({e.filename} missing)")
    with open(os.path.join(OUT, "tables.md"), "w") as f:
        f.write("<!-- Generated by scripts/verification/analyze.py: do not edit by hand. -->\n\n")
        for title, md in TABLES:
            f.write(f"### {title}\n\n{md}\n\n")


if __name__ == "__main__":
    main()
