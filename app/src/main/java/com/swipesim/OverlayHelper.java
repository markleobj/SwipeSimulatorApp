package com.swipesim;

import android.app.Dialog;
import android.os.Build;
import android.view.WindowManager;

/**
 * 提取 FloatingWindowManager 中重复 3 次的「给 Dialog 设置 overlay 窗口类型」逻辑。
 * 使用 android.app.Dialog 而非 androidx.appcompat.app.AlertDialog，
 * 因为悬浮窗只有 Application Context，AppCompat 对话框要求 Activity Context 会崩溃。
 */
public final class OverlayHelper {

    private OverlayHelper() {}

    /**
     * 让 Dialog 能从悬浮窗（非 Activity）中弹出。
     * Android 8.0+ 用 TYPE_APPLICATION_OVERLAY，以下用 TYPE_PHONE。
     */
    public static void applyOverlayType(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;
        try {
            WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
            lp.type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            // 显式 setAttributes，部分 ROM 上 getAttributes 返回的不是同一个引用
            dialog.getWindow().setAttributes(lp);
        } catch (Throwable ignored) {}
    }
}
