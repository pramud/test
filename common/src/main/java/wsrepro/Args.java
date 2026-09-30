package wsrepro;

import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal "--key=value" / "--flag" parser. */
public final class Args
{
    private final Map<String, String> values = new LinkedHashMap<>();

    public Args(String[] argv)
    {
        for (String a : argv)
        {
            if (!a.startsWith("--"))
                throw new IllegalArgumentException("Unexpected argument: " + a);
            int eq = a.indexOf('=');
            if (eq < 0)
                values.put(a.substring(2), "true");
            else
                values.put(a.substring(2, eq), a.substring(eq + 1));
        }
    }

    public boolean has(String key)
    {
        return values.containsKey(key);
    }

    public String str(String key, String def)
    {
        return values.getOrDefault(key, def);
    }

    public long num(String key, long def)
    {
        String v = values.get(key);
        return v == null ? def : parseSize(v);
    }

    public boolean bool(String key, boolean def)
    {
        String v = values.get(key);
        return v == null ? def : v.equals("true") || v.equals("on") || v.equals("yes");
    }

    public Map<String, String> all()
    {
        return values;
    }

    /** Accepts plain numbers and k/m suffixes (binary), e.g. 64k, 15m. */
    public static long parseSize(String v)
    {
        String s = v.trim().toLowerCase();
        long mul = 1;
        if (s.endsWith("k"))
            mul = 1024;
        else if (s.endsWith("m"))
            mul = 1024 * 1024;
        if (mul != 1)
            s = s.substring(0, s.length() - 1);
        return Long.parseLong(s) * mul;
    }
}
