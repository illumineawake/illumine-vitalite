package com.illumine.barb3tickfishing;

class ModeScheduler
{
    private final Barb3TickRuntimeConfig config;
    private final java.util.function.Consumer<String> logger;

    private long modeExpiresAtMs = 0L;
    private boolean switchQueued = false;
    private FishingMode fishingMode = FishingMode.THREE_TICK;

    private ThreeTickFrequencyMode activeRandomProfile = null;
    private RandomPhase randomPhase = RandomPhase.NONE;

    private enum RandomPhase
    {
        NONE,
        PHASE_3T,
        PHASE_NORMAL
    }

    ModeScheduler(Barb3TickRuntimeConfig config, java.util.function.Consumer<String> logger)
    {
        this.config = config;
        this.logger = (logger == null) ? s -> { } : logger;
    }

    void reset()
    {
        modeExpiresAtMs = 0L;
        switchQueued = false;
        fishingMode = FishingMode.THREE_TICK;
        activeRandomProfile = null;
        randomPhase = RandomPhase.NONE;
    }

    void initialiseMode()
    {
        switchQueued = false;
        if (isRandomActive())
        {
            pickNewRandomProfile();
            boolean startThreeTick = startsInThreeTickForProfile(activeRandomProfile);
            fishingMode = startThreeTick ? FishingMode.THREE_TICK : FishingMode.NORMAL;
            randomPhase = startThreeTick ? RandomPhase.PHASE_3T : RandomPhase.PHASE_NORMAL;
        }
        else
        {
            fishingMode = config.frequencyMode().startsInThreeTick()
                    ? FishingMode.THREE_TICK
                    : FishingMode.NORMAL;
        }
        scheduleNextWindow();
    }

    boolean tickFishing()
    {
        return fishingMode == FishingMode.THREE_TICK;
    }

    long modeExpiresAtMs()
    {
        return modeExpiresAtMs;
    }

    boolean switchQueued()
    {
        return switchQueued;
    }

    void queueSwitch()
    {
        switchQueued = true;
    }

    void clearQueue()
    {
        switchQueued = false;
    }

    void setFishingMode(FishingMode mode)
    {
        fishingMode = mode;
        scheduleNextWindow();
        log("Switched fishing mode to " + (tickFishing() ? "3-tick" : "normal"));
    }

    void refreshScheduleAfterConfigChange()
    {
        scheduleNextWindow();
    }

    void onWindowExpired()
    {
        clearQueue();
        if (!isRandomActive())
        {
            if (config.switchingEnabled())
            {
                queueSwitch();
            }
            else
            {
                modeExpiresAtMs = 0L;
            }
            return;
        }

        if (activeRandomProfile == null)
        {
            pickNewRandomProfile();
            randomPhase = startsInThreeTickForProfile(activeRandomProfile) ? RandomPhase.PHASE_3T : RandomPhase.PHASE_NORMAL;
        }

        switch (activeRandomProfile)
        {
            case MOSTLY:
            case SOMETIMES:
            {
                if (randomPhase == RandomPhase.PHASE_3T)
                {
                    queueSwitch();
                    randomPhase = RandomPhase.PHASE_NORMAL;
                    return;
                }
                pickNewRandomProfile();
                boolean startThreeTick = startsInThreeTickForProfile(activeRandomProfile);
                RandomPhase nextPhase = startThreeTick ? RandomPhase.PHASE_3T : RandomPhase.PHASE_NORMAL;
                if (tickFishing() != startThreeTick)
                {
                    queueSwitch();
                    randomPhase = nextPhase;
                }
                else
                {
                    randomPhase = nextPhase;
                    scheduleNextWindow();
                }
                return;
            }
            case ALWAYS:
            {
                pickNewRandomProfile();
                boolean startThreeTick = startsInThreeTickForProfile(activeRandomProfile);
                randomPhase = startThreeTick ? RandomPhase.PHASE_3T : RandomPhase.PHASE_NORMAL;
                if (tickFishing() != startThreeTick)
                {
                    queueSwitch();
                }
                else
                {
                    scheduleNextWindow();
                }
                return;
            }
            case NEVER:
            {
                pickNewRandomProfile();
                boolean startThreeTick = startsInThreeTickForProfile(activeRandomProfile);
                randomPhase = startThreeTick ? RandomPhase.PHASE_3T : RandomPhase.PHASE_NORMAL;
                if (tickFishing() != startThreeTick)
                {
                    queueSwitch();
                }
                else
                {
                    scheduleNextWindow();
                }
                return;
            }
            case RANDOM:
            default:
            {
                pickNewRandomProfile();
                scheduleNextWindow();
            }
        }
    }

