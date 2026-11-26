package com.d.netgate;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 出口指纹。
 *
 * 加速器的「节点」是它自己的私有概念，Android 层看不到——它不会把
 * 「当前是日本 01」这件事告诉系统，也没有任何公开接口能读到。
 *
 * 能拿到的只有流量最终从哪出去：出口 IP、国家码、Cloudflare 边缘机房。
 * 实测日本 01 / 日本 02 两条线路，这三项完全一致（同一台落地机，只是
 * 路由不同），而且出口 IP 每天由 ISP 重新分配。所以：
 *
 *   - 不锁 IP：锁了第二天就失效；
 *   - 不锁机房：同城的多个节点在出口上没有区别；
 *   - 只锁国家：这正是「不是日本节点就关掉」的可实现形式。
 *
 * 判定必须同时确认「本应用的流量真的走了隧道」——分应用代理时
 * 本应用可能根本没被代理，拿到的会是本地出口，那时应该放行并标注，
 * 而不是把用户拦在门外。
 */
public final class NodeLock {

    /** 节点锁未启用。 */
    public static final int ALLOW_OPEN = 0;
    /** 出口匹配。 */
    public static final int ALLOW = 1;
    /** 拿不到出口信息（探测服务不可达等）：按放行处理。 */
    public static final int ALLOW_UNKNOWN = 2;
    /** 本应用的流量没走隧道（分应用代理）：按放行处理。 */
    public static final int ALLOW_NOTUNNEL = 3;
    /** 出口国家与锁定值不符：一票否决。 */
    public static final int BLOCK = 4;

    private static final String[] TRACES = {
            "https://www.cloudflare.com/cdn-cgi/trace",
            "https://1.1.1.1/cdn-cgi/trace",
    };
    /** Cloudflare 被挡时的兜底：只给 IP 和国家码，没有机房信息。 */
    private static final String IPINFO = "https://ipinfo.io/json";
    private static final String UA = "NetGate/2.10";
    private static final int MAX_BODY = 4096;
    private static final int TIMEOUT = 4500;

    private static volatile Exit cache;
    /** 本应用是否真的从隧道出去；null 表示还没测过。 */
    private static volatile Boolean tunnel;
    /** 最近一次真的去读了、但没读到的时刻；0 表示没失败过。 */
    private static volatile long lastFailAt;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final Deque<String> HISTORY = new ArrayDeque<>();

    private NodeLock() {
    }

    // ------------------------------------------------------------------ 数据

    public static final class Exit {
        public final String ip;
        /** 两位国家码，如 JP。 */
        public final String loc;
        /** Cloudflare 边缘机房三字码，如 NRT。 */
        public final String colo;
        /** 这次请求是否走了加速器隧道。 */
        public final boolean viaVpn;
        public final long at;

        Exit(String ip, String loc, String colo, boolean viaVpn, long at) {
            this.ip = ip;
            this.loc = loc;
            this.colo = colo;
            this.viaVpn = viaVpn;
            this.at = at;
        }

        public boolean usable() {
            return ip != null && !ip.isEmpty() && loc != null && !loc.isEmpty();
        }

        /** 「日本 · 东京」。 */
        public String where() {
            String city = city(colo);
            String nation = countryName(loc);
            return city.isEmpty() ? nation : nation + " · " + city;
        }

        /** 「日本 · 东京 (NRT)」。 */
        public String tag() {
            String w = where();
            return colo == null || colo.isEmpty() ? w : w + " (" + colo + ")";
        }
    }

    // ------------------------------------------------------------------ 探测

