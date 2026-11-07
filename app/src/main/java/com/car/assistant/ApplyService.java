package com.car.assistant;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

/**
 * 开机应用服务（v1.4）：只负责开机自动切驾驶模式。
 *
 * 智能启停的自动关闭移交给 ModeWatchService（模式切换后触发），
 * 开机时不再主动关启停。
 *
 * 提速策略（替代旧的固定 5/15/30/60/120s 时间表）：
 * 开机后立即开始自适应轮询——前 15 次每 1 秒试一次，之后逐步放缓，
 * 车辆信号一就绪立刻写入，成功后立即退出，不做无谓等待。
 */
public class ApplyService extends Service {
    private static final String TAG = "CarAssistant";

    private static final long FIRST_DELAY_MS = 500L;   // 首次尝试延迟
    private static final long FAST_GAP_MS = 1_000L;    // 前 FAST_TIMES 次的间隔
    private static final long MID_GAP_MS = 3_000L;     // 之后到 MID_TIMES 的间隔
    private static final long SLOW_GAP_MS = 15_000L;   // 再之后的间隔
    private static final int FAST_TIMES = 15;          // 1s 间隔的尝试次数
    private static final int MID_TIMES = 35;           // 3s 间隔的尝试次数上限
    private static final int MAX_TIMES = 55;           // 总次数上限（≈2.5 分钟）

    private HandlerThread thread;
    private Handler handler;
    private int attempts;
    private boolean modeDone;

    public static void applyNow(Context ctx) {
        try {
            ctx.startService(new Intent(ctx, ApplyService.class));
        } catch (Throwable t) {
            Log.w(TAG, "applyNow: " + t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("apply");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        modeDone = !Prefs.isModeEnabled(this);
        if (modeDone) {
            stopSelf();
            return START_NOT_STICKY;
        }
        handler.removeCallbacksAndMessages(null);
        attempts = 0;
        handler.postDelayed(this::attemptOnce, FIRST_DELAY_MS);
        Log.i(TAG, "apply scheduled (adaptive): mode target=0x"
                + Integer.toHexString(Prefs.getModeValue(this)));
        return START_NOT_STICKY;
    }

    private long nextGap() {
        if (attempts <= FAST_TIMES) return FAST_GAP_MS;
        if (attempts <= MID_TIMES) return MID_GAP_MS;
        return SLOW_GAP_MS;
    }

    private void attemptOnce() {
        if (modeDone) {
            stopSelf();
            return;
        }
        attempts++;

        int target = Prefs.getModeValue(this);
        int before = CarFunctionController.getDriveMode(this);
        if (before == target) {
            // 已是目标模式：跳过写入，避免多余写引发模式服务失步
            Log.i(TAG, "drive mode already 0x" + Integer.toHexString(target)
                    + " (attempt " + attempts + "), done");
            modeDone = true;
            stopSelf();
            return;
        }

        boolean ok = false;
        if (before != -1) {
            // 接口已就绪才写；未就绪直接等下一轮
            ok = CarFunctionController.setDriveMode(this, target);
            int now = CarFunctionController.getDriveMode(this);
            ok = ok && (now == target);
            Log.i(TAG, "attempt " + attempts + ": target=0x" + Integer.toHexString(target)
                    + " before=0x" + Integer.toHexString(before)
                    + " now=0x" + Integer.toHexString(now) + " ok=" + ok);
            if (!ok && now != -1 && now != before) {
                // 写入产生了非预期状态：立即回滚到原模式
                Log.w(TAG, "rollback to 0x" + Integer.toHexString(before));
                CarFunctionController.setDriveMode(this, before);
                toast("模式切换异常，已回滚");
            }
            if (ok) {
                toast("驾驶模式已切换为「" + CarFunctionController.modeName(target) + "」");
            }
        } else {
            Log.i(TAG, "attempt " + attempts + ": car function not ready, wait");
            CarFunctionController.reset();
        }

        if (ok) {
            modeDone = true;
            stopSelf();
            return;
        }
        if (attempts >= MAX_TIMES) {
            Log.w(TAG, "give up after " + attempts + " attempts");
            stopSelf();
            return;
        }
        handler.postDelayed(this::attemptOnce, nextGap());
    }

    private void toast(String msg) {
        Handler main = new Handler(getMainLooper());
        main.post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show());
    }

    @Override
    public void onDestroy() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
