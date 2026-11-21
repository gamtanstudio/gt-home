package com.gtman.home;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 싼슬라 패널: 네비(전체 화면) 위에 떠 있는 오른쪽 유리 패널.
 * 접근성 서비스의 오버레이 창을 써서, 네비가 앞에 있을 때만 보이고
 * 다른 앱(360 카메라 등)이 뜨면 자동으로 숨는다.
 */
public class PanelService extends AccessibilityService {

    static PanelService inst;

    static final int MODE_FULL = 0, MODE_MINI = 1, MODE_WEB = 2, MODE_BUBBLE = 3, MODE_STRIP = 4;

    private static final int GLASS = Color.parseColor("#E6111620");
    private static final int CARD = Color.parseColor("#1AFFFFFF");
    private static final int DIM = Color.parseColor("#9AA3B5");

    private static final String SB_URL = "https://sdjhvtljrzkvniwdtwir.supabase.co";
    private static final String SB_KEY = "sb_publishable_qoIwl7-UfHB2L3LsqnCu-g_t-_6B53R";
    private static final String BUCKET = "frame-photos";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private FrameLayout root;
    private boolean attached = false;
    private int mode = MODE_FULL;

    private ImageView photoA, photoB;
    private boolean showA = true;
    private int photoIdx = -1;
    private final List<Object> photos = new ArrayList<>();
    private WebView web;

    // 지금 재생 중인 곡
    private MediaController ctrl;
    private TextView mTitle, mArtist, mPlay;
    private ImageView mArt;

    // ------------------------------------------------------------------
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private SharedPreferences prefs() { return getSharedPreferences("gthome", MODE_PRIVATE); }

    @Override
    protected void onServiceConnected() {
        inst = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        loadPhotos();
        setupMedia();
        ui.post(syncTick);
        ui.postDelayed(slideTick, interval());
    }

    @Override
    public boolean onUnbind(Intent intent) {
        hidePanel();
        ui.removeCallbacksAndMessages(null);
        inst = null;
        return super.onUnbind(intent);
    }

    @Override public void onInterrupt() { }

