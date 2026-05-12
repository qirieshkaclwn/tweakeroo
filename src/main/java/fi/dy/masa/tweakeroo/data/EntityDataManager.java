package fi.dy.masa.tweakeroo.data;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.nautilus.AbstractNautilus;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import com.mojang.datafixers.util.Either;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.interfaces.IClientTickHandler;
import fi.dy.masa.malilib.interfaces.IDataSyncer;
import fi.dy.masa.malilib.mixin.entity.IMixinAbstractHorseEntity;
import fi.dy.masa.malilib.mixin.entity.IMixinAbstractNautilus;
import fi.dy.masa.malilib.mixin.entity.IMixinPiglinEntity;
import fi.dy.masa.malilib.mixin.network.IMixinDataQueryHandler;
import fi.dy.masa.malilib.network.ClientPlayHandler;
import fi.dy.masa.malilib.network.IPluginClientPlayHandler;
import fi.dy.masa.malilib.util.InventoryUtils;
import fi.dy.masa.malilib.util.MathUtils;
import fi.dy.masa.malilib.util.WorldUtils;
import fi.dy.masa.malilib.util.data.Constants;
import fi.dy.masa.malilib.util.data.DataEntityUtils;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt;
import fi.dy.masa.malilib.util.nbt.NbtKeys;
import fi.dy.masa.malilib.util.nbt.NbtView;
import fi.dy.masa.tweakeroo.Reference;
import fi.dy.masa.tweakeroo.Tweakeroo;
import fi.dy.masa.tweakeroo.config.Configs;
import fi.dy.masa.tweakeroo.config.FeatureToggle;

@SuppressWarnings({"deprecation"})
public class EntityDataManager implements IClientTickHandler, IDataSyncer
{
    private static final EntityDataManager INSTANCE = new EntityDataManager();
    public static EntityDataManager getInstance()
    {
        return INSTANCE;
    }

    private final Minecraft mc;
    private boolean checkOpStatus = true;
    private boolean hasOpStatus = false;
    private long lastOpCheck = 0L;

    private final ConcurrentHashMap<BlockPos, Pair<Long, Pair<BlockEntity, CompoundData>>> blockEntityCache = new ConcurrentHashMap<>(16, 0.9f, 1);
    private final ConcurrentHashMap<Integer,  Pair<Long, Pair<Entity,      CompoundData>>> entityCache      = new ConcurrentHashMap<>(16, 0.9f, 1);
    private long serverTickTime = 0;
    private ClientLevel clientWorld;

    @Override
    @Nullable
    public Level getWorld()
    {
        return WorldUtils.getBestWorld(this.mc);
    }

    @Override
    public ClientLevel getClientWorld()
    {
        if (this.clientWorld == null)
        {
            this.clientWorld = this.mc.level;
        }

        return this.clientWorld;
    }

    public EntityDataManager()
    {
        this.mc = Minecraft.getInstance();
    }

    @Override
    public void onClientTick(Minecraft mc)
    {
        long now = System.currentTimeMillis();

        if (now - this.serverTickTime > 50)
        {
            // Expire cached NBT
            this.tickCache(now);
            this.serverTickTime = now;
        }
    }

    private ClientPacketListener getVanillaHandler()
    {
        if (mc.player != null)
        {
            return mc.player.connection;
        }

        return null;
    }

    @Override
    public void reset(boolean isLogout)
    {
        if (isLogout)
        {
            Tweakeroo.debugLog("ServerDataSyncer#reset() - log-out");
            this.checkOpStatus = false;
            this.hasOpStatus = false;
            this.lastOpCheck = 0L;
        }
        else
        {
            Tweakeroo.debugLog("ServerDataSyncer#reset() - dimension change or log-in");
            long now = System.currentTimeMillis();
            this.serverTickTime = now - 15000L;
            this.tickCache(now);
            this.serverTickTime = now;
            this.clientWorld = mc.level;
            this.checkOpStatus = true;
            this.lastOpCheck = now;
        }

        // Clear data
        this.blockEntityCache.clear();
        this.entityCache.clear();
    }

    private boolean shouldUseQuery()
    {
        if (this.hasOpStatus) { return true; }
        if (this.checkOpStatus)
        {
            // Check for 15 minutes after login, or changing dimensions
            if ((System.currentTimeMillis() - this.lastOpCheck) < 900000L) { return true; }
            this.checkOpStatus = false;
        }

        return false;
    }

    public void resetOpCheck()
    {
        this.hasOpStatus = false;
        this.checkOpStatus = true;
        this.lastOpCheck = System.currentTimeMillis();
    }

