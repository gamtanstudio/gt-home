package com.gtman.home;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int BG = Color.parseColor("#07090D");
    private static final int PANEL = Color.parseColor("#0E1219");
    private static final int CARD = Color.parseColor("#161B25");
    private static final int CARD_EMPTY = Color.parseColor("#11151D");
    private static final int ACCENT = Color.parseColor("#4F8BFF");
    private static final int DIM = Color.parseColor("#9AA3B5");

    // 사진 앨범 (감탄 액자 서버 재사용)
    private static final String SB_URL = "https://sdjhvtljrzkvniwdtwir.supabase.co";
    private static final String SB_KEY = "sb_publishable_qoIwl7-UfHB2L3LsqnCu-g_t-_6B53R";
    private static final String BUCKET = "frame-photos";

    private static final String[] NAV_DEFAULTS = {"com.skt.tmap.ku", "com.locnall.KimGiSaNavi", "com.nhn.android.nmap", "net.daum.android.map"};
    private static final String[] APP_DEFAULTS = {
            "app.revanced.android.youtube",
            "com.gtman.browser/com.gtman.browser.MainActivity",
            "com.gtman.browser/com.gtman.browser.MusicActivity",
            "org.jw.jwlibrary.mobile",
            "com.google.android.youtube",
            "com.gtman.browser/com.gtman.browser.TubeActivity",
    };
    private static final int SLOTS = 6;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private LinearLayout leftArea;
    private ImageView navIcon;
    private TextView navName;
    private LinearLayout grid;
    private ImageView photoA, photoB;
    private boolean showA = true;
    private int photoIdx = -1;
    private final List<Object> photoList = new ArrayList<>(); // Integer(res id) 또는 File
    private FrameLayout drawer;
    private boolean wasStopped = true;
    private long lastNavLaunch = 0;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private SharedPreferences prefs() { return getSharedPreferences("gthome", MODE_PRIVATE); }

    // ======================================================================
    // 생명주기
    // ======================================================================

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (Build.VERSION.SDK_INT >= 28) {
            try { org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L"); } catch (Throwable ignored) { }
        }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (!prefs().getBoolean("init2", false)) fillDefaults();
        buildUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        startSync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshNavArea();
        buildGrid();
        loadPhotos();
        if (wasStopped && prefs().getBoolean("auto_nav", true) && freeformOn()
                && System.currentTimeMillis() - lastNavLaunch > 4000) {
            ui.postDelayed(this::launchNav, 500);
        }
        wasStopped = false;
        startSlideshow();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(slideTick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        wasStopped = true;
        ui.removeCallbacks(syncTick);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && drawer == null && dlgOpen == 0 && freeformOn() && prefs().getBoolean("auto_nav", true)
                && System.currentTimeMillis() - lastNavLaunch > 1500) {
            ui.removeCallbacks(navBack);
            ui.postDelayed(navBack, 250);
        }
    }

    private final Runnable navBack = this::launchNav;
    private int dlgOpen = 0;

    /** 설정 창은 오른쪽에 띄우고, 떠 있는 동안 네비가 덮지 않게 */
    private void showDlg(AlertDialog.Builder b) {
        ui.removeCallbacks(navBack);
        dlgOpen++;
        AlertDialog d = b.create();
        d.setOnDismissListener(x -> { if (dlgOpen > 0) dlgOpen--; });
        d.show();
        if (d.getWindow() != null) {
            d.getWindow().setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            d.getWindow().setLayout(Math.round(getResources().getDisplayMetrics().widthPixels * 0.36f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closeDrawer();
    }

    @Override
    public void onBackPressed() {
        if (drawer != null) closeDrawer();
    }

    // ======================================================================
    // 화면 구성
    // ======================================================================

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row, new FrameLayout.LayoutParams(-1, -1));

        float ratio = prefs().getFloat("left_ratio", 0.62f);

        // ---- 왼쪽: 네비 자리 ----
        leftArea = new LinearLayout(this);
        leftArea.setOrientation(LinearLayout.VERTICAL);
        leftArea.setGravity(Gravity.CENTER);
        GradientDrawable lg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#141A24"), Color.parseColor("#0A0D13")});
        leftArea.setBackground(lg);
        navIcon = new ImageView(this);
        leftArea.addView(navIcon, new LinearLayout.LayoutParams(dp(120), dp(120)));
        navName = new TextView(this);
        navName.setTextColor(Color.WHITE);
        navName.setTextSize(26);
        navName.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.topMargin = dp(18);
        leftArea.addView(navName, nlp);
        TextView open = pill("네비 열기", ACCENT, v -> launchNav());
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
        olp.topMargin = dp(22);
        leftArea.addView(open, olp);
        leftArea.setOnClickListener(v -> launchNav());
        leftArea.setOnLongClickListener(v -> { openDrawer(-2); return true; });
        row.addView(leftArea, new LinearLayout.LayoutParams(0, -1, ratio));

        // ---- 오른쪽 패널 ----
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(PANEL);
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        row.addView(panel, new LinearLayout.LayoutParams(0, -1, 1f - ratio));

        panel.addView(buildPhotoCard(), new LinearLayout.LayoutParams(-1, 0, 4.4f));

        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, 0, 1.3f);
        mlp.topMargin = dp(12);
        panel.addView(buildMediaCard(), mlp);

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, 0, 4.3f);
        glp.topMargin = dp(12);
        panel.addView(grid, glp);

        setContentView(root);
    }

    private View buildPhotoCard() {
        FrameLayout card = new FrameLayout(this);
        card.setClipToOutline(true);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(CARD);
        cbg.setCornerRadius(dp(22));
        card.setBackground(cbg);

        photoA = new ImageView(this);
        photoB = new ImageView(this);
        for (ImageView iv : new ImageView[]{photoA, photoB}) {
            iv.setScaleType(ImageView.ScaleType.MATRIX);
            iv.setAlpha(0f);
            card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            iv.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> fitTop((ImageView) v));
        }

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.TRANSPARENT, Color.TRANSPARENT, Color.parseColor("#D007090D")}));
        card.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        clock.setPadding(dp(20), 0, dp(20), dp(14));
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        time.setTextColor(Color.WHITE);
        time.setTextSize(54);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setIncludeFontPadding(false);
        time.setShadowLayer(8, 0, 2, Color.parseColor("#80000000"));
        clock.addView(time);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("M월 d일 EEEE");
        date.setFormat12Hour("M월 d일 EEEE");
        date.setTextColor(Color.parseColor("#E6ECF5"));
        date.setTextSize(18);
        date.setShadowLayer(6, 0, 2, Color.parseColor("#80000000"));
        clock.addView(date);
        card.addView(clock, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));

        TextView gear = new TextView(this);
        gear.setText("⚙");
        gear.setTextSize(22);
        gear.setTextColor(Color.WHITE);
        gear.setGravity(Gravity.CENTER);
        gear.setBackground(round(Color.parseColor("#66000000"), dp(24)));
        gear.setOnClickListener(v -> showSettings());
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.END);
        glp.setMargins(0, dp(12), dp(12), 0);
        card.addView(gear, glp);

        card.setOnClickListener(v -> nextPhoto());
        return card;
    }

    private View buildMediaCard() {
        LinearLayout m = new LinearLayout(this);
        m.setOrientation(LinearLayout.HORIZONTAL);
        m.setGravity(Gravity.CENTER_VERTICAL);
        m.setPadding(dp(18), 0, dp(10), 0);
        m.setBackground(round(CARD, dp(22)));

        TextView label = new TextView(this);
        label.setText("♪  음악");
        label.setTextColor(Color.WHITE);
        label.setTextSize(20);
        m.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

        m.addView(mediaBtn("⏮", KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        m.addView(mediaBtn("⏯", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        m.addView(mediaBtn("⏭", KeyEvent.KEYCODE_MEDIA_NEXT));
        TextView all = pill("전체 앱", Color.parseColor("#232A38"), v -> openDrawer(-1));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
        alp.leftMargin = dp(8);
        m.addView(all, alp);
        return m;
    }

    private TextView mediaBtn(String s, int code) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(26);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(Color.TRANSPARENT, dp(26)));
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(60), dp(56)));
        t.setOnClickListener(v -> {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        });
        return t;
    }

    private TextView pill(String text, int color, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(18);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(22), dp(12), dp(22), dp(12));
        t.setBackground(round(color, dp(28)));
        t.setOnClickListener(l);
        return t;
    }

    private Drawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(Color.parseColor("#334F8BFF")), g, null);
    }

    // ======================================================================
    // 앱 칸 (오른쪽 2줄 × 3칸)
    // ======================================================================

    private void buildGrid() {
        grid.removeAllViews();
        PackageManager pm = getPackageManager();
        for (int i = 0; i < 2; i++) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, 0, 1f);
            if (i > 0) rlp.topMargin = dp(12);
            grid.addView(r, rlp);
            for (int j = 0; j < 3; j++) {
                int slot = i * 3 + j;
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -1, 1f);
                if (j > 0) tlp.leftMargin = dp(12);
                r.addView(buildTile(slot, pm), tlp);
            }
        }
    }

    private View buildTile(int slot, PackageManager pm) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(6), dp(6), dp(6), dp(6));
        ComponentName cn = resolve(prefs().getString("slot_" + slot, null));
        ActivityInfo info = null;
        if (cn != null) { try { info = pm.getActivityInfo(cn, 0); } catch (Exception ignored) { } }
        if (info == null) {
            t.setBackground(round(CARD_EMPTY, dp(22)));
            TextView plus = new TextView(this);
            plus.setText("+");
            plus.setTextSize(36);
            plus.setTextColor(Color.parseColor("#3A4256"));
            t.addView(plus);
            t.setOnClickListener(v -> openDrawer(slot));
            return t;
        }
        t.setBackground(round(CARD, dp(22)));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(info.loadIcon(pm));
        t.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView label = new TextView(this);
        label.setText(info.loadLabel(pm));
        label.setTextColor(Color.WHITE);
        label.setTextSize(16);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.topMargin = dp(8);
        t.addView(label, llp);
        final ComponentName target = cn;
        t.setOnClickListener(v -> launchRight(target));
        t.setOnLongClickListener(v -> {
            showDlg(new AlertDialog.Builder(this)
                    .setItems(new String[]{"다른 앱으로 바꾸기", "이 칸 비우기"}, (d, w) -> {
                        if (w == 0) openDrawer(slot);
                        else { prefs().edit().remove("slot_" + slot).apply(); buildGrid(); }
                    }));
            return true;
        });
        return t;
    }

    private void fillDefaults() {
        SharedPreferences.Editor e = prefs().edit();
        int slot = 0;
        for (String d : APP_DEFAULTS) {
            if (slot >= SLOTS) break;
            ComponentName cn = resolve(d);
            if (cn != null) e.putString("slot_" + slot++, cn.flattenToString());
        }
        e.putBoolean("init2", true).apply();
    }

    // ======================================================================
    // 창 띄우기 (자유 형식 창)
    // ======================================================================

    private boolean freeformOn() {
        try {
            if (Settings.Global.getInt(getContentResolver(), "enable_freeform_support", 0) != 0) return true;
        } catch (Exception ignored) { }
        return getPackageManager().hasSystemFeature("android.software.freeform_window_management");
    }

    private Rect[] areas() {
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int w = dm.widthPixels, h = dm.heightPixels;
        int lw = Math.round(w * prefs().getFloat("left_ratio", 0.62f));
        int cap = dp(prefs().getInt("cap_dp", 36));
        return new Rect[]{new Rect(0, -cap, lw, h), new Rect(lw, 0, w, h)};
    }

    private void launchIn(ComponentName cn, Rect bounds) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(cn);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (bounds != null && freeformOn()) {
                ActivityOptions o = ActivityOptions.makeBasic();
                o.setLaunchBounds(bounds);
                try {
                    Method m = ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
                    m.invoke(o, 5); // WINDOWING_MODE_FREEFORM
                } catch (Throwable ignored) { }
                startActivity(i, o.toBundle());
            } else {
                startActivity(i);
            }
        } catch (Exception e) {
            Toast.makeText(this, "앱을 열 수 없어요", Toast.LENGTH_SHORT).show();
        }
    }

    private ComponentName navApp() {
        ComponentName c = resolve(prefs().getString("nav", null));
        if (c != null) return c;
        for (String d : NAV_DEFAULTS) { c = resolve(d); if (c != null) return c; }
        return null;
    }

    private void launchNav() {
        ComponentName c = navApp();
        if (c == null) { openDrawer(-2); return; }
        lastNavLaunch = System.currentTimeMillis();
        launchIn(c, areas()[0]);
    }

    private void launchRight(ComponentName cn) {
        launchIn(cn, areas()[1]);
    }

    private void refreshNavArea() {
        ComponentName c = navApp();
        PackageManager pm = getPackageManager();
        if (c == null) {
            navIcon.setImageDrawable(null);
            navName.setText("네비를 골라 주세요");
            return;
        }
        try {
            ActivityInfo ai = pm.getActivityInfo(c, 0);
            navIcon.setImageDrawable(ai.loadIcon(pm));
            navName.setText(ai.loadLabel(pm));
        } catch (Exception e) {
            navName.setText("네비");
        }
    }

    // ======================================================================
    // 사진 슬라이드
    // ======================================================================

    private final Runnable slideTick = new Runnable() {
        @Override public void run() {
            nextPhoto();
            ui.postDelayed(this, prefs().getInt("interval", 15) * 1000L);
        }
    };

    private void startSlideshow() {
        ui.removeCallbacks(slideTick);
        if (photoIdx < 0) nextPhoto();
        ui.postDelayed(slideTick, prefs().getInt("interval", 15) * 1000L);
    }

    private void loadPhotos() {
        photoList.clear();
        File dir = new File(getFilesDir(), "album");
        File[] fs = dir.listFiles();
        if (fs != null && fs.length > 0 && prefs().getString("token", null) != null) {
            List<File> l = new ArrayList<>();
            for (File f : fs) if (f.length() > 0 && !f.getName().endsWith(".tmp")) l.add(f);
            Collections.sort(l, (a, b) -> a.getName().compareTo(b.getName()));
            photoList.addAll(l);
        }
        if (photoList.isEmpty()) {
            photoList.add(R.drawable.photo1);
            photoList.add(R.drawable.photo2);
            photoList.add(R.drawable.photo3);
        }
        if (photoIdx >= photoList.size()) photoIdx = -1;
    }

    private void nextPhoto() {
        if (photoList.isEmpty()) return;
        photoIdx = (photoIdx + 1) % photoList.size();
        Bitmap bm = decode(photoList.get(photoIdx));
        if (bm == null) return;
        ImageView in = showA ? photoA : photoB;
        ImageView out = showA ? photoB : photoA;
        showA = !showA;
        in.setImageBitmap(bm);
        fitTop(in);
        in.animate().alpha(1f).setDuration(1200).start();
        out.animate().alpha(0f).setDuration(1200).start();
    }

    private Bitmap decode(Object src) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            if (src instanceof Integer) BitmapFactory.decodeResource(getResources(), (Integer) src, o);
            else BitmapFactory.decodeFile(((File) src).getPath(), o);
            int s = 1;
            while (o.outWidth / (s * 2) >= 1280 || o.outHeight / (s * 2) >= 1280) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            if (src instanceof Integer) return BitmapFactory.decodeResource(getResources(), (Integer) src, o2);
            return BitmapFactory.decodeFile(((File) src).getPath(), o2);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 카드를 꽉 채우되, 위아래가 잘릴 땐 얼굴 있는 위쪽을 살림 */
    private void fitTop(ImageView iv) {
        Drawable d = iv.getDrawable();
        int vw = iv.getWidth(), vh = iv.getHeight();
        if (d == null || vw == 0 || vh == 0) return;
        int dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        float s = Math.max((float) vw / dw, (float) vh / dh);
        float dx = (vw - dw * s) / 2f;
        float dy = (vh - dh * s) * 0.3f;
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postTranslate(dx, dy);
        iv.setImageMatrix(m);
    }

    // ======================================================================
    // 휴대폰 앨범 동기화 (감탄 액자 서버 frame_manifest)
    // ======================================================================

    private final Runnable syncTick = new Runnable() {
        @Override public void run() {
            syncAlbum();
            ui.postDelayed(this, 10 * 60 * 1000L);
        }
    };

    private void startSync() {
        ui.removeCallbacks(syncTick);
        ui.post(syncTick);
    }

    private void syncAlbum() {
        final String token = prefs().getString("token", null);
        if (token == null) return;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(SB_URL + "/rest/v1/rpc/frame_manifest").openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setDoOutput(true);
                c.setRequestProperty("apikey", SB_KEY);
                c.setRequestProperty("Authorization", "Bearer " + SB_KEY);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(new JSONObject().put("p_token", token).toString().getBytes("UTF-8"));
                }
                if (c.getResponseCode() != 200) return;
                String body = readAll(c.getInputStream());
                JSONObject d = new JSONObject(body);
                JSONArray arr = d.optJSONArray("photos");
                if (arr == null) return;
                File dir = new File(getFilesDir(), "album");
                dir.mkdirs();
                Set<String> keep = new HashSet<>();
                boolean changed = false;
                for (int i = 0; i < arr.length(); i++) {
                    String path = arr.getJSONObject(i).optString("path", null);
                    if (path == null || path.isEmpty()) continue;
                    String name = String.format(Locale.US, "%04d_", i) + path.replaceAll("[^A-Za-z0-9._-]", "_");
                    keep.add(name);
                    File f = new File(dir, name);
                    if (f.exists() && f.length() > 0) continue;
                    if (download(SB_URL + "/storage/v1/object/public/" + BUCKET + "/" + path, f)) changed = true;
                }
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) if (!keep.contains(f.getName())) { f.delete(); changed = true; }
                if (changed) ui.post(() -> { photoIdx = -1; loadPhotos(); nextPhoto(); });
            } catch (Throwable ignored) { }
        }).start();
    }

    private boolean download(String url, File to) {
        File tmp = new File(to.getPath() + ".tmp");
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            if (c.getResponseCode() != 200) return false;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            }
            return tmp.renameTo(to);
        } catch (Throwable t) {
            tmp.delete();
            return false;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    // ======================================================================
    // 설정
    // ======================================================================

    private void showSettings() {
        boolean auto = prefs().getBoolean("auto_nav", true);
        String ff = freeformOn() ? "켜짐" : "꺼짐";
        String[] items = {
                "왼쪽 네비 앱 바꾸기",
                "홈 켜질 때 네비 자동 실행: " + (auto ? "켬" : "끔"),
                "왼쪽 크기 바꾸기",
                "사진 앨범 연결 (휴대폰 업로드)",
                "사진 넘김 간격",
                "자유 형식 창: " + ff + " (개발자 옵션 열기)",
                "기본 홈 앱 바꾸기",
                "네비 위 파란 줄 숨기기: " + prefs().getInt("cap_dp", 36),
                "GT 홈 업데이트 (지금 " + versionName() + ")",
        };
        showDlg(new AlertDialog.Builder(this)
                .setTitle("GT 홈 설정")
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: openDrawer(-2); break;
                        case 1: prefs().edit().putBoolean("auto_nav", !auto).apply(); break;
                        case 2: chooseRatio(); break;
                        case 3: askToken(); break;
                        case 4: chooseInterval(); break;
                        case 5: openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS); break;
                        case 6: openSettings(Settings.ACTION_HOME_SETTINGS); break;
                        case 7: chooseCaption(); break;
                        case 8: update(); break;
                    }
                }));
    }

    private void chooseRatio() {
        final float[] vals = {0.55f, 0.62f, 0.70f};
        showDlg(new AlertDialog.Builder(this)
                .setTitle("왼쪽(네비) 크기")
                .setItems(new String[]{"55%", "62%", "70%"}, (d, w) -> {
                    prefs().edit().putFloat("left_ratio", vals[w]).apply();
                    buildUi();
                    refreshNavArea();
                    buildGrid();
                    photoIdx = -1;
                    loadPhotos();
                    startSlideshow();
                }));
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    private static final String APK_URL = "https://github.com/gamtanstudio/gt-home/releases/download/latest/gt-home.apk";

    private void update() {
        try {
            final android.app.DownloadManager dm = (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            android.app.DownloadManager.Request r = new android.app.DownloadManager.Request(android.net.Uri.parse(APK_URL));
            r.setTitle("GT 홈 업데이트");
            r.setMimeType("application/vnd.android.package-archive");
            r.setDestinationInExternalFilesDir(this, android.os.Environment.DIRECTORY_DOWNLOADS,
                    "gt-home-" + System.currentTimeMillis() + ".apk");
            r.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            final long id = dm.enqueue(r);
            Toast.makeText(this, "업데이트 받는 중…", Toast.LENGTH_LONG).show();
            registerReceiver(new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    if (i.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return;
                    try { unregisterReceiver(this); } catch (Exception ignored) { }
                    android.net.Uri u = dm.getUriForDownloadedFile(id);
                    if (u == null) { Toast.makeText(c, "받기 실패 — 인터넷을 확인해 주세요", Toast.LENGTH_LONG).show(); return; }
                    Intent in = new Intent(Intent.ACTION_VIEW);
                    in.setDataAndType(u, "application/vnd.android.package-archive");
                    in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(in); } catch (Exception e) { Toast.makeText(c, "설치 화면을 열 수 없어요", Toast.LENGTH_LONG).show(); }
                }
            }, new android.content.IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        } catch (Exception e) {
            Toast.makeText(this, "업데이트를 시작할 수 없어요", Toast.LENGTH_LONG).show();
        }
    }

    private void chooseCaption() {
        final int[] vals = {0, 24, 30, 36, 42, 48};
        showDlg(new AlertDialog.Builder(this)
                .setTitle("네비 위 파란 줄 숨기기")
                .setItems(new String[]{"0 (안 숨김)", "24", "30", "36", "42", "48"}, (d, w) -> {
                    prefs().edit().putInt("cap_dp", vals[w]).apply();
                    launchNav();
                }));
    }

    private void chooseInterval() {
        final int[] vals = {10, 15, 30, 60};
        showDlg(new AlertDialog.Builder(this)
                .setTitle("사진 넘김 간격")
                .setItems(new String[]{"10초", "15초", "30초", "1분"}, (d, w) -> {
                    prefs().edit().putInt("interval", vals[w]).apply();
                    startSlideshow();
                }));
    }

    private void askToken() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        et.setText(prefs().getString("token", ""));
        et.setSingleLine(true);
        showDlg(new AlertDialog.Builder(this)
                .setTitle("앨범 토큰")
                .setView(et)
                .setPositiveButton("저장", (d, w) -> {
                    String t = et.getText().toString().trim();
                    if (t.isEmpty()) prefs().edit().remove("token").apply();
                    else prefs().edit().putString("token", t).apply();
                    photoIdx = -1;
                    loadPhotos();
                    syncAlbum();
                })
                .setNegativeButton("취소", null));
    }

    private void openSettings(String action) {
        try { startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception ignored) { }
        }
    }

    // ======================================================================
    // 앱 고르기 / 전체 앱
    // ======================================================================

    private ComponentName resolve(String d) {
        if (d == null) return null;
        PackageManager pm = getPackageManager();
        if (d.contains("/")) {
            ComponentName c = ComponentName.unflattenFromString(d);
            if (c == null) return null;
            try { pm.getActivityInfo(c, 0); return c; } catch (Exception e) { return null; }
        }
        Intent li = pm.getLaunchIntentForPackage(d);
        return li == null ? null : li.getComponent();
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

    /** pick: -1 그냥 실행(오른쪽), -2 네비 고르기, 0~5 칸에 넣기 */
    private void openDrawer(int pick) {
        closeDrawer();
        ui.removeCallbacks(navBack);
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
        title.setText(pick == -2 ? "왼쪽에 띄울 네비" : pick >= 0 ? "이 칸에 넣을 앱" : "전체 앱");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(pill("닫기", CARD, v -> closeDrawer()));
        box.addView(top, new LinearLayout.LayoutParams(-1, -2));

        final List<ResolveInfo> apps = loadApps();
        final PackageManager pm = getPackageManager();
        GridView gv = new GridView(this);
        gv.setColumnWidth(dp(130));
        gv.setNumColumns(GridView.AUTO_FIT);
        gv.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        gv.setVerticalSpacing(dp(18));
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
                    cell.setPadding(dp(4), dp(8), dp(4), dp(8));
                    cell.addView(new ImageView(MainActivity.this), new LinearLayout.LayoutParams(dp(64), dp(64)));
                    TextView tv = new TextView(MainActivity.this);
                    tv.setTextColor(Color.WHITE);
                    tv.setTextSize(15);
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
            closeDrawer();
            if (pick == -2) {
                prefs().edit().putString("nav", cn.flattenToString()).apply();
                refreshNavArea();
                launchNav();
            } else if (pick >= 0) {
                prefs().edit().putString("slot_" + pick, cn.flattenToString()).apply();
                buildGrid();
            } else {
                launchRight(cn);
            }
        });
        LinearLayout.LayoutParams gvlp = new LinearLayout.LayoutParams(-1, 0, 1f);
        gvlp.topMargin = dp(14);
        box.addView(gv, gvlp);
        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
    }

    private void closeDrawer() {
        if (drawer != null) { root.removeView(drawer); drawer = null; }
    }
}
