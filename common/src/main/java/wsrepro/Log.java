package wsrepro;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;

/** Line logger: "epochSeconds.micros nanoTime [thread] message". Epoch time lines up with pcap timestamps. */
public final class Log
{
    private Log()
    {
    }

    public static long epochMicros()
    {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1000;
    }

    public static synchronized void log(String fmt, Object... args)
    {
        long us = epochMicros();
        System.out.printf("%d.%06d %d [%s] %s%n", us / 1_000_000, us % 1_000_000, System.nanoTime(),
            Thread.currentThread().getName(), args.length == 0 ? fmt : String.format(fmt, args));
        System.out.flush();
    }

    public static String stack(Throwable t)
    {
        StringWriter w = new StringWriter();
        t.printStackTrace(new PrintWriter(w));
        return w.toString();
    }

    /** "Class: message" of the root-most interesting cause, for CSV cells. */
    public static String brief(Throwable t)
    {
        if (t == null)
            return "";
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause())
        {
            if (sb.length() > 0)
                sb.append(" <- ");
            sb.append(c.getClass().getSimpleName());
            if (c.getMessage() != null)
                sb.append(": ").append(c.getMessage());
        }
        return sb.toString().replaceAll("[\\r\\n,;\"]", " ");
    }
}
