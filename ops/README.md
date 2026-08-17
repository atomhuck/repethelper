# RepetHelper production runbook

Инструкция рассчитана на Ubuntu 24.04, один VPS, домен с HTTPS и чистую базу данных.

## 1. Подготовить доступ

Создайте отдельный ключ Ed25519 на рабочем компьютере. Приватный файл не отправляйте в чат, почту или панель провайдера. На сервер передаётся только файл с расширением `.pub`.

При создании VPS выберите Ubuntu 24.04 и добавьте публичный ключ в панели провайдера. Пока настройка не завершена, не закрывайте исходную root-сессию.

Скопируйте репозиторий и публичный ключ на сервер, затем от имени `root` выполните:

```bash
bash /path/to/repethelper/ops/provision-server.sh /root/repethelper-deploy.pub
```

Скрипт:

- создаст swap 2 ГБ;
- установит Docker, Compose, Restic, UFW, Fail2ban и автоматические security-обновления;
- создаст пользователя `repethelper-deploy`;
- разрешит в firewall только SSH, HTTP и HTTPS.

Не закрывая root-сессию, обязательно проверьте новый вход:

```bash
ssh repethelper-deploy@SERVER_IP
```

Только после успешной проверки входа по ключу запретите парольный SSH и прямой вход `root`:

```bash
sudo /path/to/repethelper/ops/harden-ssh.sh --confirmed-key-login
```

Скрипт специально вынесен в отдельный шаг, чтобы не заблокировать доступ к VPS до проверки ключа. Если используется нестандартный порт, задайте одинаковый `SSH_PORT` при выполнении обоих скриптов.

После успешного входа разместите проект:

```bash
sudo git clone https://github.com/atomhuck/repethelper.git /opt/repethelper
sudo chown -R repethelper-deploy:repethelper-deploy /opt/repethelper
cd /opt/repethelper
```

## 2. Подготовить GitHub-образ

Workflow `.github/workflows/container.yml` запускает Maven-тесты и публикует:

- `ghcr.io/atomhuck/repethelper:main`;
- `ghcr.io/atomhuck/repethelper:sha-<короткий SHA>`.

После первого успешного запуска workflow откройте пакет `repethelper` в GitHub Packages и установите видимость `Public`. Серверу не потребуется хранить GitHub-токен.

Для production всегда используйте SHA-тег. `main` оставлен только для аварийной проверки.

## 3. Настроить домен и секреты

У регистратора создайте `A`-запись домена на IPv4 VPS. `AAAA` добавляйте только при реально настроенном IPv6. Дождитесь, пока домен начнёт возвращать IP сервера.

Создайте закрытый файл настроек:

```bash
cd /opt/repethelper
cp .env.production.example .env.production
chmod 600 .env.production
```

Замените все значения-заглушки `replace-*` и `your-domain`. Пароли удобно создавать командой:

```bash
openssl rand -base64 32
```

Правила:

- `SITE_DOMAIN` содержит только домен, без `https://` и пути;
- `IMAGE_TAG` имеет вид `sha-abcdef0`;
- `POSTGRES_PASSWORD` и `TEACHER_PASSWORD` — не менее 16 символов;
- `TEACHER_CODE` — новый непубличный код из 8–30 букв, цифр, `_` или `-`;
- значение с пробелами, например `TEACHER_NAME`, заключайте в двойные кавычки;
- `.env.production` нельзя добавлять в Git.

Production-профиль завершит запуск с ошибкой, если обнаружит тестовый пароль, короткий секрет или небезопасный WebSocket Origin.

## 4. Настроить внешние копии

Создайте приватный бакет Yandex Object Storage и отдельный сервисный аккаунт, которому разрешена работа только с этим бакетом. Добавьте S3 endpoint, статический ключ и пароль Restic в `.env.production`.

Инициализируйте зашифрованное хранилище и сделайте первую копию:

```bash
cd /opt/repethelper
./ops/init-backup.sh
./ops/backup.sh
sudo ./ops/install-timers.sh
```

Ежедневная копия включает `pg_dump`, том вложений и production-конфигурацию без секретов. Хранятся 7 ежедневных, 4 еженедельных и 3 ежемесячных снимка. Раз в месяц Restic проверяет часть зашифрованного репозитория.

Проверка восстановления не затрагивает production-тома:

```bash
./ops/restore-test.sh latest
```

Скрипт поднимает временный PostgreSQL, восстанавливает базу и файлы, проверяет таблицы и удаляет только созданные им временные ресурсы.

## 5. Первый запуск и обновления

Убедитесь, что GitHub Actions опубликовал нужный SHA-образ, затем выполните:

```bash
cd /opt/repethelper
./ops/deploy.sh sha-abcdef0
```

При первом запуске, пока базы ещё нет, предварительный backup пропускается. При последующих обновлениях порядок такой:

1. согласованная копия базы и файлов;
2. загрузка SHA-образа;
3. миграции Flyway и запуск;
4. внутренний healthcheck и запрос `https://DOMAIN/login`;
5. автоматический возврат на предыдущий SHA при ошибке.

Для ручного отката укажите ранее работавший SHA:

```bash
./ops/deploy.sh sha-previous
```

## 6. Закрытая админ-панель

Админ-панель выключена по умолчанию и не открывается на публичном домене. После выпуска кода:

1. Добавьте в `/opt/repethelper/.env.production` три разных случайных секрета для `APP_ADMIN_GATEWAY_SECRET`, `APP_ADMIN_TOTP_ENCRYPTION_KEY` и `APP_ADMIN_BOOTSTRAP_TOKEN`.
2. Не включайте `APP_ADMIN_ENABLED` до настройки Tailscale.
3. От root выполните `bash /opt/repethelper/ops/install-admin-tailscale.sh`, войдите в собственный tailnet и одобрите VPS.
4. Убедитесь, что `tailscale serve status` направляет только на `http://127.0.0.1:8081`.
5. Установите `APP_ADMIN_ENABLED=true`, разверните текущий SHA и откройте приватный адрес Tailscale с путём `/control/bootstrap`.
6. Введите bootstrap-токен, создайте отдельный пароль администратора, добавьте TOTP в приложение-аутентификатор и сохраните recovery-коды.
7. После первой настройки удалите `APP_ADMIN_BOOTSTRAP_TOKEN` из `.env.production` и разверните тот же образ ещё раз: bootstrap автоматически станет недоступен.

Никогда не публикуйте порт `8081` наружу и не добавляйте маршрут `/control` в публичный Caddy-сайт.

## 7. Проверка после запуска

```bash
docker compose --env-file .env.production -f compose.production.yaml ps
docker compose --env-file .env.production -f compose.production.yaml logs --tail=100
sudo ufw status
systemctl list-timers 'repethelper-backup*'
```

Проверьте:

- `http://DOMAIN` перенаправляет на HTTPS;
- `https://DOMAIN/actuator/health` возвращает `404`;
- извне недоступны порты `5432` и `8080`;
- преподаватель и ученик могут одновременно открыть доску;
- после `sudo reboot` база, вложения и сертификат сохраняются;
- `./ops/restore-test.sh latest` успешно завершается.

Для диагностики приложения:

```bash
docker compose --env-file .env.production -f compose.production.yaml logs -f app
```
