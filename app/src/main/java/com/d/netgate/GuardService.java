package com.d.netgate;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.PathInterpolator;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * 前台监听 + 持续连通性巡检。
 *
 * 受保护应用处于前台时：
 *   1. 没有 VPN 通道       → 立即拦截；
 *   2. 有通道但探测连续失败 → 判定加速器已掉线，拦截并结束进程。
 */
public class GuardService extends AccessibilityService {

    private static final int R_NO_VPN = 0;
    private static final int R_UNREACHABLE = 1;

    private static final long TICK_BUSY = 800;
    private static final long TICK_WATCH = 1500;
    private static final long TICK_IDLE = 2500;
    /** VPN 不通时用这个节奏：不追求快，但要坚持把后台驻留的一起清掉。 */
    private static final long TICK_SWEEP = 3000;
    /** 两次后台清扫的最小间隔，避免每个 tick 都起进程。 */
    private static final long SWEEP_GAP = 5000;

    /**
     * 无 VPN 的宽限期。
     *
     * 加速器切节点时通道会闪断 1～2 秒，原来一断就杀，玩家会被生生踢出去。
     * 2.5 秒足够跨过切换空档，又不至于让用户白等。
     */
    private static final long NOVPN_GRACE = 2500;
    /** 后台清扫是「连坐」，比前台更保守：确认 6 秒没恢复才动手。 */
    private static final long NOVPN_SWEEP = 6000;
    /** 自动恢复被禁用应用前，两次真实探测的最小间隔。 */
    private static final long RESTORE_GAP = 12000;

    /** 打开应用时：底部弹出卡片。 */
    private static final int STYLE_SHEET = 0;
    /** 使用中掉线：顶部横条，不遮挡操作区。 */
    private static final int STYLE_BANNER = 1;

    /** 36% 黑，只压暗不糊化 —— 无障碍悬浮层无法实时模糊下层画面。 */
    private static final int SCRIM = 0x5C000000;

    private static final int RED = Ui.RED_DEEP;
    private static final int GREEN = Ui.GREEN_DEEP;
    private static final int AMBER = Ui.AMBER_DEEP;

