#!/usr/bin/env python3
"""Turns the host bench's JSON into a release's measurements: measurements-<version>.json and the
job summary measurements-<version>.md.

    scripts/measurements_report.py --input-dir raw --output-dir out --version 2.5.0 \\
        [--previous-dir prev] [--commit SHA] [--allow-partial]

The input is one JSON file per run of bench_send_path and of bench_send_path_lockstats
(app/src/test/cpp/send_path_bench.cpp, tools/bench/README.md "Host bench"): each at its defaults,
and each again with --mic-hz 1000, of which only S4 to S6, the scenarios that run the mic, are
kept. A port of dish-linux's scripts/measurements-report.py, itself a port of the satellite's
scripts/measurements-report.ps1, to this bench's JSON. These are host numbers: the native input
path built for the runner and driven there over loopback, not a phone.

Two gates. The lock law gate fails the run: in the lock-statistics runs the UsbPoller, UiInput
and Receive roles take no lock in any scenario the report keeps, as lock_laws_test.cpp holds
them to empty lists. The trend gate warns and never fails: a row whose p99.9 or max is more than
--trend-ratio times the previous release's and more than --trend-min-delta-us above it.

Exit codes: 0 written and the lock law gate passed, 1 written but the lock law gate failed,
2 the input could not be read.
"""
import argparse
import datetime
import json
import os
import re
import sys
from pathlib import Path

SCHEMA = 1
BENCH = "bench_send_path"
LATENCY_TARGET = "bench_send_path"
LOCKSTATS_TARGET = "bench_send_path_lockstats"
DEFAULT_MIC_HZ = 50
DEFAULT_VARIANT = "default"
EXPECTED_TARGETS = (LATENCY_TARGET, LOCKSTATS_TARGET)
EXPECTED_VARIANTS = (DEFAULT_VARIANT, "mic1000")
MIC_SCENARIOS = ("S4", "S5", "S6")
LOCK_FREE_ROLES = ("UsbPoller", "UiInput", "Receive")
HEADLINE_SCENARIO = "S1"
HEADLINE_STREAM = "A"
HEADLINE_ROLE = "UsbPoller"
TREND_METRICS = {"p999Us": "p99.9", "maxUs": "max"}
VERSION_PATTERN = re.compile(r"^[0-9A-Za-z][0-9A-Za-z._+-]*$")
SHORT_COMMIT = 7

LATENCY_FIELDS = {
    "rateHz": int, "reports": int, "datagrams": int, "datagramsPerReport": float,
    "maxDatagramsPerReport": int, "p50Us": float, "p99Us": float, "p999Us": float,
    "maxUs": float, "overOneMs": int,
}
REQUIRED_LATENCY = ("scenario", "stream", "role", "reports", "p50Us", "p99Us", "p999Us", "maxUs")
HARNESS_FIELDS = ("satelliteReceived", "pings", "mic", "battery", "rumbleSent", "lightbarSent",
                  "rumblesHandled", "lightbarsHandled", "rebinds")
LOCK_FIELDS = {
    "acquisitions": int, "contended": int, "tryFailures": int, "maxWaitUs": float,
    "p99WaitUs": float, "maxHoldUs": float,
}

HOST_NUMBERS = (
    "These are host numbers, not a phone's: the native input path (`app/src/main/cpp`) built "
    "with the runner's compiler and driven on the runner over loopback to a fake satellite. The "
    "phone's own figure, stage 1 of `hotpath_latency` (Diagnostics, Latency profiling), is "
    "still taken by hand; `tools/bench/README.md` says how.")


class ReportError(Exception):
    """The input cannot be turned into a report."""


def version_sort_key(name: str) -> str:
    return "".join(digits.zfill(8) for digits in re.findall(r"\d+", name))


def fixed(value: float, decimals: int = 1) -> str:
    return f"{value:.{decimals}f}"


def listed(items: list[str]) -> str:
    return items[0] if len(items) == 1 else ", ".join(items[:-1]) + " and " + items[-1]


