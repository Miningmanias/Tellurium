// SPDX-License-Identifier: MIT
// WorldgenNext fused NOISE library: Java 21 / Minecraft 1.21.1 exact arithmetic
// on native IEEE FP64/FP32 with explicit rounding corrections.  Any operation
// outside the domain where exactness is established raises a per-chunk bail
// flag; the host then regenerates that chunk with the original game code.

uint gChunk;      // batch slot of the chunk being evaluated
int gBaseX;       // chunk min block X
int gBaseZ;       // chunk min block Z
int gFirstQX;     // NoiseChunk FlatCache firstNoiseX (quart)
int gFirstQZ;

void bail(uint code) { atomicOr(flags[gChunk], code); }

const uint BAIL_DIV = 1u, BAIL_SQRT = 2u, BAIL_CAST = 4u, BAIL_NONFINITE = 8u, BAIL_RANGE = 16u, BAIL_FLOAT = 32u;

int64_t dbits(double d) { return doubleBitsToInt64(d); }
double bitsd(int64_t b) { return int64BitsToDouble(b); }

// Java (int) narrowing of double: NaN -> 0, saturating, truncation toward zero.
int jd2i(double d) {
    if (isnan(d)) return 0;
    if (d >= 2147483647.0lf) return 2147483647;
    if (d <= -2147483648.0lf) return -2147483647 - 1;
    return int(d);
}
// Mth.floor(double)
int mfloor(double d) {
    int i = jd2i(d);
    return d < double(i) ? i - 1 : i;
}
int jrem(int a, int b) { return a - (a / b) * b; }
int jfloorDiv(int a, int b) {
    int q = a / b;
    if ((jrem(a, b) != 0) && ((a < 0) != (b < 0))) q = q - 1;
    return q;
}
int jfloorMod(int a, int b) { return a - jfloorDiv(a, b) * b; }

double jmin(double a, double b) {
    if (isnan(a)) return a;
    if (isnan(b)) return b;
    if (a == 0.0lf && b == 0.0lf) return dbits(a) < 0l ? a : b;
    return a <= b ? a : b;
}
double jmax(double a, double b) {
    if (isnan(a)) return a;
    if (isnan(b)) return b;
    if (a == 0.0lf && b == 0.0lf) return dbits(a) < 0l ? b : a;
    return a >= b ? a : b;
}
float jminf(float a, float b) {
    if (isnan(a)) return a;
    if (isnan(b)) return b;
    if (a == 0.0 && b == 0.0) return floatBitsToInt(a) < 0 ? a : b;
    return a <= b ? a : b;
}
float jmaxf(float a, float b) {
    if (isnan(a)) return a;
    if (isnan(b)) return b;
    if (a == 0.0 && b == 0.0) return floatBitsToInt(a) < 0 ? b : a;
    return a >= b ? a : b;
}

double nextUpPos(double q) { return bitsd(dbits(q) + 1l); }   // q > 0 finite
double nextDownPos(double q) { return bitsd(dbits(q) - 1l); } // q > 0 finite, not min subnormal

bool safeMagnitude(double v) {
    if (v == 0.0lf) return true;
    int e;
    frexp(v, e);
    return e > -900 && e < 900;
}

// Correctly rounded IEEE division (round to nearest even) for the guarded
// domain.  The FMA residual a - c*b is exact for candidates within a few ulp
// of the true quotient, so comparing |residual| compares exact distances.
precise double jdiv(double a, double b) {
    if (isnan(a) || isnan(b) || isinf(a) || isinf(b) || b == 0.0lf || !safeMagnitude(a) || !safeMagnitude(b)) {
        bail(BAIL_DIV);
        return a / b;
    }
    if (a == 0.0lf) return a / b;
    precise double q = a / b;
    if (!safeMagnitude(q)) { bail(BAIL_DIV); return q; }
    bool negative = q < 0.0lf;
    precise double mq = abs(q);
    precise double ma = abs(a);
    precise double mb = abs(b);
    precise double r = fma(-mq, mb, ma);
    for (int i = 0; i < 4; i++) {
        if (r == 0.0lf) break;
        precise double cand = r > 0.0lf ? nextUpPos(mq) : nextDownPos(mq);
        precise double rc = fma(-cand, mb, ma);
        if (abs(rc) < abs(r)) { mq = cand; r = rc; continue; }
        if (abs(rc) == abs(r)) {
            // exact midpoint: choose the even significand
            if ((dbits(mq) & 1l) != 0l) mq = cand;
        }
        break;
    }
    return negative ? -mq : mq;
}

