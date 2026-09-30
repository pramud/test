package wsrepro;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Runs experiments: for every run it starts tcpdump (if available), the server JVM, nginx (if proxied) and the
 * client JVM, then collects results into results/csv/&lt;exp&gt;.csv, results/results.csv and results/summary.md.
 *
 * <pre>
 * --exp=all|E1,E2,...|custom   experiments to run (default all)
 * --runs=20 --parallel=8
 * --bytes=1m --sink-rate=50000 --heartbeat-interval-ms=20000 --p1-fraction=0.1
 * --input-buffer=15m --output-buffer=15m --client-callback=false --server-callback=false --last-per-chunk=true
 * custom only: --proxy=on|off --lingering=off|always|long --heartbeat=off|on|smart|forced --ping-phase=P1|P2|P3
 *              --recv-buffer --send-buffer --stall-at --stall-ms --sink-delay-ms --name=...
 * --nginx=nginx            nginx executable (Windows: path to nginx.exe)
 * --tcpdump=tcpdump|off    capture tool (tcpdump-compatible CLI); default off on Windows
 * --capture-if=lo          capture interface
 * --home=.                 repository root (server/target/server.jar and client/target/client.jar)
 * --summarize              only rebuild results.csv and summary.md from results/csv
 * </pre>
 */
public class Runner
{
    static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    static final String[] COLUMNS = {"experiment", "variant", "run", "bytesExpected", "bytesDelivered", "firstGapOffset",
        "clientCloseCode", "clientException", "serverCloseCode", "serverError", "rstSeen", "rstSender", "durationMs",
        "complete", "pingsSent", "pingPhase", "pingResults", "serverSendTimeouts", "serverCloseCallback", "clientLegDownBytes", "rstDetail", "notes"};

    static Args args;
    static Path home;
    static Path results;
    static String java;
    static String nginx;
    static String tcpdump;
    static final AtomicInteger nextPort = new AtomicInteger();

    public static void main(String[] argv) throws Exception
    {
        args = new Args(argv);
        home = Path.of(args.str("home", ".")).toAbsolutePath().normalize();
        results = home.resolve(args.str("results", "results"));
        Files.createDirectories(results.resolve("csv"));
        if (args.has("summarize"))
        {
            Summary.rebuild(results);
            return;
        }
        java = Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java").toString();
        nextPort.set((int)args.num("base-port", 20000 + (int)(ProcessHandle.current().pid() % 200) * 100));
        detectTools();

        int runs = (int)args.num("runs", 20);
        int parallel = (int)args.num("parallel", 8);
        Experiments experiments = new Experiments(args);
        for (String exp : Experiments.parseList(args.str("exp", "all")))
        {
            List<Experiments.Variant> variants = experiments.variants(exp);
            boolean needsProxy = variants.stream().anyMatch(Experiments.Variant::proxy);
            if (needsProxy && nginx == null)
            {
                Log.log("SKIP %s: nginx not available", exp);
                continue;
            }
            int n = exp.equals("E9") ? (int)args.num("e9-runs", runs) : runs;
            Log.log("=== %s: %d variant(s) x %d runs, parallel %d", exp, variants.size(), n, parallel);
            ExecutorService pool = Executors.newFixedThreadPool(parallel);
            List<Future<String[]>> rows = new ArrayList<>();
            for (int r = 1; r <= n; r++)
            {
                for (Experiments.Variant v : variants)
                {
                    final int run = r;
                    rows.add(pool.submit(() -> runOne(v, run)));
                }
            }
            List<String[]> out = new ArrayList<>();
            for (Future<String[]> f : rows)
                out.add(f.get());
            pool.shutdown();
            out.sort(Comparator.<String[], String>comparing(x -> x[1]).thenComparing(x -> Integer.parseInt(x[2])));
            Summary.writeCsv(results.resolve("csv").resolve(exp + ".csv"), out);
            Summary.rebuild(results);
        }
    }

