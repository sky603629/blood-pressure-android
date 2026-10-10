"""Local administration: no public registration endpoints and no plaintext token database."""
import argparse
import os
import sys
from storage import Store


def main():
    parser = argparse.ArgumentParser(description="家庭血压服务管理")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("init-admin", help="首次生成网页查看密钥")
    commands.add_parser("rotate-admin", help="重新生成并替换网页查看密钥")
    commands.add_parser("set-admin", help="从标准输入读取并设置自定义网页查看密钥")
    add = commands.add_parser("add-device", help="为一位家人的手机生成上传密钥")
    add.add_argument("--name", required=True)
    commands.add_parser("list-devices", help="查看家人及其记录数量，不显示密钥")
    disable = commands.add_parser("disable-device", help="停用某台手机的上传密钥")
    disable.add_argument("--id", required=True)
    backup = commands.add_parser("backup", help="生成包含 WAL 已提交记录的一致备份")
    backup.add_argument("--output", required=True)
    args = parser.parse_args()
    store = Store(os.environ.get("BP_DATABASE", "data/blood-pressure.sqlite3"))
    store.initialize()
    try:
        if args.command in ("init-admin", "rotate-admin"):
            print("网页查看密钥（只显示本次生成的值，请自行保存）：")
            print(store.create_admin(rotate=args.command == "rotate-admin"))
        elif args.command == "set-admin":
            key = sys.stdin.readline(258).rstrip("\r\n")
            store.set_admin_key(key)
            print("网页查看密钥已更新；手机上传密钥和测量记录保留。")
        elif args.command == "add-device":
            device = store.create_device(args.name)
            print("家人：" + device["name"])
            print("设备编号：" + device["id"])
            print("上传密钥（填入这位家人手机 App 的服务器设置）：")
            print(device["token"])
        elif args.command == "list-devices":
            for item in store.devices():
                print(f"{item['id']}\t{item['name']}\t启用={bool(item['enabled'])}\t记录={item['recordCount']}")
        elif args.command == "disable-device":
            if not store.disable_device(args.id):
                raise ValueError("Device not found")
            print("上传密钥已停用；既有记录保留。")
        elif args.command == "backup":
            store.backup(args.output)
            print("备份已保存：" + args.output)
    except (ValueError, FileExistsError) as error:
        parser.exit(1, str(error) + "\n")


if __name__ == "__main__":
    main()