// Correctly rounded square root for the guarded domain.  For candidate c with
// exact residual r = x - c*c, sqrt(x) > c + u/2  <=>  r > c*u (multiples of the
// residual quantum make the comparison exact); symmetric for the lower side.
precise double jsqrt(double x) {
    if (isnan(x) || isinf(x) || x < 0.0lf || !safeMagnitude(x)) { bail(BAIL_SQRT); return sqrt(x); }
    if (x == 0.0lf) return x;
    precise double c = sqrt(x);
    for (int i = 0; i < 4; i++) {
        precise double r = fma(-c, c, x);
        precise double up = nextUpPos(c);
        precise double down = nextDownPos(c);
        precise double uUp = up - c;
        precise double uDown = c - down;
        if (r > 0.0lf && r > c * uUp) { c = up; continue; }
        if (r < 0.0lf && -r > c * uDown) { c = down; continue; }
        break;
    }
    return c;
}

float jdivf(float a, float b) {
    if (b == 0.0 || isnan(a) || isnan(b) || isinf(a) || isinf(b)) { bail(BAIL_FLOAT); return a / b; }
    return float(jdiv(double(a), double(b)));
}
float jsqrtf(float x) { return float(jsqrt(double(x))); }

// Java float remainder (fmod, exact) for finite a and b > 0 used by End islands.
float jremf(float a, float b) {
    if (isnan(a) || isinf(a) || !(b > 0.0)) { bail(BAIL_FLOAT); return 0.0; }
    precise double da = abs(double(a));
    precise double db = double(b);
    precise double q = floor(da / db);
    precise double r = da - q * db;
    if (r < 0.0) r += db;
    if (r >= db) r -= db;
    float res = float(r);
    return a < 0.0 ? -res : res;
}

precise double lerpd(double t, double a, double b) { return a + t * (b - a); }
precise float lerpf(float t, float a, float b) { return a + t * (b - a); }
precise double clampedLerp(double a, double b, double t) {
    if (t < 0.0lf) return a;
    if (t > 1.0lf) return b;
    return lerpd(t, a, b);
}
precise double inverseLerp(double v, double a, double b) { return jdiv(v - a, b - a); }
precise double clampedMap(double v, double a, double b, double c, double d) { return clampedLerp(c, d, inverseLerp(v, a, b)); }
precise double mapd(double v, double a, double b, double c, double d) { return lerpd(inverseLerp(v, a, b), c, d); }
precise double mclamp(double v, double lo, double hi) { return v < lo ? lo : jmin(v, hi); }

// ---------------------------------------------------------------- noise ---
// dtab Perlin record at index P: [levelCount, then per level 7 doubles:
//   amplitude, inputFactor, valueFactor, xOffset, yOffset, zOffset, permBase (-1 = null level)]
const int PERLIN_STRIDE = 7;

precise double smoothstepd(double v) { return v * v * v * (v * (v * 6.0lf - 15.0lf) + 10.0lf); }
precise double wrapd(double v) { return v - floor(v / 33554432.0lf + 0.5lf) * 33554432.0lf; }

int permAt(int base, int index) { return int(perm[base + (index & 255)]); }

precise double gradDot(int hash, double x, double y, double z) {
    switch (hash & 15) {
        case 0: return x + y;
        case 1: return -x + y;
        case 2: return x - y;
        case 3: return -x - y;
        case 4: return x + z;
        case 5: return -x + z;
        case 6: return x - z;
        case 7: return -x - z;
        case 8: return y + z;
        case 9: return -y + z;
        case 10: return y - z;
        case 11: return -y - z;
        case 12: return x + y;
        case 13: return -y + z;
        case 14: return -x + y;
        default: return -y - z;
    }
}

