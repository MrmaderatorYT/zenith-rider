package com.ccs.zenith_rider.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;

/**
 * Fully procedural audio: generative pentatonic ambient music and every sound effect are
 * synthesised sample by sample (PCM 16-bit mono, 44100 Hz) on a dedicated high-priority thread
 * and streamed through AudioTrack. The synthesis loop works on preallocated primitive arrays only.
 *
 * The game thread talks to the audio thread through volatile parameters and trigger counters.
 */
public final class ProceduralAudioEngine implements Runnable {

    private static final int SR = 44100;
    private static final int BLOCK = 512;
    private static final int TABLE = 4096;
    private static final int TABLE_MASK = TABLE - 1;
    private static final float[] SIN = new float[TABLE];

    static {
        for (int i = 0; i < TABLE; i++) {
            SIN[i] = (float) Math.sin(2.0 * Math.PI * i / TABLE);
        }
    }

    private static final int PADS = 10;
    private static final int BELLS = 8;
    private static final int ORB_VOICES = 4;

    private static final float[] ROOTS = {130.81f, 146.83f, 110.00f, 92.50f};
    private static final int[] MAJOR_PENTATONIC = {0, 2, 4, 7, 9};
    private static final int[] MINOR_PENTATONIC = {0, 3, 5, 7, 10};
    private static final int[] PROGRESSION = {0, 3, 1, 4, 2, 0, 2, 3};
    private static final int[] FLOW_ARPEGGIO = {5, 7, 9, 10, 12};
    private static final float[] SHATTER_PARTIALS = {2093f, 3170f, 4231f, 5387f};

    // ---- thread / device ----
    private volatile boolean running;
    private Thread thread;
    private AudioTrack track;
    private final short[] pcm = new short[BLOCK];

    // ---- parameters written by the game thread ----
    private volatile boolean muted;
    private volatile float slideLevel;
    private volatile float slideSpeed;
    private volatile float railLevel;
    private volatile float windLevel;
    private volatile float altitude;
    private volatile float flowLevel;
    private volatile int dayPhase;
    private volatile float rainLevel;
    private volatile float galeLevel;

    private volatile int jumpTrig, landTrig, orbTrig, thudTrig, shatterTrig, flowTrig, uiTrig, trickTrig, thunderTrig, dashTrig;
    private int jumpSeen, landSeen, orbSeen, thudSeen, shatterSeen, flowSeen, uiSeen, trickSeen, thunderSeen, dashSeen;

    // ---- music state (audio thread only) ----
    private final float[] padInc = new float[PADS];
    private final float[] padPhase = new float[PADS];
    private final float[] padPhase2 = new float[PADS];
    private final float[] padEnv = new float[PADS];
    private final float[] padAmp = new float[PADS];
    private final int[] padStage = new int[PADS];

    private final float[] bellInc = new float[BELLS];
    private final float[] bellPhase = new float[BELLS];
    private final float[] bellEnv = new float[BELLS];
    private final float[] bellDecay = new float[BELLS];
    private final float[] bellAmp = new float[BELLS];
    private final int[] bellDelay = new int[BELLS];
    private final boolean[] bellOn = new boolean[BELLS];
    private int bellCursor;

    private int samplesToChord = SR / 2;
    private int samplesToBell = SR * 2;
    private int chordStep;

    private final float[] delay = new float[SR / 2 + 1];
    private int delayPos;
    private float delayLp;

    // ---- sfx state ----
    private int noiseState = 0x1234ABCD;
    private float pinkB0, pinkB1, pinkB2;
    private float slideLp1, slideLp2, slideAmp, railAmp;
    private float windLow, windBand, windAmp;
    private float railPhase;

    private int jumpT = -1;
    private float jumpPhase;
    private int landT = -1;
    private float landPhase;
    private int thudT = -1;
    private float thudPhase, thudLp;
    private int shatterT = -1;
    private final float[] shatterPhase = new float[SHATTER_PARTIALS.length];
    private float shatterLp, shatterLow;
    private int uiT = -1;
    private float uiPhase;

