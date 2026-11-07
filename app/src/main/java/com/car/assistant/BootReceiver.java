package com.car.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 开机完成后拉起 ApplyService（切模式）和 ModeWatchService（模式切换后关启停）。 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "CarAssistant";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        Log.i(TAG, "boot completed, schedule apply");
        try {
            context.startService(new Intent(context, ApplyService.class));
        } catch (Throwable t) {
            Log.w(TAG, "start ApplyService failed: " + t);
        }
        // 常驻模式监听（用于“模式切换后自动关闭智能启停”）
        ModeWatchService.start(context);
    }
}
