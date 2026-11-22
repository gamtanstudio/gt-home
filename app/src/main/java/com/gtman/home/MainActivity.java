package com.gtman.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 싼슬라 홈. 홈 버튼을 누르면 네비를 전체 화면으로 띄우고(패널은 PanelService가 그 위에 띄움),
 * 설정·앱 고르기 화면도 여기서 보여 준다.
 */
public class MainActivity extends Activity {

    static boolean homeMode = false;
    static boolean otherScreen = false; // 설정·앱 고르기 화면이 떠 있음

    private static final int BG = Color.parseColor("#07090D");
    private static final int CARD = Color.parseColor("#161B25");
    private static final int ACCENT = Color.parseColor("#4F8BFF");
    private static final int DIM = Color.parseColor("#9AA3B5");

    private static final String[] APP_DEFAULTS = {
            "app.revanced.android.youtube", "com.opera.browser", "org.jw.jwlibrary.mobile",
            "org.videolan.vlc", "com.vivaldi.browser", "com.android.settings",
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private static final String APK_URL = "https://github.com/gamtanstudio/gt-home/releases/download/latest/gt-home.apk";

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private SharedPreferences prefs() { return getSharedPreferences("gthome", MODE_PRIVATE); }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (Build.VERSION.SDK_INT >= 28) {
            try { org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L"); } catch (Throwable ignored) { }
        }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (!prefs().getBoolean("init3", false)) {
            SharedPreferences.Editor e = prefs().edit();
            int slot = 0;
            for (String d : APP_DEFAULTS) {
                if (slot >= 6) break;
                ComponentName cn = PanelService.resolve(this, d);
                if (cn != null) e.putString("slot_" + slot++, cn.flattenToString());
            }
            e.putBoolean("init3", true).apply();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String mode = getIntent().getStringExtra("mode");
        int slot = getIntent().getIntExtra("slot", -1);
        getIntent().removeExtra("mode");

        if (PanelService.inst == null) { homeMode = false; showSetup(); return; }
        if ("settings".equals(mode)) { homeMode = false; showSettings(); return; }
        if ("apps".equals(mode)) { homeMode = false; showApps(-1); return; }
        if ("pick".equals(mode)) { homeMode = false; showApps(slot); return; }
        if ("nav".equals(mode)) { homeMode = false; showApps(-2); return; }
        if ("music".equals(mode)) { homeMode = false; showApps(-3); return; }
        showHome();
    }

    @Override
    protected void onPause() {
        super.onPause();
        homeMode = false;
    }

    @Override
    public void onBackPressed() {
        if (!homeMode) showHome();
    }

    // ======================================================================
    // 홈: 네비를 전체 화면으로
    // ======================================================================

    private void showHome() {
        homeMode = true;
        otherScreen = false;
        askLocationOnce();
        LinearLayout box = page();
        box.setGravity(Gravity.CENTER);
        ComponentName nav = PanelService.navApp(this);
        if (nav == null) {
            box.addView(title("왼쪽에 띄울 네비를 골라 주세요"));
            box.addView(button("네비 고르기", v -> showApps(-2)));
            setContentView(box);
            if (PanelService.inst != null) PanelService.inst.showPanel();
            return;
        }
        if (PanelService.splitLayout(this) && PanelService.inst != null) {   // 반반: 왼쪽 네비, 오른쪽 싼슬라
            box.addView(button("반반 열기", v -> PanelService.inst.goSplitHome()));
            setContentView(box);
            if (prefs().getBoolean("auto_nav", true)) ui.postDelayed(() -> {
                if (homeMode && PanelService.inst != null) PanelService.inst.goSplitHome();
            }, 250);
            return;
        }
        box.addView(button("네비 열기", v -> PanelService.launchFull(this, nav)));
        setContentView(box);
        int wait = 250;
        if (PanelService.inst != null && PanelService.inst.inSplit()) {   // 반반이면 먼저 풀고
            PanelService.inst.exitSplit();
            wait = 800;
        }
        if (PanelService.inst != null) PanelService.inst.showPanel();
        if (prefs().getBoolean("auto_nav", true)) ui.postDelayed(() -> {
            if (homeMode) PanelService.launchFull(this, nav);
        }, wait);
    }

    // ======================================================================
    // 처음 한 번: 접근성 켜기
    // ======================================================================

    private void showSetup() {
        otherScreen = true;
        LinearLayout box = page();
        box.setGravity(Gravity.CENTER);
        box.addView(title("싼슬라 패널 켜기"));
        TextView t = new TextView(this);
        t.setText("설정 → 접근성 → 싼슬라 패널 → 사용");
        t.setTextColor(DIM);
        t.setTextSize(18);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = dp(10);
        box.addView(t, tl);
        box.addView(button("접근성 열기", v -> open(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        setContentView(box);
    }

    // ======================================================================
    // 설정
    // ======================================================================

    private void showSettings() {
        otherScreen = true;
        LinearLayout box = page();
        box.addView(title("싼슬라 설정"));
        ComponentName nav = PanelService.navApp(this);
        String navName = "없음";
        if (nav != null) {
            try { navName = String.valueOf(getPackageManager().getActivityInfo(nav, 0).loadLabel(getPackageManager())); }
            catch (Exception ignored) { }
        }
        boolean auto = prefs().getBoolean("auto_nav", true);
        boolean off = prefs().getBoolean("panel_off", false);

        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);

        list.addView(row("네비 앱", navName, v -> showApps(-2)));
        boolean split = PanelService.splitLayout(this);
        list.addView(row("화면 방식", split ? "반반" : "떠 있는 패널", v -> {
            prefs().edit().putString("layout", split ? "float" : "split").apply();
            if (PanelService.inst != null) { PanelService.inst.exitSplit(); PanelService.inst.rebuild(); }
            showSettings();
        }));
        if (split) {
            boolean swap = prefs().getBoolean("split_swap", false);
            list.addView(row("네비 · 패널 자리 바꾸기", swap ? "바꿈" : "처음대로", v -> {
                prefs().edit().putBoolean("split_swap", !swap).apply();
                if (PanelService.inst != null) PanelService.inst.exitSplit();
                showSettings();
            }));
        }
        placeRow(list, "home", "🏠 집 위치");
        placeRow(list, "studio", "📷 사진관 위치");
        toggleRow(list, "auto_size", "달리면 작게 · 서면 크게");
        toggleRow(list, "auto_music", "시동 걸면 음악 이어 듣기");
        toggleRow(list, "commute_ask", "출근길 사진관 안내 묻기");
        list.addView(row("홈 누르면 네비 자동 실행", auto ? "켬" : "끔", v -> {
            prefs().edit().putBoolean("auto_nav", !auto).apply(); showSettings(); }));
        list.addView(row("패널 보이기", off ? "끔" : "켬", v -> {
            prefs().edit().putBoolean("panel_off", !off).apply(); showSettings(); }));
        list.addView(row("패널 폭 처음대로", "", v -> {
            prefs().edit().remove("panel_w").remove("web_w").apply(); toast("다시 기본 폭으로 했어요"); }));
        list.addView(row("음악 앱", musicName(), v -> showApps(-3)));
        list.addView(row("패널 오른쪽 여백", prefs().getInt("right_gap", 72) + "", v -> chooseGapFor("right_gap", "패널 오른쪽 여백")));
        list.addView(row("패널 위 여백", prefs().getInt("top_gap", 72) + "", v -> chooseGapFor("top_gap", "패널 위 여백")));
        list.addView(row("패널 아래 여백", prefs().getInt("bottom_gap", 84) + "", v -> chooseGapFor("bottom_gap", "패널 아래 여백")));
        list.addView(row("사진 넘김 간격", prefs().getInt("interval", 15) + "초", v -> chooseInterval()));
        list.addView(row("사진 앨범 연결 (휴대폰 업로드)", prefs().getString("token", null) == null ? "안 됨" : "연결됨", v -> askToken()));
        list.addView(row("인터넷 첫 화면", prefs().getString("web_home", "https://m.naver.com/"), v -> askWebHome()));
        list.addView(row("업데이트", "지금 " + versionName(), v -> update()));
        list.addView(row("위 막대 색을 바탕과 같게", "배경화면 바꾸기", v -> darkWallpaper()));
        list.addView(row("기본 홈 앱 바꾸기", "", v -> open(Settings.ACTION_HOME_SETTINGS)));
        list.addView(row("접근성 설정", "", v -> open(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        list.addView(row("개발자 옵션", "", v -> open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)));
        list.addView(row("안드로이드 설정", "", v -> open(Settings.ACTION_SETTINGS)));

        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, 0, 1f);
        sl.topMargin = dp(12);
        box.addView(sv, sl);
        box.addView(button("닫기", v -> showHome()));
        setContentView(box);
    }

    private View row(String name, String value, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(20), dp(16), dp(20), dp(16));
        r.setBackground(round(CARD, dp(14)));
        TextView n = new TextView(this);
        n.setText(name);
        n.setTextColor(Color.WHITE);
        n.setTextSize(17);
        r.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(DIM);
        v.setTextSize(15);
        v.setSingleLine(true);
        v.setMaxWidth(dp(360));
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(v);
        r.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        r.setLayoutParams(lp);
        return r;
    }

    private String musicName() {
        ComponentName c = PanelService.resolve(this, prefs().getString("music_app", null));
        if (c == null) return "없음";
        try { return String.valueOf(getPackageManager().getActivityInfo(c, 0).loadLabel(getPackageManager())); }
        catch (Exception e) { return "없음"; }
    }

    // 위치: 달리면 작게·집/사진관 안내에 씀 — 한 번만 물어봄
    private void askLocationOnce() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (PanelService.inst != null) PanelService.inst.startLocation();
            return;
        }
        if (prefs().getBoolean("asked_loc", false)) return;
        prefs().edit().putBoolean("asked_loc", true).apply();
        requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 7);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (PanelService.inst != null) PanelService.inst.startLocation();
    }

    private void placeRow(LinearLayout list, String key, String label) {
        boolean has = prefs().getString("place_" + key, null) != null;
        list.addView(row(label, has ? "저장됨" : "없음", v -> new AlertDialog.Builder(this).setTitle(label)
                .setItems(new String[]{"지금 위치로 저장", "지우기"}, (d, w) -> {
                    if (w == 0) {
                        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            prefs().edit().putBoolean("asked_loc", false).apply();
                            askLocationOnce();
                            return;
                        }
                        boolean ok = PanelService.inst != null && PanelService.inst.savePlace(key);
                        android.widget.Toast.makeText(this, ok ? "저장했어요" : "아직 위치를 못 찾았어요. 밖에서 잠시 뒤 다시 해 주세요",
                                android.widget.Toast.LENGTH_LONG).show();
                    } else prefs().edit().remove("place_" + key).apply();
                    showSettings();
                }).show()));
    }