    private final float[] orbInc = new float[ORB_VOICES];
    private final float[] orbPhase = new float[ORB_VOICES];
    private final float[] orbPhase2 = new float[ORB_VOICES];
    private final float[] orbEnv = new float[ORB_VOICES];
    private int orbCursor;
    private int orbStreak;
    private int samplesSinceOrb = SR * 4;

    // ---- weather ----
    private float rainAmp, rainLp, rainTone;
    private float galeAmp, galeLow, galeBand, galeLfo, galeLfo2;
    private int thunderT = -1;
    private float thunderLp1, thunderLp2, thunderPhase;
    private int dashT = -1;
    private float dashLow, dashBand;

    private float masterGain;

    // ------------------------------------------------------------------ control API

    public synchronized void start() {
        if (running) {
            return;
        }
        int minBuf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufBytes = Math.max(minBuf, BLOCK * 2 * 4);
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SR)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bufBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                track = null;
                return;
            }
            track.play();
        } catch (RuntimeException e) {
            track = null;
            return;
        }
        masterGain = 0f;
        running = true;
        thread = new Thread(this, "ZenithAudio");
        thread.start();
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            thread.join(600);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        thread = null;
        if (track != null) {
            try {
                track.pause();
                track.flush();
            } catch (IllegalStateException ignored) {
                // The track was already torn down by the system.
            }
            track.release();
            track = null;
        }
    }

    public void setMuted(boolean value) {
        muted = value;
    }

    public boolean isMuted() {
        return muted;
    }

    /** Continuous ride sounds: slide hiss level and speed (0..1), rail grind, air wind. */
    public void setRide(float slide, float speed01, float rail, float wind) {
        slideLevel = slide;
        slideSpeed = speed01;
        railLevel = rail;
        windLevel = wind;
    }

    /** Musical context: rider altitude (0..1), time of day (0..3), Zenith Flow intensity. */
    public void setWorld(float altitude01, int phase, float flow) {
        altitude = altitude01;
        dayPhase = phase;
        flowLevel = flow;
    }

    /** Weather ambience: steady rain (0..1) and the gusting wind of a storm or blizzard (0..1). */
    public void setWeather(float rain, float gale) {
        rainLevel = rain;
        galeLevel = gale;
    }

    public void playThunder() {
        thunderTrig++;
    }

    /** Airy whoosh of the dash that follows a landed trick. */
    public void playDash() {
        dashTrig++;
    }

    public void playJump() {
        jumpTrig++;
    }

    public void playLand() {
        landTrig++;
    }

    public void playOrb() {
        orbTrig++;
    }

    public void playThud() {
        thudTrig++;
    }

    public void playShatter() {
        shatterTrig++;
    }

    public void playFlow() {
        flowTrig++;
    }

    public void playUi() {
        uiTrig++;
    }

    public void playTrick() {
        trickTrig++;
    }

    // ------------------------------------------------------------------ audio thread

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        final AudioTrack out = track;
        while (running) {
            renderBlock();
            int written = out.write(pcm, 0, BLOCK);
            if (written < 0) {
                break;
            }
        }
    }

    private float noise() {
        int s = noiseState;
        s ^= s << 13;
        s ^= s >>> 17;
        s ^= s << 5;
        noiseState = s;
        return s * (1f / 2147483648f);
    }

    private float degreeFrequency(int degree, float octaveMul) {
        int phase = dayPhase & 3;
        int[] scale = phase < 2 ? MAJOR_PENTATONIC : MINOR_PENTATONIC;
        int octave = degree >= 0 ? degree / 5 : -((4 - degree) / 5);
        int index = degree - octave * 5;
        int semis = scale[index] + 12 * octave;
        return ROOTS[phase] * octaveMul * (float) Math.pow(2.0, semis / 12.0);
    }

    private void triggerPad(float freq, float amp) {
        int best = 0;
        float bestEnv = 10f;
        for (int v = 0; v < PADS; v++) {
            if (padStage[v] == 0) {
                best = v;
                bestEnv = -1f;
                break;
            }
            if (padStage[v] == 3 && padEnv[v] < bestEnv) {
                best = v;
                bestEnv = padEnv[v];
            }
        }
        padInc[best] = freq / SR;
        padAmp[best] = amp;
        padStage[best] = 1;
        if (bestEnv < 0f) {
            padEnv[best] = 0f;
        }
    }

    private void triggerBell(float freq, float amp, float decaySeconds, int delaySamples) {
        int v = bellCursor;
        bellCursor = (bellCursor + 1) % BELLS;
        bellInc[v] = freq / SR;
        bellPhase[v] = 0f;
        bellEnv[v] = 1f;
        bellAmp[v] = amp;
        bellDecay[v] = (float) Math.exp(-1.0 / (decaySeconds * SR));
        bellDelay[v] = delaySamples;
        bellOn[v] = true;
    }

    private void nextChord() {
        for (int v = 0; v < PADS; v++) {
            if (padStage[v] == 1 || padStage[v] == 2) {
                padStage[v] = 3;
            }
        }
        int shift = (int) (altitude * 3.99f);
        int base = PROGRESSION[chordStep % PROGRESSION.length] + shift;
        chordStep++;
        triggerPad(degreeFrequency(base - 5, 1f), 0.060f);
        triggerPad(degreeFrequency(base, 1f), 0.050f);
        triggerPad(degreeFrequency(base + 2, 1f), 0.042f);
        triggerPad(degreeFrequency(base + 4, 1f), 0.036f);
        float flow = flowLevel;
        samplesToChord = (int) (SR * (4.4f - 1.4f * flow));
    }

    private void nextBell() {
        float flow = flowLevel;
        float chance = 0.45f + 0.4f * flow;
        if ((noise() * 0.5f + 0.5f) < chance) {
            int shift = (int) (altitude * 3.99f);
            int degree = 5 + shift + (int) ((noise() * 0.5f + 0.5f) * 7.99f);
            triggerBell(degreeFrequency(degree, 2f), 0.05f + 0.02f * flow, 1.4f, 0);
        }
        samplesToBell = (int) (SR * (0.85f - 0.4f * flow));
    }

    private void handleTriggers() {
        int t = jumpTrig;
        if (t != jumpSeen) {
            jumpSeen = t;
            jumpT = 0;
            jumpPhase = 0f;
        }
        t = landTrig;
        if (t != landSeen) {
            landSeen = t;
            landT = 0;
            landPhase = 0f;
        }
        t = thudTrig;
        if (t != thudSeen) {
            thudSeen = t;
            thudT = 0;
            thudPhase = 0f;
        }
        t = shatterTrig;
        if (t != shatterSeen) {
            shatterSeen = t;
            shatterT = 0;
        }
        t = thunderTrig;
        if (t != thunderSeen) {
            thunderSeen = t;
            thunderT = 0;
        }
        t = dashTrig;
        if (t != dashSeen) {
            dashSeen = t;
            dashT = 0;
        }
        t = uiTrig;
        if (t != uiSeen) {
            uiSeen = t;
            uiT = 0;
            uiPhase = 0f;
        }
        t = orbTrig;
        if (t != orbSeen) {
            orbSeen = t;
            if (samplesSinceOrb < SR) {
                if (orbStreak < 9) {
                    orbStreak++;
                }
            } else {
                orbStreak = 0;
            }
            samplesSinceOrb = 0;
            int v = orbCursor;
            orbCursor = (orbCursor + 1) % ORB_VOICES;
            float f = degreeFrequency(5 + orbStreak, 2f);
            orbInc[v] = f / SR;
            orbPhase[v] = 0f;
            orbPhase2[v] = 0f;
            orbEnv[v] = 1f;
        }
        t = trickTrig;
        if (t != trickSeen) {
            trickSeen = t;
            triggerBell(degreeFrequency(7, 2f), 0.10f, 0.5f, 0);
            triggerBell(degreeFrequency(9, 2f), 0.10f, 0.7f, (int) (SR * 0.08f));
        }
        t = flowTrig;
        if (t != flowSeen) {
            flowSeen = t;
            for (int i = 0; i < FLOW_ARPEGGIO.length; i++) {
                triggerBell(degreeFrequency(FLOW_ARPEGGIO[i], 2f), 0.11f, 1.1f, (int) (SR * 0.07f * i));
            }
        }
    }

    private void renderBlock() {
        handleTriggers();

        final float slideTarget = slideLevel;
        final float railTarget = railLevel;
        final float windTarget = windLevel;
        final float speed = slideSpeed;
        final float gainTarget = muted ? 0f : 0.9f;
        final float rainTarget = rainLevel;
        final float galeTarget = galeLevel;

        final float slideCut = 1f - (float) Math.exp(-2.0 * Math.PI * (320f + 2600f * speed) / SR);
        final float windF = 2f * (float) Math.sin(Math.PI * (480f + 1900f * windTarget) / SR);
        final float padRelease = 0.99998715f;   // ~1.75 s time constant
        final float padAttack = 1f / (1.3f * SR);
        final float orbDecay = 0.99982f;        // ~0.125 s time constant
        final float railInc = 2350f / SR;
        final int delayLen = delay.length;

        for (int n = 0; n < BLOCK; n++) {
            // ---------------- music scheduling ----------------
            if (--samplesToChord <= 0) {
                nextChord();
            }
            if (--samplesToBell <= 0) {
                nextBell();
            }
            samplesSinceOrb++;

            // ---------------- pads ----------------
            float music = 0f;
            for (int v = 0; v < PADS; v++) {
                int stage = padStage[v];
                if (stage == 0) {
                    continue;
                }
                float env = padEnv[v];
                if (stage == 1) {
                    env += padAttack;
                    if (env >= 1f) {
                        env = 1f;
                        padStage[v] = 2;
                    }
                } else if (stage == 3) {
                    env *= padRelease;
                    if (env < 0.0004f) {
                        env = 0f;
                        padStage[v] = 0;
                    }
                }
                padEnv[v] = env;
                float ph = padPhase[v] + padInc[v];
                if (ph >= 1f) {
                    ph -= 1f;
                }
                padPhase[v] = ph;
                float ph2 = padPhase2[v] + padInc[v] * 1.0045f;
                if (ph2 >= 1f) {
                    ph2 -= 1f;
                }
                padPhase2[v] = ph2;
                int idx = (int) (ph * TABLE);
                float wave = SIN[idx & TABLE_MASK] + 0.28f * SIN[(idx << 1) & TABLE_MASK]
                        + 0.6f * SIN[(int) (ph2 * TABLE) & TABLE_MASK];
                music += wave * env * padAmp[v];
            }

            // ---------------- bells ----------------
            for (int v = 0; v < BELLS; v++) {
                if (!bellOn[v]) {
                    continue;
                }
                if (bellDelay[v] > 0) {
                    bellDelay[v]--;
                    continue;
                }
                float env = bellEnv[v] * bellDecay[v];
                bellEnv[v] = env;
                if (env < 0.0006f) {
                    bellOn[v] = false;
                    continue;
                }
                float ph = bellPhase[v] + bellInc[v];
                if (ph >= 1f) {
                    ph -= 1f;
                }
                bellPhase[v] = ph;
                int idx = (int) (ph * TABLE);
                music += (SIN[idx & TABLE_MASK] + 0.22f * SIN[(idx * 3) & TABLE_MASK]) * env * bellAmp[v];
            }

            // ---------------- orb chimes (root + fifth + octave) ----------------
            for (int v = 0; v < ORB_VOICES; v++) {
                float env = orbEnv[v];
                if (env <= 0f) {
                    continue;
                }
                env *= orbDecay;
                if (env < 0.0008f) {
                    env = 0f;
                }
                orbEnv[v] = env;
                float ph = orbPhase[v] + orbInc[v];
                if (ph >= 1f) {
                    ph -= 1f;
                }
                orbPhase[v] = ph;
                float ph2 = orbPhase2[v] + orbInc[v] * 1.5f;
                if (ph2 >= 1f) {
                    ph2 -= 1f;
                }
                orbPhase2[v] = ph2;
                int idx = (int) (ph * TABLE);
                music += (SIN[idx & TABLE_MASK] + 0.5f * SIN[(int) (ph2 * TABLE) & TABLE_MASK]
                        + 0.35f * SIN[(idx << 1) & TABLE_MASK]) * env * 0.085f;
            }

            // ---------------- echo ----------------
            float echoed = delay[delayPos];
            delayLp += (echoed - delayLp) * 0.35f;
            delay[delayPos] = music + delayLp * 0.42f;
            if (++delayPos >= delayLen) {
                delayPos = 0;
            }
            float sample = music + echoed * 0.38f;

            // ---------------- noise sources ----------------
            float white = noise();
            pinkB0 = 0.99765f * pinkB0 + white * 0.0990460f;
            pinkB1 = 0.96300f * pinkB1 + white * 0.2965164f;
            pinkB2 = 0.57000f * pinkB2 + white * 1.0526913f;
            float pink = (pinkB0 + pinkB1 + pinkB2 + white * 0.1848f) * 0.22f;

            // slide hiss: pink noise through a speed-controlled low-pass
            slideAmp += (slideTarget - slideAmp) * 0.0012f;
            slideLp1 += (pink - slideLp1) * slideCut;
            slideLp2 += (slideLp1 - slideLp2) * slideCut;
            sample += slideLp2 * slideAmp * 0.75f;

            // rail grind: bright noise plus a noisy metallic ring
            railAmp += (railTarget - railAmp) * 0.002f;
            if (railAmp > 0.001f) {
                railPhase += railInc;
                if (railPhase >= 1f) {
                    railPhase -= 1f;
                }
                float ring = SIN[(int) (railPhase * TABLE) & TABLE_MASK];
                sample += ((pink - slideLp2) * 0.45f + ring * (0.25f + pink * 1.6f) * 0.12f) * railAmp;
            }

            // wind whistle: band-passed white noise (state variable filter)
            windAmp += (windTarget - windAmp) * 0.0015f;
            windLow += windF * windBand;
            float high = white - windLow - 0.22f * windBand;
            windBand += windF * high;
            sample += windBand * windAmp * 0.085f;

            // ---------------- weather ----------------
            // rain: bright hiss (white noise with the lows removed) over a soft pink bed
            rainAmp += (rainTarget - rainAmp) * 0.0004f;
            if (rainAmp > 0.001f) {
                rainLp += (white - rainLp) * 0.24f;
                rainTone += ((white - rainLp) - rainTone) * 0.55f;
                sample += (rainTone * 0.42f + pink * 0.5f) * rainAmp * 0.17f;
            }
            // gale: band-passed noise whose pitch and loudness wander with two slow oscillators
            galeAmp += (galeTarget - galeAmp) * 0.0003f;
            if (galeAmp > 0.001f) {
                galeLfo += 0.11f / SR;
                if (galeLfo >= 1f) {
                    galeLfo -= 1f;
                }
                galeLfo2 += 0.047f / SR;
                if (galeLfo2 >= 1f) {
                    galeLfo2 -= 1f;
                }
                float sweep = 0.5f + 0.5f * SIN[(int) (galeLfo * TABLE) & TABLE_MASK];
                float swell = 0.55f + 0.45f * SIN[(int) (galeLfo2 * TABLE) & TABLE_MASK];
                float gf = 6.2831855f * (260f + 320f * sweep + 240f * galeAmp) / SR;
                galeLow += gf * galeBand;
                float galeHigh = pink * 3f - galeLow - 0.30f * galeBand;
                galeBand += gf * galeHigh;
                sample += galeBand * galeAmp * swell * 0.16f;
            }
            // thunder: a bright crack, then a long low rumble that swells and dies away
            if (thunderT >= 0) {
                float t = thunderT / (float) SR;
                if (t >= 3.4f) {
                    thunderT = -1;
                } else {
                    thunderLp1 += (white - thunderLp1) * 0.014f;
                    thunderLp2 += (thunderLp1 - thunderLp2) * 0.014f;
                    thunderPhase += 2.7f / SR;
                    if (thunderPhase >= 1f) {
                        thunderPhase -= 1f;
                    }
                    float roll = 0.62f + 0.38f * SIN[(int) (thunderPhase * TABLE) & TABLE_MASK];
                    float rumble = thunderLp2 * 15f * roll * (float) Math.exp(-t * 1.15f);
                    float crack = t < 0.10f ? (white - thunderLp1) * (1f - t / 0.10f) * 0.42f : 0f;
                    sample += rumble + crack;
                    thunderT++;
                }
            }

            // ---------------- one-shot effects ----------------
            if (dashT >= 0) {
                float u = dashT / (0.42f * SR);
                if (u >= 1f) {
                    dashT = -1;
                } else {
                    // band-passed noise whose pitch sweeps upwards: a rush of air
                    float df = 6.2831855f * (500f + 2600f * u) / SR;
                    dashLow += df * dashBand;
                    float dashHigh = white - dashLow - 0.5f * dashBand;
                    dashBand += df * dashHigh;
                    float e = u < 0.15f ? u / 0.15f : (1f - u) / 0.85f;
                    sample += dashBand * e * e * 0.15f;
                    dashT++;
                }
            }
            if (jumpT >= 0) {
                float u = jumpT / (0.18f * SR);
                if (u >= 1f) {
                    jumpT = -1;
                } else {
                    jumpPhase += (240f + 520f * u) / SR;
                    if (jumpPhase >= 1f) {
                        jumpPhase -= 1f;
                    }
                    float e = 1f - u;
                    sample += SIN[(int) (jumpPhase * TABLE) & TABLE_MASK] * e * e * 0.2f;
                    jumpT++;
                }
            }
            if (landT >= 0) {
                float u = landT / (0.15f * SR);
                if (u >= 1f) {
                    landT = -1;
                } else {
                    landPhase += (125f - 70f * u) / SR;
                    if (landPhase >= 1f) {
                        landPhase -= 1f;
                    }
                    float e = 1f - u;
                    sample += (SIN[(int) (landPhase * TABLE) & TABLE_MASK] * 0.3f + slideLp2 * 0.5f) * e * e;
                    landT++;
                }
            }
            if (thudT >= 0) {
                float t = thudT / (float) SR;
                if (t >= 0.6f) {
                    thudT = -1;
                } else {
                    float f = 34f + 70f * (float) Math.exp(-t * 6f);
                    thudPhase += f / SR;
                    if (thudPhase >= 1f) {
                        thudPhase -= 1f;
                    }
                    thudLp += (white - thudLp) * 0.06f;
                    float body = SIN[(int) (thudPhase * TABLE) & TABLE_MASK] * (float) Math.exp(-t * 7f) * 0.85f;
                    float burst = thudLp * (float) Math.exp(-t * 20f) * 1.6f;
                    sample += body + burst;
                    thudT++;
                }
            }
            if (shatterT >= 0) {
                float t = shatterT / (float) SR;
                if (t >= 0.55f) {
                    shatterT = -1;
                } else {
                    shatterLp += (white - shatterLp) * 0.25f;
                    float glass = (white - shatterLp) * (float) Math.exp(-t * 15f) * 0.32f;
                    float ringEnv = (float) Math.exp(-t * 9f) * 0.06f;
                    float ring = 0f;
                    for (int k = 0; k < SHATTER_PARTIALS.length; k++) {
                        float ph = shatterPhase[k] + SHATTER_PARTIALS[k] / SR;
                        if (ph >= 1f) {
                            ph -= 1f;
                        }
                        shatterPhase[k] = ph;
                        ring += SIN[(int) (ph * TABLE) & TABLE_MASK];
                    }
                    shatterLow += 72f / SR;
                    if (shatterLow >= 1f) {
                        shatterLow -= 1f;
                    }
                    float low = SIN[(int) (shatterLow * TABLE) & TABLE_MASK] * (float) Math.exp(-t * 12f) * 0.4f;
                    sample += glass + ring * ringEnv + low;
                    shatterT++;
                }
            }
            if (uiT >= 0) {
                float u = uiT / (0.07f * SR);
                if (u >= 1f) {
                    uiT = -1;
                } else {
                    uiPhase += (660f + 330f * u) / SR;
                    if (uiPhase >= 1f) {
                        uiPhase -= 1f;
                    }
                    sample += SIN[(int) (uiPhase * TABLE) & TABLE_MASK] * (1f - u) * 0.14f;
                    uiT++;
                }
            }

            // ---------------- master: mute fade + soft clip ----------------
            masterGain += (gainTarget - masterGain) * 0.0008f;
            sample *= masterGain;
            if (sample > 1f) {
                sample = 1f;
            } else if (sample < -1f) {
                sample = -1f;
            }
            sample = sample * (1.5f - 0.5f * sample * sample);
            pcm[n] = (short) (sample * 32000f);
        }
    }
}