def run_variant(mic_hz: int) -> str:
    return DEFAULT_VARIANT if mic_hz == DEFAULT_MIC_HZ else f"mic{mic_hz}"


def scenario_kept(variant: str, scenario: str) -> bool:
    return variant == DEFAULT_VARIANT or scenario in MIC_SCENARIOS


def scenario_label(variant: str, scenario: str, mic_hz: int) -> str:
    return scenario if variant == DEFAULT_VARIANT else f"{scenario} (mic {mic_hz} Hz)"


def figure(row: dict, name: str, kind) -> float | int:
    value = row.get(name)
    return kind(0) if value is None else kind(value)


def bench_rows(data: dict, name: str, target: str, variant: str, mic_hz: int) -> dict:
    rows = []
    for row in data.get("rows") or []:
        for field in REQUIRED_LATENCY:
            if row.get(field) is None:
                raise ReportError(f"{name} has a row without {field}")
        scenario = str(row["scenario"])
        if not scenario_kept(variant, scenario):
            continue
        rows.append({"target": target, "variant": variant,
                     "scenario": scenario_label(variant, scenario, mic_hz),
                     "stream": str(row["stream"]), "role": str(row["role"]),
                     **{field: figure(row, field, kind) for field, kind in LATENCY_FIELDS.items()}})
    if not rows:
        raise ReportError(f"{name} has no rows")
    harness = [{"target": target, "variant": variant,
                "scenario": scenario_label(variant, str(entry["scenario"]), mic_hz),
                **{field: int(entry.get(field, 0)) for field in HARNESS_FIELDS}}
               for entry in data.get("harness") or []
               if scenario_kept(variant, str(entry.get("scenario", "")))]
    locks = [{"target": target, "variant": variant,
              "scenario": scenario_label(variant, str(lock["scenario"]), mic_hz),
              "lock": str(lock["lock"]), "role": str(lock["role"]),
              **{field: figure(lock, field, kind) for field, kind in LOCK_FIELDS.items()}}
             for lock in data.get("locks") or []
             if scenario_kept(variant, str(lock.get("scenario", "")))]
    return {"rows": rows, "harness": harness, "locks": locks}


def read_bench_file(path: Path) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise ReportError(f"{path.name} is not valid JSON: {error}") from error
    if not isinstance(data, dict) or data.get("bench") != BENCH:
        raise ReportError(f"{path.name} is not {BENCH} output")
    settings = data.get("settings")
    machine = data.get("machine")
    if not isinstance(settings, dict) or "micHz" not in settings:
        raise ReportError(f"{path.name} has no settings.micHz, so its run cannot be named")
    if not isinstance(machine, dict):
        raise ReportError(f"{path.name} has no machine")
    if not isinstance(data.get("lockStats"), bool):
        raise ReportError(f"{path.name} does not say whether it is the lock-statistics build")
    target = LOCKSTATS_TARGET if data["lockStats"] else LATENCY_TARGET
    try:
        mic_hz = int(settings["micHz"])
        variant = run_variant(mic_hz)
        parts = bench_rows(data, path.name, target, variant, mic_hz)
        problems = [f"{path.name}: {problem}" for problem in data.get("problems") or []]
    except (AttributeError, KeyError, TypeError, ValueError) as error:
        raise ReportError(f"{path.name} is not in the shape the bench writes: {error}") from error
    return {"file": path.name, "target": target, "variant": variant, "micHz": mic_hz,
            "settings": settings, "machine": machine, **parts, "problems": problems}


def read_bench_directory(directory: Path) -> list[dict]:
    if not directory.is_dir():
        raise ReportError(f"input directory {directory} does not exist")
    files = sorted(directory.glob("*.json"))
    if not files:
        raise ReportError(f"no bench JSON files in {directory}")
    return [read_bench_file(path) for path in files]


def row_key(row: dict) -> str:
    return f"{row['target']}|{row['variant']}|{row['scenario']}|{row['stream']}"


def missing_runs(runs: list[dict]) -> list[str]:
    present = {(run["target"], run["variant"]) for run in runs}
    return [f"{target} {variant}" for target in EXPECTED_TARGETS for variant in EXPECTED_VARIANTS
            if (target, variant) not in present]


