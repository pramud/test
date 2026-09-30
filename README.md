# WebSocket PING / tail-loss reproduction harness

This harness reproduces a problem where a WebSocket download loses the tail of its data when the client sends a
PING while it is still slowly draining the response. Everything is written from scratch: Java 17, Jetty 12.0.16,
slf4j 2.0.16 and Maven, with all versions pinned in `pom.xml`. The findings are in [RESULTS.md](RESULTS.md).

| module | what it is |
|---|---|
| `server` | "Connector mimic". Jetty 12.0.16 ee10 `JettyWebSocketServlet` with an annotated `@WebSocket` endpoint. `/stream?bytes=N` sends `OKAY` followed by N bytes of a running 64-bit counter, in 512-byte `sendPartialBinary(chunk, isLast, cb)` calls. It waits up to 30 s for each callback and ignores the result, then calls `close(1000, "Normal closure", cb)`. `/upload?bytes=N` verifies an upload. |
| `client` | "DevAccess mimic". Jetty 12.0.16 `WebSocketClient` with a `Session.Listener.AutoDemanding` listener. Each message is written synchronously to a throttled sink, and the Callback is never completed (variant A; variant B completes it). A heartbeat thread sends a synchronous PING (waits for the callback, up to 30 s). Heartbeat modes: `off`, `on`, `smart` (skips the PING while data is flowing) and `forced` (one PING at phase P1, P2 or P3). |
| `runner` | Runs the experiment matrix. It starts tcpdump, the server JVM, nginx and the client JVM for every run, parses the pcap without any library, and writes `results/`. |
| `common` | Logger (epoch µs + `nanoTime`), pattern generator/verifier, argument parser. |

## Running

```
./run-matrix.sh                                  # build if needed, then E1..E10 with 20 runs each
./run-matrix.sh --exp=E6 --runs=5 --parallel=4
./run-matrix.sh --exp=custom --proxy=on --lingering=always --heartbeat=forced --ping-phase=P2 \
                --bytes=4m --sink-rate=20000 --input-buffer=default --output-buffer=64k
./run-matrix.sh --summarize                      # rebuild results/results.csv and summary.md from results/csv
```

On Windows, use the same options with `.\run-matrix.ps1 --nginx=C:\path\to\nginx.exe ...`. Packet capture is off by
default there; you can pass a tcpdump-compatible tool with `--tcpdump=... --capture-if=...`.

Runner options (defaults in brackets):

- **Matrix:** `--exp` (all \| E1,E2,… \| custom), `--runs` [20], `--parallel` [8]
- **Download:** `--bytes` [1m], `--sink-rate` in bytes/s [50000], `--heartbeat-interval-ms` [20000], `--p1-fraction` [0.1]
- **Jetty behaviour:** `--input-buffer` / `--output-buffer` [15m; `default` = Jetty default], `--client-callback` [false], `--server-callback` [false], `--last-per-chunk` [true]
- **custom only:** `--proxy` [off], `--lingering` [off \| always \| long], `--heartbeat` [on], `--ping-phase`, `--recv-buffer`, `--send-buffer`, `--stall-at`, `--stall-ms`, `--sink-delay-ms`, `--name`
- **Tools:** `--nginx` [nginx], `--tcpdump` [tcpdump; `off` on Windows], `--capture-if` [lo], `--keep-full` [2], `--pcap-snap` [128]

You can also start the server and client by hand. Their options are documented at the top of
`StreamServer.java` and `StreamClient.java`:

```
java -jar server/target/server.jar --port=18080 --dir=/tmp/run
java -jar client/target/client.jar --url=ws://127.0.0.1:18080/stream --bytes=1m --heartbeat=on --dir=/tmp/run
```

## Output

- `results/results.csv` has one row per run. The requested columns come first, followed by `complete`, `pingsSent`,
  `pingPhase`, `pingResults`, `serverSendTimeouts`, `serverCloseCallback`, `clientLegDownBytes`, `rstDetail` and
  `notes`.
- `results/summary.md` has the generated per-experiment tables. `results/csv/<exp>.csv` holds the same rows,
  split per experiment.
- `results/raw/<exp>/<variant>/run-NN/` contains:
  - `client.log`, `server.log.gz`, `*-result.properties`, `commands.txt`
  - `pcap-events.txt`: the Close frames, PINGs, FINs and RSTs for each leg, with times relative to the SYN
  - for proxied runs: `nginx.conf` and the nginx `logs/`

  Runs 01 and 02 of every variant also keep `capture.pcap.gz` (headers plus the first bytes of each packet) and the
  server log with every send-start and send-complete line. Later runs drop those two to keep the repo small.
