package com.d.netgate;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    private static final int REQ_NOTIF = 0x51D;
    private static final int REQ_SHIZUKU = 1001;

    private static final int BG = Ui.BG;
    private static final int CARD = Ui.CARD;
    private static final int STROKE = 0x14000000;
    private static final int TEXT = Ui.INK;
    private static final int SUB = Ui.SUB;
    private static final int GREEN = Ui.GREEN_DEEP;
    private static final int RED = Ui.RED_DEEP;
    private static final int AMBER = Ui.AMBER_DEEP;

    private static final int STATE_OK = 0;
    private static final int STATE_BAD = 1;
    private static final int STATE_WAIT = 2;

    private TextView accValue;
    private TextView shizukuValue;
    private TextView identValue;
    private TextView netValue;
    private TextView levelValue;
    private TextView targetsValue;
    private TextView guardCount;
    private TextView headSub;
    private TextView killValue;
    private TextView restoreLink;
    /** 有应用被挂起/禁用时的置顶警示条。 */
    private LinearLayout disabledBar;
    private TextView disabledText;

    // 连通性主卡
    private FrameLayout netIconWrap;
    private Glyph netGlyph;
    private TextView netTitle;
    private TextView netSub;
    private TextView nodeValue;
    private TextView nodeSub;
    private TextView nodeExit;
    private TextView netRtt;
    private TextView netRttUnit;
    private LinearLayout targetList;
    private View pulseDot;
    private TextView netFootText;

    private boolean probing;
    private long lastProbeAt;
    private long lastProbeDoneAt;
    private NetworkGate.Probe lastProbe;
    private android.animation.ValueAnimator pulseAnim;

    /** 页面开着时每 2 秒刷新一次网络状态，而不是只靠 onResume。 */
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable uiTick = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            refreshStatusOnly();
            uiHandler.postDelayed(this, 2000);
        }
    };

    private final Shizuku.OnRequestPermissionResultListener permListener =
            (requestCode, grantResult) -> {
                if (requestCode != REQ_SHIZUKU) return;
                boolean ok = grantResult == PackageManager.PERMISSION_GRANTED;
                toast(ok ? "Shizuku 授权成功" : "Shizuku 授权被拒绝");
                if (ok) ShizukuShell.init(this);
                refresh();
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.migrate(this);
        // 旧版本或手工导入的配置里可能混进了桌面、输入法，启动时清一次
        SysApps.refresh();
        Set<String> stale = Prefs.guarded(this);
        if (stale.removeIf(pkg -> SysApps.isCritical(this, pkg))) {
            Prefs.setGuarded(this, stale);
        }
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        // 浅色底必须配深色状态栏图标，否则时间、电量会白底白字看不见
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        buildUi();
        try {
            Shizuku.addRequestPermissionResultListener(permListener);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ShizukuShell.init(this);
        // 服务可能被 ColorOS 清掉，回到前台时补一次
        if (Prefs.shellGuard(this) && !ShellGuardService.running) {
            ShellGuardService.start(this);
        }
        // 卡片上要显示「当前出口」；没有加速器通道时读也没用，白耗流量
        if (NetworkGate.hasVpn(this)
                && (Prefs.hasNodeRule(this) || !Prefs.nodeGuarded(this).isEmpty())) {
            refreshExitAsync();
        }
        refresh();
        uiHandler.removeCallbacks(uiTick);
        uiHandler.postDelayed(uiTick, 1500);
        startPulse();
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(uiTick);
        stopPulse();
        super.onPause();
    }

    /** 底部那个"还在监控"的呼吸点，只在页面可见时跑，别白耗电。 */
    private void startPulse() {
        if (pulseAnim != null || pulseDot == null) return;
        pulseAnim = android.animation.ValueAnimator.ofFloat(0.3f, 1f);
        pulseAnim.setDuration(1100);
        pulseAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        pulseAnim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        pulseAnim.addUpdateListener(a -> {
            if (pulseDot != null) pulseDot.setAlpha((float) a.getAnimatedValue());
        });
        pulseAnim.start();
    }

    private void stopPulse() {
        if (pulseAnim != null) {
            pulseAnim.cancel();
            pulseAnim = null;
        }
        if (pulseDot != null) pulseDot.setAlpha(1f);
    }

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permListener);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    private void buildUi() {
        try {
            buildMainUi();
        } catch (Throwable t) {
            // 新界面万一在某个 ROM 上构建失败，也不能让应用直接闪退：
            // 退到一个只有两个按钮的极简界面，功能仍然可用。
            try {
                Diag.log("主界面构建失败 · " + t);
            } catch (Throwable ignored) {
            }
            buildFallbackUi(t);
        }
    }

    private void buildFallbackUi(Throwable t) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(dp(24), dp(48), dp(24), dp(24));

        root.addView(Ui.text(this, "NetGate", 24, Ui.INK, true));
        root.addView(Ui.text(this, "界面加载出错，但拦截功能不受影响。", 13, Ui.SUB, false),
                lpTop(-1, -2, 10));
        root.addView(Ui.text(this, String.valueOf(t), 11, Ui.RED_DEEP, false),
                lpTop(-1, -2, 8));

        TextView b1 = Ui.text(this, "打开无障碍设置", 15, 0xFFFFFFFF, true);
        b1.setGravity(Gravity.CENTER);
        b1.setBackground(Ui.grad(0xFF3D9BFF, 0xFF0673E3, 13, this));
        b1.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable ignored) {
            }
        });
        root.addView(b1, lpTop(-1, dp(48), 22));

        TextView b2 = Ui.text(this, "管理受保护应用", 15, Ui.INK, true);
        b2.setGravity(Gravity.CENTER);
        b2.setBackground(Ui.round(0xFFFFFFFF, 13, this));
        b2.setOnClickListener(v -> startActivity(new Intent(this, AppPickerActivity.class)));
        root.addView(b2, lpTop(-1, dp(48), 10));

        setContentView(root);
    }

    private void buildMainUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(26), dp(18), dp(44));
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);

        // ------------------------------------------------------------ 抬头
        TextView kicker = Ui.text(this, "网络门卫 · 本地拦截", 11, Ui.GREEN_DEEP, true);
        kicker.setLetterSpacing(0.12f);
        root.addView(kicker);

        TextView title = Ui.text(this, "NetGate", 30, Ui.INK, true);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setLetterSpacing(-0.02f);
        root.addView(title, lpTop(-1, -2, 6));

        headSub = Ui.text(this, "正在读取网络状态", 13, Ui.SUB, false);
        root.addView(headSub, lpTop(-1, -2, 7));

        // 被系统停用的应用：这是唯一会让用户「应用凭空消失」的状态，必须置顶
        disabledBar = new LinearLayout(this);
        disabledBar.setOrientation(LinearLayout.HORIZONTAL);
        disabledBar.setGravity(Gravity.CENTER_VERTICAL);
        disabledBar.setBackground(Ui.round(0xFFFFF2DE, 16, this));
        disabledBar.setPadding(dp(12), dp(11), dp(12), dp(11));
        disabledBar.setVisibility(View.GONE);

        Glyph warn = new Glyph(this);
        warn.type(Glyph.TRI).color(0xFFD98200).weight(2.2f);
        FrameLayout warnWrap = new FrameLayout(this);
        warnWrap.addView(warn,
                new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        disabledBar.addView(warnWrap, new LinearLayout.LayoutParams(dp(28), dp(28)));

        LinearLayout warnCol = new LinearLayout(this);
        warnCol.setOrientation(LinearLayout.VERTICAL);
        disabledText = Ui.text(this, "", 13, 0xFF8A5A00, true);
        warnCol.addView(disabledText);
        TextView warnSub = Ui.text(this, "网络恢复后自动还原，也可以点这里立即恢复",
                11, 0xFFA5814A, false);
        warnCol.addView(warnSub, lpTop(-1, -2, 2));
        disabledBar.addView(warnCol, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView warnAct = Ui.text(this, "恢复 ›", 12, 0xFFB26A00, true);
        warnAct.setPadding(dp(8), dp(4), dp(2), dp(4));
        disabledBar.addView(warnAct);
        disabledBar.setOnClickListener(v -> showRestoreApps());
        root.addView(disabledBar, lpTop(-1, -2, 14));

        // ------------------------------------------------------ 连通性主卡
        Ui.ShadowCard netCard = new Ui.ShadowCard(this);
        netCard.radius(21);
        netCard.depth(3.2f, 0x1A000000);
        netCard.bg(Ui.CARD);
        netCard.setPadding(dp(14), dp(14), dp(14), 0);
        root.addView(netCard, lpTop(-1, -2, 18));

        LinearLayout netTop = new LinearLayout(this);
        netTop.setOrientation(LinearLayout.HORIZONTAL);
        netTop.setGravity(Gravity.CENTER_VERTICAL);

        netIconWrap = new FrameLayout(this);
        netGlyph = new Glyph(this);
        netGlyph.type(Glyph.CHECK).color(Ui.GREEN_DEEP).weight(2.4f);
        netIconWrap.addView(netGlyph,
                new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        netTop.addView(netIconWrap, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout netMeta = new LinearLayout(this);
        netMeta.setOrientation(LinearLayout.VERTICAL);
        netTitle = Ui.text(this, "检测中…", 16, Ui.INK, true);
        netTitle.setSingleLine(true);
        netTitle.setEllipsize(TextUtils.TruncateAt.END);
        netMeta.addView(netTitle, new LinearLayout.LayoutParams(-1, -2));
        netSub = Ui.text(this, "正在读取网络状态", 11, Ui.SUB, false);
        netSub.setSingleLine(true);
        netSub.setEllipsize(TextUtils.TruncateAt.END);
        netMeta.addView(netSub, lpTop(-1, -2, 4));
        LinearLayout.LayoutParams nmp = new LinearLayout.LayoutParams(0, -2, 1f);
        nmp.leftMargin = dp(11);
        netTop.addView(netMeta, nmp);

        LinearLayout rttCol = new LinearLayout(this);
        rttCol.setOrientation(LinearLayout.VERTICAL);
        rttCol.setGravity(Gravity.END);
        LinearLayout rttRow = new LinearLayout(this);
        rttRow.setOrientation(LinearLayout.HORIZONTAL);
        rttRow.setGravity(Gravity.BOTTOM);
        netRtt = Ui.text(this, "—", 21, Ui.INK, true);
        netRttUnit = Ui.text(this, "ms", 11, Ui.SUB, true);
        LinearLayout.LayoutParams rup = new LinearLayout.LayoutParams(-2, -2);
        rup.leftMargin = dp(1);
        rup.bottomMargin = dp(3);
        rttRow.addView(netRtt);
        rttRow.addView(netRttUnit, rup);
        rttCol.addView(rttRow);
        TextView rttCap = Ui.text(this, "平均延迟", 10, Ui.SUB, false);
        rttCap.setGravity(Gravity.END);
        rttCol.addView(rttCap, lpTop(-1, -2, 1));
        netTop.addView(rttCol);
        netCard.addView(netTop, new LinearLayout.LayoutParams(-1, -2));

        targetList = new LinearLayout(this);
        targetList.setOrientation(LinearLayout.VERTICAL);
        netCard.addView(targetList, lpTop(-1, -2, 10));

        View netLine = new View(this);
        netLine.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, dp(1));
        nlp.topMargin = dp(10);
        netCard.addView(netLine, nlp);

        LinearLayout netFoot = new LinearLayout(this);
        netFoot.setOrientation(LinearLayout.HORIZONTAL);
        netFoot.setGravity(Gravity.CENTER_VERTICAL);
        netFoot.setPadding(0, dp(11), 0, dp(12));

        pulseDot = new View(this);
        LinearLayout.LayoutParams pdp = new LinearLayout.LayoutParams(dp(6), dp(6));
        pdp.rightMargin = dp(6);
        netFoot.addView(pulseDot, pdp);

        netFootText = Ui.text(this, "实时监控中", 11, Ui.SUB, false);
        netFoot.addView(netFootText, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView detailLink = Ui.text(this, "校验详情 ›", 11, Ui.BLUE, true);
        detailLink.setPadding(dp(10), dp(2), 0, dp(2));
        detailLink.setOnClickListener(v -> runProbeDialog());
        netFoot.addView(detailLink);
        netCard.addView(netFoot, new LinearLayout.LayoutParams(-1, -2));

        // ---------------------------------------------------------- 节点锁
        Ui.ShadowCard nodeCard = new Ui.ShadowCard(this);
        nodeCard.radius(19);
        nodeCard.depth(2.2f, 0x14000000);
        nodeCard.bg(Ui.CARD);
        nodeCard.setPadding(dp(14), dp(12), dp(14), 0);
        root.addView(nodeCard, lpTop(-1, -2, 12));

        LinearLayout nodeTop = new LinearLayout(this);
        nodeTop.setOrientation(LinearLayout.HORIZONTAL);
        nodeTop.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout nodeIco = new FrameLayout(this);
        nodeIco.setBackground(Ui.grad(0xFFFFF3E2, 0xFFFFE6C9, 11, this));
        Glyph nodeGlyph = new Glyph(this);
        nodeGlyph.type(Glyph.BOLT).color(Ui.AMBER_DEEP).weight(2.1f);
        nodeIco.addView(nodeGlyph,
                new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        nodeTop.addView(nodeIco, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout nodeMeta = new LinearLayout(this);
        nodeMeta.setOrientation(LinearLayout.VERTICAL);
        TextView nodeTitle = Ui.text(this, "节点锁", 14, Ui.INK, true);
        nodeTitle.setSingleLine(true);
        nodeMeta.addView(nodeTitle);
        nodeSub = Ui.text(this, "未启用 · 任意节点均可", 11, Ui.SUB, false);
        nodeSub.setSingleLine(true);
        nodeSub.setEllipsize(TextUtils.TruncateAt.END);
        nodeMeta.addView(nodeSub, lpTop(-1, -2, 3));

        LinearLayout.LayoutParams nodeMp = new LinearLayout.LayoutParams(0, -2, 1f);
        nodeMp.leftMargin = dp(11);
        nodeTop.addView(nodeMeta, nodeMp);

        nodeValue = Ui.text(this, "选择 ›", 12, Ui.SUB, false);
        nodeTop.addView(nodeValue);
        nodeCard.addView(nodeTop, new LinearLayout.LayoutParams(-1, -2));
        nodeCard.setOnClickListener(v -> showNodeDialog());

        View nodeLine = new View(this);
        nodeLine.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams nodeLp = new LinearLayout.LayoutParams(-1, dp(1));
        nodeLp.topMargin = dp(10);
        nodeCard.addView(nodeLine, nodeLp);

        LinearLayout nodeFoot = new LinearLayout(this);
        nodeFoot.setOrientation(LinearLayout.HORIZONTAL);
        nodeFoot.setGravity(Gravity.CENTER_VERTICAL);
        nodeFoot.setPadding(0, dp(11), 0, dp(12));

        // 这一行只做信息展示：整张卡片已经是一个入口，再放「设置」就重复了
        nodeExit = Ui.text(this, "当前出口 · 未知", 11, Ui.SUB, false);
        nodeExit.setSingleLine(true);
        nodeExit.setEllipsize(TextUtils.TruncateAt.END);
        nodeFoot.addView(nodeExit, new LinearLayout.LayoutParams(0, -2, 1f));
        nodeCard.addView(nodeFoot, new LinearLayout.LayoutParams(-1, -2));

        // ---------------------------------------------------------- 设置组
        Ui.ShadowCard group = new Ui.ShadowCard(this);
        group.radius(19);
        group.depth(2.2f, 0x14000000);
        group.bg(Ui.CARD);
        group.setPadding(0, dp(2), 0, dp(2));
        root.addView(group, lpTop(-1, -2, 12));

        guardCount = addSettingRow(group, Glyph.SHIELD,
                0xFFF2EEFF, 0xFFE4DCFF, Ui.PURPLE,
                "受保护应用", "选中的应用", "0 个",
                v -> startActivity(new Intent(this, AppPickerActivity.class)));
        addDivider(group);

        levelValue = addSettingRow(group, Glyph.SWORD,
                0xFFFFEAE8, 0xFFFFD7D3, Ui.RED_DEEP,
                "拦截强度", "超时与失败次数", "",
                v -> showLevelDialog());
        addDivider(group);

        killValue = addSettingRow(group, Glyph.SHIELD_CHECK,
                0xFFE5F1FF, 0xFFD2E6FF, 0xFF0A78E8,
                "强停通道", "Shizuku · shell 身份", "",
                v -> runSelfCheck());
        addDivider(group);

        targetsValue = addSettingRow(group, Glyph.BOLT,
                0xFFFFF2E0, 0xFFFFE5C4, Ui.AMBER_DEEP,
                "检测目标", "走 VPN 侧 DNS", "",
                v -> showTargetsDialog());

        // ------------------------------------------------------ 开关卡片
        Ui.ShadowCard toggles = new Ui.ShadowCard(this);
        toggles.radius(19);
        toggles.depth(2.2f, 0x14000000);
        toggles.bg(Ui.CARD);
        toggles.setPadding(0, dp(2), 0, dp(2));
        root.addView(toggles, lpTop(-1, -2, 12));

        Switch probeSwitch = switchFor(Prefs.probe(this));
        probeSwitch.setOnCheckedChangeListener((b, checked) -> {
            Prefs.setProbe(this, checked);
            refresh();
        });
        addSwitchRow(toggles, "校验实际连通性", "VPN 挂着但实际不通时也拦截", probeSwitch);
        addDivider(toggles);

        Switch killSwitch = switchFor(Prefs.kill(this));
        killSwitch.setOnCheckedChangeListener((b, checked) -> Prefs.setKill(this, checked));
        addSwitchRow(toggles, "拦截时结束进程", "需要 Shizuku；关闭后只退回桌面", killSwitch);
        addDivider(toggles);

        Switch hardSwitch = switchFor(Prefs.hardBlock(this));
        hardSwitch.setOnCheckedChangeListener((b, checked) -> Prefs.setHardBlock(this, checked));
        addSwitchRow(toggles, "强停无效时挂起应用",
                "最后的兜底：图标会变灰，网络恢复后自动还原", hardSwitch);
        addDivider(toggles);

        Switch shellSwitch = switchFor(Prefs.shellGuard(this));
        shellSwitch.setOnCheckedChangeListener((b, checked) -> {
            Prefs.setShellGuard(this, checked);
            if (checked) {
                ensureNotifPermission();
                ShellGuardService.start(this);
            } else {
                ShellGuardService.stop(this);
            }
        });
        addSwitchRow(toggles, "Shizuku 前台监听",
                "无障碍被撤销时接管 · 提示降级为通知", shellSwitch);

        // -------------------------------------------------------- 运行状态
        TextView stHead = Ui.text(this, "运行状态", 13, Ui.SUB, true);
        stHead.setPadding(dp(4), 0, 0, 0);
        root.addView(stHead, lpTop(-1, -2, 24));

        Ui.ShadowCard stCard = new Ui.ShadowCard(this);
        stCard.radius(19);
        stCard.depth(2.2f, 0x14000000);
        stCard.bg(Ui.CARD);
        stCard.setPadding(0, dp(2), 0, dp(2));
        root.addView(stCard, lpTop(-1, -2, 8));

        accValue = addPlainRow(stCard, "无障碍服务", "—");
        addDivider(stCard);
        shizukuValue = addPlainRow(stCard, "Shizuku 权限", "—");
        addDivider(stCard);
        identValue = addPlainRow(stCard, "执行身份", "—");
        addDivider(stCard);
        netValue = addPlainRow(stCard, "当前网络", "—");

        // ---------------------------------------------------------- 工具
        Ui.ShadowCard tools = new Ui.ShadowCard(this);
        tools.radius(19);
        tools.depth(2.2f, 0x14000000);
        tools.bg(Ui.CARD);
        tools.setPadding(0, dp(2), 0, dp(2));
        root.addView(tools, lpTop(-1, -2, 12));

        addToolRow(tools, "开启无障碍服务", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable t) {
                toast("无法打开无障碍设置");
            }
        });
        addDivider(tools);
        addToolRow(tools, "授权 Shizuku", v -> requestShizuku());
        addDivider(tools);
        addToolRow(tools, "Shizuku 通道自检", v -> runSelfCheck());
        addDivider(tools);
        addToolRow(tools, "预览拦截效果", v -> {
            if (!GuardService.preview()) toast("请先开启无障碍服务");
        });
        addDivider(tools);
        addToolRow(tools, "诊断报告", v -> showDiag());
        addDivider(tools);
        addToolRow(tools, "强停诊断", v -> showKillDiag());

        restoreLink = addToolRow(tools, "恢复被禁用的应用", v -> showRestoreApps());
        restoreLink.setTextColor(Ui.AMBER_DEEP);
        restoreLink.setVisibility(View.GONE);

        // ---------------------------------------------------------- 说明
        Ui.ShadowCard info = new Ui.ShadowCard(this);
        info.radius(19);
        info.depth(2.2f, 0x14000000);
        info.bg(Ui.CARD);
        info.setPadding(dp(15), dp(14), dp(15), dp(15));
        root.addView(info, lpTop(-1, -2, 12));

        info.addView(Ui.text(this, "工作方式", 12, Ui.SUB, true));
        TextView body = Ui.text(this,
                "加速器开启时，系统会出现一条 VPN 通道。\n"
                        + "开启「校验实际连通性」后，受保护应用在前台期间，"
                        + "会按当前强度每隔几秒（带随机偏移）向检测目标发起一次 TLS 端到端握手：\n"
                        + "通道存在但连续探测失败，判定为加速器已掉线，立即拦截。\n\n"
                        + "拦截时先盖上提示层、退回桌面，再通过 Shizuku 执行 force-stop：\n"
                        + "该应用的全部进程会被结束，并进入 stopped 状态。\n"
                        + "最近任务里的卡片可能仍在，点开会冷启动并再次被拦截。\n\n"
                        + "探测只做握手，不发送任何业务数据，也不上传。\n"
                        + "若加速器是分流模式，请把检测目标改成游戏服务器的域名或 IP。",
                12, Ui.SUB, false);
        body.setLineSpacing(dp(4), 1f);
        info.addView(body, lpTop(-1, -2, 10));

        // 版本号从 PackageInfo 读，避免每次发版忘了改这里
        String verName = "2.6";
        try {
            verName = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
        }
        TextView foot = Ui.text(this, "v" + verName + " · 由 DeepSeek 打造",
                11, 0xFFB0B0B8, false);
        foot.setGravity(Gravity.CENTER);
        root.addView(foot, lpTop(-1, -2, 24));
    }

    // ------------------------------------------------------------ 主界面零件

    // -------------------------------------------------------------- 节点锁

    /**
     * 国内常用节点地区。
     *
     * 加速器不会把「当前是日本 01」告诉系统，只能靠出口 IP 反查国家；
     * 反查失败时不能让用户卡死，所以这里给一份固定表，手动指定即可。
     */
    private static final String[][] NODE_REGIONS = {
            {"JP", "日本"}, {"HK", "中国香港"}, {"TW", "中国台湾"},
            {"SG", "新加坡"}, {"KR", "韩国"}, {"US", "美国"},
            {"GB", "英国"}, {"DE", "德国"}, {"NL", "荷兰"},
            {"CA", "加拿大"}, {"AU", "澳大利亚"}, {"TH", "泰国"},
            {"MY", "马来西亚"}, {"VN", "越南"}, {"TR", "土耳其"},
            {"AE", "阿联酋"}, {"RU", "俄罗斯"}, {"BR", "巴西"},
    };

    /** 弹窗里选中的地区，读出口成功时自动填一次，用户点过之后就不再覆盖。 */
    private static final class NodePick {
        String sel;
        boolean touched;

        NodePick(String s) {
            sel = s == null ? "" : s;
        }
    }

    private interface ExitSink {
        void onExit(NodeLock.Exit e);
    }

    private void updateNodeCard() {
        if (nodeSub == null) return;
        int n = Prefs.nodeGuarded(this).size();
        boolean has = Prefs.hasNodeRule(this);

        String sub;
        if (n == 0) {
            sub = "未指定应用 · 普通保护不受限";
        } else if (!has) {
            sub = n + " 个应用待锁定节点";
        } else {
            sub = n + " 个应用只走 " + Prefs.nodeLabel(this);
        }
        nodeSub.setText(sub);
        nodeSub.setTextColor(n > 0 && !has ? Ui.AMBER_DEEP : Ui.SUB);
        if (nodeValue != null) {
            nodeValue.setText(has ? "已锁定 ›" : "选择 ›");
        }

        NodeLock.Exit e = NodeLock.cached();
        boolean mismatch = has && e != null && e.usable()
                && !Prefs.nodeRegion(this).equalsIgnoreCase(e.loc);
        nodeExit.setText(exitLine());
        nodeExit.setTextColor(mismatch ? Ui.RED_DEEP : Ui.SUB);
    }

    private String exitLine() {
        NodeLock.Exit e = NodeLock.cached();
        if (e == null) {
            if (!NetworkGate.hasVpn(this)) return "当前出口 · 未连接加速器";
            return NodeLock.lastFailAt() > 0 ? "当前出口 · 读取失败" : "当前出口 · 未读取";
        }
        String s = "当前出口 " + e.tag();
        if (!e.viaVpn) s += " · 未走隧道";
        if (Prefs.hasNodeRule(this) && e.usable()
                && !Prefs.nodeRegion(this).equalsIgnoreCase(e.loc)) {
            s += " · 与锁定不符";
        }
        return s;
    }

    private String exitMeta(NodeLock.Exit e) {
        StringBuilder sb = new StringBuilder();
        if (e.ip != null) sb.append(e.ip);
        if (e.colo != null && !e.colo.isEmpty()) sb.append("   ·   ").append(e.colo);
        sb.append(e.viaVpn ? "   ·   已走隧道" : "   ·   未走隧道");
        return sb.toString();
    }

    private void refreshExitAsync() {
        readExitAsync(null);
    }

    /** 后台读一次出口，结果回主线程；e 为 null 表示这次没读到（保留旧缓存）。 */
    private void readExitAsync(final ExitSink sink) {
        new Thread(() -> {
            NodeLock.Exit e = null;
            try {
                e = NodeLock.refresh(this);
            } catch (Throwable ignored) {
            }
            final NodeLock.Exit r = e;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                updateNodeCard();
                if (sink != null) sink.onExit(r);
            });
        }, "netgate-node-ui").start();
    }

    private void showNodeDialog() {
        final NodeLock.Exit cached = NodeLock.cached();
        final boolean has = Prefs.hasNodeRule(this);
        final int n = Prefs.nodeGuarded(this).size();
        // 已锁定的地区优先；没锁过就用刚读到的出口预选，用户点过之后不再自动改
        final NodePick pick = new NodePick(has ? Prefs.nodeRegion(this)
                : (cached != null && cached.usable() ? cached.loc : ""));
        // 已经有规则时，自动读到的出口不能顶掉它（否则一开一关就把规则改了）
        pick.touched = has;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), dp(4));

        // ---------------------------------------------------------- 当前出口
        LinearLayout now = new LinearLayout(this);
        now.setOrientation(LinearLayout.VERTICAL);
        now.setBackground(Ui.round(0xFFF7F7FA, 14, this));
        now.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout nowHead = new LinearLayout(this);
        nowHead.setOrientation(LinearLayout.HORIZONTAL);
        nowHead.setGravity(Gravity.CENTER_VERTICAL);
        nowHead.addView(Ui.text(this, "当前出口", 11, Ui.SUB2, true),
                new LinearLayout.LayoutParams(0, -2, 1f));
        TextView reRead = Ui.text(this, "重新读取 ›", 11, Ui.BLUE, true);
        nowHead.addView(reRead);
        now.addView(nowHead);

        final TextView exitWhere = Ui.text(this,
                cached == null ? (NodeLock.lastFailAt() > 0 ? "读取失败" : "未读取")
                        : cached.where(),
                18, Ui.INK, true);
        now.addView(exitWhere, lpTop(-1, -2, 5));

        final TextView exitMeta = Ui.text(this,
                cached == null ? "读不到也没关系，在下面直接选国家" : exitMeta(cached),
                11, Ui.SUB, false);
        now.addView(exitMeta, lpTop(-1, -2, 4));
        box.addView(now, new LinearLayout.LayoutParams(-1, -2));

        // ------------------------------------------------------------ 地区选择
        final TextView ruleText = Ui.text(this, ruleLine(pick.sel), 13,
                pick.sel.isEmpty() ? Ui.AMBER_DEEP : Ui.INK, true);
        box.addView(ruleText, lpTop(-1, -2, 14));

        final LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        box.addView(grid, lpTop(-1, -2, 6));
        fillRegions(grid, pick, cached, ruleText);

        // -------------------------------------------------------- 应用名单入口
        LinearLayout apps = new LinearLayout(this);
        apps.setOrientation(LinearLayout.HORIZONTAL);
        apps.setGravity(Gravity.CENTER_VERTICAL);
        apps.setBackground(Ui.round(0xFFFFFFFF, 12, this));
        apps.setPadding(dp(13), dp(12), dp(13), dp(12));
        apps.setClickable(true);
        TextView apLabel = Ui.text(this, "限定节点的应用", 13, Ui.INK, false);
        apps.addView(apLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView apValue = Ui.text(this, n + " 个 ›", 13, Ui.BLUE, true);
        apps.addView(apValue);
        apps.setOnClickListener(v -> {
            Intent i = new Intent(this, AppPickerActivity.class);
            i.putExtra("mode", "node");
            startActivity(i);
        });
        box.addView(apps, lpTop(-1, -2, 14));

        TextView note = Ui.text(this,
                "只有这里选中的应用受节点限制；其余受保护应用只要加速器通就能用。\n"
                        + "判定只看国家，不看 IP——出口 IP 每天都会被运营商重新分配。\n"
                        + "读不到出口时无法比对，这一轮先放行；地区照旧保存，下次读到就按它判定。",
                11, 0xFF7C7C85, false);
        note.setLineSpacing(dp(4), 1f);
        box.addView(note, lpTop(-1, -2, 10));

        if (cached != null) {
            box.addView(Ui.text(this, "最近几次出口", 11, Ui.SUB2, true),
                    lpTop(-1, -2, 14));
            TextView h = Ui.text(this, NodeLock.historyText(), 11, 0xFF7C7C85, false);
            h.setLineSpacing(dp(3), 1f);
            box.addView(h, lpTop(-1, -2, 5));
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(box, new FrameLayout.LayoutParams(-1, -2));

        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.NetGateDialog)
                .setTitle("节点锁")
                .setView(sv)
                .setNegativeButton("关闭", null);

        if (has) {
            b.setNeutralButton("解除锁定", (d, w) -> {
                Prefs.clearNodeRule(this);
                updateNodeCard();
                toast("已解除节点锁定，应用恢复为普通保护");
            });
        }
        b.setPositiveButton("锁定", null);

        // 打开弹窗就试读一次：之前「读取中…」永远不动，用户只能干等
        final boolean fresh = cached != null
                && System.currentTimeMillis() - cached.at < 15000;
        if (!fresh && !NetworkGate.hasVpn(this) && cached == null) {
            exitWhere.setText("未连接加速器");
            exitMeta.setText("连上后会自动读取；现在也可以直接在下面选国家");
        } else if (!fresh) {
            if (cached == null) {
                exitWhere.setText("读取中…");
                exitMeta.setText("正在请求出口指纹，读不到也可以在下面手动选");
            }
            readExitAsync(e -> {
                if (e == null) showReadFail(exitWhere, exitMeta);
                else {
                    exitWhere.setText(e.where());
                    exitMeta.setText(exitMeta(e));
                    if (!pick.touched) pick.sel = e.loc;
                    fillRegions(grid, pick, e, ruleText);
                    ruleText.setText(ruleLine(pick.sel));
                    ruleText.setTextColor(pick.sel.isEmpty() ? Ui.AMBER_DEEP : Ui.INK);
                }
            });
        }

        final AlertDialog d = dlg(b);
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String sel = pick.sel;
            if (sel == null || sel.isEmpty()) {
                toast("先在上面选一个国家");
                return;
            }
            if (!NetworkGate.hasVpn(this)) {
                toast("当前没有加速器通道，锁节点暂时不会生效");
            }
            Prefs.setNodeRule(this, sel, NodeLock.countryName(sel));
            updateNodeCard();
            toast("已锁定 " + NodeLock.countryName(sel)
                    + (n == 0 ? " · 记得勾选要限定的应用" : ""));
            d.dismiss();
        });

        reRead.setOnClickListener(v -> {
            exitWhere.setText("读取中…");
            exitMeta.setText("正在请求出口指纹…");
            readExitAsync(e -> {
                if (e == null) {
                    showReadFail(exitWhere, exitMeta);
                    return;
                }
                exitWhere.setText(e.where());
                exitMeta.setText(exitMeta(e));
                pick.touched = false;
                pick.sel = e.loc;
                fillRegions(grid, pick, e, ruleText);
                ruleText.setText(ruleLine(pick.sel));
                ruleText.setTextColor(Ui.INK);
            });
        });
    }

    /** 读取失败时别把上一次的结果抹掉——那仍然是当前最有用的信息。 */
    private void showReadFail(TextView where, TextView meta) {
        NodeLock.Exit last = NodeLock.cached();
        if (last != null) {
            where.setText(last.where());
            meta.setText("刚才没读到 · 这是上次的结果");
            return;
        }
        where.setText("读取失败");
        meta.setText(NetworkGate.hasVpn(this)
                ? "加速器没返回出口信息，在下面直接选国家即可"
                : "当前没有加速器通道，在下面直接选国家即可");
    }

    private String ruleLine(String sel) {
        if (sel == null || sel.isEmpty()) return "未锁定 · 选一个国家";
        String name = NodeLock.countryName(sel);
        return Prefs.nodeRegion(this).equalsIgnoreCase(sel)
                ? "已锁定：" + name : "将锁定到：" + name;
    }

    /** 三列地区按钮；读到的国家不在表里时临时补一个。 */
    private void fillRegions(final LinearLayout grid, final NodePick pick,
                             final NodeLock.Exit e, final TextView ruleText) {
        grid.removeAllViews();

        List<String[]> items = new ArrayList<>();
        Collections.addAll(items, NODE_REGIONS);
        if (e != null && e.usable() && e.loc != null && !e.loc.isEmpty()) {
            boolean known = false;
            for (String[] r : items) {
                if (r[0].equalsIgnoreCase(e.loc)) {
                    known = true;
                    break;
                }
            }
            if (!known) items.add(new String[]{e.loc, NodeLock.countryName(e.loc)});
        }

        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, lpTop(-1, -2, i == 0 ? 0 : 8));
            }
            final String code = items.get(i)[0];
            final String name = items.get(i)[1];
            boolean on = code.equalsIgnoreCase(pick.sel);

            TextView chip = Ui.text(this, name, 12, on ? Ui.BLUE : Ui.TEXT, on);
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine(true);
            chip.setBackground(on
                    ? Ui.stroke(0xFFEAF3FF, 11, 1.2f, 0x660A84FF, this)
                    : Ui.round(0xFFF4F4F8, 11, this));
            chip.setPadding(0, dp(9), 0, dp(9));
            chip.setOnClickListener(v -> {
                pick.sel = code;
                pick.touched = true;
                fillRegions(grid, pick, e, ruleText);
                ruleText.setText(ruleLine(pick.sel));
                ruleText.setTextColor(Ui.INK);
            });

            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1f);
            cp.leftMargin = i % 3 == 0 ? 0 : dp(4);
            cp.rightMargin = i % 3 == 2 ? 0 : dp(4);
            row.addView(chip, cp);
        }

        // 最后一行不满三个：补空位，别把剩下的按钮拉宽
        int rem = items.size() % 3;
        if (row != null && rem != 0) {
            for (int i = rem; i < 3; i++) {
                View gap = new View(this);
                LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(0, 1, 1f);
                gp.leftMargin = i == 0 ? 0 : dp(4);
                gp.rightMargin = i == 2 ? 0 : dp(4);
                row.addView(gap, gp);
            }
        }
    }

    private TextView addSettingRow(LinearLayout parent, int glyphType,
                                   int bgTop, int bgBottom, int glyphColor,
                                   String label, String defSub, String value,
                                   View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        if (click != null) {
            row.setOnClickListener(click);
            row.setBackground(ripple());
        }

        FrameLayout ico = new FrameLayout(this);
        ico.setBackground(Ui.grad(bgTop, bgBottom, 9, this));
        Glyph g = new Glyph(this);
        g.type(glyphType).color(glyphColor).weight(2.1f);
        ico.addView(g, new FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(30), dp(30));
        ip.rightMargin = dp(12);
        row.addView(ico, ip);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView lt = Ui.text(this, label, 14, Ui.INK, false);
        lt.setSingleLine(true);
        lt.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(lt, new LinearLayout.LayoutParams(-1, -2));
        if (defSub != null && !defSub.isEmpty()) {
            TextView st = Ui.text(this, defSub, 11, Ui.SUB, false);
            st.setSingleLine(true);
            st.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(st, lpTop(-1, -2, 3));
        }
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView v = Ui.text(this, value, 13, Ui.SUB, false);
        v.setGravity(Gravity.END);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(v, new LinearLayout.LayoutParams(-2, -2));

        if (click != null) {
            Glyph chev = new Glyph(this);
            chev.type(Glyph.CHEVRON).color(0xFFC4C4CC).weight(2.6f);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(12), dp(12));
            cp.leftMargin = dp(7);
            row.addView(chev, cp);
        }

        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return v;
    }

    private TextView addPlainRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(15), dp(11), dp(15), dp(11));
        row.addView(Ui.text(this, label, 14, Ui.TEXT, false),
                new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = Ui.text(this, value, 13, Ui.SUB, false);
        v.setGravity(Gravity.END);
        row.addView(v);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return v;
    }

    private TextView addToolRow(LinearLayout parent, String label, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(15), dp(12), dp(15), dp(12));
        row.setBackground(ripple());
        row.setOnClickListener(click);

        TextView t = Ui.text(this, label, 14, Ui.TEXT, false);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));

        Glyph chev = new Glyph(this);
        chev.type(Glyph.CHEVRON).color(0xFFC4C4CC).weight(2.6f);
        row.addView(chev, new LinearLayout.LayoutParams(dp(12), dp(12)));

        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return t;
    }

    private void addSwitchRow(LinearLayout parent, String label, String sub, Switch sw) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(15), dp(10), dp(12), dp(10));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView lt = Ui.text(this, label, 14, Ui.INK, false);
        lt.setSingleLine(true);
        col.addView(lt, new LinearLayout.LayoutParams(-1, -2));
        TextView st = Ui.text(this, sub, 11, Ui.SUB, false);
        st.setSingleLine(true);
        st.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(st, lpTop(-1, -2, 3));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(sw);

        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private Switch switchFor(boolean checked) {
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setThumbTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{0xFFFFFFFF, 0xFFFFFFFF}));
        sw.setTrackTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{Ui.GREEN, 0xFFD9D9E0}));
        return sw;
    }

    private void addDivider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(1));
        p.leftMargin = dp(56);
        parent.addView(line, p);
    }

    private android.graphics.drawable.Drawable ripple() {
        return new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x12000000), null, null);
    }

    private void refresh() {
        if (accValue == null) return;
        refreshStatusOnly();
        updateProbeRows();
        updateNodeCard();
    }

    /** 只刷新状态行，不重建列表 —— 供 2 秒一次的定时器调用。 */
    private void refreshStatusOnly() {
        if (accValue == null) return;

        boolean acc = isAccessibilityEnabled();
        accValue.setText(acc ? "已开启" : "未开启");
        accValue.setTextColor(acc ? Ui.GREEN_DEEP : Ui.RED_DEEP);

        boolean alive = false;
        boolean granted = false;
        try {
            alive = Shizuku.pingBinder();
            granted = alive && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
        }
        if (granted) {
            shizukuValue.setText("已授权");
            shizukuValue.setTextColor(Ui.GREEN_DEEP);
        } else if (alive) {
            shizukuValue.setText("未授权");
            shizukuValue.setTextColor(Ui.AMBER_DEEP);
        } else {
            shizukuValue.setText("未运行");
            shizukuValue.setTextColor(Ui.RED_DEEP);
        }

        if (identValue != null) {
            String idt = ShizukuShell.identity();
            if (idt == null || idt.trim().isEmpty()) {
                identValue.setText("—");
                identValue.setTextColor(Ui.SUB);
            } else {
                String show = idt.trim();
                int i = show.indexOf("uid=");
                if (i >= 0) {
                    int j = show.indexOf(' ', i);
                    show = j > 0 ? show.substring(i, j) : show.substring(i);
                }
                if (show.length() > 22) show = show.substring(0, 22) + "…";
                identValue.setText(show);
                boolean ok = show.contains("uid=2000") || show.contains("uid=0");
                identValue.setTextColor(ok ? Ui.GREEN_DEEP : Ui.RED_DEEP);
            }
        }

        boolean vpn = NetworkGate.hasVpn(this);
        int guardedCount = Prefs.guarded(this).size();
        if (headSub != null) {
            headSub.setText((vpn ? "加速器已连接" : "加速器未连接")
                    + " · " + guardedCount + " 个应用受保护");
        }
        if (netValue != null) {
            netValue.setText(NetworkGate.describe(this));
            netValue.setTextColor(vpn ? Ui.GREEN_DEEP : Ui.AMBER_DEEP);
        }

        if (killValue != null) {
            String st = ShizukuShell.state();
            killValue.setText(st);
            boolean ok = st.contains("已连接") || st.contains("就绪");
            killValue.setTextColor(ok ? Ui.GREEN_DEEP : Ui.SUB);
        }

        if (guardCount != null) {
            guardCount.setText(Prefs.guarded(this).size() + " 个");
        }

        Set<String> dis = Prefs.disabled(this);
        if (restoreLink != null) {
            if (dis.isEmpty()) {
                restoreLink.setVisibility(View.GONE);
            } else {
                restoreLink.setVisibility(View.VISIBLE);
                restoreLink.setText("恢复被禁用的应用 · " + dis.size() + " 个");
            }
        }
        if (disabledBar != null) {
            if (dis.isEmpty()) {
                disabledBar.setVisibility(View.GONE);
            } else {
                disabledBar.setVisibility(View.VISIBLE);
                disabledText.setText(dis.size() + " 个应用被系统停用");
            }
        }

        refreshProbeStatus();
    }

    private void updateProbeRows() {
        if (levelValue != null) levelValue.setText(Prefs.levelName(this));
        if (targetsValue != null) {
            int n = 0;
            for (String line : Prefs.targets(this).split("[,\\n]")) {
                if (!line.trim().isEmpty()) n++;
            }
            targetsValue.setText(n + " 个");
        }
    }

    /**
     * 连通性主卡的驱动逻辑。
     *
     * 探测本身做了 4 秒节流：页面 2 秒刷一次状态，但不会每 2 秒发一次握手。
     */
    private void refreshProbeStatus() {
        if (netTitle == null) return;

        boolean vpn = NetworkGate.hasVpn(this);

        if (!vpn) {
            lastProbe = null;
            setNetState(STATE_BAD, Glyph.TRI, "网络不可用", "未检测到加速器 VPN");
            setRtt(-1);
            renderTargets(null);
            footBase = "等待加速器连接";
            setFoot();
            return;
        }
        if (!Prefs.probe(this)) {
            setNetState(STATE_OK, Glyph.CHECK, "网络已连接",
                    NetworkGate.describe(this) + " · 未启用校验");
            setRtt(-1);
            renderTargets(null);
            footBase = "仅检测 VPN 通道";
            setFoot();
            return;
        }

        if (lastProbe == null && !probing) {
            setNetState(STATE_WAIT, Glyph.DOT, "正在校验…", "等待第 1 个响应");
            setRtt(-1);
            footBase = "首次校验中";
            setFoot();
        } else {
            setFoot();
        }

        if (probing) return;
        long now = System.currentTimeMillis();
        if (now - lastProbeAt < 4000) return;
        lastProbeAt = now;
        runProbeAsync();
    }

    private String footBase = "实时监控中";

    private void setFoot() {
        if (netFootText == null) return;
        long age = lastProbeDoneAt <= 0 ? -1
                : (System.currentTimeMillis() - lastProbeDoneAt) / 1000;
        boolean timed = footBase.startsWith("实时监控") || footBase.startsWith("持续监控")
                || footBase.startsWith("按放行");
        if (timed && age >= 0 && age < 600) {
            netFootText.setText(footBase + " · " + (age <= 1 ? "刚刚" : age + " 秒前") + "更新");
        } else {
            netFootText.setText(footBase);
        }
    }

    private void runProbeAsync() {
        if (probing) return;
        probing = true;
        if (lastProbe == null) {
            setNetState(STATE_WAIT, Glyph.DOT, "正在校验…", "等待第 1 个响应");
            setRtt(-1);
            footBase = "首次校验中";
            setFoot();
        }
        new Thread(() -> {
            NetworkGate.Probe p;
            try {
                p = NetworkGate.probe(this);
            } catch (Throwable t) {
                p = null;
            }
            final NetworkGate.Probe r = p;
            runOnUiThread(() -> {
                probing = false;
                lastProbe = r;
                lastProbeDoneAt = System.currentTimeMillis();
                if (isFinishing() || netTitle == null) return;
                applyProbe(r);
            });
        }, "netgate-ui-probe").start();
    }

    private void applyProbe(NetworkGate.Probe p) {
        if (p == null) {
            setNetState(STATE_WAIT, Glyph.TRI, "检测失败", "请稍后再试");
            setRtt(-1);
            renderTargets(null);
            footBase = "上次校验失败";
            setFoot();
            return;
        }
        if (!p.vpnPresent) {
            setNetState(STATE_BAD, Glyph.TRI, "网络不可用", "未检测到加速器 VPN");
            setRtt(-1);
            renderTargets(p);
            footBase = "等待加速器连接";
            setFoot();
            return;
        }
        if (p.ok) {
            setNetState(STATE_OK, Glyph.CHECK, "网络可用",
                    p.reached + "/" + p.total + " 个目标通过");
            setRtt(p.rtt);
            renderTargets(p);
            footBase = "实时监控中";
        } else if (p.inconclusive) {
            setNetState(STATE_WAIT, Glyph.TRI, "无法验证",
                    p.detail == null ? "加速器为分应用模式" : p.detail);
            setRtt(-1);
            renderTargets(p);
            footBase = "按放行处理";
        } else {
            setNetState(STATE_BAD, Glyph.TRI, "网络不可用",
                    p.detail == null ? "检测目标无响应" : p.detail);
            setRtt(-1);
            renderTargets(p);
            footBase = "持续监控中 · 恢复后自动放行";
        }
        setFoot();
    }

    private void setNetState(int state, int glyph, String title, String sub) {
        int top;
        int bottom;
        int color;
        if (state == STATE_OK) {
            top = 0xFFEBFAEE;
            bottom = 0xFFDCF3E2;
            color = Ui.GREEN_DEEP;
        } else if (state == STATE_BAD) {
            top = 0xFFFFEFED;
            bottom = 0xFFFFE7E4;
            color = Ui.RED_DEEP;
        } else {
            top = 0xFFFFF6E9;
            bottom = 0xFFFFEEDB;
            color = Ui.AMBER_DEEP;
        }
        if (netIconWrap != null) {
            netIconWrap.setBackground(Ui.grad(top, bottom, 100, this));
        }
        if (netGlyph != null) {
            netGlyph.type(glyph).color(color);
        }
        netTitle.setText(title);
        netSub.setText(sub);
        netSub.setSingleLine(true);
        netSub.setEllipsize(TextUtils.TruncateAt.END);
        if (pulseDot != null) {
            pulseDot.setBackground(Ui.round(color, 100, this));
        }
    }

    private void setRtt(long rtt) {
        if (netRtt == null) return;
        if (rtt > 0) {
            netRtt.setText(String.valueOf(rtt));
            netRtt.setTextColor(Ui.INK);
            netRttUnit.setVisibility(View.VISIBLE);
        } else {
            netRtt.setText("—");
            netRtt.setTextColor(0xFFC7C7CE);
            netRttUnit.setVisibility(View.INVISIBLE);
        }
    }

    /**
     * 逐目标渲染。最多列 4 条：失败优先，其余折成一行数量。
     * 32 个目标全铺在首页会把卡片拉成一屏，没必要。
     */
    private void renderTargets(NetworkGate.Probe p) {
        if (targetList == null) return;
        targetList.removeAllViews();
        if (p == null || p.lines == null || p.lines.isEmpty()) return;

        List<String> fail = new ArrayList<>();
        List<String> okList = new ArrayList<>();
        for (String line : p.lines) {
            if (line.contains("·") && line.replaceAll(".*?(\\d+)\\s*ms$", "$1").matches("\\d+")) {
                okList.add(line);
            } else {
                fail.add(line);
            }
        }
        List<String> show = new ArrayList<>();
        for (String line : fail) {
            if (show.size() >= 4) break;
            show.add(line);
        }
        for (String line : okList) {
            if (show.size() >= 4) break;
            show.add(line);
        }

        for (String line : show) {
            targetList.addView(targetRow(line), new LinearLayout.LayoutParams(-1, -2));
        }
        int rest = p.lines.size() - show.size();
        if (rest > 0) {
            TextView more = Ui.text(this, "其余 " + rest + " 个目标 · 点「校验详情」查看",
                    11, Ui.SUB2, false);
            more.setPadding(dp(2), dp(6), 0, dp(2));
            targetList.addView(more);
        }
    }

    private View targetRow(String line) {
        String host = line;
        String rest = "";
        int i = line.indexOf("·");
        if (i > 0) {
            host = line.substring(0, i).trim();
            rest = line.substring(i + 1).trim();
        }
        boolean ok = rest.matches(".*?\\d+\\s*ms$");
        long rtt = -1;
        if (ok) {
            try {
                rtt = Long.parseLong(rest.replaceAll(".*?(\\d+)\\s*ms$", "$1"));
            } catch (Throwable ignored) {
            }
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(5), 0, dp(5));

        View dot = new View(this);
        dot.setBackground(Ui.round(ok ? Ui.GREEN : Ui.RED, 100, this));
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(dp(6), dp(6));
        dp2.rightMargin = dp(8);
        row.addView(dot, dp2);

        TextView h = Ui.text(this, host, 11.5f, 0xFF3A3A3C, false);
        h.setSingleLine(true);
        h.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row.addView(h, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView ms = Ui.text(this, ok ? rtt + " ms" : (rest.isEmpty() ? "失败" : rest),
                11, ok ? Ui.GREEN_DEEP : Ui.RED_DEEP, ok);
        ms.setSingleLine(true);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
        mp.leftMargin = dp(8);
        row.addView(ms, mp);
        return row;
    }

    private void requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                toast("Shizuku 未运行，请先在 Shizuku 里启动服务");
                return;
            }
            if (Shizuku.isPreV11()) {
                toast("Shizuku 版本过低，请升级");
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                toast("Shizuku 已授权");
                ShizukuShell.init(this);
                refresh();
                return;
            }
            Shizuku.requestPermission(REQ_SHIZUKU);
        } catch (Throwable t) {
            toast("调用 Shizuku 失败");
        }
    }

    private void runSelfCheck() {
        toast("正在检测 Shizuku 通道…");
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                sb.append("pingBinder：").append(Shizuku.pingBinder()).append('\n');
            } catch (Throwable t) {
                sb.append("pingBinder：异常\n");
            }
            try {
                sb.append("授权：").append(
                        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                                ? "已授予" : "被拒绝").append('\n');
            } catch (Throwable ignored) {
            }
            try {
                sb.append("getUid：").append(Shizuku.getUid()).append('\n');
            } catch (Throwable t) {
                sb.append("getUid：").append(t.getClass().getSimpleName()).append('\n');
            }
            try {
                sb.append("服务版本：").append(Shizuku.getVersion()).append('\n');
            } catch (Throwable ignored) {
            }

            sb.append("\n== 执行身份（关键）==\n");
            sb.append(ShizukuShell.execSync("id"));

            sb.append("\n\n== SELinux ==\n");
            sb.append(ShizukuShell.execSync("getenforce"));

            sb.append("\n\n== pm 可用性 ==\n");
            sb.append(ShizukuShell.execSync("pm list packages 2>&1 | head -3"));

            sb.append("\n\n== force-stop 权限（关键）==\n");
            // 用一个不存在的包名探测：有权限会报 Unknown package，
            // 没权限会报 SecurityException / Permission Denial。
            sb.append(ShizukuShell.execSync(
                    "am force-stop com.netgate.probe.nonexistent 2>&1; echo exit=$?"));

            final String out = sb.toString();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                TextView tv = new TextView(this);
                tv.setText(out);
                tv.setTextSize(10);
                tv.setTextColor(TEXT);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextIsSelectable(true);
                tv.setPadding(dp(14), dp(10), dp(14), dp(10));

                ScrollView sv = new ScrollView(this);
                sv.addView(tv, new FrameLayout.LayoutParams(-1, -2));
                sv.setPadding(dp(6), 0, dp(6), 0);

                dlg(new AlertDialog.Builder(this, R.style.NetGateDialog)
                        .setTitle("Shizuku 通道自检")
                        .setView(sv)
                        .setPositiveButton("关闭", null)
                        .setNegativeButton("复制", (d, w) -> {
                            try {
                                ClipboardManager cm =
                                        (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                if (cm != null) {
                                    cm.setPrimaryClip(ClipData.newPlainText("NetGate 自检", out));
                                    toast("已复制");
                                }
                            } catch (Throwable ignored) {
                            }
                        })
                        );
            });
        }, "netgate-selfcheck").start();
    }

    private boolean isAccessibilityEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (flat == null) return false;
        String target = getPackageName() + "/" + GuardService.class.getName();
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(flat);
        while (splitter.hasNext()) {
            if (splitter.next().equalsIgnoreCase(target)) return true;
        }
        return false;
    }

    private TextView statusRow(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));
        row.addView(text(label, 14, SUB, Typeface.NORMAL),
                new LinearLayout.LayoutParams(0, -2, 1f));
        TextView value = text("—", 14, TEXT, Typeface.BOLD);
        row.addView(value);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return value;
    }

    private void showDiag() {
        StringBuilder sb = new StringBuilder();
        boolean acc = isAccessibilityEnabled();
        Set<String> guarded = Prefs.guarded(this);

        long age = Diag.heartbeatAge();
        String hb;
        if (age < 0) {
            hb = "从未启动";
        } else if (age > 20) {
            hb = age + " 秒前（已停止响应）";
        } else {
            hb = age + " 秒前（正常）";
        }

        sb.append("版本：2.9 (21)\n");
        sb.append("结论：");
        if (!acc) {
            sb.append("无障碍服务没开，打开应用时没有任何东西在拦\n");
        } else if (guarded.isEmpty()) {
            sb.append("还没选择要限制的应用\n");
        } else if (age > 20) {
            sb.append("无障碍服务已停止响应，需要关掉再打开一次\n");
        } else {
            sb.append("基础条件正常，问题在判定环节\n");
        }
        sb.append("\n无障碍：").append(acc ? "已开启" : "未开启")
                .append(" · 心跳 ").append(hb).append('\n');

        boolean alive = false;
        boolean granted = false;
        try {
            alive = Shizuku.pingBinder();
            granted = alive && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
        }
        sb.append("Shizuku：").append(granted ? "已授权" : (alive ? "未授权" : "未运行"));
        try {
            if (alive) {
                sb.append(" · uid=").append(Shizuku.getUid())
                        .append(" · server v").append(Shizuku.getVersion());
            }
        } catch (Throwable ignored) {
        }
        sb.append('\n');
        sb.append("强停通道：").append(ShizukuShell.state());
        String le = ShizukuShell.lastError();
        if (!le.isEmpty()) sb.append("（").append(le).append("）");
        sb.append('\n');

        sb.append("受保护应用：").append(guarded.size()).append(" 个\n");
        for (String p : guarded) sb.append("    ").append(p).append('\n');

        sb.append("当前网络：").append(NetworkGate.describe(this)).append('\n');
        sb.append("VPN 网络：").append(NetworkGate.vpnNetwork(this) != null ? "找到" : "未找到")
                .append('\n');
        sb.append("探测开关：").append(Prefs.probe(this) ? "开" : "关")
                .append(" · ").append(Prefs.levelName(this))
                .append("（").append(Prefs.probeTimeout(this)).append("ms / 失败 ")
                .append(Prefs.probeFails(this)).append(" 次 / ")
                .append(Prefs.probeRhythm(this)).append("）\n");
        sb.append("检测目标：").append(Prefs.targets(this)).append('\n');

        String recent = Diag.lastForeground();
        sb.append("最近识别：").append(recent.isEmpty() ? "无" : recent).append('\n');
        sb.append("\n—— 最近前台切换（确认包名看这里）——\n")
                .append(Diag.foregroundDump());

        sb.append("\n—— 最近日志 ——\n").append(Diag.dump());

        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(11);
        tv.setTextColor(TEXT);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(14), dp(12), dp(14), dp(12));

        ScrollView sv = new ScrollView(this);
        sv.addView(tv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        sv.setPadding(dp(10), 0, dp(10), 0);

        dlg(new AlertDialog.Builder(this, R.style.NetGateDialog)
                .setTitle("诊断报告")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .setNeutralButton("测试强停", (d, w) -> testKill())
                .setNegativeButton("复制", (d, w) -> {
                    try {
                        ClipboardManager cm =
                                (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("NetGate 诊断", sb.toString()));
                            toast("已复制，可以直接粘贴发给我");
                        }
                    } catch (Throwable ignored) {
                    }
                })
                );
    }

    /**
     * 强停诊断：把「ps 能不能看到进程 / am 到底有没有执行 / 进程会不会复活」
     * 三件事一次测完，结果直接看文本，不用猜。
     */
    private void showKillDiag() {
        Set<String> guarded = Prefs.guarded(this);
        String def = guarded.isEmpty() ? "" : guarded.iterator().next();

        EditText et = new EditText(this);
        et.setText(def);
        et.setHint("com.example.app");
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        Ui.field(et, this);
        et.setSelection(et.getText().length());

        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(20), dp(6), dp(20), 0);
        wrap.addView(et, new FrameLayout.LayoutParams(-1, -2));

        dlg(new AlertDialog.Builder(this, R.style.NetGateDialog)
                .setTitle("强停诊断")
                .setMessage("会对该应用执行一次真实的强停，并记录每一步的结果。")
                .setView(wrap)
                .setPositiveButton("开始", (d, w) -> doKillDiag(et.getText().toString().trim()))
                .setNegativeButton("取消", null)
                );
    }

    private void doKillDiag(final String pkg) {
        if (pkg.isEmpty()) {
            toast("请输入包名");
            return;
        }
        toast("诊断中，约 6 秒…");

        new Thread(() -> {
            String safe = pkg.replaceAll("[^A-Za-z0-9._]", "");
            String pat = safe.replace(".", "\\.");
            String p = "(^|[[:space:]])" + pat + "(:[^[:space:]]*)?$";
            String PS = "/system/bin/ps -A 2>/dev/null";

            StringBuilder s = new StringBuilder();

            s.append("包名：").append(safe).append('\n');
            s.append("ps 可见进程总数：")
                    .append(ShizukuShell.execSync(PS + " | wc -l").trim())
                    .append("（正常应 300+，只有几十说明被 ROM 限制）\n\n");

            s.append("== 1. 强停前 ==\n");
            String pre = ShizukuShell.execSync(PS + " | grep -E '" + p + "'").trim();
            s.append(pre.isEmpty() ? "(没有在运行的进程)" : pre).append("\n\n");

            s.append("== 2. am force-stop ==\n");
            s.append(ShizukuShell.execSync(
                    "/system/bin/am force-stop " + safe + " 2>&1; echo \"exit=$?\"").trim())
                    .append("\n\n");

            s.append("== 3. 0.5 秒后 ==\n");
            s.append(ShizukuShell.execSync(
                    "sleep 0.5; " + PS + " | grep -E '" + p + "'; echo \"count=$("
                            + PS + " | grep -cE '" + p + "')\"").trim()).append("\n\n");

            s.append("== 4. cmd activity force-stop ==\n");
            s.append(ShizukuShell.execSync(
                    "/system/bin/cmd activity force-stop " + safe
                            + " 2>&1; echo \"exit=$?\"").trim()).append("\n\n");

            s.append("== 5. kill -9 ==\n");
            s.append(ShizukuShell.execSync(
                    "PIDS=$(" + PS + " | grep -E '" + p + "' | awk '{print $2}'); "
                            + "echo \"pids=$PIDS\"; "
                            + "if [ -n \"$PIDS\" ]; then /system/bin/kill -9 $PIDS 2>&1; "
                            + "echo \"kill_exit=$?\"; fi").trim()).append("\n\n");

            s.append("== 6. 2 秒后（看会不会被系统拉起来）==\n");
            s.append(ShizukuShell.execSync(
                    "sleep 2; " + PS + " | grep -E '" + p + "'; echo \"count=$("
                            + PS + " | grep -cE '" + p + "')\"").trim());

            final String out = s.toString();

            runOnUiThread(() -> {
                if (isFinishing()) return;
                TextView tv = new TextView(this);
                tv.setText(out);
                tv.setTextSize(10);
                tv.setTextColor(TEXT);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextIsSelectable(true);
                tv.setPadding(dp(14), dp(10), dp(14), dp(10));

                ScrollView sv = new ScrollView(this);
                sv.addView(tv, new FrameLayout.LayoutParams(-1, -2));
                sv.setPadding(dp(6), 0, dp(6), 0);

                dlg(new AlertDialog.Builder(this, R.style.NetGateDialog)
                        .setTitle("强停诊断结果")
                        .setView(sv)
                        .setPositiveButton("关闭", null)
                        .setNegativeButton("复制", (d, w) -> {
                            try {
                                ClipboardManager cm =
                                        (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                if (cm != null) {
                                    cm.setPrimaryClip(ClipData.newPlainText("NetGate 强停诊断", out));
                                    toast("已复制");
                                }
                            } catch (Throwable ignored) {
                            }
                        })
                        );
            });
        }, "netgate-killdiag").start();
    }

    /** 恢复因强停失败而被 pm disable-user 禁用的应用。 */
    private void showRestoreApps() {
        final Set<String> dis = Prefs.disabled(this);
        if (dis.isEmpty()) {
            toast("没有被禁用的应用");
            return;
        }

        StringBuilder sb = new StringBuilder("以下应用因强停不生效，已被系统级禁用：\n\n");
        for (String p : dis) {
            sb.append("· ").append(Prefs.label(this, p))
                    .append("（").append(p).append("）\n");
        }
        sb.append("\n恢复后会重新出现在桌面。VPN 正常时也会自动恢复。");

        dlg(new AlertDialog.Builder(this, R.style.NetGateDialog)
                .setTitle("恢复应用")
                .setMessage(sb.toString())
                .setPositiveButton("全部恢复", (d, w) -> {
                    toast("正在恢复…");
                    new Thread(() -> {
                        int n = 0;
                        for (String p : dis) {
                            String r = ShizukuShell.enablePackage(p);
                            if (r.contains("exit=0")) {
                                Prefs.unmarkDisabled(this, p);
                                n++;
                            }
                        }
                        final int cnt = n;
                        runOnUiThread(() -> toast("已恢复 " + cnt + " 个应用"));
                    }, "netgate-restore").start();
                })
                .setNegativeButton("取消", null)
                );
    }

    /**
     * 测试强停：自动挑一个当前真的在运行的受保护应用。
     * 之前固定取列表第一个，它没在跑的时候强停前后都是 0 进程，
     * 结果就是「显示已强停、其实什么都没验证」。
     */
    private void testKill() {
        final Set<String> guarded = Prefs.guarded(this);
        if (guarded.isEmpty()) {
            toast("先在「管理」里选一个应用");
            return;
        }
        toast("正在找一个正在运行的应用…");

        new Thread(() -> {
            String target = null;
            for (String p : guarded) {
                if (ShizukuShell.countProcess(p) > 0) {
                    target = p;
                    break;
                }
            }

            if (target == null) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    dlg(new AlertDialog.Builder(this, R.style.NetGateDialog)
                            .setTitle("没有可测的应用")
                            .setMessage("受保护的应用现在都没有在运行。\n\n"
                                    + "请先打开其中一个（不用切走），再回来点「测试强停」。")
                            .setPositiveButton("知道了", null)
                            );
                });
                return;
            }

            final String pkg = target;
            final String name = Prefs.label(this, pkg);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                toast("正在强停「" + name + "」，约 5 秒…");
            });

            ShizukuShell.forceStop(this, pkg, (ok, before, after, note) ->
                    runOnUiThread(() -> {
                        if (isFinishing()) return;
                        String msg = "应用：" + name + "\n" + pkg
                                + "\n\n强停前进程数：" + before
                                + "\n强停后进程数：" + after
                                + "\n\n结果：" + (ok ? "进程已清空" : "进程仍在")
                                + (note == null || note.isEmpty() ? "" : "\n\n" + note);
                        dlg(new AlertDialog.Builder(this, R.style.NetGateDialog)
                                .setTitle(ok ? "强停成功" : "强停失败")
                                .setMessage(msg)
                                .setPositiveButton("知道了", null)
                                );
                    }));
        }, "netgate-testkill").start();
    }

    private LinearLayout settingRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(13), 0, dp(13));
        row.addView(text(label, 14, TEXT, Typeface.NORMAL),
                new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = text(value, 13, SUB, Typeface.NORMAL);
        row.addView(v);
        return row;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(Ui.LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        return v;
    }

    private void showLevelDialog() {
        final String[] items = {
                "极速 · 1 秒超时，1 次失败即拦截 · 约 1.5 秒一次",
                "严格 · 1.5 秒超时，2 次失败即拦截 · 约 2.5 秒一次",
                "标准 · 2.5 秒超时，2 次失败即拦截 · 约 5 秒一次（±2 秒）",
                "稳妥 · 3 秒超时，2 次失败即拦截 · 约 8 秒一次（±2 秒）",
                "宽松 · 4 秒超时，3 次失败即拦截 · 约 12 秒一次（±2 秒）"
        };
        dlg(new AlertDialog.Builder(this, R.style.NetGateDialog)
                .setTitle("检测灵敏度")
                .setSingleChoiceItems(items, Prefs.level(this), (d, which) -> {
                    Prefs.setLevel(this, which);
                    d.dismiss();
                    updateProbeRows();
                })
                .setNegativeButton("取消", null)
                );
    }

    private void showTargetsDialog() {
        int pad = dp(20);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(pad, dp(6), pad, dp(4));

        EditText et = new EditText(this);
        et.setText(Prefs.targets(this));
        et.setHint("每行一个，或用逗号分隔，最多 " + NetworkGate.MAX_TARGETS + " 个");
        et.setTextSize(14);
        et.setTextColor(TEXT);
        et.setHintTextColor(Ui.SUB2);
        et.setSingleLine(false);
        et.setMinLines(4);
        et.setMaxLines(9);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_URI);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF7F7FA);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), STROKE);
        et.setBackground(bg);
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        wrap.addView(et, new LinearLayout.LayoutParams(-1, -2));

        TextView count = text("已配置 " + NetworkGate.summary(Prefs.targets(this))
                        + " · 上限 " + NetworkGate.MAX_TARGETS + " 个",
                11, SUB, Typeface.NORMAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = dp(8);
        wrap.addView(count, cp);

        TextView quick = text("点一下追加（不会覆盖已有内容）", 12, SUB, Typeface.BOLD);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(-1, -2);
        qp.topMargin = dp(16);
        wrap.addView(quick, qp);

        String[][] presets = {
                {"Google / YouTube（通用）", "www.google.com:443, www.youtube.com:443"},
                {"ChatGPT", "chatgpt.com:443, api.openai.com:443"},
                {"Claude", "claude.ai:443, api.anthropic.com:443"},
                {"Gemini", "gemini.google.com:443"},
                {"Steam", "store.steampowered.com:443, api.steampowered.com:443"},
                {"Discord", "discord.com:443, gateway.discord.gg:443"}
        };
        for (String[] preset : presets) {
            TextView chip = text("＋ " + preset[0], 13, GREEN, Typeface.NORMAL);
            chip.setPadding(0, dp(9), 0, dp(9));
            chip.setOnClickListener(v -> {
                String cur = et.getText().toString();
                StringBuilder sb = new StringBuilder(cur.trim());
                for (String item : preset[1].split(",")) {
                    String one = item.trim();
                    if (one.isEmpty() || cur.contains(one)) continue;
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(one);
                }
                et.setText(sb.toString());
                et.setSelection(et.getText().length());
                count.setText("已配置 " + NetworkGate.summary(sb.toString())
                        + " · 上限 " + NetworkGate.MAX_TARGETS + " 个");
            });
            wrap.addView(chip);
        }

        TextView note = text("填「只有挂加速器才连得上」的地址。\n"
                        + "别填 1.1.1.1、baidu.com 这类国内可直连的地址——\n"
                        + "它们不走加速器，永远显示可用，会让判定失真。\n"
                        + "判定规则：任意一个目标连上即放行，越多越容易误放行。",
                11, 0xFF5E6C7A, Typeface.NORMAL);
        note.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = dp(14);
        wrap.addView(note, np);

        ScrollView sv = new ScrollView(this);
        sv.addView(wrap);

        dlg(new AlertDialog.Builder(this, R.style.NetGateDialog)
                .setTitle("检测目标")
                .setView(sv)
                .setPositiveButton("保存", (d, w) -> {
                    String v = et.getText().toString().trim();
                    Prefs.setTargets(this, v.isEmpty() ? Prefs.DEFAULT_TARGETS : v);
                    updateProbeRows();
                    refresh();
                })
                .setNegativeButton("取消", null)
                );
    }

    private void runProbeDialog() {
        toast("正在测试连通性…");
        new Thread(() -> {
            NetworkGate.Probe p;
            try {
                p = NetworkGate.probe(this);
            } catch (Throwable t) {
                p = null;
            }
            final NetworkGate.Probe r = p;
            runOnUiThread(() -> {
                if (isFinishing()) return;

                final String verdict;
                final String sub;
                final int tone;
                if (r == null) {
                    verdict = "检测失败";
                    sub = "本次探测没有拿到结果，请稍后再试";
                    tone = Ui.SUB;
                } else if (r.ok && r.inconclusive) {
                    verdict = "无法验证";
                    sub = r.detail == null ? "加速器为分应用模式" : r.detail;
                    tone = Ui.AMBER_DEEP;
                } else if (r.ok) {
                    verdict = "连通正常";
                    sub = r.detail == null ? "已收到服务器响应" : r.detail;
                    tone = Ui.GREEN_DEEP;
                } else {
                    verdict = "网络不可用";
                    sub = r.detail == null ? "检测目标无响应" : r.detail;
                    tone = Ui.RED_DEEP;
                }

                LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(dp(22), dp(4), dp(22), dp(6));

                // 结论条
                LinearLayout head = new LinearLayout(this);
                head.setOrientation(LinearLayout.VERTICAL);
                head.setBackground(Ui.round(0xFFF7F7FA, 14, this));
                head.setPadding(dp(14), dp(12), dp(14), dp(12));
                TextView v = Ui.text(this, verdict, 16, tone, true);
                head.addView(v);
                TextView vs = Ui.text(this, sub, 11, Ui.SUB, false);
                vs.setLineSpacing(dp(3), 1f);
                head.addView(vs, lpTop(-1, -2, 5));
                box.addView(head, new LinearLayout.LayoutParams(-1, -2));

                // 目标明细
                if (r != null && r.lines != null && !r.lines.isEmpty()) {
                    TextView cap = Ui.text(this, "检测目标", 11, Ui.SUB2, true);
                    box.addView(cap, lpTop(-1, -2, 14));
                    for (String line : r.lines) {
                        box.addView(probeLine(line), lpTop(-1, -2, 6));
                    }
                }

                // 说明
                TextView note = Ui.text(this,
                        "探测路径：绑定 VPN 网络 → TCP 连接 → TLS 握手 → 请求 HTTP。\n"
                                + "只有收到服务器真实响应才算可用；加速器挂掉时 tun 会在本地代答 TCP，"
                                + "单看 TCP 会出现 1 ms 假连通，必须走到 TLS 才能识破。\n"
                                + "显示「无法验证」说明加速器只代理了游戏，本应用没有资格走它的隧道，"
                                + "此时放行而不是误杀。",
                        11, 0xFF7C7C85, false);
                note.setLineSpacing(dp(4), 1f);
                box.addView(note, lpTop(-1, -2, 16));

                ScrollView sv = new ScrollView(this);
                sv.addView(box, new FrameLayout.LayoutParams(-1, -2));

                final String clip = r == null ? "检测失败" : probeDump(r);
                dlg(new AlertDialog.Builder(this, R.style.NetGateDialog)
                        .setTitle("连通性校验")
                        .setView(sv)
                        .setPositiveButton("关闭", null)
                        .setNeutralButton("复制", (d, w) -> {
                            try {
                                ClipboardManager cm =
                                        (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                if (cm != null) {
                                    cm.setPrimaryClip(ClipData.newPlainText("NetGate 探测", clip));
                                    toast("已复制");
                                }
                            } catch (Throwable ignored) {
                            }
                        })
                );
            });
        }, "netgate-ui-test").start();
    }

    /** 一行「目标 + 结果」，和主界面里的目标行同一种读法。 */
    private View probeLine(String line) {
        String host = line;
        String rest = "";
        int i = line.indexOf("·");
        if (i > 0) {
            host = line.substring(0, i).trim();
            rest = line.substring(i + 1).trim();
        }
        boolean ok = rest.matches(".*?\\d+\\s*ms$");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), 0, dp(2), 0);

        View dot = new View(this);
        dot.setBackground(Ui.round(ok ? Ui.GREEN : Ui.RED, 100, this));
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(dp(6), dp(6));
        dp2.rightMargin = dp(9);
        row.addView(dot, dp2);

        TextView h = Ui.text(this, host, 12, 0xFF3A3A3C, false);
        h.setSingleLine(true);
        h.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row.addView(h, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView st = Ui.text(this, rest.isEmpty() ? "失败" : rest, 11,
                ok ? Ui.GREEN_DEEP : Ui.RED_DEEP, false);
        st.setSingleLine(true);
        st.setGravity(Gravity.END);
        LinearLayout.LayoutParams sp2 = new LinearLayout.LayoutParams(-2, -2);
        sp2.leftMargin = dp(10);
        row.addView(st, sp2);
        return row;
    }

    private String probeDump(NetworkGate.Probe r) {
        StringBuilder sb = new StringBuilder();
        sb.append("VPN 通道：").append(r.vpnPresent ? "存在" : "不存在").append('\n');
        sb.append("结论：").append(r.ok ? (r.inconclusive ? "无法验证" : "可用") : "不可用")
                .append("（").append(r.detail).append("）\n");
        for (String line : r.lines) sb.append("· ").append(line).append('\n');
        return sb.toString();
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(18));
        g.setStroke(dp(1), STROKE);
        c.setBackground(g);
        c.setPadding(dp(18), dp(15), dp(18), dp(16));
        return c;
    }

    private TextView pill(String label) {
        TextView t = text(label, 14, TEXT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFF7F7FA);
        g.setCornerRadius(dp(100));
        g.setStroke(dp(1), Ui.LINE);
        t.setBackground(g);
        return t;
    }

    private TextView text(String s, float sp, int color, int style) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", style));
        return t;
    }

    private LinearLayout.LayoutParams lpTop(int w, int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                w == -1 ? ViewGroup.LayoutParams.MATCH_PARENT : w,
                h == -2 ? ViewGroup.LayoutParams.WRAP_CONTENT : h);
        p.topMargin = dp(top);
        return p;
    }

    /** 统一外观：白底圆角 + 蓝/灰文字按钮。 */
    private AlertDialog dlg(AlertDialog.Builder b) {
        return Ui.show(b, this);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    /** Android 13 起前台服务的通知要单独授权，没给的话通知栏是空的。 */
    private void ensureNotifPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        try {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == PackageManager.PERMISSION_GRANTED) return;
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        } catch (Throwable ignored) {
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
