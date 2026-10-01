"""离线审计某个 BD 网络存档：EMC 池、物化条目、已学习物品数。

用法: python netaudit.py <BDNet_*.dat>

存在理由：用户报「蓝图大炮只认得部分材料，其余显示无库存」时，需要在不进游戏的前提下
判断卡在物化链的哪一道闸（EMC 不够 / 条目被 maxMaterializedItems 截断 / 未学习 / 无价格）。
这些量全部直接躺在网络的存档里。
"""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from nbtdump import load  # noqa: E402

EMC_ITEM_TYPE = "beyondemc:stack_type/emc_item"
KNOWLEDGE_KEY = "beyondemc:knowledge"


def main(path):
    root = load(path)
    data = root.get("data", root)
    print("顶层键: %s" % sorted(root.keys()))
    print("data 键: %s" % sorted(data.keys()))

    # --- EMC 池 ---
    pool = None
    for key in ("beyondemc:emc", "beyondemc:emc_pool", "emc"):
        if key in data:
            pool = data[key]
            print("EMC 池 NBT 键 = %r -> %r" % (key, pool))
    if pool is None:
        for k, v in data.items():
            if "emc" in k.lower():
                print("疑似 EMC 键 %r = %r" % (k, v))

    # --- 物化条目 vs 真实库存 ---
    storage = data.get("UnifiedStorage")
    if isinstance(storage, dict):
        print("UnifiedStorage 键: %s" % sorted(storage.keys()))
        for k, v in storage.items():
            if isinstance(v, dict):
                print("  [%s] 子键: %s" % (k, sorted(v.keys())))

    def buckets(node, prefix=""):
        """递归找 typeId -> {物品: 数量} 形状的结构。"""
        if isinstance(node, dict):
            for k, v in node.items():
                if isinstance(v, dict) and v and all(
                        isinstance(x, dict) for x in v.values()):
                    print("%s%s -> %d 个物品" % (prefix, k, len(v)))
                else:
                    buckets(v, prefix + k + ".")

    buckets(data)

    # --- 存储条目（紧凑表） ---
    stacks = None
    if isinstance(data.get("UnifiedStorage"), dict):
        stacks = data["UnifiedStorage"].get("stacks")
    if isinstance(stacks, list):
        print("\n=== 存储条目 %d 条 ===" % len(stacks))
        by_type = {}
        for s in stacks:
            if not isinstance(s, dict):
                continue
            key = s.get("key") or {}
            t = key.get("type", "?")
            by_type.setdefault(t, []).append(s)
        for t in sorted(by_type):
            rows = by_type[t]
            print("\n[%s] %d 条" % (t, len(rows)))
            rows.sort(key=lambda s: -(s.get("amount") or 0))
            for s in rows:
                key = s.get("key") or {}
                name = key.get("item") or key.get("id") or repr(key)
                extra = ""
                if key.get("components"):
                    extra = "  (带 components: %s)" % ",".join(
                        sorted(key["components"].keys()))
                print("    %-46s %12s%s" % (name, s.get("amount"), extra))

    # --- 学习集合 ---
    kn = None
    for k, v in data.items():
        if k == KNOWLEDGE_KEY:
            kn = v
    if kn is None:
        # 可能嵌在子 compound 里
        for k, v in data.items():
            if isinstance(v, dict) and KNOWLEDGE_KEY in v:
                kn = v[KNOWLEDGE_KEY]
                print("学习集合位于 %s 下" % k)
    if isinstance(kn, list):
        print("已学习物品数 = %d" % len(kn))
        names = []
        for e in kn:
            if isinstance(e, dict):
                item = e.get("item") or e.get("id")
                names.append(str(item))
        if names:
            print("前 20 项: %s" % names[:20])
            print("后 20 项: %s" % names[-20:])
    else:
        print("未找到学习集合 %r" % KNOWLEDGE_KEY)


if __name__ == "__main__":
    main(sys.argv[1])
