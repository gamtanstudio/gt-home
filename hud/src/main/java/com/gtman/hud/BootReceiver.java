package com.gtman.hud;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 전용 HUD폰이 재부팅돼도 대기 상태로 돌아오게 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (!c.getSharedPreferences("hud", Context.MODE_PRIVATE).getBoolean("solo", false)) return;
        try { c.startForegroundService(new Intent(c, HudService.class)); } catch (Exception ignored) { }
    }
}
