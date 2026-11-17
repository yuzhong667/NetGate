package com.d.netgate;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/**
 * 以 shell 身份结束目标应用，三条通道按速度与可靠性排序：
 *
 *  A. newProcess：在 Shizuku 服务端（已是 shell 身份）直接起子进程执行
 *     cmd activity force-stop，不需要拉起 app_process，也不依赖 UserService 绑定。
 *  B. AMS 直调：用 Shizuku 包装系统 ActivityManager 直接调 forceStopPackage，
 *     不额外起进程，响应最快。
 *  C. UserService：在独立 shell 进程里执行 am force-stop + kill -9 扫残留，
 *     用于核对进程数并补刀。
 *
 * force-stop 是非 root 下最彻底的方式：杀掉同一 uid 的所有进程，
 * 并把应用置为 stopped 状态，广播 / JobScheduler / Alarm 都无法把它拉起。
 */
public final class ShizukuShell {

    public interface KillCallback {
        /** ok 表示执行后已无残留进程。before/after 为进程数，-1 表示没测到。 */
        void onResult(boolean ok, int before, int after, String note);
    }

    /** 绑定的最长等待时间。Shizuku 首次拉起服务进程要 2–5 秒。 */
    private static final long BIND_TIMEOUT = 5000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static IUserService service;
    private static Shizuku.UserServiceArgs args;
    private static boolean binding;
    private static long bindAt;
    private static String lastError = "";
    private static long lastBindTryAt;
    private static boolean deadListenerAdded;
    private static volatile String identity = "";
    private static long lastIdentityAt;

    private ShizukuShell() {
    }

