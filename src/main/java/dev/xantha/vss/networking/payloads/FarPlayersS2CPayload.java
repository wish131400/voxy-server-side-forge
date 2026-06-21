package dev.xantha.vss.networking.payloads;

import dev.xantha.vss.common.VSSConstants;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;

public record FarPlayersS2CPayload(Entry[] entries) {
    private static final int MAX_NAME_LENGTH = 16;
    private static final int MAX_USE_ITEM_REMAINING_TICKS = 72000;

    public static void encode(FarPlayersS2CPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.entries.length);
        for (Entry entry : payload.entries) {
            buf.writeUUID(entry.uuid());
            buf.writeUtf(entry.name(), MAX_NAME_LENGTH);
            buf.writeDouble(entry.x());
            buf.writeDouble(entry.y());
            buf.writeDouble(entry.z());
            buf.writeFloat(entry.yaw());
            buf.writeFloat(entry.pitch());
            buf.writeFloat(entry.headYaw());
            buf.writeBoolean(entry.crouching());
            buf.writeBoolean(entry.sprinting());
            buf.writeEnum(orDefault(entry.pose(), Pose.STANDING));
            buf.writeEnum(orDefault(entry.mainArm(), HumanoidArm.RIGHT));
            buf.writeBoolean(entry.usingItem());
            buf.writeEnum(orDefault(entry.usedItemHand(), InteractionHand.MAIN_HAND));
            buf.writeVarInt(clampUseItemTicks(entry.useItemRemainingTicks()));
            buf.writeBoolean(entry.swinging());
            buf.writeEnum(orDefault(entry.swingingArm(), InteractionHand.MAIN_HAND));
            buf.writeVarInt(Math.max(0, entry.swingTime()));
            buf.writeFloat(entry.oAttackAnim());
            buf.writeFloat(entry.attackAnim());
            buf.writeBoolean(entry.fallFlying());
            buf.writeBoolean(entry.swimming());
            buf.writeBoolean(entry.autoSpinAttack());
            buf.writeBoolean(entry.invisible());
            buf.writeBoolean(entry.glowing());
            buf.writeBoolean(entry.onGround());
            buf.writeBoolean(entry.onFire());
            writeEquipment(buf, entry);
        }
    }

    public static FarPlayersS2CPayload decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > VSSConstants.MAX_FAR_PLAYER_ENTRIES) {
            throw new IllegalArgumentException("Far player entry count out of range: " + count);
        }

        Entry[] entries = new Entry[count];
        for (int i = 0; i < count; i++) {
            entries[i] = new Entry(
                    buf.readUUID(),
                    buf.readUtf(MAX_NAME_LENGTH),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readEnum(Pose.class),
                    buf.readEnum(HumanoidArm.class),
                    buf.readBoolean(),
                    buf.readEnum(InteractionHand.class),
                    clampUseItemTicks(buf.readVarInt()),
                    buf.readBoolean(),
                    buf.readEnum(InteractionHand.class),
                    Math.max(0, buf.readVarInt()),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readItem(),
                    buf.readItem(),
                    buf.readItem(),
                    buf.readItem(),
                    buf.readItem(),
                    buf.readItem());
        }
        return new FarPlayersS2CPayload(entries);
    }

    private static void writeEquipment(FriendlyByteBuf buf, Entry entry) {
        buf.writeItem(orEmpty(entry.mainHand()));
        buf.writeItem(orEmpty(entry.offHand()));
        buf.writeItem(orEmpty(entry.head()));
        buf.writeItem(orEmpty(entry.chest()));
        buf.writeItem(orEmpty(entry.legs()));
        buf.writeItem(orEmpty(entry.feet()));
    }

    private static int clampUseItemTicks(int ticks) {
        return Math.max(0, Math.min(MAX_USE_ITEM_REMAINING_TICKS, ticks));
    }

    private static <E extends Enum<E>> E orDefault(E value, E fallback) {
        return value != null ? value : fallback;
    }

    private static ItemStack orEmpty(ItemStack stack) {
        return stack != null ? stack : ItemStack.EMPTY;
    }

    public record Entry(
            UUID uuid,
            String name,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            float headYaw,
            boolean crouching,
            boolean sprinting,
            Pose pose,
            HumanoidArm mainArm,
            boolean usingItem,
            InteractionHand usedItemHand,
            int useItemRemainingTicks,
            boolean swinging,
            InteractionHand swingingArm,
            int swingTime,
            float oAttackAnim,
            float attackAnim,
            boolean fallFlying,
            boolean swimming,
            boolean autoSpinAttack,
            boolean invisible,
            boolean glowing,
            boolean onGround,
            boolean onFire,
            ItemStack mainHand,
            ItemStack offHand,
            ItemStack head,
            ItemStack chest,
            ItemStack legs,
            ItemStack feet) {
        public ItemStack itemBySlot(EquipmentSlot slot) {
            return switch (slot) {
                case MAINHAND -> mainHand;
                case OFFHAND -> offHand;
                case HEAD -> head;
                case CHEST -> chest;
                case LEGS -> legs;
                case FEET -> feet;
            };
        }
    }
}
