package com.github.h3yow.otafleetrolloutjava.util;

/** 点分数字版本比较工具，如 1.2.10 > 1.2.9 */
public final class Versions {

    private Versions() {
    }

    /** 返回负数/0/正数，分别表示 a 小于/等于/大于 b */
    public static int compare(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? parseSegment(pa[i]) : 0;
            int vb = i < pb.length ? parseSegment(pb[i]) : 0;
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        return 0;
    }

    /** source 是否满足最低源版本约束（source >= minSource） */
    public static boolean satisfiesMinSource(String source, String minSource) {
        if (minSource == null || minSource.isBlank()) {
            return true;
        }
        return compare(source, minSource) >= 0;
    }

    private static int parseSegment(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
