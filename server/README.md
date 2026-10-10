# 家庭血压服务

这个服务接收 App 的完整记录快照，补传历史记录并同步手机的新增、修改和删除，提供私有网页查看和 CSV 导出。手机是数据来源，不提供网页修改或双向同步，没有大模型调用或公开注册。新旧数据收齐后才一次性更新，上传中断时保留上次完整版本。

当前家庭服务地址为 `https://bp.282412.xyz`，部署目录为 `/opt/blood-pressure`，Compose 项目名为 `pressure-diary`。该部署的 `server/.env` 使用 `BP_DOMAIN=bp.282412.xyz`；仓库提供通用 `.env.example`，部署其他域名时修改这个值。真实网页密钥、各手机上传码、SSH 私钥和家庭测量数据库分别保存在部署环境或本机私有目录，均不随源代码提交。

## Docker 部署

配置文件：`Dockerfile` 构建 API，`compose.yaml` 管理容器和持久化卷，`Caddyfile` 配置 HTTPS 反向代理，`.env.example` 是域名配置模板，`.dockerignore` 排除运行数据和本机环境。`requirements.txt` 固定服务端依赖，`requirements-test.txt` 增加 API 测试依赖。

在 Linux 服务器上安装 Docker 和 Compose。为一个子域名添加指向服务器公网 IPv4 的 A 记录，先选择“仅 DNS”，确保 80/443 端口可访问且未被其他网站占用。若已有网站使用这些端口，应接入已有反向代理，不能直接启动本配置的 Caddy 抢占端口。

把此目录复制到 `/opt/blood-pressure`，运行：

```sh
cd /opt/blood-pressure
cp .env.example .env
# 编辑 .env，将 BP_DOMAIN 改为自己的子域名，值不含 https://
docker compose -p pressure-diary config --quiet
docker compose -p pressure-diary up -d --build
docker compose -p pressure-diary ps
```

Caddy 为域名申请并续期证书，HTTP 自动转至 HTTPS。API 的 8000 端口仅在容器网络中可用，不发布到宿主机。SQLite 数据、证书和 Caddy 配置保存在命名卷中，容器重建不会删除它们。服务以非 root 用户运行，容器根文件系统只读；API/Caddy 的内存上限为 192/128 MiB，日志每个容器最多 3 个 10 MiB 文件。不记录请求正文或 Authorization 标头。

