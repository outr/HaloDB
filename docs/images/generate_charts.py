#!/usr/bin/env python3
"""Generate the benchmark bar charts (SVG) for docs/benchmarks.md and benchmarks/README.md.

The original HaloDB charts were hand-made in Google Sheets and exported as PNGs. This script
reproduces that visual style (Google-blue HaloDB bars, amber RocksDB bars, horizontal bars with
value labels, a top-right legend and light gridlines) but as version-controlled SVG so the charts
can be regenerated from data with no spreadsheet and no third-party dependencies.

Run:  python3 docs/images/generate_charts.py   (writes the *.svg files next to this script)

Update the numbers below when re-running the benchmark (`sbt "benchmarks/run quick"`).
"""

import os

# --- Google Sheets palette ---
BLUE = "#4285F4"   # HaloDB
AMBER = "#E8B33A"  # RocksDB
INK = "#202124"    # titles / dark labels
GREY = "#5f6368"   # axis text / category labels
GRID = "#e3e6ea"   # gridlines
AXIS = "#bdc1c6"   # baseline axis

FONT = ("font-family=\"-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,"
        "sans-serif\"")

SERIES = ["HaloDB", "RocksDB"]
COLOR = {"HaloDB": BLUE, "RocksDB": AMBER}
# White value labels read well on the blue bar; dark labels on the lighter amber bar.
LABEL_COLOR = {"HaloDB": "#ffffff", "RocksDB": INK}


def nice_ceiling(v):
    """Round an axis max up to a clean 1/2/2.5/5 x 10^n value (Google-chart style)."""
    if v <= 0:
        return 1
    import math
    mag = 10 ** math.floor(math.log10(v))
    for m in (1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10):
        if v <= m * mag:
            return m * mag
    return 10 * mag


def fmt(v):
    return f"{v:g}" if v < 100 else f"{int(round(v)):,}"


def chart(title, categories, data, unit, ticks=5, log=False):
    """Horizontal grouped bar chart.

    categories: ["1KB", "16KB"]; data: {"HaloDB": [..], "RocksDB": [..]} aligned to categories.
    log=True uses a log10 x-axis (for data spanning several orders of magnitude).
    """
    import math
    W = 940
    pad_l, pad_r, pad_top, pad_bottom = 70, 30, 96, 56
    bar_h, intra_gap, group_gap = 30, 8, 30

    n_cat = len(categories)
    plot_h = n_cat * (len(SERIES) * bar_h + (len(SERIES) - 1) * intra_gap) + (n_cat - 1) * group_gap
    H = pad_top + plot_h + pad_bottom
    plot_x0, plot_x1 = pad_l, W - pad_r
    plot_w = plot_x1 - plot_x0

    if log:
        vmin = 10 ** math.floor(math.log10(min(min(v) for v in data.values())))
        vmax = 10 ** math.ceil(math.log10(max(max(v) for v in data.values())))
        lo, hi = math.log10(vmin), math.log10(vmax)
        x = lambda v: plot_x0 + plot_w * (math.log10(v) - lo) / (hi - lo)
        gridvals = [10 ** p for p in range(int(lo), int(hi) + 1)]
    else:
        vmax = nice_ceiling(max(max(v) for v in data.values()))
        x = lambda v: plot_x0 + plot_w * (v / vmax)
        gridvals = [vmax * i / ticks for i in range(ticks + 1)]

    s = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" '
         f'viewBox="0 0 {W} {H}" {FONT}>']
    s.append(f'<rect width="{W}" height="{H}" fill="#ffffff"/>')
    s.append(f'<text x="{pad_l-46}" y="40" font-size="22" font-weight="600" fill="{INK}">'
             f'{title}</text>')

    # Legend (top-right)
    lx = plot_x1
    for name in reversed(SERIES):
        lx -= 14 + 8 + len(name) * 8.4 + 22
    lx = plot_x1 - sum(14 + 8 + len(n) * 8.6 + 22 for n in SERIES) + 22
    cursor = lx
    for name in SERIES:
        s.append(f'<rect x="{cursor:.0f}" y="56" width="14" height="14" rx="2" '
                 f'fill="{COLOR[name]}"/>')
        s.append(f'<text x="{cursor+20:.0f}" y="68" font-size="14" fill="{GREY}">{name}</text>')
        cursor += 14 + 8 + len(name) * 8.6 + 22

    # Vertical gridlines + x tick labels
    for gv in gridvals:
        gx = x(gv)
        s.append(f'<line x1="{gx:.1f}" y1="{pad_top}" x2="{gx:.1f}" y2="{pad_top+plot_h}" '
                 f'stroke="{GRID}" stroke-width="1"/>')
        s.append(f'<text x="{gx:.1f}" y="{pad_top+plot_h+22}" font-size="12" fill="{GREY}" '
                 f'text-anchor="middle">{fmt(gv)}</text>')
    # Baseline axis
    s.append(f'<line x1="{plot_x0}" y1="{pad_top}" x2="{plot_x0}" y2="{pad_top+plot_h}" '
             f'stroke="{AXIS}" stroke-width="1.5"/>')

    # Bars
    y = pad_top
    for ci, cat in enumerate(categories):
        grp_top = y
        for name in SERIES:
            v = data[name][ci]
            bw = x(v) - plot_x0
            s.append(f'<rect x="{plot_x0}" y="{y}" width="{bw:.1f}" height="{bar_h}" '
                     f'fill="{COLOR[name]}"/>')
            label = fmt(v) + (f" {unit}" if unit else "")
            if bw > 90:  # label inside the bar, right-aligned
                s.append(f'<text x="{plot_x0+bw-10:.1f}" y="{y+bar_h/2+5:.1f}" font-size="14" '
                         f'font-weight="600" fill="{LABEL_COLOR[name]}" text-anchor="end">'
                         f'{label}</text>')
            else:        # too short — label outside to the right
                s.append(f'<text x="{plot_x0+bw+8:.1f}" y="{y+bar_h/2+5:.1f}" font-size="14" '
                         f'font-weight="600" fill="{INK}" text-anchor="start">{label}</text>')
            y += bar_h + intra_gap
        y += -intra_gap + group_gap
        # category label centered on the group
        cy = (grp_top + (y - group_gap)) / 2
        s.append(f'<text x="{plot_x0-12}" y="{cy+5:.1f}" font-size="14" fill="{GREY}" '
                 f'text-anchor="end">{cat}</text>')

    s.append('</svg>')
    return "\n".join(s)


CHARTS = {
    "read-throughput.svg": chart(
        "Random read throughput (ops/sec) — higher is better",
        ["1KB", "16KB"],
        {"HaloDB": [2815617, 635900], "RocksDB": [1676199, 493585]}, ""),
    "read-latency.svg": chart(
        "Read latency p50 (microseconds) — lower is better",
        ["1KB"],
        {"HaloDB": [2.1], "RocksDB": [4.7]}, "µs"),
    "write-throughput.svg": chart(
        "Write throughput (ops/sec) — higher is better",
        ["1KB", "16KB"],
        {"HaloDB": [326630, 71183], "RocksDB": [1540575, 222664]}, ""),
    "prefix-throughput.svg": chart(
        "Prefix-scan throughput (keys/sec, log scale) — higher is better",
        ["1KB", "16KB", "256KB", "1MB", "10MB"],
        {"HaloDB": [1051824, 265794, 32905, 9639, 847],
         "RocksDB": [1236131, 209773, 21629, 5068, 421]}, "", log=True),
}

if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    for fname, svg in CHARTS.items():
        with open(os.path.join(here, fname), "w") as f:
            f.write(svg + "\n")
        print("wrote", fname)