    private static final ServiceConnection CONN = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IUserService.Stub.asInterface(binder);
            binding = false;
            lastError = "";
            Diag.log("Shizuku 通道已连接");
            probeIdentity();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            binding = false;
            lastError = "通道已断开";
            Diag.log("Shizuku 通道断开");
        }
    };

    public static boolean ready() {
        try {
            return service != null && service.asBinder().pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static String lastError() {
        return lastError;
    }

    /** 最近一次测到的 shell 身份，形如 uid=2000(shell)。空表示还没测到。 */
    public static String identity() {
        return identity;
    }

    /** 用一条 id 命令确认命令到底是以谁的身份执行。这是判断通道是否真生效的唯一硬证据。 */
    public static void probeIdentity() {
        new Thread(() -> {
            String r = execSync("id");
            identity = r == null ? "" : r.trim();
            Diag.log("执行身份：" + identity);
        }, "netgate-identity").start();
    }

    /** 供诊断报告展示：未绑定 / 绑定中 / 已就绪（直连）/ 已连接。 */
    public static String state() {
        if (ready()) return "已连接";
        if (binding) return "绑定中";
        // newProcess 通道不依赖 UserService 绑定，只要 Shizuku 活着且有授权就能强停
        if (newProcessAvailable()) return "已就绪（直连）";
        return "未绑定";
    }

    private static boolean newProcessAvailable() {
        try {
            if (!Shizuku.pingBinder()) return false;
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return false;
            Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 在任意线程调用都不会出问题，内部统一切到主线程。 */
    public static void init(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (Looper.myLooper() == Looper.getMainLooper()) {
            bindNow(app);
        } else {
            MAIN.post(() -> bindNow(app));
        }
    }

    private static void bindNow(Context ctx) {
        if (ready()) return;

        long now = System.currentTimeMillis();
        if (binding && now - bindAt < BIND_TIMEOUT) return;

        if (binding) {
            Diag.log("上次绑定超时，重新建立通道");
            safeUnbind();
            binding = false;
        }

        if (now - lastBindTryAt < 1000) return;
        lastBindTryAt = now;

        try {
            if (!Shizuku.pingBinder()) {
                lastError = "Shizuku 服务未运行";
                return;
            }
            addDeadListener();

            // 通道是否真的可用，用 id 命令验证一次：这是唯一不会被骗的证据。
            if (identity.isEmpty() && now - lastIdentityAt > 8000) {
                lastIdentityAt = now;
                probeIdentity();
            }

            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                lastError = "Shizuku 未授权";
                return;
            }
            if (Shizuku.isPreV11()) {
                lastError = "Shizuku 版本过低，请升级到 13.x";
                return;
            }

            if (args == null) {
                args = new Shizuku.UserServiceArgs(
                        new ComponentName(ctx.getPackageName(), ShellService.class.getName()))
                        // daemon(true) 要求 Shizuku 以 root 运行；无线调试是 shell 身份，
                        // 服务进程会起不来。这里用普通模式。
                        .daemon(false)
                        .processNameSuffix("shell")
                        .debuggable(false)
                        // AIDL 契约变过，升版本号强制 Shizuku 重建服务进程
                        .version(2);
            }

            binding = true;
            bindAt = now;
            lastError = "";
            Shizuku.bindUserService(args, CONN);
        } catch (Throwable t) {
            binding = false;
            lastError = "绑定异常：" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + t.getMessage());
            Diag.log(lastError);
        }
    }

    private static void safeUnbind() {
        if (args == null) return;
        try {
            Shizuku.unbindUserService(args, CONN, true);
        } catch (Throwable ignored) {
        }
        service = null;
    }

    private static void addDeadListener() {
        if (deadListenerAdded) return;
        deadListenerAdded = true;
        try {
            Shizuku.addBinderDeadListener(() -> {
                service = null;
                binding = false;
                lastError = "Shizuku 服务已停止";
                Diag.log("Shizuku 服务已停止");
            });
        } catch (Throwable ignored) {
        }
    }

    private static boolean await(Context ctx, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (ready()) return true;
            if (!binding) init(ctx);
            try {
                Thread.sleep(120);
            } catch (InterruptedException ignored) {
            }
        }
        return ready();
    }

    // --------------------------------------------------- A. newProcess（不依赖 UserService）

    /**
     * Shizuku 13 把 newProcess 标成了内部 API，方法本身还在，反射即可调用。
     * 返回对象是 ShizukuRemoteProcess，不同版本对 Process 的继承关系变过，
     * 所以这里用反射调 getInputStream / getErrorStream / waitFor，不强制转型。
     *
     * 它运行在 Shizuku 服务端进程（shell 身份）里，不需要拉起任何 app_process。
     */
    private static String runViaNewProcess(String[] cmd) throws Exception {
        Method m;
        try {
            m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
        } catch (NoSuchMethodException e) {
            // 个别版本把它挪到了 ShizukuRemoteProcess 上
            Class<?> rp = Class.forName("rikka.shizuku.ShizukuRemoteProcess");
            m = rp.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        }
        m.setAccessible(true);

        Object proc = m.invoke(null, cmd, null, null);
        if (proc == null) throw new IllegalStateException("newProcess 返回 null");

        Class<?> pc = proc.getClass();
        InputStream in = (InputStream) pc.getMethod("getInputStream").invoke(proc);
        InputStream err = (InputStream) pc.getMethod("getErrorStream").invoke(proc);

        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Thread errT = new Thread(() -> pump(err, buf), "netgate-np-err");
        errT.setDaemon(true);
        errT.start();

        pump(in, buf);
        Object codeObj = pc.getMethod("waitFor").invoke(proc);
        int code = codeObj instanceof Number ? ((Number) codeObj).intValue() : -1;
        errT.join(500);
        return code + "|" + buf.toString("UTF-8").trim();
    }

    private static void pump(InputStream in, ByteArrayOutputStream out) {
        try {
            byte[] b = new byte[2048];
            int n;
            while ((n = in.read(b)) > 0) {
                synchronized (out) {
                    out.write(b, 0, n);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean forceStopViaNewProcess(String pkg) {
        try {
            if (!Shizuku.pingBinder()) return false;
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return false;
            String safe = pkg.replaceAll("[^A-Za-z0-9._]", "");
            String pattern = safe.replace(".", "\\.");

            // 只有退出码不可信：ColorOS 上出现过 cmd 返回 0、应用却还活着的情况。
            // 所以这里把所有可用的手段都下一次，再直接数进程，只认最终结果。
            String script = "/system/bin/am force-stop " + safe + " 2>&1 | tr '\\n' ' '; "
                    + "/system/bin/am force-stop --user 0 " + safe + " 2>&1 | tr '\\n' ' '; "
                    + "/system/bin/cmd activity force-stop " + safe + " 2>&1 | tr '\\n' ' '; "
                    + "/system/bin/cmd activity force-stop --user 0 " + safe
                    + " 2>&1 | tr '\\n' ' '; "
                    + "/system/bin/am kill " + safe + " 2>&1 | tr '\\n' ' '; "
                    + "sleep 0.45; "
                    + "LEFT=$(/system/bin/ps -A 2>/dev/null | grep -E '(^|[[:space:]])"
                    + pattern + "(:[^[:space:]]*)?$' | awk '{print $2}'); "
                    + "if [ -n \"$LEFT\" ]; then /system/bin/kill -9 $LEFT 2>/dev/null; "
                    + "sleep 0.25; fi; "
                    + "echo; echo NGSEP; "
                    + "C=$(/system/bin/ps -A 2>/dev/null | grep -cE '(^|[[:space:]])"
                    + pattern + "(:[^[:space:]]*)?$'); "
                    // ps 在部分 ROM 上看不到别的应用，返回 0 时用 /proc 复核一次
                    + "if [ \"$C\" = \"0\" ]; then "
                    + "C=0; for d in /proc/[0-9]*; do "
                    + "if tr '\\000' '\\n' < $d/cmdline 2>/dev/null | grep -qxE '"
                    + pattern + "(:.*)?'; then C=$((C+1)); fi; "
                    + "done; fi; "
                    + "echo $C";

            String r = runViaNewProcess(new String[]{"/system/bin/sh", "-c", script});
            Diag.log("newProcess force-stop · " + r);

            int bar = r.indexOf('|');
            if (bar < 0) return false;
            String out = r.substring(bar + 1);
            int sep = out.indexOf("NGSEP");
            if (sep < 0) return false;

            String head = out.substring(0, sep).trim();
            String tail = out.substring(sep + 5).trim();
            int left = -1;
            for (String tok : tail.split("\\s+")) {
                if (tok.matches("\\d+")) {
                    left = Integer.parseInt(tok);
                    break;
                }
            }
            Diag.log("强停 " + pkg + " · 残留 " + left
                    + (head.isEmpty() ? "" : " · " + head));

            if (head.contains("SecurityException")
                    || head.contains("Permission Denial")
                    || head.contains("not allowed to")) {
                lastError = "Shell 无强停权限";
                Diag.log("权限不足：am 被系统拒绝");
            }
            return left == 0;
        } catch (Throwable t) {
            Diag.log("newProcess 不可用 · " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + t.getMessage()));
            return false;
        }
    }

    /** 目标包当前的进程数；-1 表示数不出来（ps 不可用或被 ROM 限制）。 */
    public static int countProcess(String pkg) {
        try {
            if (!Shizuku.pingBinder()) return -1;
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return -1;
            String safe = pkg.replaceAll("[^A-Za-z0-9._]", "");
            String pat = safe.replace(".", "\\.");

            // ps 和 /proc 两条路都数，取较大值。
            // 单用任何一条，ROM 限制住的时候都会返回 0，把「还活着」判成「已清空」。
            String cmd = "P=$(/system/bin/ps -A 2>/dev/null | grep -cE "
                    + "'(^|[[:space:]])" + pat + "(:[^[:space:]]*)?$'); "
                    + "C=$(cat /proc/[0-9]*/cmdline 2>/dev/null "
                    + "| tr '\\000' '\\n' | grep -acE '^" + pat + "(:.*)?$'); "
                    + "C=${C:-0}; P=${P:-0}; "
                    + "if [ \"$C\" -gt \"$P\" ]; then echo $C; else echo $P; fi";

            String r = runViaNewProcess(new String[]{"/system/bin/sh", "-c", cmd});
            int bar = r.indexOf('|');
            if (bar < 0) return -1;
            for (String tok : r.substring(bar + 1).trim().split("\\s+")) {
                if (tok.matches("\\d+")) return Integer.parseInt(tok);
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 系统级禁用：pm disable-user。
     *
     * 这是 FORCE_STOP_PACKAGES 被 ROM 挡住时的兜底 —— 它只需要
     * CHANGE_COMPONENT_ENABLED_STATE，shell 身份基本一定持有。
     * 禁用后系统不会启动该应用，比 force-stop 更彻底，但图标会变灰。
     */
    public static String disablePackage(String pkg) {
        String safe = pkg.replaceAll("[^A-Za-z0-9._]", "");
        String r = execSync("/system/bin/pm disable-user --user 0 " + safe
                + " 2>&1; echo \"exit=$?\"");
        Diag.log("禁用 " + safe + " · " + r.replace('\n', ' ').trim());
        return r;
    }

    public static String enablePackage(String pkg) {
        String safe = pkg.replaceAll("[^A-Za-z0-9._]", "");
        String r = execSync("/system/bin/pm enable --user 0 " + safe
                + " 2>&1; echo \"exit=$?\"");
        Diag.log("恢复 " + safe + " · " + r.replace('\n', ' ').trim());
        return r;
    }

    /**
     * 挂起应用：pm suspend。效果是图标变灰、无法启动，但不算“停用”，
     * 权限走 SUSPEND_APPS，比 disable 温和，也更容易被 ROM 放行。
     */
    public static String suspendPackage(String pkg) {
        String safe = pkg.replaceAll("[^A-Za-z0-9._]", "");
        String r = execSync("/system/bin/pm suspend --user 0 " + safe
                + " 2>&1; echo \"exit=$?\"");
        Diag.log("挂起 " + safe + " · " + r.replace('\n', ' ').trim());
        return r;
    }

    public static String unsuspendPackage(String pkg) {
        String safe = pkg.replaceAll("[^A-Za-z0-9._]", "");
        String r = execSync("/system/bin/pm unsuspend --user 0 " + safe
                + " 2>&1; echo \"exit=$?\"");
        Diag.log("取消挂起 " + safe + " · " + r.replace('\n', ' ').trim());
        return r;
    }

    /** 同步执行一条 shell 命令，返回合并后的输出（仅供诊断用）。 */
    public static String execSync(String cmd) {
        try {
            if (!Shizuku.pingBinder()) return "Shizuku 未运行";
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return "Shizuku 未授权";
            }
            String r = runViaNewProcess(new String[]{"/system/bin/sh", "-c", cmd});
            int bar = r.indexOf('|');
            return bar >= 0 ? r.substring(bar + 1) : r;
        } catch (Throwable t) {
            return "ERR: " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + t.getMessage());
        }
    }

    /**
     * 清扫后台：一次性 ps 快照，把所有仍在运行的受保护应用直接 force-stop。
     *
     * 用于「VPN 已经不通、但受保护应用退到了后台」的场景 —— 前台巡检抓不到它们，
     * 用户仍能从最近任务一键切回去。这里是按进程名匹配，不依赖应用是否在前台。
     */
    public static void sweepBackground(final Context ctx, final java.util.Collection<String> pkgs) {
        if (pkgs == null || pkgs.isEmpty()) return;
        new Thread(() -> {
            try {
                if (!Shizuku.pingBinder()) return;
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return;

                StringBuilder script = new StringBuilder("PS=$(/system/bin/ps -A 2>/dev/null); ");
                int n = 0;
                for (String p : pkgs) {
                    if (p == null) continue;
                    String safe = p.replaceAll("[^A-Za-z0-9._]", "");
                    if (safe.isEmpty()) continue;
                    String pat = safe.replace(".", "\\.");
                    script.append("if echo \"$PS\" | grep -qE '(^|[[:space:]])")
                            .append(pat)
                            .append("(:[^[:space:]]*)?$'; then ")
                            .append("/system/bin/cmd activity force-stop ").append(safe)
                            .append(" >/dev/null 2>&1; ")
                            .append("echo KILLED:").append(safe).append("; fi; ");
                    n++;
                }
                if (n == 0) return;

                String r = runViaNewProcess(new String[]{"/system/bin/sh", "-c", script.toString()});
                if (r != null && r.contains("KILLED:")) {
                    Diag.log("后台清扫 · " + r.substring(r.indexOf('|') + 1).trim());
                }
            } catch (Throwable t) {
                Diag.log("后台清扫不可用 · " + t.getClass().getSimpleName());
            }
        }, "netgate-sweep").start();
    }

    // ------------------------------------------------------------ B. AMS 直调

    /**
     * TRANSACTION_xxx 常量定义在 IActivityManager$Stub 内部类里，
     * 不在 IActivityManager 接口本身。查事务码要传 Stub，
     * 查方法签名才用接口。
     */
    private static final String IAM = "android.app.IActivityManager";
    private static final String IAM_STUB = "android.app.IActivityManager$Stub";

    private static boolean forceStopViaAm(String pkg) {
        try {
            if (!Shizuku.pingBinder()) return false;
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return false;

            IBinder am = SystemServiceHelper.getSystemService("activity");
            if (am == null) {
                Diag.log("AMS 直调：拿不到 activity 服务");
                return false;
            }

            Integer code = SystemServiceHelper.getTransactionCode(IAM_STUB, "forceStopPackage");
            if (code == null) {
                // 少数 ROM 把常量放在接口上，再试一次
                code = SystemServiceHelper.getTransactionCode(IAM, "forceStopPackage");
            }
            if (code == null) {
                Diag.log("AMS 直调：当前系统没有 forceStopPackage");
                return false;
            }

            Parcel data = SystemServiceHelper.obtainParcel(
                    IAM, "forceStopPackage", "java.lang.String", "int");
            if (data == null) {
                Diag.log("AMS 直调：Parcel 构造失败");
                return false;
            }
            data.writeString(pkg);
            data.writeInt(0);

            Parcel reply = Parcel.obtain();
            try {
                boolean sent = new ShizukuBinderWrapper(am).transact(code, data, reply, 0);
                if (!sent) {
                    Diag.log("AMS 直调：transact 返回 false");
                    return false;
                }
                // 服务端若拒绝执行，会在这里抛出
                reply.readException();
            } finally {
                reply.recycle();
                data.recycle();
            }

            Diag.log("AMS 强停已下发 " + pkg);
            return true;
        } catch (Throwable t) {
            Diag.log("AMS 强停失败 · " + t);
            return false;
        }
    }

    // ------------------------------------------------------------ C. 主流程

    /**
     * 分三段执行，每一段都用 /proc 重新数进程，只认最终结果：
     *
     *  1. force-stop（cmd + am）→ 0.7 秒后数一次；
     *  2. 已归零也要再等 1.8 秒复查 —— ColorOS 常在 1～2 秒内把应用重新拉起，
     *     只看第一眼就会把「杀了又活」记成成功；
     *  3. 仍有进程（没杀掉或已复活）→ pm suspend 挂起后不再拉起，
     *     还压不住就 pm disable-user。两者都记账，网络恢复后自动还原。
     *     这一步受「挂起兜底」开关控制，关掉后只强停、不动应用状态；
     *     系统关键应用（桌面 / 输入法 / 拨号 / SystemUI / Shizuku）直接跳过。
     */
    public static void forceStop(final Context ctx, final String pkg, final KillCallback cb) {
        final Context app = ctx.getApplicationContext();

        // 最后一道闸：系统关键应用连 force-stop 都不做。
        // 桌面被强停会闪回、SystemUI 被停会没有状态栏，用户连恢复入口都点不到。
        if (SysApps.isCritical(app, pkg)) {
            Diag.log("强停 " + pkg + " · 系统关键应用，已跳过");
            if (cb != null) {
                cb.onResult(false, -1, -1, "系统关键应用，已跳过（不做任何处理）");
            }
            return;
        }

        new Thread(() -> {
            final String safe = pkg.replaceAll("[^A-Za-z0-9._]", "");
            StringBuilder seq = new StringBuilder();

            int before = countProcess(pkg);
            seq.append("前 ").append(before);

            if (before < 0) {
                Diag.log("强停 " + pkg + " · 读不到进程列表");
                if (cb != null) {
                    cb.onResult(false, -1, -1, "读不到进程列表，请确认 Shizuku 正在运行");
                }
                return;
            }

            if (before == 0) {
                // 目标没在运行：命令照发（清掉 stopped 标记），但绝不能算成功。
                // 之前固定取列表第一个应用，它没跑时前后都是 0，就会误报「已强停」。
                execSync("/system/bin/cmd activity force-stop " + safe + " 2>&1");
                Diag.log("强停 " + pkg + " · 目标当前没有进程，未验证");
                if (cb != null) {
                    cb.onResult(false, 0, 0, "目标当前没有运行，这次没有验证到任何东西");
                }
                return;
            }

            String out = execSync("/system/bin/cmd activity force-stop " + safe
                    + " 2>&1; /system/bin/am force-stop " + safe + " 2>&1");
            sleep(700);
            int t1 = countProcess(pkg);
            seq.append(" → 0.7s ").append(t1);

            int cur = t1;
            if (t1 == 0) {
                sleep(1800);
                int t2 = countProcess(pkg);
                seq.append(" → 2.5s ").append(t2);
                if (t2 == 0) {
                    Diag.log("强停 " + pkg + " · " + seq + " · 彻底结束");
                    if (cb != null) {
                        cb.onResult(true, before, 0, "已结束 " + before + " 个进程");
                    }
                    return;
                }
                cur = t2;
            }

            // 到这里说明纯强停压不住。挂起/禁用会改动系统的应用启用状态，
            // 属于「宁可杀不掉也不能弄坏手机」的档位，必须由用户显式允许。
            if (!Prefs.hardBlock(app)) {
                fallbackKill(app, pkg);
                Diag.log("强停 " + pkg + " · " + seq + " · 未启用挂起兜底");
                if (cb != null) {
                    cb.onResult(false, before, cur,
                            "仍有 " + cur + " 个进程；已关闭「挂起兜底」，未改动应用状态");
                }
                return;
            }

            // 还有进程：要么没杀掉，要么被杀后又被系统拉起。
            // 挂起（SUSPEND_APPS，shell 持有）是唯一能让 ColorOS 不再拉起的
            // 普通权限手段，比反复杀进程可靠。
            String sp = suspendPackage(safe);
            execSync("/system/bin/cmd activity force-stop " + safe + " 2>&1");
            sleep(1100);
            int t3 = countProcess(pkg);
            seq.append(" → 挂起 ").append(t3);

            if (t3 == 0) {
                Prefs.markDisabled(app, pkg);
                Diag.log("强停 " + pkg + " · " + seq + " · 已挂起");
                if (cb != null) {
                    cb.onResult(true, before, 0,
                            "进程被杀后又被系统拉起，已挂起该应用（网络恢复后自动恢复）");
                }
                return;
            }

            String d = disablePackage(safe);
            sleep(900);
            int t4 = countProcess(pkg);
            seq.append(" → 禁用 ").append(t4);
            Diag.log("强停 " + pkg + " · " + seq + " · suspend="
                    + (sp == null ? "" : sp.replace('\n', ' ').trim())
                    + " · disable=" + (d == null ? "" : d.replace('\n', ' ').trim()));

            if (t4 == 0) {
                Prefs.markDisabled(app, pkg);
                if (cb != null) {
                    cb.onResult(true, before, 0,
                            "强停和挂起都压不住，已系统级禁用（网络恢复后自动恢复）");
                }
                return;
            }

            fallbackKill(app, pkg);
            if (cb != null) {
                cb.onResult(false, before, t4,
                        "仍有 " + t4 + " 个进程"
                                + (out == null || out.trim().isEmpty()
                                ? "" : "：" + out.trim()));
            }
        }, "netgate-force-stop").start();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /** Shizuku 不可用时的最后手段：请系统回收目标的后台进程。 */
    private static void fallbackKill(Context ctx, String pkg) {
        try {
            ActivityManager am =
                    (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.killBackgroundProcesses(pkg);
            Diag.log("已请求系统回收 " + pkg);
        } catch (Throwable t) {
            Diag.log("系统回收失败 · " + t);
        }
    }

    public interface Callback {
        void onResult(String output);
    }

    /** 自检用：把命令交给 shell 身份执行并回传输出。 */
    public static void execAsync(final Context ctx, final String cmd, final Callback cb) {
        new Thread(() -> {
            try {
                if (!await(ctx, BIND_TIMEOUT)) {
                    cb.onResult("未连接：" + (lastError.isEmpty()
                            ? "请确认 Shizuku 正在运行，且本应用已获得授权"
                            : lastError));
                    return;
                }
                cb.onResult(service.exec(cmd));
            } catch (Throwable t) {
                cb.onResult("执行异常：" + t);
            }
        }).start();
    }

    /**
     * 先 force-stop，再用 ps 扫残留补刀，最后输出前后进程数。
     * force-stop 若报错，再用 cmd activity force-stop 试一次。
     */
    private static String buildCommand(String pkg) {
        String safe = pkg.replaceAll("[^A-Za-z0-9._]", "");
        String pattern = safe.replace(".", "\\.");
        return "P='[[:space:]]" + pattern + "(:[^[:space:]]*)?$'; "
                + "B=$(ps -A 2>/dev/null | grep -cE \"$P\"); "
                + "M=$(/system/bin/am force-stop " + safe + " 2>&1 | tr '\\n' ' '); "
                + "if [ -n \"$M\" ]; then "
                + "M=\"$M | $(/system/bin/cmd activity force-stop " + safe
                + " 2>&1 | tr '\\n' ' ')\"; fi; "
                + "sleep 0.3; "
                + "for p in $(ps -A 2>/dev/null | grep -E \"$P\" | awk '{print $2}'); do "
                + "/system/bin/kill -9 $p 2>/dev/null; done; "
                + "sleep 0.2; "
                + "A=$(ps -A 2>/dev/null | grep -E \"$P\" | wc -l | tr -d ' '); "
                + "echo \"NG|$B|$A|$M\"";
    }
}
