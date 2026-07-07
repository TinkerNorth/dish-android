# dish device-side latency benchmark (stages 0–2)

On-device instrumentation for the half of the chain that lives on the phone. Pairs
with the PC-side `dish_bench` (satellite `tools/bench/`) which covers stages 3–4 and
the cakama3a-equivalent poll cadence. Full chain and methodology: see that README.

| # | Stage | Source |
|---|-------|--------|
| 0 | Controller + USB poll interval | `SatelliteNative.getDeviceUrbCount(id)` sampled over time (already wired via `PollRateSampler` / `InputRateStore`) |
| 1 | Android hot path: URB reap → parse → encrypt → `sendto` | `hotpath_latency` (this change) |
| 2 | Network one-way ≈ ½ heartbeat RTT | `hotpath_latency` (this change) |

## How the instrumentation works

`app/src/main/cpp/hotpath_latency.{h,cpp}` is **off by default**; when disabled each
mark is a single relaxed atomic load, so the streaming path is unchanged. Hooks:

- `markInputRead()` — `usb_host.cpp`, at URB reap (per-device poller thread).
- `markGamepadSent()` — `satellite_jni.cpp` `publishIfChanged`, right after the
  `MSG_GAMEPAD_DATA` packet leaves `sendto()`. Stage 1 = this Δ on one thread.
- `markPingSent()` / `markAckReceived()` — `satellite_jni.cpp` heartbeat send + ack
  receive. Stage 2 RTT measured on the device clock; one-way ≈ RTT/2.

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
> number, lower `HEARTBEAT_INTERVAL_SEC` temporarily.
>
> Measure **while input is streaming**. On an otherwise idle link, Wi-Fi power save parks
> the phone's radio between the 2 s heartbeats and each ping waits out the wakeup cycle,
> so an idle reading of tens of ms is the radio's doze schedule, not the latency games
> see. Steady gamepad traffic keeps the radio awake and the same path reads a few ms.
> Arming the bench clears the sample window, so old idle samples never pollute a run.