    private void tickCache(long nowTime)
    {
        long timeout = 10000L;

        synchronized (this.blockEntityCache)
        {
            for (BlockPos pos : this.blockEntityCache.keySet())
            {
                Pair<Long, Pair<BlockEntity, CompoundData>> pair = this.blockEntityCache.get(pos);

                if (pair != null)       // ???
                {
                    if ((nowTime - pair.getLeft()) > timeout || pair.getLeft() > nowTime)
                    {
                        //Tweakeroo.printDebug("entityCache: be at pos [{}] has timed out", pos.toShortString());
                        this.blockEntityCache.remove(pos);
                    }
                }
            }
        }

        synchronized (this.entityCache)
        {
            for (Integer entityId : this.entityCache.keySet())
            {
                Pair<Long, Pair<Entity,      CompoundData>> pair = this.entityCache.get(entityId);

                if (pair != null)       // ???
                {
                    if ((nowTime - pair.getLeft()) > timeout || pair.getLeft() > nowTime)
                    {
                        //Tweakeroo.printDebug("entityCache: entity Id [{}] has timed out", entityId);
                        this.entityCache.remove(entityId);
                    }
                }
            }
        }
    }

    @Override
    public synchronized @Nullable CompoundData getFromBlockEntityCacheData(BlockPos pos)
    {
        if (this.blockEntityCache.containsKey(pos))
        {
            return this.blockEntityCache.get(pos).getRight().getRight();
        }

        return null;
    }

    @Override
    public synchronized @Nullable BlockEntity getFromBlockEntityCache(BlockPos pos)
    {
        if (this.blockEntityCache.containsKey(pos))
        {
            return this.blockEntityCache.get(pos).getRight().getLeft();
        }

        return null;
    }

    @Override
    public synchronized @Nullable CompoundData getFromEntityCacheData(int entityId)
    {
        if (this.entityCache.containsKey(entityId))
        {
            return this.entityCache.get(entityId).getRight().getRight();
        }

        return null;
    }

    @Override
    public @Nullable CompoundTag getFromBlockEntityCacheNbt(BlockPos pos)
    {
        CompoundData data = this.getFromBlockEntityCacheData(pos);

        if (data != null)
        {
            return DataConverterNbt.toVanillaCompound(data);
        }

        return null;
    }

    @Override
    public @Nullable CompoundTag getFromEntityCacheNbt(int entityId)
    {
        CompoundData data = this.getFromEntityCacheData(entityId);

        if (data != null)
        {
            return DataConverterNbt.toVanillaCompound(data);
        }

        return null;
    }

    @Override
    public synchronized @Nullable Entity getFromEntityCache(int entityId)
    {
        if (this.entityCache.containsKey(entityId))
        {
            return this.entityCache.get(entityId).getRight().getLeft();
        }

        return null;
    }

    public boolean hasBackupStatus()
    {
        return false;
    }

    public boolean hasOperatorStatus()
    {
        return this.hasOpStatus;
    }

    public int getPendingBlockEntitiesCount()
    {
        return 0;
    }

    public int getPendingEntitiesCount()
    {
        return 0;
    }

    public int getBlockEntityCacheCount()
    {
        return this.blockEntityCache.size();
    }

    public int getEntityCacheCount()
    {
        return this.entityCache.size();
    }

    public boolean getIfReceivedBackupPackets()
    {
        return false;
    }

    @Override
    public void onGameInit()
    {
    }

    @Override
    public void onWorldPre()
    {
    }

    @Override
    public void onWorldJoin()
    {
        // NO-OP
    }

    public void onEntityDataSyncToggled(ConfigBoolean config)
    {
        this.reset(true);
    }

    @Override
    public @Nullable Pair<BlockEntity, CompoundTag> requestBlockEntityNbt(Level world, BlockPos pos)
    {
        Pair<BlockEntity, CompoundData> pair = this.requestBlockEntity(world, pos);

        if (pair != null)
        {
            return Pair.of(pair.getLeft(), DataConverterNbt.toVanillaCompound(pair.getRight()));
        }

        return null;
    }

    @Override
    public @Nullable Pair<BlockEntity, CompoundData> requestBlockEntity(Level world, BlockPos pos)
    {
        if (this.blockEntityCache.containsKey(pos))
        {
            if (world instanceof ServerLevel)
            {
                this.requestBlockEntityFromLocalServer(this.mc, world, pos);
            }

            return this.blockEntityCache.get(pos).getRight();
        }
        else if (world.getBlockState(pos).getBlock() instanceof EntityBlock)
        {
            return this.refreshBlockEntityFromWorld(this.getClientWorld(), pos);
        }

        return null;
    }

    private @Nullable Pair<BlockEntity, CompoundData> refreshBlockEntityFromWorld(Level world, BlockPos pos)
    {
        if (world != null && world.getBlockState(pos).hasBlockEntity())
        {
            BlockEntity be = world.getChunkAt(pos).getBlockEntity(pos);

            if (be != null)
            {
	            CompoundData data = DataConverterNbt.fromVanillaCompound(be.saveWithFullMetadata(world.registryAccess()));
                Pair<BlockEntity, CompoundData> pair = Pair.of(be, data);

                synchronized (this.blockEntityCache)
                {
                    this.blockEntityCache.put(pos, Pair.of(System.currentTimeMillis(), pair));
                }

                return pair;
            }
        }

        return null;
    }

