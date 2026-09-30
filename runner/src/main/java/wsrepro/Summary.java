package wsrepro;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** CSV I/O and the per-experiment summary tables (results/summary.md). */
public final class Summary
{
    private Summary()
    {
    }

    static void writeCsv(Path file, List<String[]> rows) throws IOException
    {
        List<String> lines = new ArrayList<>();
        lines.add(String.join(",", Runner.COLUMNS));
        for (String[] r : rows)
            lines.add(Stream.of(r).map(Summary::quote).collect(Collectors.joining(",")));
        Files.write(file, lines);
    }

    static String quote(String s)
    {
        if (s == null)
            return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n"))
            return '"' + s.replace("\"", "\"\"").replace("\n", " ") + '"';
        return s;
    }

    static List<String[]> readCsv(Path file) throws IOException
    {
        List<String[]> rows = new ArrayList<>();
        List<String> lines = Files.readAllLines(file);
        for (String line : lines.subList(1, lines.size()))
        {
            List<String> cells = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean q = false;
            for (int i = 0; i < line.length(); i++)
            {
                char ch = line.charAt(i);
                if (q)
                {
                    if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"')
                    {
                        cur.append('"');
                        i++;
                    }
                    else if (ch == '"')
                        q = false;
                    else
                        cur.append(ch);
                }
                else if (ch == '"')
                    q = true;
                else if (ch == ',')
                {
                    cells.add(cur.toString());
                    cur.setLength(0);
                }
                else
                    cur.append(ch);
            }
            cells.add(cur.toString());
            rows.add(cells.toArray(new String[0]));
        }
        return rows;
    }

    static int expOrder(Path p)
    {
        String n = p.getFileName().toString().replace(".csv", "");
        return n.matches("E\\d+") ? Integer.parseInt(n.substring(1)) : 1000;
    }

    /** Concatenates results/csv/*.csv into results/results.csv and writes results/summary.md. */
    static void rebuild(Path results) throws IOException
    {
        List<Path> files;
        try (Stream<Path> s = Files.list(results.resolve("csv")))
        {
            files = s.filter(p -> p.toString().endsWith(".csv"))
                .sorted(Comparator.comparingInt(Summary::expOrder).thenComparing(Path::toString)).toList();
        }
        List<String[]> all = new ArrayList<>();
        for (Path f : files)
            all.addAll(readCsv(f));
        writeCsv(results.resolve("results.csv"), all);

        StringBuilder md = new StringBuilder("# Summary (generated from results.csv)\n\n");
        md.append("`complete` = every byte delivered and the pattern verified. `truncated` = fewer bytes than expected, no corruption. ")
            .append("Close codes / exceptions / RST senders are shown as value×count.\n");
        Map<String, Map<String, List<String[]>>> byExp = new LinkedHashMap<>();
        for (String[] r : all)
            byExp.computeIfAbsent(r[0], k -> new LinkedHashMap<>()).computeIfAbsent(r[1], k -> new ArrayList<>()).add(r);
        for (var e : byExp.entrySet())
        {
            md.append("\n## ").append(e.getKey()).append("\n\n");
            md.append("| variant | runs | complete | truncated | corrupt | delivered min..max | client close | client exception | server close | server error | RST runs | RST sender | ping phase valid | median s |\n");
            md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            for (var v : e.getValue().entrySet())
            {
                List<String[]> rows = v.getValue();
                long complete = rows.stream().filter(r -> r[13].equals("true")).count();
                long corrupt = rows.stream().filter(r -> !r[5].equals("-1") && !r[5].isEmpty()).count();
                long truncated = rows.stream().filter(r -> !r[13].equals("true") && (r[5].equals("-1") || r[5].isEmpty())).count();
                long min = rows.stream().mapToLong(r -> parse(r[4])).min().orElse(0);
                long max = rows.stream().mapToLong(r -> parse(r[4])).max().orElse(0);
                long rst = rows.stream().filter(r -> r[10].equals("true")).count();
                boolean captured = rows.stream().anyMatch(r -> !r[10].equals("n/a"));
                long phases = rows.stream().filter(r -> !r[15].isEmpty()).count();
                long phaseOk = rows.stream().filter(r -> r[15].contains(" ok:")).count();
                List<Long> dur = rows.stream().map(r -> parse(r[12])).sorted().toList();
                md.append("| ").append(v.getKey())
                    .append(" | ").append(rows.size())
                    .append(" | ").append(complete)
                    .append(" | ").append(truncated)
                    .append(" | ").append(corrupt)
                    .append(" | ").append(min).append("..").append(max)
                    .append(" | ").append(hist(rows, 6))
                    .append(" | ").append(hist(rows, 7))
                    .append(" | ").append(hist(rows, 8))
                    .append(" | ").append(hist(rows, 9))
                    .append(" | ").append(captured ? Long.toString(rst) : "n/a")
                    .append(" | ").append(captured ? hist(rows, 11) : "n/a")
                    .append(" | ").append(phases == 0 ? "" : phaseOk + "/" + phases)
                    .append(" | ").append(dur.isEmpty() ? "" : String.format("%.1f", dur.get(dur.size() / 2) / 1000.0))
                    .append(" |\n");
            }
        }
        Files.writeString(results.resolve("summary.md"), md.toString());
    }

    static long parse(String s)
    {
        try
        {
            return Long.parseLong(s.trim());
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }

    static String hist(List<String[]> rows, int col)
    {
        Map<String, Integer> h = new TreeMap<>();
        for (String[] r : rows)
        {
            String k = r[col].isEmpty() ? "-" : shorten(r[col]);
            h.merge(k, 1, Integer::sum);
        }
        return h.entrySet().stream().map(x -> x.getKey() + "×" + x.getValue()).collect(Collectors.joining("<br>"));
    }

    static String shorten(String s)
    {
        String t = s.replace("|", "/");
        return t.length() > 90 ? t.substring(0, 90) + "…" : t;
    }
}
