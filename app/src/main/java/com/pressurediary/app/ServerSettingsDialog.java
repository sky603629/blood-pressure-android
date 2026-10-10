package com.pressurediary.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Family setup stays behind the existing gear menu. No upload indicators on the diary. */
final class ServerSettingsDialog {
    static void show(Activity activity) {
        UploadSettings settings = new UploadSettings(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(activity, 22), dp(activity, 12), dp(activity, 22), dp(activity, 20));

        TextView help = label(activity, "首次由家人配置。开启后，新增、修改、删除和导入记录都会同步，本机仍保存完整记录。", 17, false);
        content.addView(help);
        content.addView(label(activity, "服务器地址", 18, true), space(activity));
        EditText address = new EditText(activity);
        address.setTextSize(18);
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint("https://bp.282412.xyz");
        address.setText(settings.baseUrl());
        address.setContentDescription("HTTPS 服务器地址");
        content.addView(address);

        content.addView(label(activity, "上传密钥", 18, true), space(activity));
        EditText key = new EditText(activity);
        key.setTextSize(18);
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setHint("服务器生成的手机上传密钥");
        key.setContentDescription("手机上传密钥");
        // Prevent view-state snapshots from retaining the plaintext credential.
        key.setSaveEnabled(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        content.addView(key);

        Switch enabled = new Switch(activity);
        enabled.setText("自动同步记录");
        enabled.setTextSize(18);
        enabled.setMinHeight(dp(activity, 54));
        enabled.setChecked(settings.enabled());
        content.addView(enabled, space(activity));

        TextView result = label(activity, "", 16, false);
        result.setTextColor(0xFF636366);
        try { key.setText(settings.read().token); }
        catch (Exception error) {
            result.setText("原上传密钥无法读取，请重新填写。关闭上传并留空可以清除旧配置。");
        }

        Button test = new Button(activity);
        test.setText("测试连接");
        test.setTextSize(18);
        test.setAllCaps(false);
        test.setMinHeight(dp(activity, 50));
        content.addView(test);
        content.addView(result, space(activity));
        Button sync = new Button(activity);
        sync.setText("立即同步");
        sync.setTextSize(18);
        sync.setAllCaps(false);
        sync.setMinHeight(dp(activity, 50));
        content.addView(sync);
        sync.setOnClickListener(view -> {
            if (!settings.enabled()) {
                result.setText("请先开启自动同步并保存设置。");
            } else {
                MeasurementUploader.requestSync(activity, true);
                result.setText("已请求同步。联网成功后可在网页查看；没有网络时记录仍保存在手机里。");
            }
        });
        TextView detail = label(activity, "同步以这台手机的记录为准：先补齐旧数据，再更新新增记录，手机删除的记录也会从服务器删除。其他家人的记录不受影响。断网后，下次保存或打开应用时补传；也可点击立即同步。照片不会上传。", 15, false);
        detail.setTextColor(0xFF636366);
        content.addView(detail, space(activity));

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("服务器设置").setView(scroll)
                .setNegativeButton("取消", null).setPositiveButton("保存设置", null).create();
        test.setOnClickListener(view -> {
            try {
                String base = MeasurementUploadProtocol.normalizeBaseUrl(address.getText().toString());
                String token = MeasurementUploadProtocol.normalizeToken(key.getText().toString());
                test.setEnabled(false);
                address.setEnabled(false);
                key.setEnabled(false);
                result.setText("正在连接…");
                MeasurementUploader.testConnection(activity, base, token, (member, error) -> {
                    if (activity.isFinishing() || activity.isDestroyed() || !dialog.isShowing()) return;
                    test.setEnabled(true);
                    address.setEnabled(true);
                    key.setEnabled(true);
                    result.setText(member != null ? "连接成功 · " + member : (error == null ? "连接失败，请稍后再试" : error));
                    result.setTextColor(member != null ? 0xFF167A50 : 0xFFB8322A);
                });
            } catch (IllegalArgumentException error) {
                result.setText(error.getMessage());
                result.setTextColor(0xFFB8322A);
            }
        });
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                try {
                    settings.save(address.getText().toString(), key.getText().toString(), enabled.isChecked());
                    MeasurementUploader.onDataChanged(activity);
                    dialog.dismiss();
                    Toast.makeText(activity, "服务器设置已保存", Toast.LENGTH_SHORT).show();
                } catch (IllegalArgumentException error) {
                    result.setText(error.getMessage());
                    result.setTextColor(0xFFB8322A);
                } catch (Exception error) {
                    result.setText("无法安全保存上传密钥，请检查设备后重试");
                    result.setTextColor(0xFFB8322A);
                }
            });
            if (dialog.getWindow() != null) {
                GradientDrawable background = new GradientDrawable();
                background.setColor(0xFFFFFFFF);
                background.setCornerRadius(dp(activity, 22));
                dialog.getWindow().setBackgroundDrawable(background);
                dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN |
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        });
        dialog.setOnDismissListener(ignored -> key.setText(""));
        dialog.show();
    }

    private static TextView label(Activity activity, String text, int size, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(0xFF1C1C1E);
        view.setLineSpacing(dp(activity, 3), 1);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private static LinearLayout.LayoutParams space(Activity activity) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(activity, 16);
        return params;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
