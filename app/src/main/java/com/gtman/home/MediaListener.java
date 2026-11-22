package com.gtman.home;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** 곡 제목을 읽고, HUD용으로 네비 안내 알림 글자만 잠깐 기억함 */
public class MediaListener extends NotificationListenerService {
    static volatile String navTitle = "", navText = "";
    static volatile long navAt = 0;
    // 전화: 올인원 블루투스 전화 앱 알림
    static volatile String callText = "";
    static volatile long callAt = 0;

    static boolean isPhone(String pkg) {
        String p = pkg.toLowerCase();
        return p.contains("bt") || p.contains("bluetooth") || p.contains("dialer") || p.contains("incall")
                || p.contains("telecom") || p.contains("phone") || p.contains("contacts");
    }

    private static boolean isNav(String pkg) {
        return pkg.equals("com.nhn.android.nmap") || pkg.equals("com.skt.tmap.ku")
                || pkg.equals("com.locnall.KimGiSaNavi") || pkg.equals("net.daum.android.map");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            Notification n = sbn.getNotification();
            if (Notification.CATEGORY_CALL.equals(n.category) || (isPhone(sbn.getPackageName()) && n.extras != null
                    && n.extras.getCharSequence(Notification.EXTRA_TITLE) != null)) {
                CharSequence t = n.extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence x = n.extras.getCharSequence(Notification.EXTRA_TEXT);
                callText = ((t == null ? "" : t) + " " + (x == null ? "" : x)).trim();
                callAt = System.currentTimeMillis();
                return;
            }
            if (!isNav(sbn.getPackageName())) return;
            Bundle e = sbn.getNotification().extras;
            CharSequence t = e.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence x = e.getCharSequence(Notification.EXTRA_TEXT);
            if (x == null) x = e.getCharSequence(Notification.EXTRA_BIG_TEXT);
            navTitle = t == null ? "" : t.toString();
            navText = x == null ? "" : x.toString();
            navAt = System.currentTimeMillis();
        } catch (Exception ignored) { }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (isNav(sbn.getPackageName())) { navTitle = ""; navText = ""; }
        if (Notification.CATEGORY_CALL.equals(sbn.getNotification().category) || isPhone(sbn.getPackageName())) callText = "";
    }
}
