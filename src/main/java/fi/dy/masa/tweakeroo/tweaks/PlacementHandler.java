package fi.dy.masa.tweakeroo.tweaks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import fi.dy.masa.malilib.util.game.BlockUtils;
import fi.dy.masa.tweakeroo.Tweakeroo;
import fi.dy.masa.tweakeroo.config.Configs;
import fi.dy.masa.tweakeroo.data.DataManager;
import fi.dy.masa.tweakeroo.util.EasyPlacementProtocol;

public class PlacementHandler
{
    public static final ImmutableSet<Property<?>> WHITELISTED_PROPERTIES = ImmutableSet.of(
            // BooleanProperty:
            // INVERTED - DaylightDetector
            // OPEN - Barrel, Door, FenceGate, Trapdoor
            // PERSISTENT - Leaves (Disabled)
            BlockStateProperties.INVERTED,
            BlockStateProperties.OPEN,
            //Properties.PERSISTENT,
            //Properties.POWERED,
            //Properties.LOCKED,
            //Properties.WATERLOGGED
            // EnumProperty:
            // ATTACHMENT - Bells
            // AXIS - Pillar
            // BLOCK_HALF - Stairs, Trapdoor
            // BLOCK_FACE - Button, Grindstone, Lever
            // CHEST_TYPE - Chest
            // COMPARATOR_MODE - Comparator
            // DOOR_HINGE - Door
            // ORIENTATION - Crafter
            // RAIL_SHAPE / STRAIGHT_RAIL_SHAPE - Rails
            // SLAB_TYPE - Slab - PARTIAL ONLY: TOP and BOTTOM, not DOUBLE
            // STAIR_SHAPE - Stairs (needed to get the correct state, otherwise the player facing would be a factor)
            // BLOCK_FACE - Button, Grindstone, Lever
            BlockStateProperties.BELL_ATTACHMENT,
            BlockStateProperties.AXIS,
            BlockStateProperties.HALF,
            BlockStateProperties.ATTACH_FACE,
            BlockStateProperties.CHEST_TYPE,
            BlockStateProperties.MODE_COMPARATOR,
            BlockStateProperties.DOOR_HINGE,
            BlockStateProperties.FACING,
            BlockStateProperties.FACING_HOPPER,
            BlockStateProperties.HORIZONTAL_FACING,
            BlockStateProperties.ORIENTATION,
            BlockStateProperties.RAIL_SHAPE,
            BlockStateProperties.RAIL_SHAPE_STRAIGHT,
            BlockStateProperties.SLAB_TYPE,
            BlockStateProperties.STAIRS_SHAPE,
            BlockStateProperties.COPPER_GOLEM_POSE,
            // IntProperty:
            // BITES - Cake
            // DELAY - Repeater
            // NOTE - NoteBlock
            // ROTATION - Banner, Sign, Skull
            BlockStateProperties.BITES,
            BlockStateProperties.DELAY,
            BlockStateProperties.NOTE,
            BlockStateProperties.ROTATION_16
    );

    /**
     * BlackList for Block States.  Entries here will be reset to their default value.
     */
    public static final ImmutableMap<Property<?>, ? extends Comparable<?>> BLACKLISTED_PROPERTIES = ImmutableMap.of(
            BlockStateProperties.WATERLOGGED,       Boolean.FALSE,
            BlockStateProperties.POWERED,           Boolean.FALSE
    );

    @Nullable
    public static BlockState applyPlacementProtocolToPlacementState(BlockState state, UseContext context)
    {
        return state;
    }

    public static class UseContext
    {
        private final Level world;
        private final BlockPos pos;
        private final Direction side;
        private final Vec3 hitVec;
        private final LivingEntity entity;
        private final InteractionHand hand;
        @Nullable private final BlockPlaceContext itemPlacementContext;

        private UseContext(Level world, BlockPos pos, Direction side, Vec3 hitVec,
                           LivingEntity entity, InteractionHand hand, @Nullable BlockPlaceContext itemPlacementContext)
        {
            this.world = world;
            this.pos = pos;
            this.side = side;
            this.hitVec = hitVec;
            this.entity = entity;
            this.hand = hand;
            this.itemPlacementContext = itemPlacementContext;
        }

        /*
        public static UseContext of(World world, BlockPos pos, Direction side, Vec3d hitVec, LivingEntity entity, Hand hand)
        {
            return new UseContext(world, pos, side, hitVec, entity, hand, null);
        }
        */

        public static UseContext from(BlockPlaceContext ctx, InteractionHand hand)
        {
            Vec3 pos = ctx.getClickLocation();
            return new UseContext(ctx.getLevel(), ctx.getClickedPos(), ctx.getClickedFace(), new Vec3(pos.x, pos.y, pos.z),
                                  ctx.getPlayer(), hand, ctx);
        }

        public Level getWorld()
        {
            return this.world;
        }

        public BlockPos getPos()
        {
            return this.pos;
        }

        public Direction getSide()
        {
            return this.side;
        }

        public Vec3 getHitVec()
        {
            return this.hitVec;
        }

        public LivingEntity getEntity()
        {
            return this.entity;
        }

        public InteractionHand getHand()
        {
            return this.hand;
        }

        @Nullable
        public BlockPlaceContext getItemPlacementContext()
        {
            return this.itemPlacementContext;
        }
    }
}
