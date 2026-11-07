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
 * 驾驶模式监听服务（v1.2）：常驻监听车辆模式变化。
 *
 * 背景：原车逻辑是每次切换驾驶模式都会把「智能启停」重新打开。
 * 本服务监听 0x22010100 的值变化（无论来自物理按键、原生界面还是本 App），
 * 变化后延迟 1.5 秒自动关闭智能启停，最多补试 3 次。
 *
 * 仅当用户在设置里打开「自动关闭智能启停」时工作，否则自动退出。
 */
public class ModeWatchService extends Service {
    private static final String TAG = "CarAssistant";

    private static final long REGISTER_RETRY_MS = 5_000L;
    private static final long ISS_CLOSE_DELAY_MS = 1_500L;
    private static final long ISS_RETRY_GAP_MS = 3_000L;
    private static final int ISS_RETRY_MAX = 3;
    /** 关掉启停之后的复查时间点（毫秒），防止车辆晚几步又把它打开 */
    private static final long[] RECHECK_DELAYS = {6_000L, 15_000L};

    private HandlerThread thread;
    private Handler handler;
    private boolean watcherRegistered;
    private int issAttempts;

    public static void start(Context ctx) {
        try {
            ctx.startService(new Intent(ctx, ModeWatchService.class));
        } catch (Throwable t) {
            Log.w(TAG, "start ModeWatchService: " + t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("mode-watch");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Prefs.isIssEnabled(this)) {
            Log.i(TAG, "mode watch: iss auto-off disabled, stop");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!watcherRegistered) {
            tryRegister();
        }
        return START_STICKY;
    }

    private void tryRegister() {
        boolean ok = CarFunctionController.registerDriveModeWatcher(this, this::onModeChanged);
        Log.i(TAG, "mode watch register -> " + ok);
        if (ok) {
            watcherRegistered = true;
        } else {
            CarFunctionController.reset();
            handler.postDelayed(this::tryRegister, REGISTER_RETRY_MS);
        }
    }

    /** 回调在 binder 线程，切到工作线程处理。 */
    private void onModeChanged(int newMode) {
        Log.i(TAG, "drive mode changed -> 0x" + Integer.toHexString(newMode));
        if (!Prefs.isIssEnabled(this)) return;
        issAttempts = 0;
        handler.removeCallbacks(issCloser);
        handler.postDelayed(issCloser, ISS_CLOSE_DELAY_MS);
    }

    private final Runnable issCloser = new Runnable() {
        @Override
        public void run() {
            if (!Prefs.isIssEnabled(ModeWatchService.this)) return;
            if (!CarFunctionController.isIssOn(ModeWatchService.this)) {
                Log.i(TAG, "iss already off");
                scheduleRecheck(0);
                return;
            }
            boolean ok = CarFunctionController.setIss(ModeWatchService.this, false);
            if (ok && !CarFunctionController.isIssOn(ModeWatchService.this)) {
                Log.i(TAG, "iss closed after mode change");
                toast("智能启停已关闭");
                scheduleRecheck(0);
                return;
            }
            issAttempts++;
            if (issAttempts < ISS_RETRY_MAX) {
                Log.w(TAG, "iss close failed, retry " + issAttempts);
                handler.postDelayed(this, ISS_RETRY_GAP_MS);
            } else {
                Log.w(TAG, "iss close failed after " + ISS_RETRY_MAX + " tries");
            }
        }
    };

    /**
     * 关掉之后再复查两次：车辆有时会晚几秒自己把启停重新打开，
     * 复查发现被顶开就再关一次（总数受 ISS_RETRY_MAX 限制）。
     */
    private void scheduleRecheck(int round) {
        if (round >= RECHECK_DELAYS.length) return;
        handler.postDelayed(() -> {
            if (!Prefs.isIssEnabled(ModeWatchService.this)) return;
            if (!CarFunctionController.isIssOn(ModeWatchService.this)) {
                Log.i(TAG, "recheck " + (round + 1) + ": still off, ok");
                scheduleRecheck(round + 1);
                return;
            }
            Log.w(TAG, "recheck " + (round + 1) + ": iss reopened by car, closing again");
            boolean ok = CarFunctionController.setIss(ModeWatchService.this, false);
            Log.i(TAG, "recheck close -> " + ok
                    + " now=" + (CarFunctionController.isIssOn(ModeWatchService.this) ? "on" : "off"));
            scheduleRecheck(round + 1);
        }, RECHECK_DELAYS[round]);
    }

    private void toast(String msg) {
        new Handler(getMainLooper()).post(
                () -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onDestroy() {
        CarFunctionController.unregisterDriveModeWatcher(this);
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
