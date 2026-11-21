package com.aiplayer.android;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class OpenSourceSheet extends Dialog {
    private final LinearLayout content;
    private final AppSettings settings;

    OpenSourceSheet(Context context, AppSettings settings, String title) {
        super(context);
        this.settings = settings;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setCanceledOnTouchOutside(true);

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(PlayerAppearance.HORIZONTAL_INSET_DP), dp(PlayerAppearance.CONTROL_GAP_DP), dp(PlayerAppearance.HORIZONTAL_INSET_DP), dp(PlayerAppearance.GROUP_GAP_DP));
        panel.setFocusableInTouchMode(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(PlayerAppearance.color(context, android.R.attr.colorBackgroundFloating));
        float radius = dp(24);
        background.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        panel.setBackground(background);

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = new TextView(context);
        heading.setText(title);
        heading.setTextSize(settings.textSize(22));
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton close = new ImageButton(context);
        close.setImageResource(R.drawable.ic_control_cancel);
        close.setImageTintList(PlayerAppearance.controlIconTint(context));
        close.setContentDescription("Đóng");
        TypedValue selectable = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, selectable, true);
        close.setBackgroundResource(selectable.resourceId);
        close.setOnClickListener(view -> dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(context);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1));
        setContentView(panel);
        panel.requestFocus();

        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setGravity(Gravity.BOTTOM);
        window.setWindowAnimations(R.style.OpenSourceSheetAnimation);
        window.setLayout(-1, -2);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    LinearLayout content() {
        return content;
    }

    Button addAction(String label, int icon, boolean primary, Runnable action) {
        Button button = button(label, primary);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(PlayerAppearance.CONTROL_GAP_DP);
        content.addView(button, params);
        if (icon != 0) {
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
            button.setCompoundDrawablePadding(dp(12));
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        }
        button.setOnClickListener(view -> action.run());
        return button;
    }

    Button button(String label, boolean primary) {
        Button button = new Button(content.getContext());
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        PlayerAppearance.styleButton(button, primary ? PlayerAppearance.ButtonRole.PRIMARY
                : PlayerAppearance.ButtonRole.SECONDARY, settings, false);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }
}
