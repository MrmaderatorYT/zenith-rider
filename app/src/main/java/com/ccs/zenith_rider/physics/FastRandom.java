package com.ccs.zenith_rider.physics;

/** Allocation-free xorshift32 pseudo random generator plus a stateless integer hash. */
public final class FastRandom {

    private int state;

    public FastRandom(int seed) {
        setSeed(seed);
    }

    public void setSeed(int seed) {
        state = seed == 0 ? 0x9E3779B9 : seed;
    }

    public int nextInt() {
        int s = state;
        s ^= s << 13;
        s ^= s >>> 17;
        s ^= s << 5;
        state = s;
        return s;
    }

    /** Uniform float in [0, 1). */
    public float nextFloat() {
        return (nextInt() >>> 8) * (1f / 16777216f);
    }

    public float range(float min, float max) {
        return min + (max - min) * nextFloat();
    }

    public int nextInt(int bound) {
        return (nextInt() >>> 1) % bound;
    }

    /** Deterministic hash of an integer mapped to [0, 1). */
    public static float hash(int n) {
        n = (n << 13) ^ n;
        n = n * (n * n * 15731 + 789221) + 1376312589;
        return (n & 0x7fffffff) / 2147483648f;
    }
}
