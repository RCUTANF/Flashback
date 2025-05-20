package com.moulberry.flashback.keyframe.types;

import com.moulberry.flashback.combo_options.TrackingBodyPart;
import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.change.KeyframeChangeSpectateEntity;
import com.moulberry.flashback.keyframe.change.KeyframeChangeTrackEntity;
import com.moulberry.flashback.keyframe.impl.SpectateEntityKeyframe;
import com.moulberry.flashback.keyframe.impl.TrackEntityKeyframe;
import imgui.ImGui;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.UUID;

public class SpectateEntityKeyframeType implements KeyframeType<SpectateEntityKeyframe> {

    public static SpectateEntityKeyframeType INSTANCE = new SpectateEntityKeyframeType();

    private SpectateEntityKeyframeType() {}

    @Override
    public Class<? extends KeyframeChange> keyframeChangeType() {
        return KeyframeChangeSpectateEntity.class;
    }

    @Override
    public @Nullable String icon() {
        return "\ue55f";
    }

    @Override
    public String name() {
        return "spectate entity";
    }

    @Override
    public String id() {
        return "spectate_entity";
    }

    @Override
    public @Nullable SpectateEntityKeyframe createDirect() {
        return null;
    }

    private static class SpectateEntityData {
        ImString entityTarget = new ImString();
        UUID validEntityTarget = null;
    }

    @Override
    public KeyframeCreatePopup<SpectateEntityKeyframe> createPopup() {
        SpectateEntityData data = new SpectateEntityData();

        return () -> {
            if (ImGui.inputText("Entity UUID", data.entityTarget)) {
                data.validEntityTarget = null;
                try {
                    String uuidStr = ImGuiHelper.getString(data.entityTarget);
                    UUID uuid = UUID.fromString(uuidStr);

                    ClientLevel level = Minecraft.getInstance().level;
                    if (level != null) {
                        Entity entity = level.getEntities().get(uuid);
                        if (entity != null && entity != Minecraft.getInstance().player) {
                            data.validEntityTarget = uuid;
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (data.validEntityTarget == null) ImGui.beginDisabled();
            if (ImGui.button("Add")) {
                return new SpectateEntityKeyframe(data.validEntityTarget);
            }
            if (data.validEntityTarget == null) ImGui.endDisabled();
            ImGui.sameLine();
            if (ImGui.button("Cancel")) {
                ImGui.closeCurrentPopup();
            }
            return null;
        };
    }
}
