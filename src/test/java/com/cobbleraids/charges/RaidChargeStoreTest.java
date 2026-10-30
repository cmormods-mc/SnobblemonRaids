package com.cobbleraids.charges;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
class RaidChargeStoreTest {
    private static final long NOW=1_800_000_000_000L;
    private static final UUID A=UUID.fromString("00000000-0000-0000-0000-000000000001"),B=UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static CompoundTag row(UUID id,int charges,long next) {
        var row=new CompoundTag();row.putUUID("uuid",id);row.putInt("available",charges);row.putLong("next_refill",next);return row;
    }
    @Test void serializationPreservesUuidBalancesAndOriginalRefillSchedule() {
        var rows=new ListTag();rows.add(row(A,0,NOW+3_600_000));rows.add(row(B,2,NOW+1_800_000));
        var tag=new CompoundTag();tag.put("players",rows);
        var loaded=RaidChargeStore.load(tag,null);
        var reloaded=RaidChargeStore.load(loaded.save(new CompoundTag(),null),null);
        assertEquals(0,reloaded.status(A,NOW).available());assertEquals(2,reloaded.status(B,NOW).available());
        assertEquals(NOW+3_600_000,reloaded.status(A,NOW).nextRefillAt());
        assertEquals(1,reloaded.status(A,NOW+3_600_000).available());
        assertEquals(3,reloaded.status(UUID.randomUUID(),NOW).available());
    }
    @Test void corruptRowDoesNotResetOtherPlayersOrGrantFreeEntries() {
        var rows=new ListTag();rows.add(row(A,9,0));rows.add(row(B,1,NOW+3_600_000));
        var tag=new CompoundTag();tag.put("players",rows);
        var loaded=RaidChargeStore.load(tag,null);
        assertEquals(0,loaded.status(A,System.currentTimeMillis()).available());
        assertEquals(1,loaded.status(B,NOW).available());assertTrue(loaded.isDirty());
    }
}