    // ------------------------------------------------------------------
    // 언제 보일지: 네비가 맨 앞이거나 싼슬라 홈일 때만
    // ------------------------------------------------------------------

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) { checkSplit(); checkKeyboard(); return; }
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = e.getPackageName();
        if (p == null) return;
        String pkg = p.toString();
        if (pkg.equals("com.android.systemui") || pkg.contains("inputmethod") || pkg.contains("keyboard")
                || pkg.equals(defaultIme())) return;
        if (splitLayout(this)) { onSplitEvent(pkg, e.getClassName()); return; }
        if (pkg.equals(getPackageName())) {
            // 싼슬라 자신의 패널·영상 창 같은 건 무시하고, 홈 화면일 때만 판단
            CharSequence cls = e.getClassName();
            if (cls == null || !cls.toString().endsWith("MainActivity")) return;
            // 홈 버튼으로 싼슬라 홈이 잠깐 지나갈 때는 패널을 내리지 않음 (영상이 끊기지 않게).
            // 설정·앱 고르기 화면은 열 때 따로 숨김.
            if (MainActivity.otherScreen) { hidePanel(); return; }
            wantShow = true;
            if (!keyboardUp) showPanel();
            return;
        }
        lastPkg = pkg;
        if (splitting || inSplit()) { wantShow = false; hidePanel(); return; } // 반반 화면일 땐 패널 숨김
        ComponentName nav = navApp(this);
        boolean isNavOrHome = (nav != null && pkg.equals(nav.getPackageName())) || pkg.equals(getPackageName());
        if (!isNavOrHome && !isBigWindow(pkg, e.getClassName())) return; // 볼륨 창 같은 작은 팝업은 무시
        boolean show = (nav != null && pkg.equals(nav.getPackageName()))
                || (pkg.equals(getPackageName()) && MainActivity.homeMode);
        wantShow = show;
        if (show && !keyboardUp) showPanel(); else hidePanel();
    }

    /** 그 앱이 화면 대부분을 덮는 진짜 화면(액티비티 또는 큰 창)인지 */
    private boolean isBigWindow(String pkg, CharSequence cls) {
        if (cls != null) {
            try { getPackageManager().getActivityInfo(new ComponentName(pkg, cls.toString()), 0); return true; }
            catch (Exception ignored) { }
        }
        try {
            int area = screenW() * screenH();
            android.graphics.Rect r = new android.graphics.Rect();
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                android.view.accessibility.AccessibilityNodeInfo root = w.getRoot();
                if (root == null || root.getPackageName() == null) continue;
                if (!pkg.equals(root.getPackageName().toString())) continue;
                w.getBoundsInScreen(r);
                if ((long) r.width() * r.height() > area * 0.5) return true;
            }
            return false;
        } catch (Exception e) {
            return true; // 모르면 안전하게 숨김
        }
    }

    // 키보드가 올라오면 잠깐 숨었다가, 내려가면 다시 나타남
    private boolean wantShow = false, keyboardUp = false;

    private String lastPkg = "";

    // 반반이 끝나고 네비만 남으면 패널을 다시 띄움
    private void checkSplit() {
        if (splitLayout(this)) { if (pill != null && inSplit() != pillSplit) showPill(); return; }
        boolean now = inSplit();
        if (now == wasSplit) return;
        wasSplit = now;
        if (now) { wantShow = false; hidePanel(); return; }
        if (splitting) return;
        ComponentName nav = navApp(this);
        if (nav != null && nav.getPackageName().equals(lastPkg)) {
            wantShow = true;
            ui.postDelayed(() -> { if (wantShow && !keyboardUp && !inSplit()) showPanel(); }, 400);
        }
    }

    private void checkKeyboard() {
        boolean up = false;
        android.graphics.Rect ime = new android.graphics.Rect();
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    up = true; w.getBoundsInScreen(ime); break; }
            }
        } catch (Exception ignored) { }
        // 패널 안(유튜브·인터넷)에서 글자를 치는 중이면 패널을 숨기지 않고, 키보드 위까지만 줄임
        if (up && attached && mode == MODE_WEB && root != null && root.hasWindowFocus()) {
            typingInPanel = true;
            keyboardUp = false;
            if (ime.top > lp.y + dp(120)) {
                lp.height = ime.top - lp.y - dp(8);
                try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
            }
            return;
        }
        if (!up && typingInPanel) {
            typingInPanel = false;
            if (attached) {
                int keepW = lp.width, keepX = lp.x;
                makeParams();
                lp.width = keepW; lp.x = keepX;
                try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
            }
            return;
        }
        if (up == keyboardUp) return;
        keyboardUp = up;
        if (up) hidePanel();
        else if (wantShow) ui.postDelayed(() -> { if (!keyboardUp && wantShow) showPanel(); }, 300);
    }
    private boolean typingInPanel = false;

    private String defaultIme() {
        try {
            String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (s != null && s.contains("/")) return s.substring(0, s.indexOf('/'));
        } catch (Exception ignored) { }
        return "";
    }

    void showPanel() {
        if (prefs().getBoolean("panel_off", false) || splitLayout(this)) { hidePanel(); return; }
        if (attached) return;
        build();
        try { wm.addView(root, lp); attached = true; } catch (Exception ignored) { }
    }

    void hidePanel() {
        exitVideoFull(true);
        parkWeb();
        if (!attached) return;
        try { wm.removeView(root); } catch (Exception ignored) { }
        attached = false;
    }

    void rebuild() {
        if (sideHost != null && splitLayout(this)) { buildSide(); return; }
        boolean was = attached;
        hidePanel();
        if (was) showPanel();
    }

    // ------------------------------------------------------------------
    // 창 크기
    // ------------------------------------------------------------------

    private int screenW() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm.widthPixels;
    }

    private int screenH() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm.heightPixels;
    }

    private int statusBarH() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int panelWidth() {
        if (mode == MODE_MINI) return dp(124);
        int def = Math.round(screenW() * (mode == MODE_WEB ? 0.42f : 0.32f));
        int w = prefs().getInt(mode == MODE_WEB ? "web_w" : "panel_w", def);
        return clampW(w);
    }

    private int clampW(int w) {
        return Math.max(dp(300), Math.min(w, Math.round(screenW() * 0.65f)));
    }

    private void makeParams() {
        int top = statusBarH();
        lp = new WindowManager.LayoutParams(
                panelWidth(), screenH() - top - dp(prefs().getInt("top_gap", 72)) - dp(prefs().getInt("bottom_gap", 84)),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                (mode == MODE_WEB ? 0 : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = dp(prefs().getInt("right_gap", 72));   // 네이버 오른쪽 버튼·속도 표시가 보이게
        lp.y = top + dp(prefs().getInt("top_gap", 72));
        if (mode == MODE_STRIP) {
            lp.width = dp(300);
            lp.height = dp(50);
            lp.x = dp(prefs().getInt("right_gap", 72));
            lp.y = Math.max(top, Math.min(prefs().getInt("bubble_y", top + dp(160)), screenH() - dp(70)));
            return;
        }
        if (mode == MODE_BUBBLE) {
            lp.width = dp(92);
            lp.height = dp(92);
            lp.x = dp(prefs().getInt("right_gap", 72));
            lp.y = Math.max(top, Math.min(prefs().getInt("bubble_y", top + dp(160)), screenH() - dp(90)));
        }
    }

    // ------------------------------------------------------------------
    // 화면 만들기
    // ------------------------------------------------------------------

    private void build() {
        makeParams();
        if (mode != MODE_WEB) parkWeb();
        root = new FrameLayout(this);
        if (mode == MODE_BUBBLE) { root.addView(buildBubble(), new FrameLayout.LayoutParams(-1, -1)); return; }
        if (mode == MODE_STRIP) { root.addView(buildStrip(), new FrameLayout.LayoutParams(-1, -1)); return; }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row, new FrameLayout.LayoutParams(-1, -1));

        // 왼쪽 손잡이 (끌어서 폭 조절)
        FrameLayout handle = new FrameLayout(this);
        View pill = new View(this);
        GradientDrawable pg = new GradientDrawable();
        pg.setColor(Color.parseColor("#88FFFFFF"));
        pg.setCornerRadius(dp(3));
        pill.setBackground(pg);
        handle.addView(pill, new FrameLayout.LayoutParams(dp(6), dp(80), Gravity.CENTER));
        row.addView(handle, new LinearLayout.LayoutParams(mode == MODE_MINI ? dp(10) : dp(26), -1));
        if (mode != MODE_MINI) handle.setOnTouchListener(dragger);
        else pill.setVisibility(View.GONE);

        FrameLayout glass = new FrameLayout(this);
        GradientDrawable gb = new GradientDrawable();
        gb.setColor(GLASS);
        gb.setCornerRadius(dp(24));
        gb.setStroke(1, Color.parseColor("#22FFFFFF"));
        glass.setBackground(gb);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(0, -1, 1f);
        glp.setMargins(0, dp(10), dp(10), dp(10));
        row.addView(glass, glp);

        if (mode == MODE_MINI) glass.addView(buildMini(), new FrameLayout.LayoutParams(-1, -1));
        else if (mode == MODE_WEB) glass.addView(buildWeb(), new FrameLayout.LayoutParams(-1, -1));
        else glass.addView(buildFull(), new FrameLayout.LayoutParams(-1, -1));
    }

    private final View.OnTouchListener dragger = new View.OnTouchListener() {
        float startX; int startW;
        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = e.getRawX(); startW = lp.width; return true;
                case MotionEvent.ACTION_MOVE:
                    lp.width = clampW(startW - Math.round(e.getRawX() - startX));
                    try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    prefs().edit().putInt(mode == MODE_WEB ? "web_w" : "panel_w", lp.width).apply();
                    return true;
            }
            return false;
        }
    };

    // ---------- A: 기본 패널 ----------
    private View buildFull() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(14), dp(14), dp(10));

        col.addView(buildPhotoCard(), new LinearLayout.LayoutParams(-1, 0, 4.2f));

        View media = buildMedia();
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = dp(12);
        col.addView(media, mlp);

        LinearLayout quick = new LinearLayout(this);
        quick.addView(chip("▶  유튜브", v -> openWeb("https://m.youtube.com/")), new LinearLayout.LayoutParams(0, -2, 1f));
        View sp = new View(this);
        quick.addView(sp, new LinearLayout.LayoutParams(dp(10), 1));
        quick.addView(chip("🌐  인터넷", v -> openWeb(prefs().getString("web_home", "https://m.naver.com/"))), new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-1, -2);
        qlp.topMargin = dp(10);
        col.addView(quick, qlp);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        PackageManager pm = getPackageManager();
        for (int r = 0; r < 2; r++) {
            LinearLayout line = new LinearLayout(this);
            for (int c = 0; c < 3; c++) {
                line.addView(tile(r * 3 + c, pm, true), new LinearLayout.LayoutParams(0, -1, 1f));
            }
            grid.addView(line, new LinearLayout.LayoutParams(-1, 0, 1f));
        }
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, 0, 3.3f);
        gl.topMargin = dp(8);
        col.addView(grid, gl);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        if (building4Side) {
            bottom.addView(textBtn("⤢  네비 크게", v -> exitSplit()));
        } else {
            bottom.addView(textBtn("◀  접기", v -> setMode(MODE_MINI)));
            bottom.addView(textBtn("●  숨기기", v -> setMode(MODE_BUBBLE)));
            bottom.addView(textBtn("━  곡명", v -> setMode(MODE_STRIP)));
        }
        View fill = new View(this);
        bottom.addView(fill, new LinearLayout.LayoutParams(0, 1, 1f));
        bottom.addView(textBtn("전체 앱", v -> openHome("apps", -1)));
        bottom.addView(textBtn("⚙", v -> openHome("settings", -1)));
        col.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
        return col;
    }

    private View buildPhotoCard() {
        FrameLayout card = new FrameLayout(this);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(18)); }
        });
        card.setClipToOutline(true);
        card.setBackgroundColor(Color.parseColor("#20262F"));
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
                new int[]{Color.TRANSPARENT, Color.TRANSPARENT, Color.parseColor("#B0000000")}));
        card.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        clock.setPadding(dp(16), 0, dp(16), dp(12));
        TextClock t = new TextClock(this);
        t.setFormat24Hour("HH:mm");
        t.setFormat12Hour("HH:mm");
        t.setTextColor(Color.WHITE);
        t.setTextSize(44);
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        clock.addView(t);
        TextClock d = new TextClock(this);
        d.setFormat24Hour("M월 d일 EEEE");
        d.setFormat12Hour("M월 d일 EEEE");
        d.setTextColor(Color.parseColor("#E6ECF5"));
        d.setTextSize(14);
        clock.addView(d);
        card.addView(clock, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));

        card.setOnClickListener(v -> nextPhoto());
        card.setOnLongClickListener(v -> { openHome("settings", -1); return true; });
        showA = true;
        photoIdx = Math.max(-1, photoIdx - 1);
        ui.post(this::nextPhoto);
        return card;
    }

    // ---------- B: 접은 패널 ----------
    private View buildMini() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(8), dp(16), dp(8), dp(12));

        ImageView face = new ImageView(this);
        face.setScaleType(ImageView.ScaleType.CENTER_CROP);
        face.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
        });
        face.setClipToOutline(true);
        if (!photos.isEmpty()) {
            Bitmap bm = decode(photos.get(Math.max(0, photoIdx) % photos.size()), 400);
            if (bm != null) face.setImageBitmap(bm);
        }
        col.addView(face, new LinearLayout.LayoutParams(dp(76), dp(76)));

        TextClock t = new TextClock(this);
        t.setFormat24Hour("HH:mm");
        t.setFormat12Hour("HH:mm");
        t.setTextColor(Color.WHITE);
        t.setTextSize(24);
        t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = dp(10);
        col.addView(t, tl);

        for (Object[] m : new Object[][]{{"⏮", KeyEvent.KEYCODE_MEDIA_PREVIOUS}, {"⏯", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE}, {"⏭", KeyEvent.KEYCODE_MEDIA_NEXT}}) {
            col.addView(mediaBtn((String) m[0], (Integer) m[1]));
        }
        PackageManager pm = getPackageManager();
        for (int i = 0; i < 3; i++) {
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, dp(78));
            l.topMargin = dp(4);
            col.addView(tile(i, pm, false), l);
        }
        View fill = new View(this);
        col.addView(fill, new LinearLayout.LayoutParams(1, 0, 1f));
        if (web != null) col.addView(textBtn("▶ 영상", v -> setMode(MODE_WEB)));
        col.addView(textBtn("● 숨기기", v -> setMode(MODE_BUBBLE)));
        col.addView(textBtn("펼치기 ▶", v -> setMode(MODE_FULL)));
        return col;
    }

    // ---------- D: 동그라미 (네비만 볼 때) ----------
    private View buildBubble() {
        FrameLayout b = new FrameLayout(this);
        b.setPadding(dp(4), dp(4), dp(4), dp(4));
        ImageView face = new ImageView(this);
        face.setScaleType(ImageView.ScaleType.CENTER_CROP);
        face.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
        });
        face.setClipToOutline(true);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.parseColor("#CC111620"));
        ring.setStroke(dp(2), Color.parseColor("#AAFFFFFF"));
        b.setBackground(ring);
        if (!photos.isEmpty()) {
            Bitmap bm = decode(photos.get(Math.max(0, photoIdx) % photos.size()), 300);
            if (bm != null) face.setImageBitmap(bm);
        }
        face.setAlpha(0.92f);
        b.addView(face, new FrameLayout.LayoutParams(-1, -1));
        // 누르면 펼치고, 끌면 위아래로 옮김
        b.setOnTouchListener(moveOrOpen());
        return b;
    }

    private View.OnTouchListener moveOrOpen() {
        return (new View.OnTouchListener() {
            float sy; int startY; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: sy = e.getRawY(); startY = lp.y; moved = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        int dy = Math.round(e.getRawY() - sy);
                        if (Math.abs(dy) > dp(8)) moved = true;
                        if (moved) {
                            lp.y = Math.max(statusBarH(), Math.min(startY + dy, screenH() - dp(90)));
                            try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) prefs().edit().putInt("bubble_y", lp.y).apply();
                        else setMode(MODE_FULL);
                        return true;
                }
                return false;
            }
        });
    }

    // ---------- E: 얇은 가로 막대 (사진 + 곡명) ----------
    private TextView stripText;
    private TextClock stripClock;

    private View buildStrip() {
        if (mTitle == null) buildMedia();   // 곡 정보 받을 준비
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(5), dp(5), dp(16), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#D9111620"));
        bg.setCornerRadius(dp(25));
        bg.setStroke(dp(1), Color.parseColor("#33FFFFFF"));
        bar.setBackground(bg);
        ImageView face = new ImageView(this);
        face.setScaleType(ImageView.ScaleType.CENTER_CROP);
        face.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
        });
        face.setClipToOutline(true);
        if (!photos.isEmpty()) {
            Bitmap bm = decode(photos.get(Math.max(0, photoIdx) % photos.size()), 200);
            if (bm != null) face.setImageBitmap(bm);
        }
        bar.addView(face, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView note = new TextView(this);
        note.setText("♪");
        note.setTextColor(Color.parseColor("#8FB4FF"));
        note.setTextSize(15);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2);
        nl.leftMargin = dp(12);
        nl.rightMargin = dp(8);
        bar.addView(note, nl);
        stripText = new TextView(this);
        stripText.setTextColor(Color.WHITE);
        stripText.setTextSize(15);
        stripText.setSingleLine(true);
        stripText.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        stripText.setMarqueeRepeatLimit(-1);
        stripText.setHorizontallyScrolling(true);
        stripText.setSelected(true);
        bar.addView(stripText, new LinearLayout.LayoutParams(0, -2, 1f));
        stripClock = new TextClock(this);
        stripClock.setFormat24Hour("HH:mm");
        stripClock.setFormat12Hour("HH:mm");
        stripClock.setTextColor(Color.WHITE);
        stripClock.setTextSize(15);
        bar.addView(stripClock, new LinearLayout.LayoutParams(0, -2, 1f));
        bar.setOnTouchListener(moveOrOpen());
        updateStrip();
        return bar;
    }

    private void updateStrip() {
        if (stripText == null || mTitle == null) return;
        String t = String.valueOf(mTitle.getText());
        boolean none = t.isEmpty() || t.equals("재생 중인 음악 없음") || t.equals("음악");
        String a = mArtist == null ? "" : String.valueOf(mArtist.getText());
        if (!none && !a.isEmpty() && !a.startsWith("눌러서")) t = t + "  ·  " + a;
        if (!t.equals(String.valueOf(stripText.getText()))) stripText.setText(t);
        stripText.setVisibility(none ? View.GONE : View.VISIBLE);
        if (stripClock != null) stripClock.setVisibility(none ? View.VISIBLE : View.GONE);
    }

    // ---------- C: 패널 안 유튜브 / 인터넷 ----------
    private View buildWeb() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), dp(10), dp(10), dp(10));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(chip("◀  홈", v -> setMode(MODE_FULL)));
        View s1 = new View(this);
        top.addView(s1, new LinearLayout.LayoutParams(dp(8), 1));
        top.addView(chip("←", v -> { if (web != null && web.canGoBack()) web.goBack(); }));
        View s2 = new View(this);
        top.addView(s2, new LinearLayout.LayoutParams(dp(8), 1));
        if (building4Side) top.addView(chip("⤢  크게", v -> webBig()));
        else top.addView(chip("작게", v -> setMode(MODE_MINI)));
        View fill = new View(this);
        top.addView(fill, new LinearLayout.LayoutParams(0, 1, 1f));
        TextClock t = new TextClock(this);
        t.setFormat24Hour("HH:mm");
        t.setFormat12Hour("HH:mm");
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        top.addView(t);
        col.addView(top, new LinearLayout.LayoutParams(-1, -2));

        if (web == null) {
            web = new WebView(getApplicationContext());
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setLoadWithOverviewMode(true);
            s.setUseWideViewPort(true);
            web.setBackgroundColor(Color.BLACK);
            web.addJavascriptInterface(new WebBridge(), "GT");
            web.setOnTouchListener((v, ev) -> { lastWebTouch = android.os.SystemClock.uptimeMillis(); return false; });
            web.setWebViewClient(new WebViewClient() {
                @Override public void onPageStarted(WebView v, String url, Bitmap fav) {
                    v.evaluateJavascript(KEEP_JS, null);
                }
                @Override
                public android.webkit.WebResourceResponse shouldInterceptRequest(WebView v, android.webkit.WebResourceRequest req) {
                    String u = req.getUrl().toString();
                    for (String bad : AD_HOSTS) if (u.contains(bad))
                        return new android.webkit.WebResourceResponse("text/plain", "utf-8", new java.io.ByteArrayInputStream(new byte[0]));
                    return null;
                }
                @Override public void onPageFinished(WebView v, String url) {
                    prefs().edit().putString("web_last", url).apply();
                    v.evaluateJavascript(KEEP_JS, null);
                    v.evaluateJavascript(AD_JS, null);
                }
            });
            web.setWebChromeClient(new WebChromeClient() {
                @Override public void onShowCustomView(View v, CustomViewCallback cb) { enterVideoFull(v, cb); }
                @Override public void onHideCustomView() { exitVideoFull(false); }
            });
            web.loadUrl(prefs().getString("web_last", "https://m.youtube.com/"));
        } else {
            if (web.getParent() != null) ((android.view.ViewGroup) web.getParent()).removeView(web);
            removeHolder();
            web.onResume();
        }
        FrameLayout box = new FrameLayout(this);
        box.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14)); }
        });
        box.setClipToOutline(true);
        box.addView(web, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, 0, 1f);
        bl.topMargin = dp(8);
        col.addView(box, bl);
        return col;
    }

    private View buildMedia() {
        LinearLayout media = new LinearLayout(this);
        media.setGravity(Gravity.CENTER_VERTICAL);
        media.setPadding(dp(10), dp(10), dp(6), dp(10));
        media.setBackground(round(CARD, dp(18)));
        mArt = new ImageView(this);
        mArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mArt.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#7C4DFF"), Color.parseColor("#4F8BFF")}));
        mArt.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10)); }
        });
        mArt.setClipToOutline(true);
        media.addView(mArt, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(dp(12), 0, dp(4), 0);
        mTitle = new TextView(this);
        mTitle.setTextColor(Color.WHITE);
        mTitle.setTextSize(15);
        mTitle.setSingleLine(true);
        mTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(mTitle);
        mArtist = new TextView(this);
        mArtist.setTextColor(DIM);
        mArtist.setTextSize(12);
        mArtist.setSingleLine(true);
        mArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(mArtist);
        media.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
        media.addView(mediaBtn("⏮", KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        mPlay = mediaBtn("⏯", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        media.addView(mPlay);
        media.addView(mediaBtn("⏭", KeyEvent.KEYCODE_MEDIA_NEXT));
        txt.setOnClickListener(v -> {
            if (webIsMedia()) { setMode(MODE_WEB); return; }
            if (!mediaAllowed()) { openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS); return; }
            ComponentName app = resolve(this, prefs().getString("music_app", null));
            if (app != null) launchFull(this, app); else openHome("music", -1);
        });
        txt.setOnLongClickListener(v -> { openHome("music", -1); return true; });
        refreshMedia();
        return media;
    }

    private boolean mediaAllowed() {
        String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(getPackageName());
    }

    private void openSettings(String action) {
        hidePanel();
        try { startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception ignored) { }
    }

    private final MediaController.Callback ctrlCb = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { ui.post(PanelService.this::refreshMedia); }
        @Override public void onPlaybackStateChanged(PlaybackState st) { ui.post(PanelService.this::refreshMedia); }
        @Override public void onSessionDestroyed() { ui.post(() -> { pickController(); refreshMedia(); }); }
    };

    private void setupMedia() {
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            ComponentName me = new ComponentName(this, MediaListener.class);
            msm.addOnActiveSessionsChangedListener(list -> ui.post(() -> { pickController(); refreshMedia(); }), me, ui);
            pickController();
        } catch (Throwable ignored) {
            ui.postDelayed(this::setupMedia, 30000); // 권한 켜질 때까지 가끔 다시 시도
        }
    }

    private void pickController() {
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            List<MediaController> list = msm.getActiveSessions(new ComponentName(this, MediaListener.class));
            MediaController best = null;
            for (MediaController c : list) {
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
                if (best == null) best = c;
            }
            if (ctrl != null) { try { ctrl.unregisterCallback(ctrlCb); } catch (Exception ignored) { } }
            ctrl = best;
            if (ctrl != null) ctrl.registerCallback(ctrlCb, ui);
        } catch (Throwable ignored) {
            ctrl = null;
        }
    }

    private void refreshMedia() { refreshMedia0(); updateStrip(); }

    private void refreshMedia0() {
        if (mTitle == null) return;
        if (webIsMedia()) {
            mTitle.setText(webTitle.isEmpty() ? "유튜브" : webTitle);
            mArtist.setText("패널 유튜브");
            mArt.setImageBitmap(web.getFavicon());
            if (mPlay != null) mPlay.setText(webPlaying ? "⏸" : "▶");
            return;
        }
        if (!mediaAllowed()) {
            mTitle.setText("음악");
            mArtist.setText("눌러서 곡 제목 보이게 하기");
            return;
        }
        MediaMetadata m = ctrl == null ? null : ctrl.getMetadata();
        String title = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null && m != null) artist = m.getString(MediaMetadata.METADATA_KEY_ALBUM);
        mTitle.setText(title == null || title.isEmpty() ? "재생 중인 음악 없음" : title);
        String app = "";
        if (ctrl != null) {
            try { app = String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(ctrl.getPackageName(), 0))); }
            catch (Exception ignored) { }
        }
        mArtist.setText(artist == null || artist.isEmpty() ? app : artist + (app.isEmpty() ? "" : "  ·  " + app));
        Bitmap art = null;
        if (m != null) {
            art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        }
        mArt.setImageBitmap(art);
        PlaybackState st = ctrl == null ? null : ctrl.getPlaybackState();
        boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        if (mPlay != null) mPlay.setText(playing ? "⏸" : "▶");
    }

    // ---------- 영상 전체 화면 ↔ 원래대로 ----------
    private FrameLayout videoRoot;
    private WebChromeClient.CustomViewCallback videoCb;

    private void enterVideoFull(View v, WebChromeClient.CustomViewCallback cb) {
        exitVideoFull(true);
        videoCb = cb;
        videoRoot = new FrameLayout(this) {
            @Override public boolean dispatchTouchEvent(MotionEvent ev) {
                lastWebTouch = android.os.SystemClock.uptimeMillis();
                return super.dispatchTouchEvent(ev);
            }
        };
        videoRoot.setBackgroundColor(Color.BLACK);
        if (v.getParent() != null) ((android.view.ViewGroup) v.getParent()).removeView(v);
        videoRoot.addView(v, new FrameLayout.LayoutParams(-1, -1));
        TextView back = new TextView(this);
        back.setText("⤡  원래대로");
        back.setTextColor(Color.WHITE);
        back.setTextSize(15);
        back.setPadding(dp(18), dp(10), dp(18), dp(10));
        back.setBackground(round(Color.parseColor("#88000000"), dp(22)));
        back.setOnClickListener(x -> exitVideoFull(true));
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        bl.setMargins(0, dp(16), dp(16), 0);
        videoRoot.addView(back, bl);
        videoRoot.setFocusableInTouchMode(true);
        videoRoot.setOnKeyListener((x, code, ev) -> {
            if (code == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP) { exitVideoFull(true); return true; }
            return code == KeyEvent.KEYCODE_BACK;
        });
        WindowManager.LayoutParams fl = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE);
        try {
            wm.addView(videoRoot, fl);
            videoRoot.requestFocus();
        } catch (Exception e) {
            videoRoot = null;
            if (cb != null) cb.onCustomViewHidden();
            videoCb = null;
        }
    }

    /** tellWeb=true 면 웹페이지에도 전체 화면이 끝났다고 알려 줌 */
    private void exitVideoFull(boolean tellWeb) {
        if (videoRoot == null) return;
        FrameLayout r = videoRoot;
        videoRoot = null;
        try { wm.removeView(r); } catch (Exception ignored) { }
        r.removeAllViews();
        WebChromeClient.CustomViewCallback cb = videoCb;
        videoCb = null;
        if (webBigOn) { webBigOn = false; ui.post(() -> { if (sideHost != null) buildSide(); }); }
        if (tellWeb && cb != null) { try { cb.onCustomViewHidden(); } catch (Exception ignored) { } }
    }

    private static final String[] AD_HOSTS = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
            "/pagead/", "/api/stats/ads", "/get_midroll_", "/ptracking", "adservice.google", "/ad_status",
            "imasdk.googleapis.com", "/youtubei/v1/log_event"
    };

    private static final String AD_JS = "(function(){if(window.__sslAd)return;window.__sslAd=1;"
            + "setInterval(function(){try{"
            + "var p=document.querySelector('.ad-showing,.ad-interrupting');var v=document.querySelector('video');"
            + "if(p&&v){v.muted=true;if(isFinite(v.duration)&&v.duration>0)v.currentTime=v.duration;v.playbackRate=16;}"
            + "else if(v&&v.playbackRate==16){v.playbackRate=1;v.muted=false;}"
            + "var b=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytp-ad-skip-button-container button');if(b)b.click();"
            + "document.querySelectorAll('ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer,ad-slot-renderer,ytm-promoted-video-renderer,ytm-ad-slot-renderer,.ytd-ad-slot-renderer,ytm-display-ad-renderer').forEach(function(e){e.remove()});"
            + "}catch(e){}},400);})();";

    // 페이지가 "안 보인다"고 느끼지 않게 해서, 패널을 접거나 홈으로 나가도 소리가 계속 나오게
    private static final String KEEP_JS = "(function(){if(window.__sslKeep)return;window.__sslKeep=1;"
            + "try{Object.defineProperty(document,'hidden',{get:function(){return false},configurable:true});"
            + "Object.defineProperty(document,'visibilityState',{get:function(){return 'visible'},configurable:true});"
            + "Object.defineProperty(document,'webkitHidden',{get:function(){return false},configurable:true});}catch(e){}"
            + "var stop=function(e){e.stopImmediatePropagation();};"
            + "window.addEventListener('visibilitychange',stop,true);document.addEventListener('visibilitychange',stop,true);"
            + "window.addEventListener('webkitvisibilitychange',stop,true);"
            + "window.addEventListener('pagehide',stop,true);window.addEventListener('blur',stop,true);"
            + "setInterval(function(){try{var v=document.querySelector('video');"
            + "if(window.GT)GT.st(!!(v&&!v.paused&&!v.ended),(document.title||'').replace(/ - YouTube$/,''));"
            + "}catch(e){}},1500);})();";

    public class WebBridge {
        @android.webkit.JavascriptInterface
        public void st(boolean playing, String title) {
            ui.post(() -> {
                boolean ch = playing != webPlaying || !String.valueOf(title).equals(webTitle);
                boolean was = webPlaying;
                webPlaying = playing; webTitle = title;
                long now = android.os.SystemClock.uptimeMillis();
                if (playing) { lastPlayAt = now; userPaused = false; }
                // 네비 안내 등 다른 앱 때문에 멈춘 거면 (내가 멈춘 게 아니면) 잠시 뒤 한 번만 다시 재생
                if (was && !playing && !userPaused && now - lastWebTouch > 2500 && now - lastAutoResume > 8000) {
                    lastAutoResume = now;
                    ui.postDelayed(() -> {
                        if (!webPlaying && !userPaused && web != null) web.evaluateJavascript(RESUME_JS, null);
                    }, 2500);
                }
                if (ch) refreshMedia();
            });
        }
    }

    private long lastPlayAt = -100000, lastWebTouch = -100000, lastAutoResume = -100000;
    private boolean userPaused = false;
    private boolean bgKeep = false;   // 화면 바뀔 때 듣고 있던 유튜브는 계속 이어서 재생
    private static final String RESUME_JS = "(function(){try{var v=document.querySelector('video');if(!v||v.ended)return;"
            + "if(v.paused){v.play();}else{var t=v.currentTime;setTimeout(function(){if(v.currentTime==t&&!v.paused){v.pause();v.play();}},700);}"
            + "}catch(e){}})();";

    private void markTransition() {
        if (web == null) return;
        if (!webPlaying && android.os.SystemClock.uptimeMillis() - lastPlayAt > 3500) { bgKeep = false; return; }
        bgKeep = true;
        for (int d : new int[]{500, 1300, 2500, 4000, 6000}) ui.postDelayed(this::resumeWeb, d);
    }

    private void resumeWeb() {
        if (web != null && bgKeep) web.evaluateJavascript(RESUME_JS, null);
    }

    private boolean webPlaying = false;
    private String webTitle = null;
    private FrameLayout holder;

    /** 패널 밖에서도 영상·음악이 끊기지 않게 작은 보이지 않는 창에 맡겨 둠 */
    private void parkWeb() {
        if (web == null) return;
        if (sideHost != null && mode == MODE_WEB && isInside(web, sideHost)) return;
        if (holder != null && web.getParent() == holder) return;
        try {
            if (holder == null) {
                holder = new FrameLayout(this);
                WindowManager.LayoutParams h = new WindowManager.LayoutParams(2, 2,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                h.gravity = Gravity.TOP | Gravity.START;
                h.x = 0; h.y = 0;
                holder.setAlpha(0.01f);
                wm.addView(holder, h);
            }
            if (web.getParent() != null) ((android.view.ViewGroup) web.getParent()).removeView(web);
            holder.addView(web, new FrameLayout.LayoutParams(dp(320), dp(240)));
            web.onResume();
            web.resumeTimers();
        } catch (Exception ignored) { }
    }

    private void removeHolder() {
        if (holder == null) return;
        try { holder.removeAllViews(); wm.removeView(holder); } catch (Exception ignored) { }
        holder = null;
    }

    /** 음악 카드가 패널 유튜브를 다룰 차례인지 */
    private boolean webIsMedia() {
        if (web == null || webTitle == null) return false;
        PlaybackState st = ctrl == null ? null : ctrl.getPlaybackState();
        boolean other = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        return webPlaying || !other && ctrl == null;
    }

    private void webMedia(int code) {
        if (web == null) return;
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { bgKeep = !webPlaying; if (webPlaying) { lastPlayAt = -100000; userPaused = true; } }
        String js = code == KeyEvent.KEYCODE_MEDIA_NEXT
                ? "var v=document.querySelector('video');if(v&&isFinite(v.duration))v.currentTime=v.duration-0.3;"
                : code == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                ? "var v=document.querySelector('video');if(v)v.currentTime=0;"
                : "var v=document.querySelector('video');if(v){if(v.paused)v.play();else v.pause();}";
        web.evaluateJavascript("(function(){try{" + js + "}catch(e){}})();", null);
        ui.postDelayed(() -> { if (web != null) web.evaluateJavascript(
                "(function(){var v=document.querySelector('video');if(window.GT)GT.st(!!(v&&!v.paused&&!v.ended),(document.title||'').replace(/ - YouTube$/,''));})();", null); }, 400);
    }

    private void openWeb(String url) {
        prefs().edit().putString("web_last", url).apply();
        if (web != null) web.loadUrl(url);
        setMode(MODE_WEB);
    }

    void setMode(int m) {
        if (sideHost != null && splitLayout(this)) {
            mode = (m == MODE_WEB) ? MODE_WEB : MODE_FULL;
            buildSide();
            return;
        }
        mode = m;
        boolean was = attached;
        hidePanel();
        if (was) showPanel();
    }

    // ------------------------------------------------------------------
    // 작은 부품들
    // ------------------------------------------------------------------

    private GradientDrawable round(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    private TextView chip(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(9), dp(14), dp(9));
        t.setBackground(round(CARD, dp(20)));
        t.setOnClickListener(l);
        return t;
    }

    private TextView textBtn(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(DIM);
        t.setTextSize(14);
        t.setPadding(dp(10), dp(10), dp(10), dp(10));
        t.setOnClickListener(l);
        return t;
    }

    private TextView mediaBtn(String s, int code) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(20);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(44)));
        t.setOnClickListener(v -> {
            if (webIsMedia()) { webMedia(code); return; }
            if (ctrl != null) {
                try {
                    MediaController.TransportControls tc = ctrl.getTransportControls();
                    if (code == KeyEvent.KEYCODE_MEDIA_NEXT) { tc.skipToNext(); return; }
                    if (code == KeyEvent.KEYCODE_MEDIA_PREVIOUS) { tc.skipToPrevious(); return; }
                    PlaybackState st = ctrl.getPlaybackState();
                    if (st != null && st.getState() == PlaybackState.STATE_PLAYING) tc.pause(); else tc.play();
                    return;
                } catch (Exception ignored) { }
            }
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        });
        return t;
    }

    private View tile(int slot, PackageManager pm, boolean label) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        String raw = prefs().getString("slot_" + slot, null);
        if (raw != null && raw.startsWith("split:")) return splitTile(t, slot, raw, pm, label);
        ComponentName cn = resolve(this, raw);
        ActivityInfo info = null;
        if (cn != null) { try { info = pm.getActivityInfo(cn, 0); } catch (Exception ignored) { } }
        if (info == null) {
            TextView plus = new TextView(this);
            plus.setText("+");
            plus.setTextSize(24);
            plus.setTextColor(Color.parseColor("#55FFFFFF"));
            t.addView(plus);
            t.setOnClickListener(v -> openHome("pick", slot));
            return t;
        }
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(info.loadIcon(pm));
        t.addView(icon, new LinearLayout.LayoutParams(dp(50), dp(50)));
        if (label) {
            TextView l = new TextView(this);
            l.setText(info.loadLabel(pm));
            l.setTextColor(Color.parseColor("#C9D1DC"));
            l.setTextSize(12);
            l.setSingleLine(true);
            l.setEllipsize(android.text.TextUtils.TruncateAt.END);
            l.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
            ll.topMargin = dp(6);
            t.addView(l, ll);
        }
        final ComponentName target = cn;
        t.setOnClickListener(v -> { if (sideHost != null && splitLayout(this)) launchRight(target); else launchFull(this, target); });
        t.setOnLongClickListener(v -> { openHome("pick", slot); return true; });
        return t;
    }

    // ------------------------------------------------------------------
    // 분할 칸: 두 앱을 저장해 두었다가 한 번에 반반으로
    // ------------------------------------------------------------------

    private View splitTile(LinearLayout t, int slot, String raw, PackageManager pm, boolean label) {
        String[] ab = raw.substring(6).split("\\|");
        final ComponentName a = ab.length > 0 ? resolve(this, ab[0]) : null;
        final ComponentName b = ab.length > 1 ? resolve(this, ab[1]) : null;
        LinearLayout pair = new LinearLayout(this);
        pair.setGravity(Gravity.CENTER);
        pair.setPadding(dp(7), dp(5), dp(7), dp(5));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.parseColor("#22FFFFFF"));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.parseColor("#33FFFFFF"));
        pair.setBackground(bg);
        pair.addView(splitIcon(a, pm), new LinearLayout.LayoutParams(dp(34), dp(34)));
        View bar = new View(this);
        bar.setBackgroundColor(Color.parseColor("#88FFFFFF"));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(2), dp(26));
        bl.leftMargin = dp(5); bl.rightMargin = dp(5);
        pair.addView(bar, bl);
        pair.addView(splitIcon(b, pm), new LinearLayout.LayoutParams(dp(34), dp(34)));
        t.addView(pair, new LinearLayout.LayoutParams(-2, dp(50)));
        if (label) {
            TextView l = new TextView(this);
            l.setText(shortName(a, pm) + " · " + shortName(b, pm));
            l.setTextColor(Color.parseColor("#C9D1DC"));
            l.setTextSize(12);
            l.setSingleLine(true);
            l.setEllipsize(android.text.TextUtils.TruncateAt.END);
            l.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
            ll.topMargin = dp(6);
            t.addView(l, ll);
        }
        t.setOnClickListener(v -> { if (a != null && b != null) launchSplit(a, b); else openHome("pick", slot); });
        t.setOnLongClickListener(v -> { openHome("pick", slot); return true; });
        return t;
    }

    private ImageView splitIcon(ComponentName cn, PackageManager pm) {
        ImageView iv = new ImageView(this);
        try { iv.setImageDrawable(pm.getActivityInfo(cn, 0).loadIcon(pm)); } catch (Exception e) { iv.setAlpha(0.3f); }
        return iv;
    }

    private String shortName(ComponentName cn, PackageManager pm) {
        try {
            String n = pm.getActivityInfo(cn, 0).loadLabel(pm).toString().trim();
            int sp = n.indexOf(' ');
            return sp > 0 ? n.substring(0, sp) : n;
        } catch (Exception e) { return "?"; }
    }

    /** 화면이 지금 반반으로 나뉘어 있는지 (가운데 막대가 있는지) */
    boolean inSplit() {
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows())
                if (w.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER) return true;
        } catch (Exception ignored) { }
        return false;
    }

    void exitSplit() {
        if (inSplit()) performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
    }

    private void launchSplit(ComponentName a, ComponentName b) {
        splitting = true;
        wantShow = false;
        hidePanel();
        int wait = 0;
        if (inSplit()) { performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN); wait = 700; }
        ui.postDelayed(() -> {
            launchFull(this, a);                                   // 왼쪽 앱을 먼저 맨 앞으로
            ui.postDelayed(() -> {
                performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN); // 그 앱을 왼쪽 반으로
                ui.postDelayed(() -> {
                    Intent i = new Intent(Intent.ACTION_MAIN);
                    i.addCategory(Intent.CATEGORY_LAUNCHER);
                    i.setComponent(b);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    try { startActivity(i); } catch (Exception ignored) { }
                    ui.postDelayed(() -> splitting = false, 1500);
                }, 900);
            }, 800);
        }, wait);
    }
    private boolean splitting = false, wasSplit = false;

    // ------------------------------------------------------------------
    // 반반 방식: 왼쪽 네비, 오른쪽 싼슬라 화면(SideActivity)
    // ------------------------------------------------------------------

    static boolean splitLayout(Context c) {
        return "split".equals(c.getSharedPreferences("gthome", MODE_PRIVATE).getString("layout", "split"));
    }

    private FrameLayout sideHost;
    private boolean building4Side = false, webBigOn = false;
    private String rightPkg = null;
    private ComponentName rightApp = null;
    private FrameLayout pill;

    private static boolean isInside(View v, View parent) {
        android.view.ViewParent p = v.getParent();
        while (p != null) { if (p == parent) return true; p = p.getParent(); }
        return false;
    }

    void attachSide(FrameLayout host) {
        hidePanel();
        hidePill();
        boolean same = sideHost == host && host.getChildCount() > 0;
        sideHost = host;
        if (mode != MODE_WEB) mode = MODE_FULL;
        if (!same) buildSide();
        // 오른쪽이 잠깐 가려졌다 돌아오면, 듣던 유튜브를 한 번만 이어서 재생
        if (web != null && android.os.SystemClock.uptimeMillis() - lastPlayAt < 5000) {
            bgKeep = true;
            ui.postDelayed(this::resumeWeb, 900);
            ui.postDelayed(() -> bgKeep = false, 1200);
        }
    }

    void detachSide(FrameLayout host) {
        if (sideHost != host) return;
        mode = MODE_FULL;
        parkWeb();
        host.removeAllViews();
        sideHost = null;
    }

    boolean sideBack() {
        if (mode == MODE_WEB) {
            if (web != null && web.canGoBack()) web.goBack(); else setMode(MODE_FULL);
            return true;
        }
        return true;
    }

    private void buildSide() {
        if (sideHost == null) return;
        if (mode != MODE_WEB) parkWeb();
        else if (web != null && web.getParent() != null && !isInside(web, sideHost)) {
            ((android.view.ViewGroup) web.getParent()).removeView(web);
        }
        sideHost.removeAllViews();
        FrameLayout glass = new FrameLayout(this);
        GradientDrawable gb = new GradientDrawable();
        gb.setColor(GLASS);
        gb.setCornerRadius(dp(24));
        gb.setStroke(1, Color.parseColor("#22FFFFFF"));
        glass.setBackground(gb);
        building4Side = true;
        try {
            glass.addView(mode == MODE_WEB ? buildWeb() : buildFull(), new FrameLayout.LayoutParams(-1, -1));
        } finally { building4Side = false; }
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(-1, -1);
        gl.setMargins(dp(8), dp(8), dp(8), dp(8));
        sideHost.addView(glass, gl);
    }

    /** 집 버튼: 왼쪽 네비 + 오른쪽 싼슬라 */
    void goSplitHome() {
        ComponentName nav = navApp(this);
        if (nav == null) return;
        hidePill();
        if (inSplit()) { openSide(); return; }
        splitWithNav(nav, new ComponentName(this, SideActivity.class));
    }

    /** 네비와 다른 화면을 반반으로. 설정에서 자리를 바꾸면 순서가 바뀜 */
    private void splitWithNav(ComponentName nav, ComponentName other) {
        if (prefs().getBoolean("split_swap", false)) launchSplit(other, nav);
        else launchSplit(nav, other);
    }

    private void openSide() {
        Intent i = new Intent(this, SideActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    /** 패널에서 고른 앱을 오른쪽 칸에 띄움 */
    private void launchRight(ComponentName cn) {
        rightApp = cn;
        rightPkg = cn.getPackageName();
        if (!inSplit()) { ComponentName nav = navApp(this); if (nav != null) { splitWithNav(nav, cn); return; } }
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(cn);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    /** 오른쪽 앱을 전체 화면으로 */
    private void rightBig() {
        if (rightApp == null) return;
        hidePill();
        if (inSplit()) performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
        final ComponentName cn = rightApp;
        ui.postDelayed(() -> launchFull(this, cn), 700);
    }

    /** 전체 화면 앱을 다시 오른쪽 반으로 */
    private void rightBack() {
        ComponentName nav = navApp(this);
        if (rightApp == null || nav == null) return;
        hidePill();
        splitWithNav(nav, rightApp);
    }

    private void webBig() {
        if (web == null) return;
        webBigOn = true;
        if (web.getParent() != null) ((android.view.ViewGroup) web.getParent()).removeView(web);
        enterVideoFull(web, null);
        if (android.os.SystemClock.uptimeMillis() - lastPlayAt < 3000) {
            bgKeep = true; ui.postDelayed(this::resumeWeb, 600); ui.postDelayed(() -> bgKeep = false, 900);
        }
    }

    private void onSplitEvent(String pkg, CharSequence cls) {
        if (attached) hidePanel();
        ComponentName nav = navApp(this);
        if (pkg.equals(getPackageName()) || (nav != null && pkg.equals(nav.getPackageName()))) {
            if (!pkg.equals(getPackageName()) || cls == null || !cls.toString().contains("Video")) hidePill();
            return;
        }
        if (!isBigWindow(pkg, cls)) return;
        if (pkg.equals(rightPkg)) showPill(); else hidePill();
    }

    private boolean pillSplit = false;

    private void showPill() {
        boolean split = inSplit();
        hidePill();
        pillSplit = split;
        pill = new FrameLayout(this);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(4), dp(6), dp(4));
        row.setBackground(round(Color.parseColor("#CC111620"), dp(22)));
        if (split) {
            row.addView(pillBtn("⤢  크게", v -> rightBig()));
            row.addView(pillBtn("◧  패널", v -> { hidePill(); openSide(); }));
        } else {
            row.addView(pillBtn("◧  반반으로", v -> rightBack()));
        }
        pill.addView(row, new FrameLayout.LayoutParams(-2, -2));
        WindowManager.LayoutParams pl = new WindowManager.LayoutParams(-2, -2,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        pl.gravity = Gravity.BOTTOM | Gravity.END;
        pl.x = dp(16);
        pl.y = dp(16);
        try { wm.addView(pill, pl); } catch (Exception e) { pill = null; }
    }

    private TextView pillBtn(String s, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14);
        t.setPadding(dp(14), dp(9), dp(14), dp(9));
        t.setOnClickListener(l);
        return t;
    }

    private void hidePill() {
        if (pill == null) return;
        try { wm.removeView(pill); } catch (Exception ignored) { }
        pill = null;
    }

    private void openHome(String what, int slot) {
        MainActivity.otherScreen = true;
        hidePanel();
        if (splitLayout(this) && inSplit()) {   // 설정·앱 고르기는 반반을 풀고 전체 화면으로
            hidePill();
            performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
            ui.postDelayed(() -> openHomeNow(what, slot), 700);
            return;
        }
        openHomeNow(what, slot);
    }

    private void openHomeNow(String what, int slot) {
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra("mode", what);
        i.putExtra("slot", slot);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    // ------------------------------------------------------------------
    // 앱 열기 (항상 전체 화면)
    // ------------------------------------------------------------------

    static void launchFull(Context c, ComponentName cn) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(cn);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ActivityOptions o = ActivityOptions.makeBasic();
            try {
                Method m = ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
                m.invoke(o, 1); // 전체 화면
            } catch (Throwable ignored) { }
            c.startActivity(i, o.toBundle());
        } catch (Exception e) {
            try { c.startActivity(i); } catch (Exception ignored) { }
        }
    }

    static ComponentName resolve(Context c, String d) {
        if (d == null) return null;
        PackageManager pm = c.getPackageManager();
        if (d.contains("/")) {
            ComponentName cn = ComponentName.unflattenFromString(d);
            if (cn == null) return null;
            try { pm.getActivityInfo(cn, 0); return cn; } catch (Exception e) { return null; }
        }
        Intent li = pm.getLaunchIntentForPackage(d);
        return li == null ? null : li.getComponent();
    }

    static final String[] NAV_DEFAULTS = {"com.nhn.android.nmap", "com.skt.tmap.ku", "com.locnall.KimGiSaNavi", "net.daum.android.map"};

    static ComponentName navApp(Context c) {
        SharedPreferences p = c.getSharedPreferences("gthome", MODE_PRIVATE);
        ComponentName cn = resolve(c, p.getString("nav", null));
        if (cn != null) return cn;
        for (String d : NAV_DEFAULTS) { cn = resolve(c, d); if (cn != null) return cn; }
        return null;
    }

    // ------------------------------------------------------------------
    // 사진
    // ------------------------------------------------------------------

    private long interval() { return prefs().getInt("interval", 15) * 1000L; }

    private final Runnable slideTick = new Runnable() {
        @Override public void run() {
            if (attached && mode == MODE_FULL) nextPhoto();
            ui.postDelayed(this, interval());
        }
    };

    void loadPhotos() {
        photos.clear();
        File dir = new File(getFilesDir(), "album");
        File[] fs = dir.listFiles();
        if (fs != null && fs.length > 0 && prefs().getString("token", null) != null) {
            List<File> l = new ArrayList<>();
            for (File f : fs) if (f.length() > 0 && !f.getName().endsWith(".tmp")) l.add(f);
            Collections.sort(l, (a, b) -> a.getName().compareTo(b.getName()));
            photos.addAll(l);
        }
        if (photos.isEmpty()) {
            photos.add(R.drawable.photo1);
            photos.add(R.drawable.photo2);
            photos.add(R.drawable.photo3);
        }
        if (photoIdx >= photos.size()) photoIdx = -1;
    }

    private void nextPhoto() {
        if (photos.isEmpty() || photoA == null) return;
        photoIdx = (photoIdx + 1) % photos.size();
        Bitmap bm = decode(photos.get(photoIdx), 1280);
        if (bm == null) return;
        ImageView in = showA ? photoA : photoB;
        ImageView out = showA ? photoB : photoA;
        showA = !showA;
        in.setImageBitmap(bm);
        fitTop(in);
        in.animate().alpha(1f).setDuration(1000).start();
        out.animate().alpha(0f).setDuration(1000).start();
    }

    private Bitmap decode(Object src, int max) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            if (src instanceof Integer) BitmapFactory.decodeResource(getResources(), (Integer) src, o);
            else BitmapFactory.decodeFile(((File) src).getPath(), o);
            int s = 1;
            while (o.outWidth / (s * 2) >= max || o.outHeight / (s * 2) >= max) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            if (src instanceof Integer) return BitmapFactory.decodeResource(getResources(), (Integer) src, o2);
            return BitmapFactory.decodeFile(((File) src).getPath(), o2);
        } catch (Throwable t) {
            return null;
        }
    }

    private void fitTop(ImageView iv) {
        Drawable d = iv.getDrawable();
        int vw = iv.getWidth(), vh = iv.getHeight();
        if (d == null || vw == 0 || vh == 0) return;
        int dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        float s = Math.max((float) vw / dw, (float) vh / dh);
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postTranslate((vw - dw * s) / 2f, (vh - dh * s) * 0.3f);
        iv.setImageMatrix(m);
    }

    // ---------- 휴대폰 앨범 동기화 ----------
    private final Runnable syncTick = new Runnable() {
        @Override public void run() {
            syncAlbum();
            ui.postDelayed(this, 10 * 60 * 1000L);
        }
    };

    void syncAlbum() {
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
                JSONObject d = new JSONObject(readAll(c.getInputStream()));
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
}
