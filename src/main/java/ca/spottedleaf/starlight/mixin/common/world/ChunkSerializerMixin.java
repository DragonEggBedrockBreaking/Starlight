package ca.spottedleaf.starlight.mixin.common.world;

import ca.spottedleaf.starlight.common.light.StarLightEngine;
import ca.spottedleaf.starlight.common.chunk.ExtendedChunk;
import ca.spottedleaf.starlight.common.light.SWMRNibbleArray;
import ca.spottedleaf.starlight.common.util.WorldUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkSerializer.class)
public abstract class ChunkSerializerMixin {

    private static final int STARLIGHT_LIGHT_VERSION = 4;

    private static final String UNINITIALISED_SKYLIGHT_TAG = "starlight.skylight_uninit";
    private static final String STARLIGHT_VERSION_TAG = "starlight.light_version";

    /**
     * Overwrites vanilla's light data with our own.
     * TODO this needs to be checked on update to account for format changes
     */
    @Inject(
            method = "write",
            at = @At("RETURN")
    )
    private static void saveLightHook(final ServerLevel world, final ChunkAccess chunk, final CallbackInfoReturnable<CompoundTag> cir) {
        final int minSection = WorldUtil.getMinLightSection(world);
        final int maxSection = WorldUtil.getMaxLightSection(world);
        CompoundTag ret = cir.getReturnValue();
        if (ret == null) {
            return;
        }

        SWMRNibbleArray[] blockNibbles = ((ExtendedChunk)chunk).getBlockNibbles();
        SWMRNibbleArray[] skyNibbles = ((ExtendedChunk)chunk).getSkyNibbles();

        CompoundTag level = ret.getCompound("Level");
        boolean lit = chunk.isLightCorrect();
        // diff start - store our tag for whether light data is init'd
        if (lit) {
            level.putBoolean("isLightOn", false);
            level.putInt(STARLIGHT_VERSION_TAG, STARLIGHT_LIGHT_VERSION);
        }
        // diff end - store our tag for whether light data is init'd
        ChunkStatus status = ChunkStatus.byName(level.getString("Status"));

        CompoundTag[] sections = new CompoundTag[maxSection - minSection + 1];

        ListTag sectionsStored = level.getList("Sections", 10);

        for (int i = 0; i < sectionsStored.size(); ++i) {
            CompoundTag sectionStored = sectionsStored.getCompound(i);
            int k = sectionStored.getByte("Y");

            // strip light data
            sectionStored.remove("BlockLight");
            sectionStored.remove("SkyLight");

            if (!sectionStored.isEmpty()) {
                sections[k - minSection] = sectionStored;
            }
        }

        if (lit && status.isOrAfter(ChunkStatus.LIGHT)) {
            for (int i = minSection; i <= maxSection; ++i) {
                DataLayer blockNibble = blockNibbles[i - minSection].isAllZero() ? new DataLayer() : blockNibbles[i - minSection].toVanillaNibble();
                DataLayer skyNibble = skyNibbles[i - minSection].isAllZero() ? new DataLayer() : skyNibbles[i - minSection].toVanillaNibble();
                if (blockNibble != null || skyNibble != null) {
                    CompoundTag section = sections[i - minSection];
                    if (section == null) {
                        section = new CompoundTag();
                        section.putByte("Y", (byte)i);
                        sections[i - minSection] = section;
                    }

                    if (blockNibble != null && !blockNibble.isEmpty()) {
                        section.putByteArray("BlockLight", blockNibble.getData());
                    }

                    if (skyNibble != null) {
                        if (skyNibble.isEmpty()) {
                            section.putBoolean(UNINITIALISED_SKYLIGHT_TAG, true);
                        } else {
                            // we store under the same key so mod programs editing nbt
                            // can still read the data, hopefully.
                            // however, for compatibility we store chunks as unlit so vanilla
                            // is forced to re-light them if it encounters our data. It's too much of a burden
                            // to try and maintain compatibility with a broken and inferior skylight management system.
                            section.putByteArray("SkyLight", skyNibble.getData());
                        }
                    }
                }
            }
        }

        // rewrite section list
        sectionsStored.clear();
        for (CompoundTag section : sections) {
            if (section != null) {
                sectionsStored.add(section);
            }
        }
        level.put("Sections", sectionsStored);
    }

    /**
     * Loads our light data into the returned chunk object from the tag.
     * TODO this needs to be checked on update to account for format changes
     */
    @Inject(
            method = "read",
            at = @At("RETURN")
    )
    private static void loadLightHook(final ServerLevel world, final StructureManager structureManager, final PoiManager poiManager,
                                      final ChunkPos pos, final CompoundTag tag, final CallbackInfoReturnable<ProtoChunk> cir) {
        final int minSection = WorldUtil.getMinLightSection(world);
        final int maxSection = WorldUtil.getMaxLightSection(world);
        ProtoChunk ret = cir.getReturnValue();
        if (ret == null) {
            return;
        }

        SWMRNibbleArray[] blockNibbles = StarLightEngine.getFilledEmptyLight(world);
        SWMRNibbleArray[] skyNibbles = StarLightEngine.getFilledEmptyLight(world);


        // start copy from from the original method
        CompoundTag levelTag = tag.getCompound("Level");
        boolean lit = levelTag.getInt(STARLIGHT_VERSION_TAG) == STARLIGHT_LIGHT_VERSION; ret.setLightCorrect(lit); // diff - override lit with our value
        boolean canReadSky = world.dimensionType().hasSkyLight();
        ChunkStatus status = ChunkStatus.byName(tag.getCompound("Level").getString("Status"));
        if (lit && status.isOrAfter(ChunkStatus.LIGHT)) { // diff - we add the status check here
            ListTag sections = levelTag.getList("Sections", 10);

            for (int i = 0; i < sections.size(); ++i) {
                CompoundTag sectionData = sections.getCompound(i);
                int y = sectionData.getByte("Y");

                if (sectionData.contains("BlockLight", 7)) {
                    // this is where our diff is
                    blockNibbles[y - minSection] = new SWMRNibbleArray(sectionData.getByteArray("BlockLight").clone()); // clone for data safety
                }

                if (canReadSky) {
                    if (sectionData.contains("SkyLight", 7)) {
                        // we store under the same key so mod programs editing nbt
                        // can still read the data, hopefully.
                        // however, for compatibility we store chunks as unlit so vanilla
                        // is forced to re-light them if it encounters our data. It's too much of a burden
                        // to try and maintain compatibility with a broken and inferior skylight management system.
                        skyNibbles[y - minSection] = new SWMRNibbleArray(sectionData.getByteArray("SkyLight").clone()); // clone for data safety
                    } else if (sectionData.getBoolean(UNINITIALISED_SKYLIGHT_TAG)) {
                        skyNibbles[y - minSection] = new SWMRNibbleArray();
                    }
                }
            }
        }
        // end copy from vanilla

        ((ExtendedChunk)ret).setBlockNibbles(blockNibbles);
        ((ExtendedChunk)ret).setSkyNibbles(skyNibbles);
    }
}
