package com.d.netgate;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

/** 最近若干条运行日志，供诊断页展示。只存在内存里，不写盘、不上传。 */
public final class Diag {

    private static final int MAX = 80;
    private static final LinkedList<String> LINES = new LinkedList<>();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    /** 最近发生过的前台切换，用来确认某个应用的真实包名。 */
    private static final int FG_MAX = 16;
    private static final LinkedList<String> FG = new LinkedList<>();

    /** 无障碍服务最后一次心跳（毫秒）。用于判断服务是否被系统回收。 */
    private static volatile long lastTickAt;

    private Diag() {
    }

    public static void log(String s) {
        synchronized (LINES) {
            LINES.add(FMT.format(new Date()) + "  " + s);
            while (LINES.size() > MAX) LINES.removeFirst();
        }
    }

    public static String dump() {
        synchronized (LINES) {
            StringBuilder sb = new StringBuilder();
            for (String s : LINES) sb.append(s).append('\n');
            return sb.toString();
        }
    }

    /** 记录一次前台切换。同一个包名连续出现只留一条，避免刷屏。 */
    public static void foreground(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        String line = FMT.format(new Date()) + "  " + pkg;
        synchronized (FG) {
            for (String s : FG) {
                if (s.endsWith("  " + pkg)) {
                    // 已在列表里，挪到末尾表示仍然活跃
                    FG.remove(s);
                    break;
                }
            }
            FG.add(line);
            while (FG.size() > FG_MAX) FG.removeFirst();
        }
    }

    public static String foregroundDump() {
        synchronized (FG) {
            if (FG.isEmpty()) return "（还没记录到任何前台切换，请先打开几个应用再回来看）\n";
            StringBuilder sb = new StringBuilder();
            for (String s : FG) sb.append(s).append('\n');
            return sb.toString();
        }
    }

    /** 最近一次记录的前台包名，没有则返回空串。 */
    public static String lastForeground() {
        synchronized (FG) {
            if (FG.isEmpty()) return "";
            String s = FG.getLast();
            int i = s.indexOf("  ");
            return i >= 0 ? s.substring(i + 2) : s;
        }
    }

    public static void heartbeat() {
        lastTickAt = System.currentTimeMillis();
    }

    /** 距离上一次心跳的秒数；负数表示服务从没跑过。 */
    public static long heartbeatAge() {
        long t = lastTickAt;
        if (t == 0) return -1;
        return (System.currentTimeMillis() - t) / 1000;
    }
}
