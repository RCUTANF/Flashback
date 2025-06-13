package com.moulberry.flashback.keyframe.interpolation;

public enum SidedInterpolationType {

    SMOOTH,
    LINEAR,
    EASE,
    HOLD,
    HERMITE,
    TRIGGER;

    private boolean isSpecial() {
        return this == SidedInterpolationType.SMOOTH || this == SidedInterpolationType.HERMITE;
    }

    public static float interpolate(SidedInterpolationType left, SidedInterpolationType right, float amount) {
        // 处理TRIGGER类型
        if (left == SidedInterpolationType.TRIGGER) {
            // 只在精确的关键帧点(amount=0或1)触发，使用极小的容差
            if (amount == 0.0f) {
                return 0.0f; // 特殊值表示"在起始关键帧触发"
            }
            else {
                return -1.0f; // 中间状态不触发任何操作
            }
        }
        if (left.isSpecial()) {
            if (right.isSpecial()) {
                left = SidedInterpolationType.LINEAR;
                right = SidedInterpolationType.LINEAR;
            } else {
                left = right;
            }
        } else if (right.isSpecial()) {
            right = left;
        }

        if (left == SidedInterpolationType.HOLD) {
            return 0.0f;
        }
        if (right == SidedInterpolationType.HOLD) {
            right = left;
        }

        if (left == SidedInterpolationType.LINEAR) {
            if (right == SidedInterpolationType.LINEAR) {
                return amount;
            } else if (right == SidedInterpolationType.EASE) {
                // https://easings.net/#easeOutCubic
                return 1 - (float) Math.pow(1 - amount, 3);
            }
        } else if (left == SidedInterpolationType.EASE) {
            if (right == SidedInterpolationType.LINEAR) {
                // https://easings.net/#easeInCubic
                return (float) Math.pow(amount, 3);
            } else if (right == SidedInterpolationType.EASE) {
                // https://easings.net/#easeInOutCubic
                if (amount < 0.5) {
                    return 4 * amount * amount * amount;
                } else {
                    return 1 - (float) Math.pow(-2 * amount + 2, 3) / 2;
                }
            }
        }

        throw new IllegalArgumentException("Don't know how to interpolate " + left + " and " + right);

    }

}
