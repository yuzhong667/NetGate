package com.d.netgate;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统关键应用判定。
 *
 * 强停的兜底是 pm suspend / pm disable-user —— 这两条打到桌面、输入法、
 * 拨号、SystemUI、Shizuku 上，轻则桌面消失、无法输入，重则连「打开 NetGate
 * 去恢复」的机会都没有。所以这些包必须硬性排除：宁可漏保护一个应用，
 * 也不能把手机锁死。
 */
public final class SysApps {

    /** 无论什么 ROM 都不允许挂起/禁用的包（前缀匹配）。 */
    private static final String[] HARD = {
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.shell",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "moe.shizuku.privileged.api",
            "rikka.shizuku",
    };

    private static final Map<String, Boolean> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> IMES = new HashSet<>();
    private static String homePkg = "";
    private static String dialPkg = "";
    private static String smsPkg = "";
    private static String settingsPkg = "";
    private static boolean primed;

    private SysApps() {
    }

    /** 设置页每次进入时刷新一次，跟随系统默认应用的变化。 */
    public static void refresh() {
        primed = false;
        CACHE.clear();
    }

    public static boolean isCritical(Context c, String pkg) {
        if (c == null || pkg == null || pkg.isEmpty()) return false;
        Boolean hit = CACHE.get(pkg);
        if (hit != null) return hit;
        boolean v;
        try {
            v = compute(c, pkg);
        } catch (Throwable t) {
            v = false;
        }
        CACHE.put(pkg, v);
        return v;
    }

    private static boolean compute(Context c, String pkg) {
        if (pkg.equals(c.getPackageName())) return true;
        for (String p : HARD) {
            if (pkg.equals(p) || pkg.startsWith(p + ".")) return true;
        }
        prime(c);
        // 输入法：被禁用后全系统无法输入，连解锁密码都打不进去
        if (IMES.contains(pkg)) return true;
        // 桌面 / 拨号 / 短信 / 设置由系统角色决定，各 ROM 包名不一致
        return pkg.equals(homePkg)
                || pkg.equals(dialPkg)
                || pkg.equals(smsPkg)
                || pkg.equals(settingsPkg);
    }

    private static synchronized void prime(Context c) {
        if (primed) return;
        primed = true;
        IMES.clear();
        try {
            InputMethodManager imm =
                    (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
            List<InputMethodInfo> list = imm == null ? null : imm.getEnabledInputMethodList();
            if (list != null) {
                for (InputMethodInfo i : list) {
                    if (i != null) IMES.add(i.getPackageName());
                }
            }
        } catch (Throwable ignored) {
        }
        homePkg = resolve(c, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        dialPkg = resolve(c, new Intent(Intent.ACTION_DIAL));
        smsPkg = resolve(c, new Intent(Intent.ACTION_VIEW, Uri.parse("sms:")));
        settingsPkg = resolve(c, new Intent(Settings.ACTION_SETTINGS));
    }

    private static String resolve(Context c, Intent i) {
        try {
            ResolveInfo ri = c.getPackageManager()
                    .resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            return ri == null || ri.activityInfo == null ? "" : ri.activityInfo.packageName;
        } catch (Throwable t) {
            return "";
        }
    }
}
