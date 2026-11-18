package com.aiplayer.android;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

final class PlayerAppearance {
    static final int CONTROL_GAP_DP = 8;
    static final int GROUP_GAP_DP = 16;

    private PlayerAppearance() {}

    static int color(Context context, int attribute) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(attribute, value, true);
        return value.resourceId == 0 ? value.data
                : context.getColorStateList(value.resourceId).getDefaultColor();
    }

    static void styleSurface(View view, int fill, boolean raised) {
        float density = view.getResources().getDisplayMetrics().density;
        view.setBackground(surface(fill, raised, density));
        view.setElevation(raised ? 3 * density : 0);
    }

    static void stylePrimary(View view, boolean raised) {
        Context context = view.getContext();
        float density = view.getResources().getDisplayMetrics().density;
        ColorStateList fills = context.getColorStateList(R.color.primary_button_background);
        int[] disabled = {-android.R.attr.state_enabled};
        StateListDrawable states = new StateListDrawable();
        states.addState(disabled,
                surface(fills.getColorForState(disabled, fills.getDefaultColor()), false, density));
        states.addState(new int[0],
                surface(fills.getDefaultColor(), raised, density));
        view.setBackgroundTintList(null);
        view.setBackground(states);
        view.setElevation(raised ? 3 * density : 0);
    }

    static void styleButton(Button button, boolean primary, AppSettings settings) {
        Context context = button.getContext();
        styleButtonSurface(button, primary, settings.raisedControls);
        button.setTextColor(context.getColorStateList(primary
                ? R.color.primary_button_foreground : R.color.control_icon_foreground));
        button.setTextSize(settings.textSize(14));
    }

    private static void styleButtonSurface(Button button, boolean primary, boolean raised) {
        if (primary) {
            stylePrimary(button, raised);
            return;
        }
        Context context = button.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        int fill = color(context, android.R.attr.colorBackgroundFloating);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, surface(fill, false, density));
        states.addState(new int[]{android.R.attr.state_selected},
                surface(color(context, R.attr.playerSelection), raised, density));
        states.addState(new int[0], surface(fill, raised, density));
        button.setBackgroundTintList(null);
        button.setBackground(states);
        button.setElevation(raised ? 3 * density : 0);
    }

    static AlertDialog showDialog(AlertDialog dialog, AppSettings settings) {
        dialog.show();
        int[] roles = {AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_POSITIVE};
        for (int role : roles) {
            Button button = dialog.getButton(role);
            if (button == null || button.getVisibility() != View.VISIBLE) continue;
            styleButton(button, role == AlertDialog.BUTTON_POSITIVE, settings);
            button.setAllCaps(false);
            button.setSingleLine(false);
            float density = button.getResources().getDisplayMetrics().density;
            button.setMinimumHeight(Math.round(48 * density));
            button.setMinHeight(Math.round(48 * density));
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            int padding = Math.round(CONTROL_GAP_DP * density);
            button.setPadding(padding, padding, padding, padding);
            if (button.getParent() instanceof LinearLayout) {
                LinearLayout bar = (LinearLayout) button.getParent();
                for (int i = 0; i < bar.getChildCount(); i++) {
                    View child = bar.getChildAt(i);
                    if (!(child instanceof Button)) child.setVisibility(View.GONE);
                }
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                params.setMargins(padding / 2, 0, padding / 2, 0);
                button.setLayoutParams(params);
            }
        }
        return dialog;
    }

    static final class DialogBuilder extends AlertDialog.Builder {
        private final AppSettings settings;

        DialogBuilder(Context context, AppSettings settings) {
            super(context);
            this.settings = settings;
        }

        @Override public AlertDialog show() {
            return showDialog(create(), settings);
        }
    }

    private static RippleDrawable surface(int fill, boolean raised, float density) {
        int highlight = Color.argb(Color.alpha(fill), (Color.red(fill) * 9 + 255) / 10,
                (Color.green(fill) * 9 + 255) / 10, (Color.blue(fill) * 9 + 255) / 10);
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{raised ? highlight : fill, fill});
        shape.setCornerRadius(12 * density);
        return new RippleDrawable(ColorStateList.valueOf(0x337c9bd0), shape, null);
    }
}
