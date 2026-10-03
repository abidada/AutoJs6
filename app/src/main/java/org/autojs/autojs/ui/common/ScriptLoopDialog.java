package org.autojs.autojs.ui.common;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.annotation.Nullable;
import com.afollestad.materialdialogs.MaterialDialog;
import org.autojs.autojs.util.DialogUtils;
import org.autojs.autojs.model.script.ScriptFile;
import org.autojs.autojs.model.script.Scripts;
import org.autojs.autojs.util.ViewUtils;
import com.xiaoyu.ai.R;
import com.xiaoyu.ai.databinding.DialogScriptLoopBinding;

/**
 * Created by Stardust on Jul 8, 2017.
 */
public class ScriptLoopDialog {

    /**
     * 循环参数确认回调：设置后点「确定」只回传参数、不立即执行
     * （语音分发规则编辑页复用同一弹窗采集参数时使用；null = 原行为，确认即执行）。
     */
    public interface OnLoopConfiguredListener {
        void onLoopConfigured(int loopTimes, long delayMs, long intervalMs);
    }

    private final ScriptFile mScriptFile;
    private final MaterialDialog mDialog;
    @Nullable
    private final OnLoopConfiguredListener mListener;

    private final EditText mLoopTimes;
    private final EditText mLoopInterval;
    private final EditText mLoopDelay;

    public ScriptLoopDialog(Context context, ScriptFile file) {
        this(context, file, null, null);
    }

    /**
     * @param prefillMs 预填参数 [循环次数, 延迟毫秒, 间隔毫秒]；null = 不预填
     * @param listener  确认回调；null = 确认后立即执行（原行为）
     */
    public ScriptLoopDialog(Context context, ScriptFile file, @Nullable long[] prefillMs,
                            @Nullable OnLoopConfiguredListener listener) {
        mScriptFile = file;
        mListener = listener;

        DialogScriptLoopBinding binding = DialogScriptLoopBinding.inflate(LayoutInflater.from(context));
        LinearLayout view = binding.getRoot();

        mLoopTimes = binding.loopTimes;
        mLoopInterval = binding.loopInterval;
        mLoopDelay = binding.loopDelay;

        if (prefillMs != null && prefillMs.length >= 3) {
            mLoopTimes.setText(String.valueOf(prefillMs[0]));
            mLoopDelay.setText(toSecondsText(prefillMs[1]));
            mLoopInterval.setText(toSecondsText(prefillMs[2]));
        }

        mDialog = new MaterialDialog.Builder(context)
                .title(R.string.text_run_repeatedly)
                .customView(view, true)
                .negativeText(R.string.dialog_button_cancel)
                .negativeColorRes(R.color.dialog_button_default)
                .positiveText(R.string.dialog_button_confirm)
                .positiveColorRes(R.color.dialog_button_attraction)
                .onPositive((dialog, which) -> startScriptRunningLoop())
                .build();
    }

    /** 毫秒 → 秒文本（整数秒不带小数点，与弹窗输入单位一致）。 */
    private static String toSecondsText(long ms) {
        if (ms % 1000L == 0) {
            return String.valueOf(ms / 1000L);
        }
        return String.valueOf(ms / 1000.0);
    }

    private void startScriptRunningLoop() {
        try {
            int loopTimes = Integer.parseInt(mLoopTimes.getText().toString());
            float loopInterval = Float.parseFloat(mLoopInterval.getText().toString());
            float loopDelay = Float.parseFloat(mLoopDelay.getText().toString());
            long delayMs = (long) (1000L * loopDelay);
            long intervalMs = (long) (loopInterval * 1000L);
            if (mListener != null) {
                mListener.onLoopConfigured(loopTimes, delayMs, intervalMs);
                return;
            }
            Scripts.runRepeatedly(mScriptFile, loopTimes, delayMs, intervalMs);
        } catch (NumberFormatException e) {
            ViewUtils.showToast(mDialog.getContext(), R.string.text_number_format_error, true);
        }
    }

    public ScriptLoopDialog windowType(int windowType) {
        Window window = mDialog.getWindow();
        if (window != null) {
            window.setType(windowType);
        }
        return this;
    }

    public void show() {
        DialogUtils.showAdaptive(mDialog);
    }

}
