package com.d.netgate;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class AppPickerActivity extends Activity {

    private final List<AppEntry> all = new ArrayList<>();
    private final List<AppEntry> shown = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();

    private AppAdapter adapter;
    private TextView countView;
    /** true = 选「限定节点」的应用（只列已受保护的），false = 选受保护应用。 */
    private boolean nodeMode;

    static class AppEntry {
        String pkg;
        String label;
        Drawable icon;
        /** 桌面 / 输入法 / 拨号 / SystemUI 之类：可以显示，但绝不允许勾选。 */
        boolean critical;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        SysApps.refresh();
        nodeMode = "node".equals(getIntent().getStringExtra("mode"));
        if (nodeMode) {
            // 只列已受保护的应用，且历史脏数据在这里收敛一次
            selected.addAll(Prefs.nodeGuarded(this));
            selected.retainAll(Prefs.guarded(this));
        } else {
            selected.addAll(Prefs.guarded(this));
            // 历史版本可能已经把桌面之类写进了名单，这里顺手清掉
            boolean dirty = selected.removeIf(p -> SysApps.isCritical(this, p));
            if (dirty) Prefs.setGuarded(this, selected);
        }
        buildUi();
        loadApps();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(18), dp(24), dp(18), 0);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout backWrap = new FrameLayout(this);
        Glyph back = new Glyph(this);
        back.type(Glyph.CHEVRON).color(Ui.INK).weight(2.4f);
        back.setRotation(180f);
        backWrap.addView(back, new FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER));
        backWrap.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(34), dp(34));
        bp.rightMargin = dp(4);
        topRow.addView(backWrap, bp);

        TextView title = Ui.text(this, nodeMode ? "节点锁应用" : "选择应用",
                24, Ui.INK, true);
        topRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        countView = Ui.text(this, "", 12, Ui.SUB, false);
        countView.setBackground(Ui.round(0xFFE9E9EF, 100, this));
        countView.setPadding(dp(10), dp(5), dp(10), dp(5));
        topRow.addView(countView);
        header.addView(topRow);

        TextView tip = Ui.text(this, nodeMode
                        ? "勾选的应用只能在锁定的节点下打开 · 其余应用不受限制"
                        : "勾选需要限制的应用 · 置灰的是系统关键应用，不可选",
                12, Ui.SUB, false);
        header.addView(tip, topMargin(7));

        EditText search = new EditText(this);
        search.setHint("搜索应用");
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(Ui.INK);
        search.setHintTextColor(Ui.SUB2);
        search.setPadding(dp(14), dp(11), dp(14), dp(11));
        search.setBackground(Ui.round(0xFFFFFFFF, 13, this));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                filter(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        header.addView(search, topMarginWide(14));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0x00000000));
        list.setBackgroundColor(0x00000000);
        list.setCacheColorHint(0);
        list.setPadding(dp(18), dp(16), dp(18), dp(28));
        list.setClipToPadding(false);
        list.setVerticalScrollBarEnabled(false);
        adapter = new AppAdapter();
        list.setAdapter(adapter);

        TextView empty = Ui.text(this, nodeMode
                        ? "还没有受保护应用\n先去「受保护应用」里选几个"
                        : "没有匹配的应用",
                13, Ui.SUB, false);
        empty.setGravity(Gravity.CENTER);
        empty.setLineSpacing(dp(4), 1f);

        FrameLayout listWrap = new FrameLayout(this);
        listWrap.addView(list, new FrameLayout.LayoutParams(-1, -1));
        listWrap.addView(empty, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        list.setEmptyView(empty);
        root.addView(listWrap, new LinearLayout.LayoutParams(-1, 0, 1f));

        updateCount();
    }

    private void loadApps() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list;
            try {
                list = pm.queryIntentActivities(main, 0);
            } catch (Throwable t) {
                return;
            }
            Set<String> seen = new HashSet<>();
            List<AppEntry> tmp = new ArrayList<>();
            for (ResolveInfo ri : list) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || seen.contains(pkg)) continue;
                seen.add(pkg);
                if (nodeMode && !Prefs.guarded(AppPickerActivity.this).contains(pkg)) continue;
                AppEntry e = new AppEntry();
                e.pkg = pkg;
                try {
                    e.label = ri.loadLabel(pm).toString();
                } catch (Throwable t) {
                    e.label = pkg;
                }
                try {
                    e.icon = ri.loadIcon(pm);
                } catch (Throwable ignored) {
                }
                e.critical = SysApps.isCritical(AppPickerActivity.this, pkg);
                tmp.add(e);
            }
            // 关键应用沉到列表末尾：既不挡路，也让人看得见它为什么不给选
            Collections.sort(tmp, (a, b) -> {
                if (a.critical != b.critical) return a.critical ? 1 : -1;
                return a.label.compareToIgnoreCase(b.label);
            });
            runOnUiThread(() -> {
                all.clear();
                all.addAll(tmp);
                filter("");
            });
        }).start();
    }

    private void filter(String q) {
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (AppEntry e : all) {
            if (query.isEmpty()
                    || e.label.toLowerCase(Locale.ROOT).contains(query)
                    || e.pkg.toLowerCase(Locale.ROOT).contains(query)) {
                shown.add(e);
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void updateCount() {
        if (countView != null) {
            countView.setText((nodeMode ? "已绑 " : "已选 ") + selected.size() + " 个");
        }
    }

    /** 分组列表：首行圆上角、末行圆下角，中间是白底 + 左侧缩进的分隔线。 */
    private Drawable rowBg(int position, int count) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Ui.CARD);
        float r = dp(16);
        float[] radii;
        if (count <= 1) {
            radii = new float[]{r, r, r, r, r, r, r, r};
        } else if (position == 0) {
            radii = new float[]{r, r, r, r, 0, 0, 0, 0};
        } else if (position == count - 1) {
            radii = new float[]{0, 0, 0, 0, r, r, r, r};
        } else {
            radii = new float[]{0, 0, 0, 0, 0, 0, 0, 0};
        }
        g.setCornerRadii(radii);
        return g;
    }

    private class AppAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout wrap;
            if (convertView == null) {
                wrap = new LinearLayout(AppPickerActivity.this);
                wrap.setOrientation(LinearLayout.VERTICAL);

                LinearLayout row = new LinearLayout(AppPickerActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(14), dp(11), dp(12), dp(11));

                ImageView icon = new ImageView(AppPickerActivity.this);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(40), dp(40));
                ip.rightMargin = dp(13);
                row.addView(icon, ip);

                LinearLayout col = new LinearLayout(AppPickerActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                TextView label = Ui.text(AppPickerActivity.this, "", 15, Ui.INK, false);
                label.setSingleLine(true);
                TextView pkg = Ui.text(AppPickerActivity.this, "", 11, Ui.SUB2, false);
                pkg.setSingleLine(true);
                col.addView(label);
                col.addView(pkg, topMargin(2));
                row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

                CheckBox cb = new CheckBox(AppPickerActivity.this);
                cb.setClickable(true);
                cb.setButtonTintList(new ColorStateList(
                        new int[][]{{android.R.attr.state_checked}, {}},
                        new int[]{Ui.GREEN_DEEP, 0xFFC7C7CE}));
                row.addView(cb);

                View divider = new View(AppPickerActivity.this);
                divider.setBackgroundColor(Ui.LINE);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dp(1));
                dlp.leftMargin = dp(67);
                wrap.addView(row, new LinearLayout.LayoutParams(-1, -2));
                wrap.addView(divider, dlp);

                Holder h = new Holder(icon, label, pkg, cb, divider);
                wrap.setTag(h);
            } else {
                wrap = (LinearLayout) convertView;
            }

            Holder h = (Holder) wrap.getTag();
            final AppEntry e = shown.get(position);
            h.label.setText(e.label);
            h.pkg.setText(e.critical ? "系统关键应用 · 不可选" : e.pkg);
            if (e.icon != null) h.icon.setImageDrawable(e.icon);
            else h.icon.setImageDrawable(null);
            h.divider.setVisibility(position == shown.size() - 1 ? View.GONE : View.VISIBLE);
            wrap.setBackground(rowBg(position, shown.size()));
            wrap.setAlpha(e.critical ? 0.42f : 1f);

            h.cb.setOnCheckedChangeListener(null);
            h.cb.setChecked(!e.critical && selected.contains(e.pkg));
            h.cb.setEnabled(!e.critical);
            h.cb.setClickable(!e.critical);
            h.cb.setOnCheckedChangeListener((btn, checked) -> {
                if (e.critical) return;
                if (checked) selected.add(e.pkg);
                else selected.remove(e.pkg);
                if (nodeMode) Prefs.setNodeGuarded(AppPickerActivity.this, selected);
                else Prefs.setGuarded(AppPickerActivity.this, selected);
                updateCount();
            });

            wrap.setOnClickListener(v -> {
                if (e.critical) {
                    Toast.makeText(AppPickerActivity.this,
                            "桌面、输入法、拨号等系统关键应用不能拦截",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                h.cb.toggle();
            });
            return wrap;
        }
    }

    static class Holder {
        ImageView icon;
        TextView label;
        TextView pkg;
        CheckBox cb;
        View divider;

        Holder(ImageView icon, TextView label, TextView pkg, CheckBox cb, View divider) {
            this.icon = icon;
            this.label = label;
            this.pkg = pkg;
            this.cb = cb;
            this.divider = divider;
        }
    }

    private LinearLayout.LayoutParams topMargin(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.topMargin = dp(top);
        return p;
    }

    private LinearLayout.LayoutParams topMarginWide(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(top);
        return p;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
