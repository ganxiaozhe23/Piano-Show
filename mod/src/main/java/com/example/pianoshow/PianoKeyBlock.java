package com.example.pianoshow;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.ShapeContext;

public final class PianoKeyBlock extends Block {
    public static final IntProperty NOTE = IntProperty.of("note", 0, 127);
    public static final EnumProperty<KeyKind> KEY_KIND = EnumProperty.of("key_kind", KeyKind.class);
    public static final BooleanProperty PRESSED = BooleanProperty.of("pressed");

    private static final VoxelShape WHITE_SHAPE = Block.createCuboidShape(0, 0, 0, 16, 4, 16);
    private static final VoxelShape BLACK_SHAPE = Block.createCuboidShape(1, 0, 1, 15, 10, 15);

    public enum KeyKind implements StringIdentifiable {
        WHITE("white"), BLACK("black");

        private final String name;

        KeyKind(String name) {
            this.name = name;
        }

        @Override
        public String asString() {
            return name;
        }
    }

    public PianoKeyBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState()
                .with(NOTE, 60)
                .with(KEY_KIND, KeyKind.WHITE)
                .with(PRESSED, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, net.minecraft.block.BlockState> builder) {
        builder.add(NOTE, KEY_KIND, PRESSED);
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return state.get(KEY_KIND) == KeyKind.BLACK ? BLACK_SHAPE : WHITE_SHAPE;
    }
}