    static void detectTools() throws IOException
    {
        StringBuilder env = new StringBuilder();
        env.append("date: ").append(ZonedDateTime.now()).append('\n');
        env.append("os: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append('\n');
        env.append("java: ").append(System.getProperty("java.vendor")).append(' ').append(System.getProperty("java.runtime.version")).append('\n');
        env.append("jetty: 12.0.16 (pinned in pom.xml)\n");
        String nginxCmd = args.str("nginx", WINDOWS ? "nginx.exe" : "nginx");
        String v = capture(List.of(nginxCmd, "-v"));
        nginx = v != null && v.contains("nginx version") ? nginxCmd : null;
        env.append("nginx: ").append(nginx == null ? "NOT AVAILABLE" : v.trim()).append('\n');
        String td = args.str("tcpdump", WINDOWS ? "off" : "tcpdump");
        if (!td.equals("off"))
        {
            String tv = capture(List.of(td, "--version"));
            tcpdump = tv != null && tv.toLowerCase(Locale.ROOT).contains("version") ? td : null;
            env.append("capture: ").append(tcpdump == null ? "NOT AVAILABLE (" + td + ")" : tv.lines().findFirst().orElse("")).append('\n');
        }
        else
        {
            env.append("capture: off\n");
        }
        if (!WINDOWS)
        {
            String tc = capture(List.of("tc", "qdisc", "show", "dev", "lo"));
            env.append("tc: ").append(tc == null ? "not available" : tc.trim()).append('\n');
        }
        env.append("args: ").append(args.all()).append('\n');
        Files.writeString(results.resolve("environment.txt"), env.toString());
        Log.log("environment:\n%s", env);
    }

    static String capture(List<String> cmd)
    {
        try
        {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] out = p.getInputStream().readAllBytes();
            p.waitFor(10, TimeUnit.SECONDS);
            return new String(out);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    static int freePort()
    {
        while (true)
        {
            int port = nextPort.getAndIncrement();
            if (port > 64000)
                nextPort.set(20000);
            try (ServerSocket s = new ServerSocket())
            {
                s.bind(new InetSocketAddress("127.0.0.1", port));
                return port;
            }
            catch (IOException ignored)
            {
            }
        }
    }

    static boolean waitPort(int port, long timeoutMs) throws InterruptedException
    {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end)
        {
            try (Socket s = new Socket())
            {
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return true;
            }
            catch (IOException e)
            {
                Thread.sleep(50);
            }
        }
        return false;
    }

    static Process start(List<String> cmd, Path dir, Path log) throws IOException
    {
        return new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true)
            .redirectOutput(log.toFile()).start();
    }

