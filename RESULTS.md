# Results: WebSocket PING during a slow drain → tail loss

860 runs in total. The raw data is in `results/`:
- `results/results.csv`: one row per run.
- `results/summary.md`: the generated tables.
- `results/raw/<exp>/<variant>/run-NN/`: logs, pcap event timelines, and full pcaps for runs 01–02.

## Environment (what was actually run)

| item | value |
|---|---|
| OS | Linux 6.18 (Ubuntu 24.04 container), loopback only |
| Java | OpenJDK 17.0.20 (`--release 17`) |
| Jetty | 12.0.16, server and client (pinned) |
| nginx | **1.24.0 (Ubuntu package)**. 1.28.x could not be installed: nginx.org and GitHub source downloads are blocked by this environment's network policy. |
| capture | tcpdump 4.99.4 on `lo`, covering both legs (client↔nginx and nginx↔server) |
| defaults | 1 MiB payload (+4-byte `OKAY`), sink 50,000 B/s (~21 s drain), heartbeat 20 s, server buffers 15 MiB, client Callback never completed, server binary Callback never completed, 512-byte messages with `isLast=true`. 20 runs per variant, 8 in parallel. |

**Not run, stated explicitly:**
- **Windows client and Windows nginx.** `run-matrix.ps1` is written but has never been executed. No Windows machine was available.
- **~20 ms RTT (`tc netem`).** The kernel has no netem qdisc (`Error: Specified qdisc kind is unknown`), so everything ran over plain loopback (RTT well under 1 ms).
- **nginx 1.28.x.** Not available here (see above).

## Short answer

**nginx turns the late PING into data loss. Jetty doesn't, and neither does the client's code.**

1. After writing its Close(1000) frame, Jetty 12.0.16 calls `shutdownOutput()`, which sends a TCP FIN. This happens in
   `FrameFlusher`, unconditionally for every CLOSE frame.
2. nginx sees end-of-stream from upstream. Once its own userspace buffer (`proxy_buffer_size` 128k) has been
   handed to the kernel, it finalizes the upgraded request and `close()`s **both** sockets.
   - With 1 MiB this happens within 10 ms of the server's FIN in all 380 nginx runs (median ~76 µs).
   - With 4 MiB it happens 1–25 s later, once the userspace buffer has drained.

   See `pcap-events.txt` in each run directory.
   Up to several hundred KB for the slow client are still queued in the kernel send buffer of nginx's client-side
   socket, which is now orphaned.
3. The client is still draining, so it hasn't seen the Close yet, and its heartbeat sends a PING. The PING reaches
   a socket nginx has closed. The kernel answers with a **TCP RST** (median 0.037 ms after the PING, range
   0.017–0.137 ms, across 176 runs) and **discards the unsent tail**.
4. The client application then gets only the bytes already in its own receive buffer. Jetty's `fill()` turns the
   `ECONNRESET` into EOF, so the listener sees `onWebSocketError(ClosedChannelException)` from
   `WebSocketSessionState.onEof`, followed by `onWebSocketClose(1006)`. The data is truncated on a 512-byte
   message boundary: all 177 truncated runs end on a multiple of 512. This matches the production symptom exactly.

Across all runs whose loss or RST was triggered by a PING, what decides the outcome is whether nginx still had unsent data:

| when nginx's RST was triggered by the PING | complete | truncated |
|---|---|---|
| after nginx had already sent its FIN, i.e. all data had left nginx | **120** | 0 |
| while nginx still had unsent data queued (no FIN sent yet) | 0 | **176** |

No run lost data without an RST from nginx right after a client PING.

## Answers

### 1. Does a PING during the drain cause tail loss with real Jetty 12.0.16?

- **Without nginx: no.** Every direct variant delivered every byte: E1, E2, E6 direct-P1/P1early/P2/P3, the E7
  direct variants, E9 and E10 direct-hbOn-4MiB. That is 280/280 direct download runs, all clean 1000/1000
  closes with no RST. On a direct connection Jetty half-closes after its Close frame and keeps
  reading. A late PING is read, its automatic PONG fails quietly because output is already closed (Jetty ignores
  that failure by design), and the close handshake completes once the client has drained.
