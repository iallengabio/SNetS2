#!/usr/bin/env python3
"""L12-a: cross-validation of SNetS2 against SNetS v1.

Both simulators run the same scenarios with aligned premises: no physical layer (QoT, ASE, NLI and XT
off), routing by distance with no ties (checked in generate, so tie-breaking does not matter), the same
slot grid, FEC overhead, polarizations and bit rates, the same total load split uniformly over the
ordered pairs, 100 k requests per replication with the first 10 % discarded, and 10 replications per load.

Families (see docs/review/04_relatorio_verificacao_validacao.md, E9):
    ff    1 core, 128 slots, shortest path, First-Fit, guard band 0, 5 formats (distance-adaptive)
    ffgb  same as ff with guard band 1 (known modelling difference: v1 shares one guard band
          between neighbouring circuits and none at the grid edges; SNetS2 adds it to every demand)
    abne  MCF of 7 cores, 320 slots, ABNE (v1 CSBASDM), guard band 0, a single format (16QAM)
    cpcas MCF of 7 cores, 320 slots, CPCAS, guard band 0, a single format (16QAM)
    ksp   1 core, 128 slots, k = 3 shortest paths, First-Fit, guard band 0, a single format (16QAM)

Topologies: t3, t6, t9 (simple, E9a) for ff/ffgb/abne/cpcas; nsfnet and usa (from the v1 artifact,
with a deterministic perturbation of 0-9.6 km per link that removes ties, E9b) for ff and ksp.

The ABNE and CPCAS families use one format because SNetS2 falls back to the next format when the
first has no spectrum (a new core-and-spectrum call, which advances the ABNE round robin), while the
v1 sequential RMCSA calls the core-and-spectrum assignment once per request. The ksp family uses one
format because the v1 KSP algorithm (integrated `kspcsa`) ignores the reach of the formats and, without
QoT, always takes the most efficient one; the v1 sequential RMCSA, which honours the reach, has a
single route.

Usage:
    python3 scripts/verification/cross_v1.py generate
    python3 scripts/verification/cross_v1.py run-v1 <SNetS-v1 checkout, built with mvn package> [threads] [scenario ...]
    python3 scripts/verification/cross_v1.py run-snets2 [threads] [scenario ...]
    python3 scripts/verification/cross_v1.py analyze

Scenarios are written to experiments/verification/cross_v1/<scenario>/{v1/,snets2/setup.json}; the
comparison goes to docs/review/vv/data/e9_cross_v1.csv, docs/review/vv/figures/e9_cross_v1.png (E9a),
docs/review/vv/figures/e9b_cross_v1_networks.png (E9b) and docs/review/vv/e9_cross_v1.md.

Requires: numpy, matplotlib, openpyxl (see scripts/verification/requirements.txt).
"""
import csv
import glob
import heapq
import json
import math
import os
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
EXP = os.path.join(ROOT, "experiments", "verification", "cross_v1")
DATA = os.path.join(ROOT, "docs", "review", "vv", "data")
FIG = os.path.join(ROOT, "docs", "review", "vv", "figures")
TABLE = os.path.join(ROOT, "docs", "review", "vv", "e9_cross_v1.md")

REQUESTS = 100_000
REPLICATIONS = 10
LOAD_POINTS = 5
BIT_RATES = [(100.0, 1.0), (200.0, 1.0), (400.0, 1.0)]  # Gbps, weight
RATE_OF_FEC = 0.25
POLARIZATIONS = 2.0
SLOT_HZ = 12.5e9

# Undirected edges (u, v, length in km); both directions become links. Lengths are chosen so that every
# pair has a unique shortest path (checked in generate) and the formats from 32QAM to 4QAM are all used.
TOPOLOGIES = {
    "t3": (3, [(0, 1, 380), (1, 2, 620)]),
    "t6": (6, [(0, 1, 350), (1, 2, 710), (2, 3, 470), (3, 4, 930), (4, 5, 290), (5, 0, 640), (1, 4, 1170)]),
    "t9": (9, [(0, 1, 360), (1, 2, 530), (3, 4, 610), (4, 5, 440), (6, 7, 270), (7, 8, 780),
               (0, 3, 490), (3, 6, 330), (1, 4, 660), (4, 7, 580), (2, 5, 390), (5, 8, 830)]),
}

