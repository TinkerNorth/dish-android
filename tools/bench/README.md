# dish device-side latency benchmark (stages 0–2)

On-device instrumentation for the half of the chain that lives on the phone. Pairs
with the PC-side `dish_bench` (satellite `tools/bench/`) which covers stages 3–4 and
the cakama3a-equivalent poll cadence. Full chain and methodology: see that README.

| # | Stage | Source |
|---|-------|--------|
| 0 | Controller + USB poll interval | `UsbDirectNative.getDeviceUrbCount(id)` sampled over time (already wired via `PollRateSampler` / `InputRateStore`) |
| 1 | Android hot path: URB reap → parse → encrypt → `sendto` | `hotpath_latency` (this change) |
| 2 | Network one-way ≈ ½ heartbeat RTT | `hotpath_latency` (this change) |
| – | USB poll jitter (`urb_gap_us`: URB inter-arrival percentiles, gaps >100 ms dropped as stream pauses) | `hotpath_latency` |

## How the instrumentation works

`app/src/main/cpp/hotpath_latency.{h,cpp}` is **off by default**; when disabled each
mark is a single relaxed atomic load, so the streaming path is unchanged. Hooks:

- `markInputRead()` — `usb_host.cpp`, at URB reap (per-device poller thread).
- `markGamepadSentAt()` — `send_path.cpp` `sendFramed`: the clock is read as a pad's
  published `MSG_GAMEPAD_DATA` packet leaves `sendto()`, and the mark is taken with that
  time once the session's send gate is let go. Stage 1 = this Δ on one thread.
- `shouldArmPing()` / `addRttSample()` — `send_path.cpp` heartbeat send and
  `receive_path.cpp` ack receive. Stage 2 RTT measured on the device clock; one-way ≈ RTT/2.

## Running it

1. Build and install a debug build instrumented as above:
   ```
   $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
   .\gradlew installDebug
   ```
2. Connect a USB controller in **USB-direct** mode and start streaming to satellite.
3. Enable the benchmark and play normally for ~30 s (wiggle sticks so every poll is a
   distinct sample). From any debug surface or a scratch call:
   ```kotlin
   physicalInputNative.setHotPathBench(true)
   // ...stream for ~30 s...
   val json = physicalInputNative.hotPathBenchJson(reset = true)
   android.util.Log.i("bench", json)
   ```
   `json` looks like:
   ```json
   {"enabled":true,
    "stage1_hotpath_us":{"n":18234,"min":21.4,"p50":34.8,"p90":52.1,"p99":88.0,"max":910.2,"mean":38.7},
    "rtt_us":{"n":15,"min":2100.0,"p50":2480.0,"p90":3100.0,"p99":3400.0,"max":3400.0,"mean":2560.0}}
   ```
4. For stage 0, read the URB rate during the same run:
   ```kotlin
   val hz = inputRateStore.rateFor(deviceId)   // or sample getDeviceUrbCount over 1 s
   ```

## Fill in the budget

```
stage 0  USB poll interval         : 1000/hz ms  (= <hz> Hz controller)
stage 1  Android hot path p50/p99  : <stage1 p50/1000> / <stage1 p99/1000> ms
stage 2  network one-way (RTT/2)   : <rtt p50 / 2 / 1000> ms
```

Add the PC-measured stages 3+4 (~0.02 ms combined, loopback-isolated) for the full
controller→game budget.

> Note: `rtt_us` is the **full** heartbeat round trip; one-way network ≈ p50/2 (the
> Diagnostics screen already shows the halved value). The heartbeat fires every 2 s, so a
> 30 s run yields ~15 RTT samples — enough for a median, not a tail. For a denser network
> number, open the Diagnostics latency panel, which switches the heartbeat to
> `HEARTBEAT_INTERVAL_PROBE_MS` (250 ms) while it is open.
>
> Measure **while input is streaming**. On an otherwise idle link, Wi-Fi power save parks
> the phone's radio between the 2 s heartbeats and each ping waits out the wakeup cycle,
> so an idle reading of tens of ms is the radio's doze schedule, not the latency games
> see. Steady gamepad traffic keeps the radio awake and the same path reads a few ms.
> Arming the bench clears the sample window, so old idle samples never pollute a run.

## Host bench

