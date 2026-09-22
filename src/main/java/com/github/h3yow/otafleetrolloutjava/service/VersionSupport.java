package com.github.h3yow.otafleetrolloutjava.service;

/**
 * 简单语义化版本比较：按 "." 拆分，逐段比较；数字段按数值比较，非数字段按字符串比较，缺位补 0。
 * 例如 1.2 < 1.2.1，1.10 > 1.9。
 */
public final class VersionSupport {

    private VersionSupport() {
    }

    public static int compare(String a, String b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        String[] pa = a.trim().split("\\.");
        String[] pb = b.trim().split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            String sa = i < pa.length ? pa[i] : "0";
            String sb = i < pb.length ? pb[i] : "0";
            int c;
            if (sa.chars().allMatch(Character::isDigit) && sb.chars().allMatch(Character::isDigit)) {
                c = Long.compare(Long.parseLong(sa), Long.parseLong(sb));
            } else {
                c = sa.compareTo(sb);
            }
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    public static boolean gte(String version, String base) {
        return compare(version, base) >= 0;
    }
}