HTTPS 的要求与自动续期依据：[Caddy Automatic HTTPS](https://caddyserver.com/docs/automatic-https)。Docker 部署方式参考：[FastAPI 官方部署文档](https://fastapi.tiangolo.com/deployment/docker/)。

## 密钥及手机配置

```sh
cd /opt/blood-pressure
# 首次生成网页查看密钥；再次运行会拒绝覆盖已有密钥
docker compose -p pressure-diary exec -T api python manage.py init-admin
# 每位家人或每台手机生成各自的上传密钥
docker compose -p pressure-diary exec -T api python manage.py add-device --name '家人手机'
docker compose -p pressure-diary exec -T api python manage.py list-devices
```

把输出保存在自己的私有位置，不提交到仓库。网页查看密钥用于 HTTPS 首页，上传密钥用于手机齿轮 → 服务器设置。不要把网页查看密钥填到手机上。网页密钥只在当前页面的内存中使用，不写入浏览器存储；点击退出或关闭页面后需要重新填写。

停用遗失手机的上传密钥或更换网页密钥：

```sh
docker compose -p pressure-diary exec -T api python manage.py disable-device --id '设备编号'
docker compose -p pressure-diary exec -T api python manage.py rotate-admin
```

停用手机不删除既有记录。更换网页密钥会立即使旧密钥失效，需自行保留新输出。

也可使用 `docker compose -p pressure-diary exec -T api python manage.py set-admin`，从标准输入读取自定义网页查看密钥，不需要把密钥写进命令参数或源代码。自定义密钥使用随机盐和 PBKDF2-SHA256 摘要保存；手机上传密钥保持独立不变。

## 备份与升级

```sh
docker compose -p pressure-diary exec -T api python manage.py backup --output /data/backup-2026-10-10.sqlite3
docker compose -p pressure-diary cp api:/data/backup-2026-10-10.sqlite3 ./backup-2026-10-10.sqlite3
chmod 600 ./backup-2026-10-10.sqlite3
```

备份包含当时已提交的全部记录、家人列表和密钥摘要，使用 SQLite 在线备份接口，包含 WAL 中已提交的数据；同名目标已存在时拒绝覆盖。请复制到另一台设备保存，仅留在服务器磁盘上无法应对整盘损坏。本版本没有配置自动定时备份。

升级时先备份，替换代码后运行 `docker compose -p pressure-diary up -d --build`，保留同一项目名和命名卷。不要使用 `down -v`、删除命名卷或覆盖数据库；`docker compose stop` 只停止当前服务。若需从备份恢复，应先停止 API，并在保留当前数据副本后恢复到同一个 `/data/blood-pressure.sqlite3` 路径，不能把运行中的 WAL 文件当作独立备份复制。

## API 契约

全部数据接口通过 `Authorization: Bearer <密钥>` 鉴权。只允许 HTTPS 访问公开端点，App 不跟随重定向，不跳过系统的证书或主机名校验。

| 接口 | 密钥 | 行为 |
| --- | --- | --- |
| GET `/healthz` | 无 | 只返回健康状态和协议版本 |
| GET `/api/v1/device` | 手机上传密钥 | 返回当前设备编号和家人名称，连接测试不产生记录 |
| POST `/api/v1/measurements` | 手机上传密钥 | 兼容旧 App；首次 V2 同步后拒绝旧单条上传，防止破坏完整快照 |
| POST `/api/v2/snapshots` | 手机上传密钥 | 声明安装标识、版本、条数、创建编号水位和内容摘要；已提交版本可恢复丢失的确认 |
| PUT `/api/v2/snapshots/{syncId}/pages/{index}` | 手机上传密钥 | 每页 25 条，暂存并校验，尚不修改网页可见记录 |
| POST `/api/v2/snapshots/{syncId}/commit` | 手机上传密钥 | 收齐全部页面后，原子更新当前手机记录并删除快照中已不存在的记录 |
| GET `/api/v1/devices` | 网页查看密钥 | 家人列表和记录数量 |
| GET `/api/v1/measurements` | 网页查看密钥 | 分页查询，参数 `device_id`、`from_ms`、`to_ms`、`limit`、`offset` |
| GET `/api/v1/measurements.csv` | 网页查看密钥 | 导出选定范围内全部记录 |

时间范围为 `from_ms <= measuredAt < to_ms`，按浏览器本机日期筛选；记录展示和 CSV 使用上传时手机提供的测量时区偏移，网页与手机时区不同需要留意筛选边界。每页最多 200 条，网页每页 50 条。记录编号按安装标识和本地行编号生成，服务端从上传密钥确定所属家人，客户端不能替换所属家人。

请求体最多 16 KiB，字段为协议版本 `schemaVersion=1`、UUID `recordId`、整数 `systolic/diastolic/pulse`、毫秒时间 `measuredAt`、`note`、`timeZone`、`utcOffsetMinutes`、来源 `manual/camera`。数值范围与 App 一致：高压 50–260、低压 30–160 且低于高压、脉搏 30–220；备注最多 2048 个字符，超出协议范围的记录不上传，手机保存不受影响。拒绝额外字段和把字符串、布尔值当作整数的请求。

V2 开始请求为 `{installationId,version,recordCount,highWatermark,digest}`，版本为正整数，一轮最多 20,000 条。页面请求为 `{records:[...]}`，单页最多 512 KiB；每条沿用 V1 的数值字段，`schemaVersion=2`，增加 `localId`，来源支持 `manual/camera/import/legacy`。编号仍由安装标识和本地行编号生成；旧记录沿用编号，不重复创建。首次升级无法恢复旧来源时使用 `legacy`，服务器保留已经知道的来源和未改变的测量时区。

摘要为 SHA-256：每页按实际 UTF-8 请求字节计算摘要；整轮摘要为按页面顺序拼接每页小写十六进制摘要及换行后的 SHA-256，空快照使用空字节摘要。服务器验证页面条数、内容摘要、所属安装及创建编号顺序后，在同一 SQLite 事务中从旧到新更新，再删除缺失项，仅影响当前手机。错误或缺页时整轮回滚，重试幂等，旧版本不能覆盖新版本。每台手机的上传密钥首次成功提交后绑定安装标识；换新手机请生成新的上传密钥，不能通过重装空应用清空原手机记录。原手机明确删除全部记录时，保留非零创建编号水位，可提交空快照。

服务器数据库升级到版本 2，自动添加列与暂存表，保留家人、密钥摘要和旧记录。暂存快照最多每设备一份，更新版本会替换旧暂存，超过一天的未提交快照在开始新同步时清理。手机本地修改在 SQLite 事务中增加版本；确认完整提交后才持久化服务器确认版本，失败和应用重启不会丢失待同步状态。

CSV 的测量时间加“测量于”文字前缀，避免表格软件自动转换日期导致 `########`。家人名称和备注的公式前缀会转义，防止表格将用户输入作为公式执行。

## 本地验证

```sh
python -m venv .venv
# 激活虚拟环境后
python -m pip install -r requirements-test.txt
python -m unittest discover -s tests -v
```

API 回归涵盖权限隔离、严格校验、按家人区分、分页、日期边界、重复与并发重复、密钥停用、更换及数据库备份重启。真实 HTTPS 联调脚本 `tests/run_android_integration.py` 另需测试环境中的 `cryptography`、JDK、已运行过 `../test-behavior.ps1` 的 Java 测试类；它使用临时数据库和证书，验证 Java → HTTPS → SQLite，并检查不可信证书确实被拒绝。所有测试数据为合成数据，不提交到部署后的家庭数据库。

手机验收：覆盖安装 1.3.1 APK，确认旧记录仍在；保留已有上传配置，联网打开应用应补传历史记录。分别新增、编辑、删除和导入，在网页刷新后核对。断网完成这些操作，恢复联网并重新打开应用，应收敛为手机当前记录，其他家人数据保持。连接中断时服务器保留上次完整记录，本地保存照常完成。无需在首页显示未上传标记。
