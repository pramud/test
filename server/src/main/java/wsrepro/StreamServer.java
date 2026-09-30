package wsrepro;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Frame;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketFrame;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.core.OpCode;

/**
 * "Connector mimic": streams N pattern bytes (prefixed with OKAY) in 512-byte messages on /stream?bytes=N,
 * or verifies an upload on /upload?bytes=N. Serves a single run; exits shortly after the session closes.
 *
 * <pre>
 * --port=N                 listen port on 127.0.0.1 (required)
 * --input-buffer=15m       factory inputBufferSize ("default" = Jetty default)
 * --output-buffer=15m      factory outputBufferSize ("default" = Jetty default)
 * --chunk=512              bytes per sendPartialBinary call
 * --last-per-chunk=true    isLast=true on every chunk (false: one fragmented message)
 * --complete-callback=false  complete the Callback of received binary messages
 * --send-timeout-ms=30000  how long the producer waits for each send callback (result ignored)
 * --send-buffer=0          SO_SNDBUF of accepted sockets (0 = OS default)
 * --dir=.                  where flag files and server-result.properties are written
 * </pre>
 */
public class StreamServer
{
    static final long MIB = 1024 * 1024;

    static Args args;
    static Path dir;
    static ResultFile result;

    public static void main(String[] argv) throws Exception
    {
        args = new Args(argv);
        dir = Path.of(args.str("dir", "."));
        result = new ResultFile(dir.resolve("server-result.properties"));
        Log.log("server args %s", args.all());

        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort((int)args.num("port", 0));
        long sndBuf = args.num("send-buffer", 0);
        if (sndBuf > 0)
            connector.setAcceptedSendBufferSize((int)sndBuf);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(new StreamServlet()), "/stream");
        context.addServlet(new ServletHolder(new StreamServlet()), "/upload");
        JettyWebSocketServletContainerInitializer.configure(context, null);
        server.setHandler(context);
        server.start();
        Log.log("READY port=%d jetty=%s", connector.getLocalPort(), Server.getVersion());
        server.join();
    }

    static void flag(String name)
    {
        try
        {
            Files.writeString(dir.resolve("flag-" + name), Long.toString(Log.epochMicros()));
        }
        catch (Exception e)
        {
            Log.log("cannot write flag %s: %s", name, e);
        }
    }

    public static class StreamServlet extends JettyWebSocketServlet
    {
        @Override
        protected void configure(JettyWebSocketServletFactory factory)
        {
            factory.setMaxBinaryMessageSize(15 * MIB);
            factory.setMaxTextMessageSize(15 * MIB);
            String in = args.str("input-buffer", "15m");
            String out = args.str("output-buffer", "15m");
            if (!in.equals("default"))
                factory.setInputBufferSize((int)Args.parseSize(in));
            if (!out.equals("default"))
                factory.setOutputBufferSize((int)Args.parseSize(out));
            factory.setIdleTimeout(Duration.ofHours(1));
            Log.log("factory inputBufferSize=%d outputBufferSize=%d maxBinaryMessageSize=%d idleTimeout=%s",
                factory.getInputBufferSize(), factory.getOutputBufferSize(), factory.getMaxBinaryMessageSize(), factory.getIdleTimeout());
            factory.setCreator((req, resp) ->
            {
                // Request parameters are only available during the upgrade.
                List<String> bytes = req.getParameterMap().get("bytes");
                return new StreamEndpoint(req.getRequestPath(), bytes == null ? MIB : Long.parseLong(bytes.get(0)));
            });
        }
    }

    @WebSocket
    public static class StreamEndpoint
    {
        private final String path;
        private final long bytes;
        private final Pattern.Verifier uploadVerifier = new Pattern.Verifier(false);
        private final AtomicLong uploadMessages = new AtomicLong();
        private volatile Session session;

        StreamEndpoint(String path, long bytes)
        {
            this.path = path;
            this.bytes = bytes;
        }

        @OnWebSocketOpen
        public void onOpen(Session session)
        {
            this.session = session;
            Log.log("onOpen path=%s bytes=%d remote=%s", path, bytes, session.getRemoteSocketAddress());
            result.put("mode", path.endsWith("/upload") ? "upload" : "download");
            result.put("bytesExpected", path.endsWith("/upload") ? bytes : bytes + Pattern.PREFIX.length);
            if (path.endsWith("/stream"))
                new Thread(() -> produce(bytes), "producer").start();
        }

        private void produce(long n)
        {
            int chunk = (int)args.num("chunk", 512);
            boolean lastPerChunk = args.bool("last-per-chunk", true);
            long timeoutMs = args.num("send-timeout-ms", 30_000);
            long total = n + Pattern.PREFIX.length;
            long timeouts = 0;
            long failures = 0;
            long seq = 0;
            long start = System.nanoTime();
            for (long off = 0; off < total; off += chunk, seq++)
            {
                byte[] b = new byte[(int)Math.min(chunk, total - off)];
                Pattern.fill(b, off, true);
                boolean isLast = lastPerChunk || off + b.length == total;
                CountDownLatch latch = new CountDownLatch(1);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                final long s = seq;
                Log.log("send-start seq=%d off=%d len=%d last=%b", s, off, b.length, isLast);
                session.sendPartialBinary(ByteBuffer.wrap(b), isLast, new Callback()
                {
                    @Override
                    public void succeed()
                    {
                        Log.log("send-complete seq=%d", s);
                        latch.countDown();
                    }

                    @Override
                    public void fail(Throwable x)
                    {
                        Log.log("send-fail seq=%d %s", s, Log.brief(x));
                        failure.set(x);
                        latch.countDown();
                    }
                });
                try
                {
                    // The result is deliberately ignored, as in production.
                    if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS))
                    {
                        timeouts++;
                        Log.log("send-timeout seq=%d off=%d after %d ms (ignored, continuing)", s, off, timeoutMs);
                    }
                    else if (failure.get() != null)
                    {
                        failures++;
                    }
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            result.put("sendTimeouts", timeouts);
            result.put("sendFailures", failures);
            result.put("produceMs", (System.nanoTime() - start) / 1_000_000);

            Log.log("close-requested");
            flag("close-requested");
            result.put("closeRequestedEpochUs", Log.epochMicros());
            session.close(1000, "Normal closure", new Callback()
            {
                @Override
                public void succeed()
                {
                    Log.log("close-callback succeeded (Close frame written)");
                    result.put("closeCallback", "succeeded");
                    flag("close-done");
                }

                @Override
                public void fail(Throwable x)
                {
                    Log.log("close-callback failed %s", Log.brief(x));
                    result.put("closeCallback", "failed: " + Log.brief(x));
                    flag("close-done");
                }
            });
            Log.log("close() returned");
        }

        @OnWebSocketMessage
        public void onBinary(ByteBuffer payload, Callback callback)
        {
            long m = uploadMessages.incrementAndGet();
            long before = uploadVerifier.mismatches();
            uploadVerifier.update(payload);
            long bad = uploadVerifier.mismatches() - before;
            Log.log("onBinary #%d len=%d total=%d badBytesInMessage=%d firstGap=%d %s", m, payload.remaining(), uploadVerifier.bytes(),
                bad, uploadVerifier.firstGap(), uploadVerifier.firstGapDetail());
            if (bad > 0)
                result.put("corruptMessages", Long.parseLong(result.get("corruptMessages", "0")) + 1);
            result.put("mismatchedBytes", uploadVerifier.mismatches());
            result.put("bytesDelivered", uploadVerifier.bytes());
            result.put("firstGapOffset", uploadVerifier.firstGap());
            result.put("uploadMessages", m);
            // Production does not complete this callback.
            if (args.bool("complete-callback", false))
                callback.succeed();
        }

        @OnWebSocketFrame
        public void onFrame(Frame frame, Callback callback)
        {
            byte op = frame.getOpCode();
            if (op == OpCode.PING || op == OpCode.PONG || op == OpCode.CLOSE)
                Log.log("frame-received %s payloadLength=%d", OpCode.name(op), frame.getPayloadLength());
            callback.succeed();
        }

        @OnWebSocketError
        public void onError(Throwable cause)
        {
            Log.log("onWebSocketError %s", Log.stack(cause));
            result.put("error", Log.brief(cause));
        }

        @OnWebSocketClose
        public void onClose(int code, String reason)
        {
            Log.log("onWebSocketClose code=%d reason=%s", code, reason);
            result.putIfAbsent("closeCode", code);
            result.putIfAbsent("closeReason", reason);
            Runtime rt = Runtime.getRuntime();
            result.put("heapUsedMiB", (rt.totalMemory() - rt.freeMemory()) / MIB);
            // One session per process: exit once the connection is gone.
            Thread t = new Thread(() ->
            {
                try
                {
                    Thread.sleep(1000);
                }
                catch (InterruptedException ignored)
                {
                }
                Log.log("exiting");
                System.exit(0);
            });
            t.setDaemon(true);
            t.start();
        }
    }
}