precise double improvedNoise(int permBase, double xo, double yo, double zo,
                             double x, double y, double z, double yScale, double yMax) {
    precise double sx = x + xo, sy = y + yo, sz = z + zo;
    int x0 = mfloor(sx), y0 = mfloor(sy), z0 = mfloor(sz);
    precise double fx = sx - double(x0), fy = sy - double(y0), fz = sz - double(z0);
    precise double smear = 0.0lf;
    if (yScale != 0.0lf) {
        precise double clamped = (yMax >= 0.0lf && yMax < fy) ? yMax : fy;
        smear = floor(jdiv(clamped, yScale) + 1.0000000116860974E-7lf) * yScale;
    }
    precise double afy = fy - smear;
    int xh = permAt(permBase, x0), xhn = permAt(permBase, x0 + 1);
    int xy00 = permAt(permBase, xh + y0), xy01 = permAt(permBase, xh + y0 + 1);
    int xy10 = permAt(permBase, xhn + y0), xy11 = permAt(permBase, xhn + y0 + 1);
    precise double v000 = gradDot(permAt(permBase, xy00 + z0), fx, afy, fz);
    precise double v100 = gradDot(permAt(permBase, xy10 + z0), fx - 1.0lf, afy, fz);
    precise double v010 = gradDot(permAt(permBase, xy01 + z0), fx, afy - 1.0lf, fz);
    precise double v110 = gradDot(permAt(permBase, xy11 + z0), fx - 1.0lf, afy - 1.0lf, fz);
    precise double v001 = gradDot(permAt(permBase, xy00 + z0 + 1), fx, afy, fz - 1.0lf);
    precise double v101 = gradDot(permAt(permBase, xy10 + z0 + 1), fx - 1.0lf, afy, fz - 1.0lf);
    precise double v011 = gradDot(permAt(permBase, xy01 + z0 + 1), fx, afy - 1.0lf, fz - 1.0lf);
    precise double v111 = gradDot(permAt(permBase, xy11 + z0 + 1), fx - 1.0lf, afy - 1.0lf, fz - 1.0lf);
    precise double tx = smoothstepd(fx), ty = smoothstepd(fy), tz = smoothstepd(fz);
    precise double x00 = lerpd(tx, v000, v100), x10 = lerpd(tx, v010, v110);
    precise double x01 = lerpd(tx, v001, v101), x11 = lerpd(tx, v011, v111);
    return lerpd(tz, lerpd(ty, x00, x10), lerpd(ty, x01, x11));
}

precise double perlinValue(int P, double x, double y, double z) {
    int n = int(dtab[P]);
    precise double result = 0.0lf;
    for (int i = 0; i < n; i++) {
        int L = P + 1 + i * PERLIN_STRIDE;
        int permBase = int(dtab[L + 6]);
        if (permBase >= 0) {
            precise double f = dtab[L + 1];
            precise double noise = improvedNoise(permBase, dtab[L + 3], dtab[L + 4], dtab[L + 5],
                    wrapd(x * f), wrapd(y * f), wrapd(z * f), 0.0lf, 0.0lf);
            result += dtab[L] * noise * dtab[L + 2];
        }
    }
    return result;
}

// NormalNoise record at index N: [valueFactor, firstPerlinIndex, secondPerlinIndex]
precise double normalNoise(int N, double x, double y, double z) {
    precise double first = perlinValue(int(dtab[N + 1]), x, y, z);
    precise double factor = 1.0181268882175227lf;
    precise double second = perlinValue(int(dtab[N + 2]), x * factor, y * factor, z * factor);
    return (first + second) * dtab[N];
}

// One BlendedNoise legacy octave: levels indexed from the end of the octave array.
precise double legacyLevel(int P, int octave, double x, double y, double z, double yScale, double yMax) {
    int n = int(dtab[P]);
    int levelIndex = n - 1 - octave;
    if (levelIndex < 0 || levelIndex >= n) return 0.0lf;
    int L = P + 1 + levelIndex * PERLIN_STRIDE;
    int permBase = int(dtab[L + 6]);
    if (permBase < 0) return 0.0lf;
    return dtab[L] * improvedNoise(permBase, dtab[L + 3], dtab[L + 4], dtab[L + 5], x, y, z, yScale, yMax);
}

