/*
 * Peanut Cam
 * Copyright (c) 2026 James Scott. All rights reserved.
 * Unauthorised copying, modification, redistribution or reuse is prohibited.
 * See COPYRIGHT.md and LICENSE. Contact: jamscoot22@gmail.com
 */
package com.beanhead.peanutcam.live;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The small part of AMF0 (Flash's serialisation) that RTMP publishing needs. Pure Java, no Android. */
final class Amf0 {
    private Amf0() { }

    /** Builds an AMF0 payload value by value. Maps become objects, or ECMA arrays via {@link #ecma}. */
    static final class Writer {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(256);

        Writer str(String s) { out.write(0x02); rawStr(s); return this; }
        Writer num(double d) { out.write(0x00); long v = Double.doubleToLongBits(d); for (int i = 7; i >= 0; i--) out.write((int) (v >>> (i * 8))); return this; }
        Writer bool(boolean b) { out.write(0x01); out.write(b ? 1 : 0); return this; }
        Writer nul() { out.write(0x05); return this; }
        Writer obj(Map<String, ?> m) { out.write(0x03); props(m); return this; }
        Writer ecma(Map<String, ?> m) {
            out.write(0x08);
            int n = m.size();
            out.write(n >>> 24); out.write(n >>> 16); out.write(n >>> 8); out.write(n);
            props(m);
            return this;
        }
        Writer any(Object v) {
            if (v == null) return nul();
            if (v instanceof String) return str((String) v);
            if (v instanceof Boolean) return bool((Boolean) v);
            if (v instanceof Number) return num(((Number) v).doubleValue());
            if (v instanceof Map) {
                @SuppressWarnings("unchecked") Map<String, ?> m = (Map<String, ?>) v;
                return obj(m);
            }
            throw new IllegalArgumentException("AMF0: unsupported " + v.getClass());
        }
        byte[] bytes() { return out.toByteArray(); }

        private void rawStr(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            out.write(b.length >>> 8); out.write(b.length);
            out.write(b, 0, b.length);
        }
        private void props(Map<String, ?> m) {
            for (Map.Entry<String, ?> e : m.entrySet()) { rawStr(e.getKey()); any(e.getValue()); }
            out.write(0); out.write(0); out.write(0x09);
        }
    }

    /** Decodes every value in a payload. Numbers come back as Double, objects as Map, unknown types stop the read. */
    static List<Object> readAll(byte[] b) {
        List<Object> vals = new ArrayList<>();
        int[] p = {0};
        try {
            while (p[0] < b.length) vals.add(read(b, p));
        } catch (RuntimeException ignored) {
            // a type we don't model: keep what was read so far
        }
        return vals;
    }

    private static Object read(byte[] b, int[] p) {
        int t = b[p[0]++] & 0xFF;
        switch (t) {
            case 0x00: { long v = 0; for (int i = 0; i < 8; i++) v = (v << 8) | (b[p[0]++] & 0xFF); return Double.longBitsToDouble(v); }
            case 0x01: return b[p[0]++] != 0;
            case 0x02: return rawStr(b, p, u16(b, p));
            case 0x0C: return rawStr(b, p, (int) u32(b, p));
            case 0x03: return props(b, p);
            case 0x08: p[0] += 4; return props(b, p);
            case 0x05: case 0x06: return null;
            case 0x0A: {
                long n = u32(b, p);
                List<Object> l = new ArrayList<>();
                for (long i = 0; i < n; i++) l.add(read(b, p));
                return l;
            }
            case 0x0B: p[0] += 10; return null;
            default: throw new IllegalStateException("AMF0 type " + t);
        }
    }

    private static Map<String, Object> props(byte[] b, int[] p) {
        Map<String, Object> m = new LinkedHashMap<>();
        while (true) {
            int len = u16(b, p);
            if (len == 0 && (b[p[0]] & 0xFF) == 0x09) { p[0]++; return m; }
            String k = rawStr(b, p, len);
            m.put(k, read(b, p));
        }
    }

    private static int u16(byte[] b, int[] p) { int v = ((b[p[0]] & 0xFF) << 8) | (b[p[0] + 1] & 0xFF); p[0] += 2; return v; }
    private static long u32(byte[] b, int[] p) { long v = 0; for (int i = 0; i < 4; i++) v = (v << 8) | (b[p[0]++] & 0xFF); return v; }
    private static String rawStr(byte[] b, int[] p, int len) { String s = new String(b, p[0], len, StandardCharsets.UTF_8); p[0] += len; return s; }
}
