package com.d.netgate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * 纯 Shizuku 模式的后台守护。
 *
 * 无障碍服务被系统撤销后，前台应用就没人监听了。这条通道用 shell 身份
 * 轮询 dumpsys activity 拿前台包名，判定与拦截逻辑和无障碍模式一致，
 * 只是提示层降级为一条通知 —— 没有无障碍就用不了 accessibility overlay，
 * 也不去要悬浮窗权限。
 *
 * 与无障碍模式互斥：{@link GuardService#alive()} 为真时本服务只待命。
 */
public class ShellGuardService extends Service {

    private static final String CHANNEL = "netgate_guard";
    private static final int NID_ONGOING = 41;
    private static final int NID_ALERT = 42;

    /** 轮询周期。dumpsys 一次 200~400ms，2 秒的间隔对电量几乎没有影响。 */
    private static final long TICK = 2000;
    private static final long NOVPN_GRACE = 2500;
    private static final long NOVPN_SWEEP = 6000;
    private static final long SWEEP_GAP = 5000;
    private static final long BLOCK_GAP = 3000;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "netgate-shell-guard");
        t.setDaemon(true);
        return t;
    });

    /** 供主界面显示「运行中 / 已停止」。 */
    public static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable ticker = this::onTick;

    private NotificationManager nm;
    private boolean destroyed;
    private boolean busy;
    private boolean probing;
    private String currentPkg = "";
    private String lastNote = "";
    private int failStreak;
    private long nextProbeAt;
    private long noVpnSince;
    private long lastSweepAt;
    private long lastBlockAt;
    /** 上一次 tick 时 VPN 是否存在，用于捕捉重连边沿。 */
    private boolean lastVpnSeen;
    private long lastExitAt;
    private boolean exitRunning;

    public static void start(Context c) {
        Intent i = new Intent(c, ShellGuardService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, ShellGuardService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        destroyed = false;
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        try {
            startForeground(NID_ONGOING, build("NetGate", "Shizuku 监听启动中…", true));
        } catch (Throwable t) {
            Diag.log("前台通知失败 · " + t);
        }
        main.postDelayed(ticker, 400);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        running = false;
        main.removeCallbacks(ticker);
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 巡检

    private void onTick() {
        if (destroyed) return;
        if (busy || probing) {
            main.postDelayed(ticker, 700);
            return;
        }
        busy = true;
        IO.execute(() -> {
            boolean ready;
            String fg = "";
            try {
                ready = Shizuku.pingBinder()
                        && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
                if (ready) fg = ShizukuShell.foregroundPackage();
            } catch (Throwable t) {
                ready = false;
            }
            final boolean ok = ready;
            final String pkg = fg;
            main.post(() -> {
                busy = false;
                if (destroyed) return;
                handle(ok, pkg);
                // 必须无条件续上：探测期间会跳过几拍，这里断了服务就停摆了
                main.postDelayed(ticker, TICK);
            });
        });
    }

    private void handle(boolean ready, String fg) {
        if (!ready) {
            noVpnSince = 0;
            note("等待 Shizuku 授权");
            return;
        }

        // 无障碍活着就让给它：两边同时拦会重复 force-stop
        if (GuardService.alive()) {
            noVpnSince = 0;
            note("无障碍服务在线 · 本通道待命");
            return;
        }

        long now = System.currentTimeMillis();
        Set<String> guarded = Prefs.guarded(this);
        boolean vpn = NetworkGate.hasVpn(this);

        if (vpn) {
            noVpnSince = 0;
            if (!lastVpnSeen) {
                NodeLock.reset();
                lastExitAt = 0;
            }
        } else if (noVpnSince == 0) {
            noVpnSince = now;
            NodeLock.reset();
            Diag.log("（Shizuku 模式）VPN 通道消失");
        }
        lastVpnSeen = vpn;

        if (!fg.isEmpty()) currentPkg = fg;
        boolean active = guarded.contains(currentPkg) && !SysApps.isCritical(this, currentPkg);

        // 出口指纹：前台勤一点，后台降频
        if (vpn && Prefs.nodeActive(this)) {
            long gap = active ? 10000 : 60000;
            if (!exitRunning && now - lastExitAt >= gap) {
                lastExitAt = now;
                refreshExit();
            }
        }

        note((vpn ? "加速器已连接" : "加速器未连接")
                + " · 保护 " + guarded.size() + " 个应用"
                + (active ? " · 前台 " + Prefs.label(this, currentPkg) : ""));

        if (!vpn && noVpnSince > 0 && now - noVpnSince >= NOVPN_SWEEP) {
            sweep(guarded, now);
        }

        if (!active) {
            failStreak = 0;
            return;
        }

        if (!vpn) {
            if (now - noVpnSince < NOVPN_GRACE) return;  // 切节点的空档，别打断
            failStreak = 0;
            block(currentPkg, "加速器未连接");
            return;
        }

        if (!Prefs.probe(this)) return;
        if (now < nextProbeAt) return;

        probing = true;
        IO.execute(() -> {
            NetworkGate.Probe p = null;
            try {
                p = NetworkGate.probe(this);
            } catch (Throwable ignored) {
            }
            final NetworkGate.Probe r = p;
            main.post(() -> {
                probing = false;
                if (!destroyed) onProbe(r);
            });
        });
    }

    private void onProbe(NetworkGate.Probe p) {
        long now = System.currentTimeMillis();
        if (p == null) {
            nextProbeAt = now + 3000;
            return;
        }
        if (p.ok) {
            failStreak = 0;
            nextProbeAt = now + Prefs.probeDelay(this);
            if (Prefs.nodeBound(this, currentPkg)
                    && NodeLock.verdict(this) == NodeLock.BLOCK) {
                Diag.log("（Shizuku 模式）节点不符 · " + NodeLock.blockDetail(this));
                block(currentPkg, "节点不符合", NodeLock.blockDetail(this));
            }
            return;
        }

        failStreak++;
        nextProbeAt = now + 700;
        Diag.log("（Shizuku 模式）探测失败 · " + p.detail + " · 连续 " + failStreak);
        if (failStreak < Prefs.probeFails(this)) return;

        failStreak = 0;
        block(currentPkg, p.detail == null ? "连通性校验失败" : p.detail);
    }

    private void sweep(Set<String> guarded, long now) {
        if (!Prefs.kill(this) || guarded.isEmpty()) return;
        if (now - lastSweepAt < SWEEP_GAP) return;
        lastSweepAt = now;
        ShizukuShell.sweepBackground(getApplicationContext(), guarded);
    }

    /**
     * 没有悬浮窗权限，就不做遮罩：可靠 shell 的身份按一次 Home，再 force-stop。
     *
     * shell 持有 START_ACTIVITIES_FROM_BACKGROUND，直接 input keyevent 回桌面
     * 不受 Android 10 之后的后台启动限制。
     */
    private void refreshExit() {
        if (exitRunning || destroyed) return;
        exitRunning = true;
        new Thread(() -> {
            NodeLock.Exit e = null;
            try {
                e = NodeLock.refresh(this);
            } catch (Throwable ignored) {
            }
            final boolean got = e != null;
            main.post(() -> {
                exitRunning = false;
                if (destroyed || !got) return;
                checkNode();
            });
        }, "netgate-node-shell").start();
    }

    private void checkNode() {
        if (!Prefs.nodeActive(this)) return;
        if (NodeLock.verdict(this) != NodeLock.BLOCK) return;
        if (currentPkg == null || currentPkg.isEmpty()) return;
        if (!Prefs.nodeBound(this, currentPkg)) return;
        if (!Prefs.guarded(this).contains(currentPkg)) return;
        if (SysApps.isCritical(this, currentPkg)) return;
        block(currentPkg, "节点不符合", NodeLock.blockDetail(this));
    }

    private void block(final String pkg, final String why) {
        block(pkg, "网络中断，已退出 " + Prefs.label(this, pkg), why);
    }

    private void block(final String pkg, final String title, final String why) {
        long now = System.currentTimeMillis();
        if (now - lastBlockAt < BLOCK_GAP) return;
        lastBlockAt = now;

        Diag.log("（Shizuku 模式）拦截 " + pkg + " · " + why);
        alert(title, why);

        IO.execute(() -> {
            ShizukuShell.execSync("/system/bin/input keyevent 3");
            if (Prefs.kill(this)) {
                ShizukuShell.forceStop(getApplicationContext(), pkg, null);
            }
        });
    }

    // ---------------------------------------------------------------- 通知

    private void note(String text) {
        if (text.equals(lastNote)) return;
        lastNote = text;
        try {
            nm.notify(NID_ONGOING, build("NetGate · Shizuku 监听中", text, true));
        } catch (Throwable ignored) {
        }
    }

    private void alert(String title, String text) {
        try {
            nm.notify(NID_ALERT, build(title, text, false));
        } catch (Throwable ignored) {
        }
    }

    private Notification build(String title, String text, boolean ongoing) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "网络守护",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }

        Intent it = new Intent(this, MainActivity.class);
        it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setAutoCancel(!ongoing);
        return b.build();
    }
}