// BlendedNoise record at index B: [xzScale, yScale, xzFactor, yFactor, smearScaleMultiplier, minP, maxP, mainP]
precise double blendedNoise(int B, int bx, int by, int bz) {
    precise double xzMultiplier = 684.412lf * dtab[B];
    precise double yMultiplier = 684.412lf * dtab[B + 1];
    precise double xValue = double(bx) * xzMultiplier;
    precise double yValue = double(by) * yMultiplier;
    precise double zValue = double(bz) * xzMultiplier;
    precise double mainX = jdiv(xValue, dtab[B + 2]);
    precise double mainY = jdiv(yValue, dtab[B + 3]);
    precise double mainZ = jdiv(zValue, dtab[B + 2]);
    precise double smearScale = jdiv(yMultiplier * dtab[B + 4], dtab[B + 3]);
    int minP = int(dtab[B + 5]), maxP = int(dtab[B + 6]), mainP = int(dtab[B + 7]);
    precise double main = 0.0lf;
    precise double mainScale = 1.0lf;
    precise double inverse = 1.0lf;
    for (int i = 0; i < 8; i++) {
        main += legacyLevel(mainP, i, wrapd(mainX * mainScale), wrapd(mainY * mainScale), wrapd(mainZ * mainScale),
                smearScale * mainScale, mainY * mainScale) * inverse;
        mainScale *= 0.5lf;
        inverse *= 2.0lf;
    }
    precise double blend = (jdiv(main, 10.0lf) + 1.0lf) * 0.5lf;
    bool skipMin = blend >= 1.0lf;
    bool skipMax = blend <= 0.0lf;
    precise double minV = 0.0lf, maxV = 0.0lf;
    precise double limitScale = 1.0lf;
    precise double limitInverse = 1.0lf;
    precise double limitSmear = yMultiplier * dtab[B + 4];
    for (int i = 0; i < 16; i++) {
        precise double wx = wrapd(xValue * limitScale), wy = wrapd(yValue * limitScale), wz = wrapd(zValue * limitScale);
        if (!skipMin) minV += legacyLevel(minP, i, wx, wy, wz, limitSmear * limitScale, yValue * limitScale) * limitInverse;
        if (!skipMax) maxV += legacyLevel(maxP, i, wx, wy, wz, limitSmear * limitScale, yValue * limitScale) * limitInverse;
        limitScale *= 0.5lf;
        limitInverse *= 2.0lf;
    }
    precise double lower = minV * (1.0lf / 512.0lf);
    precise double upper = maxV * (1.0lf / 512.0lf);
    precise double clampedBlend = jmax(0.0lf, jmin(1.0lf, blend));
    return (lower + clampedBlend * (upper - lower)) * (1.0lf / 128.0lf);
}

// ------------------------------------------------------------ End island ---
// dtab record E: [permBase]
const double SIMPLEX_F2 = 0.3660254037844386lf;   // 0.5lf * (sqrt(3) - 1)
const double SIMPLEX_G2 = 0.21132486540518713lf;  // (3 - sqrt(3)) / 6

precise double simplexCorner(int hash, double x, double y) {
    precise double att = 0.5lf - x * x - y * y;
    if (att < 0.0lf) return 0.0lf;
    att *= att;
    int h = hash % 12;
    precise double g;
    switch (h) {
        case 0: g = x + y; break;
        case 1: g = -x + y; break;
        case 2: g = x - y; break;
        case 3: g = -x - y; break;
        case 4: g = x; break;
        case 5: g = -x; break;
        case 6: g = x; break;
        case 7: g = -x; break;
        case 8: g = y; break;
        case 9: g = -y; break;
        case 10: g = y; break;
        default: g = -y; break;
    }
    return att * att * g;
}

precise double simplex2D(int permBase, double x, double y) {
    precise double skew = (x + y) * SIMPLEX_F2;
    int lx = mfloor(x + skew);
    int ly = mfloor(y + skew);
    precise double unskew = double(lx + ly) * SIMPLEX_G2;
    precise double x0 = x - (double(lx) - unskew);
    precise double y0 = y - (double(ly) - unskew);
    int ox = x0 > y0 ? 1 : 0;
    int oy = x0 > y0 ? 0 : 1;
    precise double x1 = x0 - double(ox) + SIMPLEX_G2;
    precise double y1 = y0 - double(oy) + SIMPLEX_G2;
    precise double x2 = x0 - 1.0lf + 2.0lf * SIMPLEX_G2;
    precise double y2 = y0 - 1.0lf + 2.0lf * SIMPLEX_G2;
    int h0 = permAt(permBase, lx + permAt(permBase, ly)) % 12;
    int h1 = permAt(permBase, lx + ox + permAt(permBase, ly + oy)) % 12;
    int h2 = permAt(permBase, lx + 1 + permAt(permBase, ly + 1)) % 12;
    precise double n0 = simplexCorner(h0, x0, y0);
    precise double n1 = simplexCorner(h1, x1, y1);
    precise double n2 = simplexCorner(h2, x2, y2);
    return 70.0lf * (n0 + n1 + n2);
}

