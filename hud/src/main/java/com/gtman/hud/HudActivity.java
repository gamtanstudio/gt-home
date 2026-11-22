package com.gtman.hud;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 싼슬라 HUD (휴대폰용): 켜지면 차(싼슬라)를 스스로 찾아서 HUD 화면을 전체 화면으로 띄움.
 * 차와 1분 넘게 연결이 끊기면(시동 끔) 알아서 닫힘.
 */
public class HudActivity extends Activity {
    private static final int PORT = 8080;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView wait;
    private volatile String carIp = null;
    private long lostSince = 0;
    private boolean searching = false;

    private SharedPreferences prefs() { return getSharedPreferences("hud", MODE_PRIVATE); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.setBackgroundColor(Color.BLACK);
        web.setWebViewClient(new WebViewClient());
        web.setVisibility(View.INVISIBLE);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        wait = new TextView(this);
        wait.setText("싼슬라 찾는 중…");
        wait.setTextColor(Color.parseColor("#6F8296"));
        wait.setTextSize(22);
        wait.setGravity(Gravity.CENTER);
        root.addView(wait, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        hideBars();
        ui.post(check);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
    }

    @Override
    public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) hideBars();
    }

    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** 3초마다 차가 살아 있는지 보고, 없으면 다시 찾음. 1분 넘게 없으면 닫기 */
    private final Runnable check = new Runnable() {
        @Override public void run() {
            final String ip = carIp;
            new Thread(() -> {
                boolean ok = ip != null && alive(ip, 1200);
                ui.post(() -> {
                    if (ok) {
                        lostSince = 0;
                        if (web.getVisibility() != View.VISIBLE) {
                            web.loadUrl("http://" + ip + ":" + PORT + "/hud");
                            web.setVisibility(View.VISIBLE);
                            wait.setVisibility(View.GONE);
                        }
                    } else {
                        if (lostSince == 0) lostSince = System.currentTimeMillis();
                        if (carIp != null && System.currentTimeMillis() - lostSince > 60000) { finish(); return; }
                        web.setVisibility(View.INVISIBLE);
                        wait.setVisibility(View.VISIBLE);
                        carIp = null;
                        if (!searching) search();
                    }
                    ui.postDelayed(check, 3000);
                });
            }).start();
        }
    };

    private static boolean alive(String ip, int timeout) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + ip + ":" + PORT + "/hud.json").openConnection();
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            int code = c.getResponseCode();
            c.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** 핫스팟(또는 같은 와이파이) 안에서 싼슬라를 찾음: 지난번 주소 먼저, 없으면 전체 훑기 */
    private void search() {
        searching = true;
        new Thread(() -> {
            String found = null;
            String last = prefs().getString("ip", null);
            if (last != null && alive(last, 1200)) found = last;
            if (found == null) {
                List<String> bases = subnets();
                ExecutorService pool = Executors.newFixedThreadPool(48);
                AtomicReference<String> hit = new AtomicReference<>();
                for (String base : bases) {
                    for (int i = 1; i < 255; i++) {
                        final String ip = base + i;
                        pool.execute(() -> { if (hit.get() == null && alive(ip, 600)) hit.compareAndSet(null, ip); });
                    }
                }
                pool.shutdown();
                try { pool.awaitTermination(25, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) { }
                found = hit.get();
            }
            final String f = found;
            ui.post(() -> {
                searching = false;
                if (f != null) { carIp = f; prefs().edit().putString("ip", f).apply(); }
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