    private void toggleRow(LinearLayout list, String key, String label) {
        boolean on = prefs().getBoolean(key, true);
        list.addView(row(label, on ? "켬" : "끔", v -> { prefs().edit().putBoolean(key, !on).apply(); showSettings(); }));
    }

    private void chooseGapFor(String key, String title) {
        final int[] vals = key.equals("top_gap")
                ? new int[]{-40, -32, -24, -16, -8, 0, 24, 48, 72, 96}
                : new int[]{0, 24, 48, 72, 96, 120, 150};
        String[] names = new String[vals.length];
        for (int i = 0; i < vals.length; i++) names[i] = vals[i] == 0 ? "0" : String.valueOf(vals[i]);
        new AlertDialog.Builder(this).setTitle(title)
                .setItems(names, (d, w) -> {
                    prefs().edit().putInt(key, vals[w]).apply(); showSettings(); })
                .show();
    }

    private void chooseInterval() {
        final int[] vals = {10, 15, 30, 60};
        new AlertDialog.Builder(this).setTitle("사진 넘김 간격")
                .setItems(new String[]{"10초", "15초", "30초", "1분"}, (d, w) -> {
                    prefs().edit().putInt("interval", vals[w]).apply(); showSettings(); })
                .show();
    }

    private void askToken() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        et.setText(prefs().getString("token", ""));
        new AlertDialog.Builder(this).setTitle("앨범 토큰").setView(et)
                .setPositiveButton("저장", (d, w) -> {
                    String t = et.getText().toString().trim();
                    if (t.isEmpty()) prefs().edit().remove("token").apply();
                    else prefs().edit().putString("token", t).apply();
                    if (PanelService.inst != null) { PanelService.inst.loadPhotos(); PanelService.inst.syncAlbum(); }
                    showSettings();
                })
                .setNegativeButton("취소", null).show();
    }

    private void askWebHome() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        et.setText(prefs().getString("web_home", "https://m.naver.com/"));
        new AlertDialog.Builder(this).setTitle("인터넷 첫 화면 주소").setView(et)
                .setPositiveButton("저장", (d, w) -> {
                    String u = et.getText().toString().trim();
                    if (!u.startsWith("http")) u = "https://" + u;
                    prefs().edit().putString("web_home", u).apply();
                    showSettings();
                })
                .setNegativeButton("취소", null).show();
    }

    // ======================================================================
    // 전체 앱 / 앱 고르기
    // ======================================================================

    /** pick: -1 그냥 실행, -2 네비 고르기, -3 음악, 0~5 패널 칸, 100+칸 분할 왼쪽, 200+칸 분할 오른쪽 */
    private String splitLeft = null;

    private void showApps(int pick) {
        homeMode = false;
        otherScreen = true;
        LinearLayout box = page();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = title(pick == -2 ? "네비 고르기" : pick == -3 ? "음악 앱 고르기"
                : pick >= 200 ? "오른쪽 반에 띄울 앱" : pick >= 100 ? "왼쪽 반에 띄울 앱"
                : pick >= 0 ? "이 칸에 넣을 앱" : "전체 앱");
        top.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        if (pick >= 0 && pick < 100) {
            top.addView(small("칸 비우기", v -> {
                prefs().edit().remove("slot_" + pick).apply(); showHome(); }));
        }
        top.addView(small("닫기", v -> showHome()));
        box.addView(top);
        if (pick >= 0 && pick < 100) {
            TextView sp = new TextView(this);
            sp.setText("◧   두 앱 반반으로 저장하기");
            sp.setTextColor(Color.WHITE);
            sp.setTextSize(19);
            sp.setGravity(Gravity.CENTER);
            sp.setPadding(dp(20), dp(16), dp(20), dp(16));
            sp.setBackground(round(Color.parseColor("#3D6FE0"), dp(18)));
            sp.setOnClickListener(v -> showApps(100 + pick));
            LinearLayout.LayoutParams spl = new LinearLayout.LayoutParams(-1, -2);
            spl.topMargin = dp(14);
            box.addView(sp, spl);
        }

        final List<ResolveInfo> apps = loadApps();
        final PackageManager pm = getPackageManager();
        GridView gv = new GridView(this);
        gv.setColumnWidth(dp(120));
        gv.setNumColumns(GridView.AUTO_FIT);
        gv.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        gv.setVerticalSpacing(dp(14));
        gv.setSelector(new ColorDrawable(Color.TRANSPARENT));
        gv.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return apps.size(); }
            @Override public Object getItem(int p) { return apps.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int p, View cv, ViewGroup parent) {
                LinearLayout cell;
                if (cv == null) {
                    cell = new LinearLayout(MainActivity.this);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    cell.setGravity(Gravity.CENTER_HORIZONTAL);
                    cell.setPadding(dp(4), dp(6), dp(4), dp(6));
                    cell.addView(new ImageView(MainActivity.this), new LinearLayout.LayoutParams(dp(54), dp(54)));
                    TextView tv = new TextView(MainActivity.this);
                    tv.setTextColor(Color.parseColor("#D5DBE5"));
                    tv.setTextSize(13);
                    tv.setGravity(Gravity.CENTER);
                    tv.setMaxLines(2);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                    lp.topMargin = dp(6);
                    cell.addView(tv, lp);
                } else cell = (LinearLayout) cv;
                ResolveInfo r = apps.get(p);
                ((ImageView) cell.getChildAt(0)).setImageDrawable(r.loadIcon(pm));
                ((TextView) cell.getChildAt(1)).setText(r.loadLabel(pm));
                return cell;
            }
        });
        gv.setOnItemClickListener((parent, view, pos, id) -> {
            ResolveInfo r = apps.get(pos);
            ComponentName cn = new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
            if (pick == -2) {
                prefs().edit().putString("nav", cn.flattenToString()).apply();
                showHome();
            } else if (pick == -3) {
                prefs().edit().putString("music_app", cn.flattenToString()).apply();
                PanelService.launchFull(this, cn);
            } else if (pick >= 200) {
                if (splitLeft != null) prefs().edit().putString("slot_" + (pick - 200),
                        "split:" + splitLeft + "|" + cn.flattenToString()).apply();
                splitLeft = null;
                showHome();
            } else if (pick >= 100) {
                splitLeft = cn.flattenToString();
                showApps(pick + 100);
            } else if (pick >= 0) {
                prefs().edit().putString("slot_" + pick, cn.flattenToString()).apply();
                showHome();
            } else {
                PanelService.launchFull(this, cn);
            }
        });
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, 0, 1f);
        gl.topMargin = dp(12);
        box.addView(gv, gl);
        setContentView(box);
    }

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

    // ======================================================================
    // 업데이트 / 배경화면
    // ======================================================================

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    private void update() {
        try {
            final android.app.DownloadManager dm = (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            android.app.DownloadManager.Request r = new android.app.DownloadManager.Request(android.net.Uri.parse(APK_URL));
            r.setTitle("싼슬라 업데이트");
            r.setMimeType("application/vnd.android.package-archive");
            r.setDestinationInExternalFilesDir(this, android.os.Environment.DIRECTORY_DOWNLOADS,
                    "gt-home-" + System.currentTimeMillis() + ".apk");
            r.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            final long id = dm.enqueue(r);
            toast("업데이트 받는 중…");
            getApplicationContext().registerReceiver(new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    if (i.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return;
                    try { c.unregisterReceiver(this); } catch (Exception ignored) { }
                    android.net.Uri u = dm.getUriForDownloadedFile(id);
                    if (u == null) { Toast.makeText(c, "받기 실패 — 인터넷을 확인해 주세요", Toast.LENGTH_LONG).show(); return; }
                    Intent in = new Intent(Intent.ACTION_VIEW);
                    in.setDataAndType(u, "application/vnd.android.package-archive");
                    in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { c.startActivity(in); } catch (Exception e) { Toast.makeText(c, "설치 화면을 열 수 없어요", Toast.LENGTH_LONG).show(); }
                }
            }, new android.content.IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        } catch (Exception e) {
            toast("업데이트를 시작할 수 없어요");
        }
    }

    private void darkWallpaper() {
        try {
            Bitmap b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            b.eraseColor(BG);
            android.app.WallpaperManager.getInstance(this).setBitmap(b);
            toast("배경화면을 바꿨어요");
        } catch (Exception e) {
            toast("배경화면을 바꿀 수 없어요");
        }
    }

    // ======================================================================
    // 부품
    // ======================================================================

    private LinearLayout page() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(BG);
        box.setPadding(dp(32), dp(24), dp(32), dp(20));
        return box;
    }

    private TextView title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(24);
        return t;
    }

    private TextView button(String s, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(18);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(28), dp(14), dp(28), dp(14));
        t.setBackground(round(ACCENT, dp(28)));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(20);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView small(String s, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setPadding(dp(18), dp(10), dp(18), dp(10));
        t.setBackground(round(CARD, dp(22)));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(10);
        t.setLayoutParams(lp);
        return t;
    }

    private GradientDrawable round(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    private void open(String action) {
        try { startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception ignored) { }
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