`bench_send_path` (`app/src/test/cpp/send_path_bench.cpp`) times the native input path on a
host, without a phone: the extracted units (`slot_table`, `send_path`, `session_table`,
`receive_path`) driven the way the JNI layer and the USB poller drive them, sending real
sealed datagrams over loopback to a fake satellite. Latency is per report, from the moment the
report is in hand (when the poller would have reaped the URB, or the UI thread received the
event) to the return of the `sendto` that carried it: the device and slot locks, the session
lookup, the send turn and counter, framing, the ChaCha20-Poly1305 seal and the system call. The
session's `DatagramPort` notes the time each `sendto` returns on the calling thread.

Every producer works to absolute deadlines: frame `k` is due `k` periods after a common start,
whatever happened to frame `k - 1`; it sleeps until shortly before the deadline (a
high-resolution waitable timer on Windows) and spins the rest. Because the start is common, each
frame of a 250 Hz stream falls on the same instant as every fourth frame of the 1000 Hz poller:
every one of its reports meets one of A's, the worst case for the locks they share. Each scenario
runs `--secs` (3) seconds, `--reps` (3) times interleaved after a discarded warm-up pass; the
table shows the run whose stream A p99.9 is the median.

- S1: one USB poller (A, `UsbPoller`) at `--rate` (1000 Hz), one session; its reports carry
  input only.
- S2: S1 plus a second USB poller (B) at 250 Hz on the same session.
- S3: S2 plus the UI thread (`UiInput`) at 250 Hz, the framework pad path (`applyFrameworkMotion`).
- S4: S3 plus the heartbeat (every 2 s, the production body), battery (one datagram per
  controller at the start, every 30 s and once more at 1.0 s) and mic frames through the real
  Opus encoder at `--mic-hz` (50).
- S5: S4 plus the receive loop on its own thread, handling 100 rumble and 10 lightbar
  datagrams a second and the heartbeat acks from the fake satellite; its feedback sink counts
  what the JNI layer would have been asked to do.
- S6: S5 plus a binding thread that unbinds and rebinds B's slot every 200 ms.
- S7: a DualSense: one poller at `--rate` whose every report carries input, motion and a moving
  finger, published as `usb_host.cpp` does (motion no closer than 8 ms, touch through the
  send-on-change gate), so one report can become three datagrams. Its second row times those
  three-datagram reports to the return of their last `sendto`.

`bench_send_path_lockstats` is the same bench in the `DISH_LOCK_STATS` build: it adds, for the
reported run of each scenario, every named lock each thread role took, with its acquisitions,
contended acquisitions, failed `try_lock`s, maximum wait, p99 wait (the upper edge of its log2
bucket) and maximum hold. The instrumentation costs a few clock reads per lock, so the latencies
come from `bench_send_path` and only the lock figures from the stats build.

`--json PATH` writes the settings, the machine, every row (and the lock rows) and the harness
counts; `--csv PATH` writes the rows. `--smoke` runs every scenario once for 0.2 s with the
battery burst and the rebinds compressed to fit; ctest runs it as `send_path_bench_smoke`, and
the stats build, when configured with `-DDISH_BENCH_LOCKSTATS=ON`, as
`send_path_bench_lockstats_smoke`. The exit code reflects the harness's own accounting (a report
that sent nothing, a failed send, a datagram the satellite could not open or saw out of counter
order, a cold sender or feedback that never arrived) and usage errors, never a latency; in a
smoke run it also checks the pacing: no frame before its deadline, and the median frame within
1 ms of it. `--help` lists the options.

