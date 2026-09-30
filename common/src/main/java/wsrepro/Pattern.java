package wsrepro;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Verifiable byte stream: optional "OKAY" prefix, then a running 64-bit big-endian counter
 * (bytes 0..7 = 0L, 8..15 = 1L, ...). Any gap, duplicate or reordering changes the expected byte.
 */
public final class Pattern
{
    public static final byte[] PREFIX = "OKAY".getBytes(StandardCharsets.US_ASCII);

    private Pattern()
    {
    }

    public static byte streamByte(long off, boolean prefixed)
    {
        if (prefixed)
        {
            if (off < PREFIX.length)
                return PREFIX[(int)off];
            off -= PREFIX.length;
        }
        long counter = off >>> 3;
        int shift = 56 - 8 * (int)(off & 7);
        return (byte)(counter >>> shift);
    }

    public static void fill(byte[] b, long startOff, boolean prefixed)
    {
        for (int i = 0; i < b.length; i++)
            b[i] = streamByte(startOff + i, prefixed);
    }

    /** Streaming verifier; not thread safe. */
    public static final class Verifier
    {
        private final boolean prefixed;
        private long offset;
        private long firstGap = -1;
        private long mismatches;
        private String firstGapDetail = "";

        public Verifier(boolean prefixed)
        {
            this.prefixed = prefixed;
        }

        public void update(ByteBuffer buf)
        {
            ByteBuffer b = buf.slice();
            while (b.hasRemaining())
            {
                byte got = b.get();
                byte want = streamByte(offset, prefixed);
                if (got != want)
                {
                    mismatches++;
                    if (firstGap < 0)
                    {
                        firstGap = offset;
                        firstGapDetail = String.format("expected 0x%02x got 0x%02x", want, got);
                    }
                }
                offset++;
            }
        }

        public long bytes()
        {
            return offset;
        }

        /** Number of bytes that did not match the pattern. */
        public long mismatches()
        {
            return mismatches;
        }

        public String firstGapDetail()
        {
            return firstGapDetail;
        }

        /** Offset of the first byte that did not match the pattern, or -1. */
        public long firstGap()
        {
            return firstGap;
        }
    }
}
