package wsrepro;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Minimal libpcap reader (no dependencies) and a WebSocket/TCP event analysis for one run.
 * Supports Ethernet, Linux SLL/SLL2, BSD null/loopback and raw IP link types, IPv4 and IPv6.
 */
public final class Pcap
{
    static final int FIN = 0x01;
    static final int RST = 0x04;
    /** Server Close frame as sent by StreamServer: FIN+CLOSE, len 16, code 1000, "Normal closure". */
    static final byte[] SERVER_CLOSE = concat(new byte[]{(byte)0x88, 0x10, 0x03, (byte)0xE8}, "Normal closure".getBytes(StandardCharsets.US_ASCII));

    record Packet(long tsUs, int sport, int dport, int flags, int payloadLen, byte[] payload)
    {
    }

    private Pcap()
    {
    }

    static byte[] concat(byte[] a, byte[] b)
    {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    static List<Packet> read(Path file) throws IOException
    {
        List<Packet> out = new ArrayList<>();
        byte[] all = Files.readAllBytes(file);
        if (all.length < 24)
            return out;
        ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        int magic = bb.getInt(0);
        boolean nanos;
        if (magic == 0xa1b2c3d4 || magic == 0xa1b23c4d)
            nanos = magic == 0xa1b23c4d;
        else
        {
            bb.order(ByteOrder.BIG_ENDIAN);
            magic = bb.getInt(0);
            if (magic != 0xa1b2c3d4 && magic != 0xa1b23c4d)
                throw new IOException("not a pcap file: " + file);
            nanos = magic == 0xa1b23c4d;
        }
        int linkType = bb.getInt(20) & 0x0FFFFFFF;
        int pos = 24;
        while (pos + 16 <= all.length)
        {
            long sec = bb.getInt(pos) & 0xFFFFFFFFL;
            long frac = bb.getInt(pos + 4) & 0xFFFFFFFFL;
            int incl = bb.getInt(pos + 8);
            int start = pos + 16;
            if (start + incl > all.length)
                break;
            long tsUs = sec * 1_000_000L + (nanos ? frac / 1000 : frac);
            Packet p = parse(all, start, incl, linkType, tsUs);
            if (p != null)
                out.add(p);
            pos = start + incl;
        }
        return out;
    }

    private static Packet parse(byte[] d, int off, int len, int linkType, long tsUs)
    {
        int end = off + len;
        int ip;
        switch (linkType)
        {
            case 1 -> ip = off + 14; // Ethernet
            case 113 -> ip = off + 16; // Linux cooked
            case 276 -> ip = off + 20; // Linux cooked v2
            case 0, 108 -> ip = off + 4; // BSD loopback
            case 12, 101 -> ip = off; // raw IP
            default ->
            {
                return null;
            }
        }
        if (ip >= end)
            return null;
        int version = (d[ip] >> 4) & 0xF;
        int tcp;
        int tcpTotal;
        if (version == 4)
        {
            int ihl = (d[ip] & 0xF) * 4;
            if ((d[ip + 9] & 0xFF) != 6)
                return null;
            int total = u16(d, ip + 2);
            tcp = ip + ihl;
            tcpTotal = total - ihl;
        }
        else if (version == 6)
        {
            if ((d[ip + 6] & 0xFF) != 6)
                return null;
            tcp = ip + 40;
            tcpTotal = u16(d, ip + 4);
        }
        else
        {
            return null;
        }
        if (tcp + 14 > end)
            return null;
        int sport = u16(d, tcp);
        int dport = u16(d, tcp + 2);
        int dataOff = ((d[tcp + 12] >> 4) & 0xF) * 4;
        int flags = d[tcp + 13] & 0xFF;
        int payloadLen = Math.max(0, tcpTotal - dataOff);
        int capStart = Math.min(end, tcp + dataOff);
        byte[] payload = new byte[Math.min(payloadLen, end - capStart)];
        System.arraycopy(d, capStart, payload, 0, payload.length);
        return new Packet(tsUs, sport, dport, flags, payloadLen, payload);
    }

    private static int u16(byte[] d, int i)
    {
        return ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
    }

    /** Rewrites the capture keeping only the first {@code snap} bytes of each packet (headers + frame starts). */
    static void truncate(Path file, int snap) throws IOException
    {
        byte[] all = Files.readAllBytes(file);
        if (all.length < 24)
            return;
        ByteOrder order = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == 0xa1b2c3d4
            || ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == 0xa1b23c4d ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        ByteBuffer in = ByteBuffer.wrap(all).order(order);
        ByteBuffer out = ByteBuffer.allocate(all.length).order(order);
        out.put(all, 0, 24);
        out.putInt(16, snap);
        int pos = 24;
        while (pos + 16 <= all.length)
        {
            int incl = in.getInt(pos + 8);
            if (pos + 16 + incl > all.length)
                break;
            int keep = Math.min(incl, snap);
            out.putInt(in.getInt(pos)).putInt(in.getInt(pos + 4)).putInt(keep).putInt(in.getInt(pos + 12));
            out.put(all, pos + 16, keep);
            pos += 16 + incl;
        }
        try (OutputStream os = Files.newOutputStream(file))
        {
            os.write(out.array(), 0, out.position());
        }
    }

    /** Result of analysing one run's capture. */
    static final class Report
    {
        boolean rstSeen;
        final Set<String> rstSenders = new LinkedHashSet<>();
        String detail = "";
        long clientLegDownBytes;
        final List<String> events = new ArrayList<>();
    }

    static Report analyze(Path file, int serverPort, int proxyPort)
    {
        Report r = new Report();
        List<Packet> pkts;
        try
        {
            pkts = read(file);
        }
        catch (IOException e)
        {
            r.detail = "pcap unreadable: " + e.getMessage();
            return r;
        }
        if (pkts.isEmpty())
        {
            r.detail = "no packets captured";
            return r;
        }
        boolean proxied = proxyPort > 0;
        // Only look at the WebSocket connections (the ones carrying the HTTP upgrade request); this drops the
        // runner's port-readiness probes.
        Set<Integer> wsPorts = new LinkedHashSet<>();
        for (Packet p : pkts)
        {
            if (p.payload().length > 4 && new String(p.payload(), 0, 4, StandardCharsets.US_ASCII).equals("GET "))
                wsPorts.add(p.sport());
        }
        pkts = pkts.stream().filter(p -> wsPorts.contains(p.sport()) || wsPorts.contains(p.dport())).toList();
        if (pkts.isEmpty())
        {
            r.detail = "no WebSocket connection captured";
            return r;
        }
        long t0 = pkts.get(0).tsUs();
        long serverCloseUs = -1;
        long firstRstUs = -1;
        String firstRst = null;
        long lastPingBeforeRst = -1;
        long lastPingAny = -1;
        List<Long> clientLegPings = new ArrayList<>();
        long nginxClientFinUs = -1;
        for (Packet p : pkts)
        {
            boolean clientLeg = proxied ? (p.sport() == proxyPort || p.dport() == proxyPort) : (p.sport() == serverPort || p.dport() == serverPort);
            boolean down = p.sport() == serverPort || (proxied && p.sport() == proxyPort);
            String leg = !proxied ? "client<->server" : clientLeg ? "client<->nginx" : "nginx<->server";
            String sender = !proxied ? (down ? "server" : "client")
                : clientLeg ? (down ? "nginx(client-leg)" : "client") : (down ? "server" : "nginx(server-leg)");
            double rel = (p.tsUs() - t0) / 1e6;
            if (clientLeg && down)
                r.clientLegDownBytes += p.payloadLen();

            if (down && indexOf(p.payload(), SERVER_CLOSE) >= 0)
            {
                r.events.add(String.format("%10.6f %-15s %-18s Close(1000) frame", rel, leg, sender));
                if (!clientLeg || !proxied)
                    serverCloseUs = serverCloseUs < 0 ? p.tsUs() : serverCloseUs;
            }
            if (!down && p.payload().length > 0 && p.payload()[0] == (byte)0x89)
            {
                r.events.add(String.format("%10.6f %-15s %-18s PING frame", rel, leg, sender));
                if (clientLeg)
                {
                    clientLegPings.add(p.tsUs());
                    lastPingAny = p.tsUs();
                }
            }
            if (!down && p.payload().length > 0 && p.payload()[0] == (byte)0x88)
                r.events.add(String.format("%10.6f %-15s %-18s Close frame (masked, from client side)", rel, leg, sender));
            if ((p.flags() & FIN) != 0)
            {
                r.events.add(String.format("%10.6f %-15s %-18s FIN (+%d payload bytes)", rel, leg, sender, p.payloadLen()));
                if (proxied && clientLeg && down && nginxClientFinUs < 0)
                    nginxClientFinUs = p.tsUs();
            }
            if ((p.flags() & RST) != 0)
            {
                r.events.add(String.format("%10.6f %-15s %-18s RST", rel, leg, sender));
                r.rstSeen = true;
                r.rstSenders.add(sender);
                if (firstRstUs < 0)
                {
                    firstRstUs = p.tsUs();
                    firstRst = sender + " on " + leg;
                    lastPingBeforeRst = lastPingAny;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        if (firstRstUs >= 0)
        {
            sb.append("first RST by ").append(firstRst).append(String.format(" at +%.3fs", (firstRstUs - t0) / 1e6));
            if (serverCloseUs >= 0)
                sb.append(String.format("; %+.3fs vs server Close frame", (firstRstUs - serverCloseUs) / 1e6));
            if (lastPingBeforeRst >= 0)
                sb.append(String.format("; %+.3f ms after client PING", (firstRstUs - lastPingBeforeRst) / 1e3));
            else
                sb.append("; no client PING before it");
            if (nginxClientFinUs >= 0)
                sb.append(nginxClientFinUs < firstRstUs ? "; nginx had sent FIN to client before RST" : "; RST before nginx FIN to client");
            else if (proxied)
                sb.append("; nginx never sent FIN to client");
        }
        else
        {
            sb.append("no RST");
        }
        sb.append("; pings on client leg=").append(clientLegPings.size());
        r.detail = sb.toString();
        return r;
    }

    static int indexOf(byte[] hay, byte[] needle)
    {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++)
        {
            for (int j = 0; j < needle.length; j++)
            {
                if (hay[i + j] != needle[j])
                    continue outer;
            }
            return i;
        }
        return -1;
    }

    /** For quick manual use: java -cp runner.jar wsrepro.Pcap file.pcap serverPort [proxyPort] */
    public static void main(String[] argv)
    {
        Report r = analyze(Path.of(argv[0]), Integer.parseInt(argv[1]), argv.length > 2 ? Integer.parseInt(argv[2]) : -1);
        r.events.forEach(System.out::println);
        System.out.println(r.detail);
        System.out.println("client-leg downstream payload bytes: " + r.clientLegDownBytes);
    }
}