    @Override
    public @Nullable Pair<Entity, CompoundTag> requestEntityNbt(Level world, int entityId)
    {
        Pair<Entity, CompoundData> pair = this.requestEntity(world, entityId);

        if (pair != null)
        {
            return Pair.of(pair.getLeft(), DataConverterNbt.toVanillaCompound(pair.getRight()));
        }

        return null;
    }

    @Override
    public @Nullable Pair<Entity, CompoundData> requestEntity(Level world, int entityId)
    {
        if (this.entityCache.containsKey(entityId))
        {
            // Refresh from Server World
            if (world instanceof ServerLevel)
            {
                this.requestEntityFromLocalServer(this.mc, world, entityId);
            }

            return this.entityCache.get(entityId).getRight();
        }

        return this.refreshEntityFromWorld(this.getClientWorld(), entityId);
    }

    private @Nullable Pair<Entity, CompoundData> refreshEntityFromWorld(Level world, int entityId)
    {
        if (world != null)
        {
            Entity entity = world.getEntity(entityId);

            if (entity != null)
            {
	            CompoundData data = DataEntityUtils.invokeEntityDataTagNoPassengers(entity, entityId);

                if (!data.isEmpty())
                {
                    Pair<Entity, CompoundData> pair = Pair.of(entity, data);

                    synchronized (this.entityCache)
                    {
                        this.entityCache.put(entityId, Pair.of(System.currentTimeMillis(), pair));
                    }

                    return pair;
                }
            }
        }

        return null;
    }

    @Override
    @Nullable
    public Container getBlockInventory(Level world, BlockPos pos, boolean useNbt)
    {
        if (this.blockEntityCache.containsKey(pos))
        {
            Container inv = null;

            if (useNbt)
            {
                inv = InventoryUtils.getDataInventory(this.blockEntityCache.get(pos).getRight().getRight(), -1, world.registryAccess());
            }
            else
            {
                BlockEntity be = this.blockEntityCache.get(pos).getRight().getLeft();
                BlockState state = world.getBlockState(pos);

                if (state.is(BlockTags.AIR) || !state.hasBlockEntity())
                {
                    synchronized (this.blockEntityCache)
                    {
                        this.blockEntityCache.remove(pos);
                    }

                    // Don't keep requesting if we're tick warping or something.
                    return null;
                }

                if (be instanceof Container inv1)
                {
                    if (be instanceof ChestBlockEntity && state.hasProperty(ChestBlock.TYPE))
                    {
                        ChestType type = state.getValue(ChestBlock.TYPE);

                        if (type != ChestType.SINGLE)
                        {
                            BlockPos posAdj = pos.relative(ChestBlock.getConnectedDirection(state));
                            if (!world.hasChunkAt(posAdj)) return null;
                            BlockState stateAdj = world.getBlockState(posAdj);

                            var dataAdj = this.getFromBlockEntityCache(posAdj);

                            if (dataAdj == null)
                            {
                                this.requestBlockEntity(world, posAdj);
                            }

                            if (stateAdj.getBlock() == state.getBlock() &&
                                dataAdj instanceof ChestBlockEntity inv2 &&
                                stateAdj.getValue(ChestBlock.TYPE) != ChestType.SINGLE &&
                                stateAdj.getValue(ChestBlock.FACING) == state.getValue(ChestBlock.FACING))
                            {
                                Container invRight = type == ChestType.RIGHT ? inv1 : inv2;
                                Container invLeft = type == ChestType.RIGHT ? inv2 : inv1;

                                inv = new CompoundContainer(invRight, invLeft);
                            }
                        }
                        else
                        {
                            inv = inv1;
                        }
                    }
                    else
                    {
                        inv = inv1;
                    }
                }
            }

            if (inv != null)
            {
                return inv;
            }
        }

        return null;
    }