# NSFNET and USA of the v1 artifact (simulations/*_sims/*/network, nodes renumbered from 0). Their round
# lengths give many pairs with equally long paths, so each edge j (1-based) gets ((37 j) mod 97) / 10 km.
NSFNET = [(0, 1, 1050), (0, 2, 1500), (0, 7, 1800), (1, 2, 600), (1, 3, 600), (2, 5, 1500), (3, 4, 600),
          (3, 10, 1500), (4, 5, 1300), (4, 6, 600), (5, 9, 1050), (5, 13, 1500), (6, 7, 750), (6, 9, 1350),
          (7, 8, 750), (8, 9, 800), (8, 11, 300), (8, 12, 300), (10, 11, 500), (10, 12, 750), (11, 13, 800),
          (12, 13, 300)]
USA = [(0, 1, 400), (0, 2, 500), (1, 2, 475), (1, 3, 550), (2, 4, 500), (2, 8, 600), (2, 10, 950), (3, 4, 500),
       (3, 5, 125), (3, 6, 500), (4, 5, 425), (4, 7, 550), (4, 8, 500), (5, 6, 400), (6, 7, 600), (7, 9, 450),
       (8, 9, 500), (8, 10, 700), (8, 11, 500), (9, 12, 475), (9, 13, 400), (10, 11, 450), (10, 14, 1300),
       (10, 15, 650), (11, 12, 450), (11, 17, 500), (12, 13, 325), (12, 20, 550), (13, 22, 600), (14, 16, 600),
       (15, 16, 650), (15, 17, 300), (16, 18, 350), (17, 18, 500), (17, 19, 400), (17, 20, 500), (18, 19, 150),
       (19, 20, 425), (19, 21, 300), (20, 21, 500), (20, 22, 400), (21, 23, 450), (22, 23, 450)]


def perturbed(edges):
    return [(u, v, d + ((37 * j) % 97) / 10) for j, (u, v, d) in enumerate(edges, 1)]


TOPOLOGIES["nsfnet"] = (14, perturbed(NSFNET))
TOPOLOGIES["usa"] = (24, perturbed(USA))
TOPOLOGY_NAMES = {"t3": "3 nós", "t6": "6 nós", "t9": "9 nós", "nsfnet": "NSFNET", "usa": "USA"}
K_PATHS = 3

MODULATIONS_ALL = [  # name, maxRange (km), M; thresholds are unused without QoT
    ("4QAM", 5110.0, 4.0, 5.92, -16.0), ("8QAM", 3270.0, 8.0, 9.32, -19.4),
    ("16QAM", 2000.0, 16.0, 12.34, -22.42), ("32QAM", 400.0, 32.0, 15.22, -25.3),
    ("64QAM", 160.0, 64.0, 18.02, -28.1),
]
MODULATIONS_16QAM = [("16QAM", 100000.0, 16.0, 12.34, -22.42)]

MCF7 = [[1, 2, 3, 4, 5, 6], [0, 2, 6], [0, 1, 3], [0, 2, 4], [0, 3, 5], [0, 4, 6], [0, 1, 5]]

# family: cores, slots, guard band, formats, v1 core-and-spectrum id, SNetS2 (coreAndSpectrum, spectrum),
# k shortest paths (None = shortest path: v1 sequential RMCSA with djk; else v1 integrated kspcsa)
FAMILIES = {
    "ff": (1, 128, 0, MODULATIONS_ALL, "randomcorefirstfit", ("firstfitcore", "firstfit"), None),
    "ffgb": (1, 128, 1, MODULATIONS_ALL, "randomcorefirstfit", ("firstfitcore", "firstfit"), None),
    "abne": (7, 320, 0, MODULATIONS_16QAM, "csbasdm", ("abne", None), None),
    "cpcas": (7, 320, 0, MODULATIONS_16QAM, "cpcas", ("cpcas", None), None),
    "ksp": (1, 128, 0, MODULATIONS_16QAM, "randomcorefirstfit", ("firstfitcore", "firstfit"), K_PATHS),
}

