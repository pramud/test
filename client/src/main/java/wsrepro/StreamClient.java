package wsrepro;

import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.WebSocketClient;

/**
 * "DevAccess mimic": downloads /stream?bytes=N and writes every message synchronously to a slow sink,
 * or uploads N bytes to /upload in fixed-size binary messages.
 *
 * <pre>
 * --url=ws://127.0.0.1:P/stream   endpoint (bytes=N is appended)
 * --mode=download|upload
 * --bytes=1m                      pattern bytes requested (download adds the 4-byte OKAY prefix)
 * --sink-rate=50000               slow sink throughput in bytes/s (0 = unthrottled)
 * --sink-delay-ms=0               extra fixed delay per message
 * --stall-at=-1 --stall-ms=0      sink stalls once for stall-ms after stall-at bytes (E9)
 * --complete-callback=false       variant B: complete the onWebSocketBinary Callback
 * --heartbeat=off|on|smart|forced on: PING every interval; smart: skip PING while data flows or closing
 * --heartbeat-interval-ms=20000
 * --ping-phase=P1|P2|P3           forced mode: P1 mid-drain before server close, P2 after server Close
 *                                 frame was written, P3 after the client received the Close frame
 * --p1-fraction=0.1               forced P1: fraction of the payload delivered before the PING
 * --recv-buffer=0                 client SO_RCVBUF (0 = OS default)
 * --upload-message=65544          upload message size
 * --timeout-ms=600000             give up after this long
 * --dir=.                         run directory (server flag files, sink file, client-result.properties)
 * </pre>
 */
public class StreamClient
{
    static final long MIB = 1024 * 1024;

    public static void main(String[] argv) throws Exception
    {
        Args args = new Args(argv);
        Log.log("client args %s", args.all());
        Path dir = Path.of(args.str("dir", "."));
        ResultFile result = new ResultFile(dir.resolve("client-result.properties"));
        String mode = args.str("mode", "download");
        long bytes = args.num("bytes", MIB);

        ClientConnector connector = new ClientConnector();
        long rcvBuf = args.num("recv-buffer", 0);
        if (rcvBuf > 0)
            connector.setReceiveBufferSize((int)rcvBuf);
        HttpClient http = new HttpClient(new HttpClientTransportOverHTTP(connector));
        WebSocketClient client = new WebSocketClient(http);
        client.setMaxBinaryMessageSize(16 * MIB);
        client.setIdleTimeout(Duration.ofHours(1));
        client.start();

        long start = System.nanoTime();
        int exit;
        try
        {
            URI uri = URI.create(args.str("url", "ws://127.0.0.1:8080/stream") + "?bytes=" + bytes);
            if (mode.equals("upload"))
                exit = upload(client, uri, args, bytes, result);
            else
                exit = download(client, uri, args, bytes, dir, result);
        }
        catch (Throwable t)
        {
            Log.log("client failure %s", Log.stack(t));
            result.putIfAbsent("exception", Log.brief(t));
            exit = 2;
        }
        result.put("durationMs", (System.nanoTime() - start) / 1_000_000);
        client.stop();
        http.stop();
        Log.log("client exit %d", exit);
        System.exit(exit);
    }

    static int download(WebSocketClient client, URI uri, Args args, long bytes, Path dir, ResultFile result) throws Exception
    {
        long expected = bytes + Pattern.PREFIX.length;
        result.put("bytesExpected", expected);
        Path sinkPath = dir.resolve("sink.bin");
        try (OutputStream out = Files.newOutputStream(sinkPath))
        {
            SlowSink sink = new SlowSink(out, args);
            DownloadListener listener = new DownloadListener(sink, args, dir, expected, result);
            // The connect future may only complete once the (slow) read loop yields, so do not wait on it;
            // the heartbeat is started from onWebSocketOpen instead.
            client.connect(listener, uri).whenComplete((session, failure) ->
            {
                if (failure != null)
                {
                    Log.log("connect failed %s", Log.stack(failure));
                    result.putIfAbsent("exception", "connect: " + Log.brief(failure));
                    listener.closed.countDown();
                }
            });
            boolean done = listener.closed.await(args.num("timeout-ms", 600_000), TimeUnit.MILLISECONDS);
            if (!done)
            {
                Log.log("TIMEOUT waiting for close; delivered=%d", listener.verifier.bytes());
                result.put("exception", "client timeout");
            }
            if (listener.heartbeat != null)
            {
                // Let a PING triggered by the close (phase P3) complete; the heartbeat exits on its own once closed.
                listener.heartbeat.join(35_000);
                listener.heartbeat.interrupt();
            }
            // Let Jetty flush the Close response before stopping.
            Thread.sleep(500);
        }
        Files.deleteIfExists(sinkPath);
        return 0;
    }

