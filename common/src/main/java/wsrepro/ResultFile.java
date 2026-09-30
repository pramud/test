package wsrepro;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Key/value result file, rewritten atomically on every update so a killed process leaves the last state. */
public final class ResultFile
{
    private final Path path;
    private final Properties props = new Properties();

    public ResultFile(Path path)
    {
        this.path = path;
    }

    public synchronized void put(String key, Object value)
    {
        props.setProperty(key, String.valueOf(value));
        save();
    }

    public synchronized String get(String key, String def)
    {
        return props.getProperty(key, def);
    }

    public synchronized void putIfAbsent(String key, Object value)
    {
        if (!props.containsKey(key))
            put(key, value);
    }

    private void save()
    {
        if (path == null)
            return;
        try
        {
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp))
            {
                props.store(w, null);
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException e)
        {
            Log.log("cannot write result file %s: %s", path, e);
        }
    }

    public static Properties read(Path path)
    {
        Properties p = new Properties();
        if (Files.exists(path))
        {
            try (Reader r = Files.newBufferedReader(path))
            {
                p.load(r);
            }
            catch (IOException e)
            {
                p.setProperty("readError", e.toString());
            }
        }
        return p;
    }
}