# First load and step (Erlang), chosen so that the bit-rate blocking goes from ~0.5 % to ~15 %
LOADS = {
    ("ff", "t3"): (60, 10), ("ff", "t6"): (120, 20), ("ff", "t9"): (160, 25),
    ("ffgb", "t3"): (40, 10), ("ffgb", "t6"): (80, 20), ("ffgb", "t9"): (115, 25),
    ("abne", "t3"): (1350, 150), ("abne", "t6"): (2800, 350), ("abne", "t9"): (3800, 500),
    ("cpcas", "t3"): (1350, 150), ("cpcas", "t6"): (2800, 350), ("cpcas", "t9"): (3800, 500),
    ("ff", "nsfnet"): (130, 25), ("ff", "usa"): (140, 35),
    ("ksp", "nsfnet"): (220, 40), ("ksp", "usa"): (220, 40),
}
SCENARIOS = list(LOADS)  # E9a: the 12 on t3/t6/t9; E9b: the 4 on nsfnet/usa
E9A_TOPOLOGIES = ["t3", "t6", "t9"]

PHYSICAL = {
    "rateOfFEC": RATE_OF_FEC, "power": 0.0, "spanLength": 80.0, "fiberLoss": 0.2, "fiberNonlinearity": 0.0013,
    "fiberDispersion": 1.6E-5, "centerFrequency": 1.9385E14, "constantOfPlanck": 6.626E-34,
    "noiseFigureOfOpticalAmplifier": 5.0, "powerSaturationOfOpticalAmplifier": 23.0,
    "noiseFactorModelParameterA1": 100.0, "noiseFactorModelParameterA2": 4.0, "typeOfAmplifierGain": 0,
    "amplificationFrequency": 1.9385E14, "switchInsertionLoss": 5.0, "fixedPowerSpectralDensity": False,
    "referenceBandwidthForPowerSpectralDensity": 1.25E10, "propagationConstant": 1.0E7, "bendingRadius": 0.01,
    "couplingCoefficient": 0.012, "corePitch": 4.5E-5, "polarizationModes": POLARIZATIONS,
}
PHY_OFF = {"activeQoT": False, "activeQoTForOther": False, "activeASE": False, "activeNLI": False,
           "activeXT": False, "activeXTForOther": False}


def scenarios(only=None):
    for fam, topo in SCENARIOS:
        name = f"{fam}_{topo}"
        if not only or name in only:
            yield fam, topo, name


def loads(fam, topo):
    first, step = LOADS[(fam, topo)]
    return [float(first + i * step) for i in range(LOAD_POINTS)]


def directed_links(topo):
    n, edges = TOPOLOGIES[topo]
    return n, [(u, v, d) for u, v, d in edges] + [(v, u, d) for u, v, d in edges]


def check_unique_shortest_paths(topo):
    """Fails if any pair has two shortest paths, so that both Dijkstra implementations agree."""
    n, links = directed_links(topo)
    adj = {i: [] for i in range(n)}
    for u, v, d in links:
        adj[u].append((v, d))
    longest = 0.0
    for s in range(n):
        dist, count = {s: 0.0}, {s: 1}
        heap = [(0.0, s)]
        done = set()
        while heap:
            du, u = heapq.heappop(heap)
            if u in done:
                continue
            done.add(u)
            for v, d in adj[u]:
                nd = du + d
                if v not in dist or nd < dist[v] - 1e-9:
                    dist[v], count[v] = nd, count[u]
                    heapq.heappush(heap, (nd, v))
                elif abs(nd - dist[v]) <= 1e-9:
                    count[v] += count[u]
        for t in range(n):
            if t != s and count.get(t, 0) != 1:
                raise SystemExit(f"{topo}: {count.get(t, 0)} shortest paths between {s} and {t}")
        longest = max(longest, max(dist.values()))
    return longest


def check_k_shortest_paths(topo, k):
    """Fails if, for some pair, two of the k + 1 shortest simple paths have the same length (both KSP
    implementations then return the same k paths in the same order)."""
    n, links = directed_links(topo)
    adj = {i: [] for i in range(n)}
    for u, v, d in links:
        adj[u].append((v, d))
    min_gap = float("inf")
    for s in range(n):
        for t in range(n):
            if s == t:
                continue
            found, heap = [], [(0.0, (s,))]
            while heap and len(found) < k + 1:
                d, path = heapq.heappop(heap)
                if path[-1] == t:
                    found.append(d)
                    continue
                for v, w in adj[path[-1]]:
                    if v not in path:
                        heapq.heappush(heap, (d + w, path + (v,)))
            gaps = [b - a for a, b in zip(found, found[1:])]
            if gaps and min(gaps) < 0.05:
                raise SystemExit(f"{topo}: paths of equal length between {s} and {t}")
            min_gap = min([min_gap] + gaps)
    return min_gap


