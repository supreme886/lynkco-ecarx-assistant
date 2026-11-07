package com.car.assistant;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置界面：选目标驾驶模式 + 开机自动切换开关 + 开机关闭智能启停开关。
 * 车机 1920x1080 @160dpi，全部用大字号大触控区。
 */
public class MainActivity extends Activity {

    // 实车只有这四种模式
    private static final int[] MODES = {
            CarFunctionController.MODE_COMFORT,
            CarFunctionController.MODE_ECO,
            CarFunctionController.MODE_DYNAMIC,
            CarFunctionController.MODE_CUSTOM,
    };

    private TextView statusText;
    private RadioGroup modeGroup;
    private Switch modeSwitch;
    private Switch issSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("车机助手");
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        statusText = new TextView(this);
        statusText.setTextSize(18);
        statusText.setTextColor(Color.DKGRAY);
        statusText.setPadding(0, dp(8), 0, dp(16));
        root.addView(statusText);

        // ---- 驾驶模式选择 ----
        TextView modeLabel = sectionLabel("每次开机自动切换到：");
        root.addView(modeLabel);

        modeGroup = new RadioGroup(this);
        modeGroup.setOrientation(RadioGroup.VERTICAL);
        int savedMode = Prefs.getModeValue(this);
        for (int mode : MODES) {
            RadioButton rb = new RadioButton(this);
            rb.setText(CarFunctionController.modeName(mode));
            rb.setTextSize(24);
            rb.setId(View.generateViewId());
            rb.setTag(mode);
            rb.setPadding(dp(12), dp(10), dp(12), dp(10));
            modeGroup.addView(rb);
            if (mode == savedMode) rb.setChecked(true);
        }
        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            View v = group.findViewById(checkedId);
            if (v != null) {
                Prefs.setModeValue(this, (Integer) v.getTag());
            }
        });
        root.addView(modeGroup);

        // ---- 开关：开机自动切模式 ----
        modeSwitch = makeSwitch("开机自动切换驾驶模式", Prefs.isModeEnabled(this));
        modeSwitch.setOnCheckedChangeListener((b, on) -> Prefs.setModeEnabled(this, on));
        root.addView(modeSwitch);

        // ---- 立即应用 ----
        Button apply = new Button(this);
        apply.setText("立即切换到所选模式");
        apply.setTextSize(22);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        apply.setLayoutParams(lp);
        apply.setOnClickListener(v -> {
            ApplyService.applyNow(this);
            Toast.makeText(this, "已提交，稍后生效并读回校验", Toast.LENGTH_SHORT).show();
            statusText.postDelayed(this::refreshStatus, 3000);
        });
        root.addView(apply);

        addDivider(root);

        // ---- 独立功能：智能启停 ----
        TextView issLabel = sectionLabel("智能启停（独立功能，与模式设置无关）");
        root.addView(issLabel);

        TextView issDesc = new TextView(this);
        issDesc.setText("开启后，只要驾驶模式发生切换（物理按键 / 车机设置页 / 本应用），"
                + "1.5 秒后自动关闭智能启停；开机不主动操作。");
        issDesc.setTextSize(16);
        issDesc.setTextColor(Color.DKGRAY);
        issDesc.setPadding(0, dp(4), 0, dp(8));
        root.addView(issDesc);

        issSwitch = makeSwitch("模式切换后自动关闭「智能启停」",
                Prefs.isIssEnabled(this));
        issSwitch.setOnCheckedChangeListener((b, on) -> {
            Prefs.setIssEnabled(this, on);
            if (on) {
                // 启动常驻模式监听服务
                ModeWatchService.start(this);
            }
        });
        root.addView(issSwitch);

        Button issOff = new Button(this);
        issOff.setText("立即关闭智能启停");
        issOff.setTextSize(22);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = dp(12);
        issOff.setLayoutParams(lp2);
        issOff.setOnClickListener(v -> {
            new Thread(() -> {
                boolean ok = CarFunctionController.setIss(this, false)
                        && !CarFunctionController.isIssOn(this);
                runOnUiThread(() -> {
                    Toast.makeText(this,
                            ok ? "智能启停已关闭" : "关闭失败（车辆当前状态不允许）",
                            Toast.LENGTH_SHORT).show();
                    refreshStatus();
                });
            }).start();
        });
        root.addView(issOff);

        TextView hint = new TextView(this);
        hint.setText("v1.6 · 两大独立功能：驾驶模式 / 智能启停互不影响。\n"
                + "开机紧轮询（信号就绪即写入）· zone=1 写入 · 校验失败自动回滚。");
        hint.setTextSize(15);
        hint.setTextColor(Color.GRAY);
        hint.setPadding(0, dp(20), 0, 0);
        root.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(20);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(Color.BLACK);
        tv.setPadding(0, dp(12), 0, dp(4));
        return tv;
    }

    private Switch makeSwitch(String text, boolean checked) {
        Switch sw = new Switch(this);
        sw.setText(text);
        sw.setTextSize(22);
        sw.setChecked(checked);
        sw.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        sw.setPadding(dp(12), dp(14), dp(12), dp(14));
        return sw;
    }

    /** 两个功能区之间的分隔线。 */
    private void addDivider(LinearLayout root) {
        View divider = new View(this);
        divider.setBackgroundColor(Color.LTGRAY);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(20);
        lp.bottomMargin = dp(4);
        divider.setLayoutParams(lp);
        root.addView(divider);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 开关是"已开启"状态时，确保监听服务在跑
        // （覆盖装新版本、服务被系统回收后开关仍开着的情况）
        if (Prefs.isIssEnabled(this)) {
            ModeWatchService.start(this);
        }
        refreshStatus();
    }

    /** 后台读取车辆当前状态并更新界面（信号未就绪时短暂重试，避免显示无效值）。 */
    private void refreshStatus() {
        new Thread(() -> {
            boolean connected = false;
            int mode = -1;
            boolean iss = false;
            for (int i = 0; i < 3; i++) {
                connected = CarFunctionController.getCarFunction(this) != null;
                if (connected) {
                    int m = CarFunctionController.getDriveMode(this);
                    if (m != -1) {
                        mode = m;
                        iss = CarFunctionController.isIssOn(this);
                        // 已识别到有效模式值即可停止重试
                        if (CarFunctionController.isKnownMode(m)) break;
                    }
                }
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {
                }
            }
            final boolean c = connected;
            final int md = mode;
            final boolean is = iss;
            runOnUiThread(() -> {
                if (!c) {
                    statusText.setText("车辆接口未连接（开机后稍等片刻会自动重试）");
                } else {
                    String modeStr = (md == -1) ? "读取失败"
                            : CarFunctionController.modeName(md);
                    statusText.setText("当前车辆状态：驾驶模式 = " + modeStr
                            + "　|　智能启停 = " + (is ? "开" : "关"));
                }
            });
        }).start();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
