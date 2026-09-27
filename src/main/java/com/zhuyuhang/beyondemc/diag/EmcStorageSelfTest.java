package com.zhuyuhang.beyondemc.diag;

import com.wintercogs.beyonddimensions.api.dimensionnet.DimensionsNet;
import com.wintercogs.beyonddimensions.api.storage.key.IStackKey;
import com.wintercogs.beyonddimensions.api.storage.key.StackKeyRegistry;
import com.zhuyuhang.beyondemc.emc.EmcStackKey;
import com.zhuyuhang.beyondemc.emc.NetEmcAccessor;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 阶段 2 的诊断自检：验证 EMC 资源类型真的能进维度网络的存储、并能活着穿过序列化。
 *
 * <p>这些都是<b>无头可跑</b>的（用 {@code DimensionsNet(true)} 建一个临时网络，
 * 构造函数的 {@code temporary} 标志会让 {@code onServerTick} 直接 return，不产生副作用）。
 *
 * <p>为什么必须单独测序列化：自定义 {@code IStackKey} 最容易出的事故是
 * "类型没注册好 → 读档时 {@code catch(Throwable)} 静默丢条目"，
 * 表现为"EMC 全部消失但没有任何报错"。这个测试就是专门抓这个的。
 *
 * <p>阶段 2 结束后可以精简为 GameTest 或删除。
 */
public final class EmcStorageSelfTest {

    private EmcStorageSelfTest() {
    }

    /** EMC 资源类型是否已成功注册进 BD 的资源类型表。 */
    public static boolean isTypeRegistered() {
        try {
            // 注意：getType 是泛型方法，直接写 == EmcStackKey.INSTANCE 会被推断成
            // IStackKey<Object> 而"不可比较"，必须先落到 IStackKey<?> 上
            IStackKey<?> looked = StackKeyRegistry.getType(EmcStackKey.ID);
            return looked == EmcStackKey.INSTANCE;
        } catch (Throwable t) {
            return false;
        }
    }

    public static List<String> run(RegistryAccess registryAccess) {
        List<String> out = new ArrayList<>();
        int ok = 0;

        // ---- 1. 类型注册 ----
        try {
            IStackKey<?> looked = StackKeyRegistry.getType(EmcStackKey.ID);
            if (looked == EmcStackKey.INSTANCE) {
                out.add("OK   注册：StackKeyRegistry 按 id 取回的是同一单例（" + EmcStackKey.ID + "）");
                ok++;
            } else {
                out.add("FAIL 注册：取回的实例不是单例，实际=" + looked);
            }
        } catch (Throwable t) {
            out.add("FAIL 注册：取不回类型 -> " + t);
        }

        // ---- 2. IStackKey.CODEC 的 NBT 往返（读档走的就是这条路）----
        try {
            var ops = NbtOps.INSTANCE;
            var encoded = IStackKey.CODEC.encodeStart(ops, EmcStackKey.INSTANCE).getOrThrow();
            IStackKey<?> decoded = IStackKey.CODEC.parse(ops, encoded).getOrThrow();
            if (decoded == EmcStackKey.INSTANCE) {
                out.add("OK   NBT 编解码：往返后仍是同一单例，编码结果=" + encoded);
                ok++;
            } else {
                out.add("FAIL NBT 编解码：往返后得到 " + decoded);
            }
        } catch (Throwable t) {
            out.add("FAIL NBT 编解码：" + t + "（若为'注册表中不存在此类型的Key'，说明注册没生效）");
        }

        // ---- 3. STREAM_CODEC 的网络往返（多人同步走的就是这条路）----
        try {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess);
            IStackKey.STREAM_CODEC.encode(buf, EmcStackKey.INSTANCE);
            int written = buf.readableBytes();
            IStackKey<?> decoded = IStackKey.STREAM_CODEC.decode(buf);
            if (decoded == EmcStackKey.INSTANCE) {
                out.add("OK   网络编解码：往返后仍是同一单例，写入了 " + written + " 字节（typeId + 空负载）");
                ok++;
            } else {
                out.add("FAIL 网络编解码：往返后得到 " + decoded);
            }
        } catch (Throwable t) {
            out.add("FAIL 网络编解码：" + t);
        }

        // ---- 4. 临时网络上的增删查 ----
        try {
            DimensionsNet net = new DimensionsNet(true);

            long before = NetEmcAccessor.getEmc(net);
            long added = NetEmcAccessor.addEmc(net, 12345L);
            long afterAdd = NetEmcAccessor.getEmc(net);
            long spent = NetEmcAccessor.spendEmc(net, 2345L);
            long afterSpend = NetEmcAccessor.getEmc(net);

            // BD 的 insert 返回剩余量、extract 返回实际提取量，这里顺带把这个语义钉死
            boolean good = before == 0L && added == 12345L && afterAdd == 12345L
                    && spent == 2345L && afterSpend == 10000L;
            if (good) {
                out.add("OK   存储读写：0 -> +12345 -> " + afterAdd + " -> -2345 -> " + afterSpend
                        + "（同时确认了 insert 返回剩余量、extract 返回实际提取量）");
                ok++;
            } else {
                out.add("FAIL 存储读写：before=" + before + " added=" + added + " afterAdd=" + afterAdd
                        + " spent=" + spent + " afterSpend=" + afterSpend);
            }
        } catch (Throwable t) {
            out.add("FAIL 存储读写：" + t);
        }

