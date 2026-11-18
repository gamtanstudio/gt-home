package com.gtman.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final int BG_TOP = Color.parseColor("#141821");
    private static final int BG_BOTTOM = Color.parseColor("#05070B");
    private static final int TILE = Color.parseColor("#1F2430");
    private static final int TILE_EMPTY = Color.parseColor("#151922");
    private static final int ACCENT = Color.parseColor("#4F8BFF");
    private static final int TEXT_DIM = Color.parseColor("#9AA3B5");

    private static final String[] LAYOUT_NAMES = {"2줄 × 3칸", "2줄 × 4칸", "2줄 × 5칸", "3줄 × 4칸", "3줄 × 5칸"};
    private static final int[][] LAYOUTS = {{2, 3}, {2, 4}, {2, 5}, {3, 4}, {3, 5}};

    // 처음 실행할 때 설치돼 있으면 자동으로 채워 넣을 앱들
    private static final String[] DEFAULTS = {
            "com.skt.tmap.ku",
            "com.locnall.KimGiSaNavi",
            "app.revanced.android.youtube",
            "com.gtman.browser/com.gtman.browser.TubeActivity",
            "com.gtman.browser/com.gtman.browser.MusicActivity",
            "com.gtman.browser/com.gtman.browser.MainActivity",
            "org.jw.jwlibrary.mobile",
            "com.nhn.android.nmap",
            "com.google.android.youtube",
            "com.android.settings",
    };

    private FrameLayout root;
    private LinearLayout grid;
    private FrameLayout drawer;
    private final Map<String, Drawable> iconCache = new HashMap<>();

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("gthome", MODE_PRIVATE);
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG_TOP);
        getWindow().setNavigationBarColor(BG_BOTTOM);

        root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{BG_TOP, BG_BOTTOM});
        root.setBackground(bg);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(28), dp(16), dp(28), dp(20));
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        content.addView(buildHeader(), new LinearLayout.LayoutParams(-1, -2));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, 0, 1f);
        glp.topMargin = dp(14);
        content.addView(grid, glp);

        setContentView(root);

        if (!prefs().getBoolean("init", false)) fillDefaults();
    }

    @Override
    protected void onResume() {
        super.onResume();
        iconCache.clear();
        buildGrid();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closeDrawer();
    }

    @Override
    public void onBackPressed() {
        if (drawer != null) closeDrawer();
        // 홈 화면이라 뒤로가기는 아무것도 안 함
    }

    // ---------- 위쪽: 시계·날짜·버튼 ----------

    private View buildHeader() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);

        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        time.setTextColor(Color.WHITE);
        time.setTextSize(56);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setIncludeFontPadding(false);
        clockBox.addView(time);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("M월 d일 EEEE");
        date.setFormat12Hour("M월 d일 EEEE");
        date.setTextColor(TEXT_DIM);
        date.setTextSize(18);
        clockBox.addView(date);

        h.addView(clockBox, new LinearLayout.LayoutParams(0, -2, 1f));

        h.addView(pill("전체 앱", v -> openDrawer(-1)));
        View gap = new View(this);
        h.addView(gap, new LinearLayout.LayoutParams(dp(12), 1));
        h.addView(pill("⚙", v -> showSettings()));
        return h;
    }

    private TextView pill(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(18);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(22), dp(12), dp(22), dp(12));
        t.setBackground(rounded(TILE, dp(28)));
        t.setOnClickListener(l);
        return t;
    }

    private Drawable rounded(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(Color.parseColor("#334F8BFF")), g, null);
    }

    // ---------- 큰 버튼 칸 ----------

    private int rows() { return prefs().getInt("rows", 2); }
    private int cols() { return prefs().getInt("cols", 4); }

    private void buildGrid() {
        grid.removeAllViews();
        int r = rows(), c = cols();
        PackageManager pm = getPackageManager();
        for (int i = 0; i < r; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, 0, 1f);
            if (i > 0) rlp.topMargin = dp(14);
            grid.addView(row, rlp);
            for (int j = 0; j < c; j++) {
                int slot = i * c + j;
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -1, 1f);
                if (j > 0) tlp.leftMargin = dp(14);
                row.addView(buildTile(slot, pm), tlp);
            }
        }
    }

    private View buildTile(int slot, PackageManager pm) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(8), dp(8), dp(8));

        ComponentName cn = slotComponent(slot);
        ActivityInfo info = null;
        if (cn != null) {
            try { info = pm.getActivityInfo(cn, 0); } catch (Exception e) { info = null; }
        }

        if (info == null) {
            t.setBackground(rounded(TILE_EMPTY, dp(26)));
            TextView plus = new TextView(this);
            plus.setText("+");
            plus.setTextSize(44);
            plus.setTextColor(Color.parseColor("#3A4256"));
            plus.setGravity(Gravity.CENTER);
            t.addView(plus);
            t.setOnClickListener(v -> openDrawer(slot));
            t.setOnLongClickListener(v -> { openDrawer(slot); return true; });
            return t;
        }

        t.setBackground(rounded(TILE, dp(26)));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(info.loadIcon(pm));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int size = rows() >= 3 ? dp(64) : dp(88);
        t.addView(icon, new LinearLayout.LayoutParams(size, size));

        TextView label = new TextView(this);
        label.setText(info.loadLabel(pm));
        label.setTextColor(Color.WHITE);
        label.setTextSize(rows() >= 3 ? 16 : 20);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.topMargin = dp(10);
        t.addView(label, llp);

        final ComponentName target = cn;
        t.setOnClickListener(v -> launch(target));
        t.setOnLongClickListener(v -> { tileMenu(slot); return true; });
        return t;
    }

    private void tileMenu(int slot) {
        new AlertDialog.Builder(this)
                .setItems(new String[]{"다른 앱으로 바꾸기", "이 칸 비우기"}, (d, which) -> {
                    if (which == 0) openDrawer(slot);
                    else { setSlot(slot, null); buildGrid(); }
                })
                .show();
    }

    private ComponentName slotComponent(int slot) {
        String s = prefs().getString("slot_" + slot, null);
        return s == null ? null : ComponentName.unflattenFromString(s);
    }

    private void setSlot(int slot, ComponentName cn) {
        SharedPreferences.Editor e = prefs().edit();
        if (cn == null) e.remove("slot_" + slot);
        else e.putString("slot_" + slot, cn.flattenToString());
        e.apply();
    }

    private void launch(ComponentName cn) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(cn);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "앱을 열 수 없어요", Toast.LENGTH_SHORT).show();
        }
    }

    private void fillDefaults() {
        PackageManager pm = getPackageManager();
        int slot = 0;
        int max = rows() * cols();
        for (String d : DEFAULTS) {
            if (slot >= max) break;
            ComponentName cn = null;
            if (d.contains("/")) {
                ComponentName c = ComponentName.unflattenFromString(d);
                try { pm.getActivityInfo(c, 0); cn = c; } catch (Exception ignored) { }
            } else {
                Intent li = pm.getLaunchIntentForPackage(d);
                if (li != null) cn = li.getComponent();
            }
            if (cn != null) setSlot(slot++, cn);
        }
        prefs().edit().putBoolean("init", true).apply();
    }

    // ---------- 칸 수 설정 ----------

    private void showSettings() {
        int cur = -1;
        for (int i = 0; i < LAYOUTS.length; i++)
            if (LAYOUTS[i][0] == rows() && LAYOUTS[i][1] == cols()) cur = i;
        new AlertDialog.Builder(this)
                .setTitle("한 화면에 몇 칸?")
                .setSingleChoiceItems(LAYOUT_NAMES, cur, (d, which) -> {
                    prefs().edit().putInt("rows", LAYOUTS[which][0]).putInt("cols", LAYOUTS[which][1]).apply();
                    buildGrid();
                    d.dismiss();
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    // ---------- 전체 앱 서랍 ----------

    private List<ResolveInfo> loadApps() {
        PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> all = pm.queryIntentActivities(i, 0);
        List<ResolveInfo> out = new ArrayList<>();
        Map<ResolveInfo, String> labels = new HashMap<>();
        for (ResolveInfo r : all) {
            if (getPackageName().equals(r.activityInfo.packageName)) continue;
            labels.put(r, String.valueOf(r.loadLabel(pm)));
            out.add(r);
        }
        Collator col = Collator.getInstance(Locale.KOREAN);
        Collections.sort(out, (a, b) -> col.compare(labels.get(a), labels.get(b)));
        return out;
    }

    /** pickSlot < 0 이면 그냥 앱 실행, 0 이상이면 그 칸에 넣을 앱 고르기 */
    private void openDrawer(int pickSlot) {
        closeDrawer();
        drawer = new FrameLayout(this);
        drawer.setBackgroundColor(Color.parseColor("#F2080A10"));
        drawer.setClickable(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(28), dp(16), dp(28), dp(16));
        drawer.addView(box, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(pickSlot >= 0 ? "이 칸에 넣을 앱을 고르세요" : "전체 앱");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(pill("닫기", v -> closeDrawer()));
        box.addView(top, new LinearLayout.LayoutParams(-1, -2));

        final List<ResolveInfo> apps = loadApps();
        final PackageManager pm = getPackageManager();

        GridView gv = new GridView(this);
        gv.setColumnWidth(dp(130));
        gv.setNumColumns(GridView.AUTO_FIT);
        gv.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        gv.setVerticalSpacing(dp(18));
        gv.setHorizontalSpacing(dp(8));
        gv.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        gv.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return apps.size(); }
            @Override public Object getItem(int p) { return apps.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int p, View cv, ViewGroup parent) {
                LinearLayout cell;
                ImageView iv;
                TextView tv;
                if (cv == null) {
                    cell = new LinearLayout(MainActivity.this);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    cell.setGravity(Gravity.CENTER_HORIZONTAL);
                    cell.setPadding(dp(4), dp(8), dp(4), dp(8));
                    iv = new ImageView(MainActivity.this);
                    cell.addView(iv, new LinearLayout.LayoutParams(dp(64), dp(64)));
                    tv = new TextView(MainActivity.this);
                    tv.setTextColor(Color.WHITE);
                    tv.setTextSize(15);
                    tv.setGravity(Gravity.CENTER);
                    tv.setMaxLines(2);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                    lp.topMargin = dp(6);
                    cell.addView(tv, lp);
                } else {
                    cell = (LinearLayout) cv;
                    iv = (ImageView) cell.getChildAt(0);
                    tv = (TextView) cell.getChildAt(1);
                }
                ResolveInfo r = apps.get(p);
                String key = r.activityInfo.packageName + "/" + r.activityInfo.name;
                Drawable d = iconCache.get(key);
                if (d == null) { d = r.loadIcon(pm); iconCache.put(key, d); }
                iv.setImageDrawable(d);
                tv.setText(r.loadLabel(pm));
                return cell;
            }
        });
        gv.setOnItemClickListener((parent, view, pos, id) -> {
            ResolveInfo r = apps.get(pos);
            ComponentName cn = new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
            if (pickSlot >= 0) {
                setSlot(pickSlot, cn);
                closeDrawer();
                buildGrid();
            } else {
                closeDrawer();
                launch(cn);
            }
        });
        LinearLayout.LayoutParams gvlp = new LinearLayout.LayoutParams(-1, 0, 1f);
        gvlp.topMargin = dp(14);
        box.addView(gv, gvlp);

        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
    }

    private void closeDrawer() {
        if (drawer != null) {
            root.removeView(drawer);
            drawer = null;
        }
    }
}
