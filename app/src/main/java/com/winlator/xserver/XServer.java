package com.winlator.xserver;

import android.graphics.Rect;
import android.util.Log;
import android.util.SparseArray;

import com.winlator.math.Mathf;
import com.winlator.renderer.XServerRenderer;
import com.winlator.winhandler.MouseEventFlags;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.extensions.BigReqExtension;
import com.winlator.xserver.extensions.DRI3Extension;
import com.winlator.xserver.extensions.Extension;
import com.winlator.xserver.extensions.GenericEventExtension;
import com.winlator.xserver.extensions.MITSHMExtension;
import com.winlator.xserver.extensions.PresentExtension;
import com.winlator.xserver.extensions.SyncExtension;
import com.winlator.xserver.extensions.XInput2Extension;

import java.nio.charset.Charset;
import java.util.EnumMap;
import java.util.concurrent.locks.ReentrantLock;

public class XServer {
    public enum Lockable {WINDOW_MANAGER, PIXMAP_MANAGER, DRAWABLE_MANAGER, GRAPHIC_CONTEXT_MANAGER, INPUT_DEVICE, CURSOR_MANAGER, SHMSEGMENT_MANAGER}
    public static final short VERSION = 11;
    public static final String VENDOR_NAME = "Elbrus Technologies, LLC";
    public static final Charset LATIN1_CHARSET = Charset.forName("latin1");
    public final SparseArray<Extension> extensions = new SparseArray<>();
    public final ScreenInfo screenInfo;
    public final PixmapManager pixmapManager;
    public final ResourceIDs resourceIDs = new ResourceIDs(128);
    public final GraphicsContextManager graphicsContextManager = new GraphicsContextManager();
    public final SelectionManager selectionManager;
    public final DrawableManager drawableManager;
    public final WindowManager windowManager;
    public final CursorManager cursorManager;
    public final Keyboard keyboard = Keyboard.createKeyboard(this);
    public final Pointer pointer = new Pointer(this);
    public final InputDeviceManager inputDeviceManager;
    public final GrabManager grabManager;
    private boolean isGrabbed = false;
    private XClient grabbingClient = null;
    private SHMSegmentManager shmSegmentManager;
    private XServerRenderer renderer;
    private WinHandler winHandler;
    private final EnumMap<Lockable, ReentrantLock> locks = new EnumMap<>(Lockable.class);
    private volatile boolean relativeMouseMovement = false;
    // Per-game: relative Win32 input regardless of what the touch shooter mode selects
    private volatile boolean forceRelativeMouseMovement = false;
    private volatile ScreenInfo effectiveScreenInfo;
    private final boolean mouseDragCompatibilityEnabled;
    // Mouselook warp sync (see injectPointerMoveDelta); guarded by the INPUT_DEVICE lock.
    private static final long WARP_SYNC_WINDOW_NANOS = 100_000_000L;
    private long lastWarpNanos = 0;
    private int pendingWarpDx = 0;
    private int pendingWarpDy = 0;
    private boolean simulateTouchScreen = false;
    private volatile boolean flatPresentationEnabled = true;

    public XServer(ScreenInfo screenInfo) {
        this(screenInfo, false);
    }