    @Override
    @Nullable
    public Container getEntityInventory(Level world, int entityId, boolean useNbt)
    {
        if (this.entityCache.containsKey(entityId) && this.getWorld() != null)
        {
            Container inv = null;

            if (useNbt)
            {
                inv = InventoryUtils.getDataInventory(this.entityCache.get(entityId).getRight().getRight(), -1, this.getWorld().registryAccess());
            }
            else
            {
                Entity entity = this.entityCache.get(entityId).getRight().getLeft();

                if (entity instanceof Container)
                {
                    inv = (Container) entity;
                }
                else if (entity instanceof Player player && player != null)
                {
                    inv = new SimpleContainer(player.getInventory().getNonEquipmentItems().toArray(new ItemStack[36]));
                }
                else if (entity instanceof Villager)
                {
                    inv = ((Villager) entity).getInventory();
                }
                else if (entity instanceof AbstractHorse)
                {
                    inv = ((IMixinAbstractHorseEntity) entity).malilib_getHorseInventory();
                }
                else if (entity instanceof AbstractNautilus)
                {
                    inv = ((IMixinAbstractNautilus) entity).malilib_getNautilusInventory();
                }
                else if (entity instanceof Piglin)
                {
                    inv = ((IMixinPiglinEntity) entity).malilib_getInventory();
                }
            }

            if (inv != null)
            {
                return inv;
            }
        }

        return null;
    }

    @Override
    public BlockEntity handleBlockEntityData(BlockPos pos, CompoundTag nbt, @Nullable Identifier type)
    {
        return this.handleBlockEntityData(pos, DataConverterNbt.fromVanillaCompound(nbt), type);
    }

    @Override
    public Entity handleEntityData(int entityId, CompoundTag nbt)
    {
        return this.handleEntityData(entityId, DataConverterNbt.fromVanillaCompound(nbt));
    }

    @Nullable
    @Override
    public BlockEntity handleBlockEntityData(BlockPos pos, CompoundData data, @Nullable Identifier type)
    {
        if (data == null || this.getClientWorld() == null) return null;

        BlockEntity blockEntity = this.getClientWorld().getBlockEntity(pos);

        if (blockEntity != null && (type == null || type.equals(BlockEntityType.getKey(blockEntity.getType()))))
        {
            if (!data.contains(NbtKeys.ID, Constants.NBT.TAG_STRING))
            {
                Identifier id = BlockEntityType.getKey(blockEntity.getType());

                if (id != null)
                {
                    data.putString(NbtKeys.ID, id.toString());
                }
            }
            synchronized (this.blockEntityCache)
            {
                this.blockEntityCache.put(pos, Pair.of(System.currentTimeMillis(), Pair.of(blockEntity, data)));
            }

            if (blockEntity instanceof Container)
            {
                NbtView view = NbtView.getReader(data, this.getClientWorld().registryAccess());
                blockEntity.loadWithComponents(view.getReader());
            }

            return blockEntity;
        }

        if (type == null) { return null; }
        Optional<Holder.Reference<BlockEntityType<?>>> opt = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(type);

        if (opt.isPresent())
        {
            BlockEntityType<?> beType = opt.get().value();

            if (beType.isValid(this.getClientWorld().getBlockState(pos)))
            {
                BlockEntity blockEntity2 = beType.create(pos, this.getClientWorld().getBlockState(pos));

                if (blockEntity2 != null)
                {
                    if (!data.contains(NbtKeys.ID, Constants.NBT.TAG_STRING))
                    {
                        Identifier id = BlockEntityType.getKey(beType);

                        if (id != null)
                        {
                            data.putString(NbtKeys.ID, id.toString());
                        }
                    }
                    synchronized (this.blockEntityCache)
                    {
                        this.blockEntityCache.put(pos, Pair.of(System.currentTimeMillis(), Pair.of(blockEntity2, data)));
                    }

                    return blockEntity2;
                }
            }
        }

        return null;
    }

    @Nullable
    @Override
    public Entity handleEntityData(int entityId, CompoundData data)
    {
        if (data == null || this.getClientWorld() == null) return null;
        Entity entity = this.getClientWorld().getEntity(entityId);

        if (entity != null)
        {
            if (!data.contains(NbtKeys.ID, Constants.NBT.TAG_STRING))
            {
                Identifier id = EntityType.getKey(entity.getType());

                if (id != null)
                {
                    data.putString(NbtKeys.ID, id.toString());
                }
            }

            synchronized (this.entityCache)
            {
                this.entityCache.put(entityId, Pair.of(System.currentTimeMillis(), Pair.of(entity, data)));
            }
        }

        return entity;
    }

    @Override
    public void handleBulkEntityData(int transactionId, CompoundData data)
    {
        this.handleBulkEntityData(transactionId, DataConverterNbt.toVanillaCompound(data));
    }

    @Override
    public void handleVanillaQueryNbt(int transactionId, CompoundData data)
    {
        this.handleVanillaQueryNbt(transactionId, DataConverterNbt.toVanillaCompound(data));
    }

    @Override
    public void handleBulkEntityData(int transactionId, CompoundTag nbt)
    {
        // todo
    }

    @Override
    public void handleVanillaQueryNbt(int transactionId, CompoundTag nbt)
    {
        if (this.checkOpStatus)
        {
            this.hasOpStatus = true;
            this.checkOpStatus = false;
            this.lastOpCheck = System.currentTimeMillis();
        }
    }
}

