package com.moulberry.flashback.keyframe.change;

import com.moulberry.flashback.keyframe.handler.KeyframeHandler;

import java.util.UUID;

public class KeyframeChangeSpectateEntity implements KeyframeChange {

    public final UUID target;

    public KeyframeChangeSpectateEntity(UUID target) {
        this.target = target;
    }

    @Override
    public void apply(KeyframeHandler keyframeHandler) {
        keyframeHandler.spectateEntity(this.target);
    }

    @Override
    public KeyframeChange interpolate(KeyframeChange to, double amount) {return this; }
}