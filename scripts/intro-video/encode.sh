#!/bin/bash
# 組片：只用圖片序列，不用 concat demuxer——後者在這台機器上會少算約 2 秒且末幀時長不穩
set -euo pipefail

SEQ_DIR="${1:?用法: encode.sh <seq目錄> <輸出mp4>}"
OUT="${2:?用法: encode.sh <seq目錄> <輸出mp4>}"

ffmpeg -y -framerate 30 -i "${SEQ_DIR}/%05d.png" \
    -c:v libx264 -pix_fmt yuv420p -preset slow -crf 20 \
    -movflags +faststart "${OUT}"
