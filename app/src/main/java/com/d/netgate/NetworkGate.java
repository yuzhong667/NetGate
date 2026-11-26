package com.d.netgate;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 网络判定，分两层：
 *
 * 第一层「通道」：默认网络是否带 TRANSPORT_VPN，或系统里是否存在 tun/ppp 网卡。
 *
 * 第二层「可用」：把连接强制绑定到 VPN 网络后，做一次真正的端到端握手：
 *   TCP 连接 → TLS 握手（带 SNI）→ 发送 HTTP 请求 → 读到响应首行。
 *
 * 为什么不能只测 TCP：
 *   加速器通常是 fake-ip + tun 本地代答模式，手机发出 SYN 后由 tun 自己回 SYN-ACK，
 *   包根本没离开设备。这时哪怕节点已超时，TCP 连接也会在 1–2 ms 内“成功”。
 *   只有 TLS 握手需要远端私钥参与，本地伪造不了，才能证明隧道真的在转发。
 */
public final class NetworkGate {

    /**
     * 默认目标要选「只有走加速器才连得上」的地址。
     * 1.1.1.1 这类公共 DNS 多数加速器会走直连，加速器挂了照样通，测不出真实状态。
     */
    public static final String DEFAULT_TARGETS =
            "www.google.com:443, www.youtube.com:443";

    /** 单次探测的目标上限。并行握手，所以数量不会线性拖慢速度。 */
    public static final int MAX_TARGETS = 32;