    static int upload(WebSocketClient client, URI uri, Args args, long bytes, ResultFile result) throws Exception
    {
        int msgSize = (int)args.num("upload-message", 65544);
        UploadListener listener = new UploadListener(result);
        CountDownLatch closed = listener.closed;
        Session session = client.connect(listener, uri).get(30, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(args.num("timeout-ms", 120_000));
        long sent = 0;
        int n = 0;
        while (sent < bytes)
        {
            byte[] b = new byte[(int)Math.min(msgSize, bytes - sent)];
            Pattern.fill(b, sent, false);
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            session.sendBinary(ByteBuffer.wrap(b), Callback.from(latch::countDown, x ->
            {
                failure.set(x);
                latch.countDown();
            }));
            long left = deadline - System.nanoTime();
            if (left <= 0 || !latch.await(left, TimeUnit.NANOSECONDS))
            {
                Log.log("upload send #%d timed out (sent=%d)", n, sent);
                result.put("exception", "upload send timeout after " + sent + " bytes");
                break;
            }
            if (failure.get() != null)
            {
                Log.log("upload send #%d failed %s", n, Log.stack(failure.get()));
                result.put("exception", Log.brief(failure.get()));
                break;
            }
            sent += b.length;
            n++;
            Log.log("upload sent #%d len=%d total=%d", n, b.length, sent);
        }
        result.put("bytesSent", sent);
        session.close(1000, "upload done", Callback.NOOP);
        if (!closed.await(Math.max(1, (deadline - System.nanoTime()) / 1_000_000), TimeUnit.MILLISECONDS))
        {
            Log.log("TIMEOUT waiting for close after upload");
            result.putIfAbsent("exception", "client timeout waiting for close");
        }
        Thread.sleep(500);
        return 0;
    }

    public static class UploadListener implements Session.Listener.AutoDemanding
    {
        final CountDownLatch closed = new CountDownLatch(1);
        private final ResultFile result;

        UploadListener(ResultFile result)
        {
            this.result = result;
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            Log.log("onWebSocketError %s", Log.stack(cause));
            result.putIfAbsent("exception", Log.brief(cause));
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            Log.log("onWebSocketClose code=%d reason=%s", statusCode, reason);
            result.putIfAbsent("closeCode", statusCode);
            result.putIfAbsent("closeReason", reason);
            closed.countDown();
        }
    }

    /** Synchronous, throttled local sink. */
    public static class SlowSink
    {
        private final OutputStream out;
        private final long rate;
        private final long delayMs;
        private final long stallAt;
        private final long stallMs;
        private long written;
        private long start = -1;
        private boolean stalled;

        SlowSink(OutputStream out, Args args)
        {
            this.out = out;
            this.rate = args.num("sink-rate", 50_000);
            this.delayMs = args.num("sink-delay-ms", 0);
            this.stallAt = args.num("stall-at", -1);
            this.stallMs = args.num("stall-ms", 0);
        }

        void write(byte[] b) throws Exception
        {
            if (start < 0)
                start = System.nanoTime();
            out.write(b);
            written += b.length;
            if (delayMs > 0)
                Thread.sleep(delayMs);
            if (stallAt >= 0 && !stalled && written >= stallAt)
            {
                stalled = true;
                Log.log("sink stall %d ms at %d bytes", stallMs, written);
                Thread.sleep(stallMs);
                start += TimeUnit.MILLISECONDS.toNanos(stallMs);
            }
            if (rate > 0)
            {
                long due = start + written * 1_000_000_000L / rate;
                long wait = due - System.nanoTime();
                if (wait > 0)
                    TimeUnit.NANOSECONDS.sleep(wait);
            }
        }
    }

    public static class DownloadListener implements Session.Listener.AutoDemanding
    {
        final Pattern.Verifier verifier = new Pattern.Verifier(true);
        final CountDownLatch closed = new CountDownLatch(1);
        final CountDownLatch closeFrameReceived = new CountDownLatch(1);
        private final SlowSink sink;
        private final boolean completeCallback;
        private final Args args;
        private final Path dir;
        private final long expected;
        private final ResultFile result;
        volatile Heartbeat heartbeat;
        volatile long lastMessageNanos = System.nanoTime();
        volatile long delivered;
        volatile long pingDelivered;
        private long messages;

        DownloadListener(SlowSink sink, Args args, Path dir, long expected, ResultFile result)
        {
            this.sink = sink;
            this.completeCallback = args.bool("complete-callback", false);
            this.args = args;
            this.dir = dir;
            this.expected = expected;
            this.result = result;
        }

        @Override
        public void onWebSocketOpen(Session session)
        {
            Log.log("onWebSocketOpen local=%s remote=%s", session.getLocalSocketAddress(), session.getRemoteSocketAddress());
            result.put("localAddress", session.getLocalSocketAddress());
            heartbeat = new Heartbeat(session, this, args, dir, expected, result);
            heartbeat.start();
        }

        @Override
        public void onWebSocketBinary(ByteBuffer payload, Callback callback)
        {
            lastMessageNanos = System.nanoTime();
            byte[] copy = new byte[payload.remaining()];
            payload.get(copy);
            verifier.update(ByteBuffer.wrap(copy));
            messages++;
            if (messages == 1 || messages % 256 == 0 || verifier.bytes() >= expected)
                Log.log("delivered msg=%d bytes=%d firstGap=%d", messages, verifier.bytes(), verifier.firstGap());
            try
            {
                sink.write(copy);
            }
            catch (Exception e)
            {
                Log.log("sink write failed %s", Log.stack(e));
            }
            delivered = verifier.bytes();
            result.put("bytesDelivered", delivered);
            result.put("firstGapOffset", verifier.firstGap());
            lastMessageNanos = System.nanoTime();
            // Variant A (production): never complete the callback.
            if (completeCallback)
                callback.succeed();
        }

        @Override
        public void onWebSocketPong(ByteBuffer payload)
        {
            Log.log("PONG received len=%d", payload.remaining());
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            Log.log("onWebSocketError delivered=%d %s", verifier.bytes(), Log.stack(cause));
            result.putIfAbsent("exception", Log.brief(cause));
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            // Called when the peer's Close frame is received, or when the connection dies (1006).
            Log.log("onWebSocketClose code=%d reason=%s delivered=%d/%d firstGap=%d",
                statusCode, reason, verifier.bytes(), expected, verifier.firstGap());
            result.putIfAbsent("closeCode", statusCode);
            result.putIfAbsent("closeReason", reason);
            result.put("closeEpochUs", Log.epochMicros());
            closeFrameReceived.countDown();
            closed.countDown();
        }
    }

    /** Heartbeat thread: synchronous PING (wait up to 30 s for the send callback), like sendPingSync. */
    public static class Heartbeat extends Thread
    {
        private final Session session;
        private final DownloadListener listener;
        private final String mode;
        private final long intervalMs;
        private final String phase;
        private final double p1Fraction;
        private final Path dir;
        private final long expected;
        private final ResultFile result;
        private final AtomicInteger pings = new AtomicInteger();

        Heartbeat(Session session, DownloadListener listener, Args args, Path dir, long expected, ResultFile result)
        {
            super("heartbeat");
            setDaemon(true);
            this.session = session;
            this.listener = listener;
            this.mode = args.str("heartbeat", "off");
            this.intervalMs = args.num("heartbeat-interval-ms", 20_000);
            this.phase = args.str("ping-phase", "P2");
            this.p1Fraction = Double.parseDouble(args.str("p1-fraction", "0.1"));
            this.dir = dir;
            this.expected = expected;
            this.result = result;
            result.put("heartbeat", mode.equals("forced") ? "forced-" + phase : mode);
            result.put("pingsSent", 0);
        }

        @Override
        public void run()
        {
            try
            {
                switch (mode)
                {
                    case "on", "smart" -> periodic();
                    case "forced" -> forced();
                    default ->
                    {
                    }
                }
            }
            catch (InterruptedException ignored)
            {
            }
        }

        private void periodic() throws InterruptedException
        {
            while (!listener.closed.await(intervalMs, TimeUnit.MILLISECONDS))
            {
                if (mode.equals("smart"))
                {
                    long idleMs = (System.nanoTime() - listener.lastMessageNanos) / 1_000_000;
                    if (idleMs < intervalMs || listener.closeFrameReceived.getCount() == 0)
                    {
                        Log.log("heartbeat: skip PING, still draining (last data %d ms ago)", idleMs);
                        continue;
                    }
                }
                ping("periodic");
            }
        }

        private void forced() throws InterruptedException
        {
            switch (phase)
            {
                case "P1" ->
                {
                    long target = (long)(expected * p1Fraction);
                    while (listener.delivered < target && listener.closed.getCount() > 0)
                        Thread.sleep(2);
                }
                case "P2" ->
                {
                    while (!Files.exists(dir.resolve("flag-close-done")) && listener.closed.getCount() > 0)
                        Thread.sleep(2);
                }
                case "P3" -> listener.closeFrameReceived.await();
                default -> throw new IllegalArgumentException("ping-phase " + phase);
            }
            if (!phase.equals("P3") && listener.closed.getCount() == 0)
            {
                result.put("pingPhase", phase + " INVALID: connection closed before trigger");
                return;
            }
            boolean closeFrameBefore = listener.closeFrameReceived.getCount() == 0;
            long pingUs = ping("forced " + phase);
            // Judge the phase from the actual PING send time against the server's flag timestamps.
            long closeRequestedUs = flagTime("close-requested");
            long closeDoneUs = flagTime("close-done");
            long delivered = listener.pingDelivered;
            String verdict = switch (phase)
            {
                case "P1" -> closeRequestedUs < 0 || pingUs < closeRequestedUs
                    ? "ok: PING before server close()" : "INVALID: server had already called close()";
                case "P2" -> closeDoneUs >= 0 && pingUs > closeDoneUs && delivered < expected && !closeFrameBefore
                    ? "ok: server Close written, client still draining" : "INVALID: client had already drained";
                default -> closeFrameBefore ? "ok: Close frame received by client" : "INVALID";
            };
            result.put("pingPhase", phase + " " + verdict + " (delivered=" + delivered + ")");
        }

        private long flagTime(String name)
        {
            try
            {
                return Long.parseLong(Files.readString(dir.resolve("flag-" + name)).trim());
            }
            catch (Exception e)
            {
                return -1;
            }
        }

        /** Sends one PING and waits for its callback; returns the epoch micros just before sendPing. */
        private long ping(String why)
        {
            int n = pings.incrementAndGet();
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            long delivered = listener.delivered;
            listener.pingDelivered = delivered;
            long sendUs = Log.epochMicros();
            try
            {
                session.sendPing(ByteBuffer.wrap(new byte[]{'h', 'b', (byte)n}), Callback.from(latch::countDown, x ->
                {
                    failure.set(x);
                    latch.countDown();
                }));
                Log.log("PING #%d send (%s) delivered=%d", n, why, delivered);
                result.put("pingsSent", n);
                result.put("ping" + n + "EpochUs", sendUs);
                result.put("ping" + n + "Delivered", delivered);
                if (!latch.await(30, TimeUnit.SECONDS))
                {
                    Log.log("PING #%d callback timeout", n);
                    result.put("ping" + n + "Result", "timeout");
                }
                else if (failure.get() != null)
                {
                    Log.log("PING #%d failed %s", n, Log.stack(failure.get()));
                    result.put("ping" + n + "Result", Log.brief(failure.get()));
                }
                else
                {
                    Log.log("PING #%d sent", n);
                    result.put("ping" + n + "Result", "ok");
                }
            }
            catch (Throwable t)
            {
                Log.log("PING #%d threw %s", n, Log.stack(t));
                result.put("ping" + n + "Result", "threw " + Log.brief(t));
            }
            return sendUs;
        }
    }
}
