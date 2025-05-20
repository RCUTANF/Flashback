package com.moulberry.flashback.keyframe.impl;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.change.KeyframeChange;
import com.moulberry.flashback.keyframe.change.KeyframeChangeSpectateEntity;
import com.moulberry.flashback.keyframe.interpolation.InterpolationType;
import com.moulberry.flashback.keyframe.types.SpectateEntityKeyframeType;
import imgui.ImGui;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public class SpectateEntityKeyframe extends Keyframe {

    public UUID target;

    public SpectateEntityKeyframe(UUID target) {
        this(target, InterpolationType.getDefault());
    }

    public SpectateEntityKeyframe(UUID target, InterpolationType interpolationType) {
        this.target = target;
        this.interpolationType(interpolationType);
    }

    @Override
    public KeyframeType<?> keyframeType() {
        return SpectateEntityKeyframeType.INSTANCE;
    }

    @Override
    public Keyframe copy() {
        return new SpectateEntityKeyframe(this.target, this.interpolationType());
    }

    @Override
    public void renderEditKeyframe(Consumer<Consumer<Keyframe>> update) {
        ImString target = new ImString(this.target.toString());
        target.inputData.isResizable = true;
        if (ImGui.inputText("Entity UUID", target)) {
            try {
                String uuidStr = ImGuiHelper.getString(target);
                UUID uuid = UUID.fromString(uuidStr);

                ClientLevel level = Minecraft.getInstance().level;
                if (level != null) {
                    Entity entity = level.getEntities().get(uuid);
                    if (entity != null && entity != Minecraft.getInstance().player) {
                        update.accept(keyframe -> ((SpectateEntityKeyframe)keyframe).target = uuid);
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public KeyframeChange createChange() {
        return new KeyframeChangeSpectateEntity(this.target);
    }

    @Override
    public KeyframeChange createSmoothInterpolatedChange(Keyframe p1, Keyframe p2, Keyframe p3, float t0, float t1, float t2, float t3, float amount) {
        UUID target = amount < 0.5 ? ((SpectateEntityKeyframe)p1).target : ((SpectateEntityKeyframe)p2).target;
        return new KeyframeChangeSpectateEntity(target);
    }

    @Override
    public KeyframeChange createHermiteInterpolatedChange(Map<Integer, Keyframe> keyframes, float amount) {
        float lowestTickDelta = Float.MAX_VALUE;
        UUID target = null;
        for (Map.Entry<Integer, Keyframe> entry : keyframes.entrySet()) {
            float tickDelta = Math.abs(entry.getKey() - amount);
            if (tickDelta < lowestTickDelta) {
                lowestTickDelta = tickDelta;
                target = ((SpectateEntityKeyframe)entry.getValue()).target;
            }
        }

        return new KeyframeChangeSpectateEntity(target);
    }

    public static class TypeAdapter implements JsonSerializer<SpectateEntityKeyframe>, JsonDeserializer<SpectateEntityKeyframe> {
        @Override
        public SpectateEntityKeyframe deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            JsonObject jsonObject = json.getAsJsonObject();
            UUID target = context.deserialize(jsonObject.get("target"), UUID.class);
            InterpolationType interpolationType = context.deserialize(jsonObject.get("interpolation_type"), InterpolationType.class);
            return new SpectateEntityKeyframe(target, interpolationType);
        }

        @Override
        public JsonElement serialize(SpectateEntityKeyframe src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject jsonObject = new JsonObject();
            jsonObject.add("target", context.serialize(src.target));
            jsonObject.addProperty("type", "spectate_entity");
            jsonObject.add("interpolation_type", context.serialize(src.interpolationType()));
            return jsonObject;
        }
    }
}