    private static final ExecutorService PROBE_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "netgate-guard");
        t.setDaemon(true);
        return t;
    });

    private final java.util.Random rnd = new java.util.Random();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable ticker = this::onTick;

    private static GuardService instance;

    private WindowManager wm;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCallback;
    private View overlay;
    private View overlayCard;
    private TextView overlayStatus;
    private TextView overlaySub;
    private int overlayStyle = -1;
    private long bannerStamp;
    private boolean blocking;
    /** 拦截后等一次成功校验，用来弹"已恢复"的绿条。 */
    private boolean pendingRecover;
    private boolean probeRunning;
    private boolean destroyed;
    /** 通道消失的起点；为 0 表示当前有 VPN。 */
    private long noVpnSince;
    /** 正在为「恢复被禁用应用」做一次真探，避免并发重复探。 */
    private boolean restoreChecking;
    private String currentPkg = "";
    private int failStreak;
    private long nextProbeAt;
    private long blockSeq;
    private long lastBlockAt;
    private long lastCheckAt;
    private long lastWindowSyncAt;
    private long lastSweepAt;
    private long lastRestoreAt;
    private String lastState = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        destroyed = false;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                onNetworkChanged();
            }

            @Override
            public void onLost(Network network) {
                onNetworkChanged();
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                onNetworkChanged();
            }
        };
        try {
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Throwable ignored) {
        }

        ShizukuShell.init(this);
        try {
            Shizuku.addBinderReceivedListenerSticky(() -> ShizukuShell.init(this));
        } catch (Throwable ignored) {
        }

        Diag.log("无障碍服务已连接");

        main.removeCallbacks(ticker);
        main.postDelayed(ticker, 600);
    }

    // ------------------------------------------------------------ 前台与巡检

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence p = event.getPackageName();
        if (p == null) return;
        String pkg = p.toString();
        if (pkg.equals(getPackageName())) return;

        currentPkg = pkg;
        Diag.foreground(pkg);
        if (!Prefs.guarded(this).contains(pkg)) return;
        if (SysApps.isCritical(this, pkg)) return;
        Diag.log("前台 " + pkg);

        if (!NetworkGate.hasVpn(this)) {
            long now = System.currentTimeMillis();
            if (noVpnSince == 0) noVpnSince = now;
            if (now - noVpnSince >= NOVPN_GRACE) {
                block(pkg, R_NO_VPN, null);
            } else {
                // 刚断：先等满宽限期，别把切节点的空档当成掉线
                schedule(NOVPN_GRACE - (now - noVpnSince) + 80);
            }
            return;
        }
        noVpnSince = 0;
        if (!Prefs.probe(this)) return;
        if (!probeRunning) {
            nextProbeAt = 0;
            startProbe(pkg);
        }
    }

    private void onNetworkChanged() {
        long now = System.currentTimeMillis();
        if (now - lastCheckAt < 250) return;
        lastCheckAt = now;
        main.post(this::onTick);
    }

    private void onTick() {
        if (destroyed) return;
        Diag.heartbeat();

        long now = System.currentTimeMillis();
        Set<String> guarded = Prefs.guarded(this);

        // 无障碍事件偶尔会漏，定期用窗口列表校正前台包名
        if (now - lastWindowSyncAt > 4000) {
            lastWindowSyncAt = now;
            String fg = foregroundPackage();
            if (fg != null && !fg.isEmpty() && !fg.equals(getPackageName())
                    && !fg.equals(currentPkg)) {
                Diag.foreground(fg);
                currentPkg = fg;
            }
        }

        String pkg = currentPkg;
        // 系统关键应用即使在名单里（历史数据、手工改配置）也一律不拦
        boolean active = pkg != null && !pkg.isEmpty() && guarded.contains(pkg)
                && !SysApps.isCritical(this, pkg);
        // 注意：VPN 状态要始终检查，不能只在受管应用位于前台时才算 ——
        // 否则应用一退到后台，巡检就完全放手，用户还能从最近任务切回去。
        boolean vpn = NetworkGate.hasVpn(this);
        if (vpn) {
            noVpnSince = 0;
        } else if (noVpnSince == 0) {
            noVpnSince = now;
            Diag.log("VPN 通道消失 · 进入宽限期");
        }

        boolean grace = !vpn && now - noVpnSince < NOVPN_GRACE;
        String state = !active ? (vpn ? "idle" : "idle-novpn")
                : (vpn ? "watch" : (grace ? "novpn-grace" : "novpn"));
        if (!state.equals(lastState)) {
            lastState = state;
            Diag.log("状态 " + state + (active ? " · " + pkg : ""));
        }

        // 通道回来了先别急着恢复：必须探测通过，否则会「启用→拦截→再挂起」拉锯
        if (vpn && !Prefs.disabled(this).isEmpty() && now - lastRestoreAt > RESTORE_GAP) {
            lastRestoreAt = now;
            verifyAndRestore();
        }

        if (!active) {
            failStreak = 0;
            if (!vpn) {
                maybeSweep(guarded, now);
                schedule(TICK_SWEEP);
            } else {
                schedule(TICK_IDLE);
            }
            return;
        }

        if (!vpn) {
            if (grace) {
                // 还在宽限里：不打断用户，到点再判
                failStreak = 0;
                schedule(NOVPN_GRACE - (now - noVpnSince) + 80);
                return;
            }
            failStreak = 0;
            block(pkg, R_NO_VPN, null);
            maybeSweep(guarded, now);
            schedule(TICK_SWEEP);
            return;
        }

        if (!Prefs.probe(this)) {
            failStreak = 0;
            schedule(TICK_WATCH);
            return;
        }

        if (!probeRunning && now >= nextProbeAt) {
            startProbe(pkg);
        }
        schedule(TICK_BUSY);
    }

    private void schedule(long delay) {
        if (destroyed) return;
        main.removeCallbacks(ticker);
        main.postDelayed(ticker, delay);
    }

    /**
     * 把仍在后台驻留的受保护应用一并强停。
     *
     * 触发条件只有「当前没有可用 VPN」——只有这时才需要清场。
     * 有 VPN 时完全不碰后台，避免误杀用户正常挂着加速器的应用。
     */
    private void maybeSweep(Set<String> guarded, long now) {
        maybeSweep(guarded, now, false);
    }

    /**
     * @param confirmed true 表示失败已经由「连续多次探测失败」确认过，
     *                  不必再等通道宽限；false 表示只是当前读不到 VPN，要等够 6 秒。
     */
    private void maybeSweep(Set<String> guarded, long now, boolean confirmed) {
        if (!Prefs.kill(this)) return;
        if (guarded == null || guarded.isEmpty()) return;
        // 通道刚断的那几秒很可能是切节点，等确认稳定了再清场
        if (!confirmed && (noVpnSince == 0 || now - noVpnSince < NOVPN_SWEEP)) return;
        if (now - lastSweepAt < SWEEP_GAP) return;
        lastSweepAt = now;
        ShizukuShell.sweepBackground(getApplicationContext(), guarded);
    }

    /**
     * 恢复被挂起/禁用的应用前，先做一次完整的连通性探测。
     *
     * 只看「VPN 通道回来了」不够：通道在、节点是坏的，恢复后立刻又被拦，
     * 于是启用 → 拦截 → 挂起 来回拉锯，应用图标一直闪。
     */
    private void verifyAndRestore() {
        if (restoreChecking || destroyed) return;
        restoreChecking = true;
        PROBE_EXEC.execute(() -> {
            boolean ok = false;
            try {
                NetworkGate.Probe p = NetworkGate.probe(this);
                ok = p != null && p.ok && !p.inconclusive;
            } catch (Throwable ignored) {
            }
            final boolean good = ok;
            main.post(() -> {
                restoreChecking = false;
                if (good && !destroyed) restoreDisabled();
            });
        });
    }

    /** VPN 恢复且探测通过后，把被 pm disable-user 禁用的应用重新启用。 */
    private void restoreDisabled() {
        final Set<String> dis = Prefs.disabled(this);
        if (dis.isEmpty()) return;
        new Thread(() -> {
            int n = 0;
            for (String p : dis) {
                String r = ShizukuShell.unsuspendPackage(p);
                if (!r.contains("exit=0")) {
                    r = ShizukuShell.enablePackage(p);
                }
                if (r.contains("exit=0")) {
                    Prefs.unmarkDisabled(getApplicationContext(), p);
                    n++;
                }
            }
            if (n > 0) {
                final int cnt = n;
                main.post(() -> {
                    dismiss();
                    Toast.makeText(GuardService.this,
                            "网络已恢复，已重新启用 " + cnt + " 个应用", Toast.LENGTH_SHORT).show();
                });
            }
        }, "netgate-enable").start();
    }

    private String foregroundPackage() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null) return null;
            for (AccessibilityWindowInfo w : windows) {
                if (w == null || !w.isActive()) continue;
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                CharSequence p = root.getPackageName();
                if (p != null) return p.toString();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ---------------------------------------------------------------- 探测

    private void startProbe(final String pkg) {
        probeRunning = true;
        // 兜底：万一回调丢了，15 秒后允许重新发起
        nextProbeAt = System.currentTimeMillis() + 15000;

        // 首次失败后，复测把超时压短，尽快给出结论
        final int timeout = failStreak > 0
                ? Math.max(900, Prefs.probeTimeout(this) / 2)
                : 0;

        Diag.log("探测开始" + (timeout > 0 ? "（快速复测）" : ""));

        PROBE_EXEC.execute(() -> {
            NetworkGate.Probe r;
            try {
                r = NetworkGate.probe(this, timeout);
            } catch (Throwable t) {
                r = null;
            }
            final NetworkGate.Probe result = r;
            main.post(() -> onProbeDone(pkg, result));
        });
    }

    private void onProbeDone(String pkg, NetworkGate.Probe p) {
        probeRunning = false;
        if (destroyed) return;

        long now = System.currentTimeMillis();

        if (p == null) {
            nextProbeAt = now + Prefs.probeDelay(this);
            return;
        }

        if (p.ok) {
            boolean wasFail = failStreak > 0;
            failStreak = 0;
            nextProbeAt = now + Prefs.probeDelay(this);
            if (wasFail && pendingRecover && now - lastBlockAt < 60000) {
                pendingRecover = false;
                final int n = Prefs.guarded(this).size();
                final long rtt = p.rtt;
                main.post(() -> {
                    dismiss();
                    showRecovered(n, rtt);
                });
            }
            return;
        }

        failStreak++;
        // 失败后马上补测一次，确认不是单次抖动；同样带随机，别形成固定节拍
        nextProbeAt = now + 550 + rnd.nextInt(500);
        Diag.log("探测失败 · " + p.detail + " · 连续 " + failStreak + "/"
                + Prefs.probeFails(this));

        if (failStreak < Prefs.probeFails(this)) return;

        boolean stillForeground = currentPkg != null
                && currentPkg.equals(pkg)
                && Prefs.guarded(this).contains(pkg);
        Diag.log("达到拦截阈值 · 前台仍是 " + pkg + " = " + stillForeground);
        if (stillForeground) {
            // 应用正在用，突然掉线：顶部横条，不遮操作区
            block(pkg, p.vpnPresent ? R_UNREACHABLE : R_NO_VPN, p.detail, STYLE_BANNER);
        }
        // 已确认网络不可用：不管前台切到哪，后台驻留的受保护应用也一起清掉
        maybeSweep(Prefs.guarded(this), now, true);
    }

    // ---------------------------------------------------------------- 拦截

    private void block(final String pkg, final int reason, final String detail) {
        block(pkg, reason, detail, STYLE_SHEET);
    }

    private void block(final String pkg, final int reason, final String detail,
                       final int style) {
        long now = System.currentTimeMillis();
        Diag.log("拦截 " + pkg + " · " + (reason == R_UNREACHABLE ? "网络不通" : "无 VPN")
                + (style == STYLE_BANNER ? " · 横条" : " · 弹窗"));
        pendingRecover = true;

        // 已经有一条提示在显示：
        //   横条 → 弹窗属于升级（用户确实又点开了应用，需要完整说明）；
        //   其余情况只补一次回桌面 + 强停，不重复弹。
        boolean upgrade = blocking && style == STYLE_SHEET && overlayStyle == STYLE_BANNER;
        if (blocking && !upgrade) {
            main.postDelayed(() -> {
                performGlobalAction(GLOBAL_ACTION_HOME);
                killAsync(pkg);
            }, 80);
            return;
        }
        if (!upgrade && now - lastBlockAt < 1200) return;
        if (upgrade) dismiss();

        lastBlockAt = now;
        blocking = true;
        int fails = failStreak;
        failStreak = 0;
        currentPkg = "";

        final String label = Prefs.label(this, pkg);
        final String net = NetworkGate.describe(this);
        final long seq = ++blockSeq;
        final boolean killOn = Prefs.kill(this);

        if (style == STYLE_BANNER) {
            final String sub = "连续 " + Math.max(2, fails) + " 次校验失败"
                    + (detail == null || detail.isEmpty() ? "" : " · " + detail);
            main.post(() -> showBanner("网络中断，已退出 " + label, sub, false, 4200));
        } else {
            main.post(() -> showSheet(label, reason, detail, net, killOn));
        }

        // 弹窗先在应用上停半秒再退回桌面：否则背景会先变成桌面，像是弹在桌面上
        main.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_HOME),
                style == STYLE_BANNER ? 120 : 460);

        if (killOn) killAsync(pkg);

        main.postDelayed(() -> {
            if (blockSeq == seq) dismiss();
        }, style == STYLE_BANNER ? 4600 : 9000);
    }

    private void killAsync(final String pkg) {
        // 降级链路（强停 → 挂起 → 禁用）全部在 ShizukuShell.forceStop 内部完成，
        // 这里只负责把结果写进日志和遮罩，避免重复执行同一批 pm 命令。
        ShizukuShell.forceStop(getApplicationContext(), pkg, (ok, before, after, note) -> {
            Diag.log("强停 " + pkg + " · " + (ok ? "成功" : "失败")
                    + " · 前 " + before + " 后 " + after
                    + (note == null || note.isEmpty() ? "" : " · " + note));

            main.post(() -> {
                final TextView st = overlayStatus;
                if (st == null) {
                    if (overlaySub != null) {
                        overlaySub.setText(ok ? "已结束该应用的全部进程"
                                : "未能结束进程，可能需手动关闭");
                    }
                    return;
                }
                if (ok) {
                    st.setText(note == null || note.isEmpty()
                            ? "已强制停止，后台不会驻留" : note);
                    st.setTextColor(GREEN);
                } else {
                    String msg = note == null || note.isEmpty()
                            ? "请确认 Shizuku 正在运行并已授权" : note;
                    if (msg.length() > 42) msg = msg.substring(0, 42) + "…";
                    st.setText("未能结束进程：" + msg);
                    st.setTextColor(RED);
                }
            });
        });
    }

    // ------------------------------------------------------- 提示层（弹窗/横条）

    /**
     * 打开应用时：底部弹出白卡。
     * 只回答三件事——哪个应用、为什么、会不会自动恢复。
     */
    private void showSheet(String label, int reason, String detail, String netDesc,
                           boolean killOn) {
        if (wm == null || overlay != null) return;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(SCRIM);
        root.setClickable(true);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.setClipChildren(false);
        // 点卡片外的空白处 = 知道了
        root.setOnClickListener(v -> dismiss());

        Ui.ShadowCard card = new Ui.ShadowCard(this);
        card.setClickable(true);
        card.radius(26);
        card.depth(9f, 0x3D000000);
        card.bg(0xFFFFFFFF);
        card.setPadding(dp(15), dp(9), dp(15), dp(14));
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.BOTTOM;
        clp.leftMargin = dp(8);
        clp.rightMargin = dp(8);
        clp.bottomMargin = dp(12);
        root.addView(card, clp);

        View grab = new View(this);
        grab.setBackground(Ui.round(0xFFD9D9E0, 2, this));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(32), dp(4));
        glp.bottomMargin = dp(15);
        card.addView(grab, glp);

        FrameLayout ico = new FrameLayout(this);
        ico.setBackground(Ui.grad(0xFFFFF1EF, 0xFFFFE8E5, 100, this));
        Glyph tri = new Glyph(this);
        tri.type(Glyph.TRI).color(Ui.RED_DEEP).weight(2.1f);
        ico.addView(tri, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
        card.addView(ico, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView title = Ui.text(this, "已阻止打开 " + label, 16, Ui.INK, true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = dp(10);
        card.addView(title, tp);

        String msgText;
        if (reason == R_UNREACHABLE) {
            msgText = "网络校验没通过，" + (detail == null ? "检测目标无响应" : detail) + "。\n"
                    + "连上加速器后会自动放行。";
        } else {
            msgText = "当前没有加速器 VPN 通道。\n连接后重新打开即可放行。";
        }
        TextView msg = Ui.text(this, msgText, 13, Ui.SUB, false);
        msg.setGravity(Gravity.CENTER);
        msg.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
        mp.topMargin = dp(7);
        card.addView(msg, mp);

        LinearLayout why = new LinearLayout(this);
        why.setOrientation(LinearLayout.HORIZONTAL);
        why.setGravity(Gravity.CENTER_VERTICAL);
        why.setBackground(Ui.gradStroke(0xFFFCF8F8, 0xFFF8F2F2, 11, 0.5f, 0x17E8332A, this));
        why.setPadding(dp(11), dp(10), dp(11), dp(10));
        TextView w1 = Ui.text(this, "最近一次校验", 12, 0xFF8E8E96, false);
        String right = reason == R_UNREACHABLE
                ? (detail == null ? "全部超时" : detail)
                : "未检测到 VPN 通道";
        TextView w2 = Ui.text(this, right, 12, Ui.RED_DEEP, true);
        w2.setSingleLine(true);
        w2.setEllipsize(TextUtils.TruncateAt.END);
        w2.setGravity(Gravity.END);
        why.addView(w1, new LinearLayout.LayoutParams(-2, -2));
        why.addView(w2, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams whp = new LinearLayout.LayoutParams(-1, -2);
        whp.topMargin = dp(15);
        card.addView(why, whp);

        TextView btn = new TextView(this);
        btn.setText("知道了");
        btn.setTextSize(15);
        btn.setTextColor(0xFF1C1C1E);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(softButton());
        btn.setClickable(true);
        btn.setOnClickListener(v -> dismiss());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(44));
        bp.topMargin = dp(14);
        card.addView(btn, bp);

        TextView status = Ui.text(this,
                killOn ? "正在结束该应用的全部进程…" : "仅退回桌面，应用仍在后台运行",
                12, killOn ? Ui.SUB : Ui.AMBER_DEEP, false);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.topMargin = dp(10);
        card.addView(status, sp);
        overlayStatus = status;

        TextView hint = Ui.text(this, "点开应用时会自动再校验一次", 11, 0xFF9C9CA6, false);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
        hp.topMargin = dp(6);
        card.addView(hint, hp);

        WindowManager.LayoutParams lp = overlayParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.TOP;

        try {
            card.setTranslationY(dp(440));
            root.setAlpha(0f);
            wm.addView(root, lp);
            overlay = root;
            overlayCard = card;
            overlayStyle = STYLE_SHEET;
            root.animate().alpha(1f).setDuration(200).start();
            card.animate().translationY(0f).setDuration(430)
                    .setInterpolator(new PathInterpolator(0.19f, 1f, 0.22f, 1f)).start();
        } catch (Throwable t) {
            Toast.makeText(this, "提示层创建失败，已直接退回桌面", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 使用中掉线：顶部横条。
     * 只占顶部一条，不铺满、不遮挡技能键；4 秒后自己收起。
     */
    private void showBanner(String title, String sub, boolean ok, long autoHideMs) {
        if (wm == null || overlay != null) return;

        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        // 上下留出投影的位置，否则 elevation 会被窗口边界切掉
        root.setPadding(0, dp(5), 0, dp(26));

        Ui.ShadowCard card = new Ui.ShadowCard(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.radius(18);
        card.depth(6f, 0x3D000000);
        card.bg(0xFFFFFFFF);
        card.setPadding(dp(10), dp(10), dp(8), dp(10));

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(9);
        clp.rightMargin = dp(9);
        root.addView(card, clp);

        FrameLayout ico = new FrameLayout(this);
        ico.setBackground(Ui.grad(ok ? 0xFFEDFBF2 : 0xFFFFF1EF,
                ok ? 0xFFDFF5E6 : 0xFFFFE8E5, 100, this));
        Glyph g = new Glyph(this);
        g.type(ok ? Glyph.CHECK : Glyph.TRI)
                .color(ok ? Ui.GREEN_DEEP : Ui.RED_DEEP)
                .weight(ok ? 2.8f : 2.1f);
        ico.addView(g, new FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(30), dp(30));
        ip.rightMargin = dp(9);
        card.addView(ico, ip);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = Ui.text(this, title, 13, Ui.INK, true);
        t1.setSingleLine(true);
        t1.setEllipsize(TextUtils.TruncateAt.END);
        TextView t2 = Ui.text(this, sub, 11, Ui.SUB, false);
        t2.setSingleLine(true);
        t2.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams t2p = new LinearLayout.LayoutParams(-1, -2);
        t2p.topMargin = dp(2);
        col.addView(t1, new LinearLayout.LayoutParams(-1, -2));
        col.addView(t2, t2p);
        card.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        overlaySub = t2;

        FrameLayout closeWrap = new FrameLayout(this);
        closeWrap.setBackground(Ui.round(0x1F787880, 100, this));
        Glyph cross = new Glyph(this);
        cross.type(Glyph.CROSS).color(0xFF8A8A8F).weight(2.6f);
        closeWrap.addView(cross, new FrameLayout.LayoutParams(dp(11), dp(11), Gravity.CENTER));
        closeWrap.setOnClickListener(v -> dismiss());
        LinearLayout.LayoutParams cwp = new LinearLayout.LayoutParams(dp(26), dp(26));
        cwp.leftMargin = dp(6);
        card.addView(closeWrap, cwp);

        WindowManager.LayoutParams lp = overlayParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP;
        lp.y = statusBarHeight() + dp(8);

        final long stamp = ++bannerStamp;
        try {
            card.setTranslationY(-dp(170));
            wm.addView(root, lp);
            overlay = root;
            overlayCard = card;
            overlayStyle = STYLE_BANNER;
            card.animate().translationY(0f).setDuration(430)
                    .setInterpolator(new PathInterpolator(0.19f, 1f, 0.22f, 1f)).start();
        } catch (Throwable t) {
            return;
        }

        if (autoHideMs > 0) {
            main.postDelayed(() -> {
                if (bannerStamp == stamp && overlayStyle == STYLE_BANNER) dismiss();
            }, autoHideMs);
        }
    }

    /** 校验恢复：同一条横条换成绿色，形状完全一致，切换时不跳。 */
    private void showRecovered(int apps, long rtt) {
        if (overlay != null) return;
        showBanner("网络已恢复，" + apps + " 个应用已放行",
                "校验通过 · 平均 " + rtt + " ms", true, 4000);
    }

    private WindowManager.LayoutParams overlayParams(int w, int h) {
        return new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
    }

    /** 弹窗按钮：浅灰底 + 深色字，和系统弹窗里的次级按钮同一档。 */
    private Drawable softButton() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Ui.FILL);
        g.setCornerRadius(dp(14));
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(Ui.FILL_PRESSED);
        pressed.setCornerRadius(dp(14));
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x1A000000), g, pressed);
    }

    private int statusBarHeight() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {
        }
        return dp(28);
    }

    private void dismiss() {
        blockSeq++;
        final View v = overlay;
        final View c = overlayCard;
        final int style = overlayStyle;
        overlay = null;
        overlayCard = null;
        overlayStatus = null;
        overlaySub = null;
        overlayStyle = -1;
        blocking = false;
        if (v == null || wm == null) return;

        if (style == STYLE_BANNER && c != null) {
            // 横条原路收回顶部
            c.animate().translationY(-dp(170)).alpha(0f).setDuration(170)
                    .withEndAction(() -> remove(v)).start();
        } else {
            v.animate().alpha(0f).setDuration(150).withEndAction(() -> remove(v)).start();
        }
    }

    private void remove(View v) {
        try {
            if (wm != null) wm.removeView(v);
        } catch (Throwable ignored) {
        }
    }

    private TextView text(String s, float sp, int color, int style) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", style));
        return t;
    }

    private TextView pill(String label, int bg, int fg) {
        TextView t = text(label, 14, fg, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(100));
        t.setBackground(g);
        return t;
    }

    private TextView pillOutline(String label, int fg) {
        TextView t = text(label, 14, fg, Typeface.NORMAL);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x00000000);
        g.setCornerRadius(dp(100));
        g.setStroke(dp(1), 0xFF33414E);
        t.setBackground(g);
        return t;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        Diag.log("无障碍服务已断开");
        destroyed = true;
        main.removeCallbacks(ticker);
        dismiss();
        if (instance == this) instance = null;
        try {
            if (cm != null && netCallback != null) cm.unregisterNetworkCallback(netCallback);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    /** 供设置页预览遮罩效果，不执行强停。 */
    public static boolean preview() {
        final GuardService s = instance;
        if (s == null) return false;
        s.main.post(() -> {
            if (s.blocking) return;
            s.blocking = true;
            final long seq = ++s.blockSeq;
            s.showSheet("示例应用", R_UNREACHABLE,
                    "3 个目标全部无响应", NetworkGate.describe(s), true);
            s.main.postDelayed(() -> {
                if (s.blockSeq == seq) s.dismiss();
            }, 6000);
        });
        return true;
    }
}
