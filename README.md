# MikuKits

> 作者：JunXieX　|　MikuMC系列插件交流群：1105054380　|　非开源项目，请勿二次分发

MikuKits 是一款基于 Paper Dialog 菜单的礼包插件，兼容 Paper 与 Folia。玩家通过图形化菜单查看并领取礼包，支持冷却、总次数与每日 / 每周 / 每月次数限制，奖励支持物品、金钱与指令。

## 功能特性

- 原生 Dialog 菜单，无需任何菜单 / GUI 前置
- 礼包支持冷却时间、总领取次数、每日 / 每周 / 每月周期限制
- 奖励类型：物品、金钱（Vault）、玩家指令、控制台指令
- 支持进服自动领取（auto-claim）
- 菜单与全部提示文案均可自定义
- Paper / Folia 双端兼容

## 环境要求

| 项目 | 要求 |
| --- | --- |
| 服务端 | Paper 或 Folia（api-version 26.2 版本线） |
| Java | 25 |
| 可选前置 | Vault（仅在配置了金钱奖励时需要） |

> SQLite 驱动由插件在开服时自动下载，无需手动安装。

## 安装

1. 将 `MikuKits-<版本>.jar` 放入服务端 `plugins/` 目录。
2. 启动一次服务端，插件会生成配置目录 `plugins/MikuKits/`：
   - `config.yml`：菜单与存储参数
   - `messages.yml`：全部提示与菜单文案
   - `kits/`：礼包定义（首次生成 `starter.yml`、`daily.yml` 示例）
3. 按需修改配置后，执行 `/mikukits reload` 或重启服务端生效。

## 配置说明

### config.yml

| 键 | 说明 | 默认 |
| --- | --- | --- |
| `menu.page-size` | 主菜单每页显示的礼包数量（1-20） | 8 |
| `menu.click-throttle-ms` | 同一玩家两次菜单点击的最小间隔（毫秒），用于防止连点刷取；0 表示不限制 | 200 |
| `storage.flush-interval-seconds` | 领取数据异步写回磁盘的间隔（秒） | 30 |

### kits/*.yml

每个文件即一个礼包，文件名（转为小写）即礼包 id。

| 键 | 说明 |
| --- | --- |
| `display-name` | 菜单中显示的礼包名（MiniMessage 格式） |
| `icon` / `icon-material` | 菜单图标：优先 `icon`（物品 base64），否则 `icon-material`（物品英文名） |
| `permission` | 领取所需权限节点，默认 `mikukits.kit.<礼包id>` |
| `permission-default` | 权限节点默认值：`true` / `false` / `op` / `not_op` |
| `auto-claim` | 是否进服自动领取 |
| `cooldown-seconds` | 冷却秒数，0 表示无冷却 |
| `max-claims` | 总领取次数上限，0 表示不限 |
| `period-limits` | 周期次数上限，支持 `daily` / `weekly` / `monthly` |
| `sort-order` | 菜单排序，数值越小越靠前 |
| `rewards` | 奖励列表 |

奖励项 `rewards` 的 `type` 取值：

- `item`：物品奖励，使用 `item`（base64）或 `material`（英文名），配合 `amount` 指定数量
- `money`：金钱奖励，`amount` 为金额（需要 Vault）
- `player-command`：以玩家身份执行 `command`
- `console-command`：以控制台身份执行 `command`

指令奖励支持占位符：`{player}`、`{uuid}`、`{world}`、`{x}`、`{y}`、`{z}`。

> 若礼包开启了 `auto-claim` 却未设置任何次数限制（`max-claims` 或 `period-limits`），玩家每次进服都会重复获得，插件会在启动时给出警告，请确认是否符合预期。

## 命令

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/mikukits` | 打开礼包菜单 | 无 |
| `/mikukits open [玩家]` | 为自己或指定玩家打开菜单 | 指定玩家时需 `mikukits.admin` |
| `/mikukits give <玩家> <礼包>` | 强制发放礼包（绕过限制，仍计入领取次数） | `mikukits.admin` |
| `/mikukits reload` | 重载配置与礼包 | `mikukits.admin` |
| `/mikukits info` | 查看运行状态 | `mikukits.admin` |

别名：`/kit`、`/kits`、`/mk`

## 权限节点

| 节点 | 说明 | 默认 |
| --- | --- | --- |
| `mikukits.kit.<礼包id>` | 领取对应礼包 | 由礼包的 `permission-default` 决定 |
| `mikukits.kit.*` | 领取全部礼包 | OP |
| `mikukits.admin` | 插件管理权限 | OP |
| `mikukits.bypass.cooldown` | 绕过冷却时间 | OP |
| `mikukits.bypass.limit` | 绕过次数限制 | OP |