def runner_image() -> str:
    return " ".join(part for part in (os.environ.get("ImageOS", ""),
                                      os.environ.get("ImageVersion", "")) if part)


def build_measurements(runs: list[dict], version: str, commit: str, generated_at: str) -> dict:
    seen = set()
    latency, harness, locks, problems, sources = [], [], [], [], []
    for run in runs:
        for row in run["rows"]:
            key = row_key(row)
            if key in seen:
                raise ReportError(f"two bench files cover the same row: {key}")
            seen.add(key)
            latency.append(row)
        harness.extend(run["harness"])
        locks.extend(run["locks"])
        problems.extend(run["problems"])
        sources.append({"file": run["file"], "target": run["target"], "variant": run["variant"],
                        "settings": run["settings"]})
    first = next((r for r in runs if r["variant"] == DEFAULT_VARIANT), runs[0])
    machine = first["machine"]
    mic_variants = sorted({run["micHz"] for run in runs if run["variant"] != DEFAULT_VARIANT})
    return {
        "schema": SCHEMA, "bench": BENCH, "version": version, "commit": commit,
        "generatedAt": generated_at,
        "machine": {"cpu": machine.get("cpu", "unknown"),
                    "hardwareThreads": machine.get("hardwareThreads", 0),
                    "os": machine.get("os", "unknown"),
                    "compiler": machine.get("compiler", "unknown"),
                    "optimized": machine.get("optimized", "unknown"),
                    "runnerImage": runner_image()},
        "settings": first["settings"], "micVariantsHz": mic_variants, "sources": sources,
        "latency": latency, "harness": harness, "locks": locks, "problems": problems,
    }


def lock_law_gate(measurements: dict) -> dict:
    failures = []
    recorded = {(lock["target"], lock["variant"]) for lock in measurements["locks"]}
    stats_runs = sorted({(row["target"], row["variant"]) for row in measurements["latency"]
                         if row["target"] == LOCKSTATS_TARGET})
    if not stats_runs:
        failures.append(f"no {LOCKSTATS_TARGET} run was given, so the lock laws cannot be judged")
    for target, variant in stats_runs:
        if (target, variant) not in recorded:
            failures.append(f"{target} {variant} recorded no lock at all, so its lock laws "
                            "cannot be judged")
    for lock in measurements["locks"]:
        if lock["role"] not in LOCK_FREE_ROLES or lock["acquisitions"] <= 0:
            continue
        times = "time" if lock["acquisitions"] == 1 else "times"
        failures.append(f"{lock['target']} {lock['variant']} {lock['scenario']}: the "
                        f"{lock['role']} role acquired {lock['lock']} {lock['acquisitions']} "
                        f"{times}")
    return {"name": "lockLaws", "roles": list(LOCK_FREE_ROLES),
            "checked": len(measurements["locks"]), "passed": not failures, "failures": failures}


def read_previous(directory: Path | None, exclude_version: str) -> dict | None:
    if directory is None or not directory.is_dir():
        return None
    found = None
    for path in sorted(directory.glob("measurements-*.json"),
                       key=lambda p: version_sort_key(p.name)):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            print(f"::notice::skipping {path.name}, which is not valid JSON")
            continue
        usable = isinstance(data, dict) and data.get("schema") == SCHEMA and \
            data.get("bench") == BENCH and isinstance(data.get("latency"), list)
        if not usable:
            print(f"::notice::skipping {path.name}, which is not a schema {SCHEMA} {BENCH} file")
            continue
        if str(data.get("version")) == exclude_version:
            continue
        found = data
    return found


def regressed(now: float, was: float, ratio: float, min_delta_us: float) -> bool:
    return now > was * ratio and now - was > min_delta_us