# ------------------------------------------------------------------------------------------------
# Scenario generation
# ------------------------------------------------------------------------------------------------

def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def v1_scenario(fam, topo, name):
    """SNetS v1 files. Node k of SNetS2 is node k + 1 here: v1 DJK assumes node names 1..N."""
    cores, slots, gb, mods, v1_csa, _, k = FAMILIES[fam]
    n, links = directed_links(topo)
    base = os.path.join(EXP, name, "v1")
    write_json(os.path.join(base, "network"), {
        "nodes": [{"name": str(i + 1), "transmitters": 100000, "receivers": 100000, "regenerators": 0}
                  for i in range(n)],
        "links": [{"source": str(u + 1), "destination": str(v + 1), "slots": slots, "spectrum": SLOT_HZ,
                   "size": float(d)}
                  for u, v, d in links],
        "modulations": [{"name": m, "maxRange": r, "M": M, "SNR": snr, "XT": xt} for m, r, M, snr, xt in mods],
        "cores": [{"id": c, "adjacentCores": MCF7[c] if cores == 7 else []} for c in range(cores)],
        "guardBand": gb,
        "bvtSpectralAmplitude": 1000,
    })
    write_json(os.path.join(base, "physicalLayer"), {
        "physicalLayerModel": 0, "crosstalkModel": 0, **PHY_OFF, "typeOfTestQoT": 0, **PHYSICAL})
    metrics = {k: False for k in (
        "SpectrumSizeStatistics", "ExternalFragmentation", "RelativeFragmentation",
        "TransmittersReceiversRegeneratorsUtilization", "ModulationUtilization", "ConsumedEnergy",
        "GroomingStatistics", "DataSetInformation", "PhysicalLayerStatistics", "CrosstalkStatistics")}
    metrics.update({"BlockingProbability": True, "BitRateBlockingProbability": True, "SpectrumUtilization": True})
    write_json(os.path.join(base, "simulation"), {
        "requests": REQUESTS, "rmlsaType": 0 if k is None else 1, "routing": "djk", "kRouting": "newksp",
        "spectrumAssignment": "firstfit", "coreAndSpectrumAssignment": v1_csa, "integratedRmlsa": "kspcsa",
        "modulationSelection": "modulationbydistance", "grooming": "notrafficgrooming",
        "reallocation": "fsalfav1", "powerAssignment": "cpa", "loadPoints": LOAD_POINTS,
        "replications": REPLICATIONS, "activeMetrics": metrics, "regeneratorAssignment": "aar",
        "networkType": 0, "threads": 1})
    # Poisson superposition: the total load split uniformly over the ordered pairs and by bit-rate weight
    first, step = LOADS[(fam, topo)]
    pairs = n * (n - 1)
    wsum = sum(w for _, w in BIT_RATES)
    gens = []
    for s in range(n):
        for t in range(n):
            if s == t:
                continue
            for rate, w in BIT_RATES:
                share = w / wsum / pairs
                gens.append({"source": str(s + 1), "destination": str(t + 1), "bitRate": rate * 1e9,
                             "arrivalRate": first * share, "holdRate": 1.0, "arrivalRateIncrease": step * share})
    write_json(os.path.join(base, "traffic"), {"requestGenerators": gens})
    write_json(os.path.join(base, "others"), {"variables": {} if k is None else {"k": str(k)}, "kbpWeights": {}})


