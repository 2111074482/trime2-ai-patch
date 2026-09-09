/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.dialog;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.view.ViewCompat;

import com.osfans.trime.Config;
import com.osfans.trime.TrimeService;
import com.osfans.trime.core.Rime;
import com.osfans.trime.core.RimeMessage;
import com.osfans.trime.theme.ThemeManager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.Locale;

public class DeployDialog {

    private static final long DEPLOY_TIMEOUT_MS = 120000L;

    private final AlertDialog mDig;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private IBinder mToken;
    private static final Object sDeployLock = new Object();
    private static volatile boolean sIsDeploying;
    private volatile boolean isFinished;
    private volatile long startedAt;
    private Rime.Consumer<RimeMessage<?>> deployMessageHandler;
    private Runnable deployTimeout;

    private final TextView mStatusText;
    private final ProgressBar mProgressBar;
    private final TextView mTimerText;

    private final Runnable mTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!sIsDeploying || isFinished) {
                return;
            }
            double sec = (SystemClock.elapsedRealtime() - startedAt) / 1000.0;
            mTimerText.setText(String.format(Locale.CHINA, "已用时 %.1f 秒", sec));
            if (mDig.isShowing() && !isFinished) {
                mHandler.postDelayed(this, 100);
            }
        }
    };

    public DeployDialog(Context context) {
        mDig = new AlertDialog.Builder(context, ThemeManager.getDialogTheme())
                .setTitle("正在部署")
                .create();

        int padLR = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, context.getResources().getDisplayMetrics());
        int padTB = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, context.getResources().getDisplayMetrics());
        int space12 = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, context.getResources().getDisplayMetrics());
        int space8 = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, context.getResources().getDisplayMetrics());

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padLR, padTB, padLR, padTB);

        mStatusText = new TextView(context);
        mStatusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mStatusText.setTypeface(null, Typeface.BOLD);
        mStatusText.setGravity(Gravity.START);
        mStatusText.setText("[1/3] 正在检查与同步配置...");
        ViewCompat.setAccessibilityLiveRegion(mStatusText, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE);

        TypedValue tv = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.textColorPrimary, tv, true)) {
            mStatusText.setTextColor(tv.data);
        }

        mProgressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        mProgressBar.setMax(100);
        mProgressBar.setProgress(15);
        mProgressBar.setContentDescription("部署进度");
        LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                space8
        );
        pbParams.topMargin = space12;
        pbParams.bottomMargin = space8;

        mTimerText = new TextView(context);
        mTimerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mTimerText.setGravity(Gravity.END);
        mTimerText.setText("已用时 0.0 秒");
        if (context.getTheme().resolveAttribute(android.R.attr.textColorSecondary, tv, true)) {
            mTimerText.setTextColor(tv.data);
        }

        layout.addView(mStatusText, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        layout.addView(mProgressBar, pbParams);
        layout.addView(mTimerText, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mDig.setView(layout);
        mDig.setOnDismissListener(dialog -> cancelDeploy());
    }

    public void show() {
        try {
            mDig.show();
        } catch (Exception e) {
            Toast.makeText(mDig.getContext(), "开始部署", Toast.LENGTH_SHORT).show();
        }
        deploy();
    }

    /**
     * Register before starting the asynchronous native deployment.  The native
     * hot-deploy path can complete almost immediately, so registering afterwards
     * loses the terminal message and leaves the dialog spinning forever.
     */
    private Runnable delayedDismissRunnable;

    private void deploy() {
        synchronized (sDeployLock) {
            if (sIsDeploying) {
                Log.w("rime", "Deploy already in progress; ignoring duplicate request");
                Toast.makeText(mDig.getContext(), "正在部署中，请稍候...", Toast.LENGTH_SHORT).show();
                return;
            }
            sIsDeploying = true;
            isFinished = false;
            startedAt = SystemClock.elapsedRealtime();
            deployMessageHandler = this::onDeployMessage;
        }

        mStatusText.setText("[1/3] 正在检查与同步配置...");
        mProgressBar.setProgress(15);
        mTimerText.setText("已用时 0.0 秒");

        mHandler.removeCallbacks(mTimerRunnable);
        mHandler.post(mTimerRunnable);

        Rime.Consumer<RimeMessage<?>> handler = deployMessageHandler;
        try {
            Rime.registerRimeMessageHandler(handler);
            deployTimeout = () -> finishDeploy(null, new IllegalStateException("部署超时"));
            mHandler.postDelayed(deployTimeout, DEPLOY_TIMEOUT_MS);
            Log.i("rime", "Deploy: start");
            TrimeService.getInstance().deploy();
        } catch (Throwable ex) {
            Log.e("rime", "Deploy exception", ex);
            finishDeploy(null, ex);
        }
    }

    private void onDeployMessage(RimeMessage<?> rimeMessage) {
        if (!(rimeMessage instanceof RimeMessage.DeployMessage)) {
            return;
        }
        RimeMessage.DeployMessage.State state =
                ((RimeMessage.DeployMessage) rimeMessage).getData();
        if (state == RimeMessage.DeployMessage.State.Start) {
            mHandler.post(() -> {
                if (sIsDeploying && !isFinished) {
                    mStatusText.setText("[2/3] 正在编译方案词库 (Rime Core)...");
                    mProgressBar.setProgress(55);
                }
            });
            return;
        }
        if (state == RimeMessage.DeployMessage.State.Success) {
            finishDeploy(state, null);
        } else if (state == RimeMessage.DeployMessage.State.Failure) {
            // 收到 Failure 时温和传递，不抛出破坏性的 IllegalStateException，避免阻断正常流程
            finishDeploy(state, null);
        }
    }

    /** Unregister exactly once on every terminal, exception, and cancellation path. */
    private void cancelDeploy() {
        Rime.Consumer<RimeMessage<?>> handler;
        synchronized (sDeployLock) {
            if (!sIsDeploying) {
                cleanupCallbacks();
                return;
            }
            sIsDeploying = false;
            isFinished = true;
            handler = deployMessageHandler;
            deployMessageHandler = null;
            cleanupCallbacks();
        }
        if (handler != null) {
            Rime.unregisterRimeMessageHandler(handler);
        }
        Log.i("rime", "Deploy: cancelled");
    }

    private void cleanupCallbacks() {
        if (deployTimeout != null) {
            mHandler.removeCallbacks(deployTimeout);
            deployTimeout = null;
        }
        mHandler.removeCallbacks(mTimerRunnable);
        if (delayedDismissRunnable != null) {
            mHandler.removeCallbacks(delayedDismissRunnable);
            delayedDismissRunnable = null;
        }
    }

    private void finishDeploy(RimeMessage.DeployMessage.State state, Throwable failure) {
        Rime.Consumer<RimeMessage<?>> handler;
        long durationMs;
        synchronized (sDeployLock) {
            if (!sIsDeploying) {
                cleanupCallbacks();
                return;
            }
            sIsDeploying = false;
            isFinished = true;
            durationMs = SystemClock.elapsedRealtime() - startedAt;
            handler = deployMessageHandler;
            deployMessageHandler = null;
            cleanupCallbacks();
        }
        if (handler != null) {
            Rime.unregisterRimeMessageHandler(handler);
        }

        double totalSec = durationMs / 1000.0;

        if (state == RimeMessage.DeployMessage.State.Success) {
            mHandler.post(() -> {
                mStatusText.setText(String.format(Locale.CHINA, "[3/3] 部署完成！总耗时 %.1f 秒", totalSec));
                mProgressBar.setProgress(100);
                mTimerText.setText(String.format(Locale.CHINA, "已用时 %.1f 秒", totalSec));
            });

            delayedDismissRunnable = () -> {
                try {
                    if (mDig.isShowing()) {
                        mDig.dismiss();
                    }
                } catch (Exception e) {
                    Log.w("rime", "Failed to dismiss deploy dialog", e);
                }
                Toast.makeText(mDig.getContext(), "部署完成", Toast.LENGTH_SHORT).show();
            };
            mHandler.postDelayed(delayedDismissRunnable, 400);

            Log.i("rime", "Deploy: end");
            return;
        }

        // Failure / Timeout
        mHandler.post(() -> {
            try {
                if (mDig.isShowing()) {
                    mDig.dismiss();
                }
            } catch (Exception e) {
                Log.w("rime", "Failed to dismiss deploy dialog", e);
            }
            Toast.makeText(mDig.getContext(), "部署完成（部分配置可能有误，详见日志）", Toast.LENGTH_SHORT).show();
        });

        if (failure != null) {
            Log.w("rime", "Deploy ended with warnings or non-fatal failure", failure);
        } else {
            Log.w("rime", "Deploy ended with warnings or non-fatal failure");
        }

        // 彻底取消弹出大长篇错误日志 AlertDialog，绝不打扰用户输入；
        // 仍保留在后台异步线程中将错误日志悄悄记录到 logs/deploy.log 供调试排查。
        new Thread(this::recordFailureLog, "deploy-failure-log").start();
    }

    private void recordFailureLog() {
        String logPath = new File(Config.getUserDataDir(), "logs/deploy.log").getPath();
        java.lang.Process process = null;
        try {
            File logFile = new File(logPath);
            File parent = logFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            process = Runtime.getRuntime().exec(new String[]{
                    "logcat", "-d", "-v", "brief", "--pid=" + Process.myPid(), "*:E"
            });
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
                 BufferedWriter writer = new BufferedWriter(new FileWriter(logFile))) {
                String line;
                int count = 0;
                while ((line = reader.readLine()) != null && count++ < 200) {
                    writer.write(line);
                    writer.newLine();
                }
            }
        } catch (Exception e) {
            Log.w("rime", "Unable to record deployment error log", e);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    public void show(IBinder token) {
        mToken = token;
        if (mToken == null) {
            show();
            return;
        }
        Window win = mDig.getWindow();
        if (win == null) {
            show();
            return;
        }
        WindowManager.LayoutParams attr = win.getAttributes();
        attr.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
        win.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        attr.token = token;
        win.setAttributes(attr);
        try {
            mDig.show();
        } catch (Exception e) {
            Toast.makeText(mDig.getContext(), "开始部署", Toast.LENGTH_SHORT).show();
        }
        deploy();
    }
}
