#!/usr/bin/env python3
"""L12-d: physical layer of SNetS2 against GNPy on one link of N spans.

The SNetS2 side is `VerificationCampaign gnpy` (docs/review/vv/data/e10_gnpy_snets2.csv): SNR of a 50 GHz
channel on one link of N x 80 km (booster of 3 x 5 dB after the ROADM, one amplifier per span), split into
ASE, NLI and total, for an isolated channel and for the central one of 80 equal channels of 50 GHz filling
the 4 THz of the core, at -6..+6 dBm per channel.

This script builds the same chain with GNPy elements and the same parameters:
    Fused (ROADM, 15 dB) -> Edfa (15 dB) -> [Fiber (80 km) -> Edfa (16 dB)] x N
    fibre: 0.2 dB/km, D = 16 ps/nm/km, gamma = 1.3 /W/km; amplifiers: fixed gain, NF = 5 dB, no ripple;
    channels: 50 GBd, roll-off 0, 50 GHz spacing, 193.85 THz; NLI: gn_model_analytic; no Raman, no Tx noise.
An isolated channel needs a second carrier in GNPy (the EDFA derives the slot width from two frequencies):
it gets 1e-20 W at +1.5 THz, which adds nothing measurable.

Usage:
    python3 scripts/verification/gnpy_l12d.py [data_dir] [output_dir]    (defaults: docs/review/vv/data docs/review/vv)

Writes data_dir/e10_gnpy.csv (GNPy values), data_dir/e10_gnpy_comparison.csv, output_dir/figures/e10_gnpy.png
and output_dir/e10_gnpy.md.

Requires GNPy 2.9.0 (scripts/verification/requirements-gnpy.txt; later versions need oopt-gnpy-libyang,
which has no wheel for every platform), numpy and matplotlib.
"""
import csv
import json
import os
import shutil
import sys
import tempfile

import numpy as np

import gnpy
from gnpy.core.elements import Edfa, Fiber, Fused
from gnpy.core.info import create_arbitrary_spectral_information
from gnpy.core.utils import dbm2watt
from gnpy.tools.json_io import load_equipment

DATA = sys.argv[1] if len(sys.argv) > 1 else "docs/review/vv/data"
OUT = sys.argv[2] if len(sys.argv) > 2 else "docs/review/vv"

CENTER_HZ = 193.85e12
BAUD_HZ = 50e9
CHANNELS, VICTIM = 80, 40
SPAN_KM, LOSS_DB_KM = 80.0, 0.2
BOOSTER_DB = 3 * 5.0  # demux + switch + mux, as in SNetS2 (3 x switchInsertionLoss)
FIBER = {"length": SPAN_KM, "length_units": "km", "loss_coef": LOSS_DB_KM, "dispersion": 1.6e-5,
         "gamma": 0.0013, "con_in": 0, "con_out": 0, "pmd_coef": 0, "ref_frequency": CENTER_HZ}
SPANS = [1, 5, 10, 20, 40]
POWERS = range(-6, 7)
TOLERANCE_DB = 0.5


def amplifier_params():
    """Fixed-gain EDFA with NF = 5 dB and no ripple, loaded through GNPy's own equipment parser."""
    example = os.path.join(os.path.dirname(gnpy.__file__), "example-data")
    eqpt = json.load(open(os.path.join(example, "eqpt_config.json")))
    eqpt["Edfa"] = [a for a in eqpt["Edfa"] if a["type_def"] not in ("advanced_model", "dual_stage")]
    eqpt["Edfa"].append({"type_variety": "snets2_fixed", "type_def": "fixed_gain", "gain_flatmax": 40,
                         "gain_min": 0, "p_max": 60, "nf0": 5.0, "allowed_for_design": False})
    for roadm in eqpt["Roadm"]:
        roadm["restrictions"] = {"preamp_variety_list": [], "booster_variety_list": []}
    tmp = tempfile.mkdtemp()
    shutil.copy(os.path.join(example, "default_edfa_config.json"), tmp)
    path = os.path.join(tmp, "eqpt_config.json")
    json.dump(eqpt, open(path, "w"))
    return load_equipment(path)["Edfa"]["snets2_fixed"].__dict__


def chain(spans, amp):
    elements = [Fused(uid="roadm", params={"loss": BOOSTER_DB}),
                Edfa(uid="booster", params=amp, operational={"gain_target": BOOSTER_DB, "tilt_target": 0, "out_voa": 0})]
    for i in range(spans):
        elements.append(Fiber(uid=f"span{i}", params=FIBER))
        elements.append(Edfa(uid=f"amp{i}", params=amp,
                             operational={"gain_target": SPAN_KM * LOSS_DB_KM, "tilt_target": 0, "out_voa": 0}))
    return elements