def snets2_scenario(fam, topo, name):
    cores, slots, gb, mods, _, (csa, sa), k = FAMILIES[fam]
    n, links = directed_links(topo)
    sim = {
        "requests": REQUESTS, "warmUpRequests": REQUESTS // 10, "totalSlots": slots,
        "routing": "djk" if k is None else "ksp",
        "coreAndSpectrumAssignment": csa, "integratedRMSCA": "standard", "modulationSelection": "distance-adaptive",
        "activeMetrics": {"BlockingProbability": True, "BitRateBlockingProbability": True, "SpectrumUtilization": True,
                          "SpectrumSizeStatistics": False, "ExternalFragmentation": False,
                          "RelativeFragmentation": False, "TransmittersReceiversRegeneratorsUtilization": False,
                          "ModulationUtilization": False, "ConsumedEnergy": False, "CrosstalkStatistics": False,
                          "SimulationMetadata": False},
    }
    if sa:
        sim["spectrumAssignment"] = sa
    if k is not None:
        sim["algorithmParameters"] = {"k": k}
    write_json(os.path.join(EXP, name, "snets2", "setup.json"), {
        "networkTopology": {
            "nodes": [{"id": str(i), "tx": 100000, "rx": 100000, "regenerators": 0, "addDropDegree": 1}
                      for i in range(n)],
            "links": [{"source": str(u), "destination": str(v), "length": float(d)} for u, v, d in links],
            "cores": [{"id": c, "adjacentCores": MCF7[c] if cores == 7 else []} for c in range(cores)],
            "modulations": [{"name": m, "maxRange": r, "M": M, "SNR": snr, "XT": xt} for m, r, M, snr, xt in mods],
        },
        "physicalLayer": {**PHY_OFF, **PHYSICAL, "guardBand": gb, "bvtSpectralWidth": SLOT_HZ},
        "simulation": sim,
        "traffic": {"loadDistributionPerPair": "uniform", "load": loads(fam, topo)[0],
                    "bitRates": [{"value": r, "weight": w} for r, w in BIT_RATES]},
        "experimentalPlanning": {"replications": REPLICATIONS, "traffic.load": loads(fam, topo)},
    })


def generate():
    for topo in TOPOLOGIES:
        longest = check_unique_shortest_paths(topo)
        print(f"{topo}: unique shortest paths, longest {longest:.0f} km")
    for topo in sorted({t for f, t in SCENARIOS if FAMILIES[f][6]}):
        gap = check_k_shortest_paths(topo, K_PATHS)
        print(f"{topo}: no ties among the {K_PATHS + 1} shortest paths of any pair (smallest gap {gap:.1f} km)")
    for fam, topo, name in scenarios():
        v1_scenario(fam, topo, name)
        snets2_scenario(fam, topo, name)
    print(f"scenarios written to {os.path.relpath(EXP, ROOT)}")


# ------------------------------------------------------------------------------------------------
# Execution
# ------------------------------------------------------------------------------------------------

def run_v1(v1_dir, threads, only=None):
    cp_file = os.path.join(v1_dir, "cp.txt")
    if not os.path.exists(cp_file):
        subprocess.run(["mvn", "-q", "dependency:build-classpath", f"-Dmdep.outputFile={cp_file}"],
                       cwd=v1_dir, check=True)
    classpath = os.path.join(v1_dir, "target", "classes") + os.pathsep + open(cp_file).read().strip()
    for fam, topo, name in scenarios(only):
        d = os.path.join(EXP, name, "v1")
        sim_path = os.path.join(d, "simulation")
        sim = json.load(open(sim_path))
        sim["threads"] = threads
        write_json(sim_path, sim)
        print(f"v1 {name}", flush=True)
        subprocess.run(["java", "-cp", classpath, "simulationControl.Main", d], check=True,
                       stdout=subprocess.DEVNULL)


def run_snets2(threads, only=None):
    jar = os.path.join(ROOT, "target", "SNetS2-1.0-SNAPSHOT.jar")
    if not os.path.exists(jar):
        subprocess.run([os.path.join(ROOT, "build.sh")], cwd=ROOT, check=True)
    for fam, topo, name in scenarios(only):
        d = os.path.join(EXP, name, "snets2")
        print(f"SNetS2 {name}", flush=True)
        subprocess.run(["java", "-jar", jar, d, str(threads)], check=True, stdout=subprocess.DEVNULL)


# ------------------------------------------------------------------------------------------------
# Result parsing
# ------------------------------------------------------------------------------------------------