precise double endIsland(int E, int bx, int bz) {
    int permBase = int(dtab[E]);
    int x = bx / 8;
    int z = bz / 8;
    int gridX = x / 2;
    int gridZ = z / 2;
    int remX = jrem(x, 2);
    int remZ = jrem(z, 2);
    precise float height = 100.0 - jsqrtf(float(x * x + z * z)) * 8.0;
    height = jmaxf(-100.0, jminf(80.0, height));
    for (int i = -12; i <= 12; i++) {
        for (int j = -12; j <= 12; j++) {
            int64_t ix = int64_t(gridX) + int64_t(i);
            int64_t iz = int64_t(gridZ) + int64_t(j);
            if (ix * ix + iz * iz > 4096l && simplex2D(permBase, double(ix), double(iz)) < -0.8999999761581421lf) {
                precise float scale = float(abs(ix)) * 3439.0 + float(abs(iz)) * 147.0;
                scale = jremf(scale, 13.0);
                scale += 9.0;
                precise float lx = float(remX - i * 2);
                precise float lz = float(remZ - j * 2);
                precise float h = 100.0 - jsqrtf(lx * lx + lz * lz) * scale;
                h = jmaxf(-100.0, jminf(80.0, h));
                height = jmaxf(height, h);
            }
        }
    }
    return jdiv(double(height) - 8.0lf, 128.0lf);
}

// ------------------------------------------------------------- random -----
int64_t mixStafford(int64_t v) {
    v = (v ^ int64_t(uint64_t(v) >> 30)) * -4658895280553007687l;  // 0xBF58476D1CE4E5B9
    v = (v ^ int64_t(uint64_t(v) >> 27)) * -7723592293110705685l;  // 0x94D049BB133111EB
    return v ^ int64_t(uint64_t(v) >> 31);
}
int64_t rotl64(int64_t v, int s) { return int64_t((uint64_t(v) << s) | (uint64_t(v) >> (64 - s))); }

// Mth.getSeed(x, y, z)
int64_t positionSeed(int x, int y, int z) {
    int64_t v = int64_t(x * 3129871) ^ int64_t(z) * 116129781l ^ int64_t(y);
    v = v * v * 42317861l + v * 11l;
    return v >> 16;
}

struct Rng { int64_t lo; int64_t hi; int64_t legacy; bool isLegacy; };

Rng rngAt(bool legacyAlgorithm, int64_t seed, int64_t seedLo, int64_t seedHi, int x, int y, int z) {
    Rng r;
    int64_t ps = positionSeed(x, y, z);
    r.isLegacy = legacyAlgorithm;
    if (legacyAlgorithm) {
        r.legacy = ((ps ^ seed) ^ 25214903917l) & 281474976710655l;
        r.lo = 0l; r.hi = 0l;
    } else {
        int64_t lo = ps ^ seedLo;
        int64_t hi = seedHi;
        if ((lo | hi) == 0l) { lo = -7046029254386353131l; hi = 7640891576956012809l; }
        r.lo = lo; r.hi = hi; r.legacy = 0l;
    }
    return r;
}
int64_t xoroNext(inout Rng r) {
    int64_t result = rotl64(r.lo + r.hi, 17) + r.lo;
    int64_t nh = r.hi ^ r.lo;
    r.lo = rotl64(r.lo, 49) ^ nh ^ (nh << 21);
    r.hi = rotl64(nh, 28);
    return result;
}
int legacyBits(inout Rng r, int bits) {
    r.legacy = (r.legacy * 25214903917l + 11l) & 281474976710655l;
    return int(r.legacy >> (48 - bits));
}
int rngNextInt(inout Rng r, int bound) {
    if (r.isLegacy) {
        if ((bound & -bound) == bound) return int((int64_t(bound) * int64_t(legacyBits(r, 31))) >> 31);
        int bits, value;
        do { bits = legacyBits(r, 31); value = bits % bound; } while (bits - value + (bound - 1) < 0);
        return value;
    }
    int64_t bits = xoroNext(r) & 4294967295l;
    int64_t product = bits * int64_t(bound);
    int64_t low = product & 4294967295l;
    if (low < int64_t(bound)) {
        int64_t threshold = int64_t(uint(-bound) % uint(bound));
        while (low < threshold) {
            bits = xoroNext(r) & 4294967295l;
            product = bits * int64_t(bound);
            low = product & 4294967295l;
        }
    }
    return int(product >> 32);
}
float rngNextFloat(inout Rng r) {
    if (r.isLegacy) return float(legacyBits(r, 24)) * 5.9604645E-8;
    return float(int(uint64_t(xoroNext(r)) >> 40)) * 5.9604645E-8;
}

