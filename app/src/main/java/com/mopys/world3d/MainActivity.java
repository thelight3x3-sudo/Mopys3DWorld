
package com.mopys.world3d;

import android.app.Activity;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.AssetManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;

public class MainActivity extends Activity {
    private GameView gameView;
    private ControlsOverlay overlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        gameView = new GameView(this);
        overlay = new ControlsOverlay(this, gameView);

        FrameLayout root = new FrameLayout(this);
        root.addView(gameView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(overlay, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TouchRouter touchRouter = new TouchRouter(gameView, overlay);
        gameView.setOnTouchListener(touchRouter);
        overlay.setOnTouchListener(touchRouter);
        overlay.setClickable(true);

        setContentView(root);
        hideSystemBars();
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override protected void onResume() { super.onResume(); gameView.onResume(); hideSystemBars(); }
    @Override protected void onPause() { gameView.onPause(); super.onPause(); }

    private static final class TouchRouter implements View.OnTouchListener {
        private final GameView gameView;
        private final ControlsOverlay overlay;

        TouchRouter(GameView gameView, ControlsOverlay overlay) {
            this.gameView = gameView;
            this.overlay = overlay;
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            gameView.handleTouch(event);
            overlay.invalidate();
            return true;
        }
    }

    public static class GameView extends GLSurfaceView {
        private final WorldRenderer renderer;
        public volatile boolean leftActive = false;
        public volatile float leftBaseX = 0, leftBaseY = 0, leftX = 0, leftY = 0;
        public volatile boolean jumpFlash = false;
        public volatile boolean attackFlash = false;
        private int leftPointer = -1;
        private int cameraPointer = -1;
        private float lastCamX = 0, lastCamY = 0;
        private final float joystickRadius;

        public GameView(Context context) {
            super(context);
            joystickRadius = 115.0f * getResources().getDisplayMetrics().density;
            setEGLContextClientVersion(2);
            renderer = new WorldRenderer(context.getAssets());
            setRenderer(renderer);
            setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
            setFocusable(true);
        }

        public WorldRenderer getRenderer3D() { return renderer; }

        private static final class JumpFlashReset implements Runnable {
            private final GameView gameView;

            JumpFlashReset(GameView gameView) {
                this.gameView = gameView;
            }

            @Override
            public void run() {
                gameView.jumpFlash = false;
            }
        }

        private static final class AttackFlashReset implements Runnable {
            private final GameView gameView;

            AttackFlashReset(GameView gameView) {
                this.gameView = gameView;
            }

            @Override
            public void run() {
                gameView.attackFlash = false;
            }
        }

        public boolean handleTouch(MotionEvent event) {
            final int action = event.getActionMasked();
            final int index = event.getActionIndex();
            final int width = Math.max(1, getWidth());
            final int height = Math.max(1, getHeight());

            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                int id = event.getPointerId(index);
                float x = event.getX(index);
                float y = event.getY(index);
                if (isAttackArea(x, y, width, height)) {
                    if (renderer.requestAttack()) {
                        attackFlash = true;
                        postDelayed(new AttackFlashReset(this), 160);
                    }
                    return true;
                }
                if (isJumpArea(x, y, width, height)) {
                    renderer.requestJump();
                    jumpFlash = true;
                    postDelayed(new JumpFlashReset(this), 120);
                    return true;
                }
                if (x < width * 0.48f && leftPointer == -1) {
                    leftPointer = id;
                    leftActive = true;
                    leftBaseX = x;
                    leftBaseY = y;
                    leftX = x;
                    leftY = y;
                    updateJoystick(x, y);
                } else if (cameraPointer == -1) {
                    cameraPointer = id;
                    lastCamX = x;
                    lastCamY = y;
                }
            } else if (action == MotionEvent.ACTION_MOVE) {
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int id = event.getPointerId(i);
                    float x = event.getX(i);
                    float y = event.getY(i);
                    if (id == leftPointer) {
                        updateJoystick(x, y);
                    } else if (id == cameraPointer) {
                        float dx = x - lastCamX;
                        float dy = y - lastCamY;
                        lastCamX = x;
                        lastCamY = y;
                        renderer.addCameraDelta(dx, dy);
                    }
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
                int id = event.getPointerId(index);
                if (id == leftPointer) {
                    leftPointer = -1;
                    leftActive = false;
                    renderer.setJoystick(0f, 0f);
                }
                if (id == cameraPointer) {
                    cameraPointer = -1;
                }
                if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
                    leftPointer = -1;
                    cameraPointer = -1;
                    leftActive = false;
                    renderer.setJoystick(0f, 0f);
                }
            }
            return true;
        }

        private boolean isAttackArea(float x, float y, int width, int height) {
            float cx = width * 0.73f;
            float cy = height * 0.72f;
            float r = Math.min(width, height) * 0.115f;
            float dx = x - cx;
            float dy = y - cy;
            return dx * dx + dy * dy < r * r;
        }

        private boolean isJumpArea(float x, float y, int width, int height) {
            float cx = width * 0.89f;
            float cy = height * 0.72f;
            float r = Math.min(width, height) * 0.115f;
            float dx = x - cx;
            float dy = y - cy;
            return dx * dx + dy * dy < r * r;
        }

        private void updateJoystick(float x, float y) {
            float dx = x - leftBaseX;
            float dy = y - leftBaseY;
            float len = (float)Math.sqrt(dx * dx + dy * dy);
            if (len > joystickRadius) {
                dx = dx / len * joystickRadius;
                dy = dy / len * joystickRadius;
            }
            leftX = leftBaseX + dx;
            leftY = leftBaseY + dy;
            renderer.setJoystick(dx / joystickRadius, dy / joystickRadius);
        }
    }

    public static class ControlsOverlay extends View {
        private final GameView game;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long lastInvalidate = 0L;

        public ControlsOverlay(Context context, GameView gameView) {
            super(context);
            this.game = gameView;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            float density = getResources().getDisplayMetrics().density;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(90, 20, 230, 140));
            float jcX = game.leftActive ? game.leftBaseX : w * 0.14f;
            float jcY = game.leftActive ? game.leftBaseY : h * 0.70f;
            float r = 82f * density;
            canvas.drawCircle(jcX, jcY, r, paint);
            paint.setColor(Color.argb(160, 235, 255, 245));
            float knobX = game.leftActive ? game.leftX : jcX;
            float knobY = game.leftActive ? game.leftY : jcY;
            canvas.drawCircle(knobX, knobY, 33f * density, paint);

            float buttonR = Math.min(w, h) * 0.088f;
            float attackX = w * 0.73f;
            float attackY = h * 0.72f;
            WorldRenderer rr = game.getRenderer3D();
            float atkCd = rr.getAttackCooldownFraction();
            boolean atkReady = atkCd <= 0.001f;
            paint.setColor(game.attackFlash ? Color.argb(230, 255, 245, 210) : (atkReady ? Color.argb(150, 255, 110, 55) : Color.argb(105, 80, 80, 90)));
            canvas.drawCircle(attackX, attackY, buttonR, paint);
            if (!atkReady) {
                paint.setColor(Color.argb(145, 0, 0, 0));
                canvas.drawArc(attackX - buttonR, attackY - buttonR, attackX + buttonR, attackY + buttonR, -90f, 360f * atkCd, true, paint);
            }
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(18f * density);
            canvas.drawText(atkReady ? "ATK" : String.format(Locale.US, "%.1f", rr.getAttackCooldownRemaining()), attackX, attackY + 7f * density, paint);

            float jumpR = Math.min(w, h) * 0.088f;
            float jumpX = w * 0.89f;
            float jumpY = h * 0.72f;
            paint.setColor(game.jumpFlash ? Color.argb(210, 255, 255, 255) : Color.argb(120, 80, 170, 255));
            canvas.drawCircle(jumpX, jumpY, jumpR, paint);
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(18f * density);
            canvas.drawText("JUMP", jumpX, jumpY + 7f * density, paint);

            paint.setTextSize(13f * density);
            paint.setColor(Color.argb(210, 255, 255, 255));
            canvas.drawText("left: move", jcX, jcY + r + 28f * density, paint);
            canvas.drawText("right drag: camera   optimized sun/moon lighting ON", w * 0.55f, 36f * density, paint);

            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTextSize(12f * density);
            paint.setColor(Color.argb(200, 255, 255, 255));
            canvas.drawText(String.format(Locale.US, "terrain y %.2f   player %.1f %.1f", rr.getPlayerY(), rr.getPlayerX(), rr.getPlayerZ()), 16f*density, 26f*density, paint);