def trend_comparison(measurements: dict, previous: dict | None, ratio: float,
                     min_delta_us: float) -> dict:
    if previous is None:
        return {"available": False, "previousVersion": "", "ratio": ratio,
                "minDeltaUs": min_delta_us, "warnings": [], "previousValues": {}}
    before = {row_key(row): row for row in previous["latency"]}
    warnings, previous_values = [], {}
    for row in measurements["latency"]:
        key = row_key(row)
        if key not in before:
            continue
        for metric, label in TREND_METRICS.items():
            now, was = float(row[metric]), float(before[key].get(metric, 0))
            previous_values[f"{key}|{metric}"] = was
            if regressed(now, was, ratio, min_delta_us):
                warnings.append(f"{row['target']} {row['scenario']} {row['stream']} {label} is "
                                f"{fixed(now)} us, was {fixed(was)} us in "
                                f"{previous['version']}, +{fixed(now - was)} us")
    return {"available": True, "previousVersion": str(previous["version"]), "ratio": ratio,
            "minDeltaUs": min_delta_us, "warnings": warnings, "previousValues": previous_values}


def find_row(rows: list[dict], target: str, scenario: str, stream: str) -> dict | None:
    return next((r for r in rows if r["target"] == target and r["variant"] == DEFAULT_VARIANT
                 and r["scenario"] == scenario and r["stream"] == stream), None)


def headline(measurements: dict) -> dict:
    out: dict = {"scenario": HEADLINE_SCENARIO, "stream": HEADLINE_STREAM}
    plain = find_row(measurements["latency"], LATENCY_TARGET, HEADLINE_SCENARIO, HEADLINE_STREAM)
    if plain is not None:
        out.update({"p50Us": plain["p50Us"], "p99Us": plain["p99Us"], "p999Us": plain["p999Us"],
                    "noiseFloor": {"maxUs": plain["maxUs"], "overOneMs": plain["overOneMs"]}})
    counted = find_row(measurements["latency"], LOCKSTATS_TARGET, HEADLINE_SCENARIO,
                       HEADLINE_STREAM)
    if counted is not None and counted["reports"] > 0:
        taken = sum(lock["acquisitions"] for lock in measurements["locks"]
                    if lock["target"] == LOCKSTATS_TARGET and lock["variant"] == DEFAULT_VARIANT
                    and lock["scenario"] == HEADLINE_SCENARIO and lock["role"] == HEADLINE_ROLE)
        out["inputLocksPerReport"] = taken / counted["reports"]
    return out


def table(headers: list[str], rows: list[list[str]], text_columns: int) -> str:
    rule = ["---" if i < text_columns else "---:" for i in range(len(headers))]
    lines = ["| " + " | ".join(headers) + " |", "| " + " | ".join(rule) + " |"]
    lines += ["| " + " | ".join(cells) + " |" for cells in rows]
    return "\n".join(lines)


def scenario_order(scenario: str) -> tuple:
    digits = re.findall(r"\d+", scenario)
    return (int(digits[0]) if digits else 0, scenario)


def latency_table(measurements: dict, trend: dict) -> str:
    headers = ["target", "scenario", "stream", "role", "rate Hz", "reports", "dgrams/report",
               "max per report", "p50 us", "p99 us", "p99.9 us", "max us", "over 1 ms"]
    if trend["available"]:
        headers += [f"p99.9 us in {trend['previousVersion']}",
                    f"max us in {trend['previousVersion']}"]
    rows = []
    ordered = sorted(measurements["latency"],
                     key=lambda r: (r["target"], r["variant"], scenario_order(r["scenario"])))
    for r in ordered:
        cells = [r["target"], r["scenario"], r["stream"], r["role"], str(r["rateHz"]),
                 str(r["reports"]), fixed(r["datagramsPerReport"], 3),
                 str(r["maxDatagramsPerReport"]), fixed(r["p50Us"]), fixed(r["p99Us"]),
                 fixed(r["p999Us"]), fixed(r["maxUs"]), str(r["overOneMs"])]
        if trend["available"]:
            for metric in TREND_METRICS:
                was = trend["previousValues"].get(f"{row_key(r)}|{metric}")
                cells.append("-" if was is None else fixed(was))
        rows.append(cells)
    return table(headers, rows, 4)