// ------------------------------------------------------------- splines ---
// Table-driven CubicSpline.Multipoint.apply (float arithmetic, same operation
// order).  Record at perm[N]: n, coordinate index, then per point
// [locationBits, derivativeBits, kind (0 constant, 1 child record), payload].
const int SPLINE_STACK = 16;
float sLoc(int N, int i) { return uintBitsToFloat(perm[N + 2 + i * 4]); }
float sDer(int N, int i) { return uintBitsToFloat(perm[N + 3 + i * 4]); }
uint sKind(int N, int i) { return perm[N + 4 + i * 4]; }
uint sPayload(int N, int i) { return perm[N + 5 + i * 4]; }

precise float splineEval(int root, float cs[8]) {
    int sN[SPLINE_STACK];
    int sState[SPLINE_STACK];
    int sIv[SPLINE_STACK];
    float sV0[SPLINE_STACK];
    float sV1[SPLINE_STACK];
    int sp = 0;
    sN[0] = root;
    sState[0] = 0;
    precise float result = 0.0;
    for (int guard = 0; guard < 4096; guard++) {
        int N = sN[sp];
        int n = int(perm[N]);
        precise float c = cs[int(perm[N + 1])];
        int st = sState[sp];
        bool ready = false;
        if (st == 0) {
            int iv = -1;
            for (int i = 0; i < n; i++) if (!(c < sLoc(N, i))) iv = i;
            sIv[sp] = iv;
            int first = iv < 0 ? 0 : iv;
            sState[sp] = 1;
            if (sKind(N, first) == 0u) {
                sV0[sp] = uintBitsToFloat(sPayload(N, first));
                st = 1;
            } else {
                if (sp + 1 >= SPLINE_STACK) { bail(BAIL_RANGE); return 0.0; }
                sp++;
                sN[sp] = int(sPayload(N, first));
                sState[sp] = 0;
                continue;
            }
        }
        if (st == 1) {
            int iv = sIv[sp];
            if (iv < 0 || iv == n - 1) {
                int at = iv < 0 ? 0 : n - 1;
                precise float d = sDer(N, at);
                result = d == 0.0 ? sV0[sp] : sV0[sp] + d * (c - sLoc(N, at));
                ready = true;
            } else {
                sState[sp] = 2;
                if (sKind(N, iv + 1) == 0u) {
                    sV1[sp] = uintBitsToFloat(sPayload(N, iv + 1));
                    st = 2;
                } else {
                    if (sp + 1 >= SPLINE_STACK) { bail(BAIL_RANGE); return 0.0; }
                    sp++;
                    sN[sp] = int(sPayload(N, iv + 1));
                    sState[sp] = 0;
                    continue;
                }
            }
        }
        if (!ready) {
            int iv = sIv[sp];
            precise float l0 = sLoc(N, iv);
            precise float l1 = sLoc(N, iv + 1);
            precise float span = l1 - l0;
            precise float t = jdivf(c - l0, span);
            precise float v0 = sV0[sp];
            precise float v1 = sV1[sp];
            precise float delta = v1 - v0;
            precise float a = sDer(N, iv) * span - delta;
            precise float b = -sDer(N, iv + 1) * span + delta;
            result = lerpf(t, v0, v1) + t * (1.0 - t) * lerpf(t, a, b);
        }
        sp--;
        if (sp < 0) return result;
        if (sState[sp] == 1) sV0[sp] = result; else sV1[sp] = result;
    }
    bail(BAIL_RANGE);
    return 0.0;
}