    /**
     * 重新获取一次出口指纹。会阻塞，必须在后台线程调用。
     * 返回 null 表示这次没拿到（网络不可达、trace 端点被挡等），
     * 此时调用方应保留上一次的结果。
     */
    public static Exit refresh(Context c) {
        if (!RUNNING.compareAndSet(false, true)) {
            // 另一个线程（前台服务 / 弹窗）正在读：等它读完直接复用，
            // 否则调用方会把「正在读」误当成「读不到」
            long until = System.currentTimeMillis() + 3000;
            while (RUNNING.get() && System.currentTimeMillis() < until) {
                try {
                    Thread.sleep(120);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
            return cache;
        }
        try {
            Network vpn = NetworkGate.vpnNetwork(c);
            boolean defaultVpn = isDefaultVpn(c);
            Exit e = null;

            if (vpn != null && !defaultVpn) {
                // 分应用模式：绑定隧道的请求与直连的请求，出口不同才说明被代理了。
                // 这个判断只在 VPN 重建后的第一次做，之后复用结论。
                Exit a = fetchOnce(vpn);
                if (a != null) {
                    if (tunnel == null) {
                        Exit b = fetchOnce(null);
                        tunnel = b == null || !a.ip.equals(b.ip);
                    }
                    e = new Exit(a.ip, a.loc, a.colo, tunnel, a.at);
                }
            } else {
                // 全局 VPN：系统默认网络就是隧道，绑定与否都走它
                Exit a = fetchOnce(vpn);
                if (a != null) {
                    tunnel = true;
                    e = new Exit(a.ip, a.loc, a.colo, true, a.at);
                }
            }

            if (e != null && e.usable()) {
                cache = e;
                lastFailAt = 0;
                push(e);
                return e;
            }
            lastFailAt = System.currentTimeMillis();
            return null;
        } finally {
            RUNNING.set(false);
        }
    }

    /** VPN 重建或断开时调用：隧道判定失效，需要重测。 */
    public static void reset() {
        tunnel = null;
    }

    public static Exit cached() {
        return cache;
    }

    /** 上次读取失败的时间；0 表示还没失败过。 */
    public static long lastFailAt() {
        return lastFailAt;
    }

    /**
     * 依次尝试两个 Cloudflare 域名，再退到 ipinfo。
     * 单一端点被加速器分流或 DNS 污染时，至少还有机会读到。
     */
    private static Exit fetchOnce(Network n) {
        for (int i = 0; i < TRACES.length; i++) {
            Exit e = fetchTrace(n, TRACES[i], i == 0 ? TIMEOUT : 3000);
            if (e != null && e.usable()) return e;
        }
        return fetchIpinfo(n);
    }

    private static Exit fetchTrace(Network n, String url, int timeout) {
        InputStream in = null;
        try {
            URLConnection conn = open(n, url, timeout);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Cache-Control", "no-cache");

            in = conn.getInputStream();
            String body = readAll(in);
            if (body == null || body.isEmpty()) return null;

            String ip = null, loc = null, colo = null;
            for (String line : body.split("\n")) {
                int i = line.indexOf('=');
                if (i <= 0) continue;
                String k = line.substring(0, i).trim();
                String v = line.substring(i + 1).trim();
                if (k.equals("ip")) ip = v;
                else if (k.equals("loc")) loc = v.toUpperCase(Locale.US);
                else if (k.equals("colo")) colo = v.toUpperCase(Locale.US);
            }
            if (ip == null || ip.isEmpty()) return null;
            return new Exit(ip, loc, colo, false, System.currentTimeMillis());
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static Exit fetchIpinfo(Network n) {
        InputStream in = null;
        try {
            URLConnection conn = open(n, IPINFO, 3500);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Cache-Control", "no-cache");

            in = conn.getInputStream();
            String body = readAll(in);
            if (body == null || body.isEmpty()) return null;
            String ip = json(body, "ip");
            String loc = json(body, "country");
            if (ip == null || ip.isEmpty()) return null;
            return new Exit(ip, loc == null ? null : loc.toUpperCase(Locale.US),
                    null, false, System.currentTimeMillis());
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static URLConnection open(Network n, String spec, int timeout) throws Exception {
        URL url = new URL(spec);
        URLConnection conn = n != null ? n.openConnection(url) : url.openConnection();
        conn.setConnectTimeout(timeout);
        conn.setReadTimeout(timeout);
        conn.setUseCaches(false);
        conn.setRequestProperty("User-Agent", UA);
        return conn;
    }

    /** 极简 JSON 取值：只需要 "key":"value" 这一种形状。 */
    private static String json(String body, String key) {
        String k = "\"" + key + "\"";
        int i = body.indexOf(k);
        if (i < 0) return null;
        int c = body.indexOf(':', i + k.length());
        if (c < 0) return null;
        int q1 = body.indexOf('"', c + 1);
        if (q1 < 0) return null;
        int q2 = body.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return body.substring(q1 + 1, q2);
    }

    private static String readAll(InputStream in) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(512);
            byte[] buf = new byte[512];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > MAX_BODY) break;
            }
            return bos.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isDefaultVpn(Context c) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 判定

    /**
     * 判定当前出口是否满足锁定。
     *
     * 只回答"出口对不对"，不回答"这个应用要不要受约束"——
     * 后者由调用方用 {@link Prefs#nodeBound} 判断。分层的结果是：
     * 普通受保护应用根本不进来问，只有被单独指定的那几个才会被这里拦下。
     */
    public static int verdict(Context c) {
        String region = Prefs.nodeRegion(c);
        if (region == null || region.isEmpty()) return ALLOW_OPEN;

        Exit e = cache;
        if (e == null || !e.usable()) return ALLOW_UNKNOWN;
        if (!e.viaVpn) return ALLOW_NOTUNNEL;
        if (!region.equalsIgnoreCase(e.loc)) return BLOCK;
        return ALLOW;
    }

    /** 拦截弹窗里那句「当前 X，仅允许 Y」。 */
    public static String blockDetail(Context c) {
        Exit e = cache;
        String want = Prefs.nodeLabel(c);
        if (e == null || !e.usable()) return "节点未知，仅允许 " + want;
        return "当前 " + e.tag() + "，仅允许 " + want;
    }

    // -------------------------------------------------------------- 历史记录

    private static void push(Exit e) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(e.at));
        String line = time + "   " + e.where() + "   " + e.ip;
        synchronized (HISTORY) {
            HISTORY.remove(line);
            HISTORY.addFirst(line);
            while (HISTORY.size() > 5) HISTORY.removeLast();
        }
    }

    /** 最近 5 次出口，新的在前。排查误拦时用。 */
    public static String historyText() {
        synchronized (HISTORY) {
            if (HISTORY.isEmpty()) return "还没有记录";
            StringBuilder sb = new StringBuilder();
            for (String s : HISTORY) sb.append(s).append('\n');
            return sb.toString().trim();
        }
    }

    // ------------------------------------------------------------ 地区名称表

    public static String countryName(String loc) {
        if (loc == null) return "未知";
        switch (loc) {
            case "JP": return "日本";
            case "US": return "美国";
            case "HK": return "中国香港";
            case "TW": return "中国台湾";
            case "MO": return "中国澳门";
            case "CN": return "中国大陆";
            case "SG": return "新加坡";
            case "KR": return "韩国";
            case "GB": return "英国";
            case "DE": return "德国";
            case "NL": return "荷兰";
            case "FR": return "法国";
            case "CA": return "加拿大";
            case "AU": return "澳大利亚";
            case "TH": return "泰国";
            case "MY": return "马来西亚";
            case "PH": return "菲律宾";
            case "VN": return "越南";
            case "IN": return "印度";
            case "AE": return "阿联酋";
            case "RU": return "俄罗斯";
            case "BR": return "巴西";
            case "TR": return "土耳其";
            case "ID": return "印度尼西亚";
            default: return loc;
        }
    }

    private static String city(String colo) {
        if (colo == null) return "";
        switch (colo) {
            case "NRT": return "东京";
            case "KIX": return "大阪";
            case "HKG": return "香港";
            case "TPE": return "台北";
            case "SIN": return "新加坡";
            case "SJC": return "圣何塞";
            case "LAX": return "洛杉矶";
            case "SEA": return "西雅图";
            case "IAD": return "华盛顿";
            case "EWR": return "纽约";
            case "LHR": return "伦敦";
            case "FRA": return "法兰克福";
            case "AMS": return "阿姆斯特丹";
            case "CDG": return "巴黎";
            case "ICN": return "首尔";
            case "BKK": return "曼谷";
            case "KUL": return "吉隆坡";
            case "SYD": return "悉尼";
            case "MNL": return "马尼拉";
            case "DEL": return "孟买";
            case "DXB": return "迪拜";
            case "SVO": return "莫斯科";
            case "GRU": return "圣保罗";
            case "YYZ": return "多伦多";
            default: return colo;
        }
    }
}
