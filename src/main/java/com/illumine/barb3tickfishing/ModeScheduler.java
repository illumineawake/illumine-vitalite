package com.illumine.barb3tickfishing;

class ModeScheduler
{
    private final Barb3TickRuntime runtime;
    private final Barb3TickRuntimeConfig config;

    private long modeExpiresAtMs = 0L;
    private boolean switchQueued = false;
    private Barb3TickRuntime.FishingMode fishingMode = Barb3TickRuntime.FishingMode.THREE_TICK;

    private ThreeTickFrequencyMode activeRandomProfile = null;
    private RandomPhase randomPhase = RandomPhase.NONE;

    private enum RandomPhase
    {
        NONE,
        PHASE_3T,
        PHASE_NORMAL
    }

    ModeScheduler(Barb3TickRuntime runtime, Barb3TickRuntimeConfig config)
    {
        this.runtime = runtime;
        this.config = config;
    }

    void reset()
    {
        modeExpiresAtMs = 0L;
        switchQueued = false;
        fishingMode = Barb3TickRuntime.FishingMode.THREE_TICK;
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
            fishingMode = startThreeTick ? Barb3TickRuntime.FishingMode.THREE_TICK : Barb3TickRuntime.FishingMode.NORMAL;
            randomPhase = startThreeTick ? RandomPhase.PHASE_3T : RandomPhase.PHASE_NORMAL;
        }
        else
        {
            fishingMode = config.frequencyMode().startsInThreeTick()
                    ? Barb3TickRuntime.FishingMode.THREE_TICK
                    : Barb3TickRuntime.FishingMode.NORMAL;
        }
        scheduleNextWindow();
    }

    boolean tickFishing()
    {
        return fishingMode == Barb3TickRuntime.FishingMode.THREE_TICK;
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

    void setFishingMode(Barb3TickRuntime.FishingMode mode)
    {
        fishingMode = mode;
        scheduleNextWindow();
        runtime.log("Switched fishing mode to " + (tickFishing() ? "3-tick" : "normal"));
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
        runtime.log("Random profile selected: " + activeRandomProfile.shortLabel());
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
}