        // ---- 5. 网络存储的 NBT 往返（最关键：自定义 key 能否活着穿过存档）----
        try {
            DimensionsNet src = new DimensionsNet(true);
            NetEmcAccessor.addEmc(src, 987654321L);
            CompoundTag tag = src.getUnifiedStorage().serializeNBT(registryAccess);

            DimensionsNet dst = new DimensionsNet(true);
            dst.getUnifiedStorage().deserializeNBT(registryAccess, tag);
            long restored = NetEmcAccessor.getEmc(dst);

            if (restored == 987654321L) {
                out.add("OK   存储 NBT 往返：987654321 完整还原（存档里的 EMC 不会丢）");
                ok++;
            } else {
                out.add("FAIL 存储 NBT 往返：期望 987654321，实际 " + restored
                        + "（若为 0，说明条目在反序列化时被静默丢弃）");
            }
        } catch (Throwable t) {
            out.add("FAIL 存储 NBT 往返：" + t);
        }

        // ---- 6. 溢出保护 ----
        try {
            long a = NetEmcAccessor.saturatingMultiply(Long.MAX_VALUE, 2L);
            long b = NetEmcAccessor.saturatingMultiply(100L, 3L);
            long c = NetEmcAccessor.saturatingAdd(Long.MAX_VALUE, 5L);
            boolean good = a == Long.MAX_VALUE && b == 300L && c == Long.MAX_VALUE;
            if (good) {
                out.add("OK   溢出保护：MAX*2 饱和为 MAX，100*3=300，MAX+5 饱和为 MAX");
                ok++;
            } else {
                out.add("FAIL 溢出保护：MAX*2=" + a + "，100*3=" + b + "，MAX+5=" + c);
            }
        } catch (Throwable t) {
            out.add("FAIL 溢出保护：" + t);
        }

        // ---- 7. 余额不足时不会扣成负数 ----
        try {
            DimensionsNet net = new DimensionsNet(true);
            NetEmcAccessor.addEmc(net, 100L);
            long spent = NetEmcAccessor.spendEmc(net, 999L);
            long left = NetEmcAccessor.getEmc(net);
            if (spent == 100L && left == 0L) {
                out.add("OK   余额不足：请求扣 999、实际只扣 " + spent + "，余额归零且不为负");
                ok++;
            } else {
                out.add("FAIL 余额不足：spent=" + spent + " left=" + left);
            }
        } catch (Throwable t) {
            out.add("FAIL 余额不足：" + t);
        }

        // ---- 8. 渲染器的端侧约束（特征化测试 / 哨兵）----
        //
        // 已由源码确认的结论：**BD 从不在服务端调用 getRender()**。全部调用点都在客户端：
        //   - client/gui/BDBaseGUI.java:56,86,87
        //   - common/menu/widget/ClientNetStorage.java:200（该类仅在 isClientSide 时创建）
        //   - common/menu/widget/ClientNetStorageSearchHelper.java:303
        //   - util/TooltipHelper.java:110 —— 唯一入口是 DimensionsNetMenu.afterLoadChange()，
        //     而 SlotGroupSync 契约明确标注 loadChange/afterLoadChange 为"仅客户端"
        //     （DisorderedSlotGroupSync.java:314,346），唯一调用方是 s2c 包处理器
        //     DisorderedSlotGroupSyncPacket.java:61-62。
        //
        // 我们的渲染器实现引用了 Minecraft / ClientLevel，因此**绝不能**在专用服务器上被加载。
        //
        // ⚠️ 这个约束**只对专用服务器成立**：在客户端（含单机集成的服务端）加载渲染器完全正常。
        // 实测教训：早先的版本不分端侧就断言"必须抛异常"，导致客户端自检报出一个假警报
        // （显示成 7/8 项通过），非常容易被误读成"渲染器坏了"。
        boolean dedicatedServer = net.neoforged.fml.loading.FMLEnvironment.dist
                == net.neoforged.api.distmarker.Dist.DEDICATED_SERVER;
        if (dedicatedServer) {
            try {
                EmcStackKey.INSTANCE.getRender();
                out.add("WARN 渲染器端侧约束：专用服务器上加载 getRender() 竟然成功了 —— 端侧行为可能已变化，"
                        + "请复核 BD 的 getRender() 调用点是否仍全在客户端");
            } catch (Throwable t) {
                out.add("OK   渲染器端侧约束：专用服务器上调用 getRender() 如期抛出 "
                        + t.getClass().getSimpleName() + "（与 BD 的调用点全在客户端这一事实一致）");
                ok++;
            }
        } else {
            try {
                var render = EmcStackKey.INSTANCE.getRender();
                if (render != null) {
                    out.add("OK   渲染器：当前是客户端，getRender() 正常返回（该端侧约束只对专用服务器成立）");
                    ok++;
                } else {
                    out.add("FAIL 渲染器：客户端上 getRender() 返回了 null");
                }
            } catch (Throwable t) {
                out.add("FAIL 渲染器：客户端上 getRender() 抛异常 -> " + t);
            }
        }

        out.add("---- 阶段 2 自检结果：" + ok + "/8 项通过 ----");
        return out;
    }
}