def snr(load, spans, power_dbm, amp):
    """(SNR ASE, SNR NLI, SNR total) in dB of the victim channel at the end of the link."""
    if load == "isolated":
        frequency, signal, victim = [CENTER_HZ, CENTER_HZ + 1.5e12], [dbm2watt(power_dbm), 1e-20], 0
    else:
        frequency = [CENTER_HZ + (k - VICTIM) * BAUD_HZ for k in range(CHANNELS)]
        signal, victim = dbm2watt(power_dbm), VICTIM
    si = create_arbitrary_spectral_information(frequency=frequency, signal=signal, baud_rate=BAUD_HZ,
                                               tx_osnr=100, slot_width=BAUD_HZ, roll_off=0.0)
    # The ROADM loss and the booster gain cancel: the launch power into the first span is power_dbm, as in SNetS2
    for element in chain(spans, amp):
        if isinstance(element, Fiber):
            element.ref_pch_in_dbm = power_dbm
        si = element(si)
    s, a, n = si.signal[victim], si.ase[victim], si.nli[victim]
    return 10 * np.log10(s / a), 10 * np.log10(s / n), 10 * np.log10(s / (a + n))


def main():
    amp = amplifier_params()
    gnpy_rows = []
    for load in ("isolated", "full"):
        for spans in SPANS:
            for p in POWERS:
                ase, nli, total = snr(load, spans, p, amp)
                gnpy_rows.append({"load": load, "spans": spans, "power_dbm": p,
                                  "snr_ase_db": ase, "snr_nli_db": nli, "snr_db": total})
    with open(os.path.join(DATA, "e10_gnpy.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(gnpy_rows[0]))
        w.writeheader()
        w.writerows(gnpy_rows)

    snets2 = {(r["load"], int(r["spans"]), int(r["power_dbm"])): r
              for r in csv.DictReader(open(os.path.join(DATA, "e10_gnpy_snets2.csv")))}
    comparison = []
    for g in gnpy_rows:
        s = snets2[(g["load"], g["spans"], g["power_dbm"])]
        row = {"load": g["load"], "spans": g["spans"], "power_dbm": g["power_dbm"]}
        for part in ("snr_ase_db", "snr_nli_db", "snr_db"):
            row[f"snets2_{part}"] = float(s[part])
            row[f"gnpy_{part}"] = g[part]
            row[f"delta_{part}"] = float(s[part]) - g[part]
        comparison.append(row)
    with open(os.path.join(DATA, "e10_gnpy_comparison.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(comparison[0]))
        w.writeheader()
        w.writerows(comparison)
    plot(comparison)
    table(comparison)


def plot(rows):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, axes = plt.subplots(1, 2, figsize=(10, 3.8), sharey=True)
    colors = {1: "#2a6fdb", 10: "#2e9e5b", 40: "#b03a9e"}
    for ax, load, title in zip(axes, ("isolated", "full"), ("Canal isolado", "Núcleo cheio: 80 canais de 50 GHz")):
        for spans, color in colors.items():
            sel = [r for r in rows if r["load"] == load and r["spans"] == spans]
            x = [r["power_dbm"] for r in sel]
            ax.plot(x, [r["gnpy_snr_db"] for r in sel], "-", color=color, label=f"GNPy, {spans} vão(s)")
            ax.plot(x, [r["snets2_snr_db"] for r in sel], "o", color=color, ms=4, mfc="none",
                    label=f"SNetS2, {spans} vão(s)")
        ax.set_title(title, fontsize=9)
        ax.set_xlabel("Potência por canal (dBm)")
        ax.grid(True, alpha=0.3)
        ax.spines["top"].set_visible(False)
        ax.spines["right"].set_visible(False)
    axes[0].set_ylabel("SNR (dB), ASE + NLI")
    axes[0].legend(frameon=False, fontsize=7, ncol=2)
    fig.tight_layout()
    os.makedirs(os.path.join(OUT, "figures"), exist_ok=True)
    fig.savefig(os.path.join(OUT, "figures", "e10_gnpy.png"), dpi=130)


def table(rows):
    def fmt(x):
        return f"{x:+.2f}".replace(".", ",")

    lines = ["| Carga | Componente | Δ mínimo (dB) | Δ máximo (dB) | Dentro de ±0,5 dB |", "| :-- | :-- | :-: | :-: | :-: |"]
    names = {"snr_ase_db": "ASE", "snr_nli_db": "NLI", "snr_db": "ASE + NLI"}
    for load in ("isolated", "full"):
        for part, name in names.items():
            d = [r[f"delta_{part}"] for r in rows if r["load"] == load]
            ok = sum(abs(v) <= TOLERANCE_DB for v in d)
            lines.append(f"| {'canal isolado' if load == 'isolated' else 'núcleo cheio'} | {name} | {fmt(min(d))} | "
                         f"{fmt(max(d))} | {ok} de {len(d)} |")
    with open(os.path.join(OUT, "e10_gnpy.md"), "w") as f:
        f.write("# E10: SNetS2 × GNPy (L12-d)\n\nGerado por `scripts/verification/gnpy_l12d.py`. "
                "Δ = SNR do SNetS2 − SNR do GNPy, sobre 5 comprimentos (1 a 40 vãos) × 13 potências (−6 a +6 dBm).\n\n")
        f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
