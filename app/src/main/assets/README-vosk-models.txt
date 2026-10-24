Put Vosk offline ASR models here before building the app.

Expected folder names:

- model-en-us
- model-ja
- model-vi
- model-ko
- model-cn

Example:

app/src/main/assets/model-ja/am/final.mdl
app/src/main/assets/model-ja/conf/model.conf
app/src/main/assets/model-ja/graph/...

AI Player picks a model from the source language setting:

- ja or jp -> model-ja
- vi or vn -> model-vi
- ko -> model-ko
- zh or cn -> model-cn
- auto/en/empty -> model-en-us

The model is unpacked to app storage on first use and then reused offline.