    static String[] runOne(Experiments.Variant v, int run)
    {
        Path dir = results.resolve("raw").resolve(v.experiment()).resolve(v.name()).resolve(String.format("run-%02d", run));
        Properties client = new Properties();
        Properties server = new Properties();
        Pcap.Report pcap = null;
        String note = "";
        Process tcpdumpProc = null;
        Process serverProc = null;
        Process nginxProc = null;
        Path nginxConf = null;
        int serverPort = freePort();
        int proxyPort = v.proxy() ? freePort() : -1;
        try
        {
            deleteTree(dir);
            Files.createDirectories(dir);
            if (tcpdump != null)
            {
                String filter = "tcp port " + serverPort + (v.proxy() ? " or tcp port " + proxyPort : "");
                tcpdumpProc = start(List.of(tcpdump, "-i", args.str("capture-if", "lo"), "-U", "-n", "-w", "capture.pcap", filter),
                    dir, dir.resolve("tcpdump.log"));
                long end = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < end && !Files.readString(dir.resolve("tcpdump.log")).contains("listening"))
                    Thread.sleep(20);
            }
            List<String> sc = new ArrayList<>(List.of(java, "-jar", home.resolve("server/target/server.jar").toString(),
                "--port=" + serverPort, "--dir=" + dir));
            sc.addAll(v.serverArgs());
            serverProc = start(sc, dir, dir.resolve("server.log"));
            if (!waitPort(serverPort, 30_000))
                throw new IllegalStateException("server did not start");
            int clientPort = serverPort;
            if (v.proxy())
            {
                nginxConf = writeNginxConf(dir, proxyPort, serverPort, v.lingering());
                nginxProc = start(List.of(nginx, "-p", dir.toString(), "-c", nginxConf.toString(), "-g", "daemon off;"),
                    dir, dir.resolve("nginx.out"));
                if (!waitPort(proxyPort, 15_000))
                    throw new IllegalStateException("nginx did not start");
                clientPort = proxyPort;
            }
            List<String> cc = new ArrayList<>(List.of(java, "-jar", home.resolve("client/target/client.jar").toString(),
                "--url=ws://127.0.0.1:" + clientPort + v.path(), "--dir=" + dir, "--timeout-ms=" + v.timeoutMs()));
            cc.addAll(v.clientArgs());
            Files.writeString(dir.resolve("commands.txt"), "server: " + String.join(" ", sc) + "\nclient: " + String.join(" ", cc) +
                (v.proxy() ? "\nnginx: lingering=" + v.lingering() + " port " + proxyPort : "") + "\n");
            Process clientProc = start(cc, dir, dir.resolve("client.log"));
            if (!clientProc.waitFor(v.timeoutMs() + 60_000, TimeUnit.MILLISECONDS))
            {
                note = "client killed after timeout";
                clientProc.destroyForcibly().waitFor();
            }
            // The server exits by itself ~1 s after its session closed.
            if (!serverProc.waitFor(5, TimeUnit.SECONDS))
                note += (note.isEmpty() ? "" : "; ") + "server still running 5s after client exit";
        }
        catch (Exception e)
        {
            note = "runner error: " + Log.brief(e);
            Log.log("%s/%s run %d: %s", v.experiment(), v.name(), run, Log.stack(e));
        }
        finally
        {
            if (nginxProc != null)
                stopNginx(nginxProc, dir, nginxConf);
            if (serverProc != null)
                kill(serverProc);
            if (tcpdumpProc != null)
            {
                try
                {
                    Thread.sleep(300);
                }
                catch (InterruptedException ignored)
                {
                }
                tcpdumpProc.destroy();
                try
                {
                    tcpdumpProc.waitFor(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException ignored)
                {
                }
                kill(tcpdumpProc);
            }
        }
        try
        {
            client = ResultFile.read(dir.resolve("client-result.properties"));
            server = ResultFile.read(dir.resolve("server-result.properties"));
            // Full artifacts (pcap, per-send server log) for the first runs of each variant; for later runs keep
            // the pcap event timeline and the server log without per-send lines, to keep results/ small.
            boolean keepFull = run <= args.num("keep-full", 2);
            Path pcapFile = dir.resolve("capture.pcap");
            if (Files.exists(pcapFile))
            {
                pcap = Pcap.analyze(pcapFile, serverPort, proxyPort);
                Files.write(dir.resolve("pcap-events.txt"), pcap.events);
                if (keepFull)
                {
                    Pcap.truncate(pcapFile, (int)args.num("pcap-snap", 128));
                    gzip(pcapFile);
                }
                else
                {
                    Files.delete(pcapFile);
                }
            }
            Path serverLog = dir.resolve("server.log");
            if (!keepFull && Files.exists(serverLog))
            {
                List<String> lines = Files.readAllLines(serverLog);
                long sends = lines.stream().filter(l -> l.contains("] send-start ")).count();
                List<String> kept = new ArrayList<>(lines.stream()
                    .filter(l -> !l.contains("] send-start ") && !l.contains("] send-complete ")).toList());
                kept.add("[runner] removed " + sends + " send-start and matching send-complete lines (full log kept for runs 1.." + args.num("keep-full", 2) + ")");
                Files.write(serverLog, kept);
            }
            gzip(serverLog);
            deleteTree(dir.resolve("temp"));
            Files.deleteIfExists(dir.resolve("sink.bin"));
        }
        catch (IOException e)
        {
            note += "; post-processing error: " + Log.brief(e);
        }
        String[] row = row(v, run, client, server, pcap, note);
        Log.log("%s/%s run %02d: delivered %s/%s gap=%s client=%s %s server=%s rst=%s %s", v.experiment(), v.name(), run,
            row[4], row[3], row[5], row[6], row[7], row[8], row[10], row[11]);
        return row;
    }

