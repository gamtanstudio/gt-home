package com.gtman.home;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** 곡 제목을 읽고, HUD용으로 네비 안내 알림 글자만 잠깐 기억함 */
public class MediaListener extends NotificationListenerService {
    static volatile String navTitle = "", navText = "";
    static volatile long navAt = 0;

    private static boolean isNav(String pkg) {
        return pkg.equals("com.nhn.android.nmap") || pkg.equals("com.skt.tmap.ku")
                || pkg.equals("com.locnall.KimGiSaNavi") || pkg.equals("net.daum.android.map");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
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
    }
}