    public XServer(ScreenInfo screenInfo, boolean mouseDragCompatibilityEnabled) {
        Log.d("XServer", "Creating xServer " + screenInfo);
        this.screenInfo = screenInfo;
        for (Lockable lockable : Lockable.values()) locks.put(lockable, new ReentrantLock());

        pixmapManager = new PixmapManager();
        drawableManager = new DrawableManager(this);
        cursorManager = new CursorManager(drawableManager);
        windowManager = new WindowManager(screenInfo, drawableManager);
        selectionManager = new SelectionManager(windowManager);
        inputDeviceManager = new InputDeviceManager(this);
        grabManager = new GrabManager(this);
        this.mouseDragCompatibilityEnabled = mouseDragCompatibilityEnabled;

        DesktopHelper.attachTo(this);
        setupExtensions();

        effectiveScreenInfo = screenInfo;
        windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onMapWindow(Window window) {
                if (isDesktopWindow(window)) effectiveScreenInfo = computeEffectiveScreenInfo();
            }

            @Override
            public void onUnmapWindow(Window window) {
                if (isDesktopWindow(window)) effectiveScreenInfo = computeEffectiveScreenInfo();
            }

            @Override
            public void onUpdateWindowGeometry(Window window, boolean resized) {
                if (resized && isDesktopWindow(window)) effectiveScreenInfo = computeEffectiveScreenInfo();
            }
        });
    }

    public boolean isRelativeMouseMovement() {
        return relativeMouseMovement || (forceRelativeMouseMovement && isGameCursorHidden());
    }

    /**
     * The game's own cursor is hidden (mouselook). Forced relative input only applies then: with
     * the cursor shown (menus) movement must go through the X pointer, which is what draws it.
     */
    private boolean isGameCursorHidden() {
        Window pointWindow = inputDeviceManager.getPointWindow();
        Cursor cursor = pointWindow != null ? pointWindow.attributes.getCursor() : null;
        return cursor != null && !cursor.isVisible();
    }

    public void setForceRelativeMouseMovement(boolean force) {
        this.forceRelativeMouseMovement = force;
    }

    public void setRelativeMouseMovement(boolean relativeMouseMovement) {
        this.relativeMouseMovement = relativeMouseMovement;
    }

    public boolean isMouseDragCompatibilityEnabled() {
        return mouseDragCompatibilityEnabled;
    }

    public boolean isSimulateTouchScreen() { return simulateTouchScreen; }

    public void setSimulateTouchScreen(boolean simulateTouchScreen) {
        this.simulateTouchScreen = simulateTouchScreen;
    }

    public XServerRenderer getRenderer() {
        return renderer;
    }

    public void setRenderer(XServerRenderer renderer) {
        this.renderer = renderer;
    }

    public void setRenderingEnabled(boolean enabled) {
        // intentionally empty
    }

    public void setFlatPresentationEnabled(boolean enabled) {
        flatPresentationEnabled = enabled;
    }

    public boolean isFlatPresentationEnabled() {
        return flatPresentationEnabled;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public void setWinHandler(WinHandler winHandler) {
        this.winHandler = winHandler;
    }

    public SHMSegmentManager getSHMSegmentManager() {
        return shmSegmentManager;
    }

    public void setSHMSegmentManager(SHMSegmentManager shmSegmentManager) {
        this.shmSegmentManager = shmSegmentManager;
    }

    private class SingleXLock implements XLock {
        private final ReentrantLock lock;

        private SingleXLock(Lockable lockable) {
            this.lock = locks.get(lockable);
            lock.lock();
        }

        @Override
        public void close() {
            lock.unlock();
        }
    }

    private class MultiXLock implements XLock {
        private final Lockable[] lockables;

        private MultiXLock(Lockable[] lockables) {
            this.lockables = lockables;
            for (Lockable lockable : lockables) locks.get(lockable).lock();
        }

        @Override
        public void close() {
            for (int i = lockables.length - 1; i >= 0; i--) {
                locks.get(lockables[i]).unlock();
            }
        }
    }

    public XLock lock(Lockable lockable) {
        return new SingleXLock(lockable);
    }

    public XLock lock(Lockable... lockables) {
        return new MultiXLock(lockables);
    }

    public XLock lockAll() {
        return new MultiXLock(Lockable.values());
    }

    public Extension getExtensionByName(String name) {
        for (int i = 0; i < extensions.size(); i++) {
            Extension extension = extensions.valueAt(i);
            if (extension.getName().equals(name)) return extension;
        }
        return null;
    }

    /** Optional input retarget (Gamescope mode): every Android-side capture point funnels through these five inject* methods, so this one seam retargets them all. */
    public interface InputEventSink {
        void pointerMove(int x, int y);
        void pointerMoveDelta(int dx, int dy);
        void pointerButton(Pointer.Button button, boolean pressed);
        void keyPress(XKeycode xKeycode, int keysym);
        void keyRelease(XKeycode xKeycode);
    }

    private InputEventSink inputEventSink;

    public void setInputEventSink(InputEventSink sink) {
        inputEventSink = sink;
    }

    public InputEventSink getInputEventSink() {
        return inputEventSink;
    }

    public void injectPointerMove(int x, int y) {
        InputEventSink sink = inputEventSink;
        if (sink != null) {
            try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
                pointer.setX(x);
                pointer.setY(y);
                sink.pointerMove(x, y);
            }
            return;
        }
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setPosition(x, y);
        }
    }

    public void injectPointerMoveDelta(int dx, int dy) {
        if (dx == 0 && dy == 0) return;

        InputEventSink sink = inputEventSink;
        if (sink != null) {
            try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
                pointer.setX(pointer.getX() + dx);
                pointer.setY(pointer.getY() + dy);
                sink.pointerMoveDelta(dx, dy);
            }
            return;
        }

        WinHandler handler = winHandler;
        if (isRelativeMouseMovement() && handler != null) {
            handler.mouseEvent(MouseEventFlags.MOVE, dx, dy, 0);
            return;
        }

        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            // XI raw motion carries each step as it happens; only the core pointer is synchronised.
            XInput2Extension xi = getExtension(XInput2Extension.MAJOR_OPCODE);
            if (xi != null)
                xi.emitRawMotion(2, dx, dy);

            long now = System.nanoTime();
            if (lastWarpNanos != 0 && now - lastWarpNanos < WARP_SYNC_WINDOW_NANOS) {
                // The client is recentring the pointer every frame (mouselook). Moving the pointer now
                // would race its next warp: Wine compares the reported position with where it last put
                // the cursor, so a step landing before the warp is processed is counted twice and one
                // landing after it is lost. Hold the motion and apply it right after that warp instead.
                pendingWarpDx += dx;
                pendingWarpDy += dy;
            } else {
                dx += pendingWarpDx;
                dy += pendingWarpDy;
                pendingWarpDx = 0;
                pendingWarpDy = 0;
                movePointerByLocked(dx, dy);
            }
        }
    }

    /**
     * Called (under the WINDOW_MANAGER and INPUT_DEVICE locks) when a client warps the pointer.
     * Injected motion held since the previous warp is applied on top of the new position, so it
     * reaches the client exactly once, after the client's own recentring.
     */
    public void onClientWarpPointer(int x, int y) {
        lastWarpNanos = System.nanoTime();
        if (pendingWarpDx == 0 && pendingWarpDy == 0) {
            pointer.setPosition(x, y);
            return;
        }
        int dx = pendingWarpDx, dy = pendingWarpDy;
        pendingWarpDx = 0;
        pendingWarpDy = 0;
        pointer.setX(x);
        pointer.setY(y);
        movePointerByLocked(dx, dy);
    }

    private void movePointerByLocked(int dx, int dy) {
        ScreenInfo screen = getEffectiveScreenInfo();
        int minX = 0, minY = 0;
        int maxX = screen.width - 1, maxY = screen.height - 1;
        short clampedX = 0, clampedY = 0;

        // ClipCursor
        Rect confinement = grabManager.getConfinementBounds();
        if (confinement != null) {
            minX = Math.max(minX, confinement.left);
            minY = Math.max(minY, confinement.top);
            maxX = Math.min(maxX, confinement.right - 1);
            maxY = Math.min(maxY, confinement.bottom - 1);

            clampedX = (short) Mathf.clamp(pointer.getX() + dx, minX, maxX);
            clampedY = (short) Mathf.clamp(pointer.getY() + dy, minY, maxY);

            pointer.setPosition(clampedX, clampedY);
        } else {
            // Legacy path for old Proton builds with broken ClipCursor
            // Do NOT remove without confirming no regressions with:
            // - Games that rely on XWarpPointer (see WindowRequests.java:warpPointer)
            // - Games that call ClipCursor and happen to work well with this workaround
            short softMarginX = (short)(screen.width * 0.05f);
            short softMarginY = (short)(screen.height * 0.05f);
            short x = (short)Mathf.clamp(pointer.getX() + dx, -softMarginX, maxX + softMarginX);
            short y = (short)Mathf.clamp(pointer.getY() + dy, -softMarginY, maxY + softMarginY);

            pointer.setPosition(x, y);

            clampedX = (short)Mathf.clamp(x, 0, maxX);
            clampedY = (short)Mathf.clamp(y, 0, maxY);

            pointer.setX(clampedX);
            pointer.setY(clampedY);
        }
    }

    public void injectPointerButtonPress(Pointer.Button buttonCode) {
        InputEventSink sink = inputEventSink;
        if (sink != null) {
            try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
                pointer.getButtonMask().set(buttonCode.flag(), true);
                sink.pointerButton(buttonCode, true);
            }
            return;
        }
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, true);

            XInput2Extension xi = getExtension(XInput2Extension.MAJOR_OPCODE);
            if (xi != null)
                xi.emitRawButton(2, buttonCode.code(), true);
        }
    }

    public void injectPointerButtonRelease(Pointer.Button buttonCode) {
        InputEventSink sink = inputEventSink;
        if (sink != null) {
            try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
                pointer.getButtonMask().set(buttonCode.flag(), false);
                sink.pointerButton(buttonCode, false);
            }
            return;
        }
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, false);

            XInput2Extension xi = getExtension(XInput2Extension.MAJOR_OPCODE);
            if (xi != null)
                xi.emitRawButton(2, buttonCode.code(), false);
        }
    }

    public void injectKeyPress(XKeycode xKeycode) {
        injectKeyPress(xKeycode, 0);
    }

    public void injectKeyPress(XKeycode xKeycode, int keysym) {
        InputEventSink sink = inputEventSink;
        if (sink != null) {
            try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
                sink.keyPress(xKeycode, keysym);
            }
            return;
        }
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyPress(xKeycode.getId(), keysym);
        }
    }

    public void injectKeyRelease(XKeycode xKeycode) {
        InputEventSink sink = inputEventSink;
        if (sink != null) {
            try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
                sink.keyRelease(xKeycode);
            }
            return;
        }
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyRelease(xKeycode.getId());
        }
    }

    private void registerExtension(Extension ext, int[] nextEventId, int[] nextErrorId) {
        if (ext.getNumEvents() > 0) {
            ext.setFirstEventId((byte) nextEventId[0]);
            nextEventId[0] += ext.getNumEvents();
        }
        if (ext.getNumErrors() > 0) {
            ext.setFirstErrorId((byte) nextErrorId[0]);
            nextErrorId[0] += ext.getNumErrors();
        }
        extensions.put(ext.getMajorOpcode(), ext);
    }

    private void setupExtensions() {
        int[] nextEventId = {64};
        int[] nextErrorId = {128};

        registerExtension(new BigReqExtension(),    nextEventId, nextErrorId);
        registerExtension(new MITSHMExtension(),    nextEventId, nextErrorId);
        registerExtension(new DRI3Extension(),      nextEventId, nextErrorId);
        registerExtension(new PresentExtension(),   nextEventId, nextErrorId);
        registerExtension(new SyncExtension(),      nextEventId, nextErrorId);
        if (mouseDragCompatibilityEnabled) {
            registerExtension(new GenericEventExtension(), nextEventId, nextErrorId);
        }
        registerExtension(new XInput2Extension(),   nextEventId, nextErrorId);
    }

    @SuppressWarnings("unchecked") // callers ask for the extension type registered under the opcode
    public <T extends Extension> T getExtension(int opcode) {
        return (T)extensions.get(opcode);
    }

    public synchronized void setGrabbed(boolean grabbed, XClient client) {
        this.isGrabbed = grabbed;
        this.grabbingClient = client;
    }

    public synchronized boolean isGrabbedBy(XClient client) {
        if (this.isGrabbed) {
            return this.grabbingClient == client;
        }
        return false;
    }

    public boolean isDesktopWindow(Window window) {
        return window != null && window.getParent() == windowManager.rootWindow && !window.attributes.isOverrideRedirect() && window.getClassName().contains("explorer.exe");
    }

    public ScreenInfo getEffectiveScreenInfo() {
        if (locks.get(Lockable.WINDOW_MANAGER).isHeldByCurrentThread()) effectiveScreenInfo = computeEffectiveScreenInfo();
        return effectiveScreenInfo;
    }

    private ScreenInfo computeEffectiveScreenInfo() {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER)) {
            for (Window child : windowManager.rootWindow.getChildren()) {
                if (!child.attributes.isMapped() || !isDesktopWindow(child)) continue;
                short width = child.getWidth(), height = child.getHeight();
                boolean fits = width >= 320 && height >= 200 && width <= screenInfo.width && height <= screenInfo.height;
                if (fits && (width < screenInfo.width || height < screenInfo.height)) return new ScreenInfo(width, height);
                break;
            }
        }
        return screenInfo;
    }
}