    ThreeTickFrequencyMode activeRandomProfile()
    {
        return activeRandomProfile;
    }

    boolean isRandomActive()
    {
        return config.frequencyMode() == ThreeTickFrequencyMode.RANDOM;
    }

    private void scheduleNextWindow()
    {
        if (!isRandomActive() && !config.switchingEnabled())
        {
            modeExpiresAtMs = 0L;
            return;
        }
        long duration = isRandomActive()
                ? rollDurationForRandomCurrentWindow()
                : (tickFishing() ? rollThreeTickDurationMs() : rollNormalDurationMs());
        modeExpiresAtMs = System.currentTimeMillis() + duration;
    }

    private void pickNewRandomProfile()
    {
        ThreeTickFrequencyMode[] profiles = {
                ThreeTickFrequencyMode.ALWAYS,
                ThreeTickFrequencyMode.MOSTLY,
                ThreeTickFrequencyMode.SOMETIMES,
                ThreeTickFrequencyMode.NEVER
        };
        int idx = randomInt(0, profiles.length - 1);
        activeRandomProfile = profiles[idx];
        log("Random profile selected: " + activeRandomProfile.shortLabel());
    }

    private boolean startsInThreeTickForProfile(ThreeTickFrequencyMode profile)
    {
        return profile != ThreeTickFrequencyMode.NEVER;
    }

    private long rollDurationForRandomCurrentWindow()
    {
        return tickFishing() ? rollThreeTickDurationForProfile(activeRandomProfile)
                : rollNormalDurationForProfile(activeRandomProfile);
    }

    private long rollThreeTickDurationForProfile(ThreeTickFrequencyMode profile)
    {
        if (profile == null)
        {
            return randomInt(30_000, 90_000);
        }
        switch (profile)
        {
            case ALWAYS:
            case MOSTLY:
                return randomInt(90_000, 180_000);
            case SOMETIMES:
                return randomInt(30_000, 90_000);
            case NEVER:
            default:
                return randomInt(30_000, 90_000);
        }
    }

    private long rollNormalDurationForProfile(ThreeTickFrequencyMode profile)
    {
        if (profile == null)
        {
            return randomInt(30_000, 90_000);
        }
        switch (profile)
        {
            case MOSTLY:
                return randomInt(30_000, 90_000);
            case SOMETIMES:
                return randomInt(180_000, 300_000);
            case NEVER:
                return randomInt(180_000, 300_000);
            case ALWAYS:
            default:
                return randomInt(30_000, 90_000);
        }
    }

    private long rollThreeTickDurationMs()
    {
        switch (config.frequencyMode())
        {
            case ALWAYS:
            case MOSTLY:
                return randomInt(90_000, 180_000);
            case SOMETIMES:
                return randomInt(30_000, 90_000);
            case NEVER:
            default:
                return randomInt(30_000, 90_000);
        }
    }

    private long rollNormalDurationMs()
    {
        switch (config.frequencyMode())
        {
            case MOSTLY:
                return randomInt(30_000, 90_000);
            case SOMETIMES:
                return randomInt(180_000, 300_000);
            case NEVER:
                return randomInt(180_000, 300_000);
            case ALWAYS:
            default:
                return randomInt(30_000, 90_000);
        }
    }

    private int randomInt(int minInclusive, int maxInclusive)
    {
        if (maxInclusive <= minInclusive)
        {
            return minInclusive;
        }
        return java.util.concurrent.ThreadLocalRandom.current().nextInt(minInclusive, maxInclusive + 1);
    }

    private void log(String message)
    {
        logger.accept(message);
    }
}