Build and run (Windows: msys2 ucrt64 g++, with `C:\msys64\ucrt64\bin` first on `PATH` when the
binaries run, so they load that toolchain's runtime DLLs):

```
cmake -S app/src/test/cpp -B build/native-test -G Ninja -DCMAKE_C_COMPILER=C:/msys64/ucrt64/bin/gcc.exe -DCMAKE_CXX_COMPILER=C:/msys64/ucrt64/bin/g++.exe -DDISH_BENCH_LOCKSTATS=ON
cmake --build build/native-test --target bench_send_path bench_send_path_lockstats
build/native-test/bench_send_path --json before.json --csv before.csv
build/native-test/bench_send_path --mic-hz 1000
build/native-test/bench_send_path_lockstats
```

The host build is optimised (opus's CMake makes it a Release build: `-O3 -DNDEBUG`; libsodium
1.0.20 at `-O3`), and the banner says so. On Windows the socket calls go through
`app/src/test/cpp/udp_socket_windows.cpp`, where Winsock has no per-call `MSG_DONTWAIT`.

### Before: the extracted send path, host numbers

These are host numbers, not the phone's: the on-device run of stage 1 is still owed. Settings
at their defaults (`--rate 1000 --secs 3 --reps 3 --mic-hz 50`), one invocation; AMD Ryzen 9
7900X (12 cores, 24 threads), Windows 11 Home 10.0.26300, gcc 16.1.0 (msys2 ucrt64), loopback.
The machine was not idle: total CPU load averaged 30% (peak 92%) during the run, including a
stray benchmark process holding one core. Latencies in microseconds; `over 1 ms` counts reports
slower than 1 ms; `datagrams` counts the datagrams the stream's reports put on the wire.

| scenario | stream | role | rate Hz | reports | datagrams | datagrams/report | max per report | p50 us | p99 us | p99.9 us | max us | over 1 ms |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| S1 | A | UsbPoller | 1000 | 3000 | 3000 | 1.000 | 1 | 18.4 | 33.9 | 50.2 | 144.8 | 0 |
| S2 | A | UsbPoller | 1000 | 3000 | 3000 | 1.000 | 1 | 16.5 | 52.3 | 68.9 | 87.9 | 0 |
| S2 | B | UsbPoller | 250 | 750 | 750 | 1.000 | 1 | 32.0 | 56.1 | 111.9 | 111.9 | 0 |
| S3 | A | UsbPoller | 1000 | 3000 | 3000 | 1.000 | 1 | 20.7 | 81.2 | 97.1 | 106.3 | 0 |
| S3 | B | UsbPoller | 250 | 750 | 750 | 1.000 | 1 | 22.6 | 98.5 | 241.6 | 241.6 | 0 |
| S3 | UI | UiInput | 250 | 750 | 750 | 1.000 | 1 | 48.7 | 96.7 | 217.7 | 217.7 | 0 |
| S4 | A | UsbPoller | 1000 | 3000 | 3000 | 1.000 | 1 | 16.2 | 74.1 | 93.7 | 181.7 | 0 |
| S4 | B | UsbPoller | 250 | 750 | 750 | 1.000 | 1 | 19.9 | 79.4 | 129.7 | 129.7 | 0 |
| S4 | UI | UiInput | 250 | 750 | 750 | 1.000 | 1 | 47.9 | 85.1 | 158.5 | 158.5 | 0 |
| S5 | A | UsbPoller | 1000 | 3000 | 3000 | 1.000 | 1 | 16.3 | 73.6 | 100.9 | 120.1 | 0 |
| S5 | B | UsbPoller | 250 | 750 | 750 | 1.000 | 1 | 33.5 | 77.7 | 122.5 | 122.5 | 0 |
| S5 | UI | UiInput | 250 | 750 | 750 | 1.000 | 1 | 46.4 | 92.5 | 108.7 | 108.7 | 0 |
| S6 | A | UsbPoller | 1000 | 3000 | 3000 | 1.000 | 1 | 19.5 | 77.6 | 95.8 | 99.7 | 0 |
| S6 | B | UsbPoller | 250 | 750 | 393 | 0.524 | 1 | 30.7 | 95.0 | 238.9 | 238.9 | 0 |
| S6 | UI | UiInput | 250 | 750 | 750 | 1.000 | 1 | 46.2 | 100.8 | 260.1 | 260.1 | 0 |
| S7 | A | UsbPoller | 1000 | 3000 | 3750 | 1.250 | 3 | 16.0 | 30.8 | 50.8 | 92.8 | 0 |
| S7 | A, 3-datagram reports to the last send | UsbPoller | 1000 | 375 | 1125 | 3.000 | 3 | 36.2 | 75.0 | 134.4 | 134.4 | 0 |

In S4 to S6 the fake satellite received the run's 2 pings, 150 mic frames and 6 battery
datagrams; in S5 and S6 it sent 303 to 305 rumble and 31 lightbar datagrams, of which the
receive loop handed on 301 and 30 before it stopped, and S6 rebound B's slot 7 times (B's
reports sent nothing while it was unbound, hence its 393 datagrams). An invocation about four
minutes earlier on the same machine had worst frames of 105 ms in S2, 13 ms in S3, 18 ms in S4
and 192 ms in S5 (stream A's median-run p99.9 3.3 to 5.7 ms) that neither the lock-statistics
run in between nor this repeat reproduced; its machine load was not recorded.

With the mic at 1000 frames a second (`--mic-hz 1000`, the same machine, load averaging 12%,
S4 to S6; S1 to S3 and S7 do not run the mic):

| scenario | stream | role | rate Hz | reports | datagrams | datagrams/report | max per report | p50 us | p99 us | p99.9 us | max us | over 1 ms |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| S4 | A | UsbPoller | 1000 | 3000 | 3000 | 1.000 | 1 | 16.4 | 68.3 | 94.8 | 112.2 | 0 |
| S4 | B | UsbPoller | 250 | 750 | 750 | 1.000 | 1 | 20.1 | 80.5 | 133.2 | 133.2 | 0 |
| S4 | UI | UiInput | 250 | 750 | 750 | 1.000 | 1 | 46.7 | 86.2 | 157.3 | 157.3 | 0 |
| S5 | A | UsbPoller | 1000 | 3000 | 3000 | 1.000 | 1 | 16.4 | 76.1 | 100.3 | 150.8 | 0 |
| S5 | B | UsbPoller | 250 | 750 | 750 | 1.000 | 1 | 25.1 | 85.9 | 234.3 | 234.3 | 0 |
| S5 | UI | UiInput | 250 | 750 | 750 | 1.000 | 1 | 47.8 | 91.4 | 181.2 | 181.2 | 0 |
| S6 | A | UsbPoller | 1000 | 3000 | 3000 | 1.000 | 1 | 17.9 | 68.2 | 91.9 | 96.9 | 0 |
| S6 | B | UsbPoller | 250 | 750 | 400 | 0.533 | 1 | 23.4 | 91.3 | 251.5 | 251.5 | 0 |
| S6 | UI | UiInput | 250 | 750 | 750 | 1.000 | 1 | 42.8 | 93.8 | 223.1 | 223.1 | 0 |

The locks of the reported runs of S1, S6 and S7 from one `bench_send_path_lockstats`
invocation with the same settings (so its runs are not the ones above), one row per lock and
thread role, leaving out the setup and teardown on the bench's own thread. Waits and holds in
microseconds.

| scenario | lock | role | acquisitions | contended | failed tries | max wait us | p99 wait us | max hold us |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| S1 | session.table | UsbPoller | 3000 | 0 | 0 | 0.2 | 0.1 | 0.4 |
| S1 | session.turn | UsbPoller | 3000 | 0 | 0 | 0.2 | 0.1 | 85.0 |
| S1 | input.slots | UsbPoller | 3000 | 0 | 0 | 0.3 | 0.1 | 12.6 |
| S1 | input.devices | UsbPoller | 3000 | 0 | 0 | 0.4 | 0.3 | 12.8 |
| S6 | session.table | UsbPoller | 3404 | 0 | 0 | 0.2 | 0.1 | 0.3 |
| S6 | session.table | UiInput | 750 | 0 | 0 | 0.2 | 0.1 | 0.3 |
| S6 | session.table | Receive | 333 | 0 | 0 | 0.2 | 0.3 | 1.2 |
| S6 | session.table | Binding | 7 | 0 | 0 | 0.1 | 0.1 | 0.2 |
| S6 | session.turn | UsbPoller | 3404 | 490 | 0 | 40.2 | 32.8 | 67.3 |
| S6 | session.turn | UiInput | 750 | 651 | 0 | 59.4 | 65.5 | 47.1 |
| S6 | session.turn | Heartbeat | 2 | 0 | 0 | 0.1 | 0.1 | 91.6 |
| S6 | session.turn | MicCapture | 150 | 0 | 0 | 0.2 | 0.1 | 30.1 |
| S6 | session.turn | Battery | 6 | 0 | 0 | 0.1 | 0.1 | 28.6 |
| S6 | session.turn | Binding | 7 | 0 | 0 | 0.4 | 0.5 | 60.4 |
| S6 | session.audio | MicCapture | 150 | 0 | 0 | 0.3 | 0.3 | 260.8 |
| S6 | input.slots | UsbPoller | 3750 | 1 | 0 | 3.1 | 0.1 | 40.6 |
| S6 | input.slots | UiInput | 750 | 0 | 0 | 0.3 | 0.3 | 59.7 |
| S6 | input.slots | Binding | 21 | 1 | 0 | 26.0 | 32.8 | 1.7 |
| S6 | input.devices | UsbPoller | 3750 | 753 | 0 | 64.3 | 65.5 | 40.9 |
| S6 | input.devices | UiInput | 750 | 691 | 0 | 55.2 | 65.5 | 60.3 |
| S6 | input.devices | Binding | 7 | 0 | 0 | 0.1 | 0.1 | 2.1 |
| S7 | session.table | UsbPoller | 3750 | 0 | 0 | 0.2 | 0.1 | 0.3 |
| S7 | session.turn | UsbPoller | 3750 | 0 | 0 | 0.2 | 0.1 | 95.0 |
| S7 | input.slots | UsbPoller | 3750 | 0 | 0 | 0.2 | 0.1 | 4.6 |
| S7 | input.devices | UsbPoller | 3000 | 0 | 0 | 0.3 | 0.3 | 2.3 |

Every report that is sent takes `input.devices`, `input.slots`, `session.table` and
`session.turn` (the lock laws in `lock_laws_test.cpp` list them as debts). Alone (S1, S7) none is
ever contended and the median report costs 16 to 18 us. With a second poller and the UI thread
on the same session, the frames that meet
wait for each other on the global `input.devices` and on `session.turn`: in S6 the UI thread
waited on the device lock in 691 of its 750 reports and on the turn in 651, up to 60 us each,
and its median report costs 46 us against A's 20. The mic thread holds `session.audio` for up to
0.26 ms across an Opus encode, which nothing on the input path shares.

### After: no lock on the input path, host numbers

The same bench after the optimisation commits (`7991ca80` to `74604c45`): sessions and slots in
fixed hot tables behind the send gate, one hold of the gate per URB, the bridge and speaker-audio
hand-offs on lock-free rings, a native receive thread per session and an output worker per USB
pad. Host numbers again; the phone run is still owed (below). Same machine and settings,
2026-11-02 09:41Z, with nothing else running (no compiler, CMake or Gradle process; CPU load 0 to
4%). "Before" here is the bench at `cdf665eb` run on the same machine at 2% load at the start of
this work, not the loaded run above.

| scenario | stream | role | reports | p50 us before | p50 us after | p99 us before | p99 us after | p99.9 us before | p99.9 us after | max us before | max us after |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| S1 | A | UsbPoller | 3000 | 16.0 | 16.1 | 30.7 | 30.7 | 54.1 | 57.6 | 126.8 | 86.7 |
| S2 | A | UsbPoller | 3000 | 16.3 | 16.6 | 51.8 | 58.0 | 71.8 | 79.5 | 113.1 | 191.2 |
| S2 | B | UsbPoller | 750 | 26.7 | 31.2 | 59.6 | 61.6 | 92.5 | 132.0 | 92.5 | 132.0 |
| S3 | A | UsbPoller | 3000 | 17.8 | 17.3 | 80.2 | 65.1 | 96.8 | 91.2 | 325.4 | 257.1 |
| S3 | B | UsbPoller | 750 | 39.1 | 32.1 | 91.5 | 85.0 | 120.7 | 129.8 | 120.7 | 129.8 |
| S3 | UI | UiInput | 750 | 50.7 | 40.9 | 95.3 | 85.4 | 137.0 | 151.3 | 137.0 | 151.3 |
| S4 | A | UsbPoller | 3000 | 16.2 | 16.1 | 69.0 | 72.6 | 86.7 | 93.9 | 150.0 | 148.3 |
| S4 | B | UsbPoller | 750 | 28.8 | 34.2 | 80.0 | 86.4 | 110.1 | 108.6 | 110.1 | 108.6 |
| S4 | UI | UiInput | 750 | 41.3 | 43.7 | 78.8 | 89.5 | 134.3 | 103.5 | 134.3 | 103.5 |
| S5 | A | UsbPoller | 3000 | 17.1 | 19.5 | 75.9 | 65.5 | 92.8 | 94.8 | 170.9 | 123.2 |
| S5 | B | UsbPoller | 750 | 38.8 | 29.1 | 92.1 | 74.0 | 102.5 | 100.6 | 102.5 | 100.6 |
| S5 | UI | UiInput | 750 | 46.6 | 42.4 | 94.5 | 80.6 | 123.9 | 98.2 | 123.9 | 98.2 |
| S6 | A | UsbPoller | 3000 | 15.7 | 16.7 | 56.6 | 58.9 | 83.4 | 81.3 | 104.9 | 102.6 |
| S6 | B | UsbPoller | 750 | 22.3 | 33.0 | 74.5 | 84.2 | 115.2 | 106.6 | 115.2 | 106.6 |
| S6 | UI | UiInput | 750 | 33.8 | 36.4 | 76.9 | 82.5 | 163.4 | 99.1 | 163.4 | 99.1 |
| S7 | A | UsbPoller | 3000 | 15.3 | 16.1 | 27.6 | 32.3 | 35.3 | 59.4 | 124.5 | 159.9 |
| S7 | A, 3-datagram reports to the last send | UsbPoller | 160 / 129 | 27.1 | 38.4 | 82.7 | 72.4 | 106.9 | 118.5 | 106.9 | 118.5 |

With the mic at 1000 frames a second (`--mic-hz 1000`, S4 to S6), before and after on the same
idle machine:

| scenario | stream | role | reports | p50 us before | p50 us after | p99 us before | p99 us after | p99.9 us before | p99.9 us after | max us before | max us after |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| S4 | A | UsbPoller | 3000 | 18.9 | 16.9 | 81.8 | 58.0 | 94.6 | 82.6 | 127.2 | 144.0 |
| S4 | B | UsbPoller | 750 | 27.9 | 22.3 | 92.2 | 71.4 | 98.8 | 200.9 | 98.8 | 200.9 |
| S4 | UI | UiInput | 750 | 50.1 | 43.5 | 103.1 | 82.2 | 149.2 | 188.5 | 149.2 | 188.5 |
| S5 | A | UsbPoller | 3000 | 17.5 | 16.7 | 77.4 | 59.5 | 102.8 | 78.9 | 122.9 | 98.9 |
| S5 | B | UsbPoller | 750 | 26.8 | 30.4 | 86.3 | 82.4 | 103.8 | 91.6 | 103.8 | 91.6 |
| S5 | UI | UiInput | 750 | 46.0 | 41.2 | 89.7 | 79.5 | 107.8 | 97.6 | 107.8 | 97.6 |
| S6 | A | UsbPoller | 3000 | 17.1 | 16.4 | 68.4 | 51.8 | 109.7 | 80.6 | 124.9 | 170.3 |
| S6 | B | UsbPoller | 750 | 19.9 | 29.5 | 85.2 | 74.4 | 96.4 | 209.2 | 96.4 | 209.2 |
| S6 | UI | UiInput | 750 | 39.6 | 34.3 | 81.3 | 78.4 | 148.1 | 201.1 | 148.1 | 201.1 |

The locks of the reported runs of S1, S6 and S7 from one `bench_send_path_lockstats` invocation
with the same settings. Every lock left belongs to a thread off the input path: open and close
(`session.table`, on the bench's own thread), the mic encoder (`session.audio`) and the binding
thread (`input.bindings`).

| scenario | lock | role | acquisitions | contended | failed tries | max wait us | p99 wait us | max hold us |
| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| S1 | session.table | Other | 2 | 0 | 0 | 0.1 | 0.1 | 0.2 |
| S1 | session.audio | Other | 1 | 0 | 0 | 0.0 | 0.0 | 0.3 |
| S6 | session.table | Other | 2 | 0 | 0 | 0.2 | 0.3 | 0.2 |
| S6 | session.audio | MicCapture | 150 | 0 | 0 | 0.6 | 0.5 | 293.2 |
| S6 | session.audio | Other | 1 | 0 | 0 | 0.0 | 0.0 | 11.0 |
| S6 | input.bindings | Binding | 14 | 0 | 0 | 0.2 | 0.3 | 0.5 |
| S7 | session.table | Other | 2 | 0 | 0 | 0.1 | 0.1 | 0.3 |
| S7 | session.audio | Other | 1 | 0 | 0 | 0.1 | 0.1 | 0.1 |

No input thread takes a lock any more, and the lock laws (`lock_laws_test.cpp`) now list nothing
for the USB pollers, the UI thread and the receive thread, nothing held across a blocking call and
no nesting. In S6 the UI thread no longer waits on the device lock or a send turn, as it did in
691 and 651 of its 750 reports before. On this host that shows mostly where frames meet: the UI
thread's median falls by 4 to 10 us in S3 and S5 and most p99s fall, most clearly with the mic at
1000 frames a second, while a lone stream's median stays at 16 to 17 us, the seal plus a loopback
`sendto` that dominates the path. Between runs the 250 Hz streams' medians and every stream's
tail move by 10 us or more either way (S2 B and S6 B here), so single differences of that size are
noise. The S7 three-datagram row only counts the reports whose motion and touch both fall due (78
to 375 of them, depending on the run): three runs after read 38.4, 34.9 and 35.1 us at p50
against 27.1 here and 36.2 in the loaded run above, which a paired rerun on an idle machine should
settle.

The device run is still owed. Take stage 1 as in "Running it" above, with a DualSense in
USB-direct mode (each of its URBs carries a report, motion and touch), on a build from before
these commits and on one from after, about 30 s each while a second pad or the on-screen pad also
streams, and compare `stage1_hotpath_us`.