    static String[] row(Experiments.Variant v, int run, Properties c, Properties s, Pcap.Report pcap, String note)
    {
        boolean upload = v.path().equals("/upload");
        Properties data = upload ? s : c;
        String expected = data.getProperty("bytesExpected", upload ? s.getProperty("bytesExpected", "") : "");
        String delivered = data.getProperty("bytesDelivered", "0");
        String gap = data.getProperty("firstGapOffset", "-1");
        boolean complete = !expected.isEmpty() && expected.equals(delivered) && gap.equals("-1");
        int pings = Integer.parseInt(c.getProperty("pingsSent", "0"));
        List<String> pingResults = new ArrayList<>();
        for (int i = 1; i <= pings; i++)
            pingResults.add(c.getProperty("ping" + i + "Result", "?") + "@" + c.getProperty("ping" + i + "Delivered", "?"));
        String clientException = c.getProperty("exception", "");
        if (!note.isEmpty())
            clientException = clientException.isEmpty() ? "[" + note + "]" : clientException + " [" + note + "]";
        return new String[]{
            v.experiment(), v.name(), Integer.toString(run), expected, delivered, gap,
            c.getProperty("closeCode", ""), clientException,
            s.getProperty("closeCode", ""), s.getProperty("error", ""),
            pcap == null ? "n/a" : Boolean.toString(pcap.rstSeen),
            pcap == null ? "n/a" : String.join("+", pcap.rstSenders),
            c.getProperty("durationMs", ""),
            Boolean.toString(complete), Integer.toString(pings), c.getProperty("pingPhase", ""), String.join(" ", pingResults),
            s.getProperty("sendTimeouts", ""), s.getProperty("closeCallback", ""),
            pcap == null ? "" : Long.toString(pcap.clientLegDownBytes),
            pcap == null ? "no capture" : pcap.detail,
            notes(c, s, note)};
    }

    static String notes(Properties c, Properties s, String runnerNote)
    {
        List<String> n = new ArrayList<>();
        for (String k : List.of("corruptMessages", "mismatchedBytes", "sendFailures", "heapUsedMiB"))
        {
            if (s.containsKey(k))
                n.add("server." + k + "=" + s.getProperty(k));
        }
        if (s.containsKey("error") && !c.containsKey("closeCode"))
            n.add("client did not record a close");
        if (!runnerNote.isEmpty())
            n.add(runnerNote);
        return String.join("; ", n);
    }

    static Path writeNginxConf(Path dir, int listen, int upstream, String lingering) throws IOException
    {
        Files.createDirectories(dir.resolve("logs"));
        Files.createDirectories(dir.resolve("temp"));
        String linger = switch (lingering)
        {
            case "always" -> "        lingering_close always;\n";
            case "long" -> "        lingering_close always;\n        lingering_time 3600s;\n        lingering_timeout 3600s;\n";
            default -> "";
        };
        String conf = """
            worker_processes 1;
            pid logs/nginx.pid;
            error_log logs/error.log info;
            events { worker_connections 64; }
            http {
                access_log logs/access.log;
                client_body_temp_path temp/client;
                proxy_temp_path temp/proxy;
                fastcgi_temp_path temp/fastcgi;
                uwsgi_temp_path temp/uwsgi;
                scgi_temp_path temp/scgi;
                server {
                    listen 127.0.0.1:%d;
            %s        location / {
                        proxy_pass http://127.0.0.1:%d;
                        proxy_buffering off;
                        proxy_buffer_size 128k;
                        proxy_buffers 4 256k;
                        proxy_busy_buffers_size 256k;
                        proxy_connect_timeout 6000;
                        proxy_send_timeout 6000;
                        proxy_read_timeout 6000;
                        send_timeout 6000;
                        proxy_http_version 1.1;
                        proxy_set_header Upgrade $http_upgrade;
                        proxy_set_header Connection "upgrade";
                    }
                }
            }
            """.formatted(listen, linger, upstream);
        Path file = dir.resolve("nginx.conf");
        Files.writeString(file, conf);
        return file;
    }

    static void stopNginx(Process p, Path dir, Path conf)
    {
        try
        {
            Process stop = new ProcessBuilder(nginx, "-p", dir.toString(), "-c", conf.toString(), "-s", "stop")
                .redirectErrorStream(true).redirectOutput(dir.resolve("nginx-stop.out").toFile()).start();
            stop.waitFor(10, TimeUnit.SECONDS);
            p.waitFor(10, TimeUnit.SECONDS);
        }
        catch (Exception ignored)
        {
        }
        kill(p);
    }

    static void kill(Process p)
    {
        if (p.isAlive())
        {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            try
            {
                p.waitFor(5, TimeUnit.SECONDS);
            }
            catch (InterruptedException ignored)
            {
            }
        }
    }

    static void gzip(Path file) throws IOException
    {
        if (!Files.exists(file))
            return;
        try (InputStream in = Files.newInputStream(file);
             OutputStream out = new GZIPOutputStream(Files.newOutputStream(file.resolveSibling(file.getFileName() + ".gz"))))
        {
            in.transferTo(out);
        }
        Files.delete(file);
    }

    static void deleteTree(Path dir) throws IOException
    {
        if (!Files.exists(dir))
            return;
        try (Stream<Path> s = Files.walk(dir))
        {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList())
                Files.delete(p);
        }
    }
}
