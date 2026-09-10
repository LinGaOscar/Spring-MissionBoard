# 介紹短片產片 pipeline

重跑整套：

```bash
cd scripts/intro-video
python3 capture.py                                    # 重置 DB、起 app、擷取真實畫面（唯一需要瀏覽器的一步）
python3 sequencer.py ../../.superpowers/intro-video/raw ../../.superpowers/intro-video/seq
./encode.sh ../../.superpowers/intro-video/seq ../../docs/missionboard-intro.mp4
```

只調節奏、字幕或緩動時**不必重跑 `capture.py`**——改 `storyboard.py` 後重跑 `sequencer.py` 與 `encode.sh` 即可，幾秒鐘。

測試：`python3 -m unittest discover -s tests -t .`

## 檔案職責

| 檔案 | 職責 |
|---|---|
| `storyboard.py` | 純資料：場景、字幕、秒數、紅框、拖曳路徑、寫死日期 |
| `easing.py` | 緩動與路徑取樣（純函式） |
| `compositing.py` | PIL 基元：sprite、字幕帶、紅框、游標、交叉淡入、Ken Burns |
| `sequencer.py` | 分鏡 + raw 幀 → 30fps 圖片序列 |
| `capture.py` | Playwright 擷取與環境編排 |
| `encode.sh` | ffmpeg 單次編碼 |

## 踩過的坑

- **原生 HTML5 拖曳影像 CDP 拍不到**（由瀏覽器/OS 在頁面外合成），`Input.dispatchMouseEvent` 也發動不了原生 DnD。所以拖曳一律是合成的：從乾淨畫面裁真實卡片當 sprite，再依緩動曲線貼到補間位置。
- **組片不可用 ffmpeg concat demuxer**，會少算約 2 秒且末幀時長不穩；只用 `-framerate 30 -i seq/%05d.png`。
- **不可用 `minterpolate` 補幀**，UI 截圖會產生鬼影、文字邊緣糊掉。
- **本機無 `PingFang.ttc`**，字幕用 `STHeiti Medium.ttc`；不可用 Hiragino（日文字形）。
- **截圖高度必須是偶數**（812，不是 811），否則 `yuv420p` 編不出來。
- **`capture.py` 必須自己重置 DB**，流程會現場建立專案與任務，不重置第二次跑資料就疊加。
- Ken Burns 只用在片頭/片尾卡；UI 畫面縮放會讓 14px 文字重取樣閃爍。
- **`storyboard.DATES` 是近未來絕對日期（如 `due_a=2026-09-18`），但前端 `isOverdueDate` 是拿 `new Date()`（執行當下）去比對，不是拿寫死的日期比**：日期雖然寫死，過期與否仍隨重跑當下的系統時間變動。等這些日期都過了還重跑 `capture.py`，到期日會渲染成紅色粗體的逾期樣式，畫面跟現有素材不一樣。重跑前記得先把 `DATES` 往後推。
