package com.d.netgate;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public final class Prefs {

    public static final String DEFAULT_TARGETS = NetworkGate.DEFAULT_TARGETS;

    private static final String FILE = "netgate";
    private static final String KEY_GUARDED = "guarded";
    private static final String KEY_KILL = "kill";
    private static final String KEY_PROBE = "probe";
    private static final String KEY_TARGETS = "targets";
    private static final String KEY_LEVEL = "level";
    private static final String KEY_DISABLED = "disabled_pkgs";
    private static final String KEY_HARD = "hard_block";
    private static final String KEY_SHELL_GUARD = "shell_guard";
    private static final String KEY_NODE_REGION = "node_region";
    private static final String KEY_NODE_LABEL = "node_label";
    private static final String KEY_NODE_GUARDED = "node_guarded";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 档位插过两次新值，旧数据要依次后移，不能只做一次判断。 */
    public static void migrate(Context c) {
        SharedPreferences p = sp(c);

        // 1.5 起多了「极速」，旧档位整体后移一位
        if (!p.getBoolean("migrated_v2", false)) {
            int old = p.getInt(KEY_LEVEL, 1);
            p.edit()
                    .putInt(KEY_LEVEL, Math.min(3, old + 1))
                    .putBoolean("migrated_v2", true)
                    .apply();
        }

        // 2.6 起在「标准」和「宽松」之间插入「稳妥」，旧宽松 3 → 4
        if (!p.getBoolean("migrated_v3", false)) {
            int old = p.getInt(KEY_LEVEL, 2);
            if (old >= 3) old = Math.min(4, old + 1);
            p.edit()
                    .putInt(KEY_LEVEL, old)
                    .putBoolean("migrated_v3", true)
                    .apply();
        }
    }

    public static Set<String> guarded(Context c) {
        return new HashSet<>(sp(c).getStringSet(KEY_GUARDED, Collections.<String>emptySet()));
    }

    public static void setGuarded(Context c, Set<String> pkgs) {
        // 系统关键应用一律不写进名单：挂起/禁用它们会造成不可逆的后果
        Set<String> clean = new HashSet<>();
        if (pkgs != null) {
            for (String p : pkgs) {
                if (p != null && !p.isEmpty() && !SysApps.isCritical(c, p)) clean.add(p);
            }
        }
        // 节点锁名单必须是受保护名单的子集：取消保护的同时解除节点约束
        Set<String> bound = nodeGuarded(c);
        bound.retainAll(clean);
        sp(c).edit()
                .putStringSet(KEY_GUARDED, clean)
                .putStringSet(KEY_NODE_GUARDED, bound)
                .apply();
    }

    public static boolean kill(Context c) {
        return sp(c).getBoolean(KEY_KILL, true);
    }

    public static void setKill(Context c, boolean value) {
        sp(c).edit().putBoolean(KEY_KILL, value).apply();
    }

    /** 是否校验实际连通性（而不只是看有没有 VPN 通道）。 */
    public static boolean probe(Context c) {
        return sp(c).getBoolean(KEY_PROBE, true);
    }

    public static void setProbe(Context c, boolean value) {
        sp(c).edit().putBoolean(KEY_PROBE, value).apply();
    }

    /** 1.4 之前用的公共 DNS，加速器对它走直连，测不出真实状态。 */
    private static final String LEGACY_TARGETS = "1.1.1.1:443,8.8.8.8:443,9.9.9.9:443";

    public static String targets(Context c) {
        String s = sp(c).getString(KEY_TARGETS, DEFAULT_TARGETS);
        if (s == null || s.trim().isEmpty()) return DEFAULT_TARGETS;
        if (s.replace(" ", "").trim().equals(LEGACY_TARGETS)) return DEFAULT_TARGETS;
        return s;
    }

    public static void setTargets(Context c, String value) {
        sp(c).edit().putString(KEY_TARGETS, value == null ? "" : value.trim()).apply();
    }

    /** 0 极速 / 1 严格 / 2 标准 / 3 稳妥 / 4 宽松。 */
    public static int level(Context c) {
        return sp(c).getInt(KEY_LEVEL, 2);
    }

    public static void setLevel(Context c, int value) {
        sp(c).edit().putInt(KEY_LEVEL, Math.max(0, Math.min(4, value))).apply();
    }

    public static String levelName(Context c) {
        switch (level(c)) {
            case 0:
                return "极速";
            case 1:
                return "严格";
            case 3:
                return "稳妥";
            case 4:
                return "宽松";
            default:
                return "标准";
        }
    }

    public static int probeTimeout(Context c) {
        switch (level(c)) {
            case 0:
                return 1000;
            case 1:
                return 1500;
            case 3:
                return 3000;
            case 4:
                return 4000;
            default:
                return 2500;
        }
    }

    public static int probeFails(Context c) {
        switch (level(c)) {
            case 0:
                return 1;
            case 4:
                return 3;
            default:
                return 2;
        }
    }

    /**
     * 探测节奏的基准值。
     *
     * 固定的 3 秒心跳既费电又机械，而且 TCP/TLS 握手的时刻容易和加速器
     * 自己的重连节奏撞车。所以每次在基准上下浮动 ±jitter，见 probeDelay()。
     */
    private static int probeBase(Context c) {
        switch (level(c)) {
            case 0:
                return 1500;
            case 1:
                return 2500;
            case 3:
                return 8000;
            case 4:
                return 12000;
            default:
                return 5000;
        }
    }

    private static int probeJitter(Context c) {
        int lv = level(c);
        if (lv == 0) return 400;
        if (lv == 1) return 800;
        return 2000;
    }

    private static final Random RND = new Random();

    /** 下一次探测的间隔：基准 ± 随机偏移，不会低于 900ms。 */
    public static int probeDelay(Context c) {
        int base = probeBase(c);
        int j = probeJitter(c);
        int spread = j <= 0 ? 0 : RND.nextInt(j * 2 + 1) - j;
        return Math.max(900, base + spread);
    }

    /** 界面上展示的节奏，例如「约 5 秒一次（±2 秒）」。 */
    public static String probeRhythm(Context c) {
        int base = probeBase(c);
        int j = probeJitter(c);
        String s = "约 " + (base / 1000f) + " 秒一次";
        if (j > 0) s += "（±" + (j / 1000f) + " 秒）";
        return s.replace(".0 秒", " 秒");
    }

    public static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    // ------------------------------------------------ 系统级禁用（强停失败时的兜底）

    /**
     * 强停完全不生效时，改用 pm disable-user 把应用「禁用」。
     *
     * 它用的是 CHANGE_COMPONENT_ENABLED_STATE 权限，比 FORCE_STOP_PACKAGES 基础得多，
     * shell 身份基本一定持有。禁用后系统根本不会启动该应用，比杀进程彻底。
     * 代价是图标会变灰/消失，所以必须记录，VPN 恢复后自动 enable 回来。
     */
    public static boolean hardBlock(Context c) {
        return sp(c).getBoolean(KEY_HARD, true);
    }

    public static void setHardBlock(Context c, boolean value) {
        sp(c).edit().putBoolean(KEY_HARD, value).apply();
    }

    /** 无障碍失效时是否用 Shizuku 通道接管前台监听。 */
    public static boolean shellGuard(Context c) {
        return sp(c).getBoolean(KEY_SHELL_GUARD, false);
    }

    public static void setShellGuard(Context c, boolean value) {
        sp(c).edit().putBoolean(KEY_SHELL_GUARD, value).apply();
    }

    // ------------------------------------------------------------------ 节点锁

    /**
     * 受保护应用分两层：
     *
     *   - 普通受保护：只要加速器通就行；
     *   - 节点约束（{@link #nodeGuarded}）：还要求出口是锁定地区。
     *
     * 这里只存"哪些应用受节点约束"，是否生效取决于名单非空且已锁定地区。
     */
    public static Set<String> nodeGuarded(Context c) {
        return new HashSet<>(sp(c).getStringSet(KEY_NODE_GUARDED,
                Collections.<String>emptySet()));
    }

    public static void setNodeGuarded(Context c, Set<String> pkgs) {
        Set<String> clean = new HashSet<>();
        if (pkgs != null) {
            for (String p : pkgs) {
                if (p != null && !p.isEmpty() && !SysApps.isCritical(c, p)) clean.add(p);
            }
        }
        // 只允许已受保护的应用进入节点名单
        clean.retainAll(guarded(c));
        sp(c).edit().putStringSet(KEY_NODE_GUARDED, clean).apply();
    }

    /** 这个应用是否被节点锁约束。 */
    public static boolean nodeBound(Context c, String pkg) {
        return pkg != null && !pkg.isEmpty() && nodeGuarded(c).contains(pkg);
    }

    public static boolean nodeActive(Context c) {
        return !nodeGuarded(c).isEmpty() && !nodeRegion(c).isEmpty();
    }

    /** 锁定的国家码，如 JP；空串表示还没锁定。 */
    public static String nodeRegion(Context c) {
        return sp(c).getString(KEY_NODE_REGION, "");
    }

    /** 展示用的名字，如「日本 · 东京 (NRT)」。 */
    public static String nodeLabel(Context c) {
        return sp(c).getString(KEY_NODE_LABEL, "");
    }

    public static boolean hasNodeRule(Context c) {
        return !nodeRegion(c).isEmpty();
    }

    public static void setNodeRule(Context c, String region, String label) {
        sp(c).edit()
                .putString(KEY_NODE_REGION, region == null ? "" : region)
                .putString(KEY_NODE_LABEL, label == null ? "" : label)
                .apply();
    }

    public static void clearNodeRule(Context c) {
        sp(c).edit()
                .remove(KEY_NODE_REGION)
                .remove(KEY_NODE_LABEL)
                .apply();
    }

    public static Set<String> disabled(Context c) {
        return new HashSet<>(sp(c).getStringSet(KEY_DISABLED, Collections.<String>emptySet()));
    }

    public static void markDisabled(Context c, String pkg) {
        Set<String> s = disabled(c);
        s.add(pkg);
        sp(c).edit().putStringSet(KEY_DISABLED, s).apply();
    }

    public static void unmarkDisabled(Context c, String pkg) {
        Set<String> s = disabled(c);
        if (s.remove(pkg)) {
            sp(c).edit().putStringSet(KEY_DISABLED, s).apply();
        }
    }
}
