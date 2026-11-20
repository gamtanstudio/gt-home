package com.gtman.home;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.FrameLayout;

/** 반반 화면의 오른쪽: 싼슬라 패널을 진짜 화면으로 띄움 */
public class SideActivity extends Activity {
    private FrameLayout host;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        int bg = Color.parseColor("#0B0F15");
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        host = new FrameLayout(this);
        host.setBackgroundColor(bg);
        setContentView(host);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (PanelService.inst != null) PanelService.inst.attachSide(host);
    }

    @Override
    protected void onDestroy() {
        if (PanelService.inst != null) PanelService.inst.detachSide(host);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (PanelService.inst != null && PanelService.inst.sideBack()) return;
    }
}
