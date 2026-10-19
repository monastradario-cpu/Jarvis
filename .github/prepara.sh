#!/usr/bin/env bash
# Scarica i modelli vocali e la libreria della voce neurale (non sono nel repository: pesano troppo)
set -e
curl -sSL -o modello.zip https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip
unzip -q -o modello.zip
mkdir -p app/src/main/assets/model-it
cp -r vosk-model-small-it-0.22/* app/src/main/assets/model-it/
echo "vosk-model-small-it-0.22" > app/src/main/assets/model-it/uuid
curl -sSL -o voce.zip https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip
unzip -q -o voce.zip
mkdir -p app/src/main/assets/model-spk
cp -r vosk-model-spk-0.4/* app/src/main/assets/model-spk/
echo "vosk-model-spk-0.4" > app/src/main/assets/model-spk/uuid
mkdir -p app/libs
curl -sSL -o app/libs/sherpa-onnx.aar https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar
rm -rf modello.zip voce.zip vosk-model-small-it-0.22 vosk-model-spk-0.4
du -sh app/src/main/assets/* app/libs