def harness_table(measurements: dict) -> str:
    headers = ["target", "scenario", "satellite received", "pings", "mic", "battery",
               "rumble sent", "lightbar sent", "rumbles handled", "lightbars handled", "rebinds"]
    ordered = sorted(measurements["harness"],
                     key=lambda h: (h["target"], h["variant"], scenario_order(h["scenario"])))
    rows = [[h["target"], h["scenario"]] + [str(h[field]) for field in HARNESS_FIELDS]
            for h in ordered]
    return table(headers, rows, 2)


def lock_table(measurements: dict) -> str:
    headers = ["scenario", "lock", "role", "acquisitions", "contended", "failed tries",
               "max wait us", "p99 wait us", "max hold us"]
    rows = [[lock["scenario"], lock["lock"], lock["role"], str(lock["acquisitions"]),
             str(lock["contended"]), str(lock["tryFailures"]), fixed(lock["maxWaitUs"]),
             fixed(lock["p99WaitUs"]), fixed(lock["maxHoldUs"])]
            for lock in sorted(measurements["locks"],
                               key=lambda k: (k["variant"], scenario_order(k["scenario"]),
                                              k["lock"], k["role"]))]
    return table(headers, rows, 3)


def gate_markdown(gate: dict) -> list[str]:
    roles = listed(gate["roles"])
    lines = ["## Lock law gate", ""]
    if gate["passed"]:
        lines.append(f"PASS: the {roles} roles took no lock in any scenario "
                     f"({gate['checked']} lock rows checked).")
        return lines
    lines += [f"FAIL. The {roles} roles must take no lock:", ""]
    lines += [f"- {failure}" for failure in gate["failures"]]
    return lines


def trend_markdown(trend: dict) -> list[str]:
    if not trend["available"]:
        return ["## Trend", "", "No earlier release carries measurements, so nothing was compared."]
    lines = [f"## Trend against {trend['previousVersion']}", "",
             f"A row warns when its p99.9 or max is more than {trend['ratio']:g} times the "
             f"previous release's and more than {trend['minDeltaUs']:g} us above it."]
    if not trend["warnings"]:
        lines += ["", "No row regressed."]
        return lines
    lines.append("")
    lines += [f"- {warning}" for warning in trend["warnings"]]
    return lines


def setting(settings: dict, name: str) -> str:
    value = settings.get(name)
    return f"{value:g}" if isinstance(value, (int, float)) else "unknown"


def opening(measurements: dict) -> str:
    machine = measurements["machine"]
    settings = measurements["settings"]
    commit = measurements["commit"][:SHORT_COMMIT] if measurements["commit"] else "unknown"
    build = {"yes": "optimised", "no": "unoptimised"}.get(machine["optimized"], "unknown")
    runner = f", runner image {machine['runnerImage']}" if machine["runnerImage"] else ""
    mic_runs = "".join(f"; {MIC_SCENARIOS[0]} to {MIC_SCENARIOS[-1]} again at {hz}"
                       for hz in measurements["micVariantsHz"])
    reps = setting(settings, "reps")
    plural = "" if reps == "1" else "s"
    return (f"Commit `{commit}`, generated {measurements['generatedAt']}. {machine['cpu']}, "
            f"{machine['hardwareThreads']} hardware threads, {machine['os']}{runner}, "
            f"{machine['compiler']}, {build} build. Runs of {setting(settings, 'secs')} s x "
            f"{reps} rep{plural}, stream A at {setting(settings, 'rateHz')} Hz, "
            f"mic frames at {setting(settings, 'micHz')} a second{mic_runs}. Latencies in "
            "microseconds, from a report in hand to the return of the sendto that carried it.")