- **With nginx: yes.**

  | experiment | complete |
  |---|---|
  | E4, 20 s heartbeat, 1 MiB | **9/20** |
  | E6 nginx-P2, one PING right after the server's Close | **0/20** (got 106–152 KB of 1,048,580 B) |
  | E10 nginx-hbOn-4MiB | **0/20** (got 1.05–2.12 MB of 4.19 MB) |
  | E3, no heartbeat (baseline) | 20/20 |

  E4 is borderline at 1 MiB. With a 21 s drain and a 20 s PING, the PING often arrives just after nginx has
  already sent everything.
- **Phase matters exactly as the mechanism predicts:**
  - **P1early** (PING before the server's `close()`; 20/20 verified from timestamps): 20/20 complete via nginx.
    nginx forwards the PING upstream and the PONG comes back.
  - **P2** (after the server's Close frame, while the client is still draining): 0/20 complete.
  - **P3** (after the client has received the Close frame): 20/20 complete, because nothing is left in flight.

  The original E6 "P1" at 10% of the payload was usually *not* before `close()`. The server had already
  finished writing, because the remaining data fits in socket and nginx buffers. Only 1/20 was valid, and that
  one was complete; the other 19 behaved like P2. That's why the `P1early` variants (0.5%) were added.

### 2. Who emits the RST, and when?

- **Always nginx's kernel, on the client leg** (`nginx(client-leg)` in `rstSender`). It is triggered by the client's
  PING frame arriving at nginx's already-closed client socket: 0.017–0.137 ms after the PING, and 0.005–39 s after
  the server's Close frame.
- **The server never sends an RST.** It always sends Close(1000) and then a FIN.
- **nginx never forwards the late PING upstream.** It has already FIN'd and closed the upstream connection. That is
  why the server always logs `onWebSocketClose 1006` + `ClosedChannelException` via nginx, even in runs where the
  client got everything.
- **Harmless RSTs also appear** in E3, E6 nginx-P3 and similar runs. There, nginx resets the client's *Close reply*
  after all data has already arrived. That RST loses nothing, but it shows that *any* client→nginx bytes after
  nginx's close provoke an RST.
- **One CSV row is mislabeled.** In `E4/nginx-hbOn/run-18`, `rstDetail` wrongly names an RST at t=0 as the
  "first RST". That packet came from the runner's readiness probe, which reused the same ephemeral port. The
  run's real RST is at +21.4607 s, 0.035 ms after the PING (see its `pcap-events.txt`). The analyzer was fixed
  afterwards to ignore packets before the WebSocket upgrade. That run's pcap was not kept, so its row was not
  regenerated.

### 3. Does `lingering_close` fix it? Does any server-side Jetty setting fix it?

- **`lingering_close always` alone: no.** E5 is 10/20 complete, the same as without it.
  - nginx half-closes and keeps discarding client input only until `lingering_timeout` (default 5 s) passes
    without data from the client, capped by `lingering_time` (default 30 s). After that it `close()`s, and a PING
    20 s later gets the same RST: 17 s after the Close frame in the failing runs.
  - It does help when the PING arrives inside that window: E10 nginx-linger-P2 (PING right after the Close) was
    20/20.
- **`lingering_close always; lingering_time 3600s; lingering_timeout 3600s;`: yes.** E10 nginx-lingerLong-hbOn was
  20/20 and nginx-lingerLong-P2 was 20/20, with **no RST at all**. Both timeouts must exceed the longest drain and
  PING interval.
- **Jetty server settings: none helps.**

  | E7 variant | direct | nginx, 20 s heartbeat | nginx-P2 |
  |---|---|---|---|
  | Jetty default input/output buffers | 20/20 | 14/20 | 0/20 |
  | 64 KiB output buffer | 20/20 | 15/20 | 0/20 |
  | server completes its binary Callback (E10) | – | – | 0/20 |
  | client Callback completed, variant B (for comparison) | 20/20 | 14/20 | 0/20 |

  In the nginx 20 s heartbeat column, the variants' 14–15/20 against E4's 9/20 is run-to-run variation of the
  same race, not a fix; the P2 column is 0/20 in every variant. The FIN after the Close frame is hard-coded in
  Jetty 12.0.16 (`FrameFlusher` calls `endPoint.shutdownOutput()` for CLOSE) and has no configuration switch.
- **One non-fix that looks like a fix.** Sending the whole download as **one fragmented message**
  (`isLast=false`, E10 nginx-P2-srvFragmented) was 20/20. That only works because the client's Jetty keeps
  reading fragments into memory without calling the application, so nothing is left in flight when the RST
  arrives. It is limited by `maxBinaryMessageSize` and memory, so it is not a real fix.

### 4. Does suppressing the client PING while draining or closing fix it?

**Yes.** The `smart` heartbeat skips the PING if a data message arrived within the last heartbeat interval, or
once the Close frame has been received.

| variant | complete | normal heartbeat, same payload |
|---|---|---|
| E10 nginx-hbSmart (1 MiB) | 20/20 | 9/20 |
| E10 nginx-hbSmart-4MiB | 20/20 | 0/20 |

In the smart runs the only RSTs are nginx answering the client's own Close reply, after all data has arrived.

### 5. E8 and E9

**E8, upload integrity (5 MiB in 65,544-byte messages, direct):**

| server `inputBufferSize` | server Callback never completed | completed |
|---|---|---|
| 15 MiB | **20/20 corrupted** (2–3 messages, ~60–120 KB bad per run) | **20/20 corrupted** |
| 65,544 B | **20/20 corrupted** (79 of 80 messages) | **20/20 corrupted** |
| 64 KiB | 20/20 intact | 20/20 intact |
| Jetty default | 20/20 intact | 20/20 intact |

- **The corruption claim is confirmed for Jetty 12.0.16.** Any `inputBufferSize` above 64 KiB corrupts
  client→server binary payloads. An ad-hoc sweep (not in the CSV) gave the same picture: 32 KiB and 64 KiB were
  clean; 65,544 B, 128 KiB, 256 KiB, 1 MiB, 4 MiB and 15 MiB all corrupted. Smaller buffers corrupt more
  messages.
- **Root cause, from reading the Jetty 12.0.16 sources:**
  - `ArrayByteBufferPool` pools buffers only up to its max capacity, 64 KiB by default. Larger requests get
    `RetainableByteBuffer.wrap(...)`, a `NonRetainableByteBuffer` whose `retain()` does nothing and whose
    `isRetained()` always returns `false`.
  - `WebSocketConnection.fillAndParse()` relies on `isRetained()` to avoid refilling a buffer that frames still
    reference. With these buffers it refills in place while earlier frame and fragment payloads are still in use.
  - This affects **only data received by the server**. In the download direction the server receives only PINGs,
    and E7's buffer variants show no effect there.
- **The uncompleted server Callback does not stall reads.** With `autoDemand`, Jetty demands the next frame right
  after the method returns, and the upload completed in all 80 of those runs. It only keeps buffers referenced.

**E9, server send timeout:**
- **Setup:** 256 KiB payload, client `SO_RCVBUF` 8 KiB, server `SO_SNDBUF` 8 KiB, and a sink that stalls for 45 s
  after 64 KiB.
- **Result:** every run (20/20) had one send whose callback didn't complete within 30 s (`send-timeout seq=160`),
  and the producer carried on as production does. **No data was lost or reordered:** 20/20 complete, clean
  1000/1000 closes.
- **Why:** Jetty's `FrameFlusher` just queues the next frames behind the pending write. Its own write timeout is the
  1-hour idle timeout. Ignoring the 30 s latch timeout therefore does not drop data in 12.0.16.

## Recommendations

In order of preference:

1. **Client:** don't send a heartbeat PING while data is arriving or after the Close frame has been received. This
   is the `smart` mode. If a PING fails, don't treat it as a reason to fail the transfer.
2. **nginx:** for the WebSocket location, use `lingering_close always;` with `lingering_time` and
   `lingering_timeout` longer than the longest expected drain and heartbeat interval. Note that this keeps
   connections around longer.
3. **Protocol:** end the stream with an application-level "end" message and let the client initiate the close.
   The server would then not half-close while the client is still draining. This is an untested suggestion; it
   was not implemented here.
4. **Separate from this bug:** keep the server `inputBufferSize` ≤ 64 KiB, or configure a pool whose max capacity
   is at least the input buffer size. With 15 MiB, uploads can be silently corrupted (E8).

**The Windows client may lose more than Linux does.** Here, bytes already in the Linux client's receive buffer
survived the RST, so only what nginx still held was lost. Windows is commonly documented to discard unread
received data when an RST arrives, so a Windows client could lose more. That was not verified here.
`run-matrix.ps1` exists to check it.

## Per-experiment tables (generated from `results/results.csv`)

`complete` means every byte arrived and the pattern verified. Lost bytes in these experiments were always a
truncated tail; no gap or reorder was ever seen in the download direction. Corruption was seen only in E8. The
`rstDetail` column in the CSV gives the timing of each RST relative to the server's Close frame and the client's
PING.

#### E1

| variant | complete | delivered (min–max) | client close | client exception | server close | RST runs (sender) | PING phase valid |
|---|---|---|---|---|---|---|---|
| direct-hbOff | **20/20** | 1,048,580 | 1000×20 | -×20 | 1000×20 | 0 |  |

#### E2

| variant | complete | delivered (min–max) | client close | client exception | server close | RST runs (sender) | PING phase valid |
|---|---|---|---|---|---|---|---|
| direct-hbOn | **20/20** | 1,048,580 | 1000×20 | -×20 | 1000×20 | 0 |  |

#### E3

| variant | complete | delivered (min–max) | client close | client exception | server close | RST runs (sender) | PING phase valid |
|---|---|---|---|---|---|---|---|
| nginx-hbOff | **20/20** | 1,048,580 | 1000×20 | -×20 | 1006×20 | 17 (nginx(client-leg)) |  |

#### E4

| variant | complete | delivered (min–max) | client close | client exception | server close | RST runs (sender) | PING phase valid |
|---|---|---|---|---|---|---|---|
| nginx-hbOn | **9/20** | 996,352–1,048,580 | 1000×9, 1006×11 | -×9, ClosedChannelExc.×11 | 1006×20 | 20 (nginx(client-leg)) |  |

#### E5

| variant | complete | delivered (min–max) | client close | client exception | server close | RST runs (sender) | PING phase valid |
|---|---|---|---|---|---|---|---|
| nginx-linger-hbOn | **10/20** | 1,006,592–1,048,580 | 1000×10, 1006×10 | -×10, ClosedChannelExc.×10 | 1006×20 | 20 (nginx(client-leg)) |  |

#### E6

| variant | complete | delivered (min–max) | client close | client exception | server close | RST runs (sender) | PING phase valid |
|---|---|---|---|---|---|---|---|
| direct-P1 | **20/20** | 1,048,580 | 1000×20 | -×20 | 1000×20 | 0 | 1/20 |
| direct-P1early | **20/20** | 1,048,580 | 1000×20 | -×20 | 1000×20 | 0 | 20/20 |
| direct-P2 | **20/20** | 1,048,580 | 1000×20 | -×20 | 1000×20 | 0 | 20/20 |
| direct-P3 | **20/20** | 1,048,580 | 1000×20 | -×20 | 1000×20 | 0 | 20/20 |
| nginx-P1 | **1/20** | 180,736–1,048,580 | 1000×1, 1006×19 | -×1, ClosedChannelExc.×19 | 1006×20 | 19 (nginx(client-leg)) | 1/20 |
| nginx-P1early | **20/20** | 1,048,580 | 1000×20 | -×20 | 1006×20 | 17 (nginx(client-leg)) | 20/20 |
| nginx-P2 | **0/20** | 106,496–152,064 | 1006×20 | ClosedChannelExc.×20 | 1006×20 | 20 (nginx(client-leg)) | 20/20 |
| nginx-P3 | **20/20** | 1,048,580 | 1000×20 | -×20 | 1006×20 | 17 (nginx(client-leg)) | 20/20 |

#### E7

| variant | complete | delivered (min–max) | client close | client exception | server close | RST runs (sender) | PING phase valid |
|---|---|---|---|---|---|---|---|
| E2-clientCbB | **20/20** | 1,048,580 | 1000×20 | -×20 | 1000×20 | 0 |  |
| E2-srvDefaultBufs | **20/20** | 1,048,580 | 1000×20 | -×20 | 1000×20 | 0 |  |
| E2-srvOut64k | **20/20** | 1,048,580 | 1000×20 | -×20 | 1000×20 | 0 |  |
| E4-clientCbB | **14/20** | 1,008,640–1,048,580 | 1000×14, 1006×6 | -×14, ClosedChannelExc.×6 | 1006×20 | 20 (nginx(client-leg)) |  |
| E4-srvDefaultBufs | **14/20** | 1,006,592–1,048,580 | 1000×14, 1006×6 | -×14, ClosedChannelExc.×6 | 1006×20 | 20 (nginx(client-leg)) |  |
| E4-srvOut64k | **15/20** | 1,009,664–1,048,580 | 1000×15, 1006×5 | -×15, ClosedChannelExc.×5 | 1006×20 | 20 (nginx(client-leg)) |  |
| E6P2-direct-clientCbB | **20/20** | 1,048,580 | 1000×20 | -×20 | 1000×20 | 0 | 20/20 |
| E6P2-direct-srvDefaultBufs | **20/20** | 1,048,580 | 1000×20 | -×20 | 1000×20 | 0 | 20/20 |
| E6P2-direct-srvOut64k | **20/20** | 1,048,580 | 1000×20 | -×20 | 1000×20 | 0 | 20/20 |
| E6P2-nginx-clientCbB | **0/20** | 110,080–230,912 | 1006×20 | ClosedChannelExc.×20 | 1006×20 | 20 (nginx(client-leg)) | 20/20 |
| E6P2-nginx-srvDefaultBufs | **0/20** | 112,128–164,352 | 1006×20 | ClosedChannelExc.×20 | 1006×20 | 20 (nginx(client-leg)) | 20/20 |
| E6P2-nginx-srvOut64k | **0/20** | 109,056–208,896 | 1006×20 | ClosedChannelExc.×20 | 1006×20 | 20 (nginx(client-leg)) | 20/20 |

#### E8

| variant | complete | delivered (min–max) | client close | client exception | server close | RST runs (sender) | PING phase valid |
|---|---|---|---|---|---|---|---|
| upload-in15MiB-srvCbDone | **0/20** | 5,242,880 (**20 corrupt**) | 1000×20 | -×20 | 1000×20 | 0 |  |
| upload-in15MiB-srvCbNever | **0/20** | 5,242,880 (**20 corrupt**) | 1000×20 | -×20 | 1000×20 | 0 |  |
| upload-in64KiB-srvCbDone | **20/20** | 5,242,880 | 1000×20 | -×20 | 1000×20 | 0 |  |
| upload-in64KiB-srvCbNever | **20/20** | 5,242,880 | 1000×20 | -×20 | 1000×20 | 0 |  |
| upload-in65544-srvCbDone | **0/20** | 5,242,880 (**20 corrupt**) | 1000×20 | -×20 | 1000×20 | 0 |  |
| upload-in65544-srvCbNever | **0/20** | 5,242,880 (**20 corrupt**) | 1000×20 | -×20 | 1000×20 | 0 |  |
| upload-inDefault-srvCbDone | **20/20** | 5,242,880 | 1000×20 | -×20 | 1000×20 | 0 |  |
| upload-inDefault-srvCbNever | **20/20** | 5,242,880 | 1000×20 | -×20 | 1000×20 | 0 |  |

#### E9

| variant | complete | delivered (min–max) | client close | client exception | server close | RST runs (sender) | PING phase valid |
|---|---|---|---|---|---|---|---|
| direct-stall45s | **20/20** | 262,148 | 1000×20 | -×20 | 1000×20 | 0 |  |

#### E10

| variant | complete | delivered (min–max) | client close | client exception | server close | RST runs (sender) | PING phase valid |
|---|---|---|---|---|---|---|---|
| direct-hbOn-4MiB | **20/20** | 4,194,308 | 1000×20 | -×20 | 1000×20 | 0 |  |
| nginx-P2-srvCbDone | **0/20** | 90,624–312,320 | 1006×20 | ClosedChannelExc.×20 | 1006×20 | 20 (nginx(client-leg)) | 20/20 |
| nginx-P2-srvFragmented | **20/20** | 1,048,580 | 1000×20 | -×20 | 1006×20 | 20 (nginx(client-leg)) | 20/20 |
| nginx-hbOn-4MiB | **0/20** | 1,052,672–2,124,288 | 1006×20 | ClosedChannelExc.×20 | 1006×20 | 20 (nginx(client-leg)) |  |
| nginx-hbSmart | **20/20** | 1,048,580 | 1000×20 | -×20 | 1006×20 | 16 (nginx(client-leg)) |  |
| nginx-hbSmart-4MiB | **20/20** | 4,194,308 | 1000×20 | -×20 | 1006×20 | 18 (nginx(client-leg)) |  |
| nginx-linger-P2 | **20/20** | 1,048,580 | 1000×20 | -×20 | 1006×20 | 19 (nginx(client-leg)) | 20/20 |
| nginx-lingerLong-P2 | **20/20** | 1,048,580 | 1000×20 | -×20 | 1006×20 | 0 | 20/20 |
| nginx-lingerLong-hbOn | **20/20** | 1,048,580 | 1000×20 | -×20 | 1006×20 | 0 |  |
