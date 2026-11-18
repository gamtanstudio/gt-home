package com.gtman.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/** 화면 분할 버튼을 대신 눌러 주는 접근성 서비스 (다른 일은 안 함) */
public class SplitService extends AccessibilityService {
    static SplitService inst;

    @Override protected void onServiceConnected() { inst = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }
    @Override public boolean onUnbind(Intent i) { inst = null; return super.onUnbind(i); }

    static boolean ready() { return inst != null; }

    static boolean toggleSplit() {
        return inst != null && inst.performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
    }
}
