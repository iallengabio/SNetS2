#!/usr/bin/env python3
"""Pre-FEC SNR and crosstalk thresholds per modulation format for a given FEC.

SNR threshold: smallest SNR (per symbol, in the signal bandwidth, as computed by the simulator)
whose pre-FEC BER does not exceed the BER the FEC can correct. Gray-coded M-QAM approximation:

    BER(SNR) = 4/log2(M) * (1 - 1/sqrt(M)) * Q( sqrt(3 SNR / (M - 1)) )

(exact order of magnitude for square QAM, standard approximation for 8QAM and 32QAM).

XT threshold: XT_th = -(SNR_th + margin), i.e. the inter-core crosstalk must stay `margin` dB below
the noise level allowed by the SNR threshold (default 10.08 dB, the convention of the original
configuration files; ~0.4 dB SNR penalty).

Usage: python3 scripts/compute_thresholds.py [BER_target] [XT_margin_dB]
       e.g. 2.4e-2 for 20%-overhead SD-FEC, 3.8e-3 for 7%-overhead HD-FEC.
"""
import sys
from math import erfc, log2, sqrt


def q(x):
    return 0.5 * erfc(x / sqrt(2))


def ber(m, snr_db):
    snr = 10 ** (snr_db / 10)
    return (4 / log2(m)) * (1 - 1 / sqrt(m)) * q(sqrt(3 * snr / (m - 1)))


def snr_threshold_db(m, ber_target):
    lo, hi = -10.0, 40.0
    for _ in range(200):
        mid = (lo + hi) / 2
        if ber(m, mid) > ber_target:
            lo = mid
        else:
            hi = mid
    return hi


if __name__ == "__main__":
    target = float(sys.argv[1]) if len(sys.argv) > 1 else 2.4e-2
    margin = float(sys.argv[2]) if len(sys.argv) > 2 else 10.08
    print(f"pre-FEC BER target = {target:g}, XT margin = {margin} dB")
    print(f"{'format':>6} | {'SNR (dB)':>8} | {'XT (dB)':>8}")
    for m in (4, 8, 16, 32, 64):
        snr = round(snr_threshold_db(m, target), 2)
        print(f"{str(m) + 'QAM':>6} | {snr:8.2f} | {-(snr + margin):8.2f}")
