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
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;

final class PlayerAppearance {
    static final int HORIZONTAL_INSET_DP = 20;
    static final int MIN_TOUCH_DP = 48;
    static final int CONTROL_GAP_DP = 8;
    static final int GROUP_GAP_DP = 16;

    enum ButtonRole {
        PRIMARY, SECONDARY, UTILITY, ACCENT, DANGER, CONFIRM_DELETE, VIDEO
    }

    enum StatusIcon {
        NEUTRAL(R.drawable.ic_control_info, R.attr.playerIconNeutral),
        WORKING(R.drawable.ic_status_working, R.attr.playerButtonAccentInk),
        SUCCESS(R.drawable.ic_status_success, R.attr.playerIconSuccess),
        WARNING(R.drawable.ic_status_warning, R.attr.playerIconWarning),
        ERROR(R.drawable.ic_status_error, R.attr.playerButtonDangerInk);

        private final int drawable;
        private final int colorAttribute;

        StatusIcon(int drawable, int colorAttribute) {
            this.drawable = drawable;
            this.colorAttribute = colorAttribute;
        }

        void apply(android.widget.TextView label) {
            Context context = label.getContext();
            android.graphics.drawable.Drawable icon = context.getDrawable(drawable).mutate();
            int size = Math.round(16 * context.getResources().getDisplayMetrics().density);
            icon.setBounds(0, 0, size, size);
            icon.setTint(color(context, colorAttribute));
            label.setCompoundDrawablesRelative(icon, null, null, null);
        }
    }

    private static final int[] DISABLED = {-android.R.attr.state_enabled};
    private static final int[] CHECKED = {android.R.attr.state_checked};
    private static final int[] SELECTED = {android.R.attr.state_selected};
    private static final int[] DEFAULT = {};

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

    static ImageButton addPageHeader(LinearLayout page, AppSettings settings, String title, Runnable onBack) {
        Context context = page.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        int touchSize = Math.round(MIN_TOUCH_DP * density);
        int titlePadding = Math.round(12 * density);
        LinearLayout header = new LinearLayout(context);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(context);
        back.setImageResource(R.drawable.ic_navigation_back);
        back.setImageTintList(controlIconTint(context));
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setContentDescription(context.getText(R.string.navigation_back));
        TypedValue selectableBackground = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground,
                selectableBackground, true);
        back.setBackgroundResource(selectableBackground.resourceId);
        back.setPadding(0, 0, 0, 0);
        back.setFocusable(true);
        back.setPadding(0, 0, 0, 0);
        back.setOnClickListener(view -> onBack.run());
        header.addView(back, new LinearLayout.LayoutParams(touchSize, touchSize));

