package wsrepro;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Experiment matrix. Global options (--bytes, --sink-rate, --heartbeat-interval-ms, buffers ...) feed every variant. */
public final class Experiments
{
    public static final List<String> ALL = List.of("E1", "E2", "E3", "E4", "E5", "E6", "E7", "E8", "E9", "E10");

    /** One cell of the matrix. lingering: off | always | long (always + lingering_time/timeout 1h). */
    public record Variant(String experiment, String name, boolean proxy, String lingering,
                          List<String> serverArgs, List<String> clientArgs, String path, long timeoutMs)
    {
        Variant with(String suffix, List<String> moreServer, List<String> moreClient)
        {
            List<String> s = new ArrayList<>(serverArgs);
            s.addAll(moreServer);
            List<String> c = new ArrayList<>(clientArgs);
            c.addAll(moreClient);
            return new Variant(experiment, name + suffix, proxy, lingering, s, c, path, timeoutMs);
        }

        Variant as(String exp, String newName)
        {
            return new Variant(exp, newName, proxy, lingering, serverArgs, clientArgs, path, timeoutMs);
        }
    }

    private final Args a;
    private final long bytes;
    private final long rate;
    private final long hb;

    public Experiments(Args args)
    {
        this.a = args;
        this.bytes = args.num("bytes", 1024 * 1024);
        this.rate = args.num("sink-rate", 50_000);
        this.hb = args.num("heartbeat-interval-ms", 20_000);
    }

    private List<String> serverBase()
    {
        return new ArrayList<>(List.of(
            "--input-buffer=" + a.str("input-buffer", "15m"),
            "--output-buffer=" + a.str("output-buffer", "15m"),
            "--last-per-chunk=" + a.str("last-per-chunk", "true"),
            "--complete-callback=" + a.str("server-callback", "false")));
    }

    private List<String> clientBase(long b, String heartbeat)
    {
        List<String> c = new ArrayList<>(List.of(
            "--bytes=" + b,
            "--sink-rate=" + rate,
            "--heartbeat-interval-ms=" + hb,
            "--complete-callback=" + a.str("client-callback", "false"),
            "--p1-fraction=" + a.str("p1-fraction", "0.1")));
        if (heartbeat.startsWith("P"))
            c.addAll(List.of("--heartbeat=forced", "--ping-phase=" + heartbeat));
        else
            c.add("--heartbeat=" + heartbeat);
        return c;
    }

    private long downloadTimeout(long b)
    {
        return (rate > 0 ? b * 1000 / rate : 0) + 120_000;
    }

    Variant download(String exp, String name, boolean proxy, String lingering, String heartbeat)
    {
        return download(exp, name, proxy, lingering, heartbeat, bytes);
    }

    Variant download(String exp, String name, boolean proxy, String lingering, String heartbeat, long b)
    {
        return new Variant(exp, name, proxy, lingering, serverBase(), clientBase(b, heartbeat), "/stream", downloadTimeout(b));
    }