    /** 探测线程池常驻，避免每次检查都新建线程。 */
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "netgate-probe");
        t.setDaemon(true);
        return t;
    });

    private NetworkGate() {
    }

    // ---------------------------------------------------------------- 通道判定

    public static boolean hasVpn(Context c) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network n = cm.getActiveNetwork();
            if (n != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return true;
                }
            }
            return hasVpnInterface();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean hasVpnInterface() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    String name = ni.getName();
                    if (name != null && (name.startsWith("tun") || name.startsWith("ppp"))
                            && ni.isUp() && !ni.getInterfaceAddresses().isEmpty()) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 找到 VPN 类型的 Network 对象，用于绑定 socket。 */
    public static Network vpnNetwork(Context c) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return n;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static String describe(Context c) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "无法读取网络状态";
            Network n = cm.getActiveNetwork();
            if (n == null) return "无网络连接";
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null) return "网络状态未知";

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || hasVpnInterface()) {
                return "VPN 已连接";
            }

            String base;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) base = "Wi-Fi";
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) base = "移动数据";
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) base = "以太网";
            else base = "其他网络";
            return base + " · 未连接 VPN";
        } catch (Throwable t) {
            return "网络状态未知";
        }
    }

    // -------------------------------------------------------------- 连通性探测

    public static final class Probe {
        /** 系统里是否存在 VPN 通道。 */
        public final boolean vpnPresent;
        /** 是否放行：至少一个目标端到端可用，或探测被系统路由拦下（分应用加速器）。 */
        public final boolean ok;
        /** 探测无法得出有效结论，例如加速器只代理游戏、本应用无权走它的隧道。 */
        public final boolean inconclusive;
        public final String detail;
        /** 最快一次端到端握手的耗时，-1 表示没有成功。 */
        public final long rtt;
        public final int total;
        public final int reached;
        /** 每个目标的逐行结果，用于设置页与遮罩展示。 */
        public final List<String> lines;

        Probe(boolean vpnPresent, boolean ok, boolean inconclusive, String detail, long rtt,
              int total, int reached, List<String> lines) {
            this.vpnPresent = vpnPresent;
            this.ok = ok;
            this.inconclusive = inconclusive;
            this.detail = detail;
            this.rtt = rtt;
            this.total = total;
            this.reached = reached;
            this.lines = lines;
        }
    }

    /**
     * 真实探测一次。会阻塞当前线程，必须在后台线程调用。
     * 多个目标并行握手，任意一个端到端可用即视为 VPN 可用。
     */
    public static Probe probe(Context c) {
        return probe(c, 0);
    }

    /** timeoutOverride > 0 时覆盖灵敏度设定的超时，用于失败后的快速复测。 */
    public static Probe probe(Context c, int timeoutOverride) {
        final Network vpn = vpnNetwork(c);
        final boolean present = vpn != null || hasVpnInterface();
        final List<Target> targets = parse(Prefs.targets(c));
        final List<String> lines = new ArrayList<>();

        if (targets.isEmpty()) {
            return new Probe(present, false, false, "未配置有效的检测目标", -1, 0, 0, lines);
        }
        if (!present) {
            for (Target t : targets) lines.add(t.label() + "  ·  未检测到 VPN 通道");
            return new Probe(false, false, false, "未检测到 VPN 通道", -1, targets.size(), 0, lines);
        }

        final int timeout = timeoutOverride > 0 ? timeoutOverride : Prefs.probeTimeout(c);
        // 默认网络本身就是 VPN 时，即使绑定失败，普通连接也仍然走隧道
        final boolean vpnIsDefault = isDefaultVpn(c);

        CompletionService<Result> cs = new ExecutorCompletionService<>(POOL);
        for (final Target t : targets) {
            cs.submit(() -> probeTarget(vpn, vpnIsDefault, t, timeout));
        }

        long deadline = System.currentTimeMillis() + timeout + 800L;
        long bestRtt = -1;
        int reached = 0;
        Map<String, Result> got = new HashMap<>();

        for (int i = 0; i < targets.size(); i++) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) break;
            try {
                Future<Result> f = cs.poll(remain, TimeUnit.MILLISECONDS);
                if (f == null) break;
                Result r = f.get();
                if (r == null) continue;
                got.put(r.label, r);
                if (r.ok) {
                    reached++;
                    if (bestRtt < 0 || r.rtt < bestRtt) bestRtt = r.rtt;
                }
            } catch (Throwable ignored) {
                break;
            }
        }

        for (Target t : targets) {
            Result r = got.get(t.label());
            if (r == null) {
                lines.add(t.label() + "  ·  超时");
            } else if (r.ok) {
                lines.add(t.label() + "  ·  " + r.stage + " " + r.rtt + " ms");
            } else {
                lines.add(t.label() + "  ·  " + r.error);
            }
        }

        boolean reachable = reached > 0;
        boolean inconclusive = false;
        String detail;

        if (reachable) {
            detail = reached + "/" + targets.size() + " 个目标端到端可用 · 最快 "
                    + bestRtt + " ms";
        } else {
            // 所有目标都被系统路由挡在隧道外。
            // 只有「默认网络不是 VPN」时才能解释成分应用加速器——它只给游戏建了路由，
            // 本应用没资格走这条隧道，此时放行。
            // 若默认网络本身就是 VPN，隧道却拒绝转发，那就是真出问题了，必须判失败。
            boolean allBlocked = true;
            boolean anyLocal = false;
            for (Target t : targets) {
                Result r = got.get(t.label());
                if (r == null || r.kind != KIND_ROUTE) {
                    allBlocked = false;
                }
                if (r != null && r.localReply) anyLocal = true;
            }
            inconclusive = allBlocked && !vpnIsDefault;
            if (inconclusive) {
                detail = "加速器为分应用模式，本应用无权走它的隧道";
            } else if (anyLocal) {
                detail = "节点未转发：本地代答了连接，远端无响应";
            } else {
                detail = targets.size() + " 个目标全部握手失败";
            }
        }
        return new Probe(true, reachable || inconclusive, inconclusive,
                detail, bestRtt, targets.size(), reached, lines);
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

    /** 供设置页显示"3 个目标"之类的摘要。 */
    public static String summary(String raw) {
        int n = parse(raw).size();
        return n + " 个目标";
    }

    // ------------------------------------------------------------------ 内部

    private static final int KIND_ROUTE = 2;
    private static final int KIND_TIMEOUT = 1;
    private static final int KIND_DNS = 3;
    private static final int KIND_TLS = 4;
    private static final int KIND_HTTP = 5;

    /**
     * 单个目标的端到端探测。
     *
     * 443 端口一律走 TLS：TCP 可以本地代答，TLS 不能。
     * 非 443 端口只做 TCP，结果会标注「仅 TCP」，可信度低。
     */
    private static Result probeTarget(Network n, boolean vpnIsDefault, Target t, int timeoutMs) {
        Socket raw = new Socket();
        SSLSocket ssl = null;
        long tcpMs = -1;
        try {
            raw.setTcpNoDelay(true);
            if (n != null) {
                try {
                    n.bindSocket(raw);
                } catch (Throwable e) {
                    // 分应用加速器不给本应用路由时，绑不上去是正常的；
                    // 但如果默认网络就是 VPN，不绑定也照样走隧道。
                    if (!vpnIsDefault) {
                        return Result.fail(t, "无法绑定 VPN 网络", KIND_ROUTE);
                    }
                }
            }

            InetSocketAddress addr = resolve(n, t);
            if (addr == null || addr.isUnresolved()) {
                return Result.fail(t, "地址无法解析", KIND_DNS);
            }

            final long deadline = System.currentTimeMillis() + timeoutMs;

            long t0 = System.nanoTime();
            raw.connect(addr, remain(deadline));
            tcpMs = (System.nanoTime() - t0) / 1_000_000L;

            if (t.port != 443) {
                return Result.ok(t, tcpMs, tcpMs, "TCP");
            }

            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            ssl = (SSLSocket) f.createSocket(raw, t.host, t.port, true);
            ssl.setSoTimeout(remain(deadline));
            try {
                SSLParameters p = ssl.getSSLParameters();
                p.setServerNames(Collections.singletonList(new SNIHostName(t.host)));
                ssl.setSSLParameters(p);
            } catch (Throwable ignored) {
                // SNI 设置失败不致命，继续握手
            }

            long t1 = System.nanoTime();
            ssl.startHandshake();
            long tlsMs = (System.nanoTime() - t1) / 1_000_000L;

            // TLS 通了还不够，必须拿到应用层响应，排除“假 TLS 端点”
            String req = "HEAD / HTTP/1.1\r\nHost: " + t.host + "\r\n"
                    + "User-Agent: NetGate/2.8\r\nConnection: close\r\n\r\n";
            ssl.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
            ssl.getOutputStream().flush();

            String status = readLine(ssl.getInputStream(), 256, deadline);
            if (status == null) {
                return Result.fail(t, "TLS 已通但无 HTTP 响应", KIND_HTTP, tcpMs);
            }
            if (!status.startsWith("HTTP/")) {
                return Result.fail(t, "非 HTTP 响应", KIND_HTTP, tcpMs);
            }
            int code = parseStatus(status);
            if (code < 100 || code >= 600) {
                return Result.fail(t, "响应异常 " + trim(status), KIND_HTTP, tcpMs);
            }

            long total = tcpMs + tlsMs;
            return Result.ok(t, total, tcpMs, "TLS");

        } catch (SocketTimeoutException e) {
            return Result.fail(t, localHint(tcpMs, "握手超时"), KIND_TIMEOUT, tcpMs);
        } catch (SSLException e) {
            String m = String.valueOf(e.getMessage()).toLowerCase();
            if (m.contains("certificate") || m.contains("cert")) {
                return Result.fail(t, localHint(tcpMs, "证书异常"), KIND_TLS, tcpMs);
            }
            return Result.fail(t, localHint(tcpMs, "TLS 握手失败"), KIND_TLS, tcpMs);
        } catch (Throwable e) {
            String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (m.contains("unreachable") || m.contains("network is down")
                    || m.contains("permission") || m.contains("not permitted")) {
                return Result.fail(t, "隧道拒绝转发", KIND_ROUTE, tcpMs);
            }
            return Result.fail(t, e.getClass().getSimpleName(), KIND_DNS, tcpMs);
        } finally {
            if (ssl != null) {
                try {
                    ssl.close();
                } catch (Throwable ignored) {
                }
            }
            try {
                raw.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** TCP 极快但后面失败，说明 tun 在本地应了 SYN，包没出去。 */
    private static String localHint(long tcpMs, String base) {
        if (tcpMs >= 0 && tcpMs <= 5) {
            return base + "（TCP " + tcpMs + " ms 为本地代答）";
        }
        return base;
    }

    private static int remain(long deadline) {
        return (int) Math.max(300, deadline - System.currentTimeMillis());
    }

    /** 读一行，最多 max 字节，避免被畸形响应拖住。 */
    private static String readLine(InputStream in, int max, long deadline) {
        StringBuilder sb = new StringBuilder();
        try {
            while (sb.length() < max && System.currentTimeMillis() < deadline) {
                int b = in.read();
                if (b < 0) break;
                if (b == '\n') break;
                if (b != '\r') sb.append((char) b);
            }
        } catch (Throwable ignored) {
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static int parseStatus(String line) {
        try {
            int sp = line.indexOf(' ');
            if (sp < 0 || sp + 3 >= line.length()) return -1;
            return Integer.parseInt(line.substring(sp + 1, sp + 4));
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String trim(String s) {
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    /**
     * 解析目标地址。
     * 域名优先用 VPN 网络自带的 DNS 解析，避免本地 DNS 污染导致假失败；
     * IP 字面量直接构造，不查 DNS。
     */
    private static InetSocketAddress resolve(Network n, Target t) {
        String host = t.host;
        boolean isIp = host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}") || host.contains(":");
        if (isIp) {
            InetSocketAddress a = new InetSocketAddress(host, t.port);
            return a.isUnresolved() ? null : a;
        }
        if (n != null && android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                InetAddress[] addrs = n.getAllByName(host);
                if (addrs != null && addrs.length > 0) {
                    return new InetSocketAddress(addrs[0], t.port);
                }
            } catch (Throwable ignored) {
            }
        }
        return new InetSocketAddress(host, t.port);
    }

    private static List<Target> parse(String raw) {
        List<Target> out = new ArrayList<>();
        if (raw == null) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (String part : raw.split("[,;\\s]+")) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            String host = s;
            int port = 443;
            int idx = s.lastIndexOf(':');
            if (idx > 0) {
                try {
                    port = Integer.parseInt(s.substring(idx + 1));
                    host = s.substring(0, idx);
                } catch (Throwable ignored) {
                    port = 443;
                    host = s;
                }
            }
            if (host.isEmpty() || port <= 0 || port > 65535) continue;
            String key = host + ":" + port;
            if (!seen.add(key)) continue;
            Target t = new Target();
            t.host = host;
            t.port = port;
            out.add(t);
            if (out.size() >= MAX_TARGETS) break;
        }
        return out;
    }

    private static final class Target {
        String host;
        int port;

        String label() {
            return host + ":" + port;
        }
    }

    private static final class Result {
        String label;
        long rtt;
        String error;
        String stage;
        int kind;
        boolean ok;
        /** TCP 在 5ms 内就完成，说明很可能是 tun 本地代答。 */
        boolean localReply;

        static Result ok(Target t, long rtt, long tcpMs, String stage) {
            Result r = new Result();
            r.label = t.label();
            r.rtt = rtt;
            r.stage = stage;
            r.kind = 0;
            r.ok = true;
            r.localReply = tcpMs >= 0 && tcpMs <= 5;
            return r;
        }

        static Result fail(Target t, String error, int kind) {
            return fail(t, error, kind, -1);
        }

        static Result fail(Target t, String error, int kind, long tcpMs) {
            Result r = new Result();
            r.label = t.label();
            r.rtt = -1;
            r.error = error;
            r.kind = kind;
            r.ok = false;
            // 只把「TCP 秒通但后续失败」标记为本地代答
            r.localReply = tcpMs >= 0 && tcpMs <= 5;
            return r;
        }
    }
}
