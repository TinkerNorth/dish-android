#!/usr/bin/env python3
"""scripts/measurements_report.py against bench JSON in the shape
app/src/test/cpp/send_path_bench.cpp writes. When DISH_BENCH_JSON names files the bench really
wrote (os.pathsep between them; CI names its smoke runs'), those are read too.

    python3 -m unittest discover -s scripts -p 'measurements_report_test.py' -t scripts
"""
import contextlib
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import measurements_report as report

MIC_S4 = "S4 (mic 1000 Hz)"


def latency_row(scenario, stream="A", role="UsbPoller", p999=30.0, max_us=50.0):
    return {"scenario": scenario, "stream": stream, "role": role, "rateHz": 1000,
            "reports": 3000, "datagrams": 3000, "datagramsPerReport": 1.0,
            "maxDatagramsPerReport": 1, "p50Us": 16.0, "p99Us": 30.0, "p999Us": p999,
            "maxUs": max_us, "overOneMs": 0}


def lock_row(scenario, lock, role, acquisitions):
    return {"scenario": scenario, "lock": lock, "role": role, "acquisitions": acquisitions,
            "contended": 0, "tryFailures": 0, "maxWaitUs": 0.2, "p99WaitUs": 0.1,
            "maxHoldUs": 1.0}


def harness_entry(scenario):
    return {"scenario": scenario, "satelliteReceived": 4000, "pings": 2, "mic": 150,
            "battery": 6, "rumbleSent": 300, "lightbarSent": 30, "rumblesHandled": 299,
            "lightbarsHandled": 30, "rebinds": 0}


def bench_json(lock_stats, mic_hz, rows, locks, problems=()):
    scenarios = sorted({row["scenario"] for row in rows})
    return {
        "bench": "bench_send_path", "lockStats": lock_stats,
        "settings": {"rateHz": 1000, "secs": 3.0, "reps": 3, "micHz": mic_hz, "smoke": False},
        "machine": {"cpu": "Test CPU", "os": "Linux", "hardwareThreads": 4,
                    "compiler": "gcc 13.3.0", "optimized": "yes"},
        "rows": rows, "locks": locks, "harness": [harness_entry(s) for s in scenarios],
        "problems": list(problems),
    }


def write_runs(directory, extra_locks=(), mic_p999=30.0, problems=()):
    """The four runs a release makes. Every run covers S1 as the bench does; the mic runs also
    cover S4, the only one of theirs the report keeps here."""
    default_rows = [latency_row("S1"), latency_row("S2"), latency_row("S2", "B"),
                    latency_row("S3", "UI", "UiInput")]
    mic_rows = [latency_row("S1"), latency_row("S4", p999=mic_p999),
                latency_row("S4", "UI", "UiInput")]
    default_locks = [lock_row("S1", "session.table", "Other", 2), *extra_locks]
    mic_locks = [lock_row("S1", "session.table", "Other", 2),
                 lock_row("S4", "session.audio", "MicCapture", 3000)]
    files = {
        "bench_send_path.json": bench_json(False, 50, default_rows, [], problems),
        "bench_send_path-mic1000.json": bench_json(False, 1000, mic_rows, []),
        "bench_send_path_lockstats.json": bench_json(True, 50, default_rows, default_locks),
        "bench_send_path_lockstats-mic1000.json": bench_json(True, 1000, mic_rows, mic_locks),
    }
    directory.mkdir(parents=True, exist_ok=True)
    for name, data in files.items():
        (directory / name).write_text(json.dumps(data), encoding="utf-8")


def edit_run(path, change):
    data = json.loads(path.read_text(encoding="utf-8"))
    change(data)
    path.write_text(json.dumps(data), encoding="utf-8")


def run(*args):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = report.main(list(args))
    return code, out.getvalue()


class MeasurementsReportTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.raw = self.root / "raw"
        self.out = self.root / "out"
        environment = mock.patch.dict(os.environ)
        environment.start()
        self.addCleanup(environment.stop)
        for name in ("GITHUB_STEP_SUMMARY", "ImageOS", "ImageVersion"):
            os.environ.pop(name, None)

    def report(self, version="2.5.0", *extra):
        return run("--input-dir", str(self.raw), "--output-dir", str(self.out), "--version",
                   version, "--commit", "0123456789abcdef", *extra)

    def written(self, version="2.5.0"):
        return json.loads((self.out / f"measurements-{version}.json").read_text(encoding="utf-8"))

    def markdown(self, version="2.5.0"):
        return (self.out / f"measurements-{version}.md").read_text(encoding="utf-8")

    def test_writes_the_json_and_the_summary_for_four_runs(self):
        write_runs(self.raw)
        code, _ = self.report()
        self.assertEqual(code, 0)
        data = self.written()
        self.assertEqual(data["schema"], report.SCHEMA)
        self.assertEqual(data["commit"], "0123456789abcdef")
        self.assertEqual(len(data["latency"]), 12)
        self.assertEqual({s["target"] for s in data["sources"]},
                         {"bench_send_path", "bench_send_path_lockstats"})
        self.assertEqual(data["micVariantsHz"], [1000])
        markdown = self.markdown()
        self.assertTrue(markdown.startswith("# Measurements 2.5.0\n"))
        self.assertIn("`0123456`", markdown)
        self.assertIn("Runs of 3 s x 3 reps, stream A at 1000 Hz, mic frames at 50 a second; S4 "
                      "to S6 again at 1000.", markdown)
        self.assertIn("| bench_send_path | S4 (mic 1000 Hz) | A | UsbPoller | 1000 | 3000 |",
                      markdown)
        self.assertIn("These are host numbers, not a phone's", markdown)
        self.assertIn("hotpath_latency", markdown)

    def test_the_mic_runs_keep_only_the_scenarios_that_run_the_mic(self):
        write_runs(self.raw)
        self.report()
        data = self.written()
        mic = [row for row in data["latency"] if row["variant"] == "mic1000"]
        self.assertEqual({row["scenario"] for row in mic}, {MIC_S4})
        self.assertEqual({lock["scenario"] for lock in data["locks"]
                          if lock["variant"] == "mic1000"}, {MIC_S4})
        self.assertEqual({h["scenario"] for h in data["harness"] if h["variant"] == "mic1000"},
                         {MIC_S4})
        self.assertNotIn("S1 (mic", self.markdown())

    def test_the_lock_gate_passes_when_no_input_thread_takes_a_lock(self):
        write_runs(self.raw, extra_locks=[lock_row("S1", "input.bindings", "Binding", 14)])
        code, printed = self.report()
        self.assertEqual(code, 0)
        gate = self.written()["lockGate"]
        self.assertTrue(gate["passed"])
        self.assertEqual(gate["roles"], ["UsbPoller", "UiInput", "Receive"])
        self.assertEqual(gate["checked"], 3)
        self.assertNotIn("::error", printed)
        self.assertIn("PASS: the UsbPoller, UiInput and Receive roles took no lock in any "
                      "scenario (3 lock rows checked).", self.markdown())

    def test_a_lock_taken_by_any_input_thread_fails_the_run_and_still_writes_the_record(self):
        for role in ("UsbPoller", "UiInput", "Receive"):
            with self.subTest(role=role):
                write_runs(self.raw, extra_locks=[lock_row("S1", "input.devices", role, 3000)])
                code, printed = self.report()
                self.assertEqual(code, 1)
                gate = self.written()["lockGate"]
                self.assertFalse(gate["passed"])
                self.assertEqual(gate["failures"], [
                    f"bench_send_path_lockstats default S1: the {role} role acquired "
                    "input.devices 3000 times"])
                self.assertIn("::error title=Lock law gate::", printed)
                self.assertIn("FAIL. The UsbPoller, UiInput and Receive roles must take no lock:",
                              self.markdown())

    def test_a_lock_in_a_mic_run_names_the_mic_scenario(self):
        write_runs(self.raw)
        edit_run(self.raw / "bench_send_path_lockstats-mic1000.json",
                 lambda data: data["locks"].append(lock_row("S4", "session.turn", "Receive", 1)))
        code, _ = self.report()
        self.assertEqual(code, 1)
        self.assertEqual(self.written()["lockGate"]["failures"], [
            f"bench_send_path_lockstats mic1000 {MIC_S4}: the Receive role acquired "
            "session.turn 1 time"])

    def test_a_lockstats_run_without_locks_cannot_be_judged(self):
        write_runs(self.raw)
        edit_run(self.raw / "bench_send_path_lockstats-mic1000.json",
                 lambda data: data.update(locks=[]))
        code, _ = self.report()
        self.assertEqual(code, 1)
        self.assertEqual(self.written()["lockGate"]["failures"], [
            "bench_send_path_lockstats mic1000 recorded no lock at all, so its lock laws "
            "cannot be judged"])

    def test_no_lockstats_run_at_all_cannot_pass(self):
        write_runs(self.raw)
        (self.raw / "bench_send_path_lockstats.json").unlink()
        (self.raw / "bench_send_path_lockstats-mic1000.json").unlink()
        code, _ = self.report("2.5.0", "--allow-partial")
        self.assertEqual(code, 1)
        self.assertEqual(self.written()["lockGate"]["failures"], [
            "no bench_send_path_lockstats run was given, so the lock laws cannot be judged"])

    def test_the_headline_counts_the_usb_pollers_locks_per_report(self):
        write_runs(self.raw)
        self.report()
        top = self.written()["headline"]
        self.assertEqual(top["p50Us"], 16.0)
        self.assertEqual(top["p999Us"], 30.0)
        self.assertEqual(top["inputLocksPerReport"], 0.0)
        self.assertEqual(top["noiseFloor"], {"maxUs": 50.0, "overOneMs": 0})
        self.assertIn("max 50.0 us, 0 reports over 1 ms.", self.markdown())
        self.assertIn("The UsbPoller thread took 0.00 locks per report in S1.", self.markdown())

        edit_run(self.raw / "bench_send_path.json",
                 lambda data: data["rows"][0].update(maxUs=3246.6, overOneMs=1))
        self.report()
        self.assertIn("max 3246.6 us, 1 report over 1 ms.", self.markdown())

        write_runs(self.raw, extra_locks=[lock_row("S1", "input.devices", "UsbPoller", 15000)])
        self.report()
        self.assertAlmostEqual(self.written()["headline"]["inputLocksPerReport"], 5.0)

    def test_the_runner_image_is_named_when_the_runner_says_it(self):
        write_runs(self.raw)
        os.environ["ImageOS"] = "ubuntu24"
        os.environ["ImageVersion"] = "20261026.354.1"
        self.report()
        self.assertEqual(self.written()["machine"]["runnerImage"], "ubuntu24 20261026.354.1")
        self.assertIn("Linux, runner image ubuntu24 20261026.354.1, gcc 13.3.0, optimised build",
                      self.markdown())

    def test_the_harness_problems_reach_the_record(self):
        problem = "S5: the receive loop handed on no rumble or no lightbar"
        write_runs(self.raw, problems=[problem])
        code, _ = self.report()
        self.assertEqual(code, 0)
        self.assertEqual(self.written()["problems"], [f"bench_send_path.json: {problem}"])
        self.assertIn(f"- PROBLEM: bench_send_path.json: {problem}", self.markdown())

    def test_a_missing_run_is_an_error_unless_partial_runs_are_allowed(self):
        write_runs(self.raw)
        (self.raw / "bench_send_path-mic1000.json").unlink()
        code, printed = self.report()
        self.assertEqual(code, 2)
        self.assertIn("missing runs: bench_send_path mic1000", printed)
        code, _ = self.report("2.5.0", "--allow-partial")
        self.assertEqual(code, 0)

    def test_two_files_with_the_same_rows_are_an_error(self):
        write_runs(self.raw)
        copy = (self.raw / "bench_send_path.json").read_text(encoding="utf-8")
        (self.raw / "bench_send_path-again.json").write_text(copy, encoding="utf-8")
        code, printed = self.report()
        self.assertEqual(code, 2)
        self.assertIn("two bench files cover the same row", printed)

    def test_input_that_is_not_this_benchs_is_refused(self):
        cases = {
            "another bench": (lambda data: data.update(bench="input_path"),
                              "is not bench_send_path output"),
            "no lockStats": (lambda data: data.pop("lockStats"),
                             "does not say whether it is the lock-statistics build"),
            "no mic rate": (lambda data: data["settings"].pop("micHz"),
                            "has no settings.micHz"),
            "a row without p99.9": (lambda data: data["rows"][0].pop("p999Us"),
                                    "has a row without p999Us"),
            "a row that is not an object": (lambda data: data["rows"].append(7),
                                            "is not in the shape the bench writes"),
        }
        for case, (change, message) in cases.items():
            with self.subTest(case=case):
                write_runs(self.raw)
                edit_run(self.raw / "bench_send_path.json", change)
                code, printed = self.report()
                self.assertEqual(code, 2)
                self.assertIn(message, printed)

    def test_a_version_label_with_a_slash_is_refused(self):
        write_runs(self.raw)
        code, printed = self.report("feature/x")
        self.assertEqual(code, 2)
        self.assertIn("may only hold letters", printed)

    def test_the_trend_warns_only_when_a_row_is_both_twice_and_100_us_slower(self):
        previous = self.root / "previous"
        write_runs(self.raw, mic_p999=30.0)
        self.report("2.4.0")
        previous.mkdir()
        (self.out / "measurements-2.4.0.json").rename(previous / "measurements-2.4.0.json")

        write_runs(self.raw, mic_p999=59.0)
        self.report("2.5.0", "--previous-dir", str(previous))
        self.assertEqual(self.written()["trend"]["warnings"], [])

        write_runs(self.raw, mic_p999=200.0)
        code, printed = self.report("2.5.0", "--previous-dir", str(previous))
        self.assertEqual(code, 0)
        self.assertEqual(self.written()["trend"]["warnings"], [
            f"bench_send_path {MIC_S4} A p99.9 is 200.0 us, was 30.0 us in 2.4.0, +170.0 us",
            f"bench_send_path_lockstats {MIC_S4} A p99.9 is 200.0 us, was 30.0 us in 2.4.0, "
            "+170.0 us"])
        self.assertIn("::warning title=Measurement trend::", printed)
        markdown = self.markdown()
        self.assertIn("## Trend against 2.4.0", markdown)
        self.assertIn("p99.9 us in 2.4.0", markdown)

    def test_the_newest_earlier_release_is_the_one_compared_with(self):
        previous = self.root / "previous"
        previous.mkdir()
        for version in ("2.4.0", "2.10.0", "2.9.1", "2.11.0"):
            write_runs(self.raw)
            self.report(version)
            (self.out / f"measurements-{version}.json").rename(
                previous / f"measurements-{version}.json")
        (previous / "measurements-junk.json").write_text("not json", encoding="utf-8")
        write_runs(self.raw)
        self.report("2.11.0", "--previous-dir", str(previous))
        self.assertEqual(self.written("2.11.0")["trend"]["previousVersion"], "2.10.0")

    def test_the_summary_is_appended_to_the_job_summary(self):
        write_runs(self.raw)
        summary = self.root / "summary.md"
        summary.write_text("earlier step\n", encoding="utf-8")
        os.environ["GITHUB_STEP_SUMMARY"] = str(summary)
        self.report()
        text = summary.read_text(encoding="utf-8")
        self.assertTrue(text.startswith("earlier step\n# Measurements 2.5.0\n"))

    def test_files_the_bench_really_wrote_are_read(self):
        paths = [path for path in os.environ.get("DISH_BENCH_JSON", "").split(os.pathsep) if path]
        if not paths:
            self.skipTest("DISH_BENCH_JSON names no bench output")
        for path in paths:
            with self.subTest(path=path):
                bench = report.read_bench_file(Path(path))
                self.assertTrue(bench["rows"])
                for row in bench["rows"]:
                    for name in report.LATENCY_FIELDS:
                        self.assertIn(name, row)
                self.assertEqual(bench["problems"], [])
                if bench["target"] == report.LOCKSTATS_TARGET:
                    self.assertTrue(bench["locks"])
                    for lock in bench["locks"]:
                        for name in report.LOCK_FIELDS:
                            self.assertIn(name, lock)


if __name__ == "__main__":
    unittest.main()
