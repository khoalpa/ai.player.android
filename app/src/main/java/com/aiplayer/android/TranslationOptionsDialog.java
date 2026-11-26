package com.aiplayer.android;

import android.app.AlertDialog;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.Arrays;
import java.util.List;

final class TranslationOptionsDialog {
    private final MainActivity activity;
    private Runnable translationModelFeedback;

    TranslationOptionsDialog(MainActivity activity) {
        this.activity = activity;
    }

    void refreshModelStatus() {
        if (translationModelFeedback != null) translationModelFeedback.run();
    }

    void show(TranscriptCue selected, String editedSource, boolean retry,
              VideoSession session, OfflineModels models, AppSettings settings, Uri video) {
        if (session.busy || session.cues.isEmpty()) return;
        LinearLayout form = activity.form();
        String retryTarget = retry ? session.retryTranslationTarget() : null;
        if (retry && retryTarget == null) return;
        boolean contextAvailable = !retry || "vi".equals(retryTarget);
        boolean[] contextual = {selected != null || (contextAvailable && session.contextualTranslation())};
        LinearLayout methods = selected == null && contextAvailable ? activity.row(form) : null;
        LinearLayout sentenceFields = activity.column();
        form.addView(sentenceFields);
        List<String> codes = activity.targetLanguages();
        TextView sentenceModelStatus = activity.processingLabel(sentenceFields, "");
        activity.processingLabel(sentenceFields, "Dịch từng câu sang ngôn ngữ bạn chọn.");
        Spinner sentenceEngine = activity.selection(sentenceFields, "Model dịch",
                Arrays.asList("ML Kit", M2mModelStore.NAME),
                activity.getPreferences(android.content.Context.MODE_PRIVATE).getBoolean("sentence_engine_m2m", false) ? 1 : 0);
        Spinner target = activity.languages(sentenceFields, "Ngôn ngữ đích", codes, settings.targetLanguage);
        if (retry) {
            target.setSelection(codes.indexOf(retryTarget));
            target.setEnabled(false);
        }
        CheckBox replaceSentences = new CheckBox(activity);
        if (!retry && session.targetLanguage != null) {
            replaceSentences.setText("Dịch lại các câu tự động");
            sentenceFields.addView(replaceSentences);
        }

        boolean supported = ContextModelStore.supported();
        LinearLayout context = activity.column();
        form.addView(context);
        activity.processingLabel(context, "Dùng ngữ cảnh cả video để thống nhất xưng hô, tên riêng và thuật ngữ.");
        Spinner contextEngine = activity.selection(context, "Model dịch",
                Arrays.asList(models.contextModel(false).name, models.contextModel(true).name),
                models.contextual() == models.contextModel(true) ? 1 : 0);
        TextView modelStatus = activity.processingLabel(context, "");
        modelStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout contextFields = activity.column();
        context.addView(contextFields);
        activity.processingLabel(contextFields, "Dùng lại bản dịch theo câu và hiệu đính những câu cần ngữ cảnh. Giữ các câu đã sửa tay.");

        CheckBox replace = new CheckBox(activity);
        if (!retry && selected == null && session.targetLanguage != null) {
            if (!"vi".equals(session.targetLanguage)) {
                replace.setText("Thay bản dịch ngôn ngữ hiện tại bằng tiếng Việt");
            } else {
                replace.setText("Rà soát bản dịch tự động đã có");
                replace.setChecked(!session.contextualTranslation());
            }
            contextFields.addView(replace);
        }
        if (selected != null) {
            activity.processingLabel(contextFields, "Bản xem trước dùng lời gốc bạn vừa nhập.");
        }
        String[] glossary = {session.translationGlossary().trim()};
        Button glossaryAction = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        glossaryAction.setAllCaps(false);
        glossaryAction.setMinimumHeight(activity.dp(48));
        glossaryAction.setText(glossaryActionLabel(glossary[0]));
        contextFields.addView(glossaryAction, new LinearLayout.LayoutParams(-2, -2));
        glossaryAction.setOnClickListener(v -> {
            LinearLayout editor = activity.form();
            activity.processingLabel(editor, "Mỗi dòng một quy ước. Ví dụ: Mai gọi John là anh.");
            EditText input = new EditText(activity);
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            input.setGravity(Gravity.TOP | Gravity.START);
            input.setMinLines(3);
            input.setMaxLines(5);
            input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4000)});
            input.setText(glossary[0]);
            editor.addView(input, new LinearLayout.LayoutParams(-1, -2));
            activity.showSheet(activity.sheet("Quy ước dịch", editor)
                    .setNegativeButton("Hủy", null)
                    .setPositiveButton("Lưu", (editorDialog, which) -> {
                        glossary[0] = input.getText().toString().trim();
                        glossaryAction.setText(glossaryActionLabel(glossary[0]));
                    }));
        });
        AlertDialog dialog = activity.sheet(retry ? "Dịch tiếp" : selected == null ? "Dịch transcript" : "Dịch câu theo ngữ cảnh", form)
                .setNegativeButton("Đóng", null).setPositiveButton("Dịch", null).create();
        Button bySentence = methods == null ? null : activity.button(methods, "Theo câu", () -> {
            contextual[0] = false;
            refreshModelStatus();
        });
        Button byContext = methods == null ? null : activity.button(methods, "Ngữ cảnh", () -> {
            contextual[0] = true;
            refreshModelStatus();
        });
        if (byContext != null) byContext.setMaxLines(1);
        activity.showSheet(dialog);
        Button submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Runnable refresh = () -> {
            boolean useContext = contextual[0];
            ContextModelStore model = models.contextModel(contextEngine.getSelectedItemPosition() == 1);
            boolean installing = models.contextPending() && models.contextOperationModel == model;
            boolean installed = supported && model.installed() && !installing;
            contextEngine.setEnabled(supported && !session.busy);
            boolean useM2m = !useContext && sentenceEngine.getSelectedItemPosition() == 1;
            boolean needsQwen = useContext;
            sentenceModelStatus.setText(useM2m
                    ? (supported ? models.m2mStatus() : "M2M100 cần Android ARM64.")
                    : retry ? "ML Kit · Dùng gói ngôn ngữ đã tải cho các câu còn thiếu."
                    : "ML Kit · Hai ngôn ngữ khác tiếng Anh được dịch qua tiếng Anh.");
            if (bySentence != null) {
                bySentence.setSelected(!useContext);
                byContext.setSelected(useContext);
            }
            sentenceFields.setVisibility(useContext ? View.GONE : View.VISIBLE);
            context.setVisibility(useContext ? View.VISIBLE : View.GONE);
            contextFields.setVisibility(installed ? View.VISIBLE : View.GONE);
            if (useContext) {
                String message = supported ? activity.contextDownloadStatus(models, model) : "Qwen yêu cầu Android ARM64.";
                modelStatus.setVisibility(View.VISIBLE);
                if (installed) message = "Đã tải · " + activity.storageSize(model.file.length());
                if (!message.contentEquals(modelStatus.getText())) modelStatus.setText(message);
            }
            submit.setText(useM2m && models.m2mDownloading ? "Dừng"
                    : useM2m && !models.m2m.installed() ? "Tải M2M100"
                    : needsQwen && !installed ? activity.contextDownloadAction(models, model) : "Dịch");
            submit.setEnabled(needsQwen && models.contextPending()
                    ? installing && !models.contextCancelling() : !session.busy && (!(needsQwen || useM2m) || supported));
        };
        AdapterView.OnItemSelectedListener refreshSelection = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refresh.run();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        };
        target.setOnItemSelectedListener(refreshSelection);
        sentenceEngine.setOnItemSelectedListener(refreshSelection);
        contextEngine.setOnItemSelectedListener(refreshSelection);
        translationModelFeedback = refresh;
        dialog.setOnDismissListener(window -> {
            if (translationModelFeedback == refresh) translationModelFeedback = null;
        });
        refresh.run();
        submit.setOnClickListener(view -> {
            boolean useM2m = !contextual[0] && sentenceEngine.getSelectedItemPosition() == 1;
            boolean needsQwen = contextual[0];
            boolean light = contextEngine.getSelectedItemPosition() == 1;
            ContextModelStore model = models.contextModel(light);
            if (needsQwen && models.contextPending()) {
                if (models.contextOperationModel == model) models.cancelContextDownload();
                return;
            }
            if (session.busy || session.cues.isEmpty() || !activity.isCurrentTranslationVideo(video)
                    || (selected != null && !session.cues.contains(selected))) return;
            if (useM2m && (models.m2mDownloading || !models.m2m.installed())) {
                models.toggleM2mDownload();
                return;
            }
            if (needsQwen && !model.installed()) {
                models.downloadContextModel(model);
                return;
            }
            if (!contextual[0]) {
                if (!retry) {
                    activity.getPreferences(android.content.Context.MODE_PRIVATE).edit().putBoolean("sentence_engine_m2m", useM2m).apply();
                }
                settings.targetLanguage = retry ? retryTarget : codes.get(target.getSelectedItemPosition());
                settings.save(activity);
                dialog.dismiss();
                activity.showCueTranscript();
                if (retry) {
                    session.retrySentenceTranslation(retryTarget, useM2m);
                } else if (useM2m) {
                    session.translateWithM2m(settings.targetLanguage, session.sourceLanguage, replaceSentences.isChecked());
                } else {
                    session.retranslate(settings.targetLanguage, session.sourceLanguage, replaceSentences.isChecked());
                }
                return;
            }
            if (!retry && selected == null && session.targetLanguage != null && !"vi".equals(session.targetLanguage)
                    && !replace.isChecked()) {
                replace.setError("Xác nhận đổi ngôn ngữ bản dịch.");
                return;
            }
            models.selectContextModel(light);
            String contextGlossary = glossary[0];
            dialog.dismiss();
            if (selected == null) {
                settings.targetLanguage = "vi";
                settings.save(activity);
                activity.showCueTranscript();
                session.translateInContext(null, null, contextGlossary, replace.isChecked(), activity.translationPlaybackPosition(), null);
                return;
            }
            session.translateInContext(selected, editedSource, contextGlossary, true, 0, (results, decision) -> {
                if (!activity.isCurrentTranslationVideo(video)) {
                    decision.accept(false);
                    return;
                }
                String[] rows = new String[results.size()];
                for (int i = 0; i < rows.length; i++) {
                    rows[i] = TranscriptParser.formatTime(selected.startMs) + "  " + editedSource + "\n" + results.get(i);
                }
                AlertDialog preview = new AlertDialog.Builder(activity)
                        .setTitle("Xem trước bản dịch")
                        .setAdapter(new android.widget.ArrayAdapter<>(activity,
                                android.R.layout.simple_list_item_1, rows), null)
                        .setNegativeButton("Bỏ", (window, which) -> decision.accept(false))
                        .setPositiveButton("Áp dụng", (window, which) -> {
                            decision.accept(true);
                            activity.showCueTranscript();
                        }).create();
                preview.setOnDismissListener(window -> decision.accept(false));
                activity.showSheet(preview);
            });
        });
    }

    private String glossaryActionLabel(String glossary) {
        int count = 0;
        for (String line : glossary.split("\\R")) {
            if (!line.trim().isEmpty()) count++;
        }
        return count == 0 ? "+ Thêm quy ước dịch" : "Quy ước dịch · " + count + " mục";
    }

}
