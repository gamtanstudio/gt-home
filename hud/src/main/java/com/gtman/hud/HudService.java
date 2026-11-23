package com.gtman.hud;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * 전용 HUD폰 모드: 시동 걸려서 충전이 들어오면 화면을 깨우고 HUD를 앞으로 띄움.
 */
public class HudService extends Service {
    private final BroadcastReceiver power = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_POWER_CONNECTED.equals(i.getAction())) wake();
        }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("hud", "싼슬라 HUD", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "hud")
                .setSmallIcon(R.drawable.ic_hud)
                .setContentTitle("싼슬라 HUD 대기 중")
                .setOngoing(true)
                .build();
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34)
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(1, n);
        } catch (Exception ignored) { }
        registerReceiver(power, new IntentFilter(Intent.ACTION_POWER_CONNECTED));
    }

    @Override public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(power); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void wake() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP, "sansla:hud");
            wl.acquire(5000);
        } catch (Exception ignored) { }
        try {
            startActivity(new Intent(this, HudActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (Exception ignored) { }
    }
}
