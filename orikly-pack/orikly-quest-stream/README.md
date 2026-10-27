# Orikly Quest Stream

Живая трансляция экрана Meta Quest 3 в браузер по локальной сети (MJPEG).
Подтверждение захвата на очках — один раз. Дальше трансляцию и запись запускают и останавливают с ноутбука (`viewer/index.html`).

## 1. Keystore (один раз, навсегда)

Keystore — файл с ключом подписи APK. HMS обновляет приложение только если подпись та же,
поэтому файл и пароли **нельзя терять**. Храните копию в надёжном месте, в репозиторий не кладите.

Нужна Java (JDK 17+), в ней есть `keytool`:

```
keytool -genkeypair -v -keystore orikly-release.jks -alias orikly -keyalg RSA -keysize 2048 -validity 10000
```

Спросит пароль и данные (имя/организацию — можно любые). Затем закодировать в base64:

- macOS: `base64 -i orikly-release.jks -o keystore.txt`
- Linux: `base64 -w0 orikly-release.jks > keystore.txt`
- Windows (PowerShell): `[Convert]::ToBase64String([IO.File]::ReadAllBytes("orikly-release.jks")) > keystore.txt`

## 2. Секреты GitHub

Repository → Settings → Secrets and variables → Actions → New repository secret:

| Имя | Значение |
|---|---|
| `KEYSTORE_BASE64` | содержимое `keystore.txt` |
| `KEYSTORE_PASSWORD` | пароль keystore |
| `KEY_ALIAS` | `orikly` |
| `KEY_PASSWORD` | пароль ключа (если не задавали отдельно — тот же, что у keystore) |
| `STREAM_KEY` | ключ доступа к трансляции, например `aula-7Kq2` (зашивается в APK) |

## 3. Выпуск релиза (через сайт)

Actions → Build APK → Run workflow → ввести версию (например `0.1.0`) → Run workflow.
Через 5–10 минут появится Release `v0.1.0` с файлом `orikly-quest-stream.apk`. Постоянная ссылка для HMS:

```
https://github.com/<owner>/<repo>/releases/latest/download/orikly-quest-stream.apk
```

Репозиторий должен быть **публичным**, иначе HMS не скачает файл по ссылке.
Каждая следующая версия — тот же Run workflow с большим номером (`0.1.1`, `0.2.0`…).

## 4. Проверка на очках

1. Установить через HMS, открыть «Orikly Stream».
2. Нажать «Iniciar transmisión», в системном окне выбрать «Pantalla completa» → «Empezar».
3. На экране будет IP. На ноутбуке в Chrome:
   - `http://IP:8080/stream?key=КЛЮЧ` — видео
   - `http://IP:8080/snapshot.jpg?key=КЛЮЧ` — один кадр
   - `http://IP:8080/status?key=КЛЮЧ` — состояние (JSON)

Параметры по умолчанию (в `StreamState.kt`): высота 480, 10 кадров/с, JPEG 60, до 4 зрителей на очки.

## 5. Страница на ноутбуке

1. Открыть `viewer/index.html` в Chrome (перетащить файл в окно Chrome).
2. Кнопка «Settings»: вписать ключ (тот же, что `STREAM_KEY`), имена и IP очков → Save. Chrome запомнит.
3. «Choose folder…» — папка для записей (Chrome запомнит её; при первом старте спросит разрешение).
4. «Start all» или «Start» на плитке — поток + запись. «Stop» — остановить и сохранить файл `Q3-01_дата_время.mp4`.
   Галочка «Record» выключена — только просмотр.
5. Клик по картинке — на весь экран, Esc — назад.

Если Chrome спросит «разрешить доступ к устройствам в локальной сети» — разрешить.
Не закрывайте вкладку во время записи: файл дописывается при Stop.
