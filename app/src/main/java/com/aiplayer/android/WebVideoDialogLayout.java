package com.aiplayer.android;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class WebVideoDialogLayout {
    private WebVideoDialogLayout() {}

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static LinearLayout body(Context context) {
        LinearLayout layout = column(context);
        int inset = dp(context, PlayerAppearance.HORIZONTAL_INSET_DP);
        layout.setPadding(inset, 0, inset, dp(context, PlayerAppearance.GROUP_GAP_DP));
        return layout;
    }

    static void header(LinearLayout layout, AppSettings settings, String title, boolean back, Runnable exit) {
        if (back) {
            PlayerAppearance.addPageHeader(layout, settings, title, exit);
            return;
        }
        Context context = layout.getContext();
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(context);
        label.setText(title);
        label.setTextSize(settings.textSize(22));
        if (android.os.Build.VERSION.SDK_INT >= 28) label.setAccessibilityHeading(true);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton close = new ImageButton(context);
        close.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        close.setImageTintList(PlayerAppearance.controlIconTint(context));
        close.setContentDescription("Đóng");
        close.setOnClickListener(view -> exit.run());
        int target = dp(context, PlayerAppearance.MIN_TOUCH_DP);
        row.addView(close, new LinearLayout.LayoutParams(target, target));
        add(layout, row, 0);
    }

    static Button button(Context context, AppSettings settings, String text, boolean primary, Runnable click) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        PlayerAppearance.styleButton(button, primary, settings);
       button.setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8));
        button.setOnClickListener(view -> click.run());
        return button;
    }

    static void add(LinearLayout parent, View view, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(parent.getContext(), gap);
        parent.addView(view, params);
    }

    static ScrollView scroll(LinearLayout content) {
        ScrollView scroll = new ScrollView(content.getContext());
        scroll.addView(content);
        return scroll;
    }
}