def headline_markdown(top: dict) -> list[str]:
    lines = ["## Headline", ""]
    if "p50Us" in top:
        lines.append(f"{top['scenario']} stream {top['stream']}, one USB poller and nothing else: "
                     f"{fixed(top['p50Us'])} us median, {fixed(top['p99Us'])} us p99, "
                     f"{fixed(top['p999Us'])} us p99.9 from the report in hand to the return of "
                     "its sendto.")
        floor = top["noiseFloor"]
        lines.append(f"Runner noise floor, the same run: max {fixed(floor['maxUs'])} us, "
                     f"{floor['overOneMs']} reports over 1 ms. A shared runner is noisy; a "
                     "difference inside that is not a finding.")
    if "inputLocksPerReport" in top:
        lines.append(f"The {HEADLINE_ROLE} thread took {fixed(top['inputLocksPerReport'], 2)} "
                     f"locks per report in {top['scenario']}.")
    return lines


def render_markdown(measurements: dict, gate: dict, trend: dict, top: dict) -> str:
    lines = [f"# Measurements {measurements['version']}", "", opening(measurements), "",
             HOST_NUMBERS, ""]
    lines += headline_markdown(top) + [""]
    lines += gate_markdown(gate) + [""] + trend_markdown(trend) + [""]
    lines += ["## Latency", "", latency_table(measurements, trend), ""]
    if measurements["harness"]:
        lines += ["## Harness", "", "What the fake satellite received and sent, and what the "
                  "receive loop handed on, in the same runs.", "", harness_table(measurements), ""]
    if measurements["locks"]:
        lines += ["## Locks", "", f"From {LOCKSTATS_TARGET}, for the reported rep of each "
                  "scenario.", "", lock_table(measurements), ""]
    if measurements["problems"]:
        lines += ["## Problems", ""]
        lines += [f"- PROBLEM: {problem}" for problem in measurements["problems"]]
        lines.append("")
    return "\n".join(lines)


def write_report(args: argparse.Namespace) -> int:
    if not VERSION_PATTERN.match(args.version):
        raise ReportError(f"version label '{args.version}' may only hold letters, digits, '.', "
                          "'_', '+' and '-'")
    runs = read_bench_directory(Path(args.input_dir))
    missing = missing_runs(runs)
    if missing and not args.allow_partial:
        raise ReportError(f"missing runs: {', '.join(missing)}")
    generated_at = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    measurements = build_measurements(runs, args.version, args.commit, generated_at)
    gate = lock_law_gate(measurements)
    previous = read_previous(Path(args.previous_dir) if args.previous_dir else None, args.version)
    trend = trend_comparison(measurements, previous, args.trend_ratio, args.trend_min_delta_us)
    top = headline(measurements)
    measurements["lockGate"] = gate
    measurements["headline"] = top
    measurements["trend"] = {key: trend[key] for key in
                             ("previousVersion", "ratio", "minDeltaUs", "warnings")}
    markdown = render_markdown(measurements, gate, trend, top)
    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    json_path = out_dir / f"measurements-{args.version}.json"
    md_path = out_dir / f"measurements-{args.version}.md"
    json_path.write_text(json.dumps(measurements, indent=2) + "\n", encoding="utf-8")
    md_path.write_text(markdown, encoding="utf-8")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as out:
            out.write(markdown + "\n")
    for warning in trend["warnings"]:
        print(f"::warning title=Measurement trend::{warning}")
    for failure in gate["failures"]:
        print(f"::error title=Lock law gate::{failure}")
    print(f"measurements: {len(runs)} bench files, {len(measurements['latency'])} latency rows, "
          f"{len(measurements['locks'])} lock rows")
    print(f"lock law gate: {'PASS' if gate['passed'] else 'FAIL'}")
    print(f"trend: {len(trend['warnings'])} warning(s) against {trend['previousVersion']}"
          if trend["available"] else "trend: no earlier release to compare with")
    print(f"wrote {json_path}")
    print(f"wrote {md_path}")
    return 0 if gate["passed"] else 1


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--input-dir", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--previous-dir", default="")
    parser.add_argument("--commit", default=os.environ.get("GITHUB_SHA", ""))
    parser.add_argument("--trend-ratio", type=float, default=2.0)
    parser.add_argument("--trend-min-delta-us", type=float, default=100.0)
    parser.add_argument("--allow-partial", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    try:
        return write_report(args)
    except ReportError as error:
        print(f"::error title=Measurements report::{error}")
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
