# Yabackup

Yet another mc saves data backup plugin, but support compress to zstd

## Build from source

``` sh
./gradlew build
cp -r build/libs/yabackup-*-all.jar <path/to/your-server-plugins-dir>
```

## Command usage

``` sh
/backup [zstd|zip]
```

## Config option

``` yaml
# Yabackup configuration file.
#
# Below is an explanation of the some options:
#
# * backup.backups_dir: Path to store backups. Supports both absolute and relative paths.
# * backup.keep_last_n_backups: Set to 0 to kepp all.
# * backup.backups_dir_storage_limit: Set to 0 to disable the storage limit.
# * backup.on_player_join: on join backup.
# * backup.on_player_quit: on quit backup.
# * compress.default_type: Supported values: "zstd", "zip".
# * compress.zstd_level: Valid range: 1-22, Higher means better compression, slower speed.
# * compress.zip_level: Valid range: 0-9. same above zstd.
# * interval_backup_task.initial_delay_minutes: Delay after server startup before first interval backup.
# * interval_backup_task.interval_minutes: Time between each interval backup.
# * interval_backup_task.skip_if_no_players: interval backups will be skipped when no players are online.
#
# Note:
# 1. When both keep_last_n_backups and backups_dir_storage_limit are set,
# the plugin tries to keep the latest N backups within the size limit.
# If the total size still exceeds the limit, older backups will be deleted anyway.
# 2. When the last player quits, a backup is still created
# regardless of the value of on_player_quit. This ensures that a backup
# is still created when the last player leaves, even if it happens between
# interval backups.

backup:
  backups_dir: ./backups
  keep_last_n_backups: 10
  backups_dir_storage_limit: 1024
  on_player_join: false
  on_player_quit: true
compress:
  default_type: zstd
  zstd_level: 10
  zip_level: 6
interval_backup_task:
  enable: true
  initial_delay_minutes: 1
  interval_minutes: 20
  skip_if_no_players: true
```