def v1_values(name, metric, first_col, bitrate="all"):
    """{loadPoint: [rep values]} for the rows of a v1 CSV whose first column is first_col."""
    path = glob.glob(os.path.join(EXP, name, "v1", f"*_{metric}.csv"))
    if not path:
        raise SystemExit(f"missing v1 result {metric} for {name}")
    out = {}
    with open(path[0]) as f:
        rows = list(csv.reader(f))
    header = rows[0]
    reps = [i for i, h in enumerate(header) if h.startswith("rep")]
    for row in rows[1:]:
        if not row or row[0] != first_col:
            continue
        if "BitRate" in header and row[header.index("BitRate")] != bitrate:
            continue
        if "src" in header and row[header.index("src")] != "all":
            continue
        if "Link" in header and row[header.index("Link")] != "all":
            continue
        out[int(row[1])] = [float(row[i]) for i in reps if i < len(row) and row[i] != ""]
    return out


def snets2_values(name, sheet, submetric, match=None):
    """{load: [rep values]} for a sub-metric of the SNetS2 results.xlsx."""
    import openpyxl
    wb = openpyxl.load_workbook(os.path.join(EXP, name, "snets2", "results.xlsx"), read_only=True)
    rows = list(wb[sheet].iter_rows(values_only=True))
    header = list(rows[0])
    reps = [i for i, h in enumerate(header) if str(h).startswith("rep")]
    li = header.index("traffic.load")
    out = {}
    for row in rows[1:]:
        if row[0] != submetric:
            continue
        if match and any(str(row[header.index(k)]) != v for k, v in match.items()):
            continue
        out[float(row[li])] = [float(row[i]) for i in reps]
    return out


# ------------------------------------------------------------------------------------------------
# Analysis
# ------------------------------------------------------------------------------------------------

T975 = {9: 2.262, 18: 2.101}


def stats(values):
    import numpy as np
    v = np.asarray(values, dtype=float)
    se = float(v.std(ddof=1) / math.sqrt(len(v)))
    return float(v.mean()), se, T975.get(len(v) - 1, 1.96) * se


METRICS = [  # label, v1 (file, first column, bit rate), SNetS2 (sheet, sub-metric, match)
    ("bbp", ("BitRateBlockingProbability", "BitRate blocking probability", "all"),
     ("BlockingProbability", "General Bit Rate BP", {"src": "all", "dest": "all", "core": "all", "bitrate": "all"})),
    ("bbp_100", ("BitRateBlockingProbability", "BitRate blocking probability per bitRate", "100.0Gbps"),
     ("BlockingProbability", "BP per bit rate", {"bitrate": "100.0"})),
    ("bbp_200", ("BitRateBlockingProbability", "BitRate blocking probability per bitRate", "200.0Gbps"),
     ("BlockingProbability", "BP per bit rate", {"bitrate": "200.0"})),
    ("bbp_400", ("BitRateBlockingProbability", "BitRate blocking probability per bitRate", "400.0Gbps"),
     ("BlockingProbability", "BP per bit rate", {"bitrate": "400.0"})),
    ("util", ("SpectrumUtilization", "Utilization", "all"),
     ("SpectrumUtilization", "General Utilization", None)),
]


