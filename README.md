# TailCtl

Điều khiển Tailscale trên một điện thoại Android từ group Telegram: bật/tắt VPN, xem trạng thái, bật/tắt máy làm exit node.

```
Telegram group ──webhook──▶ Cloudflare Worker (app-share-bot)
                              ├─ /on /off /status ──FCM topic "tailscale"──▶ TailCtl (Android)
                              │                                               ├─ broadcast tới Tailscale (IPNReceiver)
                              │                                               └─ tự trả lời trạng thái + IP public về group
                              └─ /nodeon /nodeoff ──Tailscale API──▶ duyệt/gỡ route exit node của máy
```

- **App Android**: repo này (`app/src/main/java/com/mwg/tailctl/Main.kt`).
- **Bot**: worker `app-share-bot`, nằm trong repo [phuocbeyou/app-share](https://github.com/phuocbeyou/app-share), thư mục `bot/`.

## Lệnh trong group

| Lệnh | Việc |
|---|---|
| `/on` | Bật Tailscale trên máy |
| `/off` | Tắt Tailscale (tắt luôn exit node) |
| `/nodeon` | Cho máy làm exit node (Tailscale phải đang bật) |
| `/nodeoff` | Tắt exit node |
| `/status` | Trạng thái Tailscale, exit node, IP public |

Bot đang bật privacy mode nên trong group hãy bấm lệnh từ menu (Telegram gửi dạng `/on@bran_owl_bot`), gõ `/on` trơn có thể không tới bot.

## Setup

### 1. Firebase

1. Tạo project trên [Firebase console](https://console.firebase.google.com), thêm app Android package `com.mwg.tailctl`.
2. Tải `google-services.json` → bỏ vào `app/`.
3. Project settings → Service accounts → **Generate new private key** → file JSON này dùng cho worker (bước 4).

### 2. Build app

Tạo `local.properties` ở thư mục gốc (Android Studio tự thêm `sdk.dir`):

```properties
sdk.dir=/Users/<you>/Library/Android/sdk
# token bot Telegram, app dùng để gửi trạng thái về group
BOT_TOKEN=123456789:AA...
```

Cắm máy (bật USB debugging) rồi:

```bash
./gradlew installDebug
```

`local.properties` và `google-services.json` đã nằm trong `.gitignore`, đừng commit. Token bot được build vào APK nên **đừng chia sẻ file APK**.

### 3. Trên điện thoại

1. Cài Tailscale, đăng nhập, bật VPN 1 lần để cấp quyền VPN.
2. Mở app **TailCtl** 1 lần (app mới cài chưa mở thì không nhận được FCM).
3. Xiaomi/MIUI: Cài đặt → Ứng dụng → TailCtl → bật **Tự khởi chạy**, Tiết kiệm pin → **Không hạn chế**.
4. Muốn dùng `/nodeon`: Tailscale → Exit node → bật **Run as exit node** (1 lần, cài đặt này được giữ lại).

### 4. Worker (repo app-share, thư mục `bot/`)

Biến trong `bot/wrangler.jsonc`:

- `TAILSCALE_CHAT_ID`: id group Telegram được phép điều khiển.
- `EXIT_NODE`: tên máy trong tailnet (ví dụ `redmi-note-11`, xem trong admin console Tailscale).

Secrets (chạy trong `bot/`):

```bash
wrangler secret put FCM_SERVICE_ACCOUNT < service-account.json
```
```bash
wrangler secret put TS_CLIENT_ID
```
```bash
wrangler secret put TS_CLIENT_SECRET
```

- `FCM_SERVICE_ACCOUNT`: file JSON ở bước 1.3. Upload xong thì xoá file đó khỏi máy.
- `TS_CLIENT_ID` / `TS_CLIENT_SECRET`: Tailscale admin → Settings → **Trust credentials** → tạo OAuth client, chỉ tick **Devices › Core: Read** và **Devices › Routes: Write**.
- `TELEGRAM_BOT_TOKEN`, `WEBHOOK_SECRET`: đã có sẵn của bot app-share.

Deploy và đăng ký lại webhook + menu lệnh:

```bash
wrangler deploy
```
```bash
curl -X POST https://app-share-bot.<account>.workers.dev/setup -H "Authorization: Bearer <WEBHOOK_SECRET>"
```

Test bot: `node bot/check.mjs`.

## Cách hoạt động / lưu ý

- **Không dùng adb.** App không tự chạy `adb` lên chính máy mình được (cần root). Các lệnh adb tương ứng chỉ là `am broadcast -n com.tailscale.ipn/.IPNReceiver -a com.tailscale.ipn.CONNECT_VPN|DISCONNECT_VPN`, app gửi đúng intent đó bằng `sendBroadcast`.
- **Màn hình khoá:** MIUI kill process ~2s sau khi nhận FCM, nên phần bật/tắt → chờ → trả lời chạy trong foreground service (`ActionService`, hiện thông báo "TailCtl: …" vài giây).
- **Exit node:** Tailscale Android không cho app khác bật "Run as exit node". Máy bật sẵn 1 lần, worker duyệt/gỡ route `0.0.0.0/0` + `::/0` qua Tailscale API. Máy khoá màn hình vẫn chạy.
- **An toàn:** app chỉ nhận các lệnh cố định, không chạy lệnh tuỳ ý từ payload; worker chỉ nhận lệnh từ `TAILSCALE_CHAT_ID` và kiểm `WEBHOOK_SECRET` của Telegram.
- Lệnh FCM có TTL 60s: máy offline thì lệnh bị bỏ, không chạy lại muộn.

## Debug

```bash
adb logcat | grep -iE "TailCtl|IPNReceiver|UseExitNode|Killing.*tailctl"
```

- Bot báo `📤 … đã gửi` nhưng máy không trả lời: xem logcat có `Killing com.mwg.tailctl` (chưa tắt tối ưu pin / Tự khởi chạy) hay `reply failed` (mạng / token).
- `/nodeon` báo "chưa bật Run as exit node": làm bước 3.4.
