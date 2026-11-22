package com.gtman.hud;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 싼슬라 HUD (휴대폰용)
 *  - 속도: 폰 GPS로 직접 (딜레이 없음)
 *  - 전화: 폰이 직접 (연락처 이름까지)
 *  - 회전 안내·곡명: 차(싼슬라)에서 받아 옴
 * 켜지면 차를 스스로 찾고, 차와 1분 넘게 끊기면(시동 끔) 알아서 닫힘.
 */
public class HudActivity extends Activity {
    private static final int PORT = 8080;
    private static final int GREEN = Color.parseColor("#3DFC8A");
    private static final int DIM = Color.parseColor("#9FB3C8");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();

    private FrameLayout stage;
    private LinearLayout turnRow, callBox;
    private TextView arrow, dist, road, speed, unit, clock, song, callName, status, raw;

    private volatile String carIp = null;
    private boolean everFound = false, searching = false;
    private long lostSince = 0;
    private int mirror = 0;   // 0 보통, 1 좌우, 2 상하

    private LocationManager lm;
    private long lastFixAt = 0;
    private String callText = "";
    private TelephonyManager tm;

    private SharedPreferences prefs() { return getSharedPreferences("hud", MODE_PRIVATE); }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        buildUi();
        hideBars();
        mirror = prefs().getInt("mirror", 0);
        applyMirror();
        askPermissions();
        startGps();
        startPhone();
        ui.post(tick);
        ui.post(carCheck);
    }

    @Override protected void onResume() { super.onResume(); hideBars(); }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideBars(); }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        try { if (lm != null) lm.removeUpdates(gps); } catch (Exception ignored) { }
        try { if (tm != null) tm.listen(phone, PhoneStateListener.LISTEN_NONE); } catch (Exception ignored) { }
        net.shutdownNow();
        super.onDestroy();
    }

    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // ------------------------------------------------------------------
    // 화면
    // ------------------------------------------------------------------
    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        stage = new FrameLayout(this);
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        stage.addView(col, new FrameLayout.LayoutParams(-1, -1));

        int h = getResources().getDisplayMetrics().heightPixels;
        int w = getResources().getDisplayMetrics().widthPixels;
        float base = Math.min(w, h) / getResources().getDisplayMetrics().density;   // dp

        turnRow = new LinearLayout(this);
        turnRow.setGravity(Gravity.CENTER_VERTICAL);
        arrow = text(base * 0.20f, GREEN, true);
        turnRow.addView(arrow);
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(18), 0, 0, 0);
        dist = text(base * 0.13f, GREEN, true);
        road = text(base * 0.055f, Color.parseColor("#D8FFE6"), false);
        road.setSingleLine(true);
        road.setEllipsize(TextUtils.TruncateAt.END);
        road.setMaxWidth(Math.round(w * 0.6f));
        tx.addView(dist);
        tx.addView(road);
        turnRow.addView(tx);
        turnRow.setVisibility(View.INVISIBLE);
        col.addView(turnRow, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout sp = new LinearLayout(this);
        sp.setGravity(Gravity.BOTTOM);
        speed = text(base * 0.30f, Color.WHITE, true);
        speed.setText("--");
        speed.setIncludeFontPadding(false);
        unit = text(base * 0.06f, DIM, true);
        unit.setText("  km/h");
        unit.setPadding(0, 0, 0, dp(10));
        sp.addView(speed);
        sp.addView(unit);
        col.addView(sp, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout bot = new LinearLayout(this);
        bot.setGravity(Gravity.CENTER);
        clock = text(base * 0.05f, DIM, false);
        song = text(base * 0.05f, DIM, false);
        song.setSingleLine(true);
        song.setEllipsize(TextUtils.TruncateAt.END);
        song.setMaxWidth(Math.round(w * 0.6f));
        song.setPadding(dp(28), 0, 0, 0);
        bot.addView(clock);
        bot.addView(song);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.topMargin = dp(8);
        col.addView(bot, bl);

        callBox = new LinearLayout(this);
        callBox.setGravity(Gravity.CENTER);
        callBox.setPadding(dp(28), dp(14), dp(28), dp(14));
        android.graphics.drawable.GradientDrawable cb = new android.graphics.drawable.GradientDrawable();
        cb.setColor(Color.parseColor("#0F3D23"));
        cb.setStroke(dp(3), GREEN);
        cb.setCornerRadius(dp(40));
        callBox.setBackground(cb);
        callName = text(base * 0.09f, Color.WHITE, true);
        callName.setSingleLine(true);
        callName.setEllipsize(TextUtils.TruncateAt.END);
        callName.setMaxWidth(Math.round(w * 0.85f));
        callBox.addView(callName);
        callBox.setVisibility(View.GONE);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        cl.topMargin = dp(16);
        stage.addView(callBox, cl);

        // 확인판: 차가 네이버 지도 화면에서 읽은 글자 (안내 맞추기용, 나중에 뺌)
        raw = text(11, Color.parseColor("#C9A227"), false);
        raw.setMaxLines(9);
        raw.setEllipsize(TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        rl.setMargins(dp(14), dp(10), dp(14), 0);
        root.addView(raw, rl);

        status = text(13, Color.parseColor("#3A4350"), false);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        sl.setMargins(0, 0, dp(14), dp(10));
        root.addView(status, sl);

        // 누르면 보통 → 좌우 뒤집기 → 상하 뒤집기
        root.setOnClickListener(v -> { mirror = (mirror + 1) % 3; prefs().edit().putInt("mirror", mirror).apply(); applyMirror(); });
        setContentView(root);
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private void applyMirror() {
        stage.setScaleX(mirror == 1 ? -1 : 1);
        stage.setScaleY(mirror == 2 ? -1 : 1);
    }

    // 0.2초마다 화면 갱신
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            clock.setText(new SimpleDateFormat("HH:mm", Locale.KOREA).format(new Date()));
            if (System.currentTimeMillis() - lastFixAt > 4000) speed.setText("--");
            int hour = Integer.parseInt(new SimpleDateFormat("H", Locale.US).format(new Date()));
            stage.setAlpha(hour >= 19 || hour < 6 ? 0.6f : 1f);   // 밤에는 살짝 어둡게
            callBox.setVisibility(callText.isEmpty() ? View.GONE : View.VISIBLE);
            callName.setText("📞  " + callText);
            ui.postDelayed(this, 200);
        }
    };

    // ------------------------------------------------------------------
    // 속도: 폰 GPS
    // ------------------------------------------------------------------
    private final LocationListener gps = new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            lastFixAt = System.currentTimeMillis();
            if (l.hasSpeed()) {
                int kmh = Math.round(l.getSpeed() * 3.6f);
                speed.setText(String.valueOf(kmh < 3 ? 0 : kmh));
            }
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    };

    private void startGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            if (lm == null) lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            lm.removeUpdates(gps);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 200, 0, gps, Looper.getMainLooper());
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------
    // 전화: 폰이 직접 (이름까지)
    // ------------------------------------------------------------------
    @SuppressWarnings("deprecation")
    private final PhoneStateListener phone = new PhoneStateListener() {
        @Override public void onCallStateChanged(int state, String number) {
            if (state == TelephonyManager.CALL_STATE_RINGING) {
                String n = nameOf(number);
                callText = n != null ? n : (number == null || number.isEmpty() ? "전화 수신" : number);
            } else if (state == TelephonyManager.CALL_STATE_IDLE) {
                callText = "";
            } else if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                ui.postDelayed(() -> callText = "", 3000);   // 받으면 3초 뒤 정리
            }
        }
    };

    @SuppressWarnings("deprecation")
    private void startPhone() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return;
        try {
            if (tm == null) tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            tm.listen(phone, PhoneStateListener.LISTEN_NONE);
            tm.listen(phone, PhoneStateListener.LISTEN_CALL_STATE);
        } catch (Exception ignored) { }
    }

    private String nameOf(String number) {
        if (number == null || number.isEmpty()) return null;
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null;
        Uri u = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
        try (Cursor c = getContentResolver().query(u, new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return null;
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        for (String p : new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS})
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        startGps();
        startPhone();
    }

    // ------------------------------------------------------------------
    // 차(싼슬라)에서 회전 안내·곡명 받기
    // ------------------------------------------------------------------
    private final Runnable carCheck = new Runnable() {
        @Override public void run() {
            final String ip = carIp;
            if (ip == null) {
                if (!searching) search();
                status.setText("싼슬라 찾는 중");
                if (everFound && lostSince != 0 && System.currentTimeMillis() - lostSince > 60000) { finish(); return; }
                ui.postDelayed(this, 1500);
                return;
            }
            net.execute(() -> {
                String body = get("http://" + ip + ":" + PORT + "/hud.json", 1200);
                ui.post(() -> {
                    if (body != null) { lostSince = 0; status.setText(""); show(body); }
                    else {
                        if (lostSince == 0) lostSince = System.currentTimeMillis();
                        if (System.currentTimeMillis() - lostSince > 6000) carIp = null;
                    }
                    ui.postDelayed(carCheck, 400);
                });
            });
        }
    };

    private static final Pattern DIST = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(km|m)\\b", Pattern.CASE_INSENSITIVE);

    private void show(String body) {
        try {
            JSONObject d = new JSONObject(body);
            String s = d.optString("song", "");
            song.setText(s.isEmpty() ? "" : "♪ " + s);
            raw.setText(d.optString("raw", ""));
            String carCall = d.optString("call", "");
            if (callText.isEmpty() && !carCall.isEmpty()) callText = carCall;   // 폰이 못 잡았으면 차 정보로
            String nav = (d.optString("navTitle", "") + " " + d.optString("navText", "")).trim();
            Matcher m = DIST.matcher(nav);
            boolean real = m.find() || nav.matches(".*(회전|유턴|U턴|목적지|도착|진입|출구).*");
            if (!real) { turnRow.setVisibility(View.INVISIBLE); return; }   // 안내 내용 없으면 숨김
            m.reset();
            String ds = "", rest = nav;
            if (m.find()) { ds = m.group(1) + m.group(2); rest = (nav.substring(0, m.start()) + nav.substring(m.end())).trim(); }
            dist.setText(ds);
            road.setText(rest);
            arrow.setText(arrowFor(nav));
            turnRow.setVisibility(View.VISIBLE);
        } catch (Exception ignored) { }
    }

    private static String arrowFor(String s) {
        if (s.matches(".*(유턴|U턴).*")) return "↶";
        if (s.matches(".*(좌회전|왼쪽).*")) return "↰";
        if (s.matches(".*(우회전|오른쪽).*")) return "↱";
        if (s.matches(".*(목적지|도착).*")) return "⚑";
        return "↑";
    }

    private static String get(String url, int timeout) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                return o.toString("UTF-8");
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 핫스팟 안에서 싼슬라 찾기: 지난번 주소 먼저, 없으면 전체 훑기 */
    private void search() {
        searching = true;
        new Thread(() -> {
            String found = null;
            String last = prefs().getString("ip", null);
            if (last != null && get("http://" + last + ":" + PORT + "/hud.json", 1000) != null) found = last;
            if (found == null) {
                ExecutorService pool = Executors.newFixedThreadPool(64);
                AtomicReference<String> hit = new AtomicReference<>();
                for (String base : subnets())
                    for (int i = 1; i < 255; i++) {
                        final String ip = base + i;
                        pool.execute(() -> {
                            if (hit.get() == null && get("http://" + ip + ":" + PORT + "/hud.json", 500) != null) hit.compareAndSet(null, ip);
                        });
                    }
                pool.shutdown();
                try { pool.awaitTermination(20, TimeUnit.SECONDS); } catch (Exception ignored) { }
                found = hit.get();
            }
            final String f = found;
            ui.post(() -> {
                searching = false;
                if (f != null) { carIp = f; everFound = true; lostSince = 0; prefs().edit().putString("ip", f).apply(); }
                else if (everFound && lostSince == 0) lostSince = System.currentTimeMillis();
            });
        }).start();
    }

    private static List<String> subnets() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ns = NetworkInterface.getNetworkInterfaces();
            while (ns.hasMoreElements()) {
                NetworkInterface n = ns.nextElement();
                if (!n.isUp() || n.isLoopback()) continue;
                String name = n.getName();
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("dummy") || name.startsWith("tun")) continue;
                Enumeration<InetAddress> as = n.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    String base = ip.substring(0, ip.lastIndexOf('.') + 1);
                    if (!out.contains(base)) out.add(base);
                }
            }
        } catch (Exception ignored) { }
        return out;
    }
}