        android.widget.TextView heading = new android.widget.TextView(context);
        heading.setText(title);
        heading.setTextSize(settings.textSize(22));
        heading.setPadding(titlePadding, 0, titlePadding, 0);
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(header, new LinearLayout.LayoutParams(-1, -2));
        return back;
    }

    static void styleButton(Button button, boolean primary, AppSettings settings) {
        styleButton(button, primary ? ButtonRole.PRIMARY : ButtonRole.SECONDARY, settings);
    }

    static void styleButton(Button button, ButtonRole role, AppSettings settings) {
        styleButton(button, role, settings, settings.raisedControls);
    }

    static void styleButton(Button button, ButtonRole role, AppSettings settings, boolean raised) {
        Context context = button.getContext();
        int target = Math.round(MIN_TOUCH_DP * context.getResources().getDisplayMetrics().density);
        button.setMinimumHeight(Math.max(target, button.getMinimumHeight()));
        button.setMinHeight(target);
        button.setMinimumWidth(Math.max(target, button.getMinimumWidth()));
        button.setMinWidth(target);
        styleButtonBackground(button, role, raised);
        int normal = color(context, android.R.attr.textColorPrimary);
        int active = color(context, R.attr.playerButtonAccentInk);
        int ink;
        switch (role) {
            case PRIMARY:
                ink = color(context, R.attr.playerAccentText);
                break;
            case ACCENT:
                ink = active;
                break;
            case DANGER:
            case CONFIRM_DELETE:
                ink = dangerInk(context);
                break;
            default:
                ink = normal;
                break;
        }
        ColorStateList colors = new ColorStateList(new int[][]{DISABLED, CHECKED, SELECTED, DEFAULT},
                new int[]{color(context, R.attr.playerButtonDisabledInk), active, active, ink});
        button.setTextColor(colors);
        button.setCompoundDrawableTintList(usesNeutralIcon(role) ? controlIconTint(context) : colors);
        if (button instanceof android.widget.CompoundButton) {
            ((android.widget.CompoundButton) button).setButtonTintList(controlIconTint(context));
        }
        button.setTextSize(settings.textSize(14));
    }

    private static boolean usesNeutralIcon(ButtonRole role) {
        return role == ButtonRole.SECONDARY || role == ButtonRole.UTILITY;
    }

    static ColorStateList controlIconTint(Context context) {
        return context.getColorStateList(R.color.control_icon_foreground);
    }

    private static int dangerInk(Context context) {
        return color(context, R.attr.playerButtonDangerInk);
    }

    static CharSequence dangerLabel(Context context, String label) {
        android.text.SpannableString text = new android.text.SpannableString(label);
        text.setSpan(new android.text.style.ForegroundColorSpan(dangerInk(context)), 0, text.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    static void styleVideoControl(View view, boolean raised) {
        styleButtonBackground(view, ButtonRole.VIDEO, raised);
        if (view instanceof android.widget.ImageButton) {
            Context context = view.getContext();
            ((android.widget.ImageButton) view).setImageTintList(
                    new ColorStateList(new int[][]{DISABLED, DEFAULT},
                            new int[]{color(context, R.attr.playerButtonDisabledInk), Color.WHITE}));
        }
    }

    private static void styleButtonBackground(View view, ButtonRole role, boolean raised) {
        Context context = view.getContext();
        float density = view.getResources().getDisplayMetrics().density;
        int selected = color(context, R.attr.playerSelection);
        int fill;
        switch (role) {
            case PRIMARY:
                fill = color(context, android.R.attr.colorAccent);
                break;
            case SECONDARY:
                fill = color(context, R.attr.playerButtonSurface);
                break;
            case ACCENT:
                fill = selected;
                break;
            case CONFIRM_DELETE:
                fill = color(context, R.attr.playerButtonDanger);
                break;
            case VIDEO:
                fill = 0xb3000000;
                break;
            default:
                fill = Color.TRANSPARENT;
                break;
        }
        boolean filled = fill != Color.TRANSPARENT;
        StateListDrawable states = new StateListDrawable();
        states.addState(DISABLED, surface(filled ? (role == ButtonRole.VIDEO ? 0x66000000
                : color(context, R.attr.playerButtonDisabled)) : Color.TRANSPARENT, false, density));
        states.addState(CHECKED, surface(selected, raised, density));
        states.addState(SELECTED, surface(selected, raised, density));
        states.addState(DEFAULT, surface(fill, raised && filled, density));
        view.setBackgroundTintList(null);
        view.setBackground(states);
        view.setStateListAnimator(null);
        view.setElevation(raised && filled && role != ButtonRole.VIDEO ? 3 * density : 0);
        view.setTranslationZ(0);
    }

    static void styleControlSurface(android.view.View view, boolean raised) {
        Context context = view.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        int fill = color(context, android.R.attr.colorBackgroundFloating);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, surface(fill, false, density));
        states.addState(new int[]{android.R.attr.state_selected},
                surface(color(context, R.attr.playerSelection), raised, density));
        states.addState(new int[0], surface(fill, raised, density));
        view.setBackgroundTintList(null);
        view.setBackground(states);
        view.setElevation(raised ? 3 * density : 0);
    }

    static AlertDialog showDialog(AlertDialog dialog, AppSettings settings) {
        return showDialog(dialog, settings, ButtonRole.PRIMARY, ButtonRole.SECONDARY);
    }

    private static AlertDialog showDialog(AlertDialog dialog, AppSettings settings,
                                          ButtonRole positive, ButtonRole neutral) {
        dialog.show();
        int[] roles = {AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_POSITIVE};
        for (int role : roles) {
            Button button = dialog.getButton(role);
            if (button == null || button.getVisibility() != View.VISIBLE) continue;
            styleButton(button, role == AlertDialog.BUTTON_POSITIVE ? positive
                    : role == AlertDialog.BUTTON_NEUTRAL ? neutral : ButtonRole.SECONDARY, settings);
            button.setAllCaps(false);
            button.setSingleLine(false);
            float density = button.getResources().getDisplayMetrics().density;
            int padding = Math.round(CONTROL_GAP_DP * density);
            button.setPadding(padding, padding, padding, padding);
            if (button.getParent() instanceof LinearLayout) {
                LinearLayout bar = (LinearLayout) button.getParent();
                int inset = Math.round(HORIZONTAL_INSET_DP * density);
                bar.setPaddingRelative(inset, Math.round(GROUP_GAP_DP * density),
                        inset, bar.getPaddingBottom());
                boolean leading = true;
                for (int i = 0; i < bar.getChildCount(); i++) {
                    View child = bar.getChildAt(i);
                    if (!(child instanceof Button)) {
                        child.setVisibility(View.GONE);
                    } else if (child.getVisibility() == View.VISIBLE) {
                        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                        params.setMarginStart(leading ? 0 : padding);
                        child.setLayoutParams(params);
                        leading = false;
                    }
                }
            }
        }
        return dialog;
    }

    static final class DialogBuilder extends AlertDialog.Builder {
        private final AppSettings settings;
        private ButtonRole positive = ButtonRole.PRIMARY;
        private ButtonRole neutral = ButtonRole.SECONDARY;

        DialogBuilder(Context context, AppSettings settings) {
            super(context);
            this.settings = settings;
        }

        DialogBuilder positiveRole(ButtonRole role) {
            positive = role;
            return this;
        }

        DialogBuilder destructiveNeutral() {
            neutral = ButtonRole.DANGER;
            return this;
        }

        @Override public AlertDialog show() {
            return showDialog(create(), settings, positive, neutral);
        }
    }

    private static RippleDrawable surface(int fill, boolean raised, float density) {
        int highlight = Color.argb(Color.alpha(fill), (Color.red(fill) * 9 + 255) / 10,
                (Color.green(fill) * 9 + 255) / 10, (Color.blue(fill) * 9 + 255) / 10);
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{raised ? highlight : fill, fill});
        shape.setCornerRadius(12 * density);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(12 * density);
        return new RippleDrawable(ColorStateList.valueOf(0x337c9bd0), shape, mask);
    }
}
