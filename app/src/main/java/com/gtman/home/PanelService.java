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

    static final int MODE_FULL = 0, MODE_MINI = 1, MODE_WEB = 2;

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
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = e.getPackageName();
        if (p == null) return;
        String pkg = p.toString();
        if (pkg.equals("com.android.systemui") || pkg.contains("inputmethod") || pkg.contains("keyboard")
                || pkg.equals(defaultIme())) return;
        ComponentName nav = navApp(this);
        boolean show = (nav != null && pkg.equals(nav.getPackageName()))
                || (pkg.equals(getPackageName()) && MainActivity.homeMode);
        if (show) showPanel(); else hidePanel();
    }

    private String defaultIme() {
        try {
            String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (s != null && s.contains("/")) return s.substring(0, s.indexOf('/'));
        } catch (Exception ignored) { }
        return "";
    }

    void showPanel() {
        if (prefs().getBoolean("panel_off", false)) { hidePanel(); return; }
        if (attached) return;
        build();
        try { wm.addView(root, lp); attached = true; } catch (Exception ignored) { }
    }

    void hidePanel() {
        if (!attached) return;
        try { wm.removeView(root); } catch (Exception ignored) { }
        attached = false;
        if (web != null) { web.onPause(); }
    }

    void rebuild() {
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
                panelWidth(), screenH() - top,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                (mode == MODE_WEB ? 0 : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = 0;
        lp.y = top;
    }

    // ------------------------------------------------------------------
    // 화면 만들기
    // ------------------------------------------------------------------

    private void build() {
        makeParams();
        root = new FrameLayout(this);

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
        bottom.addView(textBtn("◀  접기", v -> setMode(MODE_MINI)));
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
        col.addView(textBtn("펼치기 ▶", v -> setMode(MODE_FULL)));
        return col;
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
            web.setWebViewClient(new WebViewClient());
            web.setWebChromeClient(new WebChromeClient());
            web.loadUrl(prefs().getString("web_last", "https://m.youtube.com/"));
        } else {
            if (web.getParent() != null) ((android.view.ViewGroup) web.getParent()).removeView(web);
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
            if (!mediaAllowed()) openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        });
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

    private void refreshMedia() {
        if (mTitle == null) return;
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

    private void openWeb(String url) {
        prefs().edit().putString("web_last", url).apply();
        if (web != null) web.loadUrl(url);
        setMode(MODE_WEB);
    }

    void setMode(int m) {
        mode = m;
        if (m != MODE_WEB && web != null) web.onPause();
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
        ComponentName cn = resolve(this, prefs().getString("slot_" + slot, null));
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
        t.setOnClickListener(v -> launchFull(this, target));
        t.setOnLongClickListener(v -> { openHome("pick", slot); return true; });
        return t;
    }

    private void openHome(String what, int slot) {
        hidePanel();
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