def analyze():
    rows = []
    for fam, topo, name in scenarios():
        ls = loads(fam, topo)
        for label, (vfile, vcol, vbr), (sheet, sub, match) in METRICS:
            a = v1_values(name, vfile, vcol, vbr)
            b = snets2_values(name, sheet, sub, match)
            for lp, load in enumerate(ls):
                m1, se1, h1 = stats(a[lp])
                m2, se2, h2 = stats(b[load])
                diff = (m2 - m1) / m1 if m1 > 0 else float("nan")
                z = (m2 - m1) / math.sqrt(se1 ** 2 + se2 ** 2) if se1 + se2 > 0 else 0.0
                rows.append({"family": fam, "topology": topo, "metric": label, "load": load,
                             "v1_mean": m1, "v1_ci95": h1, "snets2_mean": m2, "snets2_ci95": h2,
                             "rel_diff": diff, "z": z})
    os.makedirs(DATA, exist_ok=True)
    out = os.path.join(DATA, "e9_cross_v1.csv")
    with open(out, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    print(f"wrote {os.path.relpath(out, ROOT)}")
    plot(rows)
    table(rows)


def plot(rows):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    titles = {"ff": "1 núcleo, SP + FF, guarda 0", "ffgb": "1 núcleo, SP + FF, guarda 1",
              "abne": "MCF-7, ABNE", "cpcas": "MCF-7, CPCAS", "ksp": f"1 núcleo, {K_PATHS}SP + FF, 16QAM"}
    grids = [
        ("e9_cross_v1.png", [[(f, t) for t in E9A_TOPOLOGIES] for f in ("ff", "ffgb", "abne", "cpcas")]),
        ("e9b_cross_v1_networks.png", [[(f, t) for t in ("nsfnet", "usa")] for f in ("ff", "ksp")]),
    ]
    os.makedirs(FIG, exist_ok=True)
    for filename, grid in grids:
        fig, axes = plt.subplots(len(grid), len(grid[0]), figsize=(3.4 * len(grid[0]), 2.5 * len(grid)),
                                 squeeze=False)
        for i, line in enumerate(grid):
            for j, (fam, topo) in enumerate(line):
                ax = axes[i][j]
                sel = [r for r in rows if r["family"] == fam and r["topology"] == topo and r["metric"] == "bbp"]
                x = [r["load"] for r in sel]
                ax.errorbar(x, [r["v1_mean"] for r in sel], yerr=[r["v1_ci95"] for r in sel], fmt="o-",
                            color="#e0672b", ms=4, capsize=2, label="SNetS v1")
                ax.errorbar(x, [r["snets2_mean"] for r in sel], yerr=[r["snets2_ci95"] for r in sel], fmt="s--",
                            color="#2a6fdb", ms=4, capsize=2, label="SNetS2")
                ax.set_yscale("log")
                ax.set_title(f"{titles[fam]} · {TOPOLOGY_NAMES[topo]}", fontsize=9)
                if j == 0:
                    ax.set_ylabel("BP de taxa de bits")
                if i == len(grid) - 1:
                    ax.set_xlabel("Carga (Erl)")
                ax.grid(True, alpha=0.3)
                ax.spines["top"].set_visible(False)
                ax.spines["right"].set_visible(False)
        axes[0][0].legend(frameon=False, fontsize=8)
        fig.tight_layout()
        out = os.path.join(FIG, filename)
        fig.savefig(out, dpi=130)
        print(f"wrote {os.path.relpath(out, ROOT)}")


def fmt(x, spec=".4f"):
    return f"{x:{spec}}".replace(".", ",")


def table(rows):
    lines = ["| Família | Topologia | Carga (Erl) | BP v1 (± IC95) | BP SNetS2 (± IC95) | Diferença relativa | z |",
             "| :-- | :-: | :-: | :-: | :-: | :-: | :-: |"]
    for r in rows:
        if r["metric"] != "bbp":
            continue
        lines.append(f"| {r['family']} | {TOPOLOGY_NAMES[r['topology']]} | {r['load']:.0f} | {fmt(r['v1_mean'])} ± {fmt(r['v1_ci95'])} "
                     f"| {fmt(r['snets2_mean'])} ± {fmt(r['snets2_ci95'])} | {fmt(100 * r['rel_diff'], '+.1f')} % | {fmt(r['z'], '+.1f')} |")
    lines += ["", "| Família | Topologia | Carga (Erl) | Utilização v1 | Utilização SNetS2 | Diferença relativa |",
              "| :-- | :-: | :-: | :-: | :-: | :-: |"]
    for r in rows:
        if r["metric"] == "util":
            lines.append(f"| {r['family']} | {TOPOLOGY_NAMES[r['topology']]} | {r['load']:.0f} | {fmt(r['v1_mean'])} "
                         f"| {fmt(r['snets2_mean'])} | {fmt(100 * r['rel_diff'], '+.1f')} % |")
    with open(TABLE, "w") as f:
        f.write("# E9: SNetS2 × SNetS v1 (L12-a)\n\nGerado por `scripts/verification/cross_v1.py analyze`.\n\n")
        f.write("\n".join(lines) + "\n")
    print(f"wrote {os.path.relpath(TABLE, ROOT)}")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "generate":
        generate()
    elif cmd == "run-v1" and len(sys.argv) > 2:
        run_v1(os.path.abspath(sys.argv[2]), int(sys.argv[3]) if len(sys.argv) > 3 else os.cpu_count(),
               sys.argv[4:])
    elif cmd == "run-snets2":
        run_snets2(int(sys.argv[2]) if len(sys.argv) > 2 else os.cpu_count(), sys.argv[3:])
    elif cmd == "analyze":
        analyze()
    else:
        print(__doc__)
        sys.exit(1)