            long now = SystemClock.uptimeMillis();
            if (now - lastInvalidate > 33) {
                lastInvalidate = now;
                postInvalidateDelayed(33);
            }
        }
    }

    public static class WorldRenderer implements GLSurfaceView.Renderer {
        private final AssetManager assets;
        private int program = 0;
        private Mesh playerMesh;
        private Mesh terrainMesh;
        private Mesh sunMesh;
        private Mesh sunRayMesh;
        private Mesh moonMesh;
        private Mesh moonRayMesh;
        private Mesh footprintMesh;
        private Mesh bootMesh;
        private Mesh punchArcMesh;
        private Mesh impactBurstMesh;
        private Mesh attackArmMesh;
        private Mesh attackFistMesh;
        private Mesh lakeMesh;
        private Mesh mountainMesh;
        private Mesh treeTrunkMesh;
        private Mesh treeCrownMesh;
        private Mesh grassPatchMesh;
        private final float[] treeX = new float[TREE_COUNT];
        private final float[] treeZ = new float[TREE_COUNT];
        private final float[] treeScale = new float[TREE_COUNT];
        private final float[] grassX = new float[GRASS_PATCH_COUNT];
        private final float[] grassZ = new float[GRASS_PATCH_COUNT];
        private final float[] grassScale = new float[GRASS_PATCH_COUNT];
        private int aPos, aNormal, aColor, uModel, uView, uProj, uLightDir, uSkyColor, uCamera, uRenderMode, uAlpha, uAnimMode, uAnimT;
        private int uLightPower, uAmbientPower, uBrightness, uNightAmount, uRayShadowStrength;
        private final float[] projection = new float[16];
        private final float[] view = new float[16];
        private final float[] model = new float[16];
        private final float[] playerModel = new float[16];
        private final float[] shadowProject = new float[16];
        private final float[] shadowCombined = new float[16];

        // FIX16 real time-of-day state. Updated every frame, not hardcoded.
        private float worldClockSeconds = 0.0f;
        private float skyR = 0.42f, skyG = 0.68f, skyB = 1.00f;
        private float lightDirX = -0.42f, lightDirY = -0.88f, lightDirZ = -0.22f;
        private float sunWorldX = 0f, sunWorldY = 30f, sunWorldZ = 30f;
        private float moonWorldX = 0f, moonWorldY = -20f, moonWorldZ = -30f;
        private float sunVisible = 1.0f, moonVisible = 0.0f;
        private float sunPower = 1.0f, moonPower = 0.0f;
        private float lightPower = 1.0f, ambientPower = 0.32f, brightness = 1.0f, nightAmount = 0.0f;
        private float shadowPower = 0.40f, rayPower = 0.42f, moonRayPower = 0.0f; // FIX18: optimized practical lighting, no expensive per-fragment ray marching.

        private volatile float joyX = 0f, joyY = 0f;
        private volatile boolean jumpQueued = false;
        private volatile boolean attackQueued = false;
        private float attackTimer = 0f;
        private float attackCooldownTimer = 0f;
        private static final float ATTACK_DURATION = 0.62f;
        private static final float ATTACK_COOLDOWN = 1.05f;
        private float playerX = 0f, playerY = 0f, playerZ = 0f;
        private float verticalVelocity = 0f;
        private boolean onGround = true;
        private float playerYaw = 0f;
        private float walkPhase = 0f;
        private boolean walkingNow = false;
        private float stepAccumulator = 0f;
        private int nextFootSide = -1;
        private static final int MAX_FOOTPRINTS = 44;
        private final float[] fpX = new float[MAX_FOOTPRINTS];
        private final float[] fpY = new float[MAX_FOOTPRINTS];
        private final float[] fpZ = new float[MAX_FOOTPRINTS];
        private final float[] fpYaw = new float[MAX_FOOTPRINTS];
        private final int[] fpSide = new int[MAX_FOOTPRINTS];
        private int fpCount = 0;
        private int fpNext = 0;
        private float cameraYaw = (float)Math.toRadians(42);
        private float cameraPitch = (float)Math.toRadians(24);
        private long lastTime = 0L;
        private int viewportW = 1, viewportH = 1;
        private static final float DAY_NIGHT_HALF_SECONDS = 600.0f; // 10 minutes sun-down / moon-up phase.
        private static final float DAY_NIGHT_FULL_SECONDS = 1200.0f; // 20 minute full repeating cycle.
        private static final float WORLD_LIMIT = 155.0f;
        private static final int TREE_COUNT = 120;
        private static final int GRASS_PATCH_COUNT = 220;
        private static final int MOUNTAIN_COUNT = 18;
        // x, z, radiusX, radiusZ. Water height is computed from the local terrain basin.
        private static final float[][] LAKES = new float[][] {
                {-46.0f, -34.0f, 16.0f, 9.0f},
                { 48.0f,  42.0f, 20.0f, 12.0f},
                { 98.0f, -68.0f, 28.0f, 15.0f},
                {-118.0f, 78.0f, 24.0f, 13.0f}
        };

        public WorldRenderer(AssetManager assets) {
            this.assets = assets;
        }

        public void setJoystick(float x, float y) { joyX = clamp(x, -1f, 1f); joyY = clamp(y, -1f, 1f); }
        public void requestJump() { jumpQueued = true; }
        public boolean requestAttack() {
            if (attackCooldownTimer <= 0.0f && attackTimer <= 0.0f && !attackQueued) {
                attackQueued = true;
                return true;
            }
            return false;
        }
        public float getAttackCooldownFraction() {
            return clamp(attackCooldownTimer / ATTACK_COOLDOWN, 0.0f, 1.0f);
        }
        public float getAttackCooldownRemaining() {
            return Math.max(0.0f, attackCooldownTimer);
        }
        public void addCameraDelta(float dx, float dy) {
            cameraYaw += dx * 0.006f;
            cameraPitch += dy * 0.004f;
            cameraPitch = clamp(cameraPitch, (float)Math.toRadians(8), (float)Math.toRadians(65));
        }
        public float getPlayerX() { return playerX; }
        public float getPlayerY() { return playerY; }
        public float getPlayerZ() { return playerZ; }

        @Override public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl, javax.microedition.khronos.egl.EGLConfig config) {
            GLES20.glClearColor(skyR, skyG, skyB, 1f);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glDisable(GLES20.GL_CULL_FACE);
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            GLES20.glUseProgram(program);
            aPos = GLES20.glGetAttribLocation(program, "aPos");
            aNormal = GLES20.glGetAttribLocation(program, "aNormal");
            aColor = GLES20.glGetAttribLocation(program, "aColor");
            uModel = GLES20.glGetUniformLocation(program, "uModel");
            uView = GLES20.glGetUniformLocation(program, "uView");
            uProj = GLES20.glGetUniformLocation(program, "uProj");
            uLightDir = GLES20.glGetUniformLocation(program, "uLightDir");
            uSkyColor = GLES20.glGetUniformLocation(program, "uSkyColor");
            uCamera = GLES20.glGetUniformLocation(program, "uCamera");
            uRenderMode = GLES20.glGetUniformLocation(program, "uRenderMode");
            uAlpha = GLES20.glGetUniformLocation(program, "uAlpha");
            uAnimMode = GLES20.glGetUniformLocation(program, "uAnimMode");
            uAnimT = GLES20.glGetUniformLocation(program, "uAnimT");
            uLightPower = GLES20.glGetUniformLocation(program, "uLightPower");
            uAmbientPower = GLES20.glGetUniformLocation(program, "uAmbientPower");
            uBrightness = GLES20.glGetUniformLocation(program, "uBrightness");
            uNightAmount = GLES20.glGetUniformLocation(program, "uNightAmount");
            uRayShadowStrength = GLES20.glGetUniformLocation(program, "uRayShadowStrength");
            playerMesh = Mesh.loadFromAsset(assets, "model_chibi_avatar_mesh.bin");
            terrainMesh = TerrainFactory.makeTerrain(176, 1.8f);
            lakeMesh = NatureFactory.makeLakeDisk(72);
            mountainMesh = NatureFactory.makeCone(36, 0.38f, 0.33f, 0.27f);
            treeTrunkMesh = NatureFactory.makeCylinder(12, 0.43f, 0.25f, 0.12f);
            treeCrownMesh = NatureFactory.makeCone(18, 0.09f, 0.36f, 0.12f);
            grassPatchMesh = NatureFactory.makeGrassCluster(7);
            initNatureObjects();
            sunMesh = SunFactory.makeSunDisk(64, 2.2f);
            sunRayMesh = SunFactory.makeSunRays(24, 2.7f, 7.6f);
            moonMesh = SunFactory.makeMoonDisk(64, 1.55f);
            moonRayMesh = SunFactory.makeMoonRays(20, 2.0f, 5.7f);
            footprintMesh = FlatFactory.makeEllipse(18, 0.16f, 0.34f, 0.035f, 0.022f, 0.010f);
            bootMesh = FlatFactory.makeEllipse(20, 0.19f, 0.34f, 0.025f, 0.020f, 0.016f);
            punchArcMesh = AttackFactory.makePunchArc(36, 0.25f, 1.45f);
            impactBurstMesh = SunFactory.makeSunRays(18, 0.12f, 0.78f);
            // FIX14: clean rigid attack parts. The new uploaded volumetric OBJ is NOT warped.
            attackArmMesh = AttackFactory.makeBox(0.24f, 0.22f, 0.92f, 0.93f, 0.68f, 0.47f);
            attackFistMesh = AttackFactory.makeBox(0.46f, 0.40f, 0.46f, 0.96f, 0.70f, 0.50f);
            playerY = terrainHeight(playerX, playerZ);
            lastTime = System.nanoTime();
        }

        @Override public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl, int width, int height) {
            viewportW = Math.max(1, width);
            viewportH = Math.max(1, height);
            GLES20.glViewport(0, 0, viewportW, viewportH);
            float ratio = viewportW / (float)viewportH;
            Matrix.perspectiveM(projection, 0, 60f, ratio, 0.1f, 520f);
        }

        @Override public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl) {
            long now = System.nanoTime();
            float dt = (now - lastTime) / 1000000000.0f;
            lastTime = now;
            if (dt < 0f || dt > 0.05f) dt = 0.016f;
            updateWorld(dt);
            drawWorld();
        }

        private void updateWorld(float dt) {
            worldClockSeconds += dt;
            if (worldClockSeconds >= DAY_NIGHT_FULL_SECONDS) worldClockSeconds -= DAY_NIGHT_FULL_SECONDS;
            updateDayNightLighting();

            if (attackCooldownTimer > 0f) {
                attackCooldownTimer -= dt;
                if (attackCooldownTimer < 0f) attackCooldownTimer = 0f;
            }
            if (attackQueued && attackCooldownTimer <= 0.0f && attackTimer <= 0.0f) {
                attackTimer = ATTACK_DURATION;
                attackCooldownTimer = ATTACK_COOLDOWN;
                attackQueued = false;
            } else if (attackQueued && attackCooldownTimer > 0.0f) {
                attackQueued = false;
            }
            if (attackTimer > 0f) {
                attackTimer -= dt;
                if (attackTimer < 0f) attackTimer = 0f;
            }

            float forwardAmount = -joyY;
            float strafeAmount = joyX;
            float mag = (float)Math.sqrt(forwardAmount * forwardAmount + strafeAmount * strafeAmount);
            if (mag > 1f) { forwardAmount /= mag; strafeAmount /= mag; }

            float camSin = (float)Math.sin(cameraYaw);
            float camCos = (float)Math.cos(cameraYaw);
            float forwardX = camSin;
            float forwardZ = camCos;
            float rightX = -camCos;
            float rightZ = camSin;
            float moveX = forwardX * forwardAmount + rightX * strafeAmount;
            float moveZ = forwardZ * forwardAmount + rightZ * strafeAmount;
            float moveLen = (float)Math.sqrt(moveX * moveX + moveZ * moveZ);
            walkingNow = false;
            if (moveLen > 0.04f) {
                moveX /= moveLen;
                moveZ /= moveLen;
                float speed = onGround ? 4.0f : 3.0f;
                float oldX = playerX;
                float oldZ = playerZ;
                playerX += moveX * speed * dt;
                playerZ += moveZ * speed * dt;
                playerX = clamp(playerX, -WORLD_LIMIT, WORLD_LIMIT);
                playerZ = clamp(playerZ, -WORLD_LIMIT, WORLD_LIMIT);
                float movedX = playerX - oldX;
                float movedZ = playerZ - oldZ;
                float movedDistance = (float)Math.sqrt(movedX * movedX + movedZ * movedZ);
                playerYaw = (float)Math.atan2(moveX, moveZ);
                walkingNow = onGround;
                if (onGround) {
                    walkPhase += dt * 9.0f * (0.65f + Math.min(1.0f, movedDistance * 10.0f));
                    stepAccumulator += movedDistance;
                    if (stepAccumulator > 0.42f) {
                        stepAccumulator = 0f;
                        addFootprint(nextFootSide);
                        nextFootSide = -nextFootSide;
                    }
                }
            } else {
                // Slow the body sway when standing, but do not snap it. It makes idle feel less robotic.
                walkPhase += dt * 1.2f;
                stepAccumulator = 0f;
            }

            float groundY = terrainHeight(playerX, playerZ);
            if (jumpQueued && onGround) {
                verticalVelocity = 6.0f;
                onGround = false;
            }
            jumpQueued = false;
            if (!onGround) {
                verticalVelocity -= 13.0f * dt;
                playerY += verticalVelocity * dt;
                if (playerY <= groundY) {
                    playerY = groundY;
                    verticalVelocity = 0f;
                    onGround = true;
                    addFootprint(-1);
                    addFootprint(1);
                }
            } else {
                // Stick to hills and valleys so the player changes height with the terrain.
                playerY = groundY;
            }
        }

        private void addFootprint(int side) {
            float yaw = playerYaw;
            float sx = (float)Math.sin(yaw);
            float sz = (float)Math.cos(yaw);
            float rx = (float)Math.cos(yaw);
            float rz = -(float)Math.sin(yaw);
            float sideOffset = side * 0.27f;
            float backOffset = -0.18f;
            float x = playerX + rx * sideOffset + sx * backOffset;
            float z = playerZ + rz * sideOffset + sz * backOffset;
            x = clamp(x, -WORLD_LIMIT, WORLD_LIMIT);
            z = clamp(z, -WORLD_LIMIT, WORLD_LIMIT);
            fpX[fpNext] = x;
            fpZ[fpNext] = z;
            fpY[fpNext] = terrainHeight(x, z) + 0.035f;
            fpYaw[fpNext] = yaw;
            fpSide[fpNext] = side;
            fpNext = (fpNext + 1) % MAX_FOOTPRINTS;
            if (fpCount < MAX_FOOTPRINTS) fpCount++;
        }

        private void drawWorld() {
            GLES20.glClearColor(skyR, skyG, skyB, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            GLES20.glUseProgram(program);
            // FIX16: dynamic sun/moon light direction and brightness. This changes every frame.
            GLES20.glUniform3f(uLightDir, lightDirX, lightDirY, lightDirZ);
            GLES20.glUniform3f(uSkyColor, skyR, skyG, skyB);
            GLES20.glUniform1f(uLightPower, lightPower);
            GLES20.glUniform1f(uAmbientPower, ambientPower);
            GLES20.glUniform1f(uBrightness, brightness);
            GLES20.glUniform1f(uNightAmount, nightAmount);
            GLES20.glUniform1f(uRayShadowStrength, 0.0f); // FIX18: disable expensive ray-marched terrain shadow for practical mobile FPS.
            GLES20.glUniformMatrix4fv(uProj, 1, false, projection, 0);

            float distance = 9.5f;
            float targetY = playerY + 1.35f;
            float camAttackProgress = attackTimer > 0f ? 1.0f - (attackTimer / ATTACK_DURATION) : 0f;
            float camImpact = attackImpactCurve(camAttackProgress);
            float shakeSide = (float)Math.sin(camAttackProgress * 88.0f) * camImpact * 0.16f;
            float shakeUp = (float)Math.sin(camAttackProgress * 131.0f) * camImpact * 0.08f;
            float cosPitch = (float)Math.cos(cameraPitch);
            float eyeX = playerX - (float)Math.sin(cameraYaw) * cosPitch * distance;
            float eyeZ = playerZ - (float)Math.cos(cameraYaw) * cosPitch * distance;
            float eyeY = targetY + (float)Math.sin(cameraPitch) * distance;
            eyeX += (float)Math.cos(cameraYaw) * shakeSide;
            eyeZ += -(float)Math.sin(cameraYaw) * shakeSide;
            eyeY += shakeUp;
            GLES20.glUniform3f(uCamera, eyeX, eyeY, eyeZ);
            Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ, playerX, targetY, playerZ, 0f, 1f, 0f);
            GLES20.glUniformMatrix4fv(uView, 1, false, view, 0);

            drawSunMoonBodies();

            setRenderMode(0, 1.0f);
            setAnim(0, 0f);
            drawDistantMountains();

            Matrix.setIdentityM(model, 0);
            GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
            terrainMesh.draw(aPos, aNormal, aColor);

            drawLakes();
            drawGrassPatches();
            drawTrees();
            drawFootprints();

            float attackProgress = attackTimer > 0f ? 1.0f - (attackTimer / ATTACK_DURATION) : 0f;
            float attackPose = attackTimer > 0f ? attackThrustCurve(attackProgress) : 0f;
            float attackWind = attackTimer > 0f ? attackWindupCurve(attackProgress) : 0f;
            float attackImpact = attackTimer > 0f ? attackImpactCurve(attackProgress) : 0f;
            float groundNow = terrainHeight(playerX, playerZ);
            float jumpPose = onGround ? 0f : clamp((playerY - groundNow) * 0.23f + 0.35f, 0f, 1f);
            int playerAnimMode = attackTimer > 0.0f ? 1 : (jumpPose > 0.01f ? 2 : 0);
            float playerAnimT = attackTimer > 0.0f ? attackProgress : jumpPose;

            float bob = walkingNow ? Math.abs((float)Math.sin(walkPhase)) * 0.12f : 0.02f * (float)Math.sin(walkPhase * 0.7f);
            float sideTilt = walkingNow ? (float)Math.sin(walkPhase) * 4.0f : 0.0f;
            float forwardLean = walkingNow ? -3.0f : 0.0f;
            float attackLunge = attackPose * 0.34f - attackWind * 0.08f;
            if (attackTimer > 0.0f) {
                // FIX13: clean rigid full-body attack motion, no vertex stretching.
                forwardLean += attackWind * 3.5f;
                forwardLean -= attackPose * 7.5f;
                sideTilt += attackPose * 2.0f - attackWind * 1.0f;
                bob += attackImpact * 0.035f;
            }
            if (jumpPose > 0.01f) forwardLean += jumpPose * 5.5f;
            Matrix.setIdentityM(playerModel, 0);
            Matrix.translateM(playerModel, 0,
                    playerX + (float)Math.sin(playerYaw) * attackLunge,
                    playerY + 0.04f + bob,
                    playerZ + (float)Math.cos(playerYaw) * attackLunge);
            Matrix.rotateM(playerModel, 0, (float)Math.toDegrees(playerYaw), 0f, 1f, 0f);
            Matrix.rotateM(playerModel, 0, forwardLean, 1f, 0f, 0f);
            Matrix.rotateM(playerModel, 0, sideTilt, 0f, 0f, 1f);

            setAnim(0, 0f);
            drawPlayerSunShadow();
            drawAnimatedBoots();

            // FIX13: draw the original OBJ unchanged. No shader distortion. No ugly mesh stretch.
            setRenderMode(0, 1.0f);
            setAnim(0, 0f);
            GLES20.glUniformMatrix4fv(uModel, 1, false, playerModel, 0);
            playerMesh.draw(aPos, aNormal, aColor);

            // Punch uses clean separate rigid arm/fist meshes and effects.
            if (attackTimer > 0.0f) {
                drawRigidAttackArm(attackProgress);
                drawPunchEffect(attackProgress);
            }
            setAnim(0, 0f);
        }


        private void updateDayNightLighting() {
            float phase = (worldClockSeconds % DAY_NIGHT_FULL_SECONDS) / DAY_NIGHT_FULL_SECONDS;
            float angle = phase * 6.2831853f;
            // Starts with sun high. During the first 10 minutes sun goes down while moon rises.
            float sunElev = (float)Math.cos(angle);
            float moonElev = -sunElev;
            float sunHoriz = (float)Math.sin(angle);
            float moonHoriz = -sunHoriz;

            sunVisible = smoothStep(-0.18f, 0.08f, sunElev);
            moonVisible = smoothStep(-0.16f, 0.10f, moonElev);
            float sunHigh = clamp(sunElev, 0.0f, 1.0f);
            float moonHigh = clamp(moonElev, 0.0f, 1.0f);
            float sunsetGlow = 1.0f - clamp(Math.abs(sunElev) / 0.30f, 0.0f, 1.0f);
            sunsetGlow *= (sunVisible > 0.02f || moonVisible > 0.02f) ? 1.0f : 0.0f;

            sunPower = sunVisible * (0.12f + 0.88f * sunHigh);
            moonPower = moonVisible * (0.08f + 0.26f * moonHigh);
            float dayAmount = smoothStep(-0.08f, 0.35f, sunElev);
            nightAmount = 1.0f - dayAmount;
            lightPower = Math.max(sunPower, moonPower);
            ambientPower = 0.075f + dayAmount * 0.24f + moonPower * 0.18f + sunsetGlow * 0.09f;
            brightness = clamp(0.19f + dayAmount * 0.92f + moonPower * 0.22f + sunsetGlow * 0.16f, 0.16f, 1.18f);
            shadowPower = sunPower * (0.34f + 0.28f * (1.0f - sunHigh)) + moonPower * 0.24f;
            rayPower = sunVisible * (0.16f + sunPower * 0.44f + sunsetGlow * 0.40f);
            moonRayPower = moonVisible * (0.08f + moonPower * 0.55f);

            // Smooth sky color: day blue, orange near horizon, deep blue at night.
            skyR = clamp(0.035f * nightAmount + 0.42f * dayAmount + 0.55f * sunsetGlow, 0.02f, 0.95f);
            skyG = clamp(0.060f * nightAmount + 0.68f * dayAmount + 0.20f * sunsetGlow, 0.03f, 0.82f);
            skyB = clamp(0.160f * nightAmount + 1.00f * dayAmount + 0.05f * sunsetGlow + moonPower * 0.12f, 0.11f, 1.00f);

            sunWorldX = playerX + sunHoriz * 70.0f;
            sunWorldY = playerY + 7.5f + sunElev * 42.0f;
            sunWorldZ = playerZ + 34.0f;
            moonWorldX = playerX + moonHoriz * 70.0f;
            moonWorldY = playerY + 7.5f + moonElev * 42.0f;
            moonWorldZ = playerZ - 34.0f;

            float lx, ly, lz;
            if (sunPower >= moonPower) {
                lx = playerX - sunWorldX;
                ly = (playerY + 0.8f) - sunWorldY;
                lz = playerZ - sunWorldZ;
            } else {
                lx = playerX - moonWorldX;
                ly = (playerY + 0.8f) - moonWorldY;
                lz = playerZ - moonWorldZ;
            }
            float len = (float)Math.sqrt(lx * lx + ly * ly + lz * lz);
            if (len < 0.0001f) len = 1.0f;
            lightDirX = lx / len;
            lightDirY = ly / len;
            lightDirZ = lz / len;
        }

        private void drawSunMoonBodies() {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            setAnim(0, 0f);

            if (sunVisible > 0.01f) {
                setRenderMode(2, clamp(rayPower, 0.03f, 0.86f));
                Matrix.setIdentityM(model, 0);
                Matrix.translateM(model, 0, sunWorldX, sunWorldY, sunWorldZ);
                Matrix.rotateM(model, 0, (float)Math.toDegrees(cameraYaw), 0f, 1f, 0f);
                Matrix.scaleM(model, 0, 1.0f + rayPower * 0.35f, 1.0f + rayPower * 0.35f, 1.0f);
                GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
                sunRayMesh.draw(aPos, aNormal, aColor);

                setRenderMode(2, clamp(0.60f + sunPower * 0.58f, 0.25f, 1.0f));
                Matrix.setIdentityM(model, 0);
                Matrix.translateM(model, 0, sunWorldX, sunWorldY, sunWorldZ);
                Matrix.rotateM(model, 0, (float)Math.toDegrees(cameraYaw), 0f, 1f, 0f);
                Matrix.scaleM(model, 0, 1.0f + sunPower * 0.12f, 1.0f + sunPower * 0.12f, 1.0f);
                GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
                sunMesh.draw(aPos, aNormal, aColor);
            }

            if (moonVisible > 0.01f) {
                setRenderMode(2, clamp(moonRayPower, 0.03f, 0.34f));
                Matrix.setIdentityM(model, 0);
                Matrix.translateM(model, 0, moonWorldX, moonWorldY, moonWorldZ);
                Matrix.rotateM(model, 0, (float)Math.toDegrees(cameraYaw), 0f, 1f, 0f);
                Matrix.scaleM(model, 0, 1.0f + moonRayPower * 0.50f, 1.0f + moonRayPower * 0.50f, 1.0f);
                GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
                moonRayMesh.draw(aPos, aNormal, aColor);

                setRenderMode(2, clamp(0.40f + moonPower * 1.45f, 0.22f, 0.82f));
                Matrix.setIdentityM(model, 0);
                Matrix.translateM(model, 0, moonWorldX, moonWorldY, moonWorldZ);
                Matrix.rotateM(model, 0, (float)Math.toDegrees(cameraYaw), 0f, 1f, 0f);
                GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
                moonMesh.draw(aPos, aNormal, aColor);
            }

            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            setRenderMode(0, 1.0f);
        }

        private void drawPlayerSunShadow() {
            // FIX17: this is the mesh-projected character shadow from the current dominant celestial light.
            // FIX18: terrain/object darkness uses fast directional + projected shadow; ray marching removed for practical mobile FPS.
            if (shadowPower <= 0.025f) return;
            float[] n = terrainNormal(playerX, playerZ);
            float px = playerX + n[0] * 0.045f;
            float py = terrainHeight(playerX, playerZ) + n[1] * 0.045f;
            float pz = playerZ + n[2] * 0.045f;
            makeDirectionalShadowMatrix(shadowProject, n[0], n[1], n[2], px, py, pz, lightDirX, lightDirY, lightDirZ);
            Matrix.multiplyMM(shadowCombined, 0, shadowProject, 0, playerModel, 0);

            float air = Math.max(0.0f, playerY - terrainHeight(playerX, playerZ));
            float alpha = clamp(shadowPower * (0.52f - air * 0.055f), 0.035f, 0.52f);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glDepthMask(false);
            setRenderMode(1, alpha);
            GLES20.glUniformMatrix4fv(uModel, 1, false, shadowCombined, 0);
            playerMesh.draw(aPos, aNormal, aColor);
            GLES20.glDepthMask(true);
            GLES20.glDisable(GLES20.GL_BLEND);
            setRenderMode(0, 1.0f);
        }

        private void drawFootprints() {
            int start = (fpNext - fpCount + MAX_FOOTPRINTS) % MAX_FOOTPRINTS;
            for (int i = 0; i < fpCount; i++) {
                int idx = (start + i) % MAX_FOOTPRINTS;
                float ageScale = 0.72f + 0.28f * ((float)i / Math.max(1, fpCount));
                Matrix.setIdentityM(model, 0);
                Matrix.translateM(model, 0, fpX[idx], fpY[idx], fpZ[idx]);
                Matrix.rotateM(model, 0, (float)Math.toDegrees(fpYaw[idx]), 0f, 1f, 0f);
                Matrix.translateM(model, 0, fpSide[idx] * 0.02f, 0f, 0f);
                Matrix.scaleM(model, 0, ageScale, 1.0f, ageScale);
                GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
                footprintMesh.draw(aPos, aNormal, aColor);
            }
        }

        private void drawAnimatedBoots() {
            float yaw = playerYaw;
            float sx = (float)Math.sin(yaw);
            float sz = (float)Math.cos(yaw);
            float rx = (float)Math.cos(yaw);
            float rz = -(float)Math.sin(yaw);
            for (int side = -1; side <= 1; side += 2) {
                float phase = walkPhase + (side < 0 ? 0.0f : 3.14159f);
                float swing = walkingNow ? (float)Math.sin(phase) * 0.22f : 0f;
                float lift = walkingNow ? Math.max(0f, (float)Math.sin(phase)) * 0.045f : 0f;
                float x = playerX + rx * (side * 0.24f) + sx * swing;
                float z = playerZ + rz * (side * 0.24f) + sz * swing;
                float y = terrainHeight(x, z) + 0.055f + lift;
                Matrix.setIdentityM(model, 0);
                Matrix.translateM(model, 0, x, y, z);
                Matrix.rotateM(model, 0, (float)Math.toDegrees(yaw), 0f, 1f, 0f);
                GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
                bootMesh.draw(aPos, aNormal, aColor);
            }
        }

        private void drawRigidAttackArm(float progress) {
            float thrust = attackThrustCurve(progress);
            float wind = attackWindupCurve(progress);
            float impact = attackImpactCurve(progress);
            if (thrust < 0.02f && wind < 0.02f && impact < 0.02f) return;

            float yaw = playerYaw;
            float sx = (float)Math.sin(yaw);
            float sz = (float)Math.cos(yaw);
            float armCenter = 0.72f + thrust * 0.68f - wind * 0.18f;
            float fistCenter = 1.08f + thrust * 1.28f - wind * 0.24f + impact * 0.08f;
            float armY = playerY + 1.22f + thrust * 0.07f;
            float fistY = playerY + 1.26f + thrust * 0.10f;

            setRenderMode(0, 1.0f);
            setAnim(0, 0f);

            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, playerX + sx * armCenter, armY, playerZ + sz * armCenter);
            Matrix.rotateM(model, 0, (float)Math.toDegrees(yaw), 0f, 1f, 0f);
            Matrix.rotateM(model, 0, -6f - thrust * 10f + wind * 8f, 1f, 0f, 0f);
            Matrix.scaleM(model, 0, 1.0f, 1.0f, 0.85f + thrust * 0.55f);
            GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
            attackArmMesh.draw(aPos, aNormal, aColor);

            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, playerX + sx * fistCenter, fistY, playerZ + sz * fistCenter);
            Matrix.rotateM(model, 0, (float)Math.toDegrees(yaw), 0f, 1f, 0f);
            Matrix.rotateM(model, 0, -4f - thrust * 9f, 1f, 0f, 0f);
            Matrix.scaleM(model, 0, 1.0f + impact * 0.08f, 1.0f + impact * 0.08f, 1.0f + impact * 0.08f);
            GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
            attackFistMesh.draw(aPos, aNormal, aColor);
        }

        private void drawPunchEffect(float progress) {
            float thrust = attackThrustCurve(progress);
            float wind = attackWindupCurve(progress);
            float impact = attackImpactCurve(progress);
            if (thrust < 0.02f && wind < 0.02f && impact < 0.02f) return;

            float yaw = playerYaw;
            float sx = (float)Math.sin(yaw);
            float sz = (float)Math.cos(yaw);
            float extension = 0.62f + thrust * 1.28f - wind * 0.18f;
            float x = playerX + sx * extension;
            float y = playerY + 1.38f + thrust * 0.18f;
            float z = playerZ + sz * extension;

            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            setRenderMode(2, 0.20f + thrust * 0.46f + impact * 0.24f);
            setAnim(0, 0f);

            // Long slash/trail following the fist. It grows during the strike and fades on recoil.
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, x, y, z);
            Matrix.rotateM(model, 0, (float)Math.toDegrees(yaw), 0f, 1f, 0f);
            Matrix.rotateM(model, 0, -10f - thrust * 18f, 1f, 0f, 0f);
            Matrix.scaleM(model, 0, 0.70f + thrust * 1.05f, 0.55f + thrust * 0.80f, 0.90f);
            GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
            punchArcMesh.draw(aPos, aNormal, aColor);

            // Impact burst at the fist point. This makes the end of the punch read as a hit, not a soft wave.
            if (impact > 0.02f) {
                setRenderMode(2, 0.32f + impact * 0.52f);
                Matrix.setIdentityM(model, 0);
                Matrix.translateM(model, 0, playerX + sx * (2.05f + impact * 0.18f), playerY + 1.46f, playerZ + sz * (2.05f + impact * 0.18f));
                Matrix.rotateM(model, 0, (float)Math.toDegrees(yaw), 0f, 1f, 0f);
                Matrix.scaleM(model, 0, 0.45f + impact * 0.80f, 0.45f + impact * 0.80f, 0.45f + impact * 0.80f);
                GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
                impactBurstMesh.draw(aPos, aNormal, aColor);
            }

            GLES20.glDisable(GLES20.GL_BLEND);
            setRenderMode(0, 1.0f);
        }

        private static float attackWindupCurve(float t) {
            t = clamp(t, 0f, 1f);
            return smoothStep(0.00f, 0.12f, t) * (1.0f - smoothStep(0.18f, 0.34f, t));
        }

        private static float attackThrustCurve(float t) {
            t = clamp(t, 0f, 1f);
            return smoothStep(0.16f, 0.34f, t) * (1.0f - smoothStep(0.68f, 0.98f, t));
        }

        private static float attackImpactCurve(float t) {
            t = clamp(t, 0f, 1f);
            return smoothStep(0.30f, 0.39f, t) * (1.0f - smoothStep(0.42f, 0.62f, t));
        }

        private static float smoothStep(float e0, float e1, float x) {
            float t = clamp((x - e0) / Math.max(0.0001f, e1 - e0), 0f, 1f);
            return t * t * (3.0f - 2.0f * t);
        }


        private void setRenderMode(int mode, float alpha) {
            GLES20.glUniform1i(uRenderMode, mode);
            GLES20.glUniform1f(uAlpha, alpha);
        }

        private void setAnim(int mode, float t) {
            GLES20.glUniform1i(uAnimMode, mode);
            GLES20.glUniform1f(uAnimT, clamp(t, 0f, 1f));
        }

        private static float[] terrainNormal(float x, float z) {
            float e = 0.35f;
            float dhdx = (terrainHeight(x + e, z) - terrainHeight(x - e, z)) / (2.0f * e);
            float dhdz = (terrainHeight(x, z + e) - terrainHeight(x, z - e)) / (2.0f * e);
            float nx = -dhdx;
            float ny = 1.0f;
            float nz = -dhdz;
            float len = (float)Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 0.0001f) len = 1.0f;
            return new float[] { nx / len, ny / len, nz / len };
        }

        private static void makeDirectionalShadowMatrix(float[] out, float nx, float ny, float nz,
                                                        float px, float py, float pz,
                                                        float lx, float ly, float lz) {
            float llen = (float)Math.sqrt(lx * lx + ly * ly + lz * lz);
            if (llen < 0.0001f) llen = 1.0f;
            lx /= llen; ly /= llen; lz /= llen;
            float d = -(nx * px + ny * py + nz * pz);
            float k = nx * lx + ny * ly + nz * lz;
            if (Math.abs(k) < 0.05f) k = (k < 0f ? -0.05f : 0.05f);

            // Column-major matrix. For any world point p: p' = p - L * ((N dot p + d) / (N dot L)).
            out[0]  = 1f - lx * nx / k; out[4]  =    - lx * ny / k; out[8]  =    - lx * nz / k; out[12] =    - lx * d / k;
            out[1]  =    - ly * nx / k; out[5]  = 1f - ly * ny / k; out[9]  =    - ly * nz / k; out[13] =    - ly * d / k;
            out[2]  =    - lz * nx / k; out[6]  =    - lz * ny / k; out[10] = 1f - lz * nz / k; out[14] =    - lz * d / k;
            out[3]  = 0f;                 out[7]  = 0f;                 out[11] = 0f;                 out[15] = 1f;
        }

        private void initNatureObjects() {
            for (int i = 0; i < TREE_COUNT; i++) {
                float x = (hash01(i, 1.7f) * 2.0f - 1.0f) * (WORLD_LIMIT * 0.93f);
                float z = (hash01(i, 9.2f) * 2.0f - 1.0f) * (WORLD_LIMIT * 0.93f);
                // Keep trees mostly away from deep water and the initial spawn area.
                if (isInsideLake(x, z, 1.22f) || (Math.abs(x) < 8.0f && Math.abs(z) < 8.0f)) {
                    x += 18.0f + 35.0f * hash01(i, 14.1f);
                    z -= 14.0f + 31.0f * hash01(i, 3.4f);
                    x = clamp(x, -WORLD_LIMIT * 0.92f, WORLD_LIMIT * 0.92f);
                    z = clamp(z, -WORLD_LIMIT * 0.92f, WORLD_LIMIT * 0.92f);
                }
                treeX[i] = x;
                treeZ[i] = z;
                treeScale[i] = 0.65f + hash01(i, 5.9f) * 0.95f;
            }
            for (int i = 0; i < GRASS_PATCH_COUNT; i++) {
                float x = (hash01(i, 22.8f) * 2.0f - 1.0f) * (WORLD_LIMIT * 0.98f);
                float z = (hash01(i, 31.5f) * 2.0f - 1.0f) * (WORLD_LIMIT * 0.98f);
                if (isInsideLake(x, z, 1.05f)) {
                    x += 10.0f;
                    z += 13.0f;
                }
                grassX[i] = clamp(x, -WORLD_LIMIT, WORLD_LIMIT);
                grassZ[i] = clamp(z, -WORLD_LIMIT, WORLD_LIMIT);
                grassScale[i] = 0.65f + hash01(i, 44.4f) * 1.35f;
            }
        }

        private void drawDistantMountains() {
            setRenderMode(0, 1.0f);
            setAnim(0, 0f);
            for (int i = 0; i < MOUNTAIN_COUNT; i++) {
                float a = (float)(Math.PI * 2.0 * i / MOUNTAIN_COUNT) + 0.13f * (i % 3);
                float dist = WORLD_LIMIT * (0.78f + 0.16f * hash01(i, 71.0f));
                float x = (float)Math.cos(a) * dist;
                float z = (float)Math.sin(a) * dist;
                float base = 8.0f + 13.0f * hash01(i, 72.0f);
                float h = 10.0f + 22.0f * hash01(i, 73.0f);
                Matrix.setIdentityM(model, 0);
                Matrix.translateM(model, 0, x, terrainHeight(x, z) - 0.45f, z);
                Matrix.scaleM(model, 0, base, h, base);
                GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
                mountainMesh.draw(aPos, aNormal, aColor);
            }
        }

        private void drawLakes() {
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            setRenderMode(2, 0.68f);
            setAnim(0, 0f);
            for (int i = 0; i < LAKES.length; i++) {
                float cx = LAKES[i][0], cz = LAKES[i][1], rx = LAKES[i][2], rz = LAKES[i][3];
                float y = lakeWaterY(i);
                Matrix.setIdentityM(model, 0);
                Matrix.translateM(model, 0, cx, y, cz);
                Matrix.scaleM(model, 0, rx, 1.0f, rz);
                GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
                lakeMesh.draw(aPos, aNormal, aColor);
            }
            GLES20.glDisable(GLES20.GL_BLEND);
            setRenderMode(0, 1.0f);
        }

        private void drawGrassPatches() {
            setRenderMode(0, 1.0f);
            setAnim(0, 0f);
            for (int i = 0; i < GRASS_PATCH_COUNT; i++) {
                float x = grassX[i];
                float z = grassZ[i];
                if (isInsideLake(x, z, 0.96f)) continue;
                float y = terrainHeight(x, z) + 0.035f;
                float s = grassScale[i];
                Matrix.setIdentityM(model, 0);
                Matrix.translateM(model, 0, x, y, z);
                Matrix.rotateM(model, 0, hash01(i, 55.1f) * 360.0f, 0f, 1f, 0f);
                Matrix.scaleM(model, 0, s, 0.75f + s * 0.55f, s);
                GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
                grassPatchMesh.draw(aPos, aNormal, aColor);
            }
        }

        private void drawTrees() {
            setRenderMode(0, 1.0f);
            setAnim(0, 0f);
            for (int i = 0; i < TREE_COUNT; i++) {
                float x = treeX[i];
                float z = treeZ[i];
                if (isInsideLake(x, z, 1.05f)) continue;
                float y = terrainHeight(x, z);
                float s = treeScale[i];
                float trunkH = 1.25f * s;
                float trunkR = 0.18f * s;
                Matrix.setIdentityM(model, 0);
                Matrix.translateM(model, 0, x, y, z);
                Matrix.scaleM(model, 0, trunkR, trunkH, trunkR);
                GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
                treeTrunkMesh.draw(aPos, aNormal, aColor);

                Matrix.setIdentityM(model, 0);
                Matrix.translateM(model, 0, x, y + trunkH * 0.70f, z);
                Matrix.rotateM(model, 0, hash01(i, 88.0f) * 360.0f, 0f, 1f, 0f);
                Matrix.scaleM(model, 0, 0.95f * s, 1.55f * s, 0.95f * s);
                GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
                treeCrownMesh.draw(aPos, aNormal, aColor);
            }
        }

        public static float terrainHeight(float x, float z) {
            float rolling = 0.52f * (float)Math.sin(x * 0.055f) +
                            0.38f * (float)Math.cos(z * 0.061f) +
                            0.28f * (float)Math.sin((x + z) * 0.041f);
            float detail = 0.16f * (float)Math.sin(x * 0.19f + z * 0.04f) +
                           0.12f * (float)Math.cos(z * 0.22f - x * 0.03f);
            float mountains = 0.0f;
            mountains += mountainBump(x, z, 92f, -88f, 34f, 12.0f);
            mountains += mountainBump(x, z, -126f, 96f, 38f, 15.0f);
            mountains += mountainBump(x, z, 128f, 78f, 42f, 13.0f);
            mountains += mountainBump(x, z, -74f, -132f, 36f, 10.5f);
            mountains += mountainBump(x, z, 10f, 136f, 46f, 9.0f);
            float y = rolling + detail + mountains;
            // Smooth basins under lakes so the blue water sits in real low terrain.
            for (int i = 0; i < LAKES.length; i++) {
                float dx = (x - LAKES[i][0]) / LAKES[i][2];
                float dz = (z - LAKES[i][1]) / LAKES[i][3];
                float d = (float)Math.sqrt(dx * dx + dz * dz);
                float basin = 1.0f - smoothStep(0.62f, 1.18f, d);
                y -= basin * (0.85f + 0.22f * i);
            }
            return y;
        }

        private static float lakeWaterY(int i) {
            float cx = LAKES[i][0], cz = LAKES[i][1];
            return terrainHeight(cx, cz) + 0.42f + 0.04f * i;
        }

        private static boolean isInsideLake(float x, float z, float scale) {
            for (int i = 0; i < LAKES.length; i++) {
                float dx = (x - LAKES[i][0]) / (LAKES[i][2] * scale);
                float dz = (z - LAKES[i][1]) / (LAKES[i][3] * scale);
                if (dx * dx + dz * dz < 1.0f) return true;
            }
            return false;
        }

        private static float mountainBump(float x, float z, float cx, float cz, float radius, float height) {
            float dx = (x - cx) / radius;
            float dz = (z - cz) / radius;
            float d2 = dx * dx + dz * dz;
            return height * (float)Math.exp(-d2 * 1.55f);
        }

        private static float hash01(int i, float salt) {
            double v = Math.sin(i * 12.9898 + salt * 78.233) * 43758.5453;
            return (float)(v - Math.floor(v));
        }

        private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

        private static int createProgram(String vs, String fs) {
            int vertex = compileShader(GLES20.GL_VERTEX_SHADER, vs);
            int frag = compileShader(GLES20.GL_FRAGMENT_SHADER, fs);
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, vertex);
            GLES20.glAttachShader(p, frag);
            GLES20.glLinkProgram(p);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new RuntimeException("OpenGL link error: " + GLES20.glGetProgramInfoLog(p));
            return p;
        }

        private static int compileShader(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) throw new RuntimeException("OpenGL shader error: " + GLES20.glGetShaderInfoLog(s));
            return s;
        }

        private static final String VERTEX_SHADER =
                "attribute vec3 aPos;\n" +
                "attribute vec3 aNormal;\n" +
                "attribute vec3 aColor;\n" +
                "uniform mat4 uModel;\n" +
                "uniform mat4 uView;\n" +
                "uniform mat4 uProj;\n" +
                "uniform int uAnimMode;\n" +
                "uniform float uAnimT;\n" +
                "varying vec3 vColor;\n" +
                "varying vec3 vNormal;\n" +
                "varying vec3 vWorldPos;\n" +
                "void main(){\n" +
                "  vec3 p = aPos; // FIX13: no mesh distortion; rigid movement/effects only.\n" +
                "  vec4 worldPos = uModel * vec4(p, 1.0);\n" +
                "  vNormal = normalize(mat3(uModel) * aNormal);\n" +
                "  vColor = aColor;\n" +
                "  vWorldPos = worldPos.xyz;\n" +
                "  gl_Position = uProj * uView * worldPos;\n" +
                "}\n";

        private static final String FRAGMENT_SHADER =
                "precision highp float;\n" +
                "varying vec3 vColor;\n" +
                "varying vec3 vNormal;\n" +
                "varying vec3 vWorldPos;\n" +
                "uniform vec3 uLightDir;\n" +
                "uniform vec3 uSkyColor;\n" +
                "uniform vec3 uCamera;\n" +
                "uniform int uRenderMode;\n" +
                "uniform float uAlpha;\n" +
                "uniform float uLightPower;\n" +
                "uniform float uAmbientPower;\n" +
                "uniform float uBrightness;\n" +
                "uniform float uNightAmount;\n" +
                "uniform float uRayShadowStrength;\n" +
                "float lakeBasin(vec2 p, vec2 c, vec2 r){\n" +
                "  vec2 q = (p - c) / r;\n" +
                "  float d = dot(q, q);\n" +
                "  float inside = 1.0 - smoothstep(0.64, 1.18, d);\n" +
                "  return inside;\n" +
                "}\n" +
                "float terrainH(vec2 p){\n" +
                "  float x = p.x; float z = p.y;\n" +
                "  float h = 0.42*sin(x*0.070) + 0.35*cos(z*0.055) + 0.22*sin((x+z)*0.042);\n" +
                "  h += 1.25*exp(-dot(p-vec2(82.0,-92.0), p-vec2(82.0,-92.0))/1550.0);\n" +
                "  h += 1.00*exp(-dot(p-vec2(-118.0,88.0), p-vec2(-118.0,88.0))/1900.0);\n" +
                "  h += 0.78*exp(-dot(p-vec2(135.0,118.0), p-vec2(135.0,118.0))/2400.0);\n" +
                "  h += 0.58*exp(-dot(p-vec2(-58.0,-126.0), p-vec2(-58.0,-126.0))/1400.0);\n" +
                "  h -= 0.88*lakeBasin(p, vec2(-46.0,-34.0), vec2(16.0,9.0));\n" +
                "  h -= 1.05*lakeBasin(p, vec2(48.0,42.0), vec2(20.0,12.0));\n" +
                "  h -= 1.10*lakeBasin(p, vec2(98.0,-68.0), vec2(28.0,15.0));\n" +
                "  h -= 1.04*lakeBasin(p, vec2(-118.0,78.0), vec2(24.0,13.0));\n" +
                "  return h;\n" +
                "}\n" +
                "float rayCastTerrainShadow(vec3 origin, vec3 lightVec){\n" +
                "  // FIX18: fast mode. Per-fragment ray marching was removed because it lagged on phones.\n" +
                "  return 1.0;\n" +
                "}\n" +
                "void main(){\n" +
                "  if (uRenderMode == 1) {\n" +
                "    gl_FragColor = vec4(0.010, 0.008, 0.004, uAlpha);\n" +
                "    return;\n" +
                "  }\n" +
                "  if (uRenderMode == 2) {\n" +
                "    gl_FragColor = vec4(vColor, uAlpha);\n" +
                "    return;\n" +
                "  }\n" +
                "  vec3 n = normalize(vNormal);\n" +
                "  vec3 lightVec = normalize(-uLightDir);\n" +
                "  float traced = rayCastTerrainShadow(vWorldPos + n * 0.18, lightVec);\n" +
                "  float direct = max(dot(n, lightVec), 0.0) * traced;\n" +
                "  float topLight = clamp(n.y * 0.5 + 0.5, 0.0, 1.0);\n" +
                "  vec3 sunColor = mix(vec3(1.0, 0.86, 0.50), vec3(0.45, 0.58, 1.00), uNightAmount);\n" +
                "  vec3 coolShade = mix(vec3(0.34, 0.47, 0.70), vec3(0.025, 0.045, 0.14), uNightAmount);\n" +
                "  vec3 hemi = mix(coolShade, sunColor, topLight);\n" +
                "  vec3 viewDir = normalize(uCamera - vWorldPos);\n" +
                "  vec3 halfDir = normalize(lightVec + viewDir);\n" +
                "  float spec = pow(max(dot(n, halfDir), 0.0), 34.0) * direct * uLightPower;\n" +
                "  vec3 lit = vColor * (uAmbientPower + direct * (1.05 * uLightPower)) + hemi * (0.08 + 0.16 * uLightPower) + sunColor * spec * 0.56;\n" +
                "  lit *= mix(0.72, 1.0, traced);\n" +
                "  lit *= uBrightness;\n" +
                "  float dist = length(vWorldPos - uCamera);\n" +
                "  float fog = clamp((dist - 90.0) / 270.0, 0.0, mix(0.38, 0.68, uNightAmount));\n" +
                "  lit = mix(lit, uSkyColor, fog);\n" +
                "  gl_FragColor = vec4(lit, 1.0);\n" +
                "}\n";
    }

    public static class Mesh {
        private final FloatBuffer buffer;
        private final int vertexCount;
        private static final int STRIDE = 9 * 4;

        public Mesh(FloatBuffer buffer, int vertexCount) {
            this.buffer = buffer;
            this.vertexCount = vertexCount;
        }

        public static Mesh loadFromAsset(AssetManager assets, String name) {
            try {
                InputStream in = assets.open(name);
                byte[] raw = readFully(in);
                ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
                int count = bb.getInt();
                ByteBuffer direct = ByteBuffer.allocateDirect(count * 9 * 4).order(ByteOrder.nativeOrder());
                FloatBuffer fb = direct.asFloatBuffer();
                for (int i = 0; i < count * 9; i++) fb.put(bb.getFloat());
                fb.position(0);
                return new Mesh(fb, count);
            } catch (Exception e) {
                throw new RuntimeException("Failed loading model asset: " + name, e);
            }
        }

        public void draw(int aPos, int aNormal, int aColor) {
            buffer.position(0);
            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, STRIDE, buffer);
            buffer.position(3);
            GLES20.glEnableVertexAttribArray(aNormal);
            GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, STRIDE, buffer);
            buffer.position(6);
            GLES20.glEnableVertexAttribArray(aColor);
            GLES20.glVertexAttribPointer(aColor, 3, GLES20.GL_FLOAT, false, STRIDE, buffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount);
            GLES20.glDisableVertexAttribArray(aPos);
            GLES20.glDisableVertexAttribArray(aNormal);
            GLES20.glDisableVertexAttribArray(aColor);
            buffer.position(0);
        }

        private static byte[] readFully(InputStream in) throws Exception {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                return out.toByteArray();
            } finally {
                in.close();
            }
        }
    }

    public static class SunFactory {
        public static Mesh makeSunDisk(int segments, float radius) {
            int verts = segments * 3;
            ByteBuffer direct = ByteBuffer.allocateDirect(verts * 9 * 4).order(ByteOrder.nativeOrder());
            FloatBuffer fb = direct.asFloatBuffer();
            for (int i = 0; i < segments; i++) {
                float a0 = (float)(Math.PI * 2.0 * i / segments);
                float a1 = (float)(Math.PI * 2.0 * (i + 1) / segments);
                putSunV(fb, 0f, 0f, 0f, 3.00f, 2.30f, 0.55f);
                putSunV(fb, (float)Math.cos(a0) * radius, (float)Math.sin(a0) * radius, 0f, 2.20f, 1.40f, 0.15f);
                putSunV(fb, (float)Math.cos(a1) * radius, (float)Math.sin(a1) * radius, 0f, 2.20f, 1.40f, 0.15f);
            }
            fb.position(0);
            return new Mesh(fb, verts);
        }

        public static Mesh makeSunRays(int rays, float innerRadius, float outerRadius) {
            int verts = rays * 3;
            ByteBuffer direct = ByteBuffer.allocateDirect(verts * 9 * 4).order(ByteOrder.nativeOrder());
            FloatBuffer fb = direct.asFloatBuffer();
            for (int i = 0; i < rays; i++) {
                float center = (float)(Math.PI * 2.0 * i / rays);
                float width = 0.09f + 0.035f * (i % 3);
                float a0 = center - width;
                float a1 = center + width;
                float out = outerRadius + (i % 4) * 0.45f;
                putSunV(fb, (float)Math.cos(a0) * innerRadius, (float)Math.sin(a0) * innerRadius, 0f, 1.25f, 0.78f, 0.18f);
                putSunV(fb, (float)Math.cos(center) * out, (float)Math.sin(center) * out, 0f, 1.80f, 1.05f, 0.18f);
                putSunV(fb, (float)Math.cos(a1) * innerRadius, (float)Math.sin(a1) * innerRadius, 0f, 1.25f, 0.78f, 0.18f);
            }
            fb.position(0);
            return new Mesh(fb, verts);
        }

        public static Mesh makeMoonDisk(int segments, float radius) {
            int verts = segments * 3;
            ByteBuffer direct = ByteBuffer.allocateDirect(verts * 9 * 4).order(ByteOrder.nativeOrder());
            FloatBuffer fb = direct.asFloatBuffer();
            for (int i = 0; i < segments; i++) {
                float a0 = (float)(Math.PI * 2.0 * i / segments);
                float a1 = (float)(Math.PI * 2.0 * (i + 1) / segments);
                putSunV(fb, 0f, 0f, 0f, 0.78f, 0.86f, 1.28f);
                putSunV(fb, (float)Math.cos(a0) * radius, (float)Math.sin(a0) * radius, 0f, 0.42f, 0.52f, 0.96f);
                putSunV(fb, (float)Math.cos(a1) * radius, (float)Math.sin(a1) * radius, 0f, 0.42f, 0.52f, 0.96f);
            }
            fb.position(0);
            return new Mesh(fb, verts);
        }

        public static Mesh makeMoonRays(int rays, float innerRadius, float outerRadius) {
            int verts = rays * 3;
            ByteBuffer direct = ByteBuffer.allocateDirect(verts * 9 * 4).order(ByteOrder.nativeOrder());
            FloatBuffer fb = direct.asFloatBuffer();
            for (int i = 0; i < rays; i++) {
                float center = (float)(Math.PI * 2.0 * i / rays);
                float width = 0.070f + 0.022f * (i % 3);
                float a0 = center - width;
                float a1 = center + width;
                float out = outerRadius + (i % 4) * 0.22f;
                putSunV(fb, (float)Math.cos(a0) * innerRadius, (float)Math.sin(a0) * innerRadius, 0f, 0.30f, 0.42f, 0.96f);
                putSunV(fb, (float)Math.cos(center) * out, (float)Math.sin(center) * out, 0f, 0.48f, 0.64f, 1.35f);
                putSunV(fb, (float)Math.cos(a1) * innerRadius, (float)Math.sin(a1) * innerRadius, 0f, 0.30f, 0.42f, 0.96f);
            }
            fb.position(0);
            return new Mesh(fb, verts);
        }

        private static void putSunV(FloatBuffer fb, float x, float y, float z, float r, float g, float b) {
            fb.put(x).put(y).put(z);
            fb.put(0f).put(0f).put(-1f);
            fb.put(r).put(g).put(b);
        }
    }


    public static class FlatFactory {
        public static Mesh makeEllipse(int segments, float radiusX, float radiusZ, float r, float g, float b) {
            int verts = segments * 3;
            ByteBuffer direct = ByteBuffer.allocateDirect(verts * 9 * 4).order(ByteOrder.nativeOrder());
            FloatBuffer fb = direct.asFloatBuffer();
            for (int i = 0; i < segments; i++) {
                float a0 = (float)(Math.PI * 2.0 * i / segments);
                float a1 = (float)(Math.PI * 2.0 * (i + 1) / segments);
                putFlatV(fb, 0f, 0f, 0f, r, g, b);
                putFlatV(fb, (float)Math.cos(a0) * radiusX, 0f, (float)Math.sin(a0) * radiusZ, r, g, b);
                putFlatV(fb, (float)Math.cos(a1) * radiusX, 0f, (float)Math.sin(a1) * radiusZ, r, g, b);
            }
            fb.position(0);
            return new Mesh(fb, verts);
        }

        private static void putFlatV(FloatBuffer fb, float x, float y, float z, float r, float g, float b) {
            fb.put(x).put(y).put(z);
            fb.put(0f).put(1f).put(0f);
            fb.put(r).put(g).put(b);
        }
    }


    public static class AttackFactory {
        public static Mesh makePunchArc(int segments, float innerRadius, float outerRadius) {
            int verts = segments * 6;
            ByteBuffer direct = ByteBuffer.allocateDirect(verts * 9 * 4).order(ByteOrder.nativeOrder());
            FloatBuffer fb = direct.asFloatBuffer();
            float start = -0.95f;
            float end = 0.95f;
            for (int i = 0; i < segments; i++) {
                float a0 = start + (end - start) * i / segments;
                float a1 = start + (end - start) * (i + 1) / segments;
                float x0i = (float)Math.sin(a0) * innerRadius;
                float y0i = (float)Math.cos(a0) * innerRadius;
                float x1i = (float)Math.sin(a1) * innerRadius;
                float y1i = (float)Math.cos(a1) * innerRadius;
                float x0o = (float)Math.sin(a0) * outerRadius;
                float y0o = (float)Math.cos(a0) * outerRadius;
                float x1o = (float)Math.sin(a1) * outerRadius;
                float y1o = (float)Math.cos(a1) * outerRadius;
                putArcV(fb, x0i, y0i, 0f, 1.00f, 0.82f, 0.25f);
                putArcV(fb, x0o, y0o, 0f, 1.00f, 0.56f, 0.10f);
                putArcV(fb, x1o, y1o, 0f, 1.00f, 0.56f, 0.10f);
                putArcV(fb, x0i, y0i, 0f, 1.00f, 0.82f, 0.25f);
                putArcV(fb, x1o, y1o, 0f, 1.00f, 0.56f, 0.10f);
                putArcV(fb, x1i, y1i, 0f, 1.00f, 0.82f, 0.25f);
            }
            fb.position(0);
            return new Mesh(fb, verts);
        }

        public static Mesh makeBox(float width, float height, float depth, float r, float g, float b) {
            float x = width * 0.5f, y = height * 0.5f, z = depth * 0.5f;
            float[] p = new float[] {
                    -x,-y,-z,  x,-y,-z,  x, y,-z,  -x, y,-z,
                    -x,-y, z,  x,-y, z,  x, y, z,  -x, y, z
            };
            int[][] faces = new int[][] {
                    {0,1,2, 0,2,3}, {5,4,7, 5,7,6},
                    {4,0,3, 4,3,7}, {1,5,6, 1,6,2},
                    {3,2,6, 3,6,7}, {4,5,1, 4,1,0}
            };
            float[][] normals = new float[][] {
                    {0f,0f,-1f}, {0f,0f,1f}, {-1f,0f,0f}, {1f,0f,0f}, {0f,1f,0f}, {0f,-1f,0f}
            };
            ByteBuffer direct = ByteBuffer.allocateDirect(36 * 9 * 4).order(ByteOrder.nativeOrder());
            FloatBuffer fb = direct.asFloatBuffer();
            for (int f = 0; f < faces.length; f++) {
                for (int k = 0; k < 6; k++) {
                    int idx = faces[f][k];
                    fb.put(p[idx*3]).put(p[idx*3+1]).put(p[idx*3+2]);
                    fb.put(normals[f][0]).put(normals[f][1]).put(normals[f][2]);
                    fb.put(r).put(g).put(b);
                }
            }
            fb.position(0);
            return new Mesh(fb, 36);
        }

        private static void putArcV(FloatBuffer fb, float x, float y, float z, float r, float g, float b) {
            fb.put(x).put(y).put(z);
            fb.put(0f).put(0f).put(1f);
            fb.put(r).put(g).put(b);
        }
    }

    public static class NatureFactory {
        public static Mesh makeLakeDisk(int segments) {
            int verts = segments * 3;
            ByteBuffer direct = ByteBuffer.allocateDirect(verts * 9 * 4).order(ByteOrder.nativeOrder());
            FloatBuffer fb = direct.asFloatBuffer();
            for (int i = 0; i < segments; i++) {
                float a0 = (float)(Math.PI * 2.0 * i / segments);
                float a1 = (float)(Math.PI * 2.0 * (i + 1) / segments);
                putV(fb, 0f, 0f, 0f, 0f, 1f, 0f, 0.08f, 0.46f, 0.92f);
                putV(fb, (float)Math.cos(a0), 0f, (float)Math.sin(a0), 0f, 1f, 0f, 0.16f, 0.64f, 1.00f);
                putV(fb, (float)Math.cos(a1), 0f, (float)Math.sin(a1), 0f, 1f, 0f, 0.11f, 0.52f, 0.96f);
            }
            fb.position(0);
            return new Mesh(fb, verts);
        }

        public static Mesh makeCylinder(int segments, float r, float g, float b) {
            int verts = segments * 12;
            ByteBuffer direct = ByteBuffer.allocateDirect(verts * 9 * 4).order(ByteOrder.nativeOrder());
            FloatBuffer fb = direct.asFloatBuffer();
            for (int i = 0; i < segments; i++) {
                float a0 = (float)(Math.PI * 2.0 * i / segments);
                float a1 = (float)(Math.PI * 2.0 * (i + 1) / segments);
                float x0 = (float)Math.cos(a0), z0 = (float)Math.sin(a0);
                float x1 = (float)Math.cos(a1), z1 = (float)Math.sin(a1);
                putV(fb, x0, 0f, z0, x0, 0f, z0, r, g, b);
                putV(fb, x1, 0f, z1, x1, 0f, z1, r, g, b);
                putV(fb, x1, 1f, z1, x1, 0f, z1, r, g, b);
                putV(fb, x0, 0f, z0, x0, 0f, z0, r, g, b);
                putV(fb, x1, 1f, z1, x1, 0f, z1, r, g, b);
                putV(fb, x0, 1f, z0, x0, 0f, z0, r, g, b);
                putV(fb, 0f, 0f, 0f, 0f, -1f, 0f, r * 0.75f, g * 0.75f, b * 0.75f);
                putV(fb, x1, 0f, z1, 0f, -1f, 0f, r * 0.75f, g * 0.75f, b * 0.75f);
                putV(fb, x0, 0f, z0, 0f, -1f, 0f, r * 0.75f, g * 0.75f, b * 0.75f);
                putV(fb, 0f, 1f, 0f, 0f, 1f, 0f, r * 1.10f, g * 1.10f, b * 1.10f);
                putV(fb, x0, 1f, z0, 0f, 1f, 0f, r * 1.10f, g * 1.10f, b * 1.10f);
                putV(fb, x1, 1f, z1, 0f, 1f, 0f, r * 1.10f, g * 1.10f, b * 1.10f);
            }
            fb.position(0);
            return new Mesh(fb, verts);
        }

        public static Mesh makeCone(int segments, float r, float g, float b) {
            int verts = segments * 6;
            ByteBuffer direct = ByteBuffer.allocateDirect(verts * 9 * 4).order(ByteOrder.nativeOrder());
            FloatBuffer fb = direct.asFloatBuffer();
            for (int i = 0; i < segments; i++) {
                float a0 = (float)(Math.PI * 2.0 * i / segments);
                float a1 = (float)(Math.PI * 2.0 * (i + 1) / segments);
                float x0 = (float)Math.cos(a0), z0 = (float)Math.sin(a0);
                float x1 = (float)Math.cos(a1), z1 = (float)Math.sin(a1);
                float nx = (x0 + x1) * 0.45f;
                float nz = (z0 + z1) * 0.45f;
                float ny = 0.55f;
                float len = (float)Math.sqrt(nx*nx + ny*ny + nz*nz);
                nx /= len; ny /= len; nz /= len;
                putV(fb, x0, 0f, z0, nx, ny, nz, r * 0.80f, g * 0.90f, b * 0.80f);
                putV(fb, x1, 0f, z1, nx, ny, nz, r * 0.72f, g * 0.82f, b * 0.72f);
                putV(fb, 0f, 1f, 0f, nx, ny, nz, r * 1.18f, g * 1.25f, b * 1.10f);
                putV(fb, 0f, 0f, 0f, 0f, -1f, 0f, r * 0.45f, g * 0.55f, b * 0.45f);
                putV(fb, x1, 0f, z1, 0f, -1f, 0f, r * 0.45f, g * 0.55f, b * 0.45f);
                putV(fb, x0, 0f, z0, 0f, -1f, 0f, r * 0.45f, g * 0.55f, b * 0.45f);
            }
            fb.position(0);
            return new Mesh(fb, verts);
        }

        public static Mesh makeGrassCluster(int blades) {
            int verts = blades * 3;
            ByteBuffer direct = ByteBuffer.allocateDirect(verts * 9 * 4).order(ByteOrder.nativeOrder());
            FloatBuffer fb = direct.asFloatBuffer();
            for (int i = 0; i < blades; i++) {
                float a = (float)(Math.PI * 2.0 * i / blades);
                float ca = (float)Math.cos(a), sa = (float)Math.sin(a);
                float width = 0.055f + 0.015f * (i % 3);
                float height = 0.42f + 0.09f * (i % 4);
                float off = 0.09f * (i % 2);
                float r = 0.10f + 0.04f * (i % 2);
                float g = 0.48f + 0.12f * (i % 3) / 2.0f;
                float b = 0.11f + 0.03f * (i % 3);
                putV(fb, ca * (off - width), 0f, sa * (off - width), ca, 0.25f, sa, r, g, b);
                putV(fb, ca * (off + width), 0f, sa * (off + width), ca, 0.25f, sa, r, g, b);
                putV(fb, ca * off, height, sa * off, ca, 0.55f, sa, r * 1.18f, g * 1.20f, b * 1.05f);
            }
            fb.position(0);
            return new Mesh(fb, verts);
        }

        private static void putV(FloatBuffer fb, float x, float y, float z, float nx, float ny, float nz, float r, float g, float b) {
            fb.put(x).put(y).put(z);
            fb.put(nx).put(ny).put(nz);
            fb.put(r).put(g).put(b);
        }
    }

    public static class TerrainFactory {
        public static Mesh makeTerrain(int cells, float step) {
            int verts = cells * cells * 6;
            ByteBuffer direct = ByteBuffer.allocateDirect(verts * 9 * 4).order(ByteOrder.nativeOrder());
            FloatBuffer fb = direct.asFloatBuffer();
            float half = cells * step * 0.5f;
            for (int ix = 0; ix < cells; ix++) {
                for (int iz = 0; iz < cells; iz++) {
                    float x0 = ix * step - half;
                    float z0 = iz * step - half;
                    float x1 = x0 + step;
                    float z1 = z0 + step;
                    float[] p00 = v(x0, WorldRenderer.terrainHeight(x0, z0), z0);
                    float[] p10 = v(x1, WorldRenderer.terrainHeight(x1, z0), z0);
                    float[] p01 = v(x0, WorldRenderer.terrainHeight(x0, z1), z1);
                    float[] p11 = v(x1, WorldRenderer.terrainHeight(x1, z1), z1);
                    putTri(fb, p00, p10, p11);
                    putTri(fb, p00, p11, p01);
                }
            }
            fb.position(0);
            return new Mesh(fb, verts);
        }

        private static float[] v(float x, float y, float z) { return new float[] {x, y, z}; }

        private static void putTri(FloatBuffer fb, float[] a, float[] b, float[] c) {
            float[] n = normal(a, b, c);
            putV(fb, a, n);
            putV(fb, b, n);
            putV(fb, c, n);
        }

        private static void putV(FloatBuffer fb, float[] p, float[] n) {
            float h = p[1];
            float x = p[0];
            float z = p[2];
            // FIX15: biome color variation: bright grass, dark grass, yellow-green grass, rock, and snow caps.
            float noise = 0.5f + 0.5f * (float)Math.sin(x * 0.37f + z * 0.21f + Math.sin(z * 0.09f) * 2.0f);
            float r, g, b;
            if (h > 7.0f) {
                r = 0.72f; g = 0.75f; b = 0.70f;
            } else if (h > 3.2f) {
                r = 0.30f + noise * 0.10f; g = 0.32f + noise * 0.12f; b = 0.28f + noise * 0.08f;
            } else {
                r = 0.08f + noise * 0.12f;
                g = 0.36f + noise * 0.28f;
                b = 0.10f + noise * 0.11f;
            }
            fb.put(p[0]).put(p[1]).put(p[2]);
            fb.put(n[0]).put(n[1]).put(n[2]);
            fb.put(clamp(r, 0.06f, 0.76f)).put(clamp(g, 0.25f, 0.78f)).put(clamp(b, 0.08f, 0.72f));
        }

        private static float[] normal(float[] a, float[] b, float[] c) {
            float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
            float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
            float nx = uy * vz - uz * vy;
            float ny = uz * vx - ux * vz;
            float nz = ux * vy - uy * vx;
            float len = (float)Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 0.0001f) len = 1f;
            nx /= len; ny /= len; nz /= len;
            if (ny < 0f) { nx = -nx; ny = -ny; nz = -nz; }
            return new float[] { nx, ny, nz };
        }

        private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    }
}