    public List<Variant> variants(String exp)
    {
        List<Variant> v = new ArrayList<>();
        switch (exp)
        {
            case "E1" -> v.add(download(exp, "direct-hbOff", false, "off", "off"));
            case "E2" -> v.add(download(exp, "direct-hbOn", false, "off", "on"));
            case "E3" -> v.add(download(exp, "nginx-hbOff", true, "off", "off"));
            case "E4" -> v.add(download(exp, "nginx-hbOn", true, "off", "on"));
            case "E5" -> v.add(download(exp, "nginx-linger-hbOn", true, "always", "on"));
            case "E6" ->
            {
                for (String phase : List.of("P1", "P2", "P3"))
                {
                    v.add(download(exp, "direct-" + phase, false, "off", phase));
                    v.add(download(exp, "nginx-" + phase, true, "off", phase));
                }
                // With 1 MiB the server finishes writing within ~1-5 s (the rest sits in socket/nginx buffers), so a
                // PING at 10% often lands after close(). P1early triggers at 0.5% to really be "before close".
                for (boolean proxy : List.of(false, true))
                {
                    v.add(download(exp, (proxy ? "nginx" : "direct") + "-P1early", proxy, "off", "P1")
                        .with("", List.of(), List.of("--p1-fraction=" + a.str("p1-early-fraction", "0.005"))));
                }
            }
            case "E7" ->
            {
                List<Variant> bases = List.of(
                    download(exp, "E2", false, "off", "on"),
                    download(exp, "E4", true, "off", "on"),
                    download(exp, "E6P2-direct", false, "off", "P2"),
                    download(exp, "E6P2-nginx", true, "off", "P2"));
                for (Variant b : bases)
                {
                    v.add(b.with("-clientCbB", List.of(), List.of("--complete-callback=true")));
                    v.add(b.with("-srvDefaultBufs", List.of("--input-buffer=default", "--output-buffer=default"), List.of()));
                    v.add(b.with("-srvOut64k", List.of("--output-buffer=64k"), List.of()));
                }
            }
            case "E8" ->
            {
                // 15 MiB vs Jetty default; 64 KiB and 65544 bracket the ArrayByteBufferPool max capacity (64 KiB).
                for (String in : List.of("15m", "default", "64k", "65544"))
                {
                    for (boolean cb : List.of(false, true))
                    {
                        String label = switch (in)
                        {
                            case "15m" -> "15MiB";
                            case "default" -> "Default";
                            case "64k" -> "64KiB";
                            default -> in;
                        };
                        v.add(new Variant(exp, "upload-in" + label + (cb ? "-srvCbDone" : "-srvCbNever"),
                            false, "off",
                            List.of("--input-buffer=" + in, "--output-buffer=" + a.str("output-buffer", "15m"), "--complete-callback=" + cb),
                            List.of("--mode=upload", "--bytes=" + a.num("upload-bytes", 5 * 1024 * 1024),
                                "--upload-message=" + a.num("upload-message", 65544), "--timeout-ms=60000"),
                            "/upload", 60_000));
                    }
                }
            }
            case "E9" ->
            {
                long b = a.num("e9-bytes", 256 * 1024);
                Variant base = download(exp, "direct-stall45s", false, "off", "off", b);
                v.add(base.with("", List.of("--send-buffer=8192"),
                    List.of("--recv-buffer=8192", "--stall-at=65536", "--stall-ms=" + a.num("stall-ms", 45_000))));
            }
            case "E10" ->
            {
                // Fix candidates and sensitivity (not in the original list).
                long big = 4L * 1024 * 1024;
                v.add(download(exp, "nginx-hbSmart", true, "off", "smart"));
                v.add(download(exp, "nginx-linger-P2", true, "always", "P2"));
                v.add(download(exp, "nginx-lingerLong-hbOn", true, "long", "on"));
                v.add(download(exp, "nginx-lingerLong-P2", true, "long", "P2"));
                v.add(download(exp, "nginx-P2-srvFragmented", true, "off", "P2").with("", List.of("--last-per-chunk=false"), List.of()));
                v.add(download(exp, "nginx-P2-srvCbDone", true, "off", "P2").with("", List.of("--complete-callback=true"), List.of()));
                v.add(download(exp, "nginx-hbOn-4MiB", true, "off", "on", big));
                v.add(download(exp, "nginx-hbSmart-4MiB", true, "off", "smart", big));
                v.add(download(exp, "direct-hbOn-4MiB", false, "off", "on", big));
            }
            case "custom" ->
            {
                boolean proxy = a.bool("proxy", false);
                String hbMode = a.str("heartbeat", "on");
                String hbArg = hbMode.equals("forced") ? a.str("ping-phase", "P2") : hbMode;
                String name = a.str("name", (proxy ? "nginx" : "direct") + "-" + hbArg + "-linger_" + a.str("lingering", "off"));
                Variant c = download(exp, name, proxy, a.str("lingering", "off"), hbArg);
                List<String> extraClient = new ArrayList<>();
                for (String k : List.of("recv-buffer", "stall-at", "stall-ms", "sink-delay-ms"))
                {
                    if (a.has(k))
                        extraClient.add("--" + k + "=" + a.str(k, ""));
                }
                List<String> extraServer = new ArrayList<>();
                for (String k : List.of("send-buffer", "send-timeout-ms", "chunk"))
                {
                    if (a.has(k))
                        extraServer.add("--" + k + "=" + a.str(k, ""));
                }
                v.add(c.with("", extraServer, extraClient));
            }
            default -> throw new IllegalArgumentException("unknown experiment " + exp + "; known: " + ALL + ", custom");
        }
        return v;
    }

    static List<String> parseList(String s)
    {
        if (s.equals("all"))
            return ALL;
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }
}